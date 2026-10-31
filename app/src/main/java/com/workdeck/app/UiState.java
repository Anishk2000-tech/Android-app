package com.workdeck.app;

/** What the user is looking at right now (written by MainActivity, read everywhere). */
public final class UiState {
    private UiState() {}

    /** True between MainActivity.onResume and onPause. */
    public static volatile boolean activityResumed;

    /** True while MainActivity is started (visible, possibly in picture-in-picture). */
    public static volatile boolean activityVisible;

    /** The site currently shown in MainActivity, or null. */
    public static volatile Site visibleSite;

    public static boolean isLookingAt(Site s) {
        return activityResumed && visibleSite == s;
    }
}
