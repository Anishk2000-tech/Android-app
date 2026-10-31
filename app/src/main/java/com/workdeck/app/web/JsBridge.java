package com.workdeck.app.web;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;

import com.workdeck.app.Prefs;
import com.workdeck.app.call.CallManager;
import com.workdeck.app.notify.Notifier;

import org.json.JSONObject;

import java.security.MessageDigest;

/**
 * Object exposed to page JavaScript as window.WorkDeckBridge. Every method
 * requires the per-tab secret token that only WorkDeck's injected script knows,
 * so third-party iframes cannot post notifications or write files.
 */
final class JsBridge {
    private static final String TAG = "WorkDeck/Bridge";
    private final BrowserTab mTab;
    private final Context mApp;
    private final byte[] mToken;
    private final Handler mMain = new Handler(Looper.getMainLooper());

    JsBridge(BrowserTab tab, Context app, String token) {
        mTab = tab;
        mApp = app;
        mToken = token.getBytes();
    }

    private boolean ok(String token) {
        return token != null && MessageDigest.isEqual(mToken, token.getBytes());
    }

    @JavascriptInterface
    public String permission(String token) {
        if (!ok(token)) return "denied";
        return notificationPermission(mApp, mTab);
    }

    static String notificationPermission(Context c, BrowserTab tab) {
        if (!Prefs.get(c).notificationsEnabled(tab.site)) return "denied";
        if (Build.VERSION.SDK_INT >= 33
                && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return "default";
        }
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        return nm.areNotificationsEnabled() ? "granted" : "denied";
    }

    @JavascriptInterface
    public void requestPermission(String token) {
        if (!ok(token)) return;
        mMain.post(new Runnable() {
            @Override
            public void run() {
                mTab.requestNotificationPermissionFromPage();
            }
        });
    }

    @JavascriptInterface
    public void notify(String token, String json) {
        if (!ok(token)) return;
        try {
            final JSONObject o = new JSONObject(json);
            mMain.post(new Runnable() {
                @Override
                public void run() {
                    Notifier.get(mApp).showWebNotification(mTab.site,
                            o.optString("id"), o.optString("title"), o.optString("body"),
                            o.optString("tag"), o.optString("icon"), o.optBoolean("requireInteraction"),
                            o.optBoolean("silent"), o.optBoolean("renotify"));
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "bad notification payload", t);
        }
    }

    @JavascriptInterface
    public void closeNotification(String token, final String id) {
        if (!ok(token)) return;
        mMain.post(new Runnable() {
            @Override
            public void run() {
                Notifier.get(mApp).closeWebNotification(mTab.site, id);
            }
        });
    }

    @JavascriptInterface
    public void badge(String token, String count) {
        if (!ok(token)) return;
        final int n = parseInt(count, -1);
        mMain.post(new Runnable() {
            @Override
            public void run() {
                mTab.setBadgeCount(n);
            }
        });
    }

    @JavascriptInterface
    public String fileStart(String token, String name, String mime, String size) {
        if (!ok(token)) return "";
        return Downloads.start(mApp, name, mime);
    }

    @JavascriptInterface
    public void fileChunk(String token, String id, String base64) {
        if (!ok(token)) return;
        Downloads.chunk(id, base64);
    }

    @JavascriptInterface
    public void fileEnd(String token, String id, String status) {
        if (!ok(token)) return;
        Downloads.finish(mApp, id, status);
    }

    @JavascriptInterface
    public void mediaDelta(String token, String delta) {
        if (!ok(token)) return;
        final int d = parseInt(delta, 0);
        mMain.post(new Runnable() {
            @Override
            public void run() {
                CallManager.get(mApp).onPageMicDelta(mTab.site, d);
            }
        });
    }

    @JavascriptInterface
    public void callSignal(String token, final String type, final String detail) {
        if (!ok(token)) return;
        mMain.post(new Runnable() {
            @Override
            public void run() {
                if ("ringing".equals(type)) Notifier.get(mApp).onRingtoneHeard(mTab.site);
            }
        });
    }

    @JavascriptInterface
    public void log(String token, String msg) {
        if (!ok(token)) return;
        Log.d(TAG, mTab.site.key + ": " + msg);
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }
}
