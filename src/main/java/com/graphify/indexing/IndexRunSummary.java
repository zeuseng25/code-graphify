package com.graphify.indexing;

import java.time.Instant;

public record IndexRunSummary(
        long id,
        String trigger,
        String scope,
        Long scopeId,
        String status,
        String startedBy,
        Instant startedAt,
        Instant finishedAt,
        boolean cancelRequested) {
}
