package com.graphify.scm;

import com.graphify.common.exception.InvalidRequestException;
import com.graphify.common.util.Utf8;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Checks GIT clone URLs against the connection's baseUrl and names them (spec 2026-10-07 §2). */
final class GitRepositoryUrls {

    /** Widths of scm_repository.project_key and slug (200) and clone_url (1000) in V1__core_schema.sql. */
    private static final int NAME_BYTES = 200;
    private static final int URL_BYTES = 1000;

    /** Suffix Git hosts append to clone URLs. */
    private static final String GIT_SUFFIX = ".git";

    /** A clone URL with the repository name it is stored under. */
    record Named(String url, String projectKey, String slug) {
    }

    private GitRepositoryUrls() {
    }

    /** @throws InvalidRequestException naming the offending URL (masked) and the rule it breaks */
    static List<Named> check(String baseUrl, List<String> urls) {
        List<Named> named = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String url : urls) {
            Named one = name(baseUrl, url);
            if (!seen.add(one.projectKey() + "/" + one.slug())) {
                throw new InvalidRequestException("repositoryUrls names " + one.projectKey() + "/" + one.slug()
                        + " twice");
            }
            named.add(one);
        }
        return List.copyOf(named);
    }

    /**
     * Validates one URL against the base and names it; also used by GitConnectionClient. The scheme rule (http or
     * https) belongs to the saved baseUrl, so this works on any hierarchical URI that shares the base's origin.
     */
    static Named name(String baseUrl, String url) {
        String shown = UrlMasking.mask(url);
        if (Utf8.byteLength(url) > URL_BYTES) {
            throw reject(shown, "is longer than " + URL_BYTES + " bytes");
        }
        URI uri;
        URI base;
        try {
            uri = new URI(url);
            base = new URI(baseUrl);
        } catch (URISyntaxException e) {
            throw reject(shown, "is not a valid URI");
        }
        if (uri.getRawUserInfo() != null) {
            throw reject(shown, "must not contain credentials");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw reject(shown, "must not contain a query or fragment");
        }
        if (!UrlOrigins.sameOrigin(uri, base) && !sameLocalFileBase(uri, base)) {
            throw reject(shown, "is not on the connection's base URL (scheme, host and port must match)");
        }
        String prefix = stripTrailingSlashes(base.getRawPath() == null ? "" : base.getRawPath());
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        checkEncoding(UrlMasking.mask(baseUrl), prefix, "the base URL");
        checkEncoding(shown, path, "it");
        if (!path.startsWith(prefix + "/")) {
            throw reject(shown, "is not under the connection's base URL path");
        }
        String rest = stripTrailingSlashes(path.substring(prefix.length() + 1));
        if (rest.isEmpty()) {
            throw reject(shown, "must name a repository after the base URL");
        }
        String[] segments = rest.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || isRelative(segment)) {
                throw reject(shown, "has an empty or relative path segment");
            }
        }
        String last = segments[segments.length - 1];
        String slug = last.endsWith(GIT_SUFFIX) ? last.substring(0, last.length() - GIT_SUFFIX.length()) : last;
        if (slug.isEmpty()) {
            throw reject(shown, "has no repository name");
        }
        String project = segments.length == 1 ? slug
                : String.join("/", Arrays.copyOf(segments, segments.length - 1));
        if (Utf8.byteLength(project) > NAME_BYTES || Utf8.byteLength(slug) > NAME_BYTES) {
            throw reject(shown, "names a repository longer than " + NAME_BYTES + " bytes");
        }
        return new Named(url, project, slug);
    }

    /**
     * Local {@code file:} repositories have no host. The admin API only accepts http(s) base URLs (checked when a
     * connection is saved, not here), so this exists for tests and operators who build connections directly.
     */
    private static boolean sameLocalFileBase(URI uri, URI base) {
        return "file".equalsIgnoreCase(uri.getScheme()) && "file".equalsIgnoreCase(base.getScheme())
                && uri.getHost() == null && base.getHost() == null;
    }

    /** Encoded slashes and backslashes hide path structure from the prefix check; encoded dots hide "..". */
    private static void checkEncoding(String shown, String rawPath, String what) {
        String lower = rawPath.toLowerCase(Locale.ROOT);
        if (lower.contains("%2f") || lower.contains("%5c")) {
            throw reject(shown, what + " must not contain an encoded slash or backslash");
        }
        for (String segment : rawPath.split("/", -1)) {
            if (!segment.isEmpty() && isRelative(segment)) {
                throw reject(shown, what + " has an empty or relative path segment");
            }
        }
    }

    private static boolean isRelative(String rawSegment) {
        String decoded = rawSegment.replaceAll("(?i)%2e", ".");
        return decoded.equals(".") || decoded.equals("..");
    }

    private static String stripTrailingSlashes(String path) {
        String result = path;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static InvalidRequestException reject(String shown, String rule) {
        return new InvalidRequestException("repositoryUrls: " + shown + " " + rule);
    }
}
