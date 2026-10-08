package com.graphify.indexer.model;

import java.util.List;

public record IndexResult(
        List<Symbol> symbols,
        List<Declaration> declarations,
        List<Usage> usages,
        List<IndexWarning> warnings) {

    public IndexResult {
        symbols = List.copyOf(symbols);
        declarations = List.copyOf(declarations);
        usages = List.copyOf(usages);
        warnings = List.copyOf(warnings);
    }
}
