package com.graphify.indexer.model;

/**
 * EXACT: binding resolved with no error at the site. RECOVERED: binding exists but is recovered or the site
 * has a compile error (incomplete classpath). NAME_ONLY: no binding; target derived from imports and names.
 */
public enum Confidence {
    EXACT, RECOVERED, NAME_ONLY
}
