package com.workdeck.app.call;

import com.workdeck.app.call.CallManager.Route;

import java.util.List;

/**
 * Where call audio should go - pure decisions, no Android calls (unit-tested).
 *
 * Like a phone's own dialler: a connected Bluetooth or wired headset first; otherwise
 * the earpiece for a voice call (held to the ear, screen off by the proximity sensor)
 * and the loudspeaker for a video call. The user's own choice during a call wins until
 * a headset is connected or the chosen device disappears.
 *
 * A Bluetooth headset can be used two ways: for listening and talking (its call profile:
 * the headset's microphone, telephone sound), or {@link Route#BT_PHONE_MIC}: the call
 * plays in the headset (its music profile) while the user talks into the phone. The latter
 * is for headsets whose microphone link keeps breaking up, and for headphones that have
 * no call profile at all. When the user picks it, it stays the choice for later calls.
 *
 * The browser engine also chooses a device whenever it opens the microphone - always a
 * headset or else the LOUDSPEAKER (it never picks the earpiece) - and a page re-applies
 * the microphone/speaker it saved last time at every call start. Neither is the user
 * speaking, so neither overrides this policy: only a change the page makes during the
 * call (the user picking another device in Teams) counts as the user's choice.
 */
public final class RoutePolicy {
    private RoutePolicy() {}

    /** Bluetooth call-link drops within a call after which the headset's microphone is left. */
    public static final int BT_DROPS_TO_LEAVE_HEADSET_MIC = 2;

    /** The device a call should use when nobody chose one. */
    public static Route auto(List<Route> available, boolean video) {
        return auto(available, video, false);
    }

    /**
     * @param listenOnly the user prefers Bluetooth for listening only (talking into the phone)
     */
    public static Route auto(List<Route> available, boolean video, boolean listenOnly) {
        if (listenOnly && available.contains(Route.BT_PHONE_MIC)) return Route.BT_PHONE_MIC;
        if (available.contains(Route.BLUETOOTH)) return Route.BLUETOOTH;
        if (available.contains(Route.WIRED)) return Route.WIRED;
        // Bluetooth headphones without a call profile: the call plays there, the phone's mic.
        if (available.contains(Route.BT_PHONE_MIC)) return Route.BT_PHONE_MIC;
        return phone(available, video);
    }

    /** The phone's own speaker or earpiece (leaving a headset). */
    public static Route phone(List<Route> available, boolean video) {
        if (!video && available.contains(Route.EARPIECE)) return Route.EARPIECE;
        return Route.SPEAKER;
    }

    static boolean isBluetooth(Route r) {
        return r == Route.BLUETOOTH || r == Route.BT_PHONE_MIC;
    }

    /**
     * A device was connected during a call: switch to a new headset (that's why one
     * connects mid-call); otherwise keep what's playing.
     */
    public static Route onDeviceAdded(Route current, List<Route> before, List<Route> now) {
        return onDeviceAdded(current, before, now, false);
    }

    public static Route onDeviceAdded(Route current, List<Route> before, List<Route> now, boolean listenOnly) {
        if (now.contains(Route.BLUETOOTH) && !before.contains(Route.BLUETOOTH)) {
            return listenOnly && now.contains(Route.BT_PHONE_MIC) ? Route.BT_PHONE_MIC : Route.BLUETOOTH;
        }
        // Headphones with only a music profile (or its call profile isn't up yet).
        if (now.contains(Route.BT_PHONE_MIC) && !before.contains(Route.BT_PHONE_MIC)
                && !now.contains(Route.BLUETOOTH) && !isBluetooth(current) && current != Route.WIRED) {
            return Route.BT_PHONE_MIC;
        }
        if (now.contains(Route.WIRED) && !before.contains(Route.WIRED) && !isBluetooth(current)) return Route.WIRED;
        return current;
    }

    /** A device went away during a call: if it was the one in use, fall back. */
    public static Route onDeviceRemoved(Route current, List<Route> now, boolean video) {
        return onDeviceRemoved(current, now, video, false);
    }

    public static Route onDeviceRemoved(Route current, List<Route> now, boolean video, boolean listenOnly) {
        if (current != null && now.contains(current)) return current;
        // The headset's call profile went away but it still plays music: keep listening there.
        if (current == Route.BLUETOOTH && now.contains(Route.BT_PHONE_MIC)) return Route.BT_PHONE_MIC;
        return auto(now, video, listenOnly);
    }

    /**
     * The local camera was switched on during a call: a voice call held to the ear
     * becomes a video call looked at, so the earpiece makes way for the loudspeaker -
     * unless the user picked the earpiece themselves.
     */
    public static Route onVideoStarted(Route current, boolean userPicked) {
        if (current == Route.EARPIECE && !userPicked) return Route.SPEAKER;
        return current;
    }

    /**
     * The page asked for a device (Teams' speaker or microphone choice). The first request
     * of a call is the page re-applying its saved choice: ignored. Later ones that change
     * the request are the user choosing in Teams: returned. {@code previous} is the page's
     * last request in this call (null: none yet).
     */
    public static Route fromPage(Route requested, Route previous, boolean firstOfCall, List<Route> available) {
        if (requested == null || firstOfCall || requested == previous) return null;
        return available.contains(requested) ? requested : null;
    }

    /**
     * Teams' microphone choice as a device: a headset mic means the headset (both ways);
     * the phone's microphone with Bluetooth headphones connected means: keep listening on
     * them, talk into the phone.
     */
    public static Route micToRoute(String mic, List<Route> available, boolean video) {
        if ("bluetooth".equals(mic)) return Route.BLUETOOTH;
        if ("wired".equals(mic)) return Route.WIRED;
        if ("phone".equals(mic)) return available.contains(Route.BT_PHONE_MIC) ? Route.BT_PHONE_MIC : phone(available, video);
        return null;
    }

    /**
     * The headset's call link dropped {@code drops} times in the last two minutes of a
     * call: its microphone is not usable (the other person hears the voice break up), so
     * the call moves to listening on the headset and talking into the phone - if possible.
     */
    public static boolean leaveHeadsetMic(int drops, List<Route> available) {
        return drops >= BT_DROPS_TO_LEAVE_HEADSET_MIC && available.contains(Route.BT_PHONE_MIC);
    }
}
