package com.graphify.indexer;

import com.graphify.indexer.model.Declaration;
import com.graphify.indexer.model.IndexWarning;
import com.graphify.indexer.model.Usage;
import java.util.ArrayList;
import java.util.List;

/** Accumulates the output of one indexing run. */
final class IndexCollector {

    private final List<Declaration> declarations = new ArrayList<>();
    private final List<Usage> usages = new ArrayList<>();
    private final List<IndexWarning> warnings = new ArrayList<>();

    void declaration(Declaration declaration) {
        declarations.add(declaration);
    }

    void usage(Usage usage) {
        usages.add(usage);
    }

    void warning(IndexWarning warning) {
        warnings.add(warning);
    }

    List<Declaration> declarations() {
        return List.copyOf(declarations);
    }

    List<Usage> usages() {
        return List.copyOf(usages);
    }

    List<IndexWarning> warnings() {
        return List.copyOf(warnings);
    }
}
