package com.graphify.settings;

import com.graphify.audit.AuditLog;
import com.graphify.common.util.Utf8;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Typed access to {@code app_setting}. Values are cached until the next update; defaults exist only as Flyway
 * seed rows (V2__seed_settings.sql), never in code.
 */
@Service
public class AppSettings {

    static final String UPDATED_ACTION = "SETTING_UPDATED";

    /** Width of {@code app_setting.setting_value} in V1__core_schema.sql. */
    static final int VALUE_BYTES = 4000;

    /** Width of {@code app_setting.updated_by} and {@code audit_log.actor} in V1__core_schema.sql. */
    static final int ACTOR_BYTES = 200;

    private final SettingsRepository repository;
    private final AuditLog auditLog;
    private final ApplicationEventPublisher events;
    private volatile Map<String, Setting> cache;

    public AppSettings(SettingsRepository repository, AuditLog auditLog, ApplicationEventPublisher events) {
        this.repository = repository;
        this.auditLog = auditLog;
        this.events = events;
    }

    public int getInt(String key) {
        return (Integer) typed(key, SettingType.INT);
    }

    public boolean getBoolean(String key) {
        return (Boolean) typed(key, SettingType.BOOL);
    }

    public String getString(String key) {
        return (String) typed(key, SettingType.STRING);
    }

    public String getCron(String key) {
        return (String) typed(key, SettingType.CRON);
    }

    @SuppressWarnings("unchecked")
    public List<String> getList(String key) {
        return (List<String>) typed(key, SettingType.LIST);
    }

    public Duration getDuration(String key) {
        return (Duration) typed(key, SettingType.DURATION);
    }

    public List<Setting> all() {
        return List.copyOf(settings().values());
    }

    /**
     * Checks a value against the setting's type and bounds without saving it, and returns it as it would be stored.
     * An unknown key throws {@link SettingNotFoundException}.
     */
    public String checkValue(String key, String rawValue) {
        Setting current = find(key);
        String value = rawValue == null ? null : rawValue.strip();
        validate(current, value);
        return value;
    }

    @Transactional
    public Setting update(String key, String rawValue, String actor) {
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("actor must not be blank");
        }
        if (Utf8.byteLength(actor) > ACTOR_BYTES) {
            throw new IllegalArgumentException("actor is longer than " + ACTOR_BYTES + " bytes");
        }
        Setting current = find(key);
        String value = checkValue(key, rawValue);
        repository.updateValue(key, value, actor);
        auditLog.record(actor, UPDATED_ACTION, key, current.value() + " -> " + value);
        afterCommit(() -> {
            cache = null;
            events.publishEvent(new SettingChangedEvent(key));
        });
        return repository.find(key).orElseThrow();
    }

    /** Runs the action once the surrounding transaction commits (dropped on rollback), or now if there is none. */
    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private Object typed(String key, SettingType expected) {
        Setting setting = find(key);
        if (setting.type() != expected) {
            throw new IllegalStateException("Setting " + key + " is " + setting.type() + ", not " + expected);
        }
        return expected.parse(setting.value());
    }

    private Setting find(String key) {
        Setting setting = settings().get(key);
        if (setting == null) {
            throw new SettingNotFoundException(key);
        }
        return setting;
    }

    private Map<String, Setting> settings() {
        Map<String, Setting> current = cache;
        if (current == null) {
            Map<String, Setting> loaded = new TreeMap<>();
            repository.findAll().forEach(setting -> loaded.put(setting.key(), setting));
            current = Collections.unmodifiableMap(loaded);
            cache = current;
        }
        return current;
    }

    private static void validate(Setting setting, String value) {
        if (value == null) {
            throw new InvalidSettingValueException(setting.key(), "a value is required");
        }
        if (Utf8.byteLength(value) > VALUE_BYTES) {
            throw new InvalidSettingValueException(setting.key(), "longer than " + VALUE_BYTES + " bytes");
        }
        Object parsed;
        try {
            parsed = setting.type().parse(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidSettingValueException(setting.key(), e.getMessage());
        }
        if (parsed instanceof Integer number) {
            if (setting.minValue() != null && number < setting.minValue()) {
                throw new InvalidSettingValueException(setting.key(), "must be >= " + setting.minValue());
            }
            if (setting.maxValue() != null && number > setting.maxValue()) {
                throw new InvalidSettingValueException(setting.key(), "must be <= " + setting.maxValue());
            }
        }
    }
}
