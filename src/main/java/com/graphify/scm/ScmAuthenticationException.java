package com.graphify.scm;

/** The SCM rejected the connection's credentials (HTTP 401/403); retrying will not help (spec §8 AUTH_FAILED). */
public class ScmAuthenticationException extends ScmException {

    public ScmAuthenticationException(String message) {
        super(message);
    }
}
