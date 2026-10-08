package com.graphify.repograph;

import java.util.List;

/** A repository graph at {@code level}; {@code truncated} when it was rolled up from {@code requestedLevel}. */
public record RepoGraph(long repositoryId, GraphLevel requestedLevel, GraphLevel level, String focus,
        boolean truncated, String suggestion, List<GraphNode> nodes, List<GraphEdge> edges) {
}
