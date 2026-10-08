package com.graphify.impact;

import com.graphify.indexer.model.Confidence;
import com.graphify.indexer.model.UsageKind;

/** One usage row as the BFS needs it: only columns of the covering index ix_usage_bfs. */
public record ImpactUsage(long id, long fromId, long toId, UsageKind kind, Confidence confidence, long moduleId) {
}
