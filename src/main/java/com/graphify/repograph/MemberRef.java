package com.graphify.repograph;

import com.graphify.indexer.model.SymbolKind;

/** A member (or the class itself, for field initializers) at METHOD level. */
public record MemberRef(long symbolId, String key, String classFqn, SymbolKind kind, String signature) {
}
