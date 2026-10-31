package com.workdeck.app.web;

import com.workdeck.app.Site;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Decides which URLs belong to Teams, Outlook, Microsoft sign-in, or the outside world.
 * Pure Java (no Android classes) so the rules can be unit-tested on a plain JVM.
 */
public final class UrlRouter {
    private UrlRouter() {}

    /** Domains operated by Microsoft: links to these stay inside the app. */
    static final String[] MICROSOFT_DOMAINS = {
            "microsoft.com", "microsoftonline.com", "microsoftonline.us", "microsoft.us",
            "live.com", "live.net", "office.com", "office365.com", "office365.us", "office.net",
            "cloud.microsoft", "microsoft365.com", "sharepoint.com", "sharepoint.us",
            "onedrive.com", "skype.com", "lync.com", "msauth.net", "msftauth.net", "msauthimages.net",
            "msocdn.com", "windows.net", "microsoftstream.com", "aka.ms",
            "sharepointonline.com", "svc.ms",
    };

    /**
     * Narrower list of Microsoft-run app/sign-in domains where the integration script
     * (and its secret bridge token) is injected. Deliberately excludes domains that
     * serve arbitrary customer content (e.g. *.blob.core.windows.net).
     */
    static final String[] INJECT_DOMAINS = {
            "microsoft.com", "microsoftonline.com", "microsoftonline.us", "microsoft.us",
            "live.com", "office.com", "office365.com", "office365.us", "office.net",
            "cloud.microsoft", "microsoft365.com", "sharepoint.com", "sharepoint.us",
            "skype.com", "msauth.net", "msftauth.net",
    };

    /** Origin rules (WebView format) where the integration script is injected. */
    public static String[] injectionOriginRules() {
        String[] rules = new String[INJECT_DOMAINS.length * 2 + 1];
        int i = 0;
        for (String d : INJECT_DOMAINS) {
            rules[i++] = "https://" + d;
            rules[i++] = "https://*." + d;
        }
        rules[i] = "https://login.windows.net";
        return rules;
    }

    /** Same policy as {@link #injectionOriginRules()}, for the fallback injection path. */
    public static boolean isInjectable(String url) {
        if (url == null || !url.startsWith("https://")) return false;
        String h = host(url);
        if (h.equals("login.windows.net")) return true;
        for (String d : INJECT_DOMAINS) {
            if (hostMatches(h, d)) return true;
        }
        return false;
    }

    /** Lowercase host of an absolute URL ("" if none). Lenient, like a browser's URL bar. */
    public static String host(String url) {
        if (url == null) return "";
        int scheme = url.indexOf("://");
        if (scheme <= 0) return "";
        int start = scheme + 3;
        int end = url.length();
        for (int i = start; i < url.length(); i++) {
            char ch = url.charAt(i);
            if (ch == '/' || ch == '?' || ch == '#' || ch == '\\') {
                end = i;
                break;
            }
        }
        String authority = url.substring(start, end);
        int at = authority.lastIndexOf('@');
        if (at >= 0) authority = authority.substring(at + 1);
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            return close > 0 ? authority.substring(0, close + 1).toLowerCase(Locale.ROOT) : "";
        }
        int colon = authority.indexOf(':');
        if (colon >= 0) authority = authority.substring(0, colon);
        while (authority.endsWith(".")) authority = authority.substring(0, authority.length() - 1);
        return authority.toLowerCase(Locale.ROOT);
    }

    /** RFC 3986 percent-encoding of everything except unreserved characters. */
    static String encode(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%').append(HEX[c >> 4]).append(HEX[c & 15]);
            }
        }
        return sb.toString();
    }

    /** Decodes %XX escapes (UTF-8); '+' is left alone, as in URI components. */
    static String decode(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length() && hex(s.charAt(i + 1)) >= 0 && hex(s.charAt(i + 2)) >= 0) {
                out.write(hex(s.charAt(i + 1)) * 16 + hex(s.charAt(i + 2)));
                i += 2;
            } else {
                byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                out.write(b, 0, b.length);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    static boolean hostMatches(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    public static boolean isMicrosoft(String url) {
        String h = host(url);
        if (h.isEmpty()) return false;
        for (String d : MICROSOFT_DOMAINS) {
            if (hostMatches(h, d)) return true;
        }
        return false;
    }

    public static boolean isTeams(String url) {
        String h = host(url);
        return h.equals("teams.microsoft.com") || h.endsWith(".teams.microsoft.com")
                || h.equals("teams.live.com") || h.equals("teams.cloud.microsoft")
                || h.endsWith(".teams.cloud.microsoft") || h.equals("teams.microsoft.us")
                || h.endsWith(".teams.microsoft.us");
    }

    public static boolean isOutlook(String url) {
        String h = host(url);
        return h.equals("outlook.office.com") || h.equals("outlook.office365.com")
                || h.equals("outlook.live.com") || h.equals("outlook.cloud.microsoft")
                || h.equals("outlook.office365.us") || h.equals("outlook.office.de");
    }

    /** Microsoft sign-in pages (where federated SSO redirects to other domains may start). */
    public static boolean isLogin(String url) {
        String h = host(url);
        return h.startsWith("login.") || h.equals("account.live.com") || h.equals("account.microsoft.com")
                || h.startsWith("device.login") || h.endsWith(".msauth.net") || h.startsWith("mysignins.");
    }

    /** Which hosted site a URL naturally belongs to, or null. */
    public static Site siteFor(String url) {
        if (isTeams(url)) return Site.TEAMS;
        if (isOutlook(url)) return Site.OUTLOOK;
        return null;
    }

    public static boolean isHttp(String url) {
        return url != null && (url.startsWith("https://") || url.startsWith("http://"));
    }

    /** msteams:/l/meetup-join/... -> https://teams.microsoft.com/l/meetup-join/... */
    public static String msteamsToHttps(String url) {
        String rest = url.substring("msteams:".length());
        while (rest.startsWith("/")) rest = rest.substring(1);
        return "https://teams.microsoft.com/" + rest;
    }

    /** Converts a mailto: link to an Outlook-on-the-web compose deeplink. */
    public static String mailtoToOutlook(String mailto, String outlookHome) {
        String base = isOutlook(outlookHome) ? "https://" + host(outlookHome) : "https://outlook.office.com";
        String spec = mailto.substring("mailto:".length());
        String to = spec;
        String query = "";
        int q = spec.indexOf('?');
        if (q >= 0) {
            to = spec.substring(0, q);
            query = spec.substring(q + 1);
        }
        StringBuilder sb = new StringBuilder(base).append("/mail/deeplink/compose?to=").append(encode(decode(to)));
        if (!query.isEmpty()) {
            for (String part : query.split("&")) {
                int eq = part.indexOf('=');
                if (eq <= 0) continue;
                String k = part.substring(0, eq).toLowerCase(Locale.ROOT);
                String v = part.substring(eq + 1);
                if (k.equals("subject") || k.equals("body") || k.equals("cc") || k.equals("bcc")) {
                    sb.append('&').append(k).append('=').append(encode(decode(v)));
                }
            }
        }
        return sb.toString();
    }
}
