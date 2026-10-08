package com.graphify.common.exception;

/** The requested resource does not exist; rendered as HTTP 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
