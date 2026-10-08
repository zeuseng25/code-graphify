package com.graphify.impact;

import com.graphify.indexer.model.UsageKind;

/** One row of impact_relation_rule. */
public record ImpactRule(UsageKind kind, boolean propagates, boolean shownAtLevel1) {
}
