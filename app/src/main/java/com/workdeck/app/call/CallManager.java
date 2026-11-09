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
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import com.workdeck.app.Prefs;

import java.util.ArrayList;
import java.util.HashMap;
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
    private static CallManager sInstance;

    public enum Route { SPEAKER, EARPIECE, BLUETOOTH, WIRED }

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

    private boolean mInCall;
    private long mCallStartedWall;
    private Route mRoute;
    private boolean mUserPicked;
    private Route mPendingRoute;
    private boolean mVideo;
    private boolean mWeSetMode;
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
                if (mInCall) verifySoon();
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
        } else {
            try {
                app.registerReceiver(new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent i) {
                        onScoState(i.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1));
                    }
                }, new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED));
            } catch (Throwable t) {
                Log.w(TAG, "SCO receiver unavailable", t);
            }
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
                if (!mInCall && mWeSetMode) resetRouting();
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
            mCallStartedWall = System.currentTimeMillis();
            mVideo = total(mPageVideo) > 0;
            acquireCpu();
            requestFocus();
            List<Route> avail = availableRoutes();
            mLastAvailable = avail;
            final Route pending = mPendingRoute != null && avail.contains(mPendingRoute) ? mPendingRoute : null;
            mUserPicked = pending != null;
            mRoute = pending != null ? pending : RoutePolicy.auto(avail, mVideo || voiceOnSpeaker());
            // Device requests the page made while the call was starting are its saved
            // preferences, not the user: they count as "seen" for this call.
            long now = SystemClock.elapsedRealtime();
            mPageMicSeen = mPageMicReq != null && now - mPageMicReqAt < PAGE_REQUEST_FRESH_MS;
            mPageSinkSeen = mPageSinkReq != null && now - mPageSinkReqAt < PAGE_REQUEST_FRESH_MS;
            if (!mPageMicSeen) mPageMicReq = null;
            if (!mPageSinkSeen) mPageSinkReq = null;
            mReassertCount = 0;
            mBtRetries = 0;
            mScoAttempts = 0;
            mReassertWindowStart = now;
            Log.i(TAG, "inCall=true (" + (mVideo ? "video" : "voice") + ", devices " + avail + ")");
            applyRoute(mRoute);
            mMain.postDelayed(mReapply, 900);
            mMain.postDelayed(mReapply, 2500);
            mMain.removeCallbacks(mVerifyLoop);
            mMain.postDelayed(mVerifyLoop, 2000);
        } else {
            Log.i(TAG, "inCall=false");
            mMain.removeCallbacks(mReapply);
            mMain.removeCallbacks(mVerifyLoop);
            mMain.removeCallbacks(mVerifyOnce);
            mMain.removeCallbacks(mBtRetry);
            resetRouting();
            abandonFocus();
            releaseLocks();
            mRoute = null;
            mPendingRoute = null;
            mPageMicReq = mPageSinkReq = null;
            mPageMicSeen = mPageSinkSeen = false;
        }
        dispatch();
    }

    /** WorkDeck's setting "Phone calls start on: speaker" (only when set explicitly). */
    private boolean voiceOnSpeaker() {
        Prefs p = Prefs.get(mApp);
        return p.raw().contains("default_route") && Prefs.ROUTE_SPEAKER.equals(p.defaultRoute());
    }

    // ------------------------------------------------------------------ routing

    public List<Route> availableRoutes() {
        List<Route> out = new ArrayList<>();
        boolean earpiece = false;
        boolean bt = false;
        boolean wired = false;
        try {
            AudioDeviceInfo[] devices = mAudio.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            for (AudioDeviceInfo d : devices) {
                int t = d.getType();
                if (t == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) earpiece = true;
                else if (t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || isBle(t)) bt = true;
                else if (t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                        || t == AudioDeviceInfo.TYPE_USB_HEADSET) wired = true;
            }
        } catch (Throwable ignored) {
        }
        out.add(Route.SPEAKER);
        if (earpiece && !wired) out.add(Route.EARPIECE);
        if (wired) out.add(Route.WIRED);
        if (bt) out.add(Route.BLUETOOTH);
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
        if (!mInCall) {
            // Outside a call, don't touch Android's audio mode (that would affect other
            // apps' sound); remember the choice for the next call instead.
            mPendingRoute = r;
            Log.i(TAG, "route " + r + " saved for the next call");
            return;
        }
        Log.i(TAG, "user picked " + r);
        mUserPicked = true;
        mBtRetries = 0;
        mReassertCount = 0;
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
        if (pick != null) setRouteByUser(pick);
    }

    /**
     * Teams chose a microphone. Android uses the microphone of the call's audio device,
     * so a Bluetooth/headset mic means routing the call there; the phone mic means
     * leaving any headset for the phone's own earpiece/speaker. Only a change during the
     * call is the user's choice (the first request is Teams re-applying its saved mic).
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
        if (pick != null) setRouteByUser(pick);
    }

    /** Cycles Speaker -> Earpiece/Wired -> Bluetooth -> Speaker. */
    public void cycleRoute() {
        List<Route> avail = availableRoutes();
        int i = mRoute == null ? -1 : avail.indexOf(mRoute);
        setRouteByUser(avail.get((i + 1) % avail.size()));
    }

    private void onDevicesChanged(boolean added) {
        // Let Teams refresh its device list (it re-reads enumerateDevices on 'devicechange').
        for (com.workdeck.app.web.BrowserTab t : com.workdeck.app.web.TabManager.get(mApp).allTabs()) {
            if (t.isCreated()) t.evaluate("window.__wdDevicesChanged && window.__wdDevicesChanged()");
        }
        List<Route> before = mLastAvailable;
        List<Route> now = availableRoutes();
        mLastAvailable = now;
        if (!mInCall || mRoute == null) return;
        Route next = added ? RoutePolicy.onDeviceAdded(mRoute, before, now)
                : RoutePolicy.onDeviceRemoved(mRoute, now, mVideo || voiceOnSpeaker());
        if (next != mRoute) {
            Log.i(TAG, (added ? "headset connected: " : "device gone: ") + mRoute + " -> " + next);
            mUserPicked = false;
            mBtRetries = 0;
            applyRoute(next);
        }
        // The engine reacts to the same event by picking its own device: check soon.
        verifySoon();
    }

    // ------------------------------------------------------------------ keeping the route

    private final Runnable mReapply = new Runnable() {
        @Override
        public void run() {
            if (mInCall && mRoute != null && !actualMatches(mRoute)) applyRoute(mRoute);
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

    /** Bluetooth headset connected but its call profile isn't ready yet: try again shortly. */
    private final Runnable mBtRetry = new Runnable() {
        @Override
        public void run() {
            if (mInCall && mRoute == Route.BLUETOOTH && !actualMatches(Route.BLUETOOTH)) applyRoute(Route.BLUETOOTH);
        }
    };

    private void verify() {
        if (!mInCall || mRoute == null) return;
        List<Route> avail = availableRoutes();
        if (!avail.contains(mRoute)) {
            mUserPicked = false;
            applyRoute(RoutePolicy.onDeviceRemoved(mRoute, avail, mVideo || voiceOnSpeaker()));
            return;
        }
        boolean modeOk;
        try {
            modeOk = mAudio.getMode() == AudioManager.MODE_IN_COMMUNICATION;
        } catch (Throwable t) {
            modeOk = true;
        }
        if (actualMatches(mRoute) && modeOk) return;
        long now = SystemClock.elapsedRealtime();
        if (now - mReassertWindowStart > 60000) {
            mReassertWindowStart = now;
            mReassertCount = 0;
        }
        if (++mReassertCount > REASSERT_MAX_PER_MIN) {
            // Something keeps changing it (another app's call, the system): let it be for now.
            return;
        }
        Log.i(TAG, "call audio moved away from " + mRoute + (modeOk ? "" : " (mode reset)") + "; restoring");
        applyRoute(mRoute);
    }

    /** Is Android currently sending call audio where we want it? */
    @SuppressWarnings("deprecation")
    private boolean actualMatches(Route r) {
        try {
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
            default: return type == AudioDeviceInfo.TYPE_WIRED_HEADSET || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                    || type == AudioDeviceInfo.TYPE_USB_HEADSET;
        }
    }

    /** Android < 12: Bluetooth call audio (SCO) connects asynchronously. */
    @SuppressWarnings("deprecation")
    private void onScoState(int state) {
        if (!mInCall || mRoute != Route.BLUETOOTH) return;
        if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
            mScoAttempts = 0;
            try {
                mAudio.setBluetoothScoOn(true);
            } catch (Throwable ignored) {
            }
            Log.i(TAG, "bluetooth audio connected");
        } else if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) {
            if (++mScoAttempts <= 3 && availableRoutes().contains(Route.BLUETOOTH)) {
                mMain.postDelayed(mBtRetry, 1000);
            } else {
                Log.w(TAG, "bluetooth audio would not connect; falling back");
                mUserPicked = false;
                List<Route> avail = availableRoutes();
                applyRoute(avail.contains(Route.WIRED) ? Route.WIRED : RoutePolicy.phone(avail, mVideo || voiceOnSpeaker()));
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void applyRoute(Route r) {
        if (r == null) return;
        try {
            if (mAudio.getMode() != AudioManager.MODE_IN_COMMUNICATION) {
                mAudio.setMode(AudioManager.MODE_IN_COMMUNICATION);
                mWeSetMode = true;
            }
            if (Build.VERSION.SDK_INT >= 31) {
                AudioDeviceInfo target = null;
                for (AudioDeviceInfo d : mAudio.getAvailableCommunicationDevices()) {
                    if (matches(r, d.getType())) {
                        target = d;
                        break;
                    }
                }
                boolean ok = target != null && mAudio.setCommunicationDevice(target);
                if (ok) {
                    mBtRetries = 0;
                } else if (r == Route.BLUETOOTH && mInCall && ++mBtRetries <= 8) {
                    // The headset is connected but its call profile isn't available yet.
                    mMain.removeCallbacks(mBtRetry);
                    mMain.postDelayed(mBtRetry, 700);
                } else if (target == null) {
                    Log.w(TAG, "no " + r + " device for calls right now");
                }
            } else {
                if (r == Route.BLUETOOTH) {
                    mAudio.setSpeakerphoneOn(false);
                    if (!mAudio.isBluetoothScoOn()) mAudio.startBluetoothSco();
                    mAudio.setBluetoothScoOn(true);
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
    }

    // ------------------------------------------------------------------ focus & locks

    private void requestFocus() {
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            mFocus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attrs)
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
