package com.workdeck.app.service;

import android.Manifest;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import com.workdeck.app.Account;
import com.workdeck.app.Accounts;
import com.workdeck.app.Prefs;
import com.workdeck.app.R;
import com.workdeck.app.UiState;
import com.workdeck.app.call.CallManager;
import com.workdeck.app.notify.ActionReceiver;
import com.workdeck.app.notify.Notifier;
import com.workdeck.app.ui.MainActivity;
import com.workdeck.app.web.BrowserTab;
import com.workdeck.app.web.TabManager;

import java.util.List;

/**
 * Foreground service that keeps WorkDeck's process (and therefore the Teams and
 * Outlook WebViews) alive in the background, so messages, mail and calls keep
 * arriving after the app is closed or swiped away from Recents.
 */
public final class KeepAliveService extends Service implements BrowserTab.Listener, CallManager.Listener {
    private static final String TAG = "WorkDeck/Service";
    private static final String ACTION_START = "com.workdeck.app.service.START";
    private static final long WATCHDOG_MS = 10L * 60 * 1000;

    private static KeepAliveService sInstance;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock mWakeLock;
    private ConnectivityManager.NetworkCallback mNetCallback;
    private String mLastText;
    private int mLastTypes;
    private boolean mForeground;

    public static void start(Context c) {
        try {
            c.startForegroundService(new Intent(c, KeepAliveService.class).setAction(ACTION_START));
        } catch (Throwable t) {
            // e.g. ForegroundServiceStartNotAllowedException when started from the background
            // (happens when the phone restricts WorkDeck's battery use).
            Log.w(TAG, "could not start service", t);
            if (!UiState.activityVisible) Notifier.get(c).showRestartNeeded();
        }
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, KeepAliveService.class));
    }

    public static boolean isRunning() {
        return sInstance != null;
    }

    /** Called by the activity when it becomes visible, so mic/camera service types can be added. */
    public static void onUiVisibilityChanged() {
        if (sInstance != null) sInstance.ensureForeground(true);
    }

    /** Re-reads the wake lock preference. */
    public static void onSettingsChanged() {
        if (sInstance != null) {
            sInstance.applyWakeLock();
            sInstance.ensureForeground(false);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        CallManager.get(this).addListener(this);
        registerNetwork();
        mMain.postDelayed(mWatchdog, WATCHDOG_MS);
        Watchdog.schedule(this);
        Notifier.get(this).cancelRestartNeeded();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Every startForegroundService() must be answered with startForeground().
        if (!ensureForeground(true, true)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        TabManager tabs = TabManager.get(this);
        tabs.addGlobalListener(this);
        tabs.startBackground();
        applyWakeLock();
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Swiped away from Recents: keep running so notifications continue.
        Log.i(TAG, "task removed; staying alive");
        TabManager.get(this).flushCookies();
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        mMain.removeCallbacksAndMessages(null);
        CallManager.get(this).removeListener(this);
        TabManager.get(this).removeGlobalListener(this);
        if (mNetCallback != null) {
            try {
                ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).unregisterNetworkCallback(mNetCallback);
            } catch (Throwable ignored) {
            }
        }
        releaseWakeLock();
        TabManager.get(this).flushCookies();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------ foreground

    private boolean has(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    /** Service types: special-use always; mic/camera only when allowed (app visible or in a call). */
    private int types(boolean whileInUse) {
        int t = 0;
        if (Build.VERSION.SDK_INT >= 34) t |= ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        if (Build.VERSION.SDK_INT >= 30 && whileInUse) {
            if (has(Manifest.permission.RECORD_AUDIO)) t |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            if (has(Manifest.permission.CAMERA)) t |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
        }
        return t;
    }

    /**
     * Puts/keeps the service in the foreground. Service types are only ever
     * upgraded (adding microphone/camera while the UI is visible, which Android
     * requires); later updates just re-post the notification. Re-calling
     * startForeground() with the microphone type from the background can throw on
     * Android 14+, and dropping the type mid-call would silence the microphone.
     */
    private boolean ensureForeground(boolean allowUpgrade) {
        return ensureForeground(allowUpgrade, false);
    }

    private boolean ensureForeground(boolean allowUpgrade, boolean force) {
        Notification n = buildNotification();
        if (mForeground) {
            int wanted = mLastTypes;
            if (allowUpgrade && UiState.activityVisible) wanted |= types(true);
            if (wanted != mLastTypes || force) {
                try {
                    startForegroundCompat(n, wanted);
                    mLastTypes = wanted;
                    return true;
                } catch (Throwable t) {
                    Log.w(TAG, "could not set service types " + wanted, t);
                    if (force) {
                        // Must still answer startForegroundService(); fall back to the basic type.
                        try {
                            startForegroundCompat(n, types(false));
                            mLastTypes = types(false);
                            return true;
                        } catch (Throwable t2) {
                            Log.e(TAG, "cannot run in foreground", t2);
                            return false;
                        }
                    }
                }
            }
            try {
                ((android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(Notifier.ID_SERVICE, n);
            } catch (Throwable ignored) {
            }
            return true;
        }
        int wanted = types(UiState.activityVisible);
        try {
            startForegroundCompat(n, wanted);
            mLastTypes = wanted;
            mForeground = true;
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "startForeground(" + wanted + ") failed, retrying with basic type", t);
            try {
                int basic = types(false);
                startForegroundCompat(n, basic);
                mLastTypes = basic;
                mForeground = true;
                return true;
            } catch (Throwable t2) {
                Log.e(TAG, "cannot run in foreground", t2);
                return false;
            }
        }
    }

    private void startForegroundCompat(Notification n, int types) {
        if (Build.VERSION.SDK_INT >= 29 && types != 0) {
            startForeground(Notifier.ID_SERVICE, n, types);
        } else {
            startForeground(Notifier.ID_SERVICE, n);
        }
    }

    private Notification buildNotification() {
        CallManager call = CallManager.get(this);
        PendingIntent open = PendingIntent.getActivity(this, 1,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b;
        if (call.inCall()) {
            CallManager.Route r = call.route();
            b = new Notification.Builder(this, Notifier.CH_IN_CALL)
                    .setContentTitle(getString(R.string.call_in_progress))
                    .setContentText(getString(R.string.call_audio_route, routeLabel(r)))
                    .setUsesChronometer(true)
                    .setWhen(call.callStartedAt())
                    .setShowWhen(true)
                    .setCategory(Notification.CATEGORY_CALL);
            int added = 0;
            List<CallManager.Route> routes = call.availableRoutes();
            for (CallManager.Route option : routes) {
                if (option == r || added >= 2) continue;
                Intent i = new Intent(this, ActionReceiver.class).setAction(ActionReceiver.ACTION_ROUTE)
                        .putExtra(ActionReceiver.EXTRA_ROUTE, option.name());
                PendingIntent pi = PendingIntent.getBroadcast(this, 20 + option.ordinal(), i,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                b.addAction(new Notification.Action.Builder(null, routeLabel(option), pi).build());
                added++;
            }
        } else {
            b = new Notification.Builder(this, Notifier.CH_SERVICE)
                    .setContentTitle(com.workdeck.app.Edition.solo(this)
                            ? getString(R.string.solo_service_title, com.workdeck.app.Edition.account(this),
                                    getString(com.workdeck.app.Edition.site(this).titleRes))
                            : getString(R.string.service_title))
                    .setContentText(statusText())
                    .setShowWhen(false);
            PendingIntent stop = PendingIntent.getBroadcast(this, 2,
                    new Intent(this, ActionReceiver.class).setAction(ActionReceiver.ACTION_QUIT),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.addAction(new Notification.Action.Builder(null, getString(R.string.quit), stop).build());
        }
        b.setSmallIcon(R.drawable.ic_stat_workdeck)
                .setColor(getColor(R.color.wd_primary))
                .setOngoing(true)
                .setContentIntent(open);
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }

    private String routeLabel(CallManager.Route r) {
        if (r == null) return getString(R.string.route_auto);
        switch (r) {
            case EARPIECE: return getString(R.string.route_earpiece);
            case BLUETOOTH: return getString(R.string.route_bluetooth);
            case WIRED: return getString(R.string.route_wired);
            default: return getString(R.string.route_speaker);
        }
    }

    private String statusText() {
        TabManager tabs = TabManager.get(this);
        boolean multiple = Accounts.get(this).multiple();
        StringBuilder sb = new StringBuilder();
        int running = 0;
        for (Account a : Accounts.get(this).list()) {
            StringBuilder part = new StringBuilder();
            for (BrowserTab t : tabs.tabsOf(a)) {
                if (!t.isCreated()) continue;
                running++;
                int n = t.unreadCount();
                if (n <= 0) continue;
                if (part.length() > 0) part.append(", ");
                part.append(getString(t.site.titleRes)).append(' ').append(n);
            }
            if (part.length() == 0) continue;
            if (sb.length() > 0) sb.append("  ·  ");
            if (multiple) sb.append(a.name).append(": ");
            sb.append(part);
        }
        if (running == 0) return getString(R.string.service_text_idle);
        if (sb.length() == 0) return getString(R.string.service_text_quiet);
        return getString(R.string.service_text, sb.toString());
    }

    @Override
    public void onTabChanged(BrowserTab tab) {
        String text = statusText();
        if (!text.equals(mLastText)) {
            mLastText = text;
            ensureForeground(false);
        }
    }

    @Override
    public void onCallStateChanged(boolean inCall, CallManager.Route route) {
        ensureForeground(true);
        if (inCall) Notifier.get(this).cancelCallAlert();
    }

    // ------------------------------------------------------------------ reliability

    private void applyWakeLock() {
        if (Prefs.get(this).cpuWakeLock()) {
            if (mWakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "workdeck:keepalive");
                mWakeLock.setReferenceCounted(false);
            }
            if (!mWakeLock.isHeld()) mWakeLock.acquire();
        } else {
            releaseWakeLock();
        }
    }

    private void releaseWakeLock() {
        if (mWakeLock != null && mWakeLock.isHeld()) mWakeLock.release();
    }

    private void registerNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            mNetCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    mMain.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            TabManager.get(KeepAliveService.this).retryFailed();
                        }
                    }, 2500);
                }
            };
            cm.registerDefaultNetworkCallback(mNetCallback);
        } catch (Throwable t) {
            Log.w(TAG, "network callback failed", t);
        }
    }

    private final Runnable mWatchdog = new Runnable() {
        @Override
        public void run() {
            TabManager tabs = TabManager.get(KeepAliveService.this);
            tabs.startBackground();
            tabs.retryFailed();
            tabs.autoRefreshIfStale(UiState.activityVisible);
            // Link tabs nobody looked at for 45 minutes give their memory back.
            tabs.trimIdleWebTabs(UiState.visibleTab, 45L * 60 * 1000);
            tabs.flushCookies();
            mMain.postDelayed(this, WATCHDOG_MS);
        }
    };
}
