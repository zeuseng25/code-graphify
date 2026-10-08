package com.graphify.impact;

import com.graphify.indexer.model.Confidence;
import com.graphify.indexer.model.SymbolKind;

/**
 * One reached symbol. {@code confidence} is how sure the path that reached it is (the weakest edge on the way);
 * {@code nameOnly} marks a name-only twin ({@code Class#name/argCount}).
 */
public record ImpactNode(
        long symbolId,
        String key,
        SymbolKind kind,
        String display,
        int level,
        NodeRole role,
        Confidence confidence,
        boolean nameOnly) {
}
