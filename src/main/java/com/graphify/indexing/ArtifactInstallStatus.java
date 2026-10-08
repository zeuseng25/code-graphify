package com.graphify.indexing;

/** What happened to a provider repository's artifacts in one run (index_run_repo.artifact_install, V13). */
public enum ArtifactInstallStatus {
    INSTALLED, UP_TO_DATE, FAILED, CYCLE_FAILED
}
