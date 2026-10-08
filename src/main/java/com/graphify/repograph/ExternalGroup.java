package com.graphify.repograph;

/** Where types outside the repository come from: another repository or a library artifact. */
public record ExternalGroup(String key, String label, NodeType type) {
}
