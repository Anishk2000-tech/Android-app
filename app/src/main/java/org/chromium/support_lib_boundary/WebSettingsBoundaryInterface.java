// Minimal copy of the Chromium WebView support-library boundary interface.
package org.chromium.support_lib_boundary;

import java.util.Map;

public interface WebSettingsBoundaryInterface {
    void setUserAgentMetadataFromMap(Map<String, Object> uaMetadata);

    Map<String, Object> getUserAgentMetadataMap();
}
