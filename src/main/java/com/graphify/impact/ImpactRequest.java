package com.graphify.impact;

import com.graphify.indexer.model.Confidence;
import java.util.List;
import java.util.Set;

/** Null {@code changeType}, {@code depth} or {@code includeDispatch} mean BEHAVIOR, the configured default depth, true. */
public record ImpactRequest(
        List<Long> symbolIds,
        ChangeType changeType,
        Integer depth,
        Set<Confidence> confidences,
        Boolean includeDispatch) {
}
