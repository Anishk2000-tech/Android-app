// Minimal copy of the Chromium WebView support-library boundary interface.
// The fully-qualified name and method signatures must match the interface that
// ships inside the WebView APK (Chromium, BSD-licensed) because calls are
// dispatched across the boundary by class name + method signature.
package org.chromium.support_lib_boundary;

import android.webkit.WebView;

import java.lang.reflect.InvocationHandler;

public interface WebViewProviderFactoryBoundaryInterface {
    /* SupportLibraryWebViewChromium */ InvocationHandler createWebView(WebView webview);

    /* SupportLibWebkitToCompatConverter */ InvocationHandler getWebkitToCompatConverter();

    String[] getSupportedFeatures();

    /* ProfileStoreBoundaryInterface */ InvocationHandler getProfileStore();
}
