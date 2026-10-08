package com.graphify.indexing;

/** Outcome of indexing one repository in one run (spec §8). */
public enum RepoIndexStatus {
    SUCCESS, SUCCESS_PARTIAL, FAILED, CLONE_FAILED, SKIPPED_UNCHANGED, SKIPPED_NOT_JAVA, INTERRUPTED
}
