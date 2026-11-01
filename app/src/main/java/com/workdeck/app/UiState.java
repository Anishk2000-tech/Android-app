package com.workdeck.app;

/** What the user is looking at right now (written by MainActivity, read everywhere). */
public final class UiState {
    private UiState() {}

    /** True between MainActivity.onResume and onPause. */
    public static volatile boolean activityResumed;

    /** True while MainActivity is started (visible, possibly in picture-in-picture). */
    public static volatile boolean activityVisible;

    /** Key (account|site) of the tab currently shown in MainActivity, or null. */
    public static volatile String visibleTab;

    public static boolean isLookingAt(String tabKey) {
        return activityResumed && tabKey != null && tabKey.equals(visibleTab);
    }
}
