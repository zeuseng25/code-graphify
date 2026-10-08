package com.graphify.impact;

/** SIGNATURE: callers stop compiling (level 1 + overrides, no dispatch). BEHAVIOR: transitive callers (spec §5.3). */
public enum ChangeType {
    SIGNATURE, BEHAVIOR
}
