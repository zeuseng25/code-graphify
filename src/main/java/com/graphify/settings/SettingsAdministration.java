package com.graphify.settings;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** An admin's change to a setting: the type and bounds, then the key's server-side check, then the save. */
@Service
public class SettingsAdministration {

    private final AppSettings settings;
    private final Map<String, SettingValidator> validators;

    @Autowired
    public SettingsAdministration(AppSettings settings, ObjectProvider<SettingValidator> validators) {
        this(settings, validators.orderedStream().toList());
    }

    SettingsAdministration(AppSettings settings, List<SettingValidator> validators) {
        this.settings = settings;
        Map<String, SettingValidator> byKey = new HashMap<>();
        for (SettingValidator validator : validators) {
            for (String key : validator.keys()) {
                if (byKey.putIfAbsent(key, validator) != null) {
                    throw new IllegalStateException("Two setting validators check " + key);
                }
            }
        }
        this.validators = Map.copyOf(byKey);
    }

    /**
     * Saves the value if its type, bounds and server-side check allow it. The check runs outside any database
     * transaction because it may run a program; a refused value is not saved.
     */
    public Setting update(String key, String rawValue, String actor) {
        String value = settings.checkValue(key, rawValue);
        SettingValidator validator = validators.get(key);
        if (validator != null) {
            value = validator.normalize(key, value);
            String checked = value;
            validator.problem(key, checked).ifPresent(reason -> {
                throw new InvalidSettingValueException(key, reason);
            });
        }
        return settings.update(key, value, actor);
    }
}
