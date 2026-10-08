package com.graphify.workspace;

/** The remote rejected or demanded credentials. */
public class GitAuthenticationException extends GitException {

    public GitAuthenticationException(String message) {
        super(message);
    }
}
