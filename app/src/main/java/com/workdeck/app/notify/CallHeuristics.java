package com.workdeck.app.notify;

import java.util.Locale;
import java.util.regex.Pattern;

/** Recognises incoming-call notifications from Teams (pure Java, unit-tested). */
public final class CallHeuristics {
    private static final Pattern CALL_TEXT = Pattern.compile(
            "(?i)(incoming (video |audio )?call|is calling|calling you|wants to (video )?call|(video |audio )?call from)");

    private CallHeuristics() {}

    public static boolean looksLikeIncomingCall(String title, String body, boolean requireInteraction) {
        String text = (title == null ? "" : title) + " " + (body == null ? "" : body);
        if (CALL_TEXT.matcher(text).find()) return true;
        return requireInteraction && text.toLowerCase(Locale.ROOT).contains("call");
    }
}
