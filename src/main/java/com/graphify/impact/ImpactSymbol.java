package com.graphify.impact;

import com.graphify.indexer.model.SymbolKind;
import com.graphify.indexer.model.SymbolOrigin;

public record ImpactSymbol(
        long id,
        String key,
        SymbolKind kind,
        String display,
        String classFqn,
        Long parentId,
        SymbolOrigin origin,
        boolean nameOnly) {
}
