package com.workdeck.app.call;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import com.workdeck.app.EventLog;
import com.workdeck.app.Prefs;
import com.workdeck.app.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Tracks whether a Teams call is active and controls where call audio goes.
 *
 * A call is active while the page has a live call connection that sends audio (an
 * RTCPeerConnection, reported by the page script) or records from the microphone. The
 * connection keeps the call "on" when Teams closes and re-opens the microphone (mute,
 * device change), so the chosen device isn't lost on the way.
 *
 * Routing - see {@link RoutePolicy}: headset first, else earpiece for voice and speaker
 * for video calls; the user's choice during the call wins. WorkDeck is the owner of the
 * call's audio device. The browser engine also sets one whenever it opens the microphone
 * (always a headset or else the LOUDSPEAKER) and when a headset (dis)connects, but it
 * never watches the device afterwards: so whenever the device changes away from the one
 * chosen here, it is put back at once (within ~60 ms, before the user hears the wrong one).
 *
 * Bluetooth headsets need their call link (SCO) to be up BEFORE the microphone starts:
 * Android records from the headset only then - otherwise it may record the phone's own
 * microphone (in a pocket, on the table) while the sound plays in the headset, and the
 * other person hears the voice break up. So the page's microphone is held back until the
 * link is up ({@link #prepareMic}); the link is given time to come up instead of being
 * requested over and over; and if it keeps dropping during a call, the call moves to
 * listening on the headset and talking into the phone ({@link Route#BT_PHONE_MIC}).
 * Every step is noted in the activity log ({@link EventLog}) so a failing call on a
 * user's phone can be understood afterwards.
 */
public final class CallManager {
    private static final String TAG = "WorkDeck/Call";
    private static final long POLL_MS = 2000;
    /** How fast a device change made by someone else (the engine) is undone. */
    private static final long REASSERT_DELAY_MS = 60;
    /** Re-assert at most this often per minute (never fight the user's system controls forever). */
    private static final int REASSERT_MAX_PER_MIN = 40;
    /** Page device requests this old are not part of the call that is starting. */
    private static final long PAGE_REQUEST_FRESH_MS = 30000;
    /** The Bluetooth call link gets this long to come up before it is asked for again. */
    private static final long BT_CONNECT_GRACE_MS = 4000;
    /** The page's microphone is held back at most this long while the Bluetooth link comes up. */
    public static final long MIC_WAIT_MAX_MS = 4000;
    /** Bluetooth link drops counted within this window. */
    private static final long BT_DROP_WINDOW_MS = 120000;
    /** The user prefers Bluetooth for listening only (talking into the phone). */
    private static final String PREF_LISTEN_ONLY = "bt_listen_only";
    private static final AudioAttributes VOICE = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
    private static CallManager sInstance;

    /**
     * Where call audio goes. {@link #BT_PHONE_MIC}: the call plays in the Bluetooth headset
     * (its music profile) and the user talks into the phone's microphone.
     */
    public enum Route { SPEAKER, EARPIECE, BLUETOOTH, WIRED, BT_PHONE_MIC }

    public interface Listener {
        void onCallStateChanged(boolean inCall, Route route);
    }

    private final Context mApp;
    private final AudioManager mAudio;
    private final PowerManager mPower;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Map<String, Integer> mPageMic = new HashMap<>();
    private final Map<String, Integer> mPageRtc = new HashMap<>();
    private final Map<String, Integer> mPageVideo = new HashMap<>();
    private final List<Listener> mListeners = new ArrayList<>();

    private volatile boolean mInCall;
    private long mCallStartedWall;
    private long mCallStartedAt;
    private volatile Route mRoute;
    private boolean mUserPicked;
    private volatile Route mPendingRoute;
    private volatile boolean mVideo;
    private boolean mWeSetMode;
    /** The call device was set before a call (the microphone opening) and must be undone if none follows. */
    private boolean mWeRouted;
    private boolean mPolling;
    private int mEmptyPolls;
    private long mSuspectUntil;
    private AudioFocusRequest mFocus;
    private PowerManager.WakeLock mCpuLock;
    private PowerManager.WakeLock mProximityLock;
    private long mReassertWindowStart;
    private int mReassertCount;
    private int mBtRetries;
    private int mScoAttempts;
    private List<Route> mLastAvailable = new ArrayList<>();
    /** The page's last microphone / speaker request (null: none in this call yet). */
    private Route mPageMicReq, mPageSinkReq;
    private long mPageMicReqAt, mPageSinkReqAt;
    private boolean mPageMicSeen, mPageSinkSeen;

    // Bluetooth call link (SCO), as Android reports it, and what happened in this call.
    private volatile int mScoState = AudioManager.SCO_AUDIO_STATE_DISCONNECTED;
    private long mBtRequestedAt;
    private long mLinkUpSince;
    private final List<Long> mScoDrops = new ArrayList<>();
    /** Until then, the Bluetooth link going down is WorkDeck's own doing (not a drop). */
    private long mDeliberateBtChangeUntil;
    private boolean mFallbackDone;
    private boolean mNudged;
    /** This call moved to the phone's microphone because the headset's couldn't be switched on. */
    private boolean mMicFallbackDone;
    private int mMicReopens, mPutBack, mDropsTotal;
    private String mLastMicDiag, mLastOutDiag, mDevicesSig;

    public static synchronized CallManager get(Context c) {
        if (sInstance == null) sInstance = new CallManager(c.getApplicationContext());
        return sInstance;
    }

    private CallManager(Context app) {
        mApp = app;
        mAudio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        mPower = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        mAudio.registerAudioRecordingCallback(new AudioManager.AudioRecordingCallback() {
            @Override
            public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
                // The engine (re)opened or closed the microphone - and with it, on opening,
                // switched the call device. Re-evaluate and re-assert right away.
                pollSoon(50);
                if (mInCall) {
                    verifySoon();
                    micDiagnostics(configs);
                }
            }
        }, mMain);
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                mAudio.addOnCommunicationDeviceChangedListener(app.getMainExecutor(),
                        new AudioManager.OnCommunicationDeviceChangedListener() {
                            @Override
                            public void onCommunicationDeviceChanged(AudioDeviceInfo device) {
                                if (mInCall) verifySoon();
                            }
                        });
            } catch (Throwable t) {
                Log.w(TAG, "communication device listener unavailable", t);
            }
        }
        // The Bluetooth call link's state (all Android versions; a sticky broadcast, so the
        // current state arrives right away).
        try {
            BroadcastReceiver sco = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    onScoState(i.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1));
                }
            };
            IntentFilter f = new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED);
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(sco, f, Context.RECEIVER_EXPORTED);
            else app.registerReceiver(sco, f);
        } catch (Throwable t) {
            Log.w(TAG, "SCO receiver unavailable", t);
        }
        mAudio.registerAudioDeviceCallback(new AudioDeviceCallback() {
            @Override
            public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
                onDevicesChanged(true);
            }

            @Override
            public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
                onDevicesChanged(false);
            }
        }, mMain);
        mLastAvailable = availableRoutes();
        mDevicesSig = devicesSignature();
    }

    // ------------------------------------------------------------------ signals from the page

    public void onMicPossiblyStarted() {
        mSuspectUntil = SystemClock.elapsedRealtime() + 20000;
        prepareCallAudio();
        startPolling();
        pollSoon(50);
    }

    /** A tab (account|site key) opened (+1) or closed (-1) a microphone track. */
    public void onPageMicDelta(String tabKey, int delta) {
        bump(mPageMic, tabKey, delta);
        if (delta > 0) {
            mSuspectUntil = SystemClock.elapsedRealtime() + 20000;
            prepareCallAudio();
            if (mInCall && ++mMicReopens <= 10) {
                EventLog.add(mApp, "call audio: Teams opened the microphone again (" + mMicReopens + ")");
            }
            if (mInCall) {
                // Look again which microphone records once it runs.
                mMain.removeCallbacks(mDiagnose);
                mMain.postDelayed(mDiagnose, 3500);
            }
        }
        startPolling();
        pollSoon(50);
    }

    /** A tab's call connection (RTCPeerConnection sending audio) came up (+1) or ended (-1). */
    public void onPageRtcDelta(String tabKey, int delta) {
        bump(mPageRtc, tabKey, delta);
        Log.i(TAG, "call connections " + total(mPageRtc));
        startPolling();
        pollSoon(50);
    }

    /** A tab started (+1) or stopped (-1) a camera track. */
    public void onPageVideoDelta(String tabKey, int delta) {
        boolean was = mVideo;
        bump(mPageVideo, tabKey, delta);
        mVideo = total(mPageVideo) > 0;
        if (mInCall && mVideo && !was) {
            Route next = RoutePolicy.onVideoStarted(mRoute, mUserPicked);
            if (next != mRoute) {
                Log.i(TAG, "camera on: video call, loudspeaker");
                EventLog.add(mApp, "call audio: camera on, " + label(next));
                applyRoute(next);
            }
        }
    }

    private static void bump(Map<String, Integer> m, String key, int delta) {
        Integer cur = m.get(key);
        m.put(key, Math.max(0, (cur == null ? 0 : cur) + delta));
    }

    private static int total(Map<String, Integer> m) {
        int n = 0;
        for (Integer v : m.values()) n += v == null ? 0 : v;
        return n;
    }

    /**
     * The page is opening the microphone: switch Android to call audio right away
     * (echo cancellation, call volume, correct device) instead of after the call is
     * confirmed, so the first seconds don't blast from the wrong speaker.
     */
    private void prepareCallAudio() {
        if (mInCall) return;
        // Listening on Bluetooth while talking into the phone: the headset stays in its
        // music profile, which call mode would end.
        if (targetRoute(mVideo) == Route.BT_PHONE_MIC) return;
        setCommunicationMode();
    }

    // ------------------------------------------------------------------ the microphone opening

    /** Where the call's audio will go: the current call's device, else what a new call picks. */
    private Route targetRoute(boolean video) {
        Route cur = mRoute;
        if (mInCall && cur != null) return cur;
        List<Route> avail = availableRoutes();
        Route pending = mPendingRoute;
        if (pending != null && avail.contains(pending)) return pending;
        return RoutePolicy.auto(avail, video || mVideo || voiceOnSpeaker(), listenOnlyPreferred());
    }

    /**
     * Will the microphone that is about to open need the Bluetooth call link brought up
     * first? (Thread-safe: asked from the page's bridge thread.)
     */
    public boolean micNeedsBluetoothLink(boolean video) {
        return targetRoute(video) == Route.BLUETOOTH && !bluetoothLinkUp();
    }

    /**
     * The microphone that is about to open is the phone's own while the call plays in the
     * Bluetooth headset: the page asks the engine for the phone's microphone explicitly,
     * so the engine doesn't switch the headset to its call profile first. Thread-safe.
     */
    public boolean micIsPhoneWithBluetoothSound(boolean video) {
        return targetRoute(video) == Route.BT_PHONE_MIC;
    }

    /** The Bluetooth call link is up: the headset's microphone can be used. Thread-safe. */
    @SuppressWarnings("deprecation")
    public boolean bluetoothLinkUp() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                AudioDeviceInfo d = mAudio.getCommunicationDevice();
                return d != null && (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || isBle(d.getType()));
            }
            return mAudio.isBluetoothScoOn() && mScoState == AudioManager.SCO_AUDIO_STATE_CONNECTED;
        } catch (Throwable t) {
            return true; // unknown: never hold the microphone back for it
        }
    }

    /**
     * The page is about to open the microphone. If the call goes to the Bluetooth headset,
     * its call link is brought up now, before the microphone starts (see the class
     * description). Main thread.
     */
    public void prepareMic(boolean video) {
        Route r = targetRoute(video);
        if (r == Route.BT_PHONE_MIC) {
            if (mInCall) verifySoon();
            return;
        }
        if (r != Route.BLUETOOTH || bluetoothLinkUp()) return;
        setCommunicationMode();
        if (!mInCall) mWeRouted = true;
        requestBluetooth(false);
        mSuspectUntil = SystemClock.elapsedRealtime() + 20000;
        startPolling();
    }

    /** The page's microphone waited {@code waitedMs} for the Bluetooth link. */
    public void onMicWaitDone(long waitedMs, boolean linkUp) {
        if (waitedMs < 50 && linkUp) return;
        EventLog.add(mApp, "call audio: microphone held back " + waitedMs + " ms for the Bluetooth call link"
                + (linkUp ? " (up)" : " - it didn't come up in time, opened anyway"));
    }

    /**
     * Asks Android for the Bluetooth call link - unless it was asked for moments ago and
     * is still coming up (asking again would only restart it). {@code force}: the user
     * just chose the headset.
     */
    @SuppressWarnings("deprecation")
    private boolean requestBluetooth(boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && now - mBtRequestedAt < BT_CONNECT_GRACE_MS) return true;
        mBtRequestedAt = now;
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                AudioDeviceInfo target = null;
                for (AudioDeviceInfo d : mAudio.getAvailableCommunicationDevices()) {
                    if (matches(Route.BLUETOOTH, d.getType())) {
                        target = d;
                        break;
                    }
                }
                return target != null && mAudio.setCommunicationDevice(target);
            }
            mAudio.setSpeakerphoneOn(false);
            if (mScoState != AudioManager.SCO_AUDIO_STATE_CONNECTED && mScoState != AudioManager.SCO_AUDIO_STATE_CONNECTING) {
                mAudio.startBluetoothSco();
            }
            mAudio.setBluetoothScoOn(true);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "bluetooth request failed", t);
            return false;
        }
    }

    private void setCommunicationMode() {
        try {
            if (mAudio.getMode() != AudioManager.MODE_IN_COMMUNICATION) {
                mAudio.setMode(AudioManager.MODE_IN_COMMUNICATION);
                mWeSetMode = true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not set call audio mode", t);
        }
    }

    /** The tab's page was (re)loaded: its microphone, connections and camera are gone. */
    public void resetPageMic(String tabKey) {
        mPageMic.put(tabKey, 0);
        mPageRtc.put(tabKey, 0);
        mPageVideo.put(tabKey, 0);
        mVideo = total(mPageVideo) > 0;
        if (mInCall) pollSoon(50);
    }

    private boolean recording() {
        try {
            return !mAudio.getActiveRecordingConfigurations().isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    private void startPolling() {
        if (mPolling) return;
        mPolling = true;
        mMain.postDelayed(mPoll, POLL_MS);
    }

    private void pollSoon(long delay) {
        mMain.removeCallbacks(mPollOnce);
        mMain.postDelayed(mPollOnce, delay);
    }

    private final Runnable mPollOnce = new Runnable() {
        @Override
        public void run() {
            evaluate();
        }
    };

    private final Runnable mPoll = new Runnable() {
        @Override
        public void run() {
            evaluate();
            boolean keep = mInCall || total(mPageMic) > 0 || total(mPageRtc) > 0
                    || SystemClock.elapsedRealtime() < mSuspectUntil;
            if (keep) {
                mMain.postDelayed(this, POLL_MS);
            } else {
                mPolling = false;
                // The mic was opened briefly (e.g. a device test) but no call followed.
                if (!mInCall && (mWeSetMode || mWeRouted)) resetRouting();
            }
        }
    };

    private void evaluate() {
        boolean rec = recording();
        int mic = total(mPageMic);
        int rtc = total(mPageRtc);
        if (!mInCall) {
            if (rtc > 0 || (rec && (mic > 0 || SystemClock.elapsedRealtime() < mSuspectUntil))) setInCall(true);
            return;
        }
        if (rtc > 0 || rec) {
            mEmptyPolls = 0;
            return;
        }
        mEmptyPolls++;
        // If the page still reports a live mic track (e.g. muted), wait a bit longer.
        if (mEmptyPolls >= (mic > 0 ? 6 : 2)) setInCall(false);
    }

    // ------------------------------------------------------------------ state

    public boolean inCall() {
        return mInCall;
    }

    public long callStartedAt() {
        return mCallStartedWall;
    }

    public Route route() {
        return mRoute;
    }

    public void addListener(Listener l) {
        if (!mListeners.contains(l)) mListeners.add(l);
    }

    public void removeListener(Listener l) {
        mListeners.remove(l);
    }

    private void dispatch() {
        for (Listener l : new ArrayList<>(mListeners)) l.onCallStateChanged(mInCall, mRoute);
    }

    private void setInCall(boolean inCall) {
        if (mInCall == inCall) return;
        mInCall = inCall;
        mEmptyPolls = 0;
        if (inCall) {
            long now = SystemClock.elapsedRealtime();
            mCallStartedWall = System.currentTimeMillis();
            mCallStartedAt = now;
            mVideo = total(mPageVideo) > 0;
            mWeRouted = false;
            acquireCpu();
            requestFocus();
            List<Route> avail = availableRoutes();
            mLastAvailable = avail;
            final Route pending = mPendingRoute != null && avail.contains(mPendingRoute) ? mPendingRoute : null;
            mUserPicked = pending != null;
            mRoute = pending != null ? pending : RoutePolicy.auto(avail, mVideo || voiceOnSpeaker(), listenOnlyPreferred());
            // Device requests the page made while the call was starting are its saved
            // preferences, not the user: they count as "seen" for this call.
            mPageMicSeen = mPageMicReq != null && now - mPageMicReqAt < PAGE_REQUEST_FRESH_MS;
            mPageSinkSeen = mPageSinkReq != null && now - mPageSinkReqAt < PAGE_REQUEST_FRESH_MS;
            if (!mPageMicSeen) mPageMicReq = null;
            if (!mPageSinkSeen) mPageSinkReq = null;
            mReassertCount = 0;
            mBtRetries = 0;
            mScoAttempts = 0;
            mReassertWindowStart = now;
            mScoDrops.clear();
            mFallbackDone = false;
            mNudged = false;
            mMicFallbackDone = false;
            mMicReopens = 0;
            mPutBack = 0;
            mDropsTotal = 0;
            mLastMicDiag = null;
            mLastOutDiag = null;
            Log.i(TAG, "inCall=true (" + (mVideo ? "video" : "voice") + ", devices " + avail + ")");
            EventLog.add(mApp, "call started (" + (mVideo ? "video" : "voice") + "): " + label(mRoute)
                    + (pending != null ? " (chosen before the call)" : "") + "; devices: " + describeDevices()
                    + "; Bluetooth call link " + (bluetoothLinkUp() ? "up" : "down"));
            applyRoute(mRoute);
            mMain.postDelayed(mReapply, 900);
            mMain.postDelayed(mReapply, 2500);
            mMain.removeCallbacks(mVerifyLoop);
            mMain.postDelayed(mVerifyLoop, 2000);
            mMain.removeCallbacks(mDiagnose);
            mMain.postDelayed(mDiagnose, 3000);
            mMain.postDelayed(mDiagnose, 9000);
        } else {
            long secs = (SystemClock.elapsedRealtime() - mCallStartedAt) / 1000;
            Log.i(TAG, "inCall=false");
            EventLog.add(mApp, "call ended after " + (secs / 60) + ":" + String.format(java.util.Locale.US, "%02d", secs % 60)
                    + " on " + label(mRoute) + "; Bluetooth link drops " + mDropsTotal + ", Teams re-opened the microphone "
                    + mMicReopens + "x, call audio put back " + mPutBack + "x");
            mMain.removeCallbacks(mReapply);
            mMain.removeCallbacks(mVerifyLoop);
            mMain.removeCallbacks(mVerifyOnce);
            mMain.removeCallbacks(mBtRetry);
            mMain.removeCallbacks(mDiagnose);
            resetRouting();
            abandonFocus();
            releaseLocks();
            mRoute = null;
            mPendingRoute = null;
            mPageMicReq = mPageSinkReq = null;
            mPageMicSeen = mPageSinkSeen = false;
            // The page keeps its microphone open for the call (so Teams re-opening it can't
            // drop the Bluetooth link): let it go now.
            for (com.workdeck.app.web.BrowserTab t : com.workdeck.app.web.TabManager.get(mApp).allTabs()) {
                if (t.isCreated()) t.evaluate("window.__wdMicKeeperRelease && window.__wdMicKeeperRelease()");
            }
        }
        dispatch();
    }

    /** WorkDeck's setting "Phone calls start on: speaker" (only when set explicitly). */
    private boolean voiceOnSpeaker() {
        Prefs p = Prefs.get(mApp);
        return p.raw().contains("default_route") && Prefs.ROUTE_SPEAKER.equals(p.defaultRoute());
    }

    private boolean listenOnlyPreferred() {
        return Prefs.get(mApp).raw().getBoolean(PREF_LISTEN_ONLY, false);
    }

    // ------------------------------------------------------------------ routing

    public List<Route> availableRoutes() {
        List<Route> out = new ArrayList<>();
        boolean earpiece = false;
        boolean bt = false;
        boolean a2dp = false;
        boolean wired = false;
        try {
            AudioDeviceInfo[] devices = mAudio.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            for (AudioDeviceInfo d : devices) {
                int t = d.getType();
                if (t == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) earpiece = true;
                else if (t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || isBle(t)) bt = true;
                else if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) a2dp = true;
                else if (t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                        || t == AudioDeviceInfo.TYPE_USB_HEADSET) wired = true;
            }
        } catch (Throwable ignored) {
        }
        out.add(Route.SPEAKER);
        if (earpiece && !wired) out.add(Route.EARPIECE);
        if (wired) out.add(Route.WIRED);
        if (bt) out.add(Route.BLUETOOTH);
        if (a2dp) out.add(Route.BT_PHONE_MIC);
        return out;
    }

    /** Name of the connected Bluetooth headset (e.g. "Galaxy Buds2"), or null. */
    public String bluetoothName() {
        try {
            for (AudioDeviceInfo d : mAudio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int t = d.getType();
                if (t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || isBle(t)) {
                    CharSequence n = d.getProductName();
                    if (n != null && n.length() > 0 && !n.toString().equals(android.os.Build.MODEL)) return n.toString();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isBle(int type) {
        return Build.VERSION.SDK_INT >= 31 && (type == AudioDeviceInfo.TYPE_BLE_HEADSET
                || type == AudioDeviceInfo.TYPE_BLE_SPEAKER);
    }

    /** Called from WorkDeck's own controls (call-audio button, notification buttons). */
    public void setRouteByUser(Route r) {
        if (r == null) return;
        // Bluetooth for listening only, or the headset for talking too: remembered for later calls.
        if (r == Route.BT_PHONE_MIC) Prefs.get(mApp).raw().edit().putBoolean(PREF_LISTEN_ONLY, true).apply();
        else if (r == Route.BLUETOOTH) Prefs.get(mApp).raw().edit().putBoolean(PREF_LISTEN_ONLY, false).apply();
        pick(r, "you chose");
    }

    private void pick(Route r, String why) {
        if (!mInCall) {
            // Outside a call, don't touch Android's audio mode (that would affect other
            // apps' sound); remember the choice for the next call instead.
            mPendingRoute = r;
            Log.i(TAG, "route " + r + " saved for the next call");
            return;
        }
        Log.i(TAG, "user picked " + r);
        if (r != mRoute) EventLog.add(mApp, "call audio: " + label(r) + " (" + why + ")");
        mUserPicked = true;
        mBtRetries = 0;
        mReassertCount = 0;
        if (r == Route.BLUETOOTH) requestBluetooth(true);
        applyRoute(r);
    }

    /** Teams chose a speaker (setSinkId). Only a change during the call is the user's choice. */
    public void setRouteFromPage(Route r) {
        long now = SystemClock.elapsedRealtime();
        if (!mInCall) {
            mPageSinkReq = r;
            mPageSinkReqAt = now;
            Log.i(TAG, "page speaker " + r + " (saved preference, not applied)");
            return;
        }
        Route pick = RoutePolicy.fromPage(r, mPageSinkReq, !mPageSinkSeen, availableRoutes());
        Log.i(TAG, "page speaker " + r + (pick != null ? " -> route " + pick : " (unchanged / page preference, ignored)"));
        mPageSinkReq = r;
        mPageSinkReqAt = now;
        mPageSinkSeen = true;
        if (pick != null) pick(pick, "picked in Teams");
    }

    /**
     * Teams chose a microphone. Android uses the microphone of the call's audio device,
     * so a Bluetooth/headset mic means routing the call there; the phone mic means
     * leaving any headset's microphone. Only a change during the call is the user's choice
     * (the first request is Teams re-applying its saved mic).
     */
    public void setMicFromPage(String mic) {
        Route r = RoutePolicy.micToRoute(mic, availableRoutes(), mVideo || voiceOnSpeaker());
        if (r == null) return;
        long now = SystemClock.elapsedRealtime();
        if (!mInCall) {
            mPageMicReq = r;
            mPageMicReqAt = now;
            Log.i(TAG, "page microphone " + mic + " (saved preference, not applied)");
            return;
        }
        Route pick = RoutePolicy.fromPage(r, mPageMicReq, !mPageMicSeen, availableRoutes());
        Log.i(TAG, "page microphone " + mic + (pick != null ? " -> route " + pick : " (unchanged / page preference, ignored)"));
        mPageMicReq = r;
        mPageMicReqAt = now;
        mPageMicSeen = true;
        if (pick != null) pick(pick, "microphone picked in Teams");
    }

    /** Cycles through the available devices. */
    public void cycleRoute() {
        List<Route> avail = availableRoutes();
        int i = mRoute == null ? -1 : avail.indexOf(mRoute);
        setRouteByUser(avail.get((i + 1) % avail.size()));
    }

    private void onDevicesChanged(boolean added) {
        // Let Teams refresh its device list (it re-reads enumerateDevices on 'devicechange') -
        // only when the list it sees really changed: Teams may re-open the microphone on the
        // event, and with a Bluetooth headset that interrupts the call's sound.
        String sig = devicesSignature();
        if (!sig.equals(mDevicesSig)) {
            mDevicesSig = sig;
            for (com.workdeck.app.web.BrowserTab t : com.workdeck.app.web.TabManager.get(mApp).allTabs()) {
                if (t.isCreated()) t.evaluate("window.__wdDevicesChanged && window.__wdDevicesChanged()");
            }
        }
        List<Route> before = mLastAvailable;
        List<Route> now = availableRoutes();
        mLastAvailable = now;
        if (!mInCall || mRoute == null) return;
        if (!before.equals(now)) EventLog.add(mApp, "call audio: devices now " + describeDevices());
        Route next = added ? RoutePolicy.onDeviceAdded(mRoute, before, now, listenOnlyPreferred())
                : RoutePolicy.onDeviceRemoved(mRoute, now, mVideo || voiceOnSpeaker(), listenOnlyPreferred());
        if (next != mRoute) {
            Log.i(TAG, (added ? "headset connected: " : "device gone: ") + mRoute + " -> " + next);
            EventLog.add(mApp, "call audio: " + label(next) + (added ? " (headset connected)" : " (" + label(mRoute) + " gone)"));
            mUserPicked = false;
            mBtRetries = 0;
            if (next == Route.BLUETOOTH) requestBluetooth(true);
            applyRoute(next);
        }
        // The engine reacts to the same event by picking its own device: check soon.
        verifySoon();
    }

    /** The devices the page is shown (and the headset's name). */
    private String devicesSignature() {
        StringBuilder sb = new StringBuilder();
        for (Route r : availableRoutes()) if (r != Route.BT_PHONE_MIC) sb.append(r.name()).append(',');
        String n = bluetoothName();
        if (n != null) sb.append(n);
        return sb.toString();
    }

    // ------------------------------------------------------------------ keeping the route

    private final Runnable mReapply = new Runnable() {
        @Override
        public void run() {
            if (mInCall && mRoute != null && !actualMatches(mRoute)) verify();
        }
    };

    private void verifySoon() {
        mMain.removeCallbacks(mVerifyOnce);
        mMain.postDelayed(mVerifyOnce, REASSERT_DELAY_MS);
    }

    private final Runnable mVerifyOnce = new Runnable() {
        @Override
        public void run() {
            verify();
        }
    };

    private final Runnable mVerifyLoop = new Runnable() {
        @Override
        public void run() {
            verify();
            if (mInCall) mMain.postDelayed(this, POLL_MS);
        }
    };

    /** Bluetooth headset connected but its call link isn't up yet: ask again. */
    private final Runnable mBtRetry = new Runnable() {
        @Override
        public void run() {
            if (mInCall && mRoute == Route.BLUETOOTH && !actualMatches(Route.BLUETOOTH)) {
                requestBluetooth(true);
                applyRoute(Route.BLUETOOTH);
            }
        }
    };

    /** A look at the microphone and the call's sound some seconds into the call. */
    private final Runnable mDiagnose = new Runnable() {
        @Override
        public void run() {
            if (!mInCall) return;
            try {
                micDiagnostics(mAudio.getActiveRecordingConfigurations());
            } catch (Throwable ignored) {
            }
            outputDiagnostics();
        }
    };

    private int mode() {
        try {
            return mAudio.getMode();
        } catch (Throwable t) {
            return AudioManager.MODE_IN_COMMUNICATION;
        }
    }

    private void verify() {
        if (!mInCall || mRoute == null) return;
        long now = SystemClock.elapsedRealtime();
        boolean up = bluetoothLinkUp();
        if (up && mLinkUpSince == 0) mLinkUpSince = now;
        else if (!up) mLinkUpSince = 0;
        List<Route> avail = availableRoutes();
        if (!avail.contains(mRoute)) {
            mUserPicked = false;
            Route next = RoutePolicy.onDeviceRemoved(mRoute, avail, mVideo || voiceOnSpeaker(), listenOnlyPreferred());
            EventLog.add(mApp, "call audio: " + label(mRoute) + " gone, now " + label(next));
            if (next == Route.BLUETOOTH) requestBluetooth(true);
            applyRoute(next);
            return;
        }
        int mode = mode();
        boolean modeOk = mRoute == Route.BT_PHONE_MIC ? mode != AudioManager.MODE_IN_COMMUNICATION
                : mode == AudioManager.MODE_IN_COMMUNICATION;
        if (actualMatches(mRoute) && modeOk) return;
        if (mRoute == Route.BLUETOOTH && modeOk) {
            // The headset's call link is still coming up: give it time instead of asking
            // again (that would only restart it).
            long since = now - mBtRequestedAt;
            if (since < BT_CONNECT_GRACE_MS || mScoState == AudioManager.SCO_AUDIO_STATE_CONNECTING) {
                mMain.removeCallbacks(mVerifyOnce);
                mMain.postDelayed(mVerifyOnce, Math.max(300, BT_CONNECT_GRACE_MS - since));
                return;
            }
        }
        if (now - mReassertWindowStart > 60000) {
            mReassertWindowStart = now;
            mReassertCount = 0;
        }
        if (++mReassertCount > REASSERT_MAX_PER_MIN) {
            // Something keeps changing it (another app's call, the system): let it be for now.
            return;
        }
        mPutBack++;
        Log.i(TAG, "call audio moved away from " + mRoute + (modeOk ? "" : " (mode reset)") + "; restoring");
        if (mPutBack <= 5 || mPutBack % 20 == 0) {
            EventLog.add(mApp, "call audio: moved to " + actualDescription() + (modeOk ? "" : ", call mode off")
                    + " by someone else; put back to " + label(mRoute) + " (" + mPutBack + ")");
        }
        if (mRoute == Route.BLUETOOTH) requestBluetooth(false);
        applyRoute(mRoute);
    }

    /** Is Android currently sending call audio where we want it? */
    @SuppressWarnings("deprecation")
    private boolean actualMatches(Route r) {
        try {
            if (r == Route.BT_PHONE_MIC) {
                // No call link, and the call's sound goes to the headset's music profile (not
                // to a device someone - the engine - set as the call's device).
                if (bluetoothLinkUp()) return false;
                int out = voiceOutputType();
                if (out >= 0) return out == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP;
                if (Build.VERSION.SDK_INT >= 31) {
                    AudioDeviceInfo d = mAudio.getCommunicationDevice();
                    return d == null || d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP;
                }
                return !mAudio.isSpeakerphoneOn() && !mAudio.isBluetoothScoOn();
            }
            if (Build.VERSION.SDK_INT >= 31) {
                AudioDeviceInfo d = mAudio.getCommunicationDevice();
                return d != null && matches(r, d.getType());
            }
            switch (r) {
                case SPEAKER: return mAudio.isSpeakerphoneOn() && !mAudio.isBluetoothScoOn();
                case BLUETOOTH: return mAudio.isBluetoothScoOn();
                default: return !mAudio.isSpeakerphoneOn() && !mAudio.isBluetoothScoOn();
            }
        } catch (Throwable t) {
            return true;
        }
    }

    private static boolean matches(Route r, int type) {
        switch (r) {
            case SPEAKER: return type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
            case EARPIECE: return type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE;
            case BLUETOOTH: return type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || isBle(type);
            case BT_PHONE_MIC: return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP;
            default: return type == AudioDeviceInfo.TYPE_WIRED_HEADSET || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                    || type == AudioDeviceInfo.TYPE_USB_HEADSET;
        }
    }

    /** The Bluetooth call link changed (Android's broadcast; main thread). */
    @SuppressWarnings("deprecation")
    private void onScoState(int state) {
        int was = mScoState;
        mScoState = state;
        if (state == was) return;
        long now = SystemClock.elapsedRealtime();
        boolean recent = mBtRequestedAt > 0 && now - mBtRequestedAt < 10000;
        if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
            mScoAttempts = 0;
            mLinkUpSince = now;
            if (Build.VERSION.SDK_INT < 31 && mInCall && mRoute == Route.BLUETOOTH) {
                try {
                    mAudio.setBluetoothScoOn(true);
                } catch (Throwable ignored) {
                }
            }
            if (mInCall || recent) {
                EventLog.add(mApp, "call audio: Bluetooth call link up" + (recent ? " after " + (now - mBtRequestedAt) + " ms" : ""));
            }
            Log.i(TAG, "bluetooth audio connected");
        } else if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) {
            mLinkUpSince = 0;
            if (mInCall && mRoute == Route.BLUETOOTH) onBluetoothLinkLost(was);
        }
        if (mInCall) verifySoon();
    }

    /** The headset's call link went down while the call uses it. */
    private void onBluetoothLinkLost(int was) {
        long now = SystemClock.elapsedRealtime();
        if (now < mDeliberateBtChangeUntil) return;
        if (was == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
            if (mode() != AudioManager.MODE_IN_COMMUNICATION) {
                // The browser engine ended call mode (Teams closed the microphone): not the
                // headset's fault; it is put back right away.
                EventLog.add(mApp, "call audio: Bluetooth call link closed when Teams closed the microphone; restoring");
                return;
            }
            mDropsTotal++;
            mScoDrops.add(now);
            for (Iterator<Long> it = mScoDrops.iterator(); it.hasNext(); ) {
                if (now - it.next() > BT_DROP_WINDOW_MS) it.remove();
            }
            EventLog.add(mApp, "call audio: Bluetooth call link dropped (" + mScoDrops.size() + " in the last 2 min)");
            List<Route> avail = availableRoutes();
            if (!mFallbackDone && RoutePolicy.leaveHeadsetMic(mScoDrops.size(), avail)) {
                // The headset's microphone link isn't usable: keep the sound in the headset
                // (its music profile), take the voice from the phone's microphone.
                mFallbackDone = true;
                mUserPicked = false;
                EventLog.add(mApp, "call audio: the headset's microphone keeps dropping - now "
                        + label(Route.BT_PHONE_MIC) + " for this call");
                toast(R.string.bt_mic_fallback);
                applyRoute(Route.BT_PHONE_MIC);
                return;
            }
            mMain.removeCallbacks(mBtRetry);
            mMain.postDelayed(mBtRetry, 700);
        } else if (was == AudioManager.SCO_AUDIO_STATE_CONNECTING) {
            if (++mScoAttempts <= 3 && availableRoutes().contains(Route.BLUETOOTH)) {
                EventLog.add(mApp, "call audio: Bluetooth call link didn't come up; trying again (" + mScoAttempts + ")");
                mMain.removeCallbacks(mBtRetry);
                mMain.postDelayed(mBtRetry, 1000);
            } else {
                mUserPicked = false;
                List<Route> avail = availableRoutes();
                Route next = avail.contains(Route.BT_PHONE_MIC) ? Route.BT_PHONE_MIC
                        : avail.contains(Route.WIRED) ? Route.WIRED : RoutePolicy.phone(avail, mVideo || voiceOnSpeaker());
                Log.w(TAG, "bluetooth audio would not connect; falling back");
                EventLog.add(mApp, "call audio: Bluetooth call link would not come up; now " + label(next));
                applyRoute(next);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void applyRoute(Route r) {
        if (r == null) return;
        Route was = mRoute;
        long now = SystemClock.elapsedRealtime();
        try {
            if (r == Route.BT_PHONE_MIC) {
                // No call link and no call mode: the headset stays in its music profile and
                // plays the call; the phone's microphone records.
                mDeliberateBtChangeUntil = now + 3000;
                if (Build.VERSION.SDK_INT >= 31) {
                    mAudio.clearCommunicationDevice();
                } else {
                    if (mAudio.isBluetoothScoOn()) mAudio.setBluetoothScoOn(false);
                    mAudio.stopBluetoothSco();
                    mAudio.setSpeakerphoneOn(false);
                }
                if (mAudio.getMode() == AudioManager.MODE_IN_COMMUNICATION) mAudio.setMode(AudioManager.MODE_NORMAL);
            } else {
                setCommunicationMode();
                if (was == Route.BLUETOOTH && r != Route.BLUETOOTH) mDeliberateBtChangeUntil = now + 3000;
                if (r == Route.BLUETOOTH) {
                    boolean ok = bluetoothLinkUp() || requestBluetooth(false);
                    if (ok) {
                        mBtRetries = 0;
                    } else if (mInCall && ++mBtRetries <= 8) {
                        // The headset is connected but its call profile isn't available yet.
                        mMain.removeCallbacks(mBtRetry);
                        mMain.postDelayed(mBtRetry, 700);
                    } else {
                        Log.w(TAG, "no BLUETOOTH device for calls right now");
                    }
                } else if (Build.VERSION.SDK_INT >= 31) {
                    AudioDeviceInfo target = null;
                    for (AudioDeviceInfo d : mAudio.getAvailableCommunicationDevices()) {
                        if (matches(r, d.getType())) {
                            target = d;
                            break;
                        }
                    }
                    if (target == null || !mAudio.setCommunicationDevice(target)) {
                        if (target == null) Log.w(TAG, "no " + r + " device for calls right now");
                    }
                } else {
                    if (mAudio.isBluetoothScoOn()) {
                        mAudio.setBluetoothScoOn(false);
                        mAudio.stopBluetoothSco();
                    }
                    mAudio.setSpeakerphoneOn(r == Route.SPEAKER);
                }
            }
            mRoute = r;
            updateProximity();
            Log.i(TAG, "route=" + r);
            if (mInCall && was != r && was != null) {
                mMain.removeCallbacks(mDiagnose);
                mMain.postDelayed(mDiagnose, 2500);
            }
        } catch (Throwable t) {
            Log.w(TAG, "route change failed", t);
        }
        dispatch();
    }

    @SuppressWarnings("deprecation")
    private void resetRouting() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                mAudio.clearCommunicationDevice();
            } else {
                if (mAudio.isBluetoothScoOn()) {
                    mAudio.setBluetoothScoOn(false);
                    mAudio.stopBluetoothSco();
                }
                mAudio.setSpeakerphoneOn(false);
            }
            if (mWeSetMode && mAudio.getMode() == AudioManager.MODE_IN_COMMUNICATION) {
                mAudio.setMode(AudioManager.MODE_NORMAL);
            }
        } catch (Throwable t) {
            Log.w(TAG, "reset failed", t);
        }
        mWeSetMode = false;
        mWeRouted = false;
        mBtRequestedAt = 0;
    }

    // ------------------------------------------------------------------ what is really happening

    /**
     * Notes which microphone records (and whether Android silenced it). If the call is on
     * the Bluetooth headset but the phone's own microphone records - the microphone opened
     * before the headset's call link was up - the call is switched over once more so the
     * engine re-opens the microphone on the headset.
     */
    private void micDiagnostics(List<AudioRecordingConfiguration> configs) {
        if (!mInCall) return;
        String d = describeRecordings(configs);
        if (!d.equals(mLastMicDiag)) {
            mLastMicDiag = d;
            EventLog.add(mApp, "call audio: microphone " + d);
        }
        if (mMicFallbackDone || mRoute != Route.BLUETOOTH || !onlyPhoneMic(configs) || !bluetoothLinkUp()) return;
        long now = SystemClock.elapsedRealtime();
        if (mLinkUpSince == 0) mLinkUpSince = now;
        if (now - mLinkUpSince < 3000) {
            mMain.removeCallbacks(mDiagnose);
            mMain.postDelayed(mDiagnose, 3000 - (now - mLinkUpSince) + 200);
            return;
        }
        if (mNudged) {
            // Switching over didn't move the microphone (Android keeps a microphone that
            // started before the headset's call link on the phone): the voice comes from the
            // phone anyway. Make that the clean setup - sound in the headset's music profile,
            // the phone's microphone in normal mode - and say so, instead of a call link
            // whose microphone isn't used.
            mMicFallbackDone = true;
            mUserPicked = false;
            EventLog.add(mApp, "call audio: the phone's own microphone still records - the headset's microphone"
                    + " can't be switched on in this call; now " + label(Route.BT_PHONE_MIC));
            toast(R.string.bt_mic_not_switched);
            applyRoute(Route.BT_PHONE_MIC);
            return;
        }
        mNudged = true;
        EventLog.add(mApp, "call audio: the phone's own microphone records although the call is on the Bluetooth headset;"
                + " switching the call over once more so the headset's microphone is used");
        mDeliberateBtChangeUntil = now + 5000;
        applyRoute(RoutePolicy.phone(availableRoutes(), mVideo || voiceOnSpeaker()));
        mMain.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!mInCall) return;
                requestBluetooth(true);
                applyRoute(Route.BLUETOOTH);
            }
        }, 800);
    }

    /** Every microphone recording now is the phone's own (none: false). */
    private static boolean onlyPhoneMic(List<AudioRecordingConfiguration> configs) {
        if (configs == null || configs.isEmpty()) return false;
        for (AudioRecordingConfiguration c : configs) {
            AudioDeviceInfo dev = c.getAudioDevice();
            if (dev == null || dev.getType() != AudioDeviceInfo.TYPE_BUILTIN_MIC) return false;
        }
        return true;
    }

    /** The device the call's sound plays on (Android 13+ can tell), or -1. */
    private int voiceOutputType() {
        if (Build.VERSION.SDK_INT < 33) return -1;
        try {
            List<AudioDeviceInfo> out = mAudio.getAudioDevicesForAttributes(VOICE);
            return out == null || out.isEmpty() ? -1 : out.get(0).getType();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Where the call's sound plays (Android 13+ can tell). */
    private void outputDiagnostics() {
        if (Build.VERSION.SDK_INT < 33) return;
        try {
            List<AudioDeviceInfo> out = mAudio.getAudioDevicesForAttributes(VOICE);
            String d = out == null || out.isEmpty() ? "nowhere" : typeName(out.get(0).getType());
            if (!d.equals(mLastOutDiag)) {
                mLastOutDiag = d;
                EventLog.add(mApp, "call audio: sound plays on " + d);
            }
        } catch (Throwable ignored) {
        }
    }

    private static String describeRecordings(List<AudioRecordingConfiguration> configs) {
        if (configs == null || configs.isEmpty()) return "closed";
        StringBuilder sb = new StringBuilder();
        for (AudioRecordingConfiguration c : configs) {
            if (sb.length() > 0) sb.append("; ");
            AudioDeviceInfo dev = c.getAudioDevice();
            sb.append(dev == null ? "unknown device" : typeName(dev.getType()));
            sb.append(" (").append(sourceName(c.getClientAudioSource()));
            if (Build.VERSION.SDK_INT >= 29 && c.isClientSilenced()) sb.append(", silenced by Android");
            sb.append(')');
        }
        return sb.toString();
    }

    private String describeDevices() {
        boolean sco = false, a2dp = false, ble = false, wired = false, usb = false;
        try {
            for (AudioDeviceInfo d : mAudio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int t = d.getType();
                if (t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) sco = true;
                else if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) a2dp = true;
                else if (isBle(t)) ble = true;
                else if (t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES) wired = true;
                else if (t == AudioDeviceInfo.TYPE_USB_HEADSET || t == AudioDeviceInfo.TYPE_USB_DEVICE) usb = true;
            }
        } catch (Throwable ignored) {
        }
        StringBuilder sb = new StringBuilder("phone");
        if (sco || a2dp || ble) {
            String n = bluetoothName();
            sb.append(", Bluetooth").append(n != null ? " \"" + n + "\"" : "").append(" (");
            List<String> p = new ArrayList<>();
            if (sco) p.add("call profile");
            if (a2dp) p.add("music profile");
            if (ble) p.add("LE audio");
            for (int i = 0; i < p.size(); i++) sb.append(i > 0 ? " + " : "").append(p.get(i));
            sb.append(')');
        }
        if (wired) sb.append(", wired headset");
        if (usb) sb.append(", USB audio");
        return sb.toString();
    }

    @SuppressWarnings("deprecation")
    private String actualDescription() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                AudioDeviceInfo d = mAudio.getCommunicationDevice();
                return d == null ? "no device" : typeName(d.getType());
            }
            if (mAudio.isBluetoothScoOn()) return "Bluetooth";
            return mAudio.isSpeakerphoneOn() ? "speaker" : "earpiece";
        } catch (Throwable t) {
            return "?";
        }
    }

    static String typeName(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: return "earpiece";
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "speaker";
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: return "phone microphone";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "Bluetooth (call profile)";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "Bluetooth (music profile)";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "wired headset";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "wired headphones";
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "USB headset";
            case AudioDeviceInfo.TYPE_USB_DEVICE: return "USB audio";
            case AudioDeviceInfo.TYPE_TELEPHONY: return "telephony";
            default:
                if (Build.VERSION.SDK_INT >= 31 && (type == AudioDeviceInfo.TYPE_BLE_HEADSET
                        || type == AudioDeviceInfo.TYPE_BLE_SPEAKER)) return "Bluetooth LE audio";
                return "device type " + type;
        }
    }

    private static String sourceName(int source) {
        switch (source) {
            case MediaRecorder.AudioSource.VOICE_COMMUNICATION: return "call recording";
            case MediaRecorder.AudioSource.MIC: return "plain recording";
            case MediaRecorder.AudioSource.CAMCORDER: return "video recording";
            case MediaRecorder.AudioSource.VOICE_RECOGNITION: return "voice recognition";
            case MediaRecorder.AudioSource.UNPROCESSED: return "unprocessed";
            default: return "source " + source;
        }
    }

    /** Short English description of a route for the activity log. */
    static String label(Route r) {
        if (r == null) return "no device";
        switch (r) {
            case EARPIECE: return "earpiece";
            case SPEAKER: return "speaker";
            case WIRED: return "wired headset";
            case BLUETOOTH: return "Bluetooth headset (its microphone)";
            default: return "Bluetooth sound + phone microphone";
        }
    }

    private void toast(final int res) {
        try {
            Toast.makeText(mApp, res, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ focus & locks

    private void requestFocus() {
        try {
            mFocus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(VOICE)
                    .setOnAudioFocusChangeListener(new AudioManager.OnAudioFocusChangeListener() {
                        @Override
                        public void onAudioFocusChange(int focusChange) {
                        }
                    }).build();
            mAudio.requestAudioFocus(mFocus);
        } catch (Throwable t) {
            Log.w(TAG, "focus failed", t);
        }
    }

    private void abandonFocus() {
        if (mFocus != null) {
            try {
                mAudio.abandonAudioFocusRequest(mFocus);
            } catch (Throwable ignored) {
            }
            mFocus = null;
        }
    }

    private void acquireCpu() {
        if (mCpuLock == null) {
            mCpuLock = mPower.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "workdeck:call");
            mCpuLock.setReferenceCounted(false);
        }
        mCpuLock.acquire(4L * 60 * 60 * 1000);
    }

    private void updateProximity() {
        boolean want = mInCall && mRoute == Route.EARPIECE
                && mPower.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK);
        if (want) {
            if (mProximityLock == null) {
                mProximityLock = mPower.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "workdeck:proximity");
                mProximityLock.setReferenceCounted(false);
            }
            if (!mProximityLock.isHeld()) mProximityLock.acquire(4L * 60 * 60 * 1000);
        } else if (mProximityLock != null && mProximityLock.isHeld()) {
            mProximityLock.release();
        }
    }

    private void releaseLocks() {
        if (mCpuLock != null && mCpuLock.isHeld()) mCpuLock.release();
        if (mProximityLock != null && mProximityLock.isHeld()) mProximityLock.release();
    }
}
