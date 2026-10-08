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

/**
 * A minimal GitHub serving {@code GET /orgs/{org}/repos}, {@code GET /user/repos} and {@code GET /user} with
 * Link-header paging, auth checks and injected failures.
 */
public final class FakeGitHub implements AutoCloseable {

    private record Repo(String org, String name, String cloneUrl, boolean archived, boolean disabled, boolean own) {
    }

    private final List<Repo> repos = new ArrayList<>();
    private final List<String> requestedPages = new ArrayList<>();
    private final List<String> requestedPerPage = new ArrayList<>();
    private final List<String> requestedOrgs = new ArrayList<>();
    private final List<String> authorizations = new ArrayList<>();
    private final List<String> accepts = new ArrayList<>();
    private final List<String> apiVersions = new ArrayList<>();
    private final AtomicInteger failuresLeft = new AtomicInteger();
    private volatile int failureStatus;
    private volatile String requiredAuthorization;
    private volatile String nextLinkOverride;
    private volatile String failureHeaderName;
    private volatile String failureHeaderValue;
    private volatile String redirectUrl;
    private HttpServer server;

    private volatile String ownerLogin = "octo";
    private final List<String> requestedPaths = new ArrayList<>();
    private final List<String> requestedQueries = new ArrayList<>();

    /** The login GET /user answers and the owner of the repositories added with {@link #addOwnRepository}. */
    public FakeGitHub ownerLogin(String login) {
        ownerLogin = login;
        return this;
    }

    public synchronized FakeGitHub addOwnRepository(String name, String cloneUrl) {
        repos.add(new Repo(null, name, cloneUrl, false, false, true));
        return this;
    }

    public synchronized FakeGitHub addOwnArchived(String name, String cloneUrl) {
        repos.add(new Repo(null, name, cloneUrl, true, false, true));
        return this;
    }

    public synchronized List<String> requestedPaths() {
        return List.copyOf(requestedPaths);
    }

    public synchronized List<String> requestedQueries() {
        return List.copyOf(requestedQueries);
    }

    public FakeGitHub start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/orgs/", this::handle);
        server.createContext("/user", this::handle);
        server.start();
        return this;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public synchronized FakeGitHub addRepository(String org, String name, String cloneUrl) {
        repos.add(new Repo(org, name, cloneUrl, false, false, false));
        return this;
    }

    public synchronized FakeGitHub addArchived(String org, String name, String cloneUrl) {
        repos.add(new Repo(org, name, cloneUrl, true, false, false));
        return this;
    }

    public synchronized FakeGitHub addDisabled(String org, String name, String cloneUrl) {
        repos.add(new Repo(org, name, cloneUrl, false, true, false));
        return this;
    }

    /** Adds the header to every injected failure response. */
    public FakeGitHub failureHeader(String name, String value) {
        failureHeaderName = name;
        failureHeaderValue = value;
        return this;
    }

    /** Answers every request with a 301 to this URL. */
    public FakeGitHub redirectTo(String url) {
        redirectUrl = url;
        return this;
    }

    public FakeGitHub requireAuthorization(String headerValue) {
        requiredAuthorization = headerValue;
        return this;
    }

    public FakeGitHub failNext(int status, int times) {
        failureStatus = status;
        failuresLeft.set(times);
        return this;
    }

    /** Makes the next link of the first page point at this URL (for the off-host test). */
    public FakeGitHub nextLinkOverride(String url) {
        nextLinkOverride = url;
        return this;
    }

    public synchronized List<String> requestedPages() {
        return List.copyOf(requestedPages);
    }

    public synchronized List<String> requestedPerPage() {
        return List.copyOf(requestedPerPage);
    }

    public synchronized List<String> requestedOrgs() {
        return List.copyOf(requestedOrgs);
    }

    public synchronized List<String> authorizations() {
        return List.copyOf(authorizations);
    }

    public synchronized List<String> acceptHeaders() {
        return List.copyOf(accepts);
    }

    public synchronized List<String> apiVersions() {
        return List.copyOf(apiVersions);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String requestPath = exchange.getRequestURI().getPath();
        boolean user = requestPath.equals("/user");
        boolean ownRepos = requestPath.equals("/user/repos");
        String[] path = requestPath.split("/");
        // ["", "orgs", org, "repos"] or ["", "user", "repos"] or ["", "user"]
        String org = ownRepos || user ? ownerLogin : path.length > 2 ? path[2] : "";
        int perPage = intParam(exchange.getRequestURI(), "per_page", 30);
        int page = intParam(exchange.getRequestURI(), "page", 1);
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        synchronized (this) {
            requestedPaths.add(requestPath);
            requestedQueries.add(exchange.getRequestURI().getRawQuery());
            requestedOrgs.add(org);
            requestedPages.add(Integer.toString(page));
            requestedPerPage.add(Integer.toString(perPage));
            authorizations.add(authorization);
            accepts.add(exchange.getRequestHeaders().getFirst("Accept"));
            apiVersions.add(exchange.getRequestHeaders().getFirst("X-GitHub-Api-Version"));
        }
        if (failuresLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            if (failureHeaderName != null) {
                exchange.getResponseHeaders().add(failureHeaderName, failureHeaderValue);
            }
            respond(exchange, failureStatus, "{\"message\":\"injected\"}", null);
            return;
        }
        if (redirectUrl != null) {
            exchange.getResponseHeaders().add("Location", redirectUrl);
            respond(exchange, 301, "", null);
            return;
        }
        if (requiredAuthorization != null && !requiredAuthorization.equals(authorization)) {
            respond(exchange, 401, "{\"message\":\"Bad credentials\"}", null);
            return;
        }
        if (user) {
            respond(exchange, 200, "{\"login\":\"" + ownerLogin + "\",\"type\":\"User\"}", null);
            return;
        }
        List<Repo> listed;
        synchronized (this) {
            listed = repos.stream().filter(r -> ownRepos ? r.own() : !r.own() && r.org().equals(org)).toList();
        }
        if (!ownRepos && listed.isEmpty()) {
            respond(exchange, 404, "{\"message\":\"Not Found\"}", null);
            return;
        }
        int from = Math.min(listed.size(), (page - 1) * perPage);
        int to = Math.min(listed.size(), from + perPage);
        String ownerType = ownRepos ? "User" : "Organization";
        StringBuilder items = new StringBuilder();
        for (int i = from; i < to; i++) {
            Repo repo = listed.get(i);
            if (items.length() > 0) {
                items.append(',');
            }
            items.append("{\"id\":").append(i + 1).append(",\"name\":\"").append(repo.name())
                    .append("\",\"owner\":{\"login\":\"").append(org).append("\",\"type\":\"").append(ownerType)
                    .append("\"},\"clone_url\":\"").append(repo.cloneUrl()).append("\",\"archived\":")
                    .append(repo.archived()).append(",\"disabled\":").append(repo.disabled())
                    .append(",\"fork\":false,\"stargazers_count\":3}");
        }
        String link = null;
        if (to < listed.size()) {
            String pageUrl = ownRepos
                    ? baseUrl() + "/user/repos?affiliation=owner&visibility=all&per_page=" + perPage + "&page="
                    : baseUrl() + "/orgs/" + org + "/repos?per_page=" + perPage + "&page=";
            String next = page == 1 && nextLinkOverride != null ? nextLinkOverride : pageUrl + (page + 1);
            int lastPage = (listed.size() + perPage - 1) / perPage;
            link = "<" + next + ">; rel=\"next\", <" + pageUrl + lastPage + ">; rel=\"last\"";
        }
        respond(exchange, 200, "[" + items + "]", link);
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

    private static void respond(HttpExchange exchange, int status, String body, String link) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        if (link != null) {
            exchange.getResponseHeaders().add("Link", link);
        }
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
