package com.graphify.store;

import com.graphify.indexer.model.IndexResult;
import java.util.List;

/** Everything one indexing pass produced for one repository at one commit. */
public record RepositoryIndex(long repositoryId, String commit, List<ModuleRecord> modules, IndexResult result) {

    public RepositoryIndex {
        modules = List.copyOf(modules);
    }
}
