package com.graphify.indexing;

/** Either a prepared repository or its final outcome (the head check or the checkout failed). */
public record Preparation(PreparedRepository prepared, RepoIndexOutcome finished) {
}
