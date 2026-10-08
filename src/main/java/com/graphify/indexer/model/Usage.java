package com.graphify.indexer.model;

/** One usage site: {@code fromKey} (enclosing method or class) uses {@code toKey}. Line and column are 1-based. */
public record Usage(
        String fromKey,
        String toKey,
        UsageKind kind,
        Confidence confidence,
        String modulePath,
        String filePath,
        int line,
        int column,
        String snippet) {
}
