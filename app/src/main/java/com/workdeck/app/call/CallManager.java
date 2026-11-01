package com.workdeck.app.call;

import android.content.Context;
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
import com.workdeck.app.Site;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks whether a Teams call is active and controls where call audio goes.
 *
 * A call is "active" while the app is recording from the microphone. Start is
 * signalled early by the page (getUserMedia hook) or by the WebView permission
 * grant, then confirmed/ended by polling Android's active recording list.
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
    private final Map<Site, Integer> mPageMic = new EnumMap<>(Site.class);
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
        startPolling();
        pollNow();
    }

    public void onPageMicDelta(Site site, int delta) {
        Integer cur = mPageMic.get(site);
        int n = Math.max(0, (cur == null ? 0 : cur) + delta);
        mPageMic.put(site, n);
        if (delta > 0) mSuspectUntil = SystemClock.elapsedRealtime() + 20000;
        startPolling();
        pollNow();
    }

    public void resetPageMic(Site site) {
        mPageMic.put(site, 0);
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
            // Let Chromium switch the audio mode first, then apply our routing.
            final Route pending = mPendingRoute != null && availableRoutes().contains(mPendingRoute) ? mPendingRoute : null;
            mUserPicked = pending != null;
            mMain.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (mInCall) applyRoute(pending != null ? pending : pickAutoRoute());
                }
            }, 800);
            mRoute = pending != null ? pending : pickAutoRoute();
        } else {
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
        applyRoute(r);
    }

    /** Cycles Speaker -> Earpiece/Wired -> Bluetooth -> Speaker. */
    public void cycleRoute() {
        List<Route> avail = availableRoutes();
        int i = mRoute == null ? -1 : avail.indexOf(mRoute);
        setRouteByUser(avail.get((i + 1) % avail.size()));
    }

    private void onDevicesChanged(boolean added) {
        // Let Teams refresh its device list (it re-reads enumerateDevices on 'devicechange').
        for (com.workdeck.app.web.BrowserTab t : com.workdeck.app.web.TabManager.get(mApp).enabledTabs()) {
            if (t.isCreated()) t.evaluate("window.__wdDevicesChanged && window.__wdDevicesChanged()");
        }
        if (!mInCall) return;
        List<Route> avail = availableRoutes();
        if (added && !mUserPicked && avail.contains(Route.BLUETOOTH) && mRoute != Route.BLUETOOTH) {
            applyRoute(Route.BLUETOOTH);
        } else if (mRoute != null && !avail.contains(mRoute)) {
            mUserPicked = false;
            applyRoute(pickAutoRoute());
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
                    int t = d.getType();
                    boolean match;
                    switch (r) {
                        case SPEAKER: match = t == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER; break;
                        case EARPIECE: match = t == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE; break;
                        case BLUETOOTH: match = t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || isBle(t); break;
                        default: match = t == AudioDeviceInfo.TYPE_WIRED_HEADSET
                                || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || t == AudioDeviceInfo.TYPE_USB_HEADSET;
                    }
                    if (match) {
                        target = d;
                        break;
                    }
                }
                if (target != null) mAudio.setCommunicationDevice(target);
            } else {
                if (r == Route.BLUETOOTH) {
                    mAudio.setSpeakerphoneOn(false);
                    mAudio.startBluetoothSco();
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
