package com.graphify.testsupport;

import com.graphify.settings.AppSettings;
import java.util.LinkedHashMap;
import java.util.Map;

/** Changes settings for one test and puts the previous values back in {@link #restore()}. */
public final class SettingsOverride {

    private final AppSettings settings;
    private final Map<String, String> originals = new LinkedHashMap<>();

    public SettingsOverride(AppSettings settings) {
        this.settings = settings;
    }

    public SettingsOverride set(String key, String value) {
        originals.putIfAbsent(key, settings.all().stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow()
                .value());
        settings.update(key, value, "test");
        return this;
    }

    public void restore() {
        originals.forEach((key, value) -> settings.update(key, value, "test"));
        originals.clear();
    }
}
