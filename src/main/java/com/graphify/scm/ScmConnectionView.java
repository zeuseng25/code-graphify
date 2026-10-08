package com.graphify.scm;

import java.time.Instant;
import java.util.List;

/** An SCM connection as the admin API shows it: never the secret, only whether one is set (spec §6.4). */
public record ScmConnectionView(
        long id,
        String name,
        ScmType type,
        String baseUrl,
        String username,
        boolean secretSet,
        List<String> includeProjects,
        List<String> excludeRepos,
        List<String> repositoryUrls,
        boolean includeOwnRepositories,
        boolean enabled,
        String lastTestStatus,
        Instant lastTestAt,
        String lastSyncStatus,
        Instant lastSyncAt,
        String lastSyncError,
        int repositoryCount) {
}
