package com.graphify.audit;

import com.graphify.api.Page;
import com.graphify.api.Paging;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read side of audit_log (spec §10.6 "Audit"). */
@Repository
public class AuditQueries {

    private final JdbcTemplate jdbc;

    public AuditQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Page<AuditEntry> list(String actor, String action, Instant from, Instant to, Paging paging) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (actor != null && !actor.isBlank()) {
            where.append(" AND UPPER(actor) = ?");
            args.add(actor.strip().toUpperCase(Locale.ROOT));
        }
        if (action != null && !action.isBlank()) {
            where.append(" AND UPPER(action) = ?");
            args.add(action.strip().toUpperCase(Locale.ROOT));
        }
        if (from != null) {
            where.append(" AND at >= ?");
            args.add(from.atOffset(ZoneOffset.UTC));
        }
        if (to != null) {
            where.append(" AND at < ?");
            args.add(to.atOffset(ZoneOffset.UTC));
        }
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(paging.offset());
        pageArgs.add(paging.size());
        List<AuditEntry> items = jdbc.query("SELECT id, actor, action, target, details, at FROM audit_log" + where
                + " ORDER BY at DESC, id DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY", (rs, row) -> {
                    OffsetDateTime at = rs.getObject("at", OffsetDateTime.class);
                    return new AuditEntry(rs.getLong("id"), rs.getString("actor"), rs.getString("action"),
                            rs.getString("target"), rs.getString("details"), at == null ? null : at.toInstant());
                }, pageArgs.toArray());
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log" + where, Long.class, args.toArray());
        return new Page<>(items, paging.page(), paging.size(), total == null ? 0 : total);
    }
}
