package com.graphify.maven;

import com.graphify.store.DependencyRecord;
import java.nio.file.Path;
import java.util.List;

/**
 * One module of a checkout: its coordinates, existing source roots and declared dependencies, plus what other
 * repositories may have to provide (parent, imported BOMs) and the project root whose reactor builds it.
 * {@code projectRoot} is "." for the checkout root, a checkout-relative path for a nested project, or null when
 * the checkout has no pom.
 */
public record MavenModule(String path, String groupId, String artifactId, String version, List<Path> sourceRoots,
        List<DependencyRecord> dependencies, boolean hasPom, String packaging, Gav parent, List<Gav> imports,
        String projectRoot) {

    public MavenModule {
        sourceRoots = List.copyOf(sourceRoots);
        dependencies = List.copyOf(dependencies);
        imports = imports == null ? List.of() : List.copyOf(imports);
    }

    public MavenModule(String path, String groupId, String artifactId, String version, List<Path> sourceRoots,
            List<DependencyRecord> dependencies, boolean hasPom) {
        this(path, groupId, artifactId, version, sourceRoots, dependencies, hasPom, null, null, List.of(),
                hasPom ? "." : null);
    }

    /** This module's own coordinate, or null when it is not fully resolved. */
    public Gav gav() {
        return Gav.of(groupId, artifactId, version);
    }
}
