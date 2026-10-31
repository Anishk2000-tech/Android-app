package com.workdeck.app.web;

import android.os.Build;
import android.util.Log;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.chromium.support_lib_boundary.ScriptHandlerBoundaryInterface;
import org.chromium.support_lib_boundary.WebSettingsBoundaryInterface;
import org.chromium.support_lib_boundary.WebViewProviderBoundaryInterface;
import org.chromium.support_lib_boundary.WebViewProviderFactoryBoundaryInterface;
import org.chromium.support_lib_boundary.WebkitToCompatConverterBoundaryInterface;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Talks to the optional "support library" features that ship inside the updatable
 * Android System WebView APK (the same mechanism androidx.webkit uses), without
 * depending on AndroidX. Every call is defensive: if the installed WebView is too
 * old, or anything goes wrong, the feature simply reports as unavailable and the
 * app falls back to plain framework APIs.
 */
public final class WebViewSupport {
    private static final String TAG = "WorkDeck/WVSupport";

    public static final String FEATURE_DOCUMENT_START_SCRIPT = "DOCUMENT_START_SCRIPT:1";
    public static final String FEATURE_USER_AGENT_METADATA = "USER_AGENT_METADATA";

    private static volatile boolean sInitialized;
    private static WebViewProviderFactoryBoundaryInterface sFactory;
    private static WebkitToCompatConverterBoundaryInterface sConverter;
    private static Set<String> sFeatures = Collections.emptySet();

    private WebViewSupport() {}

    private static synchronized void init() {
        if (sInitialized) return;
        sInitialized = true;
        try {
            ClassLoader loader;
            if (Build.VERSION.SDK_INT >= 28) {
                loader = WebView.getWebViewClassLoader();
            } else {
                Method getFactory = WebView.class.getDeclaredMethod("getFactory");
                getFactory.setAccessible(true);
                loader = getFactory.invoke(null).getClass().getClassLoader();
            }
            Class<?> fetcher = Class.forName(
                    "org.chromium.support_lib_glue.SupportLibReflectionUtil", false, loader);
            Method create = fetcher.getDeclaredMethod("createWebViewProviderFactory");
            InvocationHandler handler = (InvocationHandler) create.invoke(null);
            sFactory = cast(WebViewProviderFactoryBoundaryInterface.class, handler);
            String[] features = sFactory.getSupportedFeatures();
            sFeatures = new HashSet<>(Arrays.asList(features == null ? new String[0] : features));
            Log.i(TAG, "WebView support features: " + sFeatures.size() + ", documentStartScript="
                    + sFeatures.contains(FEATURE_DOCUMENT_START_SCRIPT) + ", uaMetadata="
                    + sFeatures.contains(FEATURE_USER_AGENT_METADATA));
        } catch (Throwable t) {
            Log.w(TAG, "WebView support library glue unavailable", t);
            sFactory = null;
            sFeatures = Collections.emptySet();
        }
    }

    private static <T> T cast(Class<T> clazz, InvocationHandler handler) {
        if (handler == null) return null;
        return clazz.cast(Proxy.newProxyInstance(
                WebViewSupport.class.getClassLoader(), new Class<?>[] {clazz}, handler));
    }

    public static boolean isSupported(String feature) {
        init();
        return sFeatures.contains(feature);
    }

    /** Handle returned by {@link #addDocumentStartScript}; call {@link #remove()} to unregister. */
    public static final class ScriptHandle {
        private final ScriptHandlerBoundaryInterface mHandler;

        ScriptHandle(ScriptHandlerBoundaryInterface handler) {
            mHandler = handler;
        }

        public void remove() {
            try {
                mHandler.remove();
            } catch (Throwable t) {
                Log.w(TAG, "remove script failed", t);
            }
        }
    }

    /**
     * Registers a script that runs at the very start of every matching document
     * (including iframes), before any page script. Returns null if unsupported.
     */
    public static ScriptHandle addDocumentStartScript(WebView webView, String script,
            String[] allowedOriginRules) {
        if (!isSupported(FEATURE_DOCUMENT_START_SCRIPT)) return null;
        try {
            WebViewProviderBoundaryInterface provider = cast(
                    WebViewProviderBoundaryInterface.class, sFactory.createWebView(webView));
            InvocationHandler h = provider.addDocumentStartJavaScript(script, allowedOriginRules);
            return new ScriptHandle(cast(ScriptHandlerBoundaryInterface.class, h));
        } catch (Throwable t) {
            Log.w(TAG, "addDocumentStartJavaScript failed", t);
            return null;
        }
    }

    /**
     * Overrides the User-Agent Client Hints (Sec-CH-UA headers and
     * navigator.userAgentData) so they agree with the User-Agent string.
     */
    public static boolean setUserAgentMetadata(WebSettings settings, Map<String, Object> metadata) {
        if (!isSupported(FEATURE_USER_AGENT_METADATA)) return false;
        try {
            synchronized (WebViewSupport.class) {
                if (sConverter == null) {
                    sConverter = cast(WebkitToCompatConverterBoundaryInterface.class,
                            sFactory.getWebkitToCompatConverter());
                }
            }
            WebSettingsBoundaryInterface s = cast(WebSettingsBoundaryInterface.class,
                    sConverter.convertSettings(settings));
            s.setUserAgentMetadataFromMap(metadata);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "setUserAgentMetadata failed", t);
            return false;
        }
    }
}
