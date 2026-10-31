package com.workdeck.app;

/** The two web apps WorkDeck hosts. */
public enum Site {
    TEAMS("teams", R.string.site_teams, R.drawable.ic_tab_teams,
            "https://teams.microsoft.com/v2/", "https://teams.live.com/v2/"),
    OUTLOOK("outlook", R.string.site_outlook, R.drawable.ic_tab_outlook,
            "https://outlook.office.com/mail/", "https://outlook.live.com/mail/");

    public final String key;
    public final int titleRes;
    public final int iconRes;
    public final String workUrl;
    public final String personalUrl;

    Site(String key, int titleRes, int iconRes, String workUrl, String personalUrl) {
        this.key = key;
        this.titleRes = titleRes;
        this.iconRes = iconRes;
        this.workUrl = workUrl;
        this.personalUrl = personalUrl;
    }

    public static Site fromKey(String key) {
        for (Site s : values()) {
            if (s.key.equals(key)) return s;
        }
        return null;
    }
}
