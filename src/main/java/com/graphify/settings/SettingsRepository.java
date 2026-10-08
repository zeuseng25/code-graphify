package com.graphify.settings;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class SettingsRepository {

    private final JdbcTemplate jdbc;

    SettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SELECT = """
            SELECT setting_key, setting_value, value_type, description, min_value, max_value, updated_by, updated_at
              FROM app_setting
            """;

    List<Setting> findAll() {
        return jdbc.query(SELECT, SettingsRepository::map);
    }

    Optional<Setting> find(String key) {
        return jdbc.query(SELECT + " WHERE setting_key = ?", SettingsRepository::map, key).stream().findFirst();
    }

    private static Setting map(ResultSet rs, int row) throws SQLException {
        OffsetDateTime updatedAt = rs.getObject("updated_at", OffsetDateTime.class);
        return new Setting(
                rs.getString("setting_key"),
                rs.getString("setting_value"),
                SettingType.valueOf(rs.getString("value_type")),
                rs.getString("description"),
                rs.getObject("min_value", Long.class),
                rs.getObject("max_value", Long.class),
                rs.getString("updated_by"),
                updatedAt == null ? null : updatedAt.toInstant());
    }

    void updateValue(String key, String value, String actor) {
        jdbc.update("UPDATE app_setting SET setting_value = ?, updated_by = ?, updated_at = SYSTIMESTAMP "
                + "WHERE setting_key = ?", value, actor, key);
    }
}
