package com.graphify.repograph;

import com.graphify.indexer.model.UsageKind;

/** Usages from one member to another, for one usage kind. */
public record MemberUse(MemberRef from, MemberRef to, UsageKind kind, long weight) {
}
