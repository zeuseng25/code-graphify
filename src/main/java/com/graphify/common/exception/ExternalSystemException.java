package com.graphify.common.exception;

/** A system we depend on (LDAP, SCM) failed or could not be reached (spec §8: 502); the message holds no secrets. */
public class ExternalSystemException extends RuntimeException {

    public ExternalSystemException(String message) {
        super(message);
    }
}
