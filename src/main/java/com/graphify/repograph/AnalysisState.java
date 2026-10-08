package com.graphify.repograph;

import java.time.Instant;

/** Which commit the stored analyses describe. */
public record AnalysisState(String commit, Instant analyzedAt, int classCount, int communityCount, int cycleCount) {
}
