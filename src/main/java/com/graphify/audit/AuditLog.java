package com.graphify.audit;

import com.graphify.common.util.Utf8;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Append-only record of who changed what (spec §6.4, §7.2). Never pass secret values as details. */
@Component
public class AuditLog {

    /** Width of {@code audit_log.details} in V1__core_schema.sql. */
    private static final int DETAILS_MAX_BYTES = 4000;

    private final JdbcTemplate jdbc;

    public AuditLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String actor, String action, String target, String details) {
        jdbc.update("INSERT INTO audit_log (actor, action, target, details) VALUES (?, ?, ?, ?)",
                actor, action, target, details == null ? null : Utf8.truncateToBytes(details, DETAILS_MAX_BYTES));
    }
}
