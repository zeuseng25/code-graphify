package com.graphify.scm;

import java.util.List;
import java.util.Optional;

/** A configured SCM connection with its secret already decrypted; never log it (toString omits the secret). */
public record ScmConnection(long id, String name, ScmType type, String baseUrl, String username, String secret,
        List<String> includeProjects, List<String> excludeRepos, List<String> repositoryUrls,
        boolean includeOwnRepositories) {

    public ScmConnection {
        includeProjects = includeProjects == null ? List.of() : List.copyOf(includeProjects);
        excludeRepos = excludeRepos == null ? List.of() : List.copyOf(excludeRepos);
        repositoryUrls = repositoryUrls == null ? List.of() : List.copyOf(repositoryUrls);
    }

    public ScmConnection(long id, String name, ScmType type, String baseUrl, String username, String secret,
            List<String> includeProjects, List<String> excludeRepos, List<String> repositoryUrls) {
        this(id, name, type, baseUrl, username, secret, includeProjects, excludeRepos, repositoryUrls, false);
    }

    public ScmConnection(long id, String name, ScmType type, String baseUrl, String username, String secret,
            List<String> includeProjects, List<String> excludeRepos) {
        this(id, name, type, baseUrl, username, secret, includeProjects, excludeRepos, List.of(), false);
    }

    /** Username GitHub documents for token authentication over git HTTPS (any non-empty name is accepted). */
    static final String GITHUB_TOKEN_USER = "x-access-token";

    /** The Authorization header git sends for this connection's clones; GitHub git takes Basic only. */
    public Optional<String> gitAuthorization() {
        if (type == ScmType.GITHUB && secret != null) {
            return AuthorizationHeader.of(username == null || username.isBlank() ? GITHUB_TOKEN_USER : username, secret);
        }
        return AuthorizationHeader.of(username, secret);
    }

    @Override
    public String toString() {
        return "ScmConnection[id=" + id + ", name=" + name + ", type=" + type + ", baseUrl=" + UrlMasking.mask(baseUrl)
                + "]";
    }
}
