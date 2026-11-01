// Minimal copy of the Chromium WebView support-library boundary interface
// (one isolated browsing profile: its own cookies, storage, cache, workers).
package org.chromium.support_lib_boundary;

import android.webkit.CookieManager;
import android.webkit.ServiceWorkerController;
import android.webkit.WebStorage;

public interface ProfileBoundaryInterface {
    String getName();

    CookieManager getCookieManager();

    WebStorage getWebStorage();

    ServiceWorkerController getServiceWorkerController();
}
