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
 * A call is "active" while the app is recording from the microphone. Start is
 * signalled early by the page (getUserMedia hook) or by the WebView permission
 * grant, then confirmed/ended by polling Android's active recording list.
 *
 * Routing: WorkDeck is the single owner of the call's audio device. The browser
 * engine also picks a device whenever it (re)opens audio, and headsets come and go,
 * so the chosen route is verified continuously and re-applied when something else
 * changed it (within limits, so it never fights the user's own system controls).
 */
public final class CallManager {
    private static final String TAG = "WorkDeck/Call";
    private static final long POLL_MS = 2000;
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
    private final List<Listener> mListeners = new ArrayList<>();

    private boolean mInCall;
    private long mCallStartedWall;
    private Route mRoute;
    private boolean mUserPicked;
    private Route mPendingRoute;
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
                pollNow();
            }
        }, mMain);
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                mAudio.addOnCommunicationDeviceChangedListener(app.getMainExecutor(),
                        new AudioManager.OnCommunicationDeviceChangedListener() {
                            @Override
                            public void onCommunicationDeviceChanged(AudioDeviceInfo device) {
                                if (mInCall) {
                                    mMain.removeCallbacks(mVerifyOnce);
                                    mMain.postDelayed(mVerifyOnce, 400);
                                }
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
    }

    // ------------------------------------------------------------------ signals

    public void onMicPossiblyStarted() {
        mSuspectUntil = SystemClock.elapsedRealtime() + 20000;
        prepareCallAudio();
        startPolling();
        pollNow();
    }

    /** A tab (account|site key) opened (+1) or closed (-1) a microphone track. */
    public void onPageMicDelta(String tabKey, int delta) {
        Integer cur = mPageMic.get(tabKey);
        int n = Math.max(0, (cur == null ? 0 : cur) + delta);
        mPageMic.put(tabKey, n);
        if (delta > 0) {
            mSuspectUntil = SystemClock.elapsedRealtime() + 20000;
            prepareCallAudio();
        }
        startPolling();
        pollNow();
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

    public void resetPageMic(String tabKey) {
        mPageMic.put(tabKey, 0);
        if (mInCall) pollNow();
    }

    private int pageMicTotal() {
        int n = 0;
        for (Integer v : mPageMic.values()) n += v == null ? 0 : v;
        return n;
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

    private void pollNow() {
        mMain.removeCallbacks(mPollOnce);
        mMain.postDelayed(mPollOnce, 300);
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
            boolean keep = mInCall || pageMicTotal() > 0 || SystemClock.elapsedRealtime() < mSuspectUntil;
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
        int page = pageMicTotal();
        if (!mInCall) {
            if (rec && (page > 0 || SystemClock.elapsedRealtime() < mSuspectUntil)) setInCall(true);
            return;
        }
        if (rec) {
            mEmptyPolls = 0;
            return;
        }
        mEmptyPolls++;
        // If the page still reports a live mic track (e.g. muted), wait a bit longer.
        if (mEmptyPolls >= (page > 0 ? 6 : 2)) setInCall(false);
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
        Log.i(TAG, "inCall=" + inCall);
        if (inCall) {
            mCallStartedWall = System.currentTimeMillis();
            acquireCpu();
            requestFocus();
            final Route pending = mPendingRoute != null && availableRoutes().contains(mPendingRoute) ? mPendingRoute : null;
            mUserPicked = pending != null;
            mRoute = pending != null ? pending : pickAutoRoute();
            mReassertCount = 0;
            mBtRetries = 0;
            mScoAttempts = 0;
            mReassertWindowStart = SystemClock.elapsedRealtime();
            // Apply now, and again once the browser engine has finished setting up its own
            // audio (it picks a device when it opens the call's audio streams).
            applyRoute(mRoute);
            mMain.postDelayed(mReapply, 900);
            mMain.postDelayed(mReapply, 2500);
            mMain.removeCallbacks(mVerifyLoop);
            mMain.postDelayed(mVerifyLoop, 4000);
        } else {
            mMain.removeCallbacks(mReapply);
            mMain.removeCallbacks(mVerifyLoop);
            mMain.removeCallbacks(mVerifyOnce);
            mMain.removeCallbacks(mBtRetry);
            resetRouting();
            abandonFocus();
            releaseLocks();
            mRoute = null;
            mPendingRoute = null;
        }
        dispatch();
    }

    // ------------------------------------------------------------------ routing

    public List<Route> availableRoutes() {
        List<Route> out = new ArrayList<>();
        boolean earpiece = false;
        boolean bt = false;
        boolean wired = false;
        AudioDeviceInfo[] devices = mAudio.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        for (AudioDeviceInfo d : devices) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) earpiece = true;
            else if (t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || isBle(t)) bt = true;
            else if (t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                    || t == AudioDeviceInfo.TYPE_USB_HEADSET) wired = true;
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

    private Route pickAutoRoute() {
        List<Route> avail = availableRoutes();
        if (avail.contains(Route.BLUETOOTH)) return Route.BLUETOOTH;
        if (avail.contains(Route.WIRED)) return Route.WIRED;
        if (Prefs.ROUTE_EARPIECE.equals(Prefs.get(mApp).defaultRoute()) && avail.contains(Route.EARPIECE)) {
            return Route.EARPIECE;
        }
        return Route.SPEAKER;
    }

    /** Called from the UI / notification buttons / Teams' speaker picker. */
    public void setRouteByUser(Route r) {
        if (r == null) return;
        if (!mInCall) {
            // Outside a call, don't touch Android's audio mode (that would affect other
            // apps' sound); remember the choice for the next call instead.
            mPendingRoute = r;
            Log.i(TAG, "route " + r + " saved for the next call");
            return;
        }
        mUserPicked = true;
        mBtRetries = 0;
        mReassertCount = 0;
        applyRoute(r);
    }

    /**
     * Teams picked a microphone. Android uses the microphone of the call's audio device,
     * so a Bluetooth/headset mic means routing the call there; the phone mic means
     * leaving any headset for the phone's own earpiece/speaker.
     */
    public void setMicByUser(String mic) {
        if (mic == null) return;
        Route target = null;
        Route current = mInCall ? mRoute : mPendingRoute;
        if ("bluetooth".equals(mic)) target = Route.BLUETOOTH;
        else if ("wired".equals(mic)) target = Route.WIRED;
        else if ("phone".equals(mic) && (current == Route.BLUETOOTH || current == Route.WIRED
                || (current == null && pickAutoRoute() != phoneRoute()))) {
            target = phoneRoute();
        }
        Log.i(TAG, "microphone " + mic + " -> route " + target);
        if (target != null) setRouteByUser(target);
    }

    /** The phone's own speaker or earpiece, as set in Settings. */
    private Route phoneRoute() {
        return Prefs.ROUTE_EARPIECE.equals(Prefs.get(mApp).defaultRoute()) && availableRoutes().contains(Route.EARPIECE)
                ? Route.EARPIECE : Route.SPEAKER;
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
        if (!mInCall) return;
        List<Route> avail = availableRoutes();
        if (added && avail.contains(Route.BLUETOOTH) && mRoute != Route.BLUETOOTH) {
            // A headset was just connected: that's where the user wants to talk.
            mUserPicked = false;
            applyRoute(Route.BLUETOOTH);
        } else if (added && avail.contains(Route.WIRED) && mRoute != Route.WIRED && mRoute != Route.BLUETOOTH) {
            mUserPicked = false;
            applyRoute(Route.WIRED);
        } else if (mRoute != null && !avail.contains(mRoute)) {
            mUserPicked = false;
            applyRoute(pickAutoRoute());
        }
    }

    // ------------------------------------------------------------------ keeping the route

    private final Runnable mReapply = new Runnable() {
        @Override
        public void run() {
            if (mInCall && mRoute != null && !actualMatches(mRoute)) applyRoute(mRoute);
        }
    };

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
            if (mInCall) mMain.postDelayed(this, 2500);
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
        if (!availableRoutes().contains(mRoute)) {
            mUserPicked = false;
            applyRoute(pickAutoRoute());
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
        if (now - mReassertWindowStart > 30000) {
            mReassertWindowStart = now;
            mReassertCount = 0;
        }
        if (++mReassertCount > 6) {
            // Something keeps changing it (e.g. the user in Android's own controls): let it be.
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
                applyRoute(availableRoutes().contains(Route.WIRED) ? Route.WIRED : phoneRoute());
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
