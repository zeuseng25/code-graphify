package com.graphify.settings;

/** Published after a setting is updated, so caches and schedules (plan 4) can react. */
public record SettingChangedEvent(String key) {
}
