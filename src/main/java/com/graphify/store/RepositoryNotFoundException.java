package com.graphify.store;

public class RepositoryNotFoundException extends RuntimeException {

    public RepositoryNotFoundException(long repositoryId) {
        super("No scm_repository row with id " + repositoryId);
    }
}
