package com.graphify.search;

import com.graphify.indexer.model.SymbolOrigin;
import java.util.List;

/** Everything shown on a symbol's page: where it is declared, what it contains and how it relates to other types. */
public record SymbolDetail(
        SymbolRef symbol,
        SymbolOrigin origin,
        boolean nameOnly,
        SymbolRef parent,
        List<DeclarationView> declarations,
        List<SymbolRef> members,
        List<SymbolRef> overrides,
        List<SymbolRef> overriddenBy,
        List<SymbolRef> supertypes,
        List<SymbolRef> subtypes) {
}
