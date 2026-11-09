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
        if (!tab.account.notificationsEnabled(tab.site)) return "denied";
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
                    Notifier.get(mApp).showWebNotification(mTab,
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
                Notifier.get(mApp).closeWebNotification(mTab, id);
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

    /** Outlook's Inbox unread count read from the page; {@code baseline} = not news yet. */
    @JavascriptInterface
    public void mailUnread(String token, String count, final String baseline) {
        if (!ok(token)) return;
        final int n = parseInt(count, -1);
        if (n < 0) return;
        mMain.post(new Runnable() {
            @Override
            public void run() {
                mTab.setScrapedUnread(n, "1".equals(baseline));
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
                CallManager.get(mApp).onPageMicDelta(mTab.key, d);
            }
        });
    }

    /** A call connection (RTCPeerConnection sending audio) came up (+1) or ended (-1). */
    @JavascriptInterface
    public void rtcDelta(String token, String delta) {
        if (!ok(token)) return;
        final int d = parseInt(delta, 0);
        mMain.post(new Runnable() {
            @Override
            public void run() {
                CallManager.get(mApp).onPageRtcDelta(mTab.key, d);
            }
        });
    }

    /** The page started (+1) or stopped (-1) a camera track. */
    @JavascriptInterface
    public void videoDelta(String token, String delta) {
        if (!ok(token)) return;
        final int d = parseInt(delta, 0);
        mMain.post(new Runnable() {
            @Override
            public void run() {
                CallManager.get(mApp).onPageVideoDelta(mTab.key, d);
            }
        });
    }

    @JavascriptInterface
    public void callSignal(String token, final String type, final String detail) {
        if (!ok(token)) return;
        mMain.post(new Runnable() {
            @Override
            public void run() {
                if ("ringing".equals(type)) Notifier.get(mApp).onRingtoneHeard(mTab);
                else if ("ringing_ui".equals(type)) Notifier.get(mApp).onCallScreen(mTab, true, detail);
                else if ("ringing_end".equals(type)) Notifier.get(mApp).onCallScreen(mTab, false, detail);
                else if ("unsupported_browser".equals(type)) mTab.onBrowserUnsupported();
                else if ("access_blocked".equals(type)) mTab.onAccessBlocked(detail);
            }
        });
    }

    /** "1" while the user is looking at this site in WorkDeck. */
    @JavascriptInterface
    public String looking(String token) {
        if (!ok(token)) return "0";
        return com.workdeck.app.UiState.isLookingAt(mTab.key) ? "1" : "0";
    }

    /** Real Android microphone/camera permission, cameras and audio routes (JSON). */
    @JavascriptInterface
    public String mediaInfo(String token) {
        if (!ok(token)) return "";
        return MediaInfo.json(mApp);
    }

    /** Teams picked one of WorkDeck's "speakers": route call audio there. */
    @JavascriptInterface
    public void setRoute(String token, final String route) {
        if (!ok(token)) return;
        mMain.post(new Runnable() {
            @Override
            public void run() {
                try {
                    CallManager.get(mApp).setRouteFromPage(CallManager.Route.valueOf(route));
                } catch (Throwable t) {
                    Log.w(TAG, "bad route " + route, t);
                }
            }
        });
    }

    /**
     * Is the user looking at this page right now? ("0": the page is told it's hidden and
     * not focused, so it doesn't mark messages as read.) Unknown callers get "0".
     */
    @JavascriptInterface
    public String drawn(String token) {
        if (!ok(token)) return "0";
        return mTab.isDrawn() ? "1" : "0";
    }

    /** Next chunk (base64) of a file the user is attaching; "" at the end, null on error. */
    @JavascriptInterface
    public String attachRead(String token, String id, String offset) {
        if (!ok(token)) return null;
        long off;
        try {
            off = Long.parseLong(offset.trim());
        } catch (Throwable t) {
            off = 0;
        }
        return Attachments.read(mApp, id, off);
    }

    /** How the page took the files: drop / paste / input / none; seen = file names appeared on the page. */
    @JavascriptInterface
    public void attachDone(String token, final String method, final String count, final String seen) {
        if (!ok(token)) return;
        mMain.post(new Runnable() {
            @Override
            public void run() {
                mTab.onAttachDone(method, parseInt(count, 0), "1".equals(seen));
            }
        });
    }

    /** The user is typing in a message box (show WorkDeck's attach button) or left it. */
    @JavascriptInterface
    public void composeFocus(String token, final String state) {
        if (!ok(token)) return;
        mMain.post(new Runnable() {
            @Override
            public void run() {
                mTab.setComposeFocused("1".equals(state));
            }
        });
    }

    /** Teams picked one of WorkDeck's microphones (phone / Bluetooth / headset). */
    @JavascriptInterface
    public void setMic(String token, final String mic) {
        if (!ok(token)) return;
        mMain.post(new Runnable() {
            @Override
            public void run() {
                CallManager.get(mApp).setMicFromPage(mic);
            }
        });
    }

    @JavascriptInterface
    public void log(String token, String msg) {
        if (!ok(token)) return;
        Log.d(TAG, mTab.key + ": " + msg);
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }
}
