package com.workdeck.app.web;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebStorage;
import android.webkit.WebView;

import com.workdeck.app.Account;
import com.workdeck.app.Accounts;
import com.workdeck.app.Prefs;
import com.workdeck.app.Site;
import com.workdeck.app.call.CallManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Process-wide owner of every account's Teams and Outlook tabs. Lives as long as the
 * process, independent of any activity, which is what lets the web apps keep running
 * (and notifying) after the UI is closed.
 */
public final class TabManager {
    private static final String TAG = "WorkDeck/Tabs";
    private static final long AUTO_REFRESH_AFTER_MS = 12L * 60 * 60 * 1000;
    private static TabManager sInstance;

    private final Context mApp;
    private final Map<String, BrowserTab> mTabs = new LinkedHashMap<>();
    private final Set<String> mProfilesReady = new HashSet<>();
    private final List<BrowserTab.Listener> mGlobalListeners = new ArrayList<>();
    private boolean mWebInitialized;
    private TabHost mHost;
    private boolean mUiVisible;
    private final android.os.Handler mMain = new android.os.Handler(android.os.Looper.getMainLooper());

    public static synchronized TabManager get(Context c) {
        if (sInstance == null) sInstance = new TabManager(c.getApplicationContext());
        return sInstance;
    }

    private TabManager(Context app) {
        mApp = app;
    }

    private void initWeb() {
        if (mWebInitialized) return;
        mWebInitialized = true;
        UserAgents.init(mApp);
        boolean debuggable = (mApp.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (debuggable || Prefs.get(mApp).webDebugging()) WebView.setWebContentsDebuggingEnabled(true);
    }

    /** Service-worker requests of each profile go through the notification shim. */
    private void initProfile(String profile) {
        if (!mProfilesReady.add(profile)) return;
        try {
            ServiceWorkerController swc = WebViewSupport.serviceWorkers(profile);
            if (swc == null) return;
            final CookieManager cookies = WebViewSupport.cookieManager(profile);
            swc.setServiceWorkerClient(new ServiceWorkerClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
                    return SwShim.maybeIntercept(mApp, request, cookies);
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "service worker client unavailable for " + profile, t);
        }
    }

    /** The tab for an account's site (object only; the WebView is created on start). */
    public BrowserTab tab(Account account, Site site) {
        if (account == null || !account.siteEnabled(site)) return null;
        initWeb();
        String key = account.key(site);
        BrowserTab t = mTabs.get(key);
        if (t == null) {
            initProfile(account.profile);
            t = new BrowserTab(mApp, account, site);
            if (mHost != null) t.setHost(mHost);
            for (BrowserTab.Listener l : mGlobalListeners) t.addListener(l);
            mTabs.put(key, t);
        }
        return t;
    }

    /** The tab of one of an account's opened links (object only; created on demand). */
    public BrowserTab webTab(Account account, Account.WebTab web) {
        if (account == null || web == null) return null;
        initWeb();
        String key = account.webKey(web.id);
        BrowserTab t = mTabs.get(key);
        if (t == null) {
            initProfile(account.profile);
            t = new BrowserTab(mApp, account, web);
            if (mHost != null) t.setHost(mHost);
            for (BrowserTab.Listener l : mGlobalListeners) t.addListener(l);
            mTabs.put(key, t);
        }
        return t;
    }

    /**
     * Opens a link as a new tab of the account (closing its oldest link tab if it has
     * too many). Microsoft pages start in desktop view like Teams/Outlook, others in
     * mobile view.
     */
    public BrowserTab openWebTab(Account account, String url) {
        while (account.webTabs().size() >= Account.MAX_WEB_TABS) {
            Account.WebTab oldest = account.oldestWebTab();
            if (oldest == null) break;
            closeWebTab(account, oldest.id);
        }
        Account.WebTab w = account.addWebTab(url, UrlRouter.isMicrosoft(url));
        Accounts.get(mApp).save();
        Log.i(TAG, account.id + ": opened link tab w" + w.id + " (" + UrlRouter.host(url) + ")");
        return webTab(account, w);
    }

    /** Closes one of an account's link tabs (the × in its drop-up). */
    public void closeWebTab(Account account, String webId) {
        BrowserTab t = mTabs.remove(account.webKey(webId));
        if (t != null) t.destroy();
        account.removeWebTab(webId);
        Accounts.get(mApp).save();
    }

    public List<BrowserTab> webTabsOf(Account a) {
        List<BrowserTab> out = new ArrayList<>();
        for (Account.WebTab w : a.webTabs()) out.add(webTab(a, w));
        return out;
    }

    /** Unloads link tabs nobody has looked at for a while (they reload when opened again). */
    public void trimIdleWebTabs(String visibleKey, long idleMs) {
        long now = SystemClock.elapsedRealtime();
        for (BrowserTab t : allTabs()) {
            if (!t.isWeb() || !t.isCreated() || t.key.equals(visibleKey)) continue;
            if (CallManager.get(mApp).inCall()) continue;
            if (now - Math.max(t.shownAt(), t.loadedAt()) > idleMs) {
                Log.i(TAG, "unloading idle link tab " + t.key);
                t.destroy();
            }
        }
    }

    /**
     * WorkDeck's screen became visible / invisible. While it's invisible every page is
     * kept awake (Android tells the shown page its window is gone; that is undone here,
     * and re-checked every minute), so messages keep arriving instantly.
     */
    public void setUiVisible(boolean visible) {
        mUiVisible = visible;
        mMain.removeCallbacks(mKeepAwake);
        if (!visible) mMain.postDelayed(mKeepAwake, 1200);
    }

    private final Runnable mKeepAwake = new Runnable() {
        @Override
        public void run() {
            for (BrowserTab t : mTabs.values()) {
                t.setDrawn(false);
                t.keepAwake();
            }
            if (!mUiVisible) mMain.postDelayed(this, 60000);
        }
    };

    /** The "Instant notifications" setting changed. */
    public void applyAwakeSetting() {
        for (BrowserTab t : mTabs.values()) t.applyAwakeSetting();
    }

    public BrowserTab existing(String key) {
        return key == null ? null : mTabs.get(key);
    }

    /** All tab objects that exist (created on demand). */
    public List<BrowserTab> allTabs() {
        return new ArrayList<>(mTabs.values());
    }

    public List<BrowserTab> tabsOf(Account a) {
        List<BrowserTab> out = new ArrayList<>();
        for (BrowserTab t : mTabs.values()) if (t.account.id.equals(a.id)) out.add(t);
        return out;
    }

    /** Listener added to every tab, including tabs created later (used by the service). */
    public void addGlobalListener(BrowserTab.Listener l) {
        if (mGlobalListeners.contains(l)) return;
        mGlobalListeners.add(l);
        for (BrowserTab t : mTabs.values()) t.addListener(l);
    }

    public void removeGlobalListener(BrowserTab.Listener l) {
        mGlobalListeners.remove(l);
        for (BrowserTab t : mTabs.values()) t.removeListener(l);
    }

    /**
     * Starts every tab that should be running: sites the user has opened before, for
     * accounts that keep running in the background. Removes tabs of deleted accounts
     * or disabled sites.
     */
    public void startBackground() {
        Accounts accounts = Accounts.get(mApp);
        Account active = accounts.active();
        for (Account a : accounts.list()) {
            for (Site s : Site.values()) {
                if (a.siteEnabled(s) && a.wasOpened(s) && (a.background || a == active)) tab(a, s).ensureStarted();
            }
        }
        for (BrowserTab t : allTabs()) {
            if (!stillValid(t)) {
                t.destroy();
                mTabs.remove(t.key);
            }
        }
    }

    private boolean stillValid(BrowserTab t) {
        Account a = Accounts.get(mApp).find(t.account.id);
        if (a == null) return false;
        return t.isWeb() ? a.webTab(t.web.id) != null : a.siteEnabled(t.site);
    }

    /** Removes the tabs of an account (or of one disabled site). */
    public void destroyTabsOf(Account a) {
        for (BrowserTab t : tabsOf(a)) {
            t.destroy();
            mTabs.remove(t.key);
        }
    }

    public void destroyTab(BrowserTab t) {
        t.destroy();
        mTabs.remove(t.key);
    }

    public void setHost(TabHost host) {
        mHost = host;
        for (BrowserTab t : mTabs.values()) t.setHost(host);
    }

    public int unreadOf(Account a) {
        int n = 0;
        for (BrowserTab t : tabsOf(a)) n += Math.max(0, t.unreadCount());
        return n;
    }

    /** Reloads every tab (e.g. after the network comes back) that is showing an error. */
    public void retryFailed() {
        for (BrowserTab t : mTabs.values()) {
            if (t.isCreated() && t.error() != null) t.reload();
        }
    }

    /** Long-running SPA tabs slowly bloat; refresh them while nobody is looking. */
    public void autoRefreshIfStale(boolean uiVisible) {
        if (uiVisible || !Prefs.get(mApp).autoRefresh() || CallManager.get(mApp).inCall()) return;
        long now = SystemClock.elapsedRealtime();
        for (BrowserTab t : mTabs.values()) {
            if (t.isCreated() && t.loadedAt() > 0 && now - t.loadedAt() > AUTO_REFRESH_AFTER_MS) {
                Log.i(TAG, "auto-refreshing " + t.key);
                t.reload();
            }
        }
    }

    public void refreshLayoutWidth() {
        for (BrowserTab t : mTabs.values()) t.refreshLayoutWidth();
    }

    public void flushCookies() {
        Set<String> done = new HashSet<>();
        for (BrowserTab t : mTabs.values()) {
            if (done.add(t.account.profile)) t.cookies().flush();
        }
        CookieManager.getInstance().flush();
    }

    public void destroyAll() {
        flushCookies();
        for (BrowserTab t : mTabs.values()) t.destroy();
        mTabs.clear();
    }

    /** Signs one account out: wipes its cookies, storage and cache, then reloads its tabs. */
    public void clearAccountData(final Account a, final Runnable done) {
        if (!a.usesDefaultProfile() && !WebViewSupport.supportsProfiles()) {
            // This account never had a profile of its own: there is nothing to clear, and
            // the fallback cookie jar belongs to the first account.
            if (done != null) done.run();
            return;
        }
        for (BrowserTab t : tabsOf(a)) {
            if (t.webView() != null) {
                t.webView().clearCache(true);
                t.webView().clearHistory();
            }
        }
        WebStorage storage = WebViewSupport.webStorage(a.profile);
        if (storage != null) storage.deleteAllData();
        final CookieManager cm = WebViewSupport.cookieManager(a.profile);
        cm.removeAllCookies(new ValueCallback<Boolean>() {
            @Override
            public void onReceiveValue(Boolean value) {
                cm.flush();
                for (BrowserTab t : tabsOf(a)) {
                    if (t.isCreated()) t.loadHome();
                }
                if (done != null) done.run();
            }
        });
    }

    /** Signs every account out. */
    public void clearAllData(final Runnable done) {
        final List<Account> all = Accounts.get(mApp).list();
        final int[] left = {all.size()};
        for (Account a : all) {
            clearAccountData(a, new Runnable() {
                @Override
                public void run() {
                    if (--left[0] == 0 && done != null) done.run();
                }
            });
        }
    }

    /** Deletes an account: its tabs, and its WebView profile with all its data. */
    public void deleteAccount(Account a) {
        destroyTabsOf(a);
        final String profile = a.profile;
        if (!WebViewSupport.DEFAULT_PROFILE.equals(profile)) {
            // The profile can only be deleted once no WebView uses it any more.
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!WebViewSupport.deleteProfile(profile)) Log.w(TAG, "profile " + profile + " kept");
                }
            }, 1500);
        } else {
            clearAccountData(a, null);
        }
        Accounts.get(mApp).remove(a);
    }

    public static boolean webViewAvailable(Context c) {
        try {
            if (Build.VERSION.SDK_INT >= 26) return WebView.getCurrentWebViewPackage() != null;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
