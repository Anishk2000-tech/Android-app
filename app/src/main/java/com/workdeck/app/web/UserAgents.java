package com.workdeck.app.web;

import android.content.Context;
import android.os.Build;
import android.webkit.WebSettings;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds browser identities from the real Chromium version of the installed
 * WebView, so the advertised browser always matches the actual engine.
 *
 * Desktop: Chrome on Windows 10/11 (what Teams/Outlook fully support, incl. calls).
 * Mobile:  Chrome for Android (the default WebView UA without the "wv" markers).
 */
public final class UserAgents {
    private static String sDefaultUa;
    private static String sMajor = "130";
    private static String sFull = "130.0.0.0";

    private UserAgents() {}

    public static synchronized void init(Context c) {
        if (sDefaultUa != null) return;
        try {
            sDefaultUa = WebSettings.getDefaultUserAgent(c);
        } catch (Throwable t) {
            sDefaultUa = "Mozilla/5.0 (Linux; Android " + Build.VERSION.RELEASE
                    + ") AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36";
        }
        Matcher m = Pattern.compile("Chrome/(\\d+)\\.([0-9.]+)").matcher(sDefaultUa);
        if (m.find()) {
            sMajor = m.group(1);
            sFull = m.group(1) + "." + m.group(2);
        }
    }

    public static String chromeMajor() { return sMajor; }

    public static String chromeFull() { return sFull; }

    public static String desktop() {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
                + sMajor + ".0.0.0 Safari/537.36";
    }

    public static String mobile() {
        String ua = sDefaultUa == null ? "" : sDefaultUa;
        ua = ua.replace("; wv)", ")").replaceAll(" Version/\\d+(\\.\\d+)*", "");
        return ua;
    }

    /** [brand, majorVersion, fullVersion] rows, matching what Chrome itself reports. */
    public static String[][] brands() {
        return new String[][] {
                {"Google Chrome", sMajor, sFull},
                {"Chromium", sMajor, sFull},
                {"Not?A_Brand", "99", "99.0.0.0"},
        };
    }

    /** Map in the format expected by the WebView USER_AGENT_METADATA feature. */
    public static Map<String, Object> metadata(boolean desktop) {
        Map<String, Object> m = new HashMap<>();
        m.put("BRAND_VERSION_LIST", brands());
        m.put("FULL_VERSION", sFull);
        if (desktop) {
            m.put("PLATFORM", "Windows");
            m.put("PLATFORM_VERSION", "15.0.0");
            m.put("ARCHITECTURE", "x86");
            m.put("MODEL", "");
            m.put("MOBILE", Boolean.FALSE);
            m.put("BITNESS", Integer.valueOf(64));
        } else {
            m.put("PLATFORM", "Android");
            m.put("PLATFORM_VERSION", Build.VERSION.RELEASE);
            m.put("ARCHITECTURE", "");
            m.put("MODEL", Build.MODEL);
            m.put("MOBILE", Boolean.TRUE);
            m.put("BITNESS", Integer.valueOf(0));
        }
        m.put("WOW64", Boolean.FALSE);
        return m;
    }
}
