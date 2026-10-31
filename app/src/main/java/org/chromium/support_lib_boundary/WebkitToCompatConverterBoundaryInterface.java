// Minimal copy of the Chromium WebView support-library boundary interface.
package org.chromium.support_lib_boundary;

import android.webkit.WebSettings;

import java.lang.reflect.InvocationHandler;

public interface WebkitToCompatConverterBoundaryInterface {
    /* SupportLibraryWebSettings */ InvocationHandler convertSettings(WebSettings webSettings);
}
