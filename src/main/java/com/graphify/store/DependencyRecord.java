package com.graphify.store;

/** A dependency declared in a module's pom ({@code version} may be null when it could not be resolved). */
public record DependencyRecord(String groupId, String artifactId, String version, String scope) {
}
