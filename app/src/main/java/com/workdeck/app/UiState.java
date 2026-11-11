package com.workdeck.app;

/** What the user can see right now (shared between the activity, service and pages). */
public final class UiState {
    private UiState() {}

    /** True between MainActivity.onResume and onPause. */
    public static volatile boolean activityResumed;

    /** True while MainActivity is started (visible, possibly in picture-in-picture). */
    public static volatile boolean activityVisible;

    /** Key (account|site) of the tab currently shown in MainActivity, or null. */
    public static volatile String visibleTab;

    /**
     * The user is really looking at WorkDeck: it's in front with the phone unlocked and
     * the screen on, they touched it recently (or they're in a call). Not while WorkDeck
     * is merely shown over the lock screen for a ringing call, behind a dialog, or after
     * the phone was left alone. Pages are told they're hidden otherwise, so Teams/Outlook
     * never mark messages as read ("Seen") that the user hasn't seen.
     */
    public static volatile boolean userPresent;

    /**
     * When the user last really touched the app (a touch or key in its window;
     * SystemClock.elapsedRealtime), 0 if not since it started. Opening the app from a
     * notification or the launcher is not a touch.
     */
    public static volatile long lastTouchAt;

    public static boolean isLookingAt(String tabKey) {
        return userPresent && activityResumed && tabKey != null && tabKey.equals(visibleTab);
    }
}
