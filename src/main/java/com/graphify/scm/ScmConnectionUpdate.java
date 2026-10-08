package com.graphify.scm;

import java.util.List;

/**
 * A full create/PUT document; {@code secret} null keeps the stored one for the same baseUrl and username, "" clears it.
 * {@code includeOwnRepositories} null means false.
 */
public record ScmConnectionUpdate(
        String name,
        ScmType type,
        String baseUrl,
        String username,
        String secret,
        List<String> includeProjects,
        List<String> excludeRepos,
        List<String> repositoryUrls,
        Boolean includeOwnRepositories,
        Boolean enabled) {

    @Override
    public String toString() {
        return "ScmConnectionUpdate[name=" + name + ", type=" + type + ", baseUrl=" + UrlMasking.mask(baseUrl) + "]";
    }
}
