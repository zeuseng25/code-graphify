package com.graphify.repograph;

import java.time.Instant;
import java.util.List;

/**
 * A repository graph summary (spec §10.5): live counts plus the stored analyses; {@code stale} when the index moved on
 * since the last analysis.
 */
public record RepoGraphReport(
        long repositoryId,
        String indexedCommit,
        String analyzedCommit,
        Instant analyzedAt,
        boolean stale,
        int moduleCount,
        int packageCount,
        int classCount,
        long dependencyCount,
        int communityCount,
        List<CriticalClass> criticalClasses,
        List<CommunitySummary> communities,
        List<PackageCycle> cycles,
        int entryPointCount,
        List<String> entryPointClasses) {
}
