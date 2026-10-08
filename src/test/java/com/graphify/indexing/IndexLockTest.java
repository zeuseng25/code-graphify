package com.graphify.indexing;

import java.time.OffsetDateTime;
import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.store.StoreFixtures;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class IndexLockTest extends OracleIntegrationTest {

    @Autowired
    IndexLock lock;

    @Autowired
    IndexRunRecorder runs;

    @BeforeEach
    @AfterEach
    void free() {
        lock.forceRelease();
    }

    /** A holder written by another (stopped) process, never taken through tryAcquire in this JVM. */
    private void holdOutsideThisProcess(String holder) {
        jdbc.update("UPDATE index_lock SET holder = ?, acquired_at = SYSTIMESTAMP WHERE lock_name = 'INDEX'", holder);
    }

    @Test
    void onlyOneHolderAtATimeAndOnlyTheHolderReleases() {
        assertThat(lock.tryAcquire("run:1")).isTrue();
        assertThat(lock.tryAcquire(IndexLock.CLEANUP_HOLDER)).isFalse();
        assertThat(lock.holder()).contains("run:1");

        lock.release(IndexLock.CLEANUP_HOLDER);
        assertThat(lock.holder()).contains("run:1");

        lock.release("run:1");
        assertThat(lock.holder()).isEmpty();
        assertThat(lock.tryAcquire(IndexLock.CLEANUP_HOLDER)).isTrue();
    }

    @Test
    void concurrentAcquirersNeverBothWin() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> attempts = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String holder = IndexLock.runHolder(i);
                attempts.add(pool.submit(() -> lock.tryAcquire(holder)));
            }
            int winners = 0;
            for (Future<Boolean> attempt : attempts) {
                if (attempt.get()) {
                    winners++;
                }
            }
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void runHoldersCarryTheRunId() {
        assertThat(IndexLock.runIdOf(IndexLock.runHolder(42))).hasValue(42);
        assertThat(IndexLock.runIdOf(IndexLock.CLEANUP_HOLDER)).isEmpty();
        assertThat(IndexLock.runIdOf(IndexLock.REPOINT_HOLDER)).isEmpty();
        assertThat(IndexLock.runIdOf("run:abc")).isEmpty();
        assertThat(IndexLock.runIdOf(null)).isEmpty();
    }

    @Test
    void aHolderTakenInThisProcessIsStillAConflict() {
        assertThat(lock.tryAcquire(IndexLock.runHolder(3))).isTrue();

        assertThat(lock.tryAcquire(IndexLock.runHolder(4))).isFalse();
        assertThat(lock.tryAcquire(IndexLock.runHolder(3))).isFalse();

        assertThat(lock.holder()).contains(IndexLock.runHolder(3));
    }

    @Test
    void aStaleRunHolderIsTakenOverAndItsRunInterrupted() {
        StoreFixtures.cleanIndexTables(jdbc);
        long repoId = StoreFixtures.newRepository(jdbc, "stale");
        long stale = runs.start(RunTrigger.MANUAL, RunScope.ALL, null, "tester");
        runs.markStarted(stale, repoId);
        holdOutsideThisProcess(IndexLock.runHolder(stale));

        assertThat(lock.tryAcquire("run:X")).isTrue();

        assertThat(lock.holder()).contains("run:X");
        assertThat(runs.status(stale)).contains(RunStatus.INTERRUPTED);
        assertThat(jdbc.queryForObject("SELECT status FROM index_run_repo WHERE run_id = ? AND repo_id = ?",
                String.class, stale, repoId)).isEqualTo("INTERRUPTED");
        assertThat(jdbc.queryForObject("SELECT indexing_run_id FROM scm_repository WHERE id = ?", Long.class, repoId))
                .isNull();
        assertThat(lock.tryAcquire(IndexLock.CLEANUP_HOLDER)).isFalse();
    }

    @Test
    void aStaleCleanupHolderIsTakenOver() {
        holdOutsideThisProcess(IndexLock.CLEANUP_HOLDER);

        assertThat(lock.tryAcquire(IndexLock.runHolder(9))).isTrue();

        assertThat(lock.holder()).contains(IndexLock.runHolder(9));
    }

    @Test
    void releaseForgetsTheHolderEvenWhenTheLockRowWasChangedElsewhere() {
        assertThat(lock.tryAcquire(IndexLock.CLEANUP_HOLDER)).isTrue();
        holdOutsideThisProcess(IndexLock.runHolder(8));
        lock.release(IndexLock.CLEANUP_HOLDER);

        assertThat(lock.tryAcquire(IndexLock.CLEANUP_HOLDER)).isTrue();
    }

    @Test
    void aTakeoverIsFencedOnTheAcquisitionTime() {
        jdbc.update("UPDATE index_lock SET holder = 'cleanup', acquired_at = SYSTIMESTAMP - INTERVAL '1' HOUR "
                + "WHERE lock_name = 'INDEX'");
        OffsetDateTime seen = jdbc.queryForObject("SELECT acquired_at FROM index_lock WHERE lock_name = 'INDEX'",
                OffsetDateTime.class);
        // a live cleanup re-took the lock after the stale read: same holder name, newer acquisition time
        jdbc.update("UPDATE index_lock SET acquired_at = SYSTIMESTAMP WHERE lock_name = 'INDEX'");

        assertThat(lock.takeOver("run:9", IndexLock.CLEANUP_HOLDER, seen)).isFalse();
        assertThat(lock.holder()).contains(IndexLock.CLEANUP_HOLDER);

        OffsetDateTime current = jdbc.queryForObject("SELECT acquired_at FROM index_lock WHERE lock_name = 'INDEX'",
                OffsetDateTime.class);
        assertThat(lock.takeOver("run:9", IndexLock.CLEANUP_HOLDER, current)).isTrue();
        assertThat(lock.holder()).contains("run:9");
        lock.release("run:9");
    }
}
