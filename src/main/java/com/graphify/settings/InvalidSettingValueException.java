package com.graphify.settings;

public class InvalidSettingValueException extends RuntimeException {

    private final String key;
    private final String reason;

    public InvalidSettingValueException(String key, String reason) {
        super("Invalid value for " + key + ": " + reason);
        this.key = key;
        this.reason = reason;
    }

    public String key() {
        return key;
    }

    public String reason() {
        return reason;
    }
}
