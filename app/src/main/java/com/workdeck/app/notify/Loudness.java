package com.workdeck.app.notify;

/** Loudness maths for notification sounds (pure Java, unit-tested on a plain JVM). */
public final class Loudness {
    private Loudness() {}

    /** Gain for a loudness in percent of the sound file: 300% = +9.5 dB = 954 millibels. */
    public static int millibels(int percent) {
        if (percent <= 100) return 0;
        return (int) Math.round(2000 * Math.log10(percent / 100.0));
    }
}
