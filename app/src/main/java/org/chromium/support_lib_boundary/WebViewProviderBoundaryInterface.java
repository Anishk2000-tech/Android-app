// Minimal copy of the Chromium WebView support-library boundary interface (see
// WebViewProviderFactoryBoundaryInterface for why names must match exactly).
package org.chromium.support_lib_boundary;

import java.lang.reflect.InvocationHandler;

public interface WebViewProviderBoundaryInterface {
    /* ScriptHandler */ InvocationHandler addDocumentStartJavaScript(
            String script, String[] allowedOriginRules);

    /** Must be called before the WebView loads anything. */
    void setProfile(String profileName);
}
