package com.graphify.store;

import java.util.List;

/**
 * One Maven module of a repository. {@code path} matches the indexer's {@code ModuleSource.modulePath} and must
 * not be blank (Oracle stores '' as NULL); the root module of a single-module repository is {@code "."}.
 * {@code packaging} is null when unknown (a repository without a pom).
 */
public record ModuleRecord(String path, String groupId, String artifactId, String version, ClasspathMode classpathMode,
        List<DependencyRecord> dependencies, String packaging) {

    public ModuleRecord {
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
    }

    public ModuleRecord(String path, String groupId, String artifactId, String version, ClasspathMode classpathMode,
            List<DependencyRecord> dependencies) {
        this(path, groupId, artifactId, version, classpathMode, dependencies, null);
    }

    public ModuleRecord(String path, String groupId, String artifactId, String version, ClasspathMode classpathMode) {
        this(path, groupId, artifactId, version, classpathMode, List.of(), null);
    }
}
