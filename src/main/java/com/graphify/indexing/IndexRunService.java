package com.graphify.indexing;

import com.graphify.common.exception.ConflictException;
import com.graphify.common.exception.InvalidRequestException;
import com.graphify.common.exception.NotFoundException;
import com.graphify.scm.UrlMasking;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Starts and cancels index runs (spec §10.5). A run executes on one background coordinator thread; the request
 * returns its id at once (202). The index lock guarantees a single run, so one coordinator thread is enough.
 */
@Service
public class IndexRunService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(IndexRunService.class);

    /**
     * How long shutdown waits for the interrupted coordinator to write the run's final status and release the lock
     * before the connection pool closes; a run still going after that is ended by startup recovery.
     */
    private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(10);

    private final JdbcTemplate jdbc;
    private final IndexLock lock;
    private final IndexRunRecorder runs;
    private final IndexRunExecutor executor;
    private final ExecutorService coordinator = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("index-coordinator").factory());

    public IndexRunService(JdbcTemplate jdbc, IndexLock lock, IndexRunRecorder runs, IndexRunExecutor executor) {
        this.jdbc = jdbc;
        this.lock = lock;
        this.runs = runs;
        this.executor = executor;
    }

    public long start(RunScope scope, Long scopeId, boolean force, RunTrigger trigger, String actor) {
        validate(scope, scopeId);
        long runId = runs.start(trigger, scope, scopeId, actor);
        String holder = IndexLock.runHolder(runId);
        try {
            if (!lock.tryAcquire(holder)) {
                String current = lock.holder().orElse(null);
                runs.discard(runId);
                throw new IndexRunConflictException(current);
            }
        } catch (IndexRunConflictException e) {
            throw e;
        } catch (RuntimeException e) { // never leave a RUNNING row behind
            abandon(runId, e);
            throw e;
        }
        try {
            coordinator.execute(() -> run(runId, scope, scopeId, force, holder));
        } catch (RejectedExecutionException e) {
            try {
                runs.finish(runId, RunStatus.FAILED, "The application is shutting down");
            } finally {
                lock.release(holder);
            }
            throw new ConflictException("The application is shutting down; index run " + runId + " was not started");
        }
        log.info("Index run {} started ({} {}, force={}) by {}", runId, scope, scopeId == null ? "" : scopeId, force,
                actor);
        return runId;
    }

    /** Removes the row of a run that never started; if that fails too, ends it FAILED so it does not stay RUNNING. */
    private void abandon(long runId, RuntimeException cause) {
        try {
            runs.discard(runId);
        } catch (RuntimeException discardFailure) {
            cause.addSuppressed(discardFailure);
            try {
                runs.finish(runId, RunStatus.FAILED, "The run could not be started");
            } catch (RuntimeException finishFailure) {
                cause.addSuppressed(finishFailure);
            }
        }
    }

    public void cancel(long runId) {
        RunStatus status = runs.status(runId)
                .orElseThrow(() -> new NotFoundException("No index run with id " + runId));
        if (status != RunStatus.RUNNING || !runs.requestCancel(runId)) {
            throw new ConflictException("Index run " + runId + " is already " + runs.status(runId).orElse(status));
        }
    }

    private void run(long runId, RunScope scope, Long scopeId, boolean force, String holder) {
        try {
            executor.execute(runId, scope, scopeId, force);
        } catch (Throwable e) { // the run must never stay RUNNING after its thread is gone
            boolean interrupted = Thread.interrupted(); // ojdbc fails on an interrupted thread
            String error = UrlMasking.mask(e.getClass().getSimpleName() + ": " + e.getMessage());
            log.error("Index run {} failed: {}", runId, error);
            try {
                runs.finish(runId, RunStatus.FAILED, error);
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        } finally {
            boolean interrupted = Thread.interrupted();
            try {
                lock.release(holder);
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void validate(RunScope scope, Long scopeId) {
        if (scope == null) {
            throw new InvalidRequestException("scope is required (ALL, CONNECTION or REPOSITORY)");
        }
        if (scope == RunScope.ALL) {
            if (scopeId != null) {
                throw new InvalidRequestException("id must be empty when scope is ALL");
            }
            return;
        }
        if (scopeId == null) {
            throw new InvalidRequestException("id is required when scope is " + scope);
        }
        if (scope == RunScope.CONNECTION) {
            List<Integer> enabled = jdbc.queryForList("SELECT enabled FROM scm_connection WHERE id = ?", Integer.class,
                    scopeId);
            if (enabled.isEmpty()) {
                throw new NotFoundException("No SCM connection with id " + scopeId);
            }
            if (enabled.getFirst() == 0) {
                throw new ConflictException("SCM connection " + scopeId + " is disabled");
            }
            return;
        }
        List<int[]> rows = jdbc.query("SELECT r.active, c.enabled FROM scm_repository r "
                + "JOIN scm_connection c ON c.id = r.connection_id WHERE r.id = ?",
                (rs, row) -> new int[] {rs.getInt("active"), rs.getInt("enabled")}, scopeId);
        if (rows.isEmpty()) {
            throw new NotFoundException("No repository with id " + scopeId);
        }
        if (rows.getFirst()[0] == 0) {
            throw new ConflictException("Repository " + scopeId + " is inactive");
        }
        if (rows.getFirst()[1] == 0) {
            throw new ConflictException("The SCM connection of repository " + scopeId + " is disabled");
        }
    }

    @Override
    public void destroy() {
        coordinator.shutdownNow();
        try {
            if (!coordinator.awaitTermination(SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("The index run coordinator did not stop within {}", SHUTDOWN_WAIT);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
