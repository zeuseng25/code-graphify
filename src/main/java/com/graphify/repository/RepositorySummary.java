package com.graphify.repository;

import java.time.Instant;

public record RepositorySummary(
        long id,
        String projectKey,
        String slug,
        String defaultBranch,
        String lastIndexedCommit,
        Instant lastIndexedAt,
        String lastStatus,
        boolean active,
        int moduleCount) {
}
