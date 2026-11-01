package com.workdeck.app.ui;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.NotificationManager;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import com.workdeck.app.Prefs;
import com.workdeck.app.R;
import com.workdeck.app.service.KeepAliveService;

import java.util.ArrayList;
import java.util.List;

/** Phone settings that make notifications late or stop them, and how to fix each one. */
final class Reliability {
    private Reliability() {}

    enum Problem { NOTIFICATIONS_OFF, KEEP_ALIVE_OFF, BACKGROUND_RESTRICTED, STANDBY_RESTRICTED, BATTERY_OPTIMIZED }

    static List<Problem> check(Context c) {
        List<Problem> out = new ArrayList<>();
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (!nm.areNotificationsEnabled()) out.add(Problem.NOTIFICATIONS_OFF);
            if (!Prefs.get(c).keepAlive()) out.add(Problem.KEEP_ALIVE_OFF);
            if (Build.VERSION.SDK_INT >= 28) {
                ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
                if (am.isBackgroundRestricted()) out.add(Problem.BACKGROUND_RESTRICTED);
                UsageStatsManager us = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
                if (us != null && us.getAppStandbyBucket() >= 45) out.add(Problem.STANDBY_RESTRICTED);
            }
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(c.getPackageName())) out.add(Problem.BATTERY_OPTIMIZED);
        } catch (Throwable ignored) {
        }
        return out;
    }

    static String text(Context c, Problem p) {
        switch (p) {
            case NOTIFICATIONS_OFF: return c.getString(R.string.rel_notifications_off);
            case KEEP_ALIVE_OFF: return c.getString(R.string.rel_keepalive);
            case BACKGROUND_RESTRICTED: return c.getString(R.string.rel_restricted);
            case STANDBY_RESTRICTED: return c.getString(R.string.rel_standby);
            default: return c.getString(R.string.rel_battery);
        }
    }

    static void fix(Activity a, Problem p) {
        try {
            switch (p) {
                case NOTIFICATIONS_OFF:
                    a.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, a.getPackageName()));
                    return;
                case KEEP_ALIVE_OFF:
                    Prefs.get(a).setKeepAlive(true);
                    KeepAliveService.start(a);
                    return;
                case BATTERY_OPTIMIZED:
                    try {
                        a.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + a.getPackageName())));
                    } catch (Throwable t) {
                        a.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    }
                    return;
                default:
                    // Background restriction / restricted bucket: the app's battery page ("Unrestricted").
                    a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + a.getPackageName())));
            }
        } catch (Throwable ignored) {
        }
    }
}
