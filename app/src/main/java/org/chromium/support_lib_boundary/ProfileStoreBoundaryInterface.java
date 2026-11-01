// Minimal copy of the Chromium WebView support-library boundary interface
// (multi-profile support). Names and signatures must match the WebView APK.
package org.chromium.support_lib_boundary;

import java.lang.reflect.InvocationHandler;
import java.util.List;

public interface ProfileStoreBoundaryInterface {
    /* ProfileBoundaryInterface */ InvocationHandler getOrCreateProfile(String name);

    /* ProfileBoundaryInterface */ InvocationHandler getProfile(String name);

    List<String> getAllProfileNames();

    boolean deleteProfile(String name);
}
