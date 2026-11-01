package com.workdeck.app;

import android.content.Context;
import android.content.SharedPreferences;

/** All user settings in one place. */
public final class Prefs {
    public static final String ACCOUNT_WORK = "work";
    public static final String ACCOUNT_PERSONAL = "personal";

    public static final String VIEW_DESKTOP = "desktop";
    public static final String VIEW_MOBILE = "mobile";

    public static final String ROUTE_SPEAKER = "speaker";
    public static final String ROUTE_EARPIECE = "earpiece";

    public static final int[] DESKTOP_WIDTHS = {800, 1024, 1280, 1440, 1920};

    private final SharedPreferences sp;

    private static Prefs sInstance;

    public static synchronized Prefs get(Context c) {
        if (sInstance == null) sInstance = new Prefs(c.getApplicationContext());
        return sInstance;
    }

    private Prefs(Context c) {
        sp = c.getSharedPreferences("workdeck", Context.MODE_PRIVATE);
    }

    public SharedPreferences raw() {
        return sp;
    }

    // ---- setup ----
    public boolean setupDone() { return sp.getBoolean("setup_done", false); }
    public void setSetupDone(boolean v) { sp.edit().putBoolean("setup_done", v).apply(); }

    public String accountType() { return sp.getString("account_type", ACCOUNT_WORK); }
    public void setAccountType(String v) { sp.edit().putString("account_type", v).apply(); }

    public boolean siteEnabled(Site s) { return sp.getBoolean("enabled_" + s.key, true); }
    public void setSiteEnabled(Site s, boolean v) { sp.edit().putBoolean("enabled_" + s.key, v).apply(); }

    public String customUrl(Site s) { return sp.getString("url_" + s.key, ""); }
    public void setCustomUrl(Site s, String v) { sp.edit().putString("url_" + s.key, v == null ? "" : v.trim()).apply(); }

    public String homeUrl(Site s) {
        String custom = customUrl(s);
        if (custom.startsWith("https://")) return custom;
        return ACCOUNT_PERSONAL.equals(accountType()) ? s.personalUrl : s.workUrl;
    }

    // ---- view ----
    public String viewMode(Site s) { return sp.getString("view_" + s.key, VIEW_DESKTOP); }
    public void setViewMode(Site s, String v) { sp.edit().putString("view_" + s.key, v).apply(); }
    public boolean isDesktop(Site s) { return VIEW_DESKTOP.equals(viewMode(s)); }

    public int desktopWidth() { return sp.getInt("desktop_width", 1024); }
    public void setDesktopWidth(int v) { sp.edit().putInt("desktop_width", v).apply(); }

    public int textZoom(Site s) { return sp.getInt("text_zoom_" + s.key, 100); }
    public void setTextZoom(Site s, int v) { sp.edit().putInt("text_zoom_" + s.key, v).apply(); }

    public boolean barHidden() { return sp.getBoolean("bar_hidden", false); }
    public void setBarHidden(boolean v) { sp.edit().putBoolean("bar_hidden", v).apply(); }

    public String lastSite() { return sp.getString("last_site", Site.TEAMS.key); }
    public void setLastSite(Site s) { sp.edit().putString("last_site", s.key).apply(); }

    // ---- background ----
    public boolean keepAlive() { return sp.getBoolean("keep_alive", true); }
    public void setKeepAlive(boolean v) { sp.edit().putBoolean("keep_alive", v).apply(); }

    public boolean cpuWakeLock() { return sp.getBoolean("cpu_wakelock", true); }
    public void setCpuWakeLock(boolean v) { sp.edit().putBoolean("cpu_wakelock", v).apply(); }

    public boolean startOnBoot() { return sp.getBoolean("start_on_boot", true); }
    public void setStartOnBoot(boolean v) { sp.edit().putBoolean("start_on_boot", v).apply(); }

    public boolean autoRefresh() { return sp.getBoolean("auto_refresh", true); }
    public void setAutoRefresh(boolean v) { sp.edit().putBoolean("auto_refresh", v).apply(); }

    // ---- calls ----
    public boolean pipOnCall() { return sp.getBoolean("pip_on_call", true); }
    public void setPipOnCall(boolean v) { sp.edit().putBoolean("pip_on_call", v).apply(); }

    public String defaultRoute() { return sp.getString("default_route", ROUTE_SPEAKER); }
    public void setDefaultRoute(String v) { sp.edit().putString("default_route", v).apply(); }

    public boolean callAlerts() { return sp.getBoolean("call_alerts", true); }
    public void setCallAlerts(boolean v) { sp.edit().putBoolean("call_alerts", v).apply(); }

    public boolean callRingtone() { return sp.getBoolean("call_ringtone", true); }
    public void setCallRingtone(boolean v) { sp.edit().putBoolean("call_ringtone", v).apply(); }

    // ---- notifications ----
    public boolean notificationsEnabled(Site s) { return sp.getBoolean("notify_" + s.key, true); }
    public void setNotificationsEnabled(Site s, boolean v) { sp.edit().putBoolean("notify_" + s.key, v).apply(); }

    /** Links from Teams/Outlook open as tabs of the account (true) or in the phone's browser. */
    public boolean linksInApp() { return sp.getBoolean("links_in_app", true); }
    public void setLinksInApp(boolean v) { sp.edit().putBoolean("links_in_app", v).apply(); }

    /**
     * Sound for new-message notifications of a site: a content URI, "" = the phone's
     * default notification sound, "none" = silent.
     */
    public String notificationSound(Site s) { return sp.getString("sound_" + s.key, ""); }
    public void setNotificationSound(Site s, String v) { sp.edit().putString("sound_" + s.key, v == null ? "" : v).apply(); }

    /** Loudness of WorkDeck's message sounds, in percent of the sound file (100-400). */
    public int soundBoost() { return sp.getInt("sound_boost", 300); }
    public void setSoundBoost(int v) { sp.edit().putInt("sound_boost", Math.max(100, Math.min(400, v))).apply(); }

    // ---- advanced ----
    public boolean swShim() { return sp.getBoolean("sw_shim", true); }
    public void setSwShim(boolean v) { sp.edit().putBoolean("sw_shim", v).apply(); }

    public boolean webDebugging() { return sp.getBoolean("web_debugging", false); }
    public void setWebDebugging(boolean v) { sp.edit().putBoolean("web_debugging", v).apply(); }
}
