package com.graphify.settings;

import java.util.Optional;
import java.util.Set;

/**
 * Checks what a setting does on the server (a directory it writes, a program it runs) when an admin saves it through
 * the API. Values written through {@link AppSettings#update} directly (tests, internal callers) are not checked, and
 * nothing is re-checked at startup.
 */
public interface SettingValidator {

    /** The setting keys this validator checks. */
    Set<String> keys();

    /**
     * The value to save for {@code value} (already type-checked and stripped): what {@link #problem} will check and
     * what is stored, so the check and the save name the same thing.
     */
    default String normalize(String key, String value) {
        return value;
    }

    /** Why {@code value} (already type-checked and stripped) cannot be used for {@code key}; empty if it can. */
    Optional<String> problem(String key, String value);
}
