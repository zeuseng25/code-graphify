package com.graphify.search;

import com.graphify.indexer.model.SymbolKind;

public record SymbolRef(long id, String key, SymbolKind kind, String display) {
}
