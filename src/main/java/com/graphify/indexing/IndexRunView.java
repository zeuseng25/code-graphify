package com.graphify.indexing;

import com.graphify.repository.RepositoryRef;
import java.util.List;
import java.util.Map;

/** A run with its repository outcomes (at most a few hundred: one per repository) and the repositories still in progress. */
public record IndexRunView(
        IndexRunSummary run,
        String error,
        Map<String, Integer> repositoriesByStatus,
        List<IndexRunRepoView> repositories,
        List<RepositoryRef> inProgress) {
}
