package com.graphify.auth;

import com.graphify.api.Page;
import com.graphify.api.Paging;
import com.graphify.common.util.Utf8;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** app_user rows (spec §7.2). Usernames are stored normalized: stripped and lower-cased. */
@Repository
public class AppUsers {

    /** Widths of app_user.display_name and app_user.email in V6__authentication.sql. */
    private static final int DISPLAY_NAME_BYTES = 200;
    private static final int EMAIL_BYTES = 320;

    private static final String SELECT = """
            SELECT id, username, source, display_name, email, role, role_granted_by, role_granted_at, last_login_at,
                   active
              FROM app_user
            """;

    private final JdbcTemplate jdbc;

    public AppUsers(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static String normalize(String username) {
        return username == null ? null : username.strip().toLowerCase(Locale.ROOT);
    }

    public Optional<AppUser> find(long id) {
        return jdbc.query(SELECT + " WHERE id = ?", (rs, row) -> map(rs), id).stream().findFirst();
    }

    public Optional<AppUser> findByUsername(String username) {
        return jdbc.query(SELECT + " WHERE username = ?", (rs, row) -> map(rs), normalize(username)).stream()
                .findFirst();
    }

    public AppUser create(String username, UserSource source, String displayName, String email, Role role,
            String grantedBy) {
        String normalized = normalize(username);
        jdbc.update("""
                INSERT INTO app_user (username, source, display_name, email, role, role_granted_by, role_granted_at)
                VALUES (?, ?, ?, ?, ?, ?, SYSTIMESTAMP)
                """, normalized, source.name(), cut(displayName, DISPLAY_NAME_BYTES), cut(email, EMAIL_BYTES),
                role.name(), grantedBy);
        return findByUsername(normalized).orElseThrow();
    }

    /** Stamps a successful login; non-null directory attributes replace the stored ones. */
    public void recordLogin(long id, String displayName, String email) {
        jdbc.update("""
                UPDATE app_user SET last_login_at = SYSTIMESTAMP, display_name = NVL(?, display_name),
                                    email = NVL(?, email)
                 WHERE id = ?
                """, cut(displayName, DISPLAY_NAME_BYTES), cut(email, EMAIL_BYTES), id);
    }

    public void setRole(long id, Role role, String grantedBy) {
        jdbc.update("UPDATE app_user SET role = ?, role_granted_by = ?, role_granted_at = SYSTIMESTAMP WHERE id = ?",
                role.name(), grantedBy, id);
    }

    public void setActive(long id, boolean active) {
        jdbc.update("UPDATE app_user SET active = ? WHERE id = ?", active ? 1 : 0, id);
    }

    /** Users whose username or display name contains {@code query} (case-insensitive), ordered by username. */
    public Page<AppUser> list(String query, Paging paging) {
        String pattern = query == null || query.isBlank() ? "%"
                : "%" + query.strip().toUpperCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%")
                        .replace("_", "\\_") + "%";
        String where = " WHERE UPPER(username) LIKE ? ESCAPE '\\' OR UPPER(display_name) LIKE ? ESCAPE '\\'";
        List<AppUser> items = jdbc.query(SELECT + where + " ORDER BY username OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                (rs, row) -> map(rs), pattern, pattern, paging.offset(), paging.size());
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM app_user" + where, Long.class, pattern, pattern);
        return new Page<>(items, paging.page(), paging.size(), total == null ? 0 : total);
    }

    /**
     * Locks every active admin row and the target row with one statement, so the last-admin decision is made on rows
     * no concurrent change can alter; call inside a transaction. The target is absent from the result when it does
     * not exist.
     */
    public List<AppUser> lockAdminsAnd(long targetId) {
        return jdbc.query(SELECT + " WHERE (role = 'ADMIN' AND active = 1) OR id = ? ORDER BY id FOR UPDATE",
                (rs, row) -> map(rs), targetId);
    }

    private static String cut(String text, int maxBytes) {
        return text == null ? null : Utf8.truncateToBytes(text, maxBytes);
    }

    private static AppUser map(ResultSet rs) throws SQLException {
        return new AppUser(rs.getLong("id"), rs.getString("username"), UserSource.valueOf(rs.getString("source")),
                rs.getString("display_name"), rs.getString("email"), Role.valueOf(rs.getString("role")),
                rs.getString("role_granted_by"), instant(rs, "role_granted_at"), instant(rs, "last_login_at"),
                rs.getInt("active") == 1);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
