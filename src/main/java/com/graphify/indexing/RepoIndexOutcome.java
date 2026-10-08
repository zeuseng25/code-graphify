package com.graphify.indexing;

/** {@code classpathMode} is FULL, PARTIAL or NONE over the modules with sources; null when nothing was resolved. */
public record RepoIndexOutcome(RepoIndexStatus status, String commit, String classpathMode, String error, int symbols,
        int usages, int warnings, long durationMillis) {
}
