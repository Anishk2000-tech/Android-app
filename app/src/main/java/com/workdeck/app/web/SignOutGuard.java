package com.workdeck.app.web;

/**
 * Sign-outs nobody asked for - pure decisions, no Android calls (unit-tested).
 *
 * Microsoft 365 can sign web sessions out after a period without clicks or key presses in
 * the page ("idle session timeout"; Outlook on the web is covered, Teams is not). A normal
 * browser tab is closed or asleep long before that. WorkDeck keeps its pages running all
 * day for notifications, so nobody touches them for hours and the page then signs the
 * whole account out - and with it the mail notifications stop.
 *
 * So a page going to a sign-out address is only followed when the user asked for it: they
 * are looking at that tab and touched the screen moments ago (they tapped "Sign out").
 * Otherwise the sign-out is not carried out, and the site is loaded again: the sign-in
 * itself was never ended, so it opens signed in. Whatever Microsoft enforces on its
 * servers (sign-in again after a set time, a blocked or changed account) still applies -
 * then the reload shows the sign-in page.
 */
public final class SignOutGuard {
    private SignOutGuard() {}

    /** A touch this recent, on the tab on screen, means the user asked for the sign-out. */
    public static final long USER_WINDOW_MS = 45_000;
    /** After the user's own sign-out starts, its follow-up requests (other apps' sign-out frames) pass. */
    public static final long FOLLOW_UP_MS = 120_000;
    /** After a refused sign-out the site is loaded again, but not more often than this. */
    public static final long RELOAD_EVERY_MS = 10 * 60_000;

    public enum Action {
        /** The user's own sign-out: carry it out. */
        ALLOW,
        /** Not asked for: don't carry it out, and load the site again now. */
        REFUSE_AND_RELOAD,
        /** Not asked for, and the site was loaded again only just: don't carry it out (reload later). */
        REFUSE
    }

    /**
     * @param looking       the user is looking at this tab (in front, unlocked, screen on)
     * @param sinceTouchMs  time since the user last touched the app (large if never)
     * @param sinceAllowMs  time since a sign-out of this tab was last allowed (large if never)
     * @param sinceReloadMs time since this tab was last loaded again after a refused sign-out (large if never)
     */
    public static Action decide(boolean looking, long sinceTouchMs, long sinceAllowMs, long sinceReloadMs) {
        if (looking && sinceTouchMs >= 0 && sinceTouchMs < USER_WINDOW_MS) return Action.ALLOW;
        if (sinceAllowMs >= 0 && sinceAllowMs < FOLLOW_UP_MS) return Action.ALLOW;
        if (sinceReloadMs >= 0 && sinceReloadMs < RELOAD_EVERY_MS) return Action.REFUSE;
        return Action.REFUSE_AND_RELOAD;
    }

    /** "3 h 12 min", "45 s" - for the event log. */
    public static String ago(long ms) {
        if (ms < 0 || ms > 1000L * 3600 * 24 * 365) return "never";
        long s = ms / 1000;
        if (s < 90) return s + " s";
        long min = s / 60;
        if (min < 90) return min + " min";
        return (min / 60) + " h " + (min % 60) + " min";
    }
}
