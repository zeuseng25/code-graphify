package com.graphify.indexing;

/** Status of a whole index run; per-repository outcomes are {@link RepoIndexStatus}. */
public enum RunStatus {
    RUNNING, SUCCESS, FAILED, CANCELLED, INTERRUPTED
}
