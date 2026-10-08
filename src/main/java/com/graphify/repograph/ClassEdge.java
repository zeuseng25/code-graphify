package com.graphify.repograph;

import com.graphify.indexer.model.UsageKind;

/** Usages written in one repository class of another repository class, for one usage kind. */
public record ClassEdge(String fromFqn, String toFqn, UsageKind kind, long weight) {
}
