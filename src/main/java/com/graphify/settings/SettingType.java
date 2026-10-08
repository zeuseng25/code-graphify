package com.graphify.settings;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import org.springframework.scheduling.support.CronExpression;

/** How an {@code app_setting} value is written and parsed. */
public enum SettingType {

    INT {
        @Override
        Object parse(String raw) {
            try {
                return Integer.valueOf(raw.strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("not an integer: " + raw);
            }
        }
    },
    STRING {
        @Override
        Object parse(String raw) {
            if (raw.isBlank()) {
                throw new IllegalArgumentException("must not be blank");
            }
            return raw;
        }
    },
    BOOL {
        @Override
        Object parse(String raw) {
            String value = raw.strip();
            if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                throw new IllegalArgumentException("must be true or false: " + raw);
            }
            return Boolean.valueOf(value);
        }
    },
    CRON {
        @Override
        Object parse(String raw) {
            String value = raw.strip();
            if (!CronExpression.isValidExpression(value)) {
                throw new IllegalArgumentException(
                        "not a cron expression with 6 fields (second minute hour day month weekday): " + raw);
            }
            return value;
        }
    },
    LIST {
        @Override
        Object parse(String raw) {
            return Arrays.stream(raw.split(",")).map(String::strip).filter(item -> !item.isEmpty()).toList();
        }
    },
    DURATION {
        @Override
        Object parse(String raw) {
            Duration duration;
            try {
                duration = Duration.parse(raw.strip());
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("not an ISO-8601 duration such as PT10M: " + raw);
            }
            if (duration.isNegative() || duration.isZero()) {
                throw new IllegalArgumentException("must be positive: " + raw);
            }
            return duration;
        }
    };

    abstract Object parse(String raw);
}
