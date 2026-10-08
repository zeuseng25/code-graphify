package com.graphify.maven;

import com.graphify.common.crypto.SecretCipher;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class ArtifactRepositories {

    private static final String SELECT = """
            SELECT id, name, url, username, secret_enc, mirror_of FROM artifact_repository
            """;

    private static final String VIEW = """
            SELECT id, name, url, username, secret_enc, mirror_of, sort_order, enabled, last_test_status, last_test_at
              FROM artifact_repository
            """;

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;

    public ArtifactRepositories(JdbcTemplate jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    public List<ArtifactRepository> enabled() {
        return jdbc.query(SELECT + " WHERE enabled = 1 ORDER BY sort_order, id", this::map);
    }

    /** The repository with its secret decrypted. */
    public Optional<ArtifactRepository> find(long id) {
        return jdbc.query(SELECT + " WHERE id = ?", this::map, id).stream().findFirst();
    }

    public List<ArtifactRepositoryView> views() {
        return jdbc.query(VIEW + " ORDER BY sort_order, id", (rs, row) -> view(rs));
    }

    public Optional<ArtifactRepositoryView> view(long id) {
        return jdbc.query(VIEW + " WHERE id = ?", (rs, row) -> view(rs), id).stream().findFirst();
    }

    /** Stores a validated repository; {@code secret} is plaintext (encrypted here) or null. */
    public long insert(String name, String url, String username, String secret, String mirrorOf, int sortOrder,
            boolean enabled) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO artifact_repository (name, url, username, secret_enc, mirror_of, sort_order, enabled)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, new String[] {"id"});
            statement.setString(1, name);
            statement.setString(2, url);
            statement.setString(3, username);
            statement.setString(4, secret == null ? null : cipher.encrypt(secret));
            statement.setString(5, mirrorOf);
            statement.setInt(6, sortOrder);
            statement.setInt(7, enabled ? 1 : 0);
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    public void update(long id, String name, String url, String username, String secret, String mirrorOf,
            int sortOrder, boolean enabled) {
        jdbc.update("""
                UPDATE artifact_repository SET name = ?, url = ?, username = ?, secret_enc = ?, mirror_of = ?,
                       sort_order = ?, enabled = ?
                 WHERE id = ?
                """, name, url, username, secret == null ? null : cipher.encrypt(secret), mirrorOf, sortOrder,
                enabled ? 1 : 0, id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM artifact_repository WHERE id = ?", id);
    }

    public void recordTest(long id, String status) {
        jdbc.update("UPDATE artifact_repository SET last_test_status = ?, last_test_at = SYSTIMESTAMP WHERE id = ?",
                status, id);
    }

    private ArtifactRepository map(ResultSet rs, int row) throws SQLException {
        String secret = rs.getString("secret_enc");
        return new ArtifactRepository(rs.getLong("id"), rs.getString("name"), rs.getString("url"),
                rs.getString("username"), secret == null ? null : cipher.decrypt(secret), rs.getString("mirror_of"));
    }

    private static ArtifactRepositoryView view(ResultSet rs) throws SQLException {
        OffsetDateTime testedAt = rs.getObject("last_test_at", OffsetDateTime.class);
        return new ArtifactRepositoryView(rs.getLong("id"), rs.getString("name"), rs.getString("url"),
                rs.getString("username"), rs.getString("secret_enc") != null, rs.getString("mirror_of"),
                rs.getInt("sort_order"), rs.getInt("enabled") == 1, rs.getString("last_test_status"),
                testedAt == null ? null : testedAt.toInstant());
    }
}
