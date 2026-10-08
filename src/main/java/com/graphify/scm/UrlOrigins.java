package com.graphify.scm;

import java.net.URI;
import java.util.Locale;

/** Origin (scheme, host, port) comparison shared by the Git URL checks and the GitHub paging guard. */
final class UrlOrigins {

    private UrlOrigins() {
    }

    /** True when both URIs have the same scheme, host and port (default ports mapped from the scheme). */
    static boolean sameOrigin(URI a, URI b) {
        return a.getScheme() != null && b.getScheme() != null && a.getHost() != null && b.getHost() != null
                && a.getScheme().equalsIgnoreCase(b.getScheme())
                && a.getHost().equalsIgnoreCase(b.getHost())
                && port(a) == port(b);
    }

    static int port(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equals(uri.getScheme().toLowerCase(Locale.ROOT)) ? 443 : 80;
    }
}
