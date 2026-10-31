package com.workdeck.app;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.webkit.CookieManager;

import com.workdeck.app.notify.Notifier;
import com.workdeck.app.service.KeepAliveService;
import com.workdeck.app.web.TabManager;

import java.lang.ref.WeakReference;

public final class WorkDeckApp extends Application {
    private static WeakReference<Activity> sMainActivity = new WeakReference<>(null);

    @Override
    public void onCreate() {
        super.onCreate();
        Notifier.get(this).createChannels();
    }

    public static void setMainActivity(Activity a) {
        sMainActivity = new WeakReference<>(a);
    }

    /** Fully exits: closes the UI, stops the service and ends the process (login is kept). */
    public static void quit(Context c) {
        final Context app = c.getApplicationContext();
        Activity a = sMainActivity.get();
        if (a != null) a.finishAndRemoveTask();
        KeepAliveService.stop(app);
        CookieManager.getInstance().flush();
        TabManager.get(app).destroyAll();
        Notifier.get(app).cancelCallAlert();
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                CookieManager.getInstance().flush();
                Process.killProcess(Process.myPid());
            }
        }, 600);
    }
}
