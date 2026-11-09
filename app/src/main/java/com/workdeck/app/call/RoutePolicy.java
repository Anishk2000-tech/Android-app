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
 * The browser engine also chooses a device whenever it opens the microphone - always a
 * headset or else the LOUDSPEAKER (it never picks the earpiece) - and a page re-applies
 * the microphone/speaker it saved last time at every call start. Neither is the user
 * speaking, so neither overrides this policy: only a change the page makes during the
 * call (the user picking another device in Teams) counts as the user's choice.
 */
public final class RoutePolicy {
    private RoutePolicy() {}

    /** The device a call should use when nobody chose one. */
    public static Route auto(List<Route> available, boolean video) {
        if (available.contains(Route.BLUETOOTH)) return Route.BLUETOOTH;
        if (available.contains(Route.WIRED)) return Route.WIRED;
        if (!video && available.contains(Route.EARPIECE)) return Route.EARPIECE;
        return Route.SPEAKER;
    }

    /** The phone's own speaker or earpiece (leaving a headset). */
    public static Route phone(List<Route> available, boolean video) {
        if (!video && available.contains(Route.EARPIECE)) return Route.EARPIECE;
        return Route.SPEAKER;
    }

    /**
     * A device was connected during a call: switch to a new headset (that's why one
     * connects mid-call); otherwise keep what's playing.
     */
    public static Route onDeviceAdded(Route current, List<Route> before, List<Route> now) {
        if (now.contains(Route.BLUETOOTH) && !before.contains(Route.BLUETOOTH)) return Route.BLUETOOTH;
        if (now.contains(Route.WIRED) && !before.contains(Route.WIRED) && current != Route.BLUETOOTH) return Route.WIRED;
        return current;
    }

    /** A device went away during a call: if it was the one in use, fall back. */
    public static Route onDeviceRemoved(Route current, List<Route> now, boolean video) {
        if (current != null && now.contains(current)) return current;
        return auto(now, video);
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

    /** Teams' microphone choice as a device: a headset mic means the headset (both ways). */
    public static Route micToRoute(String mic, List<Route> available, boolean video) {
        if ("bluetooth".equals(mic)) return Route.BLUETOOTH;
        if ("wired".equals(mic)) return Route.WIRED;
        if ("phone".equals(mic)) return phone(available, video);
        return null;
    }
}
