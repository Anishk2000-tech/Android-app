package com.workdeck.app.web;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.MutableContextWrapper;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.workdeck.app.Account;
import com.workdeck.app.Accounts;
import com.workdeck.app.Prefs;
import com.workdeck.app.R;
import com.workdeck.app.Site;
import com.workdeck.app.call.CallManager;
import com.workdeck.app.notify.Notifier;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One long-lived WebView for Teams or Outlook. The WebView is created with an
 * application-backed {@link MutableContextWrapper}, so it survives the activity
 * being destroyed (e.g. swiped away from Recents) and keeps receiving messages in
 * the background while the foreground service keeps the process alive.
 */
public final class BrowserTab {
    private static final String TAG = "WorkDeck/Tab";
    static final String BRIDGE_NAME = "WorkDeckBridge";
    private static final Pattern UNREAD = Pattern.compile("^\\s*\\((\\d+)\\+?\\)");
    private static String sScriptTemplate;

    public final Site site;
    public final Account account;
    /** Unique id of this tab: "accountId|site". */
    public final String key;
    private final Context mApp;
    private final Prefs mPrefs;
    private CookieManager mCookies;
    /** This account needs its own WebView profile but the installed WebView can't do that. */
    private boolean mProfileMissing;
    private final Handler mMain = new Handler(Looper.getMainLooper());

    private MutableContextWrapper mContext;
    private WebView mWebView;
    private WebViewSupport.ScriptHandle mScript;
    private boolean mDocStartSupported;
    private String mToken;
    private boolean mDesktop;
    private TabHost mHost;

    private int mProgress = 100;
    private String mTitle = "";
    private int mTitleUnread;
    private int mBadgeUnread = -1;
    private String mError;
    private long mLoadedAt;
    private boolean mStarted;

    private final List<Listener> mListeners = new ArrayList<>();

    public interface Listener {
        void onTabChanged(BrowserTab tab);
    }

    BrowserTab(Context app, Account account, Site site) {
        mApp = app.getApplicationContext();
        mPrefs = Prefs.get(mApp);
        this.account = account;
        this.site = site;
        this.key = account.key(site);
    }

    /** Cookie jar of this account's WebView profile. */
    public CookieManager cookies() {
        if (mCookies == null) mCookies = WebViewSupport.cookieManager(account.profile);
        return mCookies;
    }

    public boolean profileMissing() {
        return mProfileMissing;
    }

    /** "CRAFTWW · Teams" */
    public String label() {
        return account.name + " · " + mApp.getString(site.titleRes);
    }

    // ------------------------------------------------------------------ lifecycle

    public WebView webView() {
        return mWebView;
    }

    public boolean isCreated() {
        return mWebView != null;
    }

    /** Creates the WebView (if needed) and loads the home page once. */
    public void ensureStarted() {
        if (mWebView == null) create();
        if (!mStarted) {
            mStarted = true;
            loadHome();
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void create() {
        UserAgents.init(mApp);
        mContext = new MutableContextWrapper(mApp);
        Activity a = mHost == null ? null : mHost.activity();
        if (a != null) mContext.setBaseContext(a);
        WebView wv = new WebView(mContext);
        mWebView = wv;
        // Each account has its own profile (cookies, sign-in, storage). This must be the
        // very first thing done with a new WebView.
        mProfileMissing = !WebViewSupport.setProfile(wv, account.profile);
        mToken = newToken();
        mDesktop = account.isDesktop(site);

        applySettings(wv, mDesktop, account.textZoom(site), site);
        CookieManager cm = cookies();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(wv, true);
        if (Build.VERSION.SDK_INT >= 26) {
            // Keep the renderer alive and prioritized even when this tab is hidden.
            wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        }
        wv.setBackgroundColor(mApp.getColor(R.color.wd_bg));
        wv.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        wv.addJavascriptInterface(new JsBridge(this, mApp, mToken), BRIDGE_NAME);
        wv.setWebViewClient(new TabClient());
        wv.setWebChromeClient(new TabChromeClient());
        wv.setDownloadListener(new TabDownloads(wv));
        registerScript();
        layoutHeadless();
        Log.i(TAG, key + ": WebView created in profile " + account.profile + ", docStart=" + mDocStartSupported
                + (mProfileMissing ? ", PROFILE UNSUPPORTED" : ""));
    }

    /** Shared settings for tab WebViews and their popups. */
    @SuppressLint("SetJavaScriptEnabled")
    public static void applySettings(WebView wv, boolean desktop, int textZoom, Site site) {
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        //noinspection deprecation
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setTextZoom(textZoom);
        if (Build.VERSION.SDK_INT >= 26) s.setSafeBrowsingEnabled(true);
        // Teams always needs the desktop identity (it refuses mobile browsers);
        // Outlook's mobile view uses the real mobile site.
        boolean desktopIdentity = desktop || site == Site.TEAMS;
        s.setUserAgentString(desktopIdentity ? UserAgents.desktop() : UserAgents.mobile());
        WebViewSupport.setUserAgentMetadata(s, UserAgents.metadata(desktopIdentity));
    }

    private void registerScript() {
        if (mScript != null) {
            mScript.remove();
            mScript = null;
        }
        mScript = WebViewSupport.addDocumentStartScript(mWebView, buildScript(),
                UrlRouter.injectionOriginRules());
        mDocStartSupported = mScript != null;
    }

    String buildScript() {
        if (sScriptTemplate == null) sScriptTemplate = Assets.readText(mApp, "workdeck/inject.js");
        JSONObject cfg = new JSONObject();
        try {
            boolean desktopIdentity = mDesktop || site == Site.TEAMS;
            cfg.put("token", mToken);
            cfg.put("site", site.key);
            cfg.put("desktop", desktopIdentity);
            cfg.put("desktopLayout", mDesktop);
            cfg.put("desktopWidth", mPrefs.desktopWidth());
            cfg.put("notifications", true);
            cfg.put("fullVersion", UserAgents.chromeFull());
            cfg.put("androidVersion", Build.VERSION.RELEASE);
            cfg.put("model", Build.MODEL);
            cfg.put("debug", mPrefs.webDebugging());
            JSONArray brands = new JSONArray();
            for (String[] b : UserAgents.brands()) {
                brands.put(new JSONArray().put(b[0]).put(b[1]).put(b[2]));
            }
            cfg.put("brands", brands);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return sScriptTemplate.replace("__WORKDECK_CONFIG__", cfg.toString());
    }

    private static String newToken() {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** Give a detached WebView a real size so pages lay out sensibly in the background. */
    private void layoutHeadless() {
        if (mWebView == null || mWebView.getParent() != null) return;
        DisplayMetrics dm = mApp.getResources().getDisplayMetrics();
        int w = dm.widthPixels;
        int h = dm.heightPixels;
        mWebView.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        mWebView.layout(0, 0, w, h);
    }

    public void setHost(TabHost host) {
        mHost = host;
        if (mContext != null) {
            Activity a = host == null ? null : host.activity();
            mContext.setBaseContext(a != null ? a : mApp);
        }
    }

    public void attach(ViewGroup container) {
        if (mWebView == null) return;
        ViewGroup parent = (ViewGroup) mWebView.getParent();
        if (parent == container) return;
        if (parent != null) parent.removeView(mWebView);
        container.addView(mWebView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public void detach() {
        if (mWebView == null) return;
        ViewGroup parent = (ViewGroup) mWebView.getParent();
        if (parent != null) parent.removeView(mWebView);
        setHost(null);
        layoutHeadless();
        cookies().flush();
    }

    public void destroy() {
        if (mWebView == null) return;
        ViewGroup parent = (ViewGroup) mWebView.getParent();
        if (parent != null) parent.removeView(mWebView);
        if (mScript != null) {
            mScript.remove();
            mScript = null;
        }
        try {
            mWebView.stopLoading();
            mWebView.destroy();
        } catch (Throwable t) {
            Log.w(TAG, "destroy failed", t);
        }
        mWebView = null;
        mStarted = false;
        CallManager.get(mApp).resetPageMic(key);
    }

    /** The shared renderer died (usually low memory): rebuild this tab from scratch. */
    void recreateAfterCrash() {
        ViewGroup parent = mWebView == null ? null : (ViewGroup) mWebView.getParent();
        int index = parent == null ? -1 : parent.indexOfChild(mWebView);
        destroy();
        mError = null;
        create();
        if (parent != null) parent.addView(mWebView, index, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mStarted = true;
        loadHome();
        changed();
    }

    // ------------------------------------------------------------------ navigation

    public void loadHome() {
        if (mProfileMissing) {
            if (mWebView == null) create();
            mStarted = true;
            mWebView.loadDataWithBaseURL(null, Assets.readText(mApp, "workdeck/needs-update.html")
                    .replace("{ACCOUNT}", android.text.Html.escapeHtml(account.name)), "text/html", "utf-8", null);
            changed();
            return;
        }
        loadUrl(account.homeUrl(site));
    }

    public void loadUrl(String url) {
        if (mWebView == null) create();
        mStarted = true;
        mError = null;
        mWebView.loadUrl(url);
        changed();
    }

    public void reload() {
        if (mWebView == null) {
            ensureStarted();
            return;
        }
        mError = null;
        String url = mWebView.getUrl();
        if (url == null || url.isEmpty() || url.startsWith("about:") || url.startsWith("data:")) {
            loadHome();
        } else {
            mWebView.reload();
        }
        changed();
    }

    public boolean canGoBack() {
        return mWebView != null && mWebView.canGoBack();
    }

    public void goBack() {
        if (mWebView != null) mWebView.goBack();
    }

    public void evaluate(String js) {
        if (mWebView != null) mWebView.evaluateJavascript(js, null);
    }

    public void evaluate(String js, ValueCallback<String> cb) {
        if (mWebView != null) mWebView.evaluateJavascript(js, cb);
    }

    public String currentUrl() {
        return mWebView == null ? null : mWebView.getUrl();
    }

    // ------------------------------------------------------------------ view mode / zoom

    public boolean isDesktop() {
        return mDesktop;
    }

    public void setDesktop(boolean desktop) {
        account.setDesktop(site, desktop);
        Accounts.get(mApp).save();
        if (mWebView == null) return;
        mDesktop = desktop;
        Log.i(TAG, key + ": view mode -> " + (desktop ? "desktop" : "mobile"));
        applySettings(mWebView, mDesktop, account.textZoom(site), site);
        registerScript();
        // A fresh navigation (not reload) guarantees the new identity is used everywhere.
        String url = mWebView.getUrl();
        if (url != null && UrlRouter.isHttp(url) && !UrlRouter.isLogin(url)) {
            loadUrl(url);
        } else {
            loadHome();
        }
    }

    /** Re-applies the desktop page width preference (needs a reload to take effect). */
    public void refreshLayoutWidth() {
        if (mWebView == null) return;
        registerScript();
        if (mDesktop) reload();
    }

    public void zoomIn() {
        if (mWebView != null) mWebView.zoomIn();
    }

    public void zoomOut() {
        if (mWebView != null) mWebView.zoomOut();
    }

    /** Zoom all the way out, i.e. fit the page width to the screen. */
    public void zoomFit() {
        if (mWebView == null) return;
        try {
            // zoomBy() clamps to the minimum (fit-width) scale. Android rejects factors <= 0.01
            // despite its documentation, so use 0.02 (enough to reach the minimum from 5x).
            mWebView.zoomBy(0.02f);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "zoomFit failed", e);
        }
    }

    public int textZoom() {
        return account.textZoom(site);
    }

    public void setTextZoom(int percent) {
        percent = Math.max(50, Math.min(250, percent));
        account.setTextZoom(site, percent);
        Accounts.get(mApp).save();
        if (mWebView != null) mWebView.getSettings().setTextZoom(percent);
    }

    // ------------------------------------------------------------------ state

    public int progress() {
        return mProgress;
    }

    public String title() {
        return mTitle;
    }

    public String error() {
        return mError;
    }

    public long loadedAt() {
        return mLoadedAt;
    }

    public int unreadCount() {
        return mBadgeUnread >= 0 ? mBadgeUnread : mTitleUnread;
    }

    void setBadgeCount(int n) {
        if (n == mBadgeUnread) return;
        int before = unreadCount();
        mBadgeUnread = n < 0 ? -1 : n;
        unreadMaybeChanged(before);
        changed();
    }

    /** Unread count read from the page itself (Outlook's Inbox). */
    void setScrapedUnread(int n, boolean baseline) {
        if (baseline) Notifier.get(mApp).setUnreadBaseline(key, n);
        setBadgeCount(n);
    }

    private void unreadMaybeChanged(int before) {
        int after = unreadCount();
        if (after != before) Notifier.get(mApp).onUnreadChanged(this, after);
    }

    public void addListener(Listener l) {
        if (!mListeners.contains(l)) mListeners.add(l);
    }

    public void removeListener(Listener l) {
        mListeners.remove(l);
    }

    private void changed() {
        for (Listener l : new ArrayList<>(mListeners)) l.onTabChanged(this);
        if (mHost != null) mHost.onTabStateChanged(this);
    }

    void requestNotificationPermissionFromPage() {
        Activity a = mHost == null ? null : mHost.activity();
        if (Build.VERSION.SDK_INT >= 33 && a != null
                && a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            mHost.requestRuntimePermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, new Runnable() {
                @Override
                public void run() {
                    evaluate("window.__wdPermissionResult && window.__wdPermissionResult()");
                }
            });
        } else {
            evaluate("window.__wdPermissionResult && window.__wdPermissionResult()");
        }
    }

    /** The site says this browser engine is too old (outdated Android System WebView). */
    void onBrowserUnsupported() {
        Log.w(TAG, key + ": site reports unsupported browser (WebView " + UserAgents.chromeFull() + ")");
        if (mHost != null) mHost.onBrowserOutdated(UserAgents.chromeMajor());
    }

    // ------------------------------------------------------------------ routing

    public void openExternal(String url) {
        Context c = (mHost != null && mHost.activity() != null) ? mHost.activity() : mApp;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(mApp, R.string.no_app_for_link, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Shared navigation policy for tabs and popups. Returns true if the navigation
     * was handled (blocked, routed to the other tab, or opened externally).
     */
    public boolean handleNavigation(WebView view, String url, boolean userGesture, boolean isRedirect) {
        if (url == null) return false;
        if (UrlRouter.isHttp(url)) {
            Site target = UrlRouter.siteFor(url);
            if (target != null && target != site && mHost != null && account.siteEnabled(target)
                    && (userGesture || !isRedirect)) {
                mHost.switchToSite(this, target, url);
                return true;
            }
            String current = view.getUrl();
            boolean onAppPage = current != null && (UrlRouter.isTeams(current) || UrlRouter.isOutlook(current));
            if (!UrlRouter.isMicrosoft(url) && userGesture && !isRedirect && onAppPage) {
                openExternal(url);
                return true;
            }
            return false;
        }
        if (url.startsWith("about:") || url.startsWith("javascript:") || url.startsWith("data:")
                || url.startsWith("blob:")) {
            return false;
        }
        if (url.startsWith("msteams:")) {
            if (site != Site.TEAMS && mHost != null && account.siteEnabled(Site.TEAMS)) {
                mHost.switchToSite(this, Site.TEAMS, UrlRouter.msteamsToHttps(url));
            }
            return true; // never try to launch the Teams desktop/mobile app
        }
        if (url.startsWith("mailto:")) {
            if (account.siteEnabled(Site.OUTLOOK) && mHost != null) {
                mHost.switchToSite(this, Site.OUTLOOK, UrlRouter.mailtoToOutlook(url, account.homeUrl(Site.OUTLOOK)));
            } else {
                openExternal(url);
            }
            return true;
        }
        if (url.startsWith("intent:")) {
            try {
                Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                intent.addCategory(Intent.CATEGORY_BROWSABLE);
                intent.setComponent(null);
                intent.setSelector(null);
                Activity a = mHost == null ? null : mHost.activity();
                if (a != null) {
                    try {
                        a.startActivity(intent);
                        return true;
                    } catch (ActivityNotFoundException ignored) {
                    }
                }
                String fallback = intent.getStringExtra("browser_fallback_url");
                if (fallback != null && UrlRouter.isHttp(fallback)) view.loadUrl(fallback);
            } catch (Throwable t) {
                Log.w(TAG, "bad intent url", t);
            }
            return true;
        }
        if (url.startsWith("ms-") || url.startsWith("tel:") || url.startsWith("sms:") || url.startsWith("geo:")
                || url.startsWith("market:")) {
            if (userGesture) openExternal(url);
            return true;
        }
        return true;
    }

    // ------------------------------------------------------------------ popups

    /**
     * Creates the WebView for a window opened by the page (window.open / target=_blank).
     * Its first real URL decides what happens: Microsoft pages (sign-in, files) show
     * in an in-app overlay, Teams/Outlook links switch tabs, anything else opens in
     * the phone's browser.
     */
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    public WebView createPopupWebView(Activity activity) {
        WebView w = new WebView(activity);
        // Popups must live in the same profile (same sign-in) as the page that opened them.
        WebViewSupport.setProfile(w, account.profile);
        applySettings(w, mDesktop, account.textZoom(site), site);
        cookies().setAcceptThirdPartyCookies(w, true);
        w.addJavascriptInterface(new JsBridge(this, mApp, mToken), BRIDGE_NAME);
        WebViewSupport.addDocumentStartScript(w, buildScript(), UrlRouter.injectionOriginRules());
        w.setWebViewClient(new PopupClient());
        w.setWebChromeClient(new PopupChromeClient());
        w.setDownloadListener(new TabDownloads(w));
        w.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return w;
    }

    private final class PopupClient extends WebViewClient {
        private boolean mDecided;

        /** Returns true if the popup was closed/redirected elsewhere. */
        private boolean decide(WebView view, String url) {
            if (mDecided || url == null || url.isEmpty() || url.startsWith("about:")) return false;
            mDecided = true;
            if (mHost == null) return true;
            Site target = UrlRouter.siteFor(url);
            if (target != null && target != site && account.siteEnabled(target)) {
                mHost.closePopup(view);
                mHost.switchToSite(BrowserTab.this, target, url);
                return true;
            }
            if (!UrlRouter.isHttp(url)) {
                handleNavigation(view, url, true, false);
                mHost.closePopup(view);
                return true;
            }
            if (!UrlRouter.isMicrosoft(url)) {
                mHost.closePopup(view);
                openExternal(url);
                return true;
            }
            mHost.showPopup(view);
            return false;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (!request.isForMainFrame()) return false;
            String url = request.getUrl().toString();
            if (!mDecided) return decide(view, url);
            return handleNavigation(view, url, request.hasGesture(), request.isRedirect());
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (!mDecided && decide(view, url)) view.stopLoading();
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            return SwShim.maybeIntercept(mApp, request, cookies());
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            if (mHost != null) mHost.closePopup(view);
            return true;
        }
    }

    private final class PopupChromeClient extends TabChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            if (mHost != null) mHost.onPopupTitle(view, title);
        }
    }

    // ------------------------------------------------------------------ clients

    private final class TabClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (!request.isForMainFrame()) return false;
            return handleNavigation(view, request.getUrl().toString(), request.hasGesture(), request.isRedirect());
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            mLoadedAt = SystemClock.elapsedRealtime();
            mError = null;
            CallManager.get(mApp).resetPageMic(key);
            Notifier.get(mApp).resetUnreadBaseline(key);
            if (!mDocStartSupported && UrlRouter.isInjectable(url)) view.evaluateJavascript(buildScript(), null);
            changed();
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (!mDocStartSupported && UrlRouter.isInjectable(url)) view.evaluateJavascript(buildScript(), null);
            cookies().flush();
            if (site == Site.TEAMS && url != null && UrlRouter.isTeams(url)
                    && (url.contains("/dl/launcher") || url.contains("/l/meetup-join") || url.contains("/l/"))) {
                chooseWebAppOnLauncher(0);
            }
            changed();
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (!request.isForMainFrame()) return;
            int code = error.getErrorCode();
            if (code == ERROR_UNKNOWN || code == ERROR_UNSUPPORTED_SCHEME) return;
            mError = String.valueOf(error.getDescription());
            changed();
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            return SwShim.maybeIntercept(mApp, request, cookies());
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            Log.w(TAG, key + ": renderer gone, crashed=" + detail.didCrash());
            if (view != mWebView) {
                // A popup lost its renderer: close it.
                if (mHost != null) mHost.closePopup(view);
                return true;
            }
            mMain.post(new Runnable() {
                @Override
                public void run() {
                    recreateAfterCrash();
                }
            });
            return true;
        }
    }

    /** Teams' "open in app?" launcher page: pick "use the web app" automatically. */
    private void chooseWebAppOnLauncher(final int attempt) {
        if (attempt > 8 || mWebView == null) return;
        mMain.postDelayed(new Runnable() {
            @Override
            public void run() {
                evaluate("(window.__wdUseWebApp && window.__wdUseWebApp()) ? 1 : 0", new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        if (!"1".equals(value)) chooseWebAppOnLauncher(attempt + 1);
                    }
                });
            }
        }, 700);
    }

    private class TabChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            mProgress = newProgress;
            changed();
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            mTitle = title == null ? "" : title;
            Matcher m = UNREAD.matcher(mTitle);
            int before = unreadCount();
            mTitleUnread = m.find() ? Integer.parseInt(m.group(1)) : 0;
            unreadMaybeChanged(before);
            changed();
        }

        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            handlePermissionRequest(request);
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
            callback.invoke(origin, false, false);
        }

        @Override
        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                FileChooserParams fileChooserParams) {
            return mHost != null && mHost.showFileChooser(filePathCallback, fileChooserParams);
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
            return mHost != null && mHost.openPopup(BrowserTab.this, resultMsg, isUserGesture);
        }

        @Override
        public void onCloseWindow(WebView window) {
            if (mHost != null) mHost.closePopup(window);
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (mHost != null) mHost.showCustomView(view, callback);
            else callback.onCustomViewHidden();
        }

        @Override
        public void onHideCustomView() {
            if (mHost != null) mHost.hideCustomView();
        }

        @Override
        public Bitmap getDefaultVideoPoster() {
            // Avoid WebView's grey "play" placeholder on call video tiles.
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        }

        @Override
        public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
            return !mPrefs.webDebugging();
        }

        @Override
        public boolean onJsAlert(WebView view, String url, String message, final JsResult result) {
            Activity a = mHost == null ? null : mHost.activity();
            if (a == null) {
                result.confirm();
                return true;
            }
            new AlertDialog.Builder(a).setMessage(message)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            result.confirm();
                        }
                    })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override
                        public void onCancel(DialogInterface d) {
                            result.cancel();
                        }
                    }).show();
            return true;
        }

        @Override
        public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {
            Activity a = mHost == null ? null : mHost.activity();
            if (a == null) {
                result.cancel();
                return true;
            }
            new AlertDialog.Builder(a).setMessage(message)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            result.confirm();
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            result.cancel();
                        }
                    })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override
                        public void onCancel(DialogInterface d) {
                            result.cancel();
                        }
                    }).show();
            return true;
        }

        @Override
        public boolean onJsPrompt(WebView view, String url, String message, String defaultValue,
                final JsPromptResult result) {
            Activity a = mHost == null ? null : mHost.activity();
            if (a == null) {
                result.cancel();
                return true;
            }
            final EditText input = new EditText(a);
            input.setText(defaultValue);
            new AlertDialog.Builder(a).setMessage(message).setView(input)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            result.confirm(input.getText().toString());
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            result.cancel();
                        }
                    })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override
                        public void onCancel(DialogInterface d) {
                            result.cancel();
                        }
                    }).show();
            return true;
        }

        @Override
        public boolean onJsBeforeUnload(WebView view, String url, String message, JsResult result) {
            result.confirm();
            return true;
        }
    }

    /**
     * Grants microphone/camera to Microsoft pages (asking Android for the runtime
     * permission first if needed). Other origins get an explicit allow/deny prompt.
     */
    void handlePermissionRequest(final PermissionRequest request) {
        final String origin = String.valueOf(request.getOrigin());
        final List<String> resources = new ArrayList<>();
        final List<String> androidPerms = new ArrayList<>();
        boolean wantsMedia = false;
        for (String r : request.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) {
                resources.add(r);
                androidPerms.add(Manifest.permission.RECORD_AUDIO);
                wantsMedia = true;
            } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) {
                resources.add(r);
                androidPerms.add(Manifest.permission.CAMERA);
                wantsMedia = true;
            } else if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(r)) {
                resources.add(r);
            }
        }
        if (resources.isEmpty()) {
            request.deny();
            return;
        }
        final Runnable grant = new Runnable() {
            @Override
            public void run() {
                List<String> allowed = new ArrayList<>();
                for (String r : resources) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && !has(Manifest.permission.RECORD_AUDIO)) continue;
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && !has(Manifest.permission.CAMERA)) continue;
                    allowed.add(r);
                }
                if (allowed.isEmpty()) {
                    request.deny();
                    if (mHost != null) {
                        Toast.makeText(mApp, R.string.mic_permission_needed, Toast.LENGTH_LONG).show();
                    }
                    return;
                }
                request.grant(allowed.toArray(new String[0]));
                if (allowed.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                    CallManager.get(mApp).onMicPossiblyStarted();
                }
            }
        };
        final Runnable withAndroidPerms = new Runnable() {
            @Override
            public void run() {
                List<String> missing = new ArrayList<>();
                for (String p : androidPerms) if (!has(p)) missing.add(p);
                if (missing.isEmpty() || mHost == null) {
                    grant.run();
                } else {
                    mHost.requestRuntimePermissions(missing.toArray(new String[0]), grant);
                }
            }
        };
        if (UrlRouter.isMicrosoft(origin)) {
            withAndroidPerms.run();
            return;
        }
        Activity a = mHost == null ? null : mHost.activity();
        if (a == null || !wantsMedia) {
            request.deny();
            return;
        }
        new AlertDialog.Builder(a)
                .setTitle(R.string.site_permission_title)
                .setMessage(a.getString(R.string.site_permission_message, UrlRouter.host(origin)))
                .setPositiveButton(R.string.allow, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        withAndroidPerms.run();
                    }
                })
                .setNegativeButton(R.string.deny, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        request.deny();
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface d) {
                        request.deny();
                    }
                }).show();
    }

    private boolean has(String permission) {
        return mApp.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private final class TabDownloads implements DownloadListener {
        private final WebView mTarget;

        TabDownloads(WebView target) {
            mTarget = target;
        }

        @Override
        public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimetype,
                long contentLength) {
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                mTarget.evaluateJavascript("window.__wdDownload && window.__wdDownload(" + JSONObject.quote(url) + ","
                        + JSONObject.quote(mimetype == null ? "" : mimetype) + ")", null);
                Toast.makeText(mApp, R.string.download_preparing, Toast.LENGTH_SHORT).show();
            } else if (UrlRouter.isHttp(url)) {
                Downloads.enqueueHttp(mApp, url, userAgent, contentDisposition, mimetype, cookies());
            }
        }
    }
}
