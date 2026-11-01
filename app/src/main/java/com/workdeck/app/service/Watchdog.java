package com.workdeck.app.service;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import com.workdeck.app.Prefs;
import com.workdeck.app.web.TabManager;

/**
 * Every ~15 minutes (also in Doze), checks that the background service is still
 * running and restarts it if the phone stopped it, and nudges the pages (reloading
 * any that failed). Belt and braces next to the foreground service.
 */
public final class Watchdog extends BroadcastReceiver {
    private static final String TAG = "WorkDeck/Watchdog";
    private static final String ACTION = "com.workdeck.app.WATCHDOG";
    private static final long INTERVAL_MS = 15L * 60 * 1000;

    public static void schedule(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            PendingIntent pi = PendingIntent.getBroadcast(c, 7,
                    new Intent(c, Watchdog.class).setAction(ACTION),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + INTERVAL_MS, pi);
        } catch (Throwable t) {
            Log.w(TAG, "could not schedule", t);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Prefs p = Prefs.get(context);
        if (!p.setupDone() || !p.keepAlive()) return;
        schedule(context);
        if (!KeepAliveService.isRunning()) {
            Log.w(TAG, "background service was stopped by the phone; restarting");
            KeepAliveService.start(context);
        } else {
            TabManager tabs = TabManager.get(context);
            tabs.startBackground();
            tabs.retryFailed();
        }
    }
}
