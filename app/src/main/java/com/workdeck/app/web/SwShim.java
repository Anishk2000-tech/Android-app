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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Prepends sw-shim.js to Microsoft service-worker scripts (identified by the
 * standard "Service-Worker: script" request header). Any failure returns null,
 * which lets WebView fetch the original script normally.
 */
final class SwShim {
    private static final String TAG = "WorkDeck/SwShim";
    private static final int MAX_BYTES = 12 * 1024 * 1024;
    private static byte[] sShim;

    private SwShim() {}

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

    static WebResourceResponse maybeIntercept(Context c, WebResourceRequest req, CookieManager cookies) {
        try {
            if (req == null || !"GET".equalsIgnoreCase(req.getMethod())) return null;
            if (!"script".equalsIgnoreCase(header(req.getRequestHeaders(), "Service-Worker"))) return null;
            String url = req.getUrl().toString();
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
