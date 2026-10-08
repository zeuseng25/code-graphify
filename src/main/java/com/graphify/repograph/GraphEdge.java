package com.graphify.repograph;

import com.graphify.indexer.model.UsageKind;
import java.util.Map;

/** Usages from one node to another: their total and their count per usage kind. */
public record GraphEdge(String from, String to, long weight, Map<UsageKind, Long> kinds) {
}
