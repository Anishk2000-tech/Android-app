package com.workdeck.app.web;

import android.content.Context;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import com.workdeck.app.Prefs;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Prepends sw-shim.js to Microsoft service-worker scripts (identified by the
 * standard "Service-Worker: script" request header). Any failure returns null,
 * which lets WebView fetch the original script normally.
 */
final class SwShim {
    private static final String TAG = "WorkDeck/SwShim";
    private static final int MAX_BYTES = 12 * 1024 * 1024;
    private static byte[] sShim;

    /**
     * Served instead of a site's own service worker when that worker keeps failing the
     * site's page: it takes over at once, deletes the site's caches and unregisters
     * itself, so the page comes from the network again. It has no fetch handler, so
     * while it is there requests simply go to the network.
     */
    private static final String REMOVER = "/* WorkDeck: this site's service worker kept failing its page;"
            + " this one removes it and its caches. */\n"
            + "self.addEventListener('install', function () { self.skipWaiting(); });\n"
            + "self.addEventListener('activate', function (e) {\n"
            + "  e.waitUntil(caches.keys().then(function (ks) {\n"
            + "    return Promise.all(ks.map(function (k) { return caches.delete(k); }));\n"
            + "  }).catch(function () {}).then(function () { return self.registration.unregister(); }));\n"
            + "});\n";

    /** "profile|host" whose service worker is replaced by the remover at its next update. */
    private static final Set<String> sRemoveOnce = Collections.synchronizedSet(new HashSet<String>());

    private SwShim() {}

    /** Remove the site's service worker (and its caches) at its next update check. */
    static void removeOnce(String profile, String host) {
        sRemoveOnce.add(profile + "|" + host);
    }

    /** Keep the site's service workers off in this profile (each one removes itself). */
    static void keepOff(Context c, String profile, String host) {
        Prefs.get(c).raw().edit().putBoolean("sw_kill_" + profile + "|" + host, true).apply();
    }

    private static WebResourceResponse remover(String host) {
        Log.w(TAG, "replaced " + host + "'s service worker with one that removes it and its caches");
        Map<String, String> headers = new HashMap<>();
        headers.put("Service-Worker-Allowed", "/"); // whatever scope the site's worker had
        headers.put("Cache-Control", "no-store");
        return new WebResourceResponse("application/javascript", StandardCharsets.UTF_8.name(), 200, "OK",
                headers, new ByteArrayInputStream(REMOVER.getBytes(StandardCharsets.UTF_8)));
    }

    private static String header(Map<String, String> headers, String name) {
        if (headers == null) return null;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    private static synchronized byte[] shim(Context c) throws IOException {
        if (sShim == null) {
            sShim = Assets.readBytes(c, "workdeck/sw-shim.js");
        }
        return sShim;
    }

    static WebResourceResponse maybeIntercept(Context c, String profile, WebResourceRequest req, CookieManager cookies) {
        try {
            if (req == null || !"GET".equalsIgnoreCase(req.getMethod())) return null;
            if (!"script".equalsIgnoreCase(header(req.getRequestHeaders(), "Service-Worker"))) return null;
            String url = req.getUrl().toString();
            String host = UrlRouter.host(url);
            String k = profile + "|" + host;
            if (sRemoveOnce.remove(k) || Prefs.get(c).raw().getBoolean("sw_kill_" + k, false)) return remover(host);
            if (!UrlRouter.isInjectable(url) || !Prefs.get(c).swShim()) return null;
            return fetchWithShim(c, url, req.getRequestHeaders(), cookies);
        } catch (Throwable t) {
            Log.w(TAG, "shim failed", t);
            return null;
        }
    }

    private static WebResourceResponse fetchWithShim(Context c, String url, Map<String, String> reqHeaders,
            CookieManager cookies) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            if (reqHeaders != null) {
                for (Map.Entry<String, String> e : reqHeaders.entrySet()) {
                    String k = e.getKey() == null ? "" : e.getKey().toLowerCase(Locale.ROOT);
                    if (k.isEmpty() || k.equals("accept-encoding") || k.equals("range") || k.equals("cookie")
                            || k.equals("host") || k.equals("content-length") || k.equals("connection")) {
                        continue;
                    }
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            String cookie = cookies.getCookie(url);
            if (cookie != null && !cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
            int code = conn.getResponseCode();
            if (code != 200) return null;
            String type = conn.getContentType();
            if (type == null || !type.toLowerCase(Locale.ROOT).contains("javascript")) return null;

            Map<String, String> outHeaders = new HashMap<>();
            for (Map.Entry<String, List<String>> e : conn.getHeaderFields().entrySet()) {
                String k = e.getKey();
                if (k == null || e.getValue() == null || e.getValue().isEmpty()) continue;
                String lk = k.toLowerCase(Locale.ROOT);
                if (lk.equals("set-cookie")) {
                    for (String v : e.getValue()) cookies.setCookie(url, v);
                    continue;
                }
                if (lk.equals("content-length") || lk.equals("content-encoding") || lk.equals("transfer-encoding")
                        || lk.equals("connection") || lk.equals("content-type")) {
                    continue;
                }
                outHeaders.put(k, e.getValue().get(0));
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream(256 * 1024);
            out.write(shim(c));
            out.write('\n');
            InputStream in = conn.getInputStream();
            byte[] buf = new byte[32 * 1024];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) return null;
                out.write(buf, 0, n);
            }
            in.close();
            Log.i(TAG, "shimmed service worker " + UrlRouter.host(url));
            return new WebResourceResponse("application/javascript", StandardCharsets.UTF_8.name(), 200, "OK",
                    outHeaders, new ByteArrayInputStream(out.toByteArray()));
        } finally {
            conn.disconnect();
        }
    }
}
