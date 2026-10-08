package com.graphify.indexer;

/** Tuning for one run. Values come from the caller (APP_SETTING in later plans), never from constants here. */
public record IndexerOptions(int parseBatchSize, int snippetMaxLength) {

    public IndexerOptions {
        if (parseBatchSize < 1) {
            throw new IllegalArgumentException("parseBatchSize must be >= 1 but was " + parseBatchSize);
        }
        if (snippetMaxLength < 1) {
            throw new IllegalArgumentException("snippetMaxLength must be >= 1 but was " + snippetMaxLength);
        }
    }
}
