package com.graphify.scm;

/** The SCM could not be read. Messages never contain credentials. */
public class ScmException extends RuntimeException {

    public ScmException(String message) {
        super(message);
    }

    public ScmException(String message, Throwable cause) {
        super(message, cause);
    }
}
