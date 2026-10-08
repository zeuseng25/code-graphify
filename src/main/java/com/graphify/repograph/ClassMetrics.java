package com.graphify.repograph;

/** Stored analyses of one class (spec §9.2); the community is null for a class the last analysis did not see. */
public record ClassMetrics(int inDegree, int outDegree, int dependents, boolean entryPoint, Integer communityId,
        String communityLabel) {
}
