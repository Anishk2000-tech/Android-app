package com.workdeck.app.web;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebStorage;
import android.webkit.WebView;

import com.workdeck.app.Prefs;
import com.workdeck.app.Site;
import com.workdeck.app.call.CallManager;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Process-wide owner of the Teams and Outlook tabs. Lives as long as the process,
 * independent of any activity, which is what lets the web apps keep running (and
 * notifying) after the UI is closed.
 */
public final class TabManager {
    private static final String TAG = "WorkDeck/Tabs";
    private static final long AUTO_REFRESH_AFTER_MS = 12L * 60 * 60 * 1000;
    private static TabManager sInstance;

    private final Context mApp;
    private final Map<Site, BrowserTab> mTabs = new EnumMap<>(Site.class);
    private boolean mWebInitialized;

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
        try {
            ServiceWorkerController.getInstance().setServiceWorkerClient(new ServiceWorkerClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
                    return SwShim.maybeIntercept(mApp, request);
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "service worker client unavailable", t);
        }
    }

    /** Returns the tab for an enabled site, creating the object (not the WebView) lazily. */
    public BrowserTab tab(Site site) {
        if (!Prefs.get(mApp).siteEnabled(site)) return null;
        initWeb();
        BrowserTab t = mTabs.get(site);
        if (t == null) {
            t = new BrowserTab(mApp, site);
            mTabs.put(site, t);
        }
        return t;
    }

    public BrowserTab existing(Site site) {
        return mTabs.get(site);
    }

    public List<BrowserTab> enabledTabs() {
        List<BrowserTab> out = new ArrayList<>();
        for (Site s : Site.values()) {
            BrowserTab t = tab(s);
            if (t != null) out.add(t);
        }
        return out;
    }

    /** Make sure every enabled site is loaded (used by the activity and the background service). */
    public void startAll() {
        for (Site s : Site.values()) {
            BrowserTab t = tab(s);
            if (t != null) {
                t.ensureStarted();
            } else {
                BrowserTab old = mTabs.remove(s);
                if (old != null) old.destroy();
            }
        }
    }

    public void setHost(TabHost host) {
        for (BrowserTab t : mTabs.values()) t.setHost(host);
    }

    public int totalUnread() {
        int n = 0;
        for (BrowserTab t : mTabs.values()) n += Math.max(0, t.unreadCount());
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
                Log.i(TAG, "auto-refreshing " + t.site.key);
                t.reload();
            }
        }
    }

    public void refreshLayoutWidth() {
        for (BrowserTab t : mTabs.values()) t.refreshLayoutWidth();
    }

    public void destroyAll() {
        for (BrowserTab t : mTabs.values()) t.destroy();
        mTabs.clear();
        CookieManager.getInstance().flush();
    }

    /** Signs out of everything: wipes cookies, storage and cache, then reloads. */
    public void clearAllData(final Runnable done) {
        for (BrowserTab t : mTabs.values()) {
            if (t.webView() != null) {
                t.webView().clearCache(true);
                t.webView().clearHistory();
            }
        }
        WebStorage.getInstance().deleteAllData();
        CookieManager.getInstance().removeAllCookies(new android.webkit.ValueCallback<Boolean>() {
            @Override
            public void onReceiveValue(Boolean value) {
                CookieManager.getInstance().flush();
                for (BrowserTab t : mTabs.values()) {
                    if (t.isCreated()) t.loadHome();
                }
                if (done != null) done.run();
            }
        });
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
