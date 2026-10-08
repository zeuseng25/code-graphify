package com.graphify.repository;

import com.graphify.api.Page;
import com.graphify.api.Paging;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RepositoryQueries {

    private static final String SELECT = """
            SELECT r.id, r.project_key, r.slug, r.default_branch, r.last_indexed_commit, r.last_indexed_at,
                   r.last_status, r.active,
                   (SELECT COUNT(*) FROM maven_module m WHERE m.repo_id = r.id) AS module_count
              FROM scm_repository r
            """;

    private static final String FILTER =
            " WHERE (UPPER(r.slug) LIKE ? ESCAPE '\\' OR UPPER(r.project_key) LIKE ? ESCAPE '\\')";

    private final JdbcTemplate jdbc;

    public RepositoryQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Page<RepositorySummary> list(String text, String status, Paging paging) {
        String pattern = likePattern(text);
        String where = FILTER + (status == null ? "" : " AND r.last_status = ?");
        List<Object> args = new ArrayList<>(List.of(pattern, pattern));
        if (status != null) {
            args.add(status);
        }
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(paging.offset());
        pageArgs.add(paging.size());
        List<RepositorySummary> items = jdbc.query(SELECT + where
                        + " ORDER BY r.project_key, r.slug OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                (rs, row) -> summary(rs), pageArgs.toArray());
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM scm_repository r" + where, Long.class, args.toArray());
        return new Page<>(items, paging.page(), paging.size(), total == null ? 0 : total);
    }

    public Optional<RepositoryDetail> find(long id) {
        List<RepositorySummary> found = jdbc.query(SELECT + " WHERE r.id = ?", (rs, row) -> summary(rs), id);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        List<ModuleView> modules = jdbc.query("""
                SELECT id, path, group_id, artifact_id, version, classpath_mode
                  FROM maven_module WHERE repo_id = ? ORDER BY path
                """, (rs, row) -> new ModuleView(rs.getLong("id"), rs.getString("path"), rs.getString("group_id"),
                rs.getString("artifact_id"), rs.getString("version"), rs.getString("classpath_mode")), id);
        return Optional.of(new RepositoryDetail(found.getFirst(), modules));
    }

    /** Upper-cased {@code %text%} with LIKE metacharacters escaped, so user text matches literally. */
    private static String likePattern(String text) {
        if (text == null || text.isBlank()) {
            return "%";
        }
        String escaped = text.strip().toUpperCase(Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private static RepositorySummary summary(ResultSet rs) throws SQLException {
        OffsetDateTime indexedAt = rs.getObject("last_indexed_at", OffsetDateTime.class);
        return new RepositorySummary(
                rs.getLong("id"),
                rs.getString("project_key"),
                rs.getString("slug"),
                rs.getString("default_branch"),
                rs.getString("last_indexed_commit"),
                indexedAt == null ? null : indexedAt.toInstant(),
                rs.getString("last_status"),
                rs.getInt("active") == 1,
                rs.getInt("module_count"));
    }
}
