package com.graphify.search;

import com.graphify.indexer.model.Confidence;
import com.graphify.indexer.model.UsageKind;
import com.graphify.repository.RepositoryRef;

public record UsageView(
        long id,
        SymbolRef from,
        UsageKind kind,
        Confidence confidence,
        RepositoryRef repository,
        String modulePath,
        String filePath,
        int line,
        int column,
        String snippet) {
}
