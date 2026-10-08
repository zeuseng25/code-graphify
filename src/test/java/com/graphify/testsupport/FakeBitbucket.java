package com.graphify.testsupport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** A minimal Bitbucket Data Center serving {@code GET /rest/api/1.0/repos} with paging, auth checks and injected failures. */
public final class FakeBitbucket implements AutoCloseable {

    private record Repo(String projectKey, String slug, String cloneUrl, boolean archived) {
    }

    private final List<Repo> repos = new ArrayList<>();
    private final List<String> requestedStarts = new ArrayList<>();
    private final List<String> requestedLimits = new ArrayList<>();
    private final List<String> authorizations = new ArrayList<>();
    private final AtomicInteger failuresLeft = new AtomicInteger();
    private volatile int failureStatus;
    private volatile String requiredAuthorization;
    private volatile int rawStatus;
    private volatile String rawBody;
    private HttpServer server;

    public FakeBitbucket start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rest/api/1.0/repos", this::handle);
        server.start();
        return this;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public FakeBitbucket addRepository(String projectKey, String slug, String cloneUrl) {
        repos.add(new Repo(projectKey, slug, cloneUrl, false));
        return this;
    }

    public FakeBitbucket addArchived(String projectKey, String slug, String cloneUrl) {
        repos.add(new Repo(projectKey, slug, cloneUrl, true));
        return this;
    }

    public FakeBitbucket requireAuthorization(String headerValue) {
        requiredAuthorization = headerValue;
        return this;
    }

    public FakeBitbucket failNext(int status, int times) {
        failureStatus = status;
        failuresLeft.set(times);
        return this;
    }

    /** Answers every following request with exactly this status and body (an empty body is sent as no body). */
    public FakeBitbucket respondRaw(int status, String body) {
        rawStatus = status;
        rawBody = body;
        return this;
    }

    public synchronized List<String> requestedStarts() {
        return List.copyOf(requestedStarts);
    }

    public synchronized List<String> requestedLimits() {
        return List.copyOf(requestedLimits);
    }

    public synchronized List<String> authorizations() {
        return List.copyOf(authorizations);
    }

    public synchronized void removeRepository(String projectKey, String slug) {
        repos.removeIf(r -> r.projectKey().equals(projectKey) && r.slug().equals(slug));
    }

    private void handle(HttpExchange exchange) throws IOException {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        int start = intParam(exchange.getRequestURI(), "start", 0);
        int limit = intParam(exchange.getRequestURI(), "limit", 25);
        synchronized (this) {
            requestedStarts.add(Integer.toString(start));
            requestedLimits.add(Integer.toString(limit));
            authorizations.add(authorization);
        }
        if (failuresLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            respond(exchange, failureStatus, "{\"errors\":[{\"message\":\"injected\"}]}");
            return;
        }
        if (rawBody != null) {
            respond(exchange, rawStatus, rawBody);
            return;
        }
        if (requiredAuthorization != null && !requiredAuthorization.equals(authorization)) {
            respond(exchange, 401, "{\"errors\":[{\"message\":\"Authentication failed\"}]}");
            return;
        }
        List<Repo> snapshot;
        synchronized (this) {
            snapshot = List.copyOf(repos);
        }
        int end = Math.min(snapshot.size(), start + limit);
        StringBuilder values = new StringBuilder();
        for (int i = start; i < end; i++) {
            Repo repo = snapshot.get(i);
            if (values.length() > 0) {
                values.append(',');
            }
            values.append("{\"slug\":\"").append(repo.slug()).append("\",\"id\":").append(i + 1)
                    .append(",\"name\":\"").append(repo.slug()).append("\",\"scmId\":\"git\",\"archived\":")
                    .append(repo.archived()).append(",\"project\":{\"key\":\"").append(repo.projectKey())
                    .append("\",\"id\":1},\"links\":{\"clone\":[{\"href\":\"ssh://git@scm:7999/x.git\",\"name\":\"ssh\"},")
                    .append("{\"href\":\"").append(repo.cloneUrl()).append("\",\"name\":\"http\"}]}}");
        }
        boolean last = end >= snapshot.size();
        String body = "{\"size\":" + (end - start) + ",\"limit\":" + limit + ",\"start\":" + start + ",\"isLastPage\":"
                + last + (last ? "" : ",\"nextPageStart\":" + end) + ",\"values\":[" + values + "]}";
        respond(exchange, 200, body);
    }

    private static int intParam(URI uri, String name, int fallback) {
        String query = uri.getRawQuery();
        if (query == null) {
            return fallback;
        }
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts[0].equals(name) && parts.length == 2) {
                return Integer.parseInt(parts[1]);
            }
        }
        return fallback;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
        }
    }
}
