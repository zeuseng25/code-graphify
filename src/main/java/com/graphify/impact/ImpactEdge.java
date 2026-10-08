package com.graphify.impact;

import com.graphify.indexer.model.Confidence;
import com.graphify.indexer.model.UsageKind;
import com.graphify.repository.RepositoryRef;

public record ImpactEdge(
        long fromSymbolId,
        long toSymbolId,
        UsageKind kind,
        Confidence confidence,
        int level,
        boolean viaDispatch,
        long moduleId,
        RepositoryRef repository,
        String modulePath,
        String filePath,
        int line,
        int column,
        String snippet) {
}
