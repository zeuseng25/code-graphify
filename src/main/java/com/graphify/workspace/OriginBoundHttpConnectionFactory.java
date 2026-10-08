package com.graphify.workspace;

import java.io.IOException;
import java.net.Proxy;
import java.net.URL;
import java.util.Locale;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.JDKHttpConnection;
import org.eclipse.jgit.transport.http.JDKHttpConnectionFactory;

/**
 * JGit re-sends additional headers, Authorization included, on every request of a transport, redirects to another
 * host among them. This factory drops the Authorization header from any request whose scheme, host or port differs
 * from the origin the current thread declared with {@link #bindTo}. A thread that declared nothing sends no
 * Authorization at all (fail closed).
 */
final class OriginBoundHttpConnectionFactory extends JDKHttpConnectionFactory {

    private static final ThreadLocal<String> ORIGIN = new ThreadLocal<>();

    /** Declares the only origin that may receive Authorization on this thread; close the result when done. */
    static AutoCloseable bindTo(String cloneUrl) {
        ORIGIN.set(originOf(cloneUrl));
        return ORIGIN::remove;
    }

    @Override
    public HttpConnection create(URL url) throws IOException {
        return new Bound(url, null);
    }

    @Override
    public HttpConnection create(URL url, Proxy proxy) throws IOException {
        return new Bound(url, proxy);
    }

    private static String originOf(String url) {
        try {
            return originOf(new URL(url));
        } catch (IOException e) {
            return "";
        }
    }

    private static String originOf(URL url) {
        int port = url.getPort() == -1 ? url.getDefaultPort() : url.getPort();
        return url.getProtocol().toLowerCase(Locale.ROOT) + "://" + url.getHost().toLowerCase(Locale.ROOT) + ":" + port;
    }

    private static final class Bound extends JDKHttpConnection {

        private final String origin;

        Bound(URL url, Proxy proxy) throws IOException {
            super(url, proxy);
            this.origin = originOf(url);
        }

        @Override
        public void setRequestProperty(String name, String value) {
            String allowed = ORIGIN.get();
            if ("Authorization".equalsIgnoreCase(name) && !origin.equals(allowed)) {
                return;
            }
            super.setRequestProperty(name, value);
        }
    }
}
