package com.graphify.indexing;

import com.graphify.common.util.Utf8;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** Writes index_run and index_run_repo (spec §4.1) and the per-repository in-progress markers. */
@Repository
public class IndexRunRecorder {

    /** Width of index_run.error and index_run_repo.error (V4, V5). */
    private static final int ERROR_BYTES = 4000;

    private static final String INTERRUPTED_REPOSITORY = "The application stopped while this repository was being indexed";

    /** index_run.error of an INTERRUPTED run, written by the executor and by recovery. */
    static final String INTERRUPTED_RUN = "The application stopped during this run";

    /** index_run.error of a run whose lock was taken over while its process was not running it. */
    static final String TAKEN_OVER_RUN =
            "The process running this run no longer held the index lock; the lock was taken over";

    /** index_run_repo.error of a repository that such a run was indexing. */
    static final String TAKEN_OVER_REPOSITORY = "The run that was indexing this repository lost the index lock";

    private final JdbcTemplate jdbc;

    public IndexRunRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long start(RunTrigger trigger, RunScope scope, Long scopeId, String startedBy) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("INSERT INTO index_run (trigger_type, scope, "
                    + "scope_id, status, started_by) VALUES (?, ?, ?, 'RUNNING', ?)", new String[] {"id"});
            statement.setString(1, trigger.name());
            statement.setString(2, scope.name());
            statement.setObject(3, scopeId);
            statement.setString(4, startedBy);
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    /** Removes a run that never started (the lock was taken); it has no repository rows. */
    public void discard(long runId) {
        jdbc.update("DELETE FROM index_run WHERE id = ? AND NOT EXISTS (SELECT 1 FROM index_run_repo WHERE run_id = ?)",
                runId, runId);
    }

    public void record(long runId, long repositoryId, RepoIndexOutcome outcome) {
        record(runId, repositoryId, outcome, null);
    }

    /**
     * {@code install} is null when the repository provided nothing in this run. artifact_install_error is a CLOB and
     * is not cut: it is a masked Maven tail, already bounded by index.maven_output_tail_lines.
     */
    public void record(long runId, long repositoryId, RepoIndexOutcome outcome, ArtifactInstallOutcome install) {
        jdbc.update("""
                INSERT INTO index_run_repo (run_id, repo_id, commit_sha, status, classpath_mode, error, symbol_count,
                                            usage_count, warning_count, duration_ms, artifact_install,
                                            artifact_install_error)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, runId, repositoryId, outcome.commit(), outcome.status().name(), outcome.classpathMode(),
                cut(outcome.error()), outcome.symbols(), outcome.usages(), outcome.warnings(), outcome.durationMillis(),
                install == null ? null : install.status().name(), install == null ? null : install.error());
    }

    /** A provider outside the run: listed as unchanged, with what its install did (spec §4). */
    public void recordInstall(long runId, long repositoryId, String commit, ArtifactInstallOutcome install) {
        record(runId, repositoryId, new RepoIndexOutcome(RepoIndexStatus.SKIPPED_UNCHANGED, commit, null, null, 0, 0,
                0, 0), install);
    }

    /** Records a repository whose indexing threw, and sets its last_status; {@code error} must already be masked. */
    public void recordFailure(long runId, long repositoryId, String error, long durationMillis) {
        recordFailure(runId, repositoryId, error, durationMillis, null);
    }

    public void recordFailure(long runId, long repositoryId, String error, long durationMillis,
            ArtifactInstallOutcome install) {
        record(runId, repositoryId, new RepoIndexOutcome(RepoIndexStatus.FAILED, null, null, error, 0, 0, 0,
                durationMillis), install);
        jdbc.update("UPDATE scm_repository SET last_status = ? WHERE id = ?", RepoIndexStatus.FAILED.name(),
                repositoryId);
    }

    public void markStarted(long runId, long repositoryId) {
        jdbc.update("UPDATE scm_repository SET indexing_run_id = ? WHERE id = ?", runId, repositoryId);
    }

    public void markFinished(long repositoryId) {
        jdbc.update("UPDATE scm_repository SET indexing_run_id = NULL WHERE id = ?", repositoryId);
    }

    /** Asks a running run to stop starting repositories; false when the run is not running. */
    public boolean requestCancel(long runId) {
        return jdbc.update("UPDATE index_run SET cancel_requested = 1 WHERE id = ? AND status = 'RUNNING'", runId) == 1;
    }

    public boolean isCancelRequested(long runId) {
        List<Integer> flags = jdbc.queryForList("SELECT cancel_requested FROM index_run WHERE id = ?", Integer.class,
                runId);
        return !flags.isEmpty() && flags.getFirst() == 1;
    }

    public Optional<RunStatus> status(long runId) {
        return jdbc.queryForList("SELECT status FROM index_run WHERE id = ?", String.class, runId).stream()
                .findFirst().map(RunStatus::valueOf);
    }

    /** Ends a running run; a run that has already ended keeps its first final status. */
    public void finish(long runId, RunStatus status, String error) {
        jdbc.update("UPDATE index_run SET status = ?, error = ?, finished_at = SYSTIMESTAMP "
                + "WHERE id = ? AND status = 'RUNNING'", status.name(), cut(error), runId);
    }

    /**
     * Spec §8 restart row: repositories that were mid-index get an INTERRUPTED outcome (their previous index is kept),
     * and every run still RUNNING becomes INTERRUPTED. Returns the number of runs interrupted.
     */
    public int recoverInterrupted() {
        return recover(null, INTERRUPTED_RUN, INTERRUPTED_REPOSITORY);
    }

    /**
     * The same as {@link #recoverInterrupted()} for one run whose process is gone while the lock still names it
     * ({@link IndexLock}), labelled as a lock takeover rather than a stop; false when that run was no longer RUNNING.
     */
    public boolean recoverRun(long runId) {
        return recover(runId, TAKEN_OVER_RUN, TAKEN_OVER_REPOSITORY) == 1;
    }

    /** {@code runId} null recovers every run. A repository that already has an outcome for its run gets no second. */
    private int recover(Long runId, String runError, String repositoryError) {
        String onlyRun = runId == null ? "" : " AND indexing_run_id = ?";
        Object[] runArgs = runId == null ? new Object[0] : new Object[] {runId};
        List<long[]> inFlight = jdbc.query("SELECT id, indexing_run_id FROM scm_repository "
                + "WHERE indexing_run_id IS NOT NULL" + onlyRun, (rs, row) -> new long[] {rs.getLong("id"),
                        rs.getLong("indexing_run_id")}, runArgs);
        for (long[] repository : inFlight) {
            int inserted = jdbc.update("""
                    INSERT INTO index_run_repo (run_id, repo_id, status, error)
                    SELECT ?, ?, ?, ? FROM dual
                     WHERE NOT EXISTS (SELECT 1 FROM index_run_repo WHERE run_id = ? AND repo_id = ?)
                    """, repository[1], repository[0], RepoIndexStatus.INTERRUPTED.name(), repositoryError,
                    repository[1], repository[0]);
            if (inserted == 1) {
                jdbc.update("UPDATE scm_repository SET last_status = ?, indexing_run_id = NULL WHERE id = ?",
                        RepoIndexStatus.INTERRUPTED.name(), repository[0]);
            } else {
                markFinished(repository[0]);
            }
        }
        Object[] finishArgs = runId == null ? new Object[] {runError} : new Object[] {runError, runId};
        return jdbc.update("UPDATE index_run SET status = 'INTERRUPTED', error = ?, finished_at = SYSTIMESTAMP "
                + "WHERE status = 'RUNNING'" + (runId == null ? "" : " AND id = ?"), finishArgs);
    }

    private static String cut(String error) {
        return error == null ? null : Utf8.truncateToBytes(error, ERROR_BYTES);
    }
}
