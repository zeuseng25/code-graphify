package com.graphify.indexing;

import com.graphify.repository.RepositoryRef;
import java.time.Instant;

/** One repository's outcome in one run (index_run_repo); {@code error} is already masked and cut. */
public record IndexRunRepoView(
        long runId,
        RepositoryRef repository,
        String commit,
        String status,
        String classpathMode,
        String error,
        int symbolCount,
        int usageCount,
        int warningCount,
        long durationMs,
        Instant finishedAt,
        String artifactInstall,
        String artifactInstallError) {
}
