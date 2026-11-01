package com.workdeck.app.notify;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.media.audiofx.LoudnessEnhancer;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;

import com.workdeck.app.Prefs;
import com.workdeck.app.Site;
import com.workdeck.app.call.CallManager;

/**
 * Plays the message / mail sound for WorkDeck's notifications itself (the channels are
 * silent), so the user can pick the sound per app and make it louder than the sound
 * file: up to 400% through Android's loudness enhancer.
 */
public final class SoundPlayer {
    private static final String TAG = "WorkDeck/Sound";
    private static final long MIN_GAP_MS = 1500;
    private static long sLastPlay;
    private static MediaPlayer sPlayer;
    private static LoudnessEnhancer sEnhancer;

    private SoundPlayer() {}

    /** The sound chosen for a site ("" = phone default, "none" = silent). */
    public static Uri soundUri(Context c, Site s) {
        String v = Prefs.get(c).notificationSound(s);
        if ("none".equals(v)) return null;
        if (v.isEmpty()) return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        return Uri.parse(v);
    }

    /** Human-readable name of the chosen sound. */
    public static String soundTitle(Context c, Site s, String defaultLabel, String silentLabel) {
        String v = Prefs.get(c).notificationSound(s);
        if ("none".equals(v)) return silentLabel;
        if (v.isEmpty()) return defaultLabel;
        try {
            Ringtone r = RingtoneManager.getRingtone(c, Uri.parse(v));
            if (r != null) return r.getTitle(c);
        } catch (Throwable ignored) {
        }
        String last = Uri.parse(v).getLastPathSegment();
        return last == null ? v : last;
    }

    /** Plays the new-message sound of a site, unless the phone or the user says to be quiet. */
    public static void playFor(Context c, Site site, String channelId) {
        if (site == null) return;
        long now = SystemClock.elapsedRealtime();
        if (now - sLastPlay < MIN_GAP_MS) return;
        if (!allowed(c, channelId)) return;
        Uri uri = soundUri(c, site);
        if (uri == null) return;
        sLastPlay = now;
        play(c, uri, Prefs.get(c).soundBoost());
    }

    /** Settings' "Test" button: plays regardless of the throttle. */
    public static void test(Context c, Site site) {
        Uri uri = soundUri(c, site);
        if (uri != null) play(c, uri, Prefs.get(c).soundBoost());
    }

    private static boolean allowed(Context c, String channelId) {
        try {
            if (CallManager.get(c).inCall()) return false; // never sound over a call
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am.getRingerMode() != AudioManager.RINGER_MODE_NORMAL) return false; // silent / vibrate
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (!nm.areNotificationsEnabled()) return false;
            int filter = nm.getCurrentInterruptionFilter();
            if (filter != NotificationManager.INTERRUPTION_FILTER_ALL
                    && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
                return false; // Do Not Disturb
            }
            NotificationChannel ch = nm.getNotificationChannel(channelId);
            if (ch != null && ch.getImportance() < NotificationManager.IMPORTANCE_DEFAULT) return false;
            return true;
        } catch (Throwable t) {
            return true;
        }
    }

    private static synchronized void release() {
        if (sEnhancer != null) {
            try {
                sEnhancer.release();
            } catch (Throwable ignored) {
            }
            sEnhancer = null;
        }
        if (sPlayer != null) {
            try {
                sPlayer.release();
            } catch (Throwable ignored) {
            }
            sPlayer = null;
        }
    }

    private static synchronized void play(final Context c, final Uri uri, final int boostPercent) {
        release();
        final MediaPlayer mp = new MediaPlayer();
        sPlayer = mp;
        try {
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            mp.setDataSource(c.getApplicationContext(), uri);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer p) {
                    int mb = Loudness.millibels(boostPercent);
                    if (mb > 0) {
                        try {
                            LoudnessEnhancer le = new LoudnessEnhancer(p.getAudioSessionId());
                            le.setTargetGain(mb);
                            le.setEnabled(true);
                            synchronized (SoundPlayer.class) {
                                sEnhancer = le;
                            }
                        } catch (Throwable t) {
                            Log.w(TAG, "loudness boost unavailable", t);
                        }
                    }
                    p.start();
                    Log.i(TAG, "playing message sound at " + boostPercent + "% (" + mb + " mB)");
                }
            });
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer p) {
                    synchronized (SoundPlayer.class) {
                        if (sPlayer == p) release();
                    }
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer p, int what, int extra) {
                    Log.w(TAG, "sound failed (" + what + "/" + extra + "), using the default");
                    synchronized (SoundPlayer.class) {
                        if (sPlayer == p) release();
                    }
                    fallback(c);
                    return true;
                }
            });
            mp.prepareAsync();
        } catch (Throwable t) {
            Log.w(TAG, "cannot play " + uri, t);
            release();
            fallback(c);
        }
    }

    private static void fallback(Context c) {
        try {
            Ringtone r = RingtoneManager.getRingtone(c, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
            if (r != null) {
                r.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
                r.play();
            }
        } catch (Throwable ignored) {
        }
    }
}
