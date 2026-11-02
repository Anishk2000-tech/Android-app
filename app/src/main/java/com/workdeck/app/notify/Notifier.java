package com.workdeck.app.notify;

import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.webkit.CookieManager;
import android.widget.Toast;

import com.workdeck.app.Prefs;
import com.workdeck.app.R;
import com.workdeck.app.Site;
import com.workdeck.app.UiState;
import com.workdeck.app.ui.MainActivity;
import com.workdeck.app.web.BrowserTab;
import com.workdeck.app.web.UrlRouter;
import com.workdeck.app.web.UserAgents;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Turns web notifications into Android notifications, plus call alerts and misc. */
public final class Notifier {
    private static final String TAG = "WorkDeck/Notifier";

    public static final String CH_SERVICE = "service";
    public static final String CH_IN_CALL = "in_call";
    /**
     * Message channels (1.3+). They are silent: WorkDeck plays the user's chosen sound
     * itself, louder if wanted (see {@link SoundPlayer}). They vibrate and pop up as before.
     */
    public static final String CH_TEAMS = "teams_messages";
    public static final String CH_OUTLOOK = "outlook_mail";
    /** Channels of 1.0-1.2 (with the phone's sound), replaced by the ones above. */
    private static final String OLD_CH_TEAMS = "teams";
    private static final String OLD_CH_OUTLOOK = "outlook";
    public static final String CH_CALLS = "calls";
    public static final String CH_FILES = "files";

    public static final int ID_SERVICE = 1;
    public static final int ID_CALL_ALERT = 2;

    private static Notifier sInstance;

    private final Context mApp;
    private final NotificationManager mNm;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mIcons = Executors.newSingleThreadExecutor();
    /** "account|site|jsId" -> Android notification id. */
    private final Map<String, Integer> mByJsId = new HashMap<>();
    private String mCallAlertKey;
    /** Tab (account|site) whose call is ringing. */
    private String mCallAlertTab;
    private long mLastRingAlert;
    /** Last unread count per site, and when the site last raised a real web notification. */
    private final Map<String, Integer> mUnread = new HashMap<>();
    private final Map<String, Integer> mAnnounced = new HashMap<>();
    private final Map<String, Long> mLastWebNotification = new HashMap<>();
    /**
     * Unread count the user was last told about before a page reloaded (crash recovery,
     * reconnect, scheduled refresh). Messages that arrive while it reloads are announced
     * once the page has settled, instead of being taken as "already seen".
     */
    private final Map<String, Integer> mCarry = new HashMap<>();
    private final Map<String, Long> mLastBaselineAt = new HashMap<>();
    private static final long SETTLE_MS = 21000;
    private static final long FALLBACK_DELAY_MS = 3500;
    private static final long REAL_NOTIFICATION_WINDOW_MS = 12000;

    public static synchronized Notifier get(Context c) {
        if (sInstance == null) sInstance = new Notifier(c.getApplicationContext());
        return sInstance;
    }

    private Notifier(Context app) {
        mApp = app;
        mNm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    public void createChannels() {
        NotificationChannel service = new NotificationChannel(CH_SERVICE,
                mApp.getString(R.string.channel_service), NotificationManager.IMPORTANCE_MIN);
        service.setDescription(mApp.getString(R.string.channel_service_desc));
        service.setShowBadge(false);

        NotificationChannel inCall = new NotificationChannel(CH_IN_CALL,
                mApp.getString(R.string.channel_in_call), NotificationManager.IMPORTANCE_LOW);
        inCall.setShowBadge(false);

        NotificationChannel teams = messageChannel(CH_TEAMS, OLD_CH_TEAMS, R.string.channel_teams);
        NotificationChannel outlook = messageChannel(CH_OUTLOOK, OLD_CH_OUTLOOK, R.string.channel_outlook);

        NotificationChannel calls = new NotificationChannel(CH_CALLS,
                mApp.getString(R.string.channel_calls), NotificationManager.IMPORTANCE_HIGH);
        calls.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        calls.enableVibration(true);
        calls.setVibrationPattern(new long[] {0, 800, 600, 800, 600});
        calls.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        calls.setBypassDnd(false);

        NotificationChannel files = new NotificationChannel(CH_FILES,
                mApp.getString(R.string.channel_files), NotificationManager.IMPORTANCE_LOW);

        mNm.createNotificationChannel(service);
        mNm.createNotificationChannel(inCall);
        mNm.createNotificationChannel(teams);
        mNm.createNotificationChannel(outlook);
        mNm.createNotificationChannel(calls);
        mNm.createNotificationChannel(files);
        for (String old : new String[] {OLD_CH_TEAMS, OLD_CH_OUTLOOK}) {
            try {
                if (mNm.getNotificationChannel(old) != null) mNm.deleteNotificationChannel(old);
            } catch (Throwable ignored) {
            }
        }
    }

    /** A silent, pop-up message channel; keeps "blocked" if the user had blocked the old one. */
    private NotificationChannel messageChannel(String id, String oldId, int nameRes) {
        int importance = NotificationManager.IMPORTANCE_HIGH;
        try {
            NotificationChannel old = mNm.getNotificationChannel(oldId);
            if (old != null && old.getImportance() == NotificationManager.IMPORTANCE_NONE) {
                importance = NotificationManager.IMPORTANCE_NONE;
            }
        } catch (Throwable ignored) {
        }
        NotificationChannel ch = new NotificationChannel(id, mApp.getString(nameRes), importance);
        ch.setSound(null, null);
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[] {0, 250, 150, 250});
        return ch;
    }

    // ------------------------------------------------------------------ web notifications

    static int idFor(String key) {
        return 1000 + (key.hashCode() & 0x3fffffff);
    }

    public void showWebNotification(final BrowserTab tab, String jsId, String title, String body, String tag,
            String icon, boolean requireInteraction, boolean silent, boolean renotify) {
        Site site = tab.site;
        Prefs prefs = Prefs.get(mApp);
        if (!tab.account.notificationsEnabled(site)) return;
        boolean isCall = site == Site.TEAMS && CallHeuristics.looksLikeIncomingCall(title, body, requireInteraction);
        if (isCall && prefs.callAlerts()) {
            Log.i(TAG, tab.key + ": incoming-call notification -> call alert");
            showCallAlert(tab, jsId, title, body);
            return;
        }
        // The user is already looking at this tab; the page shows its own in-app toast.
        if (UiState.isLookingAt(tab.key)) {
            Log.i(TAG, tab.key + ": notification suppressed (tab is on screen)");
            return;
        }

        String key = tab.key + "|" + (tag == null || tag.isEmpty() ? "id:" + jsId : "tag:" + tag);
        final int id = idFor(key);
        mByJsId.put(tab.key + "|" + jsId, id);
        // A real notification from the site: it replaces WorkDeck's unread-count fallback.
        mLastWebNotification.put(tab.key, SystemClock.elapsedRealtime());
        mNm.cancel(unreadId(tab.key));
        final Notification.Builder b = baseBuilder(tab, site == Site.TEAMS ? CH_TEAMS : CH_OUTLOOK)
                .setContentTitle(title.isEmpty() ? mApp.getString(site.titleRes) : title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setCategory(site == Site.TEAMS ? Notification.CATEGORY_MESSAGE : Notification.CATEGORY_EMAIL)
                .setContentIntent(openIntent(tab, jsId, id))
                .setGroup("workdeck_" + tab.key)
                .setOnlyAlertOnce(silent || (!renotify && tag != null && !tag.isEmpty() && isActive(id)));
        boolean alert = !silent && (renotify || tag == null || tag.isEmpty() || !isActive(id));
        post(id, b);
        if (alert) SoundPlayer.playFor(mApp, site, site == Site.TEAMS ? CH_TEAMS : CH_OUTLOOK);
        if (icon != null && !icon.isEmpty()) {
            loadIconAsync(icon, tab.cookies(), new IconCallback() {
                @Override
                public void onIcon(Bitmap bmp) {
                    if (!isActive(id)) return;
                    b.setLargeIcon(bmp).setOnlyAlertOnce(true);
                    post(id, b);
                }
            });
        }
    }

    public void closeWebNotification(BrowserTab tab, String jsId) {
        String k = tab.key + "|" + jsId;
        Log.i(TAG, tab.key + ": page closed notification " + jsId + (k.equals(mCallAlertKey) ? " (call alert)" : ""));
        if (k.equals(mCallAlertKey)) {
            cancelCallAlert();
        }
        Integer id = mByJsId.remove(k);
        if (id != null) mNm.cancel(id);
    }

    /** Clears notifications of a tab (e.g. when the user opens it). */
    public void clearTab(String tabKey) {
        mNm.cancel(unreadId(tabKey));
        String prefix = tabKey + "|";
        for (Map.Entry<String, Integer> e : new HashMap<>(mByJsId).entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                mNm.cancel(e.getValue());
                mByJsId.remove(e.getKey());
            }
        }
    }

    private boolean isActive(int id) {
        try {
            for (android.service.notification.StatusBarNotification s : mNm.getActiveNotifications()) {
                if (s.getId() == id) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private Notification.Builder baseBuilder(BrowserTab tab, String channel) {
        return new Notification.Builder(mApp, channel)
                .setSmallIcon(R.drawable.ic_stat_workdeck)
                .setColor(mApp.getColor(R.color.wd_primary))
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis())
                .setSubText(tab.label());
    }

    private void post(int id, Notification.Builder b) {
        try {
            mNm.notify(id, b.build());
            Log.i(TAG, "posted notification " + id);
        } catch (SecurityException e) {
            Log.w(TAG, "notification permission missing", e);
        }
    }

    private PendingIntent openIntent(BrowserTab tab, String jsId, int requestCode) {
        Intent i = new Intent(mApp, MainActivity.class)
                .setAction(MainActivity.ACTION_OPEN_SITE)
                .putExtra(MainActivity.EXTRA_SITE, tab.site.key)
                .putExtra(MainActivity.EXTRA_ACCOUNT, tab.account.id)
                .putExtra(MainActivity.EXTRA_NOTIFICATION_ID, jsId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(mApp, requestCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // ------------------------------------------------------------------ unread-count fallback

    static int unreadId(String tabKey) {
        return idFor(tabKey + "|unread");
    }

    /** A page (re)loaded: its first unread count is a baseline, not news. */
    public void resetUnreadBaseline(String tabKey) {
        Integer told = mAnnounced.get(tabKey);
        if (told == null) told = mCarry.get(tabKey);
        mUnread.remove(tabKey);
        mAnnounced.remove(tabKey);
        if (told != null) mCarry.put(tabKey, told);
    }

    /** The page says this count is a starting point (e.g. still loading), not new mail. */
    public void setUnreadBaseline(String tabKey, int count) {
        mUnread.put(tabKey, count);
        mLastBaselineAt.put(tabKey, SystemClock.elapsedRealtime());
        Integer carry = mCarry.get(tabKey);
        if (carry != null) {
            // A reload: keep what the user was told; the settle check announces anything new.
            mAnnounced.put(tabKey, carry);
            scheduleSettle(tabKey);
            return;
        }
        mAnnounced.put(tabKey, count);
        if (count <= 0) mNm.cancel(unreadId(tabKey));
    }

    private final java.util.Set<String> mSettleScheduled = new java.util.HashSet<>();

    private void scheduleSettle(final String k) {
        if (!mSettleScheduled.add(k)) return;
        com.workdeck.app.web.BrowserTab t = com.workdeck.app.web.TabManager.get(mApp).existing(k);
        long since = t == null ? 0 : SystemClock.elapsedRealtime() - t.loadedAt();
        mMain.postDelayed(new Runnable() {
            @Override
            public void run() {
                Long lastBase = mLastBaselineAt.get(k);
                if (lastBase != null && SystemClock.elapsedRealtime() - lastBase < 2500) {
                    // Still loading its counts: check again shortly.
                    mMain.postDelayed(this, 3000);
                    return;
                }
                mSettleScheduled.remove(k);
                settle(k);
            }
        }, Math.max(1000, SETTLE_MS - since));
    }

    /** After a reload: announce messages that arrived meanwhile (count above what the user was told). */
    private void settle(String k) {
        Integer carry = mCarry.remove(k);
        Integer now = mUnread.get(k);
        if (carry == null || now == null) return;
        com.workdeck.app.web.BrowserTab tab = com.workdeck.app.web.TabManager.get(mApp).existing(k);
        if (now > carry && tab != null) {
            Log.i(TAG, k + ": " + (now - carry) + " message(s) arrived while the page reloaded");
            mAnnounced.put(k, now);
            if (!UiState.isLookingAt(k) && tab.account.notificationsEnabled(tab.site)) announceRise(tab);
        } else {
            mAnnounced.put(k, now);
            if (now <= 0) mNm.cancel(unreadId(k));
        }
    }

    /**
     * The site's unread count changed (page title or app badge). Teams on the web does
     * not always raise a browser notification, so when the count goes UP while the user
     * isn't looking, WorkDeck posts one itself - unless the site's own notification
     * arrives within a few seconds.
     */
    public void onUnreadChanged(final BrowserTab tab, final int count) {
        final String k = tab.key;
        Integer prevObj = mUnread.get(k);
        mUnread.put(k, count);
        boolean freshPage = SystemClock.elapsedRealtime() - tab.loadedAt() < 20000;
        if (freshPage) {
            Integer carry = mCarry.get(k);
            if (carry != null) {
                // Reloaded page: compare with what the user was told once it has settled.
                mAnnounced.put(k, carry);
                scheduleSettle(k);
                return;
            }
            Log.i(TAG, k + ": unread " + count + " taken as baseline (page just loaded)");
            mAnnounced.put(k, count); // counts shown while a page is still loading are not news
            return;
        }
        // No change seen yet means the page has been sitting at zero.
        int prev = prevObj == null ? 0 : prevObj;
        Integer announcedObj = mAnnounced.get(k);
        final int announced = announcedObj == null ? prev : announcedObj;
        if (count < announced) {
            // Titles sometimes flicker; only accept a drop that sticks.
            mMain.postDelayed(new Runnable() {
                @Override
                public void run() {
                    Integer now = mUnread.get(k);
                    Integer ann = mAnnounced.get(k);
                    if (now != null && ann != null && now < ann) {
                        mAnnounced.put(k, now);
                        if (now <= 0) mNm.cancel(unreadId(k));
                    }
                }
            }, 6000);
            return;
        }
        if (count == announced) return;
        mAnnounced.put(k, count);
        if (UiState.isLookingAt(k) || !tab.account.notificationsEnabled(tab.site)) return;
        Log.i(TAG, k + ": unread " + announced + " -> " + count);
        announceRise(tab);
    }

    /** Posts "N unread" (with the sender when readable) unless the site's own notification came. */
    private void announceRise(final BrowserTab tab) {
        final String k = tab.key;
        mMain.postDelayed(new Runnable() {
            @Override
            public void run() {
                Long real = mLastWebNotification.get(k);
                if (real != null && SystemClock.elapsedRealtime() - real < REAL_NOTIFICATION_WINDOW_MS) return;
                final Integer now = mUnread.get(k);
                if (now == null || now <= 0 || UiState.isLookingAt(k)) return;
                tab.evaluate("window.__wdUnreadPreview ? JSON.stringify(window.__wdUnreadPreview()) : 'null'",
                        new android.webkit.ValueCallback<String>() {
                            @Override
                            public void onReceiveValue(String value) {
                                postUnread(tab, now, parsePreview(value));
                            }
                        });
            }
        }, FALLBACK_DELAY_MS);
    }

    private static String[] parsePreview(String evaluated) {
        try {
            if (evaluated == null || "null".equals(evaluated)) return null;
            // evaluateJavascript returns a JSON-encoded string containing our JSON.
            String inner = new org.json.JSONTokener(evaluated).nextValue().toString();
            if ("null".equals(inner)) return null;
            org.json.JSONObject o = new org.json.JSONObject(inner);
            String t = o.optString("title").trim();
            return t.isEmpty() ? null : new String[] {t, o.optString("body").trim()};
        } catch (Throwable t) {
            return null;
        }
    }

    private void postUnread(BrowserTab tab, int count, String[] preview) {
        Site site = tab.site;
        boolean teams = site == Site.TEAMS;
        String countText = mApp.getString(teams ? R.string.unread_teams_text : R.string.unread_outlook_text, count);
        String title = preview != null ? preview[0]
                : mApp.getString(teams ? R.string.unread_teams_title : R.string.unread_outlook_title);
        String body = preview != null && !preview[1].isEmpty() ? preview[1] + "\n" + countText : countText;
        int id = unreadId(tab.key);
        Notification.Builder b = baseBuilder(tab, teams ? CH_TEAMS : CH_OUTLOOK)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setNumber(count)
                .setAutoCancel(true)
                .setCategory(teams ? Notification.CATEGORY_MESSAGE : Notification.CATEGORY_EMAIL)
                .setContentIntent(openIntent(tab, null, id))
                .setGroup("workdeck_" + tab.key);
        Log.i(TAG, tab.key + ": unread fallback notification (" + count + ", preview=" + (preview != null) + ")");
        post(id, b);
        SoundPlayer.playFor(mApp, site, teams ? CH_TEAMS : CH_OUTLOOK);
    }

    // ------------------------------------------------------------------ calls

    /**
     * Teams is showing its incoming-call screen (Accept/Decline) in the background, or
     * stopped showing it (answered elsewhere, missed, declined).
     */
    public void onCallScreen(BrowserTab tab, boolean ringing, String who) {
        if (tab.site != Site.TEAMS) return;
        if (!ringing) {
            // The call screen went away (answered, declined, missed): stop ringing.
            if (mCallAlertKey != null) cancelCallAlert();
            return;
        }
        if (!Prefs.get(mApp).callAlerts() || UiState.isLookingAt(tab.key)) return;
        if (mCallAlertKey != null) return; // already ringing for this call
        String title = who == null || who.trim().isEmpty() ? mApp.getString(R.string.incoming_call) : who.trim();
        Log.i(TAG, "incoming call screen detected");
        showCallAlert(tab, "ui", title, mApp.getString(R.string.incoming_call_text));
    }

    public void showCallAlert(BrowserTab tab, String jsId, String title, String body) {
        mCallAlertKey = tab.key + "|" + jsId;
        mCallAlertTab = tab.key;
        Log.i(TAG, "call alert shown for " + mCallAlertKey);
        mLastRingAlert = SystemClock.elapsedRealtime();
        Intent full = new Intent(mApp, MainActivity.class)
                .setAction(MainActivity.ACTION_INCOMING_CALL)
                .putExtra(MainActivity.EXTRA_SITE, tab.site.key)
                .putExtra(MainActivity.EXTRA_ACCOUNT, tab.account.id)
                .putExtra(MainActivity.EXTRA_NOTIFICATION_ID, jsId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent fullPi = PendingIntent.getActivity(mApp, 11, full,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent answer = new Intent(full).setAction(MainActivity.ACTION_ANSWER_CALL);
        PendingIntent answerPi = PendingIntent.getActivity(mApp, 12, answer,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent decline = new Intent(mApp, ActionReceiver.class).setAction(ActionReceiver.ACTION_DECLINE_CALL);
        PendingIntent declinePi = PendingIntent.getBroadcast(mApp, 13, decline,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String callTitle = title.isEmpty() ? mApp.getString(R.string.incoming_call) : title;
        if (com.workdeck.app.Accounts.get(mApp).multiple()) callTitle = callTitle + " (" + tab.account.name + ")";
        Notification.Builder b = baseBuilder(tab, CH_CALLS)
                .setContentTitle(callTitle)
                .setContentText(body.isEmpty() ? mApp.getString(R.string.incoming_call_text) : body)
                .setCategory(Notification.CATEGORY_CALL)
                .setOngoing(true)
                .setAutoCancel(true)
                .setTimeoutAfter(60000)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(fullPi)
                .setFullScreenIntent(fullPi, true)
                .addAction(new Notification.Action.Builder(null, mApp.getString(R.string.answer), answerPi).build())
                .addAction(new Notification.Action.Builder(null, mApp.getString(R.string.decline), declinePi).build());
        Notification n = b.build();
        if (Prefs.get(mApp).callRingtone()) n.flags |= Notification.FLAG_INSISTENT;
        try {
            mNm.notify(ID_CALL_ALERT, n);
        } catch (SecurityException e) {
            Log.w(TAG, "call alert failed", e);
        }
    }

    /** The page started a looping ring sound while the app was not in front. */
    public void onRingtoneHeard(BrowserTab tab) {
        if (tab.site != Site.TEAMS || !Prefs.get(mApp).callAlerts()) return;
        if (UiState.isLookingAt(tab.key)) return;
        if (SystemClock.elapsedRealtime() - mLastRingAlert < 30000) return;
        showCallAlert(tab, "", mApp.getString(R.string.incoming_call), mApp.getString(R.string.incoming_call_text));
    }

    /** Tab key (account|site) of the call currently ringing, or null. */
    public String callAlertTab() {
        return mCallAlertKey == null ? null : mCallAlertTab;
    }

    private static final int ID_RESTART = 3;

    /** The phone stopped WorkDeck and won't let it restart by itself: ask for one tap. */
    public void showRestartNeeded() {
        PendingIntent open = PendingIntent.getActivity(mApp, 5,
                new Intent(mApp, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(mApp, CH_TEAMS)
                .setSmallIcon(R.drawable.ic_stat_workdeck)
                .setColor(mApp.getColor(R.color.wd_primary))
                .setContentTitle(mApp.getString(R.string.restart_title))
                .setContentText(mApp.getString(R.string.restart_text))
                .setStyle(new Notification.BigTextStyle().bigText(mApp.getString(R.string.restart_text)))
                .setContentIntent(open)
                .setAutoCancel(true);
        post(ID_RESTART, b);
    }

    public void cancelRestartNeeded() {
        mNm.cancel(ID_RESTART);
    }

    public void cancelCallAlert() {
        boolean wasRinging = mCallAlertKey != null;
        if (wasRinging) Log.i(TAG, "call alert cancelled");
        mNm.cancel(ID_CALL_ALERT);
        mCallAlertKey = null;
        if (wasRinging) {
            // WorkDeck may have been shown over the lock screen for this call: undo that.
            final android.app.Activity a = com.workdeck.app.WorkDeckApp.mainActivity();
            if (a instanceof MainActivity) {
                a.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        ((MainActivity) a).onRingingStopped();
                    }
                });
            }
        }
    }

    // ------------------------------------------------------------------ files

    public static void downloadComplete(Context c, String name, String mime, Uri uri) {
        Intent view;
        if (uri != null) {
            view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        } else {
            view = new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        PendingIntent pi = PendingIntent.getActivity(c, name.hashCode(), view,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, CH_FILES)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(c.getString(R.string.download_done))
                .setContentText(name)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        try {
            nm.notify(5000 + (name.hashCode() & 0xffff), n);
        } catch (SecurityException ignored) {
        }
        toast(c, c.getString(R.string.download_saved, name));
    }

    public static void toast(final Context c, final String msg) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(c.getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ------------------------------------------------------------------ icons

    interface IconCallback {
        void onIcon(Bitmap bmp);
    }

    private void loadIconAsync(final String url, final CookieManager cookies, final IconCallback cb) {
        mIcons.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bmp = loadIcon(url, cookies);
                if (bmp != null) {
                    mMain.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.onIcon(bmp);
                        }
                    });
                }
            }
        });
    }

    private Bitmap loadIcon(String url, CookieManager cookies) {
        try {
            byte[] data;
            if (url.startsWith("data:")) {
                int comma = url.indexOf(',');
                if (comma < 0 || !url.substring(0, comma).contains(";base64")) return null;
                data = Base64.decode(url.substring(comma + 1), Base64.DEFAULT);
            } else if (url.startsWith("https://") && UrlRouter.isMicrosoft(url)) {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("User-Agent", UserAgents.desktop());
                String cookie = cookies.getCookie(url);
                if (cookie != null) conn.setRequestProperty("Cookie", cookie);
                if (conn.getResponseCode() != 200) return null;
                InputStream in = conn.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > 1024 * 1024) return null;
                }
                in.close();
                conn.disconnect();
                data = out.toByteArray();
            } else {
                return null;
            }
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (b == null) return null;
            int max = 192;
            if (b.getWidth() > max || b.getHeight() > max) {
                float s = Math.min(max / (float) b.getWidth(), max / (float) b.getHeight());
                b = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * s), Math.round(b.getHeight() * s), true);
            }
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean canUseFullScreenIntent(Context c) {
        if (Build.VERSION.SDK_INT < 34) return true;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        return nm.canUseFullScreenIntent();
    }
}
