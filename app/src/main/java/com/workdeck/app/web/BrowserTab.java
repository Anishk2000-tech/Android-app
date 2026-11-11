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
 * One long-lived WebView: an account's Teams or Outlook, or a link opened from them
 * (an extra "web" tab, {@link #site} == null). The WebView is created with an
 * application-backed {@link MutableContextWrapper}, so it survives the activity
 * being destroyed (e.g. swiped away from Recents) and keeps receiving messages in
 * the background while the foreground service keeps the process alive.
 */
public final class BrowserTab {
    private static final String TAG = "WorkDeck/Tab";
    static final String BRIDGE_NAME = "WorkDeckBridge";
    private static final Pattern UNREAD = Pattern.compile("^\\s*\\((\\d+)\\+?\\)");
    private static String sScriptTemplate;

    /** Teams or Outlook; null for an extra tab showing a link ({@link #web}). */
    public final Site site;
    public final Account.WebTab web;
    public final Account account;
    /** Unique id of this tab: "accountId|site" or "accountId|w<id>". */
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
    private long mShownAt;
    private boolean mStarted;
    /** The user can see this page right now (it is told it's "hidden" otherwise). */
    private boolean mDrawn;
    /** Off screen (detached) and kept awake. */
    private boolean mParked;

    private final List<Listener> mListeners = new ArrayList<>();

    public interface Listener {
        void onTabChanged(BrowserTab tab);
    }

    BrowserTab(Context app, Account account, Site site) {
        mApp = app.getApplicationContext();
        mPrefs = Prefs.get(mApp);
        this.account = account;
        this.site = site;
        this.web = null;
        this.key = account.key(site);
    }

    /** An extra tab for a link opened in this account. */
    BrowserTab(Context app, Account account, Account.WebTab web) {
        mApp = app.getApplicationContext();
        mPrefs = Prefs.get(mApp);
        this.account = account;
        this.site = null;
        this.web = web;
        this.key = account.webKey(web.id);
    }

    public boolean isWeb() {
        return web != null;
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
        return account.name + " · " + displayTitle();
    }

    /** "Teams", "Outlook", or the page title (host if it has none) of a link tab. */
    public String displayTitle() {
        if (site != null) return mApp.getString(site.titleRes);
        String t = !mTitle.isEmpty() ? mTitle : web.title;
        if (t == null || t.trim().isEmpty() || t.startsWith("http")) {
            String h = UrlRouter.host(currentUrl() != null ? currentUrl() : web.url);
            return h.startsWith("www.") ? h.substring(4) : h;
        }
        return t.trim();
    }

    /** Remembers when the tab was last on screen (idle link tabs are unloaded to save memory). */
    public void markShown() {
        mShownAt = SystemClock.elapsedRealtime();
    }

    public long shownAt() {
        return mShownAt;
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
        mDesktop = web != null ? web.desktop : account.isDesktop(site);

        applySettings(wv, desktopIdentity(), textZoom());
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
        // Created off screen (e.g. by the background service): park it awake from the start.
        park(TabManager.get(mApp).parkingLot(null));
        Log.i(TAG, key + ": WebView created in profile " + account.profile + ", docStart=" + mDocStartSupported
                + (mProfileMissing ? ", PROFILE UNSUPPORTED" : ""));
    }

    /** Shared settings for tab WebViews and their popups. */
    @SuppressLint("SetJavaScriptEnabled")
    public static void applySettings(WebView wv, boolean desktopIdentity, int textZoom) {
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
        // Computer (Windows) or phone identity: see desktopIdentity().
        s.setUserAgentString(desktopIdentity ? UserAgents.desktop() : UserAgents.mobile());
        WebViewSupport.setUserAgentMetadata(s, UserAgents.metadata(desktopIdentity));
    }

    /**
     * Present a computer (Windows) browser to sites? In desktop view always; Teams always
     * (it refuses phone browsers); and wherever the company's sign-in policy blocks phone
     * browsers (then mobile view only changes the layout).
     */
    boolean desktopIdentity() {
        if (mDesktop || site == Site.TEAMS) return true;
        return web != null ? web.computerIdentity : account.computerIdentity(site);
    }

    /** Mobile view is shown with a computer identity because the company blocks phones. */
    public boolean computerIdentityForced() {
        return web != null ? web.computerIdentity : account.computerIdentity(site);
    }

    private long mBlockedDialogAt;

    /**
     * The company's sign-in policy (Conditional Access) refused this browser, e.g. "You
     * can't get there from here - you must use Microsoft Edge". Many companies only block
     * phone browsers: then present a computer browser from now on (keeping the phone
     * layout) and load the page again. If a computer browser is refused too, explain.
     */
    void onAccessBlocked(String reason) {
        if (mWebView == null) return;
        if (!desktopIdentity()) {
            Log.w(TAG, key + ": company policy blocks the phone identity (" + reason + "); switching to computer identity");
            if (web != null) web.computerIdentity = true;
            else account.setComputerIdentity(site, true);
            Accounts.get(mApp).save();
            applySettings(mWebView, true, textZoom());
            registerScript();
            loadHome();
            if (mHost != null) mHost.onAccessBlocked(this, true);
            return;
        }
        Log.w(TAG, key + ": company policy blocks this browser (" + reason + ")");
        long now = SystemClock.elapsedRealtime();
        if (now - mBlockedDialogAt < 60000) return;
        mBlockedDialogAt = now;
        if (mHost != null) mHost.onAccessBlocked(this, false);
    }

    /** Opens a link in the Microsoft Edge app (company-managed browser), or its Play Store page. */
    public void openInEdge(String url) {
        Context c = (mHost != null && mHost.activity() != null) ? mHost.activity() : mApp;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.microsoft.emmx");
            if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (ActivityNotFoundException e) {
            openExternal("https://play.google.com/store/apps/details?id=com.microsoft.emmx");
        }
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
            boolean desktopIdentity = desktopIdentity();
            cfg.put("token", mToken);
            cfg.put("site", site == null ? "web" : site.key);
            cfg.put("desktop", desktopIdentity);
            cfg.put("desktopLayout", mDesktop);
            cfg.put("blockServiceWorker", serviceWorkerOff());
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
        mParked = false;
        mWebView.setTranslationX(0);
        ViewGroup parent = (ViewGroup) mWebView.getParent();
        if (parent == container) {
            ViewGroup.LayoutParams lp = mWebView.getLayoutParams();
            if (lp == null || lp.width != ViewGroup.LayoutParams.MATCH_PARENT) {
                mWebView.setLayoutParams(new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            return;
        }
        if (parent != null) parent.removeView(mWebView);
        container.addView(mWebView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** The activity is going away: keep the page alive (and awake) without it. */
    public void detach() {
        if (mWebView == null) return;
        park(TabManager.get(mApp).parkingLot(null));
        setHost(null);
        cookies().flush();
    }

    // ------------------------------------------------------------------ staying awake off screen

    /**
     * Takes the page off the screen (another tab is shown) without letting the browser
     * engine put it to sleep. The engine slows down pages it thinks are hidden - timers
     * end up running only once a minute - which delays Teams'/Outlook's live connection,
     * so new messages, mail and calls would arrive late. A detached WebView that the
     * engine still considers visible keeps full speed and costs no drawing.
     */
    public void park(ViewGroup lot) {
        if (mWebView == null) return;
        setDrawn(false);
        ViewGroup parent = (ViewGroup) mWebView.getParent();
        if (mParked && parent == lot) return;
        // Visibility changes only reach the engine while attached: make it visible first.
        if (parent != null && mWebView.getVisibility() != View.VISIBLE) mWebView.setVisibility(View.VISIBLE);
        if (lot == null) {
            // Nowhere to keep it attached: detached pages are slowed down by the engine.
            if (parent != null) parent.removeView(mWebView);
            mParked = true;
            layoutHeadless();
            keepAwake();
            return;
        }
        DisplayMetrics dm = mApp.getResources().getDisplayMetrics();
        if (parent != lot) {
            if (parent != null) parent.removeView(mWebView);
            // Same size as on screen (pages lay out the same), placed off the window so it's
            // never drawn: attached and "visible" for the engine, invisible for everyone.
            lot.addView(mWebView, new FrameLayout.LayoutParams(dm.widthPixels, dm.heightPixels));
        }
        mWebView.setTranslationX(-4f * Math.max(dm.widthPixels, 1));
        if (mWebView.hasFocus()) mWebView.clearFocus();
        mParked = true;
        keepAwake();
    }

    /** Tells the engine this page's window is visible (instant notifications), see {@link #park}. */
    public void keepAwake() {
        if (mWebView == null || !mPrefs.instantNotifications()) return;
        try {
            mWebView.dispatchWindowVisibilityChanged(View.VISIBLE);
        } catch (Throwable t) {
            Log.w(TAG, "keepAwake failed", t);
        }
    }

    /** "Instant notifications" was switched off: let off-screen pages sleep again. */
    void applyAwakeSetting() {
        if (mWebView == null) return;
        if (mPrefs.instantNotifications()) {
            if (mWebView.getParent() == null || !mDrawn) keepAwake();
        } else if (mWebView.getParent() == null) {
            mWebView.dispatchWindowVisibilityChanged(View.GONE);
        }
    }

    public boolean isDrawn() {
        return mDrawn;
    }

    /** Whether the user can see this page; the page reports itself hidden otherwise. */
    public void setDrawn(boolean drawn) {
        if (drawn == mDrawn) return;
        mDrawn = drawn;
        evaluate("window.__wdDrawn && window.__wdDrawn(" + drawn + ")");
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
        create(); // (parks the new WebView off screen)
        ViewGroup now = (ViewGroup) mWebView.getParent();
        if (parent != null && now != parent) {
            // It was on screen: put the rebuilt page back there.
            if (now != null) now.removeView(mWebView);
            mParked = false;
            parent.addView(mWebView, Math.min(Math.max(index, 0), parent.getChildCount()), new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            mWebView.setTranslationX(0);
        }
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
        loadUrl(web != null ? web.url : account.homeUrl(site));
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
        boolean failed = mError != null || ERROR_TITLE.matcher(String.valueOf(mWebView.getTitle())).find();
        mError = null;
        mLoadAgainAt = SystemClock.elapsedRealtime(); // a failure after this is a new one
        String url = mWebView.getUrl();
        if (url == null || url.isEmpty() || url.startsWith("about:") || url.startsWith("data:")
                || url.startsWith("chrome-error:")) {
            loadHome();
        } else if (failed && web == null) {
            // Reloading the engine's error page may just reload that page (older engines):
            // load the site's address again instead, and look again afterwards.
            loadAgain(UrlRouter.siteFor(url) == site ? url : mLastSiteUrl != null ? mLastSiteUrl : account.homeUrl(site));
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
        if (web != null) web.desktop = desktop;
        else account.setDesktop(site, desktop);
        Accounts.get(mApp).save();
        if (mWebView == null) return;
        mDesktop = desktop;
        Log.i(TAG, key + ": view mode -> " + (desktop ? "desktop" : "mobile"));
        applySettings(mWebView, desktopIdentity(), textZoom());
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
        return web != null ? web.textZoom : account.textZoom(site);
    }

    public void setTextZoom(int percent) {
        percent = Math.max(50, Math.min(250, percent));
        if (web != null) web.textZoom = percent;
        else account.setTextZoom(site, percent);
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
        if (web != null) return 0;
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

    // ------------------------------------------------------------------ attachments

    private boolean mComposeFocused;
    private long mComposeChangedAt;

    /** True while the user is typing in a message/mail box of this page. */
    public boolean composeFocused() {
        return mComposeFocused;
    }

    void setComposeFocused(boolean focused) {
        mComposeChangedAt = SystemClock.elapsedRealtime();
        if (focused == mComposeFocused) return;
        mComposeFocused = focused;
        changed();
    }

    public long composeChangedAt() {
        return mComposeChangedAt;
    }

    /** Hands phone files to the page's message box (like dropping them onto it). */
    public void attach(List<Attachments.Item> items) {
        if (mWebView == null || items.isEmpty()) return;
        Log.i(TAG, key + ": attaching " + items.size() + " file(s)");
        evaluate("window.__wdAttach ? (window.__wdAttach(" + JSONObject.quote(Attachments.json(items))
                + "), 'started') : 'missing'", new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        if (!"\"started\"".equals(value)) onAttachDone("unavailable", 0, false);
                    }
                });
    }

    /** Types text (shared from another app) into the page's message box. */
    public void insertText(String text) {
        evaluate("window.__wdInsertText && window.__wdInsertText(" + JSONObject.quote(text) + ")");
    }

    void onAttachDone(String method, int count, boolean seen) {
        Log.i(TAG, key + ": attach result " + method + " x" + count + (seen ? " (visible)" : ""));
        if (mHost != null) mHost.onAttachResult(this, method, count, seen);
    }

    // ------------------------------------------------------------------ routing

    /** A link clicked in this tab: a new tab of this account, or the phone's browser. */
    void openLink(String url) {
        if (mHost != null && mPrefs.linksInApp()) mHost.openLink(this, url);
        else openExternal(url);
    }

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
    private int mHealAttempts;
    private long mHealWindowStart;
    private long mErrorReportedAt;
    /** When the page was last loaded again (a recovery step, Reload or Retry). */
    private long mLoadAgainAt;

    /**
     * The failure was already reported (by the error callback or the page check) and
     * nothing has loaded the page again since: the same failure, already handled.
     */
    private boolean sameErrorAsReported() {
        return mErrorReportedAt > mLoadAgainAt && SystemClock.elapsedRealtime() - mErrorReportedAt < 3000;
    }
    private static final java.util.regex.Pattern CONNECTIVITY_ERROR = java.util.regex.Pattern.compile(
            "ERR_(INTERNET_DISCONNECTED|NAME_NOT_RESOLVED|NAME_RESOLUTION_FAILED|CONNECTION_[A-Z_]+|TIMED_OUT|"
                    + "NETWORK_[A-Z_]+|ADDRESS_[A-Z_]+|PROXY_[A-Z_]+|SSL_[A-Z_]+|CERT_[A-Z_]+|TUNNEL_[A-Z_]+)");

    /**
     * Some failures (e.g. a site's service worker failing its page) show the engine's own
     * "Webpage not available" page without any error callback, and the address the engine
     * reports for it may be its own (chrome-error://...) instead of the site's. So whenever
     * the tab's page has loaded (page finished, progress 100 %, or a few seconds after it
     * started), look whether it is that error page and, if so, recover the same way
     * (reload, clear the site's storage, ...) or show WorkDeck's error screen with Retry.
     */
    private String mLastSiteUrl;
    private long mErrorCheckAt;
    private int mLoadSeq;
    private int mBusyChecks;
    private static final java.util.regex.Pattern ERROR_TITLE = java.util.regex.Pattern.compile(
            "(?i)webpage not available|page not available|net::ERR_");
    private static final java.util.regex.Pattern NET_ERROR = java.util.regex.Pattern.compile("net::ERR_[A-Z_]+");

    /** Checks soon whether the tab's page is the engine's error page (debounced). */
    private void scheduleErrorCheck(final WebView view, final String url, long delayMs) {
        if (view != mWebView || web != null) return;
        final int seq = mLoadSeq;
        mMain.postDelayed(new Runnable() {
            @Override
            public void run() {
                // Only for the page being loaded now, and not while the error screen is up.
                if (view != mWebView || mWebView == null || seq != mLoadSeq || mError != null) return;
                if (SystemClock.elapsedRealtime() - mErrorCheckAt < 800) return;
                mErrorCheckAt = SystemClock.elapsedRealtime();
                checkErrorPage(view, url != null ? url : view.getUrl());
            }
        }, delayMs);
    }

    private void checkErrorPage(final WebView view, final String url) {
        if (sameErrorAsReported()) return;
        // Just loaded again: the old error page may still be on screen; the later checks look.
        if (SystemClock.elapsedRealtime() - mLoadAgainAt < 1000) return;
        // No page yet, or another site's real page (the sign-in page, a link): not an error page.
        if (url == null || url.startsWith("about:")) return;
        if (UrlRouter.isHttp(url) && UrlRouter.siteFor(url) != site) return;
        final boolean errorAddress = url.startsWith("chrome-error:") || url.startsWith("data:text/html,chromewebdata");
        // Still loading (a slow network or a slow page; the last error page stays on screen
        // until the new page arrives): not a failure - look again in a moment (for a minute
        // at most, then judge what's on screen).
        final int progress = view.getProgress();
        if (progress < 100 && mBusyChecks < 20) {
            mBusyChecks++;
            scheduleErrorCheck(view, null, 3000);
            return;
        }
        mBusyChecks = 0;
        final String target = UrlRouter.siteFor(url) == site ? url
                : mLastSiteUrl != null ? mLastSiteUrl : account.homeUrl(site);
        final String title = view.getTitle();
        view.evaluateJavascript("(function () { try { return location.href + '|' + document.title + '|' + "
                + "(document.body ? document.body.innerText.slice(0, 600) : ''); } catch (e) { return 'x|' + e; } })()",
                new android.webkit.ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String v) {
                        if (view != mWebView) return;
                        boolean noAnswer = v == null || v.isEmpty() || "null".equals(v);
                        boolean errorDoc = !noAnswer && (v.startsWith("\"chrome-error:")
                                || v.startsWith("\"data:text/html,chromewebdata"));
                        boolean errorTitle = title != null && ERROR_TITLE.matcher(title).find();
                        // No answer from the page: the error page if the engine gave its error
                        // address or its error title.
                        boolean isError = errorDoc || (noAnswer && (errorAddress || errorTitle));
                        if (isError || errorTitle || !UrlRouter.isHttp(url)) {
                            Log.i(TAG, key + ": page check " + url + " (" + progress + "%) title=" + title + " -> "
                                    + (v == null ? "null" : v.substring(0, Math.min(100, v.length()))));
                        }
                        if (!isError) {
                            // The site's own page is there again: a later failure starts over.
                            if (mHealAttempts > 0 && v != null && v.startsWith("\"https://" + UrlRouter.host(target))) {
                                Log.i(TAG, key + ": the page loads again (after " + mHealAttempts + " recovery step(s))");
                                com.workdeck.app.EventLog.add(mApp, key + ": the page loads again (after " + mHealAttempts + " recovery step(s))");
                                mHealAttempts = 0;
                            }
                            return;
                        }
                        java.util.regex.Matcher m = NET_ERROR.matcher(v == null ? "" : v);
                        String desc = m.find() ? m.group() : "net::ERR_FAILED";
                        if (desc.contains("ERR_ABORTED")) return;
                        if (sameErrorAsReported()) return;
                        mErrorReportedAt = SystemClock.elapsedRealtime();
                        Log.w(TAG, key + ": the page shows the engine's error page (" + desc + ")");
                        if (selfHeal(view, target, desc)) return;
                        mError = desc;
                        com.workdeck.app.EventLog.add(mApp, key + ": error screen shown (" + desc + ") for " + shortUrl(target));
                        changed();
                    }
                });
    }

    /** Service workers turned off for this tab's site (its worker kept breaking the page). */
    private boolean serviceWorkerOff() {
        return web == null && mPrefs.raw().getBoolean("sw_off_" + key, false);
    }

    /**
     * Removes the site's service worker, caches and stored files (cookies, i.e. the sign-in,
     * stay). The engine's storage API leaves service workers and their caches alone, so the
     * worker is replaced at its next update (which loading the page triggers) by one that
     * deletes its caches and unregisters itself.
     */
    private void clearSiteStorage(String url) {
        try {
            android.net.Uri u = android.net.Uri.parse(url);
            String origin = u.getScheme() + "://" + u.getHost();
            SwShim.removeOnce(account.profile, UrlRouter.host(url));
            android.webkit.WebStorage ws = WebViewSupport.webStorage(account.profile);
            if (ws != null) ws.deleteOrigin(origin);
            Log.w(TAG, key + ": removed " + origin + "'s service worker and stored files; loading again");
            com.workdeck.app.EventLog.add(mApp, key + ": removed " + origin + "'s service worker and stored files");
        } catch (Throwable t) {
            Log.w(TAG, "could not clear the site's storage", t);
        }
    }

    /**
     * The site's own page failed with a generic error (net::ERR_FAILED) - typically the
     * site's service worker failing to serve its page (seen with Teams right after a fresh
     * sign-in). Recover on our own: reload once; if it fails again, remove the site's
     * service worker and cached files (the sign-in cookies stay) and load it again. Only
     * then is the error shown (with Retry). Returns true while recovering.
     */
    private void loadAgainSoon(final String url) {
        mMain.postDelayed(new Runnable() {
            @Override
            public void run() {
                loadAgain(url);
            }
        }, 1500);
    }

    private int mLoadAgainGen;

    /**
     * Loads the site's page again (a recovery step, Reload or Retry) and looks again a little
     * later whether it is the engine's error page: newer engines report neither "started",
     * "finished", progress nor an error for that page, so nothing else would notice.
     */
    private void loadAgain(String url) {
        if (mWebView == null) return;
        mLoadAgainAt = SystemClock.elapsedRealtime();
        mBusyChecks = 0;
        final int gen = ++mLoadAgainGen;
        Log.i(TAG, key + ": loading again " + url);
        mWebView.loadUrl(url);
        if (web != null) return;
        for (long delay : new long[] {3000, 8000}) {
            mMain.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (gen != mLoadAgainGen || mWebView == null || mError != null) return;
                    mErrorCheckAt = SystemClock.elapsedRealtime();
                    checkErrorPage(mWebView, mWebView.getUrl());
                }
            }, delay);
        }
    }

    private boolean selfHeal(WebView view, String url, String desc) {
        if (view != mWebView || web != null || UrlRouter.siteFor(url) != site) return false;
        // Network trouble (offline, no DNS, timeouts, certificates) is shown as it is and
        // retried when the network is back - clearing the site's data wouldn't help.
        if (CONNECTIVITY_ERROR.matcher(desc).find()) return false;
        long now = SystemClock.elapsedRealtime();
        if (now - mHealWindowStart > 120000) {
            mHealWindowStart = now;
            mHealAttempts = 0;
        }
        mHealAttempts++;
        Log.w(TAG, key + ": " + desc + " loading " + url + " (attempt " + mHealAttempts + ")");
        com.workdeck.app.EventLog.add(mApp, key + ": " + desc + " loading " + shortUrl(url) + " (attempt " + mHealAttempts + ")");
        final String target = url;
        // 1: load again. 2: remove the site's service worker and stored files, load again
        // (that load may still go through the old worker, it triggers its removal). 3: load
        // again. 4: still failing: service workers off for this site here (notifications
        // don't need them in WorkDeck), load again. Then WorkDeck's error screen (Retry).
        if (mHealAttempts == 1 || mHealAttempts == 3) {
            loadAgainSoon(target);
            return true;
        }
        if (mHealAttempts == 2) {
            clearSiteStorage(url);
            loadAgainSoon(target);
            return true;
        }
        if (mHealAttempts == 4 && !serviceWorkerOff()) {
            mPrefs.raw().edit().putBoolean("sw_off_" + key, true).apply();
            SwShim.keepOff(mApp, account.profile, UrlRouter.host(url));
            Log.w(TAG, key + ": service workers off for this site; loading again");
            com.workdeck.app.EventLog.add(mApp, key + ": service workers off for this site; loading again");
            clearSiteStorage(url);
            registerScript();
            loadAgainSoon(target);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ sign-outs nobody asked for

    private String mLoginLogged;
    private volatile long mSignOutAllowedAt;
    private volatile long mSignOutReloadAt;
    private volatile long mSignOutLoggedAt;
    private volatile String mSignOutLogged;
    private volatile SignOutGuard.Action mSignOutLoggedAction;
    private boolean mSignOutReloadPending;

    private String siteName() {
        return web != null ? "the page" : site == Site.TEAMS ? "Teams" : "Outlook";
    }

    private static String touchText(long sinceTouch) {
        String ago = SignOutGuard.ago(sinceTouch);
        return "never".equals(ago) ? "not touched since the app started" : "last touch " + ago + " ago";
    }

    private static long since(long at) {
        return at == 0 ? Long.MAX_VALUE / 4 : SystemClock.elapsedRealtime() - at;
    }

    /** Host and path of an address - never its parameters (they can hold account details). */
    private static String shortUrl(String url) {
        return UrlRouter.host(url) + UrlRouter.path(url);
    }

    /**
     * The page wants to load a sign-out address (see {@link SignOutGuard}). Returns true if
     * that must not happen: the user didn't ask for it. Then the site is loaded again
     * instead (it opens signed in, the sign-in was never ended). Called from the main
     * thread (navigations) and from the engine's network thread (requests of any frame).
     */
    private boolean refuseSignOut(String url, boolean mainFrame) {
        if (!mPrefs.raw().getBoolean("keep_signed_in", true)) return false;
        long sinceTouch = since(com.workdeck.app.UiState.lastTouchAt);
        // On screen: this tab is the one shown. (Not "the user is looking": a dialog of the
        // page, e.g. "Sign out?", takes the window's focus for a moment.)
        boolean onScreen = com.workdeck.app.UiState.activityVisible && key.equals(com.workdeck.app.UiState.visibleTab);
        SignOutGuard.Action action = SignOutGuard.decide(onScreen, sinceTouch,
                since(mSignOutAllowedAt), since(mSignOutReloadAt));
        String what = shortUrl(url);
        // One sign-out is several requests (the page, its redirects, frames): one diary line.
        // A page that keeps trying is noted every few minutes, not every time.
        // (Trying again right after the reload is always noted: it says the reload didn't help.)
        long quiet = action == SignOutGuard.Action.REFUSE ? 5 * 60_000 : 5000;
        boolean log = !what.equals(mSignOutLogged) || action != mSignOutLoggedAction || since(mSignOutLoggedAt) > quiet;
        if (log) {
            mSignOutLogged = what;
            mSignOutLoggedAction = action;
            mSignOutLoggedAt = SystemClock.elapsedRealtime();
        }
        if (action == SignOutGuard.Action.ALLOW) {
            if (since(mSignOutAllowedAt) > SignOutGuard.FOLLOW_UP_MS) {
                com.workdeck.app.EventLog.add(mApp, key + ": sign-out by the user (" + touchText(sinceTouch) + "): " + what);
            }
            if (onScreen && sinceTouch < SignOutGuard.USER_WINDOW_MS) {
                mSignOutAllowedAt = SystemClock.elapsedRealtime();
            }
            return false;
        }
        if (log) {
            com.workdeck.app.EventLog.add(mApp, key + ": the page tried to sign out by itself ("
                    + (mainFrame ? "page" : "in the background") + "; " + touchText(sinceTouch)
                    + ", " + (onScreen ? "on screen" : "not on screen")
                    + "): " + what + " - not carried out"
                    + (action == SignOutGuard.Action.REFUSE_AND_RELOAD ? ", loading " + siteName() + " again"
                    : ", loaded again only " + SignOutGuard.ago(since(mSignOutReloadAt)) + " ago"));
        }
        final long delay = action == SignOutGuard.Action.REFUSE_AND_RELOAD ? 1500
                : Math.max(1500, SignOutGuard.RELOAD_EVERY_MS - since(mSignOutReloadAt));
        if (action == SignOutGuard.Action.REFUSE_AND_RELOAD) mSignOutReloadAt = SystemClock.elapsedRealtime();
        mMain.post(new Runnable() {
            @Override
            public void run() {
                reloadAfterRefusedSignOut(delay);
            }
        });
        return true;
    }

    /** Loads the tab's own page again after a refused sign-out (one pending reload at a time). */
    private void reloadAfterRefusedSignOut(long delayMs) {
        if (mSignOutReloadPending) return;
        mSignOutReloadPending = true;
        mMain.postDelayed(new Runnable() {
            @Override
            public void run() {
                mSignOutReloadPending = false;
                if (mWebView == null) return;
                mSignOutReloadAt = SystemClock.elapsedRealtime();
                mError = null;
                if (web != null) {
                    mWebView.loadUrl(web.url);
                } else {
                    Log.i(TAG, key + ": loading again after a refused sign-out");
                    mWebView.loadUrl(account.homeUrl(site));
                }
                changed();
            }
        }, delayMs);
    }

    /** What a refused request gets instead: nothing (the sign-out address is never contacted). */
    private static WebResourceResponse emptyResponse() {
        return new WebResourceResponse("text/html", "utf-8", new java.io.ByteArrayInputStream(new byte[0]));
    }

    /**
     * The tab's page is Microsoft's "You signed out of your account" page (a sign-out that
     * was carried out: the user's own). There is nothing to tap on it, so go back to the
     * site, which shows its sign-in page - after the page had time to sign the account out
     * of Microsoft's other apps too.
     */
    private void leaveSignedOutPage(final WebView view) {
        mMain.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (view != mWebView || mWebView == null || web != null) return;
                if (!UrlRouter.isSignOut(mWebView.getUrl())) return;
                // (After a refused sign-out an empty page can stand at that address for a
                // moment; then nothing was signed out and the reload is already on its way.)
                if (since(mSignOutAllowedAt) < 5 * 60_000 || !mPrefs.raw().getBoolean("keep_signed_in", true)) {
                    com.workdeck.app.EventLog.add(mApp, key + ": signed out; back to " + siteName() + " (sign-in page)");
                }
                mSignOutAllowedAt = 0;
                mWebView.loadUrl(account.homeUrl(site));
            }
        }, 5000);
    }

    /**
     * Can a link for the account's other site be handed over? WorkDeck: when the account
     * has that site. Single-app builds: always (the account's other app opens it, or -
     * if that isn't installed - it opens here as a link tab).
     */
    private boolean canRouteTo(Site target) {
        return account.siteEnabled(target) || com.workdeck.app.Edition.solo(mApp);
    }

    public boolean handleNavigation(WebView view, String url, boolean userGesture, boolean isRedirect) {
        if (url == null) return false;
        if (UrlRouter.isSignOut(url) && refuseSignOut(url, true)) return true;
        if (UrlRouter.isHttp(url)) {
            Site target = UrlRouter.siteFor(url);
            if (target != null && target != site && mHost != null && canRouteTo(target)
                    && (userGesture || !isRedirect)) {
                mHost.switchToSite(this, target, url);
                return true;
            }
            if (web != null) return false; // a link tab browses freely
            String current = view.getUrl();
            boolean onAppPage = current != null && (UrlRouter.isTeams(current) || UrlRouter.isOutlook(current));
            if (!UrlRouter.isMicrosoft(url) && userGesture && !isRedirect && onAppPage) {
                openLink(url);
                return true;
            }
            return false;
        }
        if (url.startsWith("about:") || url.startsWith("javascript:") || url.startsWith("data:")
                || url.startsWith("blob:")) {
            return false;
        }
        if (url.startsWith("msteams:")) {
            if (site != Site.TEAMS && mHost != null && canRouteTo(Site.TEAMS)) {
                mHost.switchToSite(this, Site.TEAMS, UrlRouter.msteamsToHttps(url));
            }
            return true; // never try to launch the Teams desktop/mobile app
        }
        if (url.startsWith("mailto:")) {
            if (canRouteTo(Site.OUTLOOK) && mHost != null) {
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
        if (url.startsWith("microsoft-edge:")) {
            // "Launch in Edge" on a company-policy page.
            String target = url.substring("microsoft-edge:".length());
            if (userGesture) openInEdge(UrlRouter.isHttp(target) ? target : currentUrl() != null ? currentUrl() : target);
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
        applySettings(w, desktopIdentity(), textZoom());
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
            if (target != null && target != site && canRouteTo(target)) {
                mHost.closePopup(view);
                mHost.switchToSite(BrowserTab.this, target, url);
                return true;
            }
            if (!UrlRouter.isHttp(url)) {
                handleNavigation(view, url, true, false);
                mHost.closePopup(view);
                return true;
            }
            if (mPrefs.linksInApp()) {
                // Sign-in windows and the app's own pop-outs (Teams -> Teams, Outlook ->
                // Outlook) must stay attached to the page that opened them; every other
                // link becomes a tab of this account (same sign-in), named after the page.
                if (UrlRouter.looksLikeAuth(url) || (target != null && target == site)) {
                    mHost.showPopup(view);
                    return false;
                }
                mHost.closePopup(view);
                mHost.openLink(BrowserTab.this, url);
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
            String u = request.getUrl().toString();
            if (UrlRouter.isSignOut(u) && refuseSignOut(u, request.isForMainFrame())) return emptyResponse();
            return SwShim.maybeIntercept(mApp, account.profile, request, cookies());
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
            if (view == mWebView && web == null) {
                mLoadSeq++;
                if (UrlRouter.siteFor(url) == site) mLastSiteUrl = url;
                if (!UrlRouter.isHttp(url) || UrlRouter.siteFor(url) == site) Log.i(TAG, key + ": page started " + url);
                scheduleErrorCheck(view, url, 6000); // in case no "finished" comes
            }
            CallManager.get(mApp).resetPageMic(key);
            Notifier.get(mApp).resetUnreadBaseline(key);
            if (!mDocStartSupported && UrlRouter.isInjectable(url)) view.evaluateJavascript(buildScript(), null);
            changed();
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (!mDocStartSupported && UrlRouter.isInjectable(url)) view.evaluateJavascript(buildScript(), null);
            cookies().flush();
            if (web != null && UrlRouter.isHttp(url) && !UrlRouter.isLogin(url) && !url.equals(web.url)) {
                web.url = url; // reopen where the user left off
                Accounts.get(mApp).save();
            }
            if (site == Site.TEAMS && url != null && UrlRouter.isTeams(url)
                    && (url.contains("/dl/launcher") || url.contains("/l/meetup-join") || url.contains("/l/"))) {
                chooseWebAppOnLauncher(0);
            }
            if (view == mWebView && web == null) {
                if (!UrlRouter.isHttp(url) || UrlRouter.siteFor(url) == site) Log.i(TAG, key + ": page finished " + url);
                scheduleErrorCheck(view, url, 0);
                if (UrlRouter.isSignOut(url)) {
                    leaveSignedOutPage(view);
                } else if (UrlRouter.isLogin(url) && !shortUrl(url).equals(mLoginLogged)) {
                    // The sign-in page in the site's own tab: the sign-in ended (or never was).
                    mLoginLogged = shortUrl(url);
                    com.workdeck.app.EventLog.add(mApp, key + ": sign-in page shown: " + mLoginLogged + " ("
                            + touchText(since(com.workdeck.app.UiState.lastTouchAt)) + ")");
                } else if (UrlRouter.siteFor(url) == site && mLoginLogged != null) {
                    mLoginLogged = null;
                    com.workdeck.app.EventLog.add(mApp, key + ": signed in, " + siteName() + " loaded");
                }
            }
            changed();
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (!request.isForMainFrame()) return;
            int code = error.getErrorCode();
            if (code == ERROR_UNSUPPORTED_SCHEME) return;
            String desc = String.valueOf(error.getDescription());
            if (code == ERROR_UNKNOWN) {
                // Cancelled navigations (downloads, app links, a newer navigation) are not errors.
                if (desc.contains("ERR_ABORTED") || !desc.contains("ERR_")) return;
                mErrorReportedAt = SystemClock.elapsedRealtime();
                if (selfHeal(view, request.getUrl().toString(), desc)) return;
            }
            mErrorReportedAt = SystemClock.elapsedRealtime();
            mError = desc;
            if (view == mWebView) {
                com.workdeck.app.EventLog.add(mApp, key + ": error screen shown (" + desc + ") for "
                        + shortUrl(request.getUrl().toString()));
            }
            changed();
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            String u = request.getUrl().toString();
            if (UrlRouter.isSignOut(u) && refuseSignOut(u, request.isForMainFrame())) return emptyResponse();
            return SwShim.maybeIntercept(mApp, account.profile, request, cookies());
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            Log.w(TAG, key + ": renderer gone, crashed=" + detail.didCrash());
            com.workdeck.app.EventLog.add(mApp, key + ": the page's process ended (" + (detail.didCrash() ? "crashed" : "ended by Android") + ")");
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
            if (newProgress == 100 && mProgress != 100) scheduleErrorCheck(view, null, 300);
            mProgress = newProgress;
            changed();
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            mTitle = title == null ? "" : title;
            if (web != null) {
                if (!mTitle.isEmpty() && !mTitle.equals(web.title) && !mTitle.startsWith("http")) {
                    web.title = mTitle;
                    Accounts.get(mApp).save();
                }
                changed();
                return;
            }
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
