package com.graphify.repository;

public record ModuleView(long id, String path, String groupId, String artifactId, String version, String classpathMode) {
}
