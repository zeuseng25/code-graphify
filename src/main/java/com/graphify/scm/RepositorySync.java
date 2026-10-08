package com.graphify.scm;

import com.graphify.common.util.Utf8;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import com.graphify.store.RepositoryIndexWriter;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Makes a connection's scm_repository rows match what its SCM lists (spec §3.2 step 1, §4.1 active flag).
 * A sync that would deactivate more than scm.max_deactivation_percent of the active repositories at once (and
 * more than one) deactivates nothing: a revoked permission or a mistyped filter must not wipe the index. */
@Service
public class RepositorySync {

    private static final Logger log = LoggerFactory.getLogger(RepositorySync.class);

    /** Width of scm_repository.clone_url in V1__core_schema.sql. */
    private static final int CLONE_URL_BYTES = 1000;

    /**
     * {@code deactivationsSkipped} counts repositories left active because too many disappeared at once;
     * {@code purged}/{@code purgesSkipped} count inactive repositories (a repointed connection) whose index was
     * deleted, or kept by the same guard, because the host no longer lists them.
     */
    public record SyncResult(int listed, int added, int reactivated, int deactivated, int deactivationsSkipped,
            int purged, int purgesSkipped) {

        public SyncResult(int listed, int added, int reactivated, int deactivated, int deactivationsSkipped) {
            this(listed, added, reactivated, deactivated, deactivationsSkipped, 0, 0);
        }

        public SyncResult(int listed, int added, int reactivated, int deactivated) {
            this(listed, added, reactivated, deactivated, 0);
        }
    }

    private record Purge(int purged, int skipped) {
    }

    private final JdbcTemplate jdbc;
    private final List<ScmClient> clients;
    private final RepositoryIndexWriter writer;
    private final AppSettings settings;

    public RepositorySync(JdbcTemplate jdbc, List<ScmClient> clients, RepositoryIndexWriter writer,
            AppSettings settings) {
        this.jdbc = jdbc;
        this.clients = clients;
        this.writer = writer;
        this.settings = settings;
    }

    public SyncResult sync(ScmConnection connection) {
        ScmClient client = clients.stream().filter(c -> c.type() == connection.type()).findFirst()
                .orElseThrow(() -> new IllegalStateException("No SCM client for " + connection.type()));
        int activeBefore = jdbc.queryForObject("SELECT COUNT(*) FROM scm_repository WHERE connection_id = ? "
                + "AND active = 1", Integer.class, connection.id());
        // all indexed repositories, active or not: a stable denominator, so a purge held back stays held back
        // (rather than escalating) once the listed repositories are reactivated by the first sync
        int indexedBefore = jdbc.queryForObject("SELECT COUNT(*) FROM scm_repository WHERE connection_id = ? "
                + "AND last_indexed_commit IS NOT NULL", Integer.class, connection.id());
        List<RemoteRepository> listed = client.listRepositories(connection);
        Set<String> seen = new HashSet<>();
        int added = 0;
        int reactivated = 0;
        for (RemoteRepository repository : listed) {
            // still listed, so never deactivated below; a URL too long to store leaves its row as it is
            seen.add(repository.projectKey() + "/" + repository.slug());
            if (Utf8.byteLength(repository.cloneUrl()) > CLONE_URL_BYTES) {
                log.warn("Connection '{}': the clone URL of {}/{} is longer than {} bytes; its row is left unchanged",
                        connection.name(), repository.projectKey(), repository.slug(), CLONE_URL_BYTES);
                continue;
            }
            List<Integer> active = jdbc.queryForList("SELECT active FROM scm_repository WHERE connection_id = ? "
                    + "AND project_key = ? AND slug = ?", Integer.class, connection.id(), repository.projectKey(),
                    repository.slug());
            if (active.isEmpty()) {
                jdbc.update("INSERT INTO scm_repository (connection_id, project_key, slug, clone_url, active) "
                        + "VALUES (?, ?, ?, ?, 1)", connection.id(), repository.projectKey(), repository.slug(),
                        repository.cloneUrl());
                added++;
            } else {
                if (active.getFirst() == 0) {
                    reactivated++;
                }
                jdbc.update("UPDATE scm_repository SET clone_url = ?, active = 1 WHERE connection_id = ? "
                        + "AND project_key = ? AND slug = ?", repository.cloneUrl(), connection.id(),
                        repository.projectKey(), repository.slug());
            }
        }
        int maxPercent = settings.getInt(SettingKeys.SCM_MAX_DEACTIVATION_PERCENT);
        Purge purge = purgeUnlisted(connection, seen, indexedBefore, maxPercent);
        List<Long> gone = jdbc.query("SELECT id, project_key, slug FROM scm_repository WHERE connection_id = ? "
                        + "AND active = 1 ORDER BY id",
                        (rs, row) -> seen.contains(rs.getString("project_key") + "/" + rs.getString("slug"))
                                ? null : rs.getLong("id"),
                        connection.id())
                .stream().filter(Objects::nonNull).toList();
        if (gone.size() > 1 && (long) gone.size() * 100 > (long) activeBefore * maxPercent) {
            // a revoked permission or a mistyped project filter must not wipe the index (plan 4 follow-up)
            log.warn("Connection '{}': {} of {} active repositories are no longer listed; deactivation skipped "
                    + "(scm.max_deactivation_percent = {})", connection.name(), gone.size(), activeBefore, maxPercent);
            return new SyncResult(listed.size(), added, reactivated, 0, gone.size(), purge.purged(),
                    purge.skipped());
        }
        for (Long id : gone) {
            writer.remove(id);
            jdbc.update("UPDATE scm_repository SET active = 0 WHERE id = ?", id);
        }
        if (!gone.isEmpty()) {
            log.info("Connection '{}': deactivated {} repositories no longer listed", connection.name(), gone.size());
        }
        return new SyncResult(listed.size(), added, reactivated, gone.size(), 0, purge.purged(),
                purge.skipped());
    }

    /**
     * A repository left inactive with its index (its connection was repointed) that the host does not list is gone
     * from the new host: its index is deleted (spec §4.1), under the same guard as deactivation.
     */
    private Purge purgeUnlisted(ScmConnection connection, Set<String> seen, int indexedBefore, int maxPercent) {
        List<Long> stale = jdbc.query("SELECT id, project_key, slug FROM scm_repository WHERE connection_id = ? "
                        + "AND active = 0 AND last_indexed_commit IS NOT NULL ORDER BY id",
                        (rs, row) -> seen.contains(rs.getString("project_key") + "/" + rs.getString("slug"))
                                ? null : rs.getLong("id"),
                        connection.id())
                .stream().filter(Objects::nonNull).toList();
        if (stale.size() > 1 && (long) stale.size() * 100 > (long) indexedBefore * maxPercent) {
            log.warn("Connection '{}': {} of {} indexed repositories are no longer listed; their index is kept "
                    + "(scm.max_deactivation_percent = {})", connection.name(), stale.size(), indexedBefore,
                    maxPercent);
            return new Purge(0, stale.size());
        }
        stale.forEach(writer::remove);
        if (!stale.isEmpty()) {
            log.info("Connection '{}': deleted the index of {} inactive repositories no longer listed",
                    connection.name(), stale.size());
        }
        return new Purge(stale.size(), 0);
    }
}
