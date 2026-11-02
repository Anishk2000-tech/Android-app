package com.workdeck.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * One Microsoft account (e.g. a company) with its own Teams + Outlook and its own
 * settings. Each account lives in its own WebView profile, so cookies, sign-in and
 * storage are completely separate from the other accounts.
 */
public final class Account {
    public static final int[] COLORS = {
            0xFF0F766E, 0xFF7C3AED, 0xFFC2410C, 0xFF0369A1, 0xFFBE185D, 0xFF4D7C0F, 0xFFB45309, 0xFF334155,
    };

    public final String id;
    /** WebView profile name; the first (migrated) account uses WebView's default profile. */
    public final String profile;
    public String name;
    public String type = Prefs.ACCOUNT_WORK;
    public int color;
    /** Keep this account's apps running in the background for notifications. */
    public boolean background = true;
    public Site lastSite = Site.TEAMS;

    private final Map<Site, SiteSettings> mSites = new EnumMap<>(Site.class);
    /** Extra tabs (links opened from this account), in the order they were opened. */
    private final List<WebTab> mWebTabs = new ArrayList<>();
    private int mNextWebId = 1;
    /** Most extra tabs an account keeps; opening more closes the oldest. */
    public static final int MAX_WEB_TABS = 10;

    /** A link opened in this account (shown in its drop-up until closed with ×). */
    public static final class WebTab {
        public final String id;
        public String url;
        public String title;
        public boolean desktop;
        public int textZoom = 100;
        /** The company blocks phone browsers here: always present a computer browser. */
        public boolean computerIdentity;

        WebTab(String id, String url, String title, boolean desktop) {
            this.id = id;
            this.url = url;
            this.title = title == null ? "" : title;
            this.desktop = desktop;
        }
    }

    static final class SiteSettings {
        boolean enabled = true;
        String view = Prefs.VIEW_DESKTOP;
        int textZoom = 100;
        boolean notify = true;
        String customUrl = "";
        /** Opened at least once: start it in the background from then on. */
        boolean opened;
        /** The company blocks phone browsers here: mobile view keeps a computer identity. */
        boolean computerIdentity;
    }

    Account(String id, String profile, String name, int color) {
        this.id = id;
        this.profile = profile;
        this.name = name;
        this.color = color;
        for (Site s : Site.values()) mSites.put(s, new SiteSettings());
    }

    SiteSettings site(Site s) {
        return mSites.get(s);
    }

    public String key(Site s) {
        return id + "|" + s.key;
    }

    public String webKey(String webId) {
        return id + "|w" + webId;
    }

    // ---- extra (link) tabs ----
    public List<WebTab> webTabs() {
        return new ArrayList<>(mWebTabs);
    }

    public WebTab webTab(String webId) {
        if (webId == null) return null;
        for (WebTab w : mWebTabs) if (w.id.equals(webId)) return w;
        return null;
    }

    public WebTab addWebTab(String url, boolean desktop) {
        WebTab w = new WebTab(String.valueOf(mNextWebId++), url, "", desktop);
        mWebTabs.add(w);
        return w;
    }

    public void removeWebTab(String webId) {
        WebTab w = webTab(webId);
        if (w != null) mWebTabs.remove(w);
    }

    /** Oldest extra tab, closed first when the account has too many. */
    public WebTab oldestWebTab() {
        return mWebTabs.isEmpty() ? null : mWebTabs.get(0);
    }

    /** Moves an extra tab to the end (most recently used). */
    public void touchWebTab(String webId) {
        WebTab w = webTab(webId);
        if (w != null && mWebTabs.remove(w)) mWebTabs.add(w);
    }

    public int colorValue() {
        return COLORS[Math.abs(color) % COLORS.length];
    }

    public boolean usesDefaultProfile() {
        return com.workdeck.app.web.WebViewSupport.DEFAULT_PROFILE.equals(profile);
    }

    // ---- per-site settings ----
    public boolean siteEnabled(Site s) { return s != null && site(s).enabled; }
    public void setSiteEnabled(Site s, boolean v) { site(s).enabled = v; }

    public boolean isDesktop(Site s) { return Prefs.VIEW_DESKTOP.equals(site(s).view); }
    public void setDesktop(Site s, boolean v) { site(s).view = v ? Prefs.VIEW_DESKTOP : Prefs.VIEW_MOBILE; }

    public int textZoom(Site s) { return site(s).textZoom; }
    public void setTextZoom(Site s, int v) { site(s).textZoom = v; }

    public boolean notificationsEnabled(Site s) { return s != null && site(s).notify; }
    public void setNotificationsEnabled(Site s, boolean v) { site(s).notify = v; }

    public String customUrl(Site s) { return site(s).customUrl; }
    public void setCustomUrl(Site s, String v) { site(s).customUrl = v == null ? "" : v.trim(); }

    public boolean wasOpened(Site s) { return site(s).opened; }

    public boolean computerIdentity(Site s) { return s != null && site(s).computerIdentity; }
    public void setComputerIdentity(Site s, boolean v) { site(s).computerIdentity = v; }
    public void markOpened(Site s) { site(s).opened = true; }

    public boolean isPersonal() { return Prefs.ACCOUNT_PERSONAL.equals(type); }

    public String homeUrl(Site s) {
        String custom = customUrl(s);
        if (custom.startsWith("https://")) return custom;
        return isPersonal() ? s.personalUrl : s.workUrl;
    }

    /** First enabled site, preferring the one used last. */
    public Site preferredSite() {
        if (lastSite != null && siteEnabled(lastSite)) return lastSite;
        for (Site s : Site.values()) if (siteEnabled(s)) return s;
        return Site.TEAMS;
    }

    // ---- JSON ----
    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id).put("profile", profile).put("name", name).put("type", type).put("color", color)
                .put("background", background).put("lastSite", lastSite == null ? Site.TEAMS.key : lastSite.key);
        JSONObject sites = new JSONObject();
        for (Site s : Site.values()) {
            SiteSettings ss = site(s);
            sites.put(s.key, new JSONObject().put("enabled", ss.enabled).put("view", ss.view)
                    .put("textZoom", ss.textZoom).put("notify", ss.notify).put("customUrl", ss.customUrl)
                    .put("opened", ss.opened).put("computerIdentity", ss.computerIdentity));
        }
        o.put("sites", sites);
        JSONArray web = new JSONArray();
        for (WebTab w : mWebTabs) {
            web.put(new JSONObject().put("id", w.id).put("url", w.url).put("title", w.title)
                    .put("desktop", w.desktop).put("textZoom", w.textZoom).put("computerIdentity", w.computerIdentity));
        }
        o.put("webTabs", web).put("nextWebId", mNextWebId);
        return o;
    }

    static Account fromJson(JSONObject o) {
        Account a = new Account(o.optString("id"), o.optString("profile"), o.optString("name", "Account"),
                o.optInt("color"));
        a.type = o.optString("type", Prefs.ACCOUNT_WORK);
        a.background = o.optBoolean("background", true);
        Site last = Site.fromKey(o.optString("lastSite"));
        a.lastSite = last == null ? Site.TEAMS : last;
        JSONObject sites = o.optJSONObject("sites");
        for (Site s : Site.values()) {
            JSONObject so = sites == null ? null : sites.optJSONObject(s.key);
            if (so == null) continue;
            SiteSettings ss = a.site(s);
            ss.enabled = so.optBoolean("enabled", true);
            ss.view = so.optString("view", Prefs.VIEW_DESKTOP);
            ss.textZoom = so.optInt("textZoom", 100);
            ss.notify = so.optBoolean("notify", true);
            ss.customUrl = so.optString("customUrl", "");
            ss.opened = so.optBoolean("opened", false);
            ss.computerIdentity = so.optBoolean("computerIdentity", false);
        }
        JSONArray web = o.optJSONArray("webTabs");
        for (int i = 0; web != null && i < web.length(); i++) {
            JSONObject wo = web.optJSONObject(i);
            if (wo == null || wo.optString("url").isEmpty()) continue;
            WebTab w = new WebTab(wo.optString("id", String.valueOf(i + 1)), wo.optString("url"),
                    wo.optString("title"), wo.optBoolean("desktop", false));
            w.textZoom = wo.optInt("textZoom", 100);
            w.computerIdentity = wo.optBoolean("computerIdentity", false);
            a.mWebTabs.add(w);
        }
        a.mNextWebId = Math.max(o.optInt("nextWebId", 1), a.mWebTabs.size() + 1);
        for (WebTab w : a.mWebTabs) {
            try {
                a.mNextWebId = Math.max(a.mNextWebId, Integer.parseInt(w.id) + 1);
            } catch (NumberFormatException ignored) {
            }
        }
        return a;
    }
}
