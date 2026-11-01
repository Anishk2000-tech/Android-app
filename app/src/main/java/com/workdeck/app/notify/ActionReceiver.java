package com.workdeck.app.notify;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.workdeck.app.WorkDeckApp;
import com.workdeck.app.call.CallManager;
import com.workdeck.app.service.KeepAliveService;
import com.workdeck.app.web.BrowserTab;
import com.workdeck.app.web.TabManager;

/** Handles buttons on WorkDeck's notifications. */
public final class ActionReceiver extends BroadcastReceiver {
    public static final String ACTION_DECLINE_CALL = "com.workdeck.app.action.DECLINE_CALL";
    public static final String ACTION_ROUTE = "com.workdeck.app.action.ROUTE";
    public static final String ACTION_STOP_BACKGROUND = "com.workdeck.app.action.STOP_BACKGROUND";
    public static final String ACTION_QUIT = "com.workdeck.app.action.QUIT";
    public static final String EXTRA_ROUTE = "route";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        switch (action) {
            case ACTION_DECLINE_CALL: {
                Notifier n = Notifier.get(context);
                String key = n.callAlertTab();
                n.cancelCallAlert();
                BrowserTab teams = TabManager.get(context).existing(key);
                if (teams != null) teams.evaluate("window.__wdCallAction && window.__wdCallAction('decline')");
                break;
            }
            case ACTION_ROUTE: {
                String r = intent.getStringExtra(EXTRA_ROUTE);
                try {
                    CallManager.get(context).setRouteByUser(CallManager.Route.valueOf(r));
                } catch (Throwable ignored) {
                }
                break;
            }
            case ACTION_STOP_BACKGROUND:
                KeepAliveService.stop(context);
                break;
            case ACTION_QUIT:
                WorkDeckApp.quit(context);
                break;
            default:
                break;
        }
    }
}
