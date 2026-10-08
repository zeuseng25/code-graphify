package com.graphify.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.graphify.OracleIntegrationTest;
import com.graphify.common.exception.ConflictException;
import com.graphify.store.StoreFixtures;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Failure and lifecycle paths of the coordinator task, with a stub executor. */
class IndexRunServiceFailureTest extends OracleIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    IndexLock lock;

    @Autowired
    IndexRunRecorder runs;

    private IndexRunService service;

    private IndexRunService serviceRunning(Runnable body) {
        IndexRunExecutor stub = new IndexRunExecutor(null, null, null, null, null, null, null, null, null, null) {
            @Override
            public RunStatus execute(long runId, RunScope scope, Long scopeId, boolean force) {
                body.run();
                return RunStatus.SUCCESS;
            }
        };
        service = new IndexRunService(jdbc, lock, runs, stub);
        return service;
    }

    @BeforeEach
    void setUp() {
        StoreFixtures.cleanIndexTables(jdbc);
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.destroy();
        }
        await().atMost(TIMEOUT).until(() -> lock.holder().isEmpty());
    }

    private String error(long run) {
        return jdbc.queryForObject("SELECT error FROM index_run WHERE id = ?", String.class, run);
    }

    @Test
    void anExecutorFailureFinishesTheRunFailedWithAMaskedErrorAndReleasesTheLock() {
        long run = serviceRunning(() -> {
            throw new IllegalStateException("clone of https://u:pw@h/x failed");
        }).start(RunScope.ALL, null, false, RunTrigger.MANUAL, "t");

        await().atMost(TIMEOUT).until(() -> runs.status(run).orElseThrow() != RunStatus.RUNNING);
        await().atMost(TIMEOUT).until(() -> lock.holder().isEmpty());

        assertThat(runs.status(run)).contains(RunStatus.FAILED);
        assertThat(error(run)).contains("IllegalStateException").doesNotContain("pw");
    }

    @Test
    void aFailureOnAnInterruptedThreadStillFinishesTheRun() {
        long run = serviceRunning(() -> {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("boom");
        }).start(RunScope.ALL, null, false, RunTrigger.MANUAL, "t");

        await().atMost(TIMEOUT).until(() -> runs.status(run).orElseThrow() != RunStatus.RUNNING);
        await().atMost(TIMEOUT).until(() -> lock.holder().isEmpty());

        assertThat(runs.status(run)).contains(RunStatus.FAILED);
    }

    @Test
    void startAfterShutdownFailsTheRunAndReleasesTheLock() {
        IndexRunService s = serviceRunning(() -> { });
        s.destroy();

        assertThatThrownBy(() -> s.start(RunScope.ALL, null, false, RunTrigger.MANUAL, "t"))
                .isInstanceOf(ConflictException.class).hasMessageContaining("shutting down");

        assertThat(lock.holder()).isEmpty();
        assertThat(jdbc.queryForList("SELECT status FROM index_run", String.class)).containsExactly("FAILED");
    }
}
