package com.graphify.settings;

import java.time.Instant;

/** One row of {@code app_setting}. {@code minValue}/{@code maxValue} bound INT values; {@code null} = unbounded. */
public record Setting(
        String key,
        String value,
        SettingType type,
        String description,
        Long minValue,
        Long maxValue,
        String updatedBy,
        Instant updatedAt) {
}
