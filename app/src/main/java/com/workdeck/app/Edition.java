package com.workdeck.app;

import android.content.Context;

/**
 * Which app this build is. WorkDeck itself (several accounts, Teams and Outlook, all
 * the tools), or a single-app edition: one account and one app only (e.g. "OMC" Teams),
 * in mobile view, with nothing on screen but the page and a text-size / zoom control.
 * Each edition is its own installed app, so it also has its own sign-in.
 *
 * Set by resources (res/values/edition.xml; the single-app builds override it).
 */
public final class Edition {
    private Edition() {}

    private static Boolean sSolo;
    private static String sAccount;
    private static Site sSite;

    /** A single-app build (one account, one site, minimal screen). */
    public static boolean solo(Context c) {
        if (sSolo == null) sSolo = c.getResources().getBoolean(R.bool.edition_solo);
        return sSolo;
    }

    /** Account name of a single-app build ("OMC", "CRAFTWW"), or "" for WorkDeck. */
    public static String account(Context c) {
        if (sAccount == null) sAccount = c.getString(R.string.edition_account);
        return sAccount;
    }

    /** The only site of a single-app build, or null for WorkDeck. */
    public static Site site(Context c) {
        if (!solo(c)) return null;
        if (sSite == null) sSite = Site.fromKey(c.getString(R.string.edition_site));
        return sSite == null ? Site.TEAMS : sSite;
    }

    /** The company is known to block phone browsers: start with a computer identity (phone layout). */
    public static boolean computerIdentity(Context c) {
        return c.getResources().getBoolean(R.bool.edition_computer_identity);
    }

    /**
     * Package of the same account's other single-app build (OMC Teams <-> OMC Outlook), so a
     * Teams link in Outlook (a meeting) opens in that account's Teams app, and vice versa.
     */
    public static String siblingPackage(Context c) {
        return c.getString(R.string.edition_sibling_package);
    }
}
