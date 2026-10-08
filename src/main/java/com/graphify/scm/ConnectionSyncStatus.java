package com.graphify.scm;

/** Outcome of the last repository listing of a connection (spec §8 SCM rows), shown with the connection. */
public enum ConnectionSyncStatus {
    SUCCESS, AUTH_FAILED, FAILED, DEACTIVATION_SKIPPED
}
