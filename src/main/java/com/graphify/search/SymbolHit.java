package com.graphify.search;

import com.graphify.indexer.model.SymbolKind;
import com.graphify.indexer.model.SymbolOrigin;

/** A search candidate; {@code repositoryCount} answers "in how many projects is it used" (spec §1.2). */
public record SymbolHit(
        long id,
        String key,
        SymbolKind kind,
        String display,
        SymbolOrigin origin,
        boolean nameOnly,
        long usageCount,
        int repositoryCount) {
}
