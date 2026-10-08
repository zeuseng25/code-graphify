package com.graphify.impact;

/**
 * SEED: what is changing. AFFECTED: reached by the BFS. DISPATCH: an overridden method whose callers were followed.
 * TWIN: the name-only key (Class#name/argCount) standing for calls the indexer could not bind to a SEED, AFFECTED or
 * DISPATCH node; it has that node's level and confidence.
 */
public enum NodeRole {
    SEED, AFFECTED, DISPATCH, TWIN
}
