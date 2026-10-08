package com.graphify.indexing;

import com.graphify.api.Page;
import com.graphify.api.Paging;
import com.graphify.repository.RepositoryRef;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read side of index_run / index_run_repo for the API (spec §10.5). */
@Repository
public class IndexRunQueries {

    private static final String RUN = """
            SELECT id, trigger_type, scope, scope_id, status, started_by, started_at, finished_at, cancel_requested,
                   error
              FROM index_run
            """;

    /** API bound on the stored Maven tail, in characters. */
    private static final int INSTALL_ERROR_LIMIT = 4000;

    private static final String REPO_RUN = """
            SELECT x.run_id, r.id AS repo_id, r.project_key, r.slug, x.commit_sha, x.status, x.classpath_mode, x.error,
                   x.symbol_count, x.usage_count, x.warning_count, x.duration_ms, x.finished_at, x.artifact_install,
                   x.artifact_install_error
              FROM index_run_repo x JOIN scm_repository r ON r.id = x.repo_id
            """;

    private final JdbcTemplate jdbc;

    public IndexRunQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Page<IndexRunSummary> list(Paging paging) {
        List<IndexRunSummary> items = jdbc.query(RUN + " ORDER BY started_at DESC, id DESC "
                + "OFFSET ? ROWS FETCH NEXT ? ROWS ONLY", (rs, row) -> summary(rs), paging.offset(), paging.size());
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM index_run", Long.class);
        return new Page<>(items, paging.page(), paging.size(), total == null ? 0 : total);
    }

    public Optional<IndexRunView> find(long runId) {
        List<IndexRunView> found = jdbc.query(RUN + " WHERE id = ?", (rs, row) -> new IndexRunView(summary(rs),
                rs.getString("error"), Map.of(), List.of(), List.of()), runId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        List<IndexRunRepoView> repositories = jdbc.query(REPO_RUN + " WHERE x.run_id = ? ORDER BY r.project_key, "
                + "r.slug, r.id", (rs, row) -> repoRun(rs), runId);
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        repositories.forEach(repository -> byStatus.merge(repository.status(), 1, Integer::sum));
        List<RepositoryRef> inProgress = jdbc.query("SELECT id, project_key, slug FROM scm_repository "
                + "WHERE indexing_run_id = ? ORDER BY project_key, slug, id", (rs, row) -> new RepositoryRef(
                        rs.getLong("id"), rs.getString("project_key"), rs.getString("slug")), runId);
        IndexRunView run = found.getFirst();
        return Optional.of(new IndexRunView(run.run(), run.error(), byStatus, repositories, inProgress));
    }

    public boolean repositoryExists(long repositoryId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM scm_repository WHERE id = ?", Integer.class,
                repositoryId);
        return count != null && count > 0;
    }

    public Page<IndexRunRepoView> repositoryRuns(long repositoryId, Paging paging) {
        List<IndexRunRepoView> items = jdbc.query(REPO_RUN + " WHERE x.repo_id = ? ORDER BY x.finished_at DESC, "
                + "x.id DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY", (rs, row) -> repoRun(rs), repositoryId,
                paging.offset(), paging.size());
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM index_run_repo WHERE repo_id = ?", Long.class,
                repositoryId);
        return new Page<>(items, paging.page(), paging.size(), total == null ? 0 : total);
    }

    private static IndexRunSummary summary(ResultSet rs) throws SQLException {
        return new IndexRunSummary(rs.getLong("id"), rs.getString("trigger_type"), rs.getString("scope"),
                rs.getObject("scope_id", Long.class), rs.getString("status"), rs.getString("started_by"),
                instant(rs, "started_at"), instant(rs, "finished_at"), rs.getInt("cancel_requested") == 1);
    }

    private static IndexRunRepoView repoRun(ResultSet rs) throws SQLException {
        return new IndexRunRepoView(rs.getLong("run_id"),
                new RepositoryRef(rs.getLong("repo_id"), rs.getString("project_key"), rs.getString("slug")),
                rs.getString("commit_sha"), rs.getString("status"), rs.getString("classpath_mode"),
                rs.getString("error"), rs.getInt("symbol_count"), rs.getInt("usage_count"),
                rs.getInt("warning_count"), rs.getLong("duration_ms"), instant(rs, "finished_at"),
                rs.getString("artifact_install"), installError(rs));
    }

    /**
     * The CLOB cut in Java (SQL SUBSTR into VARCHAR2 is capped at 4000 bytes, not characters). The head is kept: the
     * column is already a bounded Maven tail, and the first lines say which cycle or root failed.
     */
    private static String installError(ResultSet rs) throws SQLException {
        Clob clob = rs.getClob("artifact_install_error");
        if (clob == null) {
            return null;
        }
        // read only the head, so a single huge output line is never loaded whole
        String error = clob.getSubString(1, (int) Math.min(clob.length(), INSTALL_ERROR_LIMIT + 1L));
        if (error.length() <= INSTALL_ERROR_LIMIT) {
            return error;
        }
        int end = Character.isHighSurrogate(error.charAt(INSTALL_ERROR_LIMIT - 1))
                ? INSTALL_ERROR_LIMIT - 1 : INSTALL_ERROR_LIMIT;
        return error.substring(0, end);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
