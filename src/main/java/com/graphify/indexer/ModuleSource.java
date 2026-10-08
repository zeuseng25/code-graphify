package com.graphify.indexer;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * One Maven module: the source roots whose files are indexed and the jars its bindings resolve against.
 * An empty classpath is valid (NONE mode): JDK and repo sources still resolve, everything else is NAME_ONLY.
 */
public record ModuleSource(String modulePath, List<Path> sourceRoots, List<Path> classpath) {

    public ModuleSource {
        Objects.requireNonNull(modulePath, "modulePath");
        sourceRoots = List.copyOf(sourceRoots);
        classpath = List.copyOf(classpath);
    }
}
