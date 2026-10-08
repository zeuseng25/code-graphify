package com.graphify.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.graphify.OracleIntegrationTest;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

@RecordApplicationEvents
class AppSettingsTest extends OracleIntegrationTest {

    private static final String ACTOR = "app-settings-test";

    @Autowired
    AppSettings settings;

    @Autowired
    ApplicationEvents events;

    @Autowired
    TransactionTemplate transactions;

    private final Map<String, String> originals = new HashMap<>();

    @AfterEach
    void restoreChangedSettings() {
        originals.forEach((key, value) -> settings.update(key, value, ACTOR));
        originals.clear();
    }

    private void change(String key, String value) {
        originals.putIfAbsent(key, settings.all().stream().filter(s -> s.key().equals(key)).findFirst()
                .orElseThrow().value());
        settings.update(key, value, ACTOR);
    }

    @Test
    void everyDeclaredKeyIsSeeded() {
        String[] declared = Arrays.stream(SettingKeys.class.getFields())
                .filter(f -> Modifier.isStatic(f.getModifiers()) && f.getType() == String.class)
                .map(SettingsTestSupport::constantValue)
                .toArray(String[]::new);

        assertThat(settings.all()).extracting(Setting::key).contains(declared);
    }

    @Test
    void readsSeededValuesWithTheirTypes() {
        assertThat(settings.getInt(SettingKeys.INDEX_PARALLELISM)).isEqualTo(4);
        assertThat(settings.getInt(SettingKeys.STORE_JDBC_BATCH_SIZE)).isEqualTo(1000);
        assertThat(settings.getDuration(SettingKeys.INDEX_MAVEN_TIMEOUT)).isEqualTo(Duration.ofMinutes(10));
        assertThat(settings.getCron(SettingKeys.INDEX_CRON)).isEqualTo("0 0 2 * * *");
        assertThat(settings.getString(SettingKeys.INDEX_WORKSPACE_DIR)).isEqualTo("/data/impact-analyzer/repos");
    }

    @Test
    void rejectsReadingAKeyAsTheWrongTypeOrAnUnknownKey() {
        assertThatThrownBy(() -> settings.getDuration(SettingKeys.INDEX_PARALLELISM))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(SettingKeys.INDEX_PARALLELISM);
        assertThatThrownBy(() -> settings.getInt("no.such.key"))
                .isInstanceOf(SettingNotFoundException.class);
    }

    @Test
    void validUpdateIsStoredAuditedAndAnnounced() {
        change(SettingKeys.INDEX_PARALLELISM, " 8 ");

        assertThat(settings.getInt(SettingKeys.INDEX_PARALLELISM)).isEqualTo(8);
        assertThat(jdbc.queryForObject(
                "SELECT details FROM audit_log WHERE actor = ? AND target = ? AND action = 'SETTING_UPDATED' "
                        + "ORDER BY id DESC FETCH FIRST 1 ROWS ONLY",
                String.class, ACTOR, SettingKeys.INDEX_PARALLELISM)).isEqualTo("4 -> 8");
        assertThat(jdbc.queryForObject("SELECT updated_by FROM app_setting WHERE setting_key = ?", String.class,
                SettingKeys.INDEX_PARALLELISM)).isEqualTo(ACTOR);
        assertThat(events.stream(SettingChangedEvent.class))
                .contains(new SettingChangedEvent(SettingKeys.INDEX_PARALLELISM));
        Setting reloaded = settings.all().stream().filter(s -> s.key().equals(SettingKeys.INDEX_PARALLELISM))
                .findFirst().orElseThrow();
        assertThat(reloaded.updatedAt()).isCloseTo(Instant.now(), within(5, ChronoUnit.MINUTES));
    }

    @Test
    void invalidUpdatesAreRejectedAndChangeNothing() {
        Integer auditRowsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class);

        assertThatThrownBy(() -> settings.update(SettingKeys.INDEX_PARALLELISM, "0", ACTOR))
                .isInstanceOf(InvalidSettingValueException.class).hasMessageContaining(">= 1");
        assertThatThrownBy(() -> settings.update(SettingKeys.INDEX_PARALLELISM, "33", ACTOR))
                .isInstanceOf(InvalidSettingValueException.class).hasMessageContaining("<= 32");
        assertThatThrownBy(() -> settings.update(SettingKeys.INDEX_PARALLELISM, "many", ACTOR))
                .isInstanceOf(InvalidSettingValueException.class);
        assertThatThrownBy(() -> settings.update(SettingKeys.INDEX_CRON, "every night", ACTOR))
                .isInstanceOf(InvalidSettingValueException.class);
        assertThatThrownBy(() -> settings.update(SettingKeys.INDEX_MAVEN_TIMEOUT, "PT0S", ACTOR))
                .isInstanceOf(InvalidSettingValueException.class);
        assertThatThrownBy(() -> settings.update(SettingKeys.INDEX_PARALLELISM, null, ACTOR))
                .isInstanceOf(InvalidSettingValueException.class);
        assertThatThrownBy(() -> settings.update("no.such.key", "1", ACTOR))
                .isInstanceOf(SettingNotFoundException.class);
        assertThatThrownBy(() -> settings.update(SettingKeys.INDEX_WORKSPACE_DIR, "/" + "ş".repeat(2000), ACTOR))
                .isInstanceOf(InvalidSettingValueException.class).hasMessageContaining("longer than 4000 bytes");
        assertThatThrownBy(() -> settings.update(SettingKeys.INDEX_PARALLELISM, "8", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings.update(SettingKeys.INDEX_PARALLELISM, "8", "  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings.update(SettingKeys.INDEX_PARALLELISM, "8", "ğ".repeat(101)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(settings.getInt(SettingKeys.INDEX_PARALLELISM)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class)).isEqualTo(auditRowsBefore);
    }

    private boolean announced(String key) {
        return events.stream(SettingChangedEvent.class).anyMatch(e -> e.key().equals(key));
    }

    @Test
    void changeBecomesVisibleAndIsAnnouncedOnlyAfterCommit() {
        originals.putIfAbsent(SettingKeys.INDEX_PARALLELISM, "4");
        assertThat(settings.getInt(SettingKeys.INDEX_PARALLELISM)).isEqualTo(4); // warm the cache

        transactions.executeWithoutResult(status -> {
            settings.update(SettingKeys.INDEX_PARALLELISM, "6", ACTOR);

            assertThat(announced(SettingKeys.INDEX_PARALLELISM)).isFalse();
            assertThat(settings.getInt(SettingKeys.INDEX_PARALLELISM)).isEqualTo(4);
        });

        assertThat(settings.getInt(SettingKeys.INDEX_PARALLELISM)).isEqualTo(6);
        assertThat(announced(SettingKeys.INDEX_PARALLELISM)).isTrue();
    }

    @Test
    void rolledBackChangeIsNeitherAnnouncedNorCached() {
        assertThat(settings.getInt(SettingKeys.INDEX_PARALLELISM)).isEqualTo(4); // warm the cache

        transactions.executeWithoutResult(status -> {
            settings.update(SettingKeys.INDEX_PARALLELISM, "7", ACTOR);
            status.setRollbackOnly();
        });

        assertThat(settings.getInt(SettingKeys.INDEX_PARALLELISM)).isEqualTo(4);
        assertThat(announced(SettingKeys.INDEX_PARALLELISM)).isFalse();
        assertThat(jdbc.queryForObject("SELECT setting_value FROM app_setting WHERE setting_key = ?", String.class,
                SettingKeys.INDEX_PARALLELISM)).isEqualTo("4");
    }

    @Test
    void updateDoesNotRunTheServerSideChecks() {
        // tests and internal callers restore the seeded /data/... defaults; only the admin API checks the server side
        change(SettingKeys.INDEX_WORKSPACE_DIR, "relative/not/checked");

        assertThat(settings.getString(SettingKeys.INDEX_WORKSPACE_DIR)).isEqualTo("relative/not/checked");
    }

    @Test
    void checkValueValidatesWithoutSaving() {
        int before = settings.getInt(SettingKeys.INDEX_PARALLELISM);

        assertThat(settings.checkValue(SettingKeys.INDEX_PARALLELISM, " 8 ")).isEqualTo("8");
        assertThatThrownBy(() -> settings.checkValue(SettingKeys.INDEX_PARALLELISM, "0"))
                .isInstanceOf(InvalidSettingValueException.class);
        assertThatThrownBy(() -> settings.checkValue("no.such.key", "1")).isInstanceOf(SettingNotFoundException.class);
        assertThat(settings.getInt(SettingKeys.INDEX_PARALLELISM)).isEqualTo(before);
    }
}

final class SettingsTestSupport {

    private SettingsTestSupport() {
    }

    static String constantValue(Field field) {
        try {
            return (String) field.get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
