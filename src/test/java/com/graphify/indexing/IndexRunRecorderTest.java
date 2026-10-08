package com.graphify.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.store.StoreFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class IndexRunRecorderTest extends OracleIntegrationTest {

    @Autowired
    IndexRunRecorder runs;

    private long repoId;

    @BeforeEach
    void setUp() {
        StoreFixtures.cleanIndexTables(jdbc);
        repoId = StoreFixtures.newRepository(jdbc, "alpha");
    }

    private long start() {
        return runs.start(RunTrigger.MANUAL, RunScope.ALL, null, "tester");
    }

    @Test
    void cancellationIsOnlyRequestedForRunningRuns() {
        long run = start();

        assertThat(runs.status(run)).contains(RunStatus.RUNNING);
        assertThat(runs.isCancelRequested(run)).isFalse();
        assertThat(runs.requestCancel(run)).isTrue();
        assertThat(runs.isCancelRequested(run)).isTrue();

        runs.finish(run, RunStatus.CANCELLED, null);

        assertThat(runs.requestCancel(run)).isFalse();
        assertThat(runs.status(-1)).isEmpty();
        assertThat(runs.isCancelRequested(-1)).isFalse();
    }

    @Test
    void theFirstFinalStatusWinsAndTheErrorIsCut() {
        long run = start();

        runs.finish(run, RunStatus.FAILED, "x".repeat(5000));
        runs.finish(run, RunStatus.SUCCESS, null);

        assertThat(runs.status(run)).contains(RunStatus.FAILED);
        assertThat(jdbc.queryForObject("SELECT LENGTHB(error) FROM index_run WHERE id = ?", Integer.class, run))
                .isEqualTo(4000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM index_run WHERE id = ? AND finished_at IS NOT NULL",
                Integer.class, run)).isEqualTo(1);
    }

    @Test
    void aFailureIsRecordedAndSetsTheRepositoryStatus() {
        long run = start();

        runs.recordFailure(run, repoId, "StackOverflowError: null", 12);

        assertThat(jdbc.queryForObject("SELECT status || ':' || error || ':' || duration_ms FROM index_run_repo "
                + "WHERE run_id = ?", String.class, run)).isEqualTo("FAILED:StackOverflowError: null:12");
        assertThat(jdbc.queryForObject("SELECT last_status FROM scm_repository WHERE id = ?", String.class, repoId))
                .isEqualTo("FAILED");
    }

    @Test
    void recoveryInterruptsRunningRunsAndTheRepositoriesTheyWereIndexing() {
        long running = start();
        long done = start();
        runs.finish(done, RunStatus.SUCCESS, null);
        runs.markStarted(running, repoId);
        assertThat(jdbc.queryForObject("SELECT indexing_run_id FROM scm_repository WHERE id = ?", Long.class, repoId))
                .isEqualTo(running);

        assertThat(runs.recoverInterrupted()).isEqualTo(1);

        assertThat(runs.status(running)).contains(RunStatus.INTERRUPTED);
        assertThat(runs.status(done)).contains(RunStatus.SUCCESS);
        assertThat(jdbc.queryForObject("SELECT status FROM index_run_repo WHERE run_id = ?", String.class, running))
                .isEqualTo("INTERRUPTED");
        assertThat(jdbc.queryForObject("SELECT last_status FROM scm_repository WHERE id = ?", String.class, repoId))
                .isEqualTo("INTERRUPTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scm_repository WHERE indexing_run_id IS NOT NULL",
                Integer.class)).isZero();
    }

    @Test
    void recoveringOneRunLeavesOtherRunningRunsAlone() {
        long other = StoreFixtures.newRepository(jdbc, "beta");
        long stale = start();
        long live = start();
        runs.markStarted(stale, repoId);
        runs.markStarted(live, other);

        assertThat(runs.recoverRun(stale)).isTrue();

        assertThat(runs.status(stale)).contains(RunStatus.INTERRUPTED);
        assertThat(jdbc.queryForObject("SELECT error FROM index_run WHERE id = ?", String.class, stale))
                .isEqualTo(IndexRunRecorder.TAKEN_OVER_RUN);
        assertThat(jdbc.queryForObject("SELECT status FROM index_run_repo WHERE run_id = ?", String.class, stale))
                .isEqualTo("INTERRUPTED");
        assertThat(runs.status(live)).contains(RunStatus.RUNNING);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM index_run_repo WHERE run_id = ?", Integer.class, live))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT indexing_run_id FROM scm_repository WHERE id = ?", Long.class, other))
                .isEqualTo(live);
        assertThat(runs.recoverRun(stale)).isFalse();
    }

    @Test
    void recoveryNeverAddsASecondRowForARecordedRepository() {
        long run = start();
        runs.markStarted(run, repoId);
        runs.recordFailure(run, repoId, "boom", 1);

        runs.recoverInterrupted();

        assertThat(jdbc.queryForList("SELECT status FROM index_run_repo WHERE run_id = ?", String.class, run))
                .containsExactly("FAILED");
        assertThat(jdbc.queryForObject("SELECT last_status FROM scm_repository WHERE id = ?", String.class, repoId))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT indexing_run_id FROM scm_repository WHERE id = ?", Long.class, repoId))
                .isNull();
    }

    @Test
    void markFinishedClearsTheMarkerAndDiscardRemovesAnUnstartedRun() {
        long run = start();
        runs.markStarted(run, repoId);
        runs.markFinished(repoId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scm_repository WHERE indexing_run_id IS NOT NULL",
                Integer.class)).isZero();

        runs.discard(run);

        assertThat(runs.status(run)).isEmpty();
    }

    @Test
    void aTakenOverRunIsLabelledAsSuch() {
        long run = start();
        runs.markStarted(run, repoId);

        assertThat(runs.recoverRun(run)).isTrue();

        assertThat(jdbc.queryForObject("SELECT error FROM index_run WHERE id = ?", String.class, run))
                .isEqualTo(IndexRunRecorder.TAKEN_OVER_RUN);
        assertThat(jdbc.queryForObject("SELECT error FROM index_run_repo WHERE run_id = ?", String.class, run))
                .isEqualTo(IndexRunRecorder.TAKEN_OVER_REPOSITORY);
    }
}
