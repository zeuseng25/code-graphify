package com.graphify.scm;

import com.graphify.common.crypto.SecretCipher;
import com.graphify.common.util.Utf8;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** Reads scm_connection rows and decrypts their secrets. */
@Repository
public class ScmConnections {

    private static final String SELECT = """
            SELECT id, name, type, base_url, username, secret_enc, include_projects, exclude_repos,
                   repository_urls, include_own_repositories FROM scm_connection
            """;

    /** Width of scm_connection.last_sync_error in V5__index_orchestration.sql. */
    private static final int ERROR_BYTES = 4000;

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;

    public ScmConnections(JdbcTemplate jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    public List<ScmConnection> enabled() {
        return jdbc.query(SELECT + " WHERE enabled = 1 ORDER BY id", this::map);
    }

    public Optional<ScmConnection> find(long id) {
        return jdbc.query(SELECT + " WHERE id = ?", this::map, id).stream().findFirst();
    }

    public void recordSync(long connectionId, ConnectionSyncStatus status, String error) {
        String masked = error == null ? null : Utf8.truncateToBytes(UrlMasking.mask(error), ERROR_BYTES);
        jdbc.update("UPDATE scm_connection SET last_sync_status = ?, last_sync_at = SYSTIMESTAMP, last_sync_error = ? "
                + "WHERE id = ?", status.name(), masked, connectionId);
    }

    private static final String VIEW = """
            SELECT c.id, c.name, c.type, c.base_url, c.username, c.secret_enc, c.include_projects, c.exclude_repos, c.repository_urls,
                   c.include_own_repositories, c.enabled, c.last_test_status, c.last_test_at, c.last_sync_status, c.last_sync_at, c.last_sync_error,
                   (SELECT COUNT(*) FROM scm_repository r WHERE r.connection_id = c.id) AS repository_count
              FROM scm_connection c
            """;

    public List<ScmConnectionView> views() {
        return jdbc.query(VIEW + " ORDER BY c.name", (rs, row) -> view(rs));
    }

    public Optional<ScmConnectionView> view(long id) {
        return jdbc.query(VIEW + " WHERE c.id = ?", (rs, row) -> view(rs), id).stream().findFirst();
    }

    /** Stores a validated connection; {@code secret} is plaintext (encrypted here) or null. */
    public long insert(String name, ScmType type, String baseUrl, String username, String secret,
            List<String> includeProjects, List<String> excludeRepos, List<String> repositoryUrls,
            boolean includeOwnRepositories, boolean enabled) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO scm_connection (name, type, base_url, username, secret_enc, include_projects,
                                                exclude_repos, repository_urls, include_own_repositories, enabled)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[] {"id"});
            statement.setString(1, name);
            statement.setString(2, type.name());
            statement.setString(3, baseUrl);
            statement.setString(4, username);
            statement.setString(5, secret == null ? null : cipher.encrypt(secret));
            statement.setString(6, join(includeProjects));
            statement.setString(7, join(excludeRepos));
            statement.setString(8, joinLines(repositoryUrls));
            statement.setInt(9, includeOwnRepositories ? 1 : 0);
            statement.setInt(10, enabled ? 1 : 0);
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    public void update(long id, String name, ScmType type, String baseUrl, String username, String secret,
            List<String> includeProjects, List<String> excludeRepos, List<String> repositoryUrls,
            boolean includeOwnRepositories, boolean enabled) {
        jdbc.update("""
                UPDATE scm_connection SET name = ?, type = ?, base_url = ?, username = ?, secret_enc = ?,
                       include_projects = ?, exclude_repos = ?, repository_urls = ?,
                       include_own_repositories = ?, enabled = ?
                 WHERE id = ?
                """, name, type.name(), baseUrl, username, secret == null ? null : cipher.encrypt(secret),
                join(includeProjects), join(excludeRepos), joinLines(repositoryUrls),
                includeOwnRepositories ? 1 : 0, enabled ? 1 : 0, id);
    }

    /** Locks the connection row until the transaction ends, so a concurrent sync cannot add repositories meanwhile. */
    public void lock(long id) {
        jdbc.query("SELECT id FROM scm_connection WHERE id = ? FOR UPDATE", rs -> {
        }, id);
    }

    /** Stops indexing a connection's repositories; the next sync reactivates the ones the connection still lists. */
    public void deactivateRepositories(long id) {
        jdbc.update("UPDATE scm_repository SET active = 0 WHERE connection_id = ?", id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM scm_connection WHERE id = ?", id);
    }

    public int repositoryCount(long id) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM scm_repository WHERE connection_id = ?",
                Integer.class, id);
        return count == null ? 0 : count;
    }

    public void recordTest(long id, ConnectionSyncStatus status) {
        jdbc.update("UPDATE scm_connection SET last_test_status = ?, last_test_at = SYSTIMESTAMP WHERE id = ?",
                status.name(), id);
    }

    static String join(List<String> items) {
        return items == null || items.isEmpty() ? null : String.join(",", items);
    }

    static String joinLines(List<String> items) {
        return items == null || items.isEmpty() ? null : String.join("\n", items);
    }

    private static ScmConnectionView view(ResultSet rs) throws SQLException {
        return new ScmConnectionView(rs.getLong("id"), rs.getString("name"), ScmType.valueOf(rs.getString("type")),
                rs.getString("base_url"), rs.getString("username"), rs.getString("secret_enc") != null,
                split(rs.getString("include_projects")), split(rs.getString("exclude_repos")),
                splitLines(rs.getString("repository_urls")), rs.getInt("include_own_repositories") == 1,
                rs.getInt("enabled") == 1, rs.getString("last_test_status"), instant(rs, "last_test_at"),
                rs.getString("last_sync_status"), instant(rs, "last_sync_at"), rs.getString("last_sync_error"),
                rs.getInt("repository_count"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private ScmConnection map(ResultSet rs, int row) throws SQLException {
        String secret = rs.getString("secret_enc");
        return new ScmConnection(rs.getLong("id"), rs.getString("name"), ScmType.valueOf(rs.getString("type")),
                rs.getString("base_url"), rs.getString("username"), secret == null ? null : cipher.decrypt(secret),
                split(rs.getString("include_projects")), split(rs.getString("exclude_repos")), splitLines(rs.getString("repository_urls")),
                rs.getInt("include_own_repositories") == 1);
    }

    static List<String> split(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    static List<String> splitLines(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return Arrays.stream(text.split("\\R")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
