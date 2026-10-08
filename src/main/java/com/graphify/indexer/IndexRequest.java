package com.graphify.indexer;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record IndexRequest(Path repoRoot, List<ModuleSource> modules, IndexerOptions options) {

    public IndexRequest {
        Objects.requireNonNull(repoRoot, "repoRoot");
        modules = List.copyOf(modules);
        Objects.requireNonNull(options, "options");
    }
}
