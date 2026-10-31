package com.workdeck.app.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.workdeck.app.Prefs;

/** Restarts the background service after a reboot or an app update. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        Prefs p = Prefs.get(context);
        if (!p.setupDone() || !p.keepAlive()) return;
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) && !p.startOnBoot()) return;
        KeepAliveService.start(context);
    }
}
