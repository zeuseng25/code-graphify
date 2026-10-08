package com.graphify.settings;

public class SettingNotFoundException extends RuntimeException {

    private final String key;

    public SettingNotFoundException(String key) {
        super("Unknown setting: " + key);
        this.key = key;
    }

    public String key() {
        return key;
    }
}
