package com.graphify.repograph;

import com.graphify.indexer.model.UsageKind;

/** Usages written in a repository class of a type outside the repository, for one usage kind. */
public record ExternalEdge(String fromFqn, ExternalGroup group, String toFqn, UsageKind kind, long weight) {
}
