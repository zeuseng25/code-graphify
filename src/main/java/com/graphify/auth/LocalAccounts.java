package com.graphify.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** local_account rows: bcrypt hashes and the lockout counter (spec §7.2, §7.4). All time checks use database time. */
@Repository
public class LocalAccounts {

    private final JdbcTemplate jdbc;

    public LocalAccounts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean any() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM local_account", Integer.class);
        return count != null && count > 0;
    }

    public Optional<LocalAccount> find(String username) {
        return jdbc.query("""
                SELECT username, password_hash, must_change_password, enabled, failed_attempts, locked_until,
                       CASE WHEN locked_until > SYSTIMESTAMP THEN 1 ELSE 0 END AS locked
                  FROM local_account WHERE username = ?
                """, (rs, row) -> map(rs), AppUsers.normalize(username)).stream().findFirst();
    }

    public void create(String username, String passwordHash, boolean mustChangePassword) {
        jdbc.update("INSERT INTO local_account (username, password_hash, must_change_password) VALUES (?, ?, ?)",
                AppUsers.normalize(username), passwordHash, mustChangePassword ? 1 : 0);
    }

    /**
     * Counts a failed password; at {@code maxAttempts} consecutive failures the account locks for
     * {@code lockDuration}. When an earlier lock has expired the count restarts, so the limit means consecutive
     * failures after it too.
     */
    public boolean recordFailure(String username, int maxAttempts, Duration lockDuration) {
        String normalized = AppUsers.normalize(username);
        jdbc.update("""
                UPDATE local_account
                   SET failed_attempts = CASE WHEN locked_until IS NOT NULL AND locked_until <= SYSTIMESTAMP
                                              THEN 1 ELSE failed_attempts + 1 END,
                       locked_until = CASE WHEN (CASE WHEN locked_until IS NOT NULL AND locked_until <= SYSTIMESTAMP
                                                      THEN 1 ELSE failed_attempts + 1 END) >= ?
                                           THEN SYSTIMESTAMP + NUMTODSINTERVAL(?, 'SECOND')
                                           WHEN locked_until IS NOT NULL AND locked_until <= SYSTIMESTAMP THEN NULL
                                           ELSE locked_until END
                 WHERE username = ?
                """, maxAttempts, lockDuration.toMillis() / 1000.0, normalized);
        return find(normalized).map(LocalAccount::locked).orElse(false);
    }

    public void recordSuccess(String username) {
        jdbc.update("UPDATE local_account SET failed_attempts = 0, locked_until = NULL WHERE username = ?",
                AppUsers.normalize(username));
    }

    /**
     * Resets the counter after a correct password, unless the account is locked at this moment (a parallel burst of
     * failures may have locked it after the caller's snapshot). Returns false, changing nothing, when it is locked.
     */
    public boolean recordSuccessUnlessLocked(String username) {
        return jdbc.update("""
                UPDATE local_account SET failed_attempts = 0, locked_until = NULL
                 WHERE username = ? AND (locked_until IS NULL OR locked_until <= SYSTIMESTAMP)
                """, AppUsers.normalize(username)) == 1;
    }

    public void setPassword(String username, String passwordHash, boolean mustChangePassword) {
        jdbc.update("UPDATE local_account SET password_hash = ?, must_change_password = ? WHERE username = ?",
                passwordHash, mustChangePassword ? 1 : 0, AppUsers.normalize(username));
    }

    public void setEnabled(String username, boolean enabled) {
        jdbc.update("UPDATE local_account SET enabled = ? WHERE username = ?", enabled ? 1 : 0,
                AppUsers.normalize(username));
    }

    private static LocalAccount map(ResultSet rs) throws SQLException {
        OffsetDateTime lockedUntil = rs.getObject("locked_until", OffsetDateTime.class);
        return new LocalAccount(rs.getString("username"), rs.getString("password_hash"),
                rs.getInt("must_change_password") == 1, rs.getInt("enabled") == 1, rs.getInt("failed_attempts"),
                lockedUntil == null ? null : lockedUntil.toInstant(), rs.getInt("locked") == 1);
    }
}
