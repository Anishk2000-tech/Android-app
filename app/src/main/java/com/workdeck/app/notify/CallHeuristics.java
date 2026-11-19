package com.workdeck.app.notify;

import java.util.regex.Pattern;

/**
 * Recognises incoming-call notifications from Teams (pure Java, unit-tested).
 *
 * A call notification is one of Teams' call templates as a whole - "Incoming call",
 * "Alice is calling you", "Incoming video call from Alice" - as its title or as its entire
 * text. A chat message's notification carries what the person wrote, which can contain
 * any of these words ("call me", "Can you call me?", "calling you in 5", "call from the
 * office"): that never rings, however it is marked. Neither do missed calls, call
 * summaries, or a call or meeting started in a group chat. (Teams' own call screen and
 * ringtone in the page are recognised separately.)
 */
public final class CallHeuristics {
    /** Words that make it something other than a call ringing now. */
    private static final Pattern NOT_RINGING = Pattern.compile(
            "(?i)\\b(missed|ended|started|left|joined|recording|recorded|transcript|voicemail|declined|cancel+ed|summary)\\b");
    private static final String END = "[.!… ]*$";
    /** "Incoming (video/audio/voice/group) call (from Alice)". */
    private static final Pattern INCOMING = Pattern.compile(
            "(?i)^incoming (video |audio |voice |group |teams )?call( from .{1,80})?" + END);
    /** "Alice is (video/audio) calling you", "Alice wants to (video) call you". */
    private static final Pattern IS_CALLING_YOU = Pattern.compile(
            "(?i)^\\S.{0,79}? (is (video |audio |voice )?calling you|wants to (video |audio |voice )?call you)" + END);
    /** As a title only (a chat's title is the sender's name): "Alice is calling", "Video call from Alice". */
    private static final Pattern TITLE_ONLY = Pattern.compile(
            "(?i)^(\\S.{0,79}? is (video |audio |voice )?calling|(video |audio |voice )?call from \\S.{0,79})" + END);

    private CallHeuristics() {}

    /** @deprecated whether the page marked it "requireInteraction" says nothing about calls. */
    @Deprecated
    public static boolean looksLikeIncomingCall(String title, String body, boolean requireInteraction) {
        return matchedTemplate(title, body) != null;
    }

    public static boolean looksLikeIncomingCall(String title, String body) {
        return matchedTemplate(title, body) != null;
    }

    /** Which part matched a call template ("title" / "text"), or null: not an incoming call. */
    public static String matchedTemplate(String title, String body) {
        String t = clean(title), b = clean(body);
        if (NOT_RINGING.matcher(t).find() || NOT_RINGING.matcher(b).find()) return null;
        if (INCOMING.matcher(t).find() || IS_CALLING_YOU.matcher(t).find() || TITLE_ONLY.matcher(t).find()) return "title";
        if (INCOMING.matcher(b).find() || IS_CALLING_YOU.matcher(b).find()) return "text";
        return null;
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }
}
