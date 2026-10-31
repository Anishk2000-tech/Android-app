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
    public static final String CH_TEAMS = "teams";
    public static final String CH_OUTLOOK = "outlook";
    public static final String CH_CALLS = "calls";
    public static final String CH_FILES = "files";

    public static final int ID_SERVICE = 1;
    public static final int ID_CALL_ALERT = 2;

    private static Notifier sInstance;

    private final Context mApp;
    private final NotificationManager mNm;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mIcons = Executors.newSingleThreadExecutor();
    /** "site|jsId" -> Android notification id. */
    private final Map<String, Integer> mByJsId = new HashMap<>();
    private String mCallAlertKey;
    private long mLastRingAlert;

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

        NotificationChannel teams = new NotificationChannel(CH_TEAMS,
                mApp.getString(R.string.channel_teams), NotificationManager.IMPORTANCE_HIGH);
        teams.enableVibration(true);

        NotificationChannel outlook = new NotificationChannel(CH_OUTLOOK,
                mApp.getString(R.string.channel_outlook), NotificationManager.IMPORTANCE_HIGH);
        outlook.enableVibration(true);

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
    }

    // ------------------------------------------------------------------ web notifications

    static int idFor(String key) {
        return 1000 + (key.hashCode() & 0x3fffffff);
    }

    public void showWebNotification(Site site, String jsId, String title, String body, String tag, String icon,
            boolean requireInteraction, boolean silent, boolean renotify) {
        Prefs prefs = Prefs.get(mApp);
        if (!prefs.notificationsEnabled(site)) return;
        boolean isCall = site == Site.TEAMS && CallHeuristics.looksLikeIncomingCall(title, body, requireInteraction);
        if (isCall && prefs.callAlerts()) {
            showCallAlert(site, jsId, title, body);
            return;
        }
        // The user is already looking at this site; the page shows its own in-app toast.
        if (UiState.isLookingAt(site)) return;

        String key = site.key + "|" + (tag == null || tag.isEmpty() ? "id:" + jsId : "tag:" + tag);
        final int id = idFor(key);
        mByJsId.put(site.key + "|" + jsId, id);
        final Notification.Builder b = baseBuilder(site, site == Site.TEAMS ? CH_TEAMS : CH_OUTLOOK)
                .setContentTitle(title.isEmpty() ? mApp.getString(site.titleRes) : title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setCategory(site == Site.TEAMS ? Notification.CATEGORY_MESSAGE : Notification.CATEGORY_EMAIL)
                .setContentIntent(openIntent(site, jsId, id))
                .setGroup("workdeck_" + site.key)
                .setOnlyAlertOnce(silent || (!renotify && tag != null && !tag.isEmpty() && isActive(id)));
        post(id, b);
        if (icon != null && !icon.isEmpty()) {
            loadIconAsync(icon, new IconCallback() {
                @Override
                public void onIcon(Bitmap bmp) {
                    if (!isActive(id)) return;
                    b.setLargeIcon(bmp).setOnlyAlertOnce(true);
                    post(id, b);
                }
            });
        }
    }

    public void closeWebNotification(Site site, String jsId) {
        String k = site.key + "|" + jsId;
        if (k.equals(mCallAlertKey)) {
            cancelCallAlert();
        }
        Integer id = mByJsId.remove(k);
        if (id != null) mNm.cancel(id);
    }

    /** Clears notifications of a site (e.g. when the user opens it). */
    public void clearSite(Site site) {
        String prefix = site.key + "|";
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

    private Notification.Builder baseBuilder(Site site, String channel) {
        return new Notification.Builder(mApp, channel)
                .setSmallIcon(R.drawable.ic_stat_workdeck)
                .setColor(mApp.getColor(R.color.wd_primary))
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis())
                .setSubText(mApp.getString(site.titleRes));
    }

    private void post(int id, Notification.Builder b) {
        try {
            mNm.notify(id, b.build());
        } catch (SecurityException e) {
            Log.w(TAG, "notification permission missing", e);
        }
    }

    private PendingIntent openIntent(Site site, String jsId, int requestCode) {
        Intent i = new Intent(mApp, MainActivity.class)
                .setAction(MainActivity.ACTION_OPEN_SITE)
                .putExtra(MainActivity.EXTRA_SITE, site.key)
                .putExtra(MainActivity.EXTRA_NOTIFICATION_ID, jsId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(mApp, requestCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // ------------------------------------------------------------------ calls

    public void showCallAlert(Site site, String jsId, String title, String body) {
        mCallAlertKey = site.key + "|" + jsId;
        mLastRingAlert = SystemClock.elapsedRealtime();
        Intent full = new Intent(mApp, MainActivity.class)
                .setAction(MainActivity.ACTION_INCOMING_CALL)
                .putExtra(MainActivity.EXTRA_SITE, site.key)
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

        Notification.Builder b = baseBuilder(site, CH_CALLS)
                .setContentTitle(title.isEmpty() ? mApp.getString(R.string.incoming_call) : title)
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
    public void onRingtoneHeard(Site site) {
        if (site != Site.TEAMS || !Prefs.get(mApp).callAlerts()) return;
        if (UiState.isLookingAt(site)) return;
        if (SystemClock.elapsedRealtime() - mLastRingAlert < 30000) return;
        showCallAlert(site, "", mApp.getString(R.string.incoming_call), mApp.getString(R.string.incoming_call_text));
    }

    public void cancelCallAlert() {
        mNm.cancel(ID_CALL_ALERT);
        mCallAlertKey = null;
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

    private void loadIconAsync(final String url, final IconCallback cb) {
        mIcons.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bmp = loadIcon(url);
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

    private Bitmap loadIcon(String url) {
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
                String cookie = CookieManager.getInstance().getCookie(url);
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
