package com.graphify.repograph;

/** A type declared in the repository: a node of the class graph. */
public record ClassNode(long symbolId, String fqn, String packageName, String modulePath) {
}
