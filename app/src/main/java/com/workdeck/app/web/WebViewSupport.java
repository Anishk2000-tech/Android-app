package com.workdeck.app.web;

import android.os.Build;
import android.util.Log;
import android.webkit.WebSettings;
import android.webkit.WebView;

import android.webkit.CookieManager;
import android.webkit.ServiceWorkerController;
import android.webkit.WebStorage;

import org.chromium.support_lib_boundary.ProfileBoundaryInterface;
import org.chromium.support_lib_boundary.ProfileStoreBoundaryInterface;
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
import java.util.HashMap;
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
    public static final String FEATURE_MULTI_PROFILE = "MULTI_PROFILE";
    /** Name of WebView's built-in profile (what a plain WebView always uses). */
    public static final String DEFAULT_PROFILE = "Default";

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
                    + sFeatures.contains(FEATURE_USER_AGENT_METADATA) + ", multiProfile="
                    + sFeatures.contains(FEATURE_MULTI_PROFILE));
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

    // ------------------------------------------------------------------ profiles (multiple accounts)

    private static ProfileStoreBoundaryInterface sProfileStore;
    private static final Map<String, ProfileBoundaryInterface> sProfiles = new HashMap<>();

    /** True if this WebView can keep several sign-ins apart (separate cookies/storage). */
    public static boolean supportsProfiles() {
        return isSupported(FEATURE_MULTI_PROFILE);
    }

    private static synchronized ProfileBoundaryInterface profile(String name) {
        if (!supportsProfiles()) return null;
        ProfileBoundaryInterface p = sProfiles.get(name);
        if (p != null) return p;
        try {
            if (sProfileStore == null) {
                sProfileStore = cast(ProfileStoreBoundaryInterface.class, sFactory.getProfileStore());
            }
            p = cast(ProfileBoundaryInterface.class, sProfileStore.getOrCreateProfile(name));
            sProfiles.put(name, p);
            return p;
        } catch (Throwable t) {
            Log.w(TAG, "profile " + name + " unavailable", t);
            return null;
        }
    }

    /**
     * Puts a freshly created WebView into the named profile. Must be called before
     * anything is loaded in it. Returns false if profiles are not supported.
     */
    public static boolean setProfile(WebView webView, String name) {
        if (DEFAULT_PROFILE.equals(name)) return true;
        if (profile(name) == null) return false;
        try {
            WebViewProviderBoundaryInterface provider = cast(
                    WebViewProviderBoundaryInterface.class, sFactory.createWebView(webView));
            provider.setProfile(name);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "setProfile(" + name + ") failed", t);
            return false;
        }
    }

    public static CookieManager cookieManager(String profile) {
        if (DEFAULT_PROFILE.equals(profile)) return CookieManager.getInstance();
        ProfileBoundaryInterface p = profile(profile);
        try {
            if (p != null) return p.getCookieManager();
        } catch (Throwable t) {
            Log.w(TAG, "cookie manager for " + profile + " unavailable", t);
        }
        return CookieManager.getInstance();
    }

    public static WebStorage webStorage(String profile) {
        if (DEFAULT_PROFILE.equals(profile)) return WebStorage.getInstance();
        ProfileBoundaryInterface p = profile(profile);
        try {
            if (p != null) return p.getWebStorage();
        } catch (Throwable t) {
            Log.w(TAG, "storage for " + profile + " unavailable", t);
        }
        return null;
    }

    public static ServiceWorkerController serviceWorkers(String profile) {
        if (DEFAULT_PROFILE.equals(profile)) return ServiceWorkerController.getInstance();
        ProfileBoundaryInterface p = profile(profile);
        try {
            if (p != null) return p.getServiceWorkerController();
        } catch (Throwable t) {
            Log.w(TAG, "service workers for " + profile + " unavailable", t);
        }
        return null;
    }

    /** Deletes a profile's data entirely. Only works when no WebView uses it any more. */
    public static synchronized boolean deleteProfile(String name) {
        if (DEFAULT_PROFILE.equals(name) || !supportsProfiles()) return false;
        try {
            if (sProfileStore == null) {
                sProfileStore = cast(ProfileStoreBoundaryInterface.class, sFactory.getProfileStore());
            }
            sProfiles.remove(name);
            return sProfileStore.deleteProfile(name);
        } catch (Throwable t) {
            Log.w(TAG, "deleteProfile(" + name + ") failed", t);
            return false;
        }
    }
}
