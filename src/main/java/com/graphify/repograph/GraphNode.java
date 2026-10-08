package com.graphify.repograph;

/** A node of the graph view; {@code metrics} only on class nodes with a stored analysis. */
public record GraphNode(String id, String label, NodeType type, int size, ClassMetrics metrics) {
}
