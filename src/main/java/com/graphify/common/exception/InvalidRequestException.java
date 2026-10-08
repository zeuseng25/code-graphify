package com.graphify.common.exception;

/** Marks an error in the caller's input; rendered as HTTP 400. */
public class InvalidRequestException extends IllegalArgumentException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
