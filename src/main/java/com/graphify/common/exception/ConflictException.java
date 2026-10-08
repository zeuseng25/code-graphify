package com.graphify.common.exception;

import java.util.Map;

/** The request conflicts with the current state (spec §8, HTTP 409); {@code properties} are added to the problem. */
public class ConflictException extends RuntimeException {

    private final Map<String, Object> properties;

    public ConflictException(String message) {
        this(message, Map.of());
    }

    public ConflictException(String message, Map<String, Object> properties) {
        super(message);
        this.properties = Map.copyOf(properties);
    }

    public Map<String, Object> properties() {
        return properties;
    }
}
