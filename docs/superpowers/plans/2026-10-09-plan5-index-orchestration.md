# Plan 5: Index Run Orchestration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the plan-4 one-repository pipeline into a complete index service:
- runs over all connections, one connection or one repository, in parallel, guarded by a single index lock;
- each run is started from the REST API or by the nightly cron, can be cancelled, and is recovered after a crash;
- the run history is visible through the API;
- orphan symbols are cleaned up under the same lock;
- impact results gain version warnings and a read-consistent snapshot.

**Architecture:**
- **Lock.** `IndexLock` is one row in `index_lock`. A single conditional UPDATE takes it, so only one holder can ever win. The holder is `run:<id>` or `cleanup`.
- **Starting a run.** `IndexRunService` validates the request, inserts the `INDEX_RUN`, takes the lock and hands the run to a single background coordinator thread. If the lock is busy, the request gets a 409 that names the run in progress.
- **Running.** `IndexRunExecutor` first syncs each connection in scope (`RepositorySync`), recording `AUTH_FAILED`/`FAILED`/`DEACTIVATION_SKIPPED` on the connection. It then indexes every active repository on `index.parallelism` worker threads through `RepositoryIndexer`.
  - Every repository outcome is recorded, even a `Throwable`.
  - An in-progress marker (`scm_repository.indexing_run_id`) records which repositories a crashed run was working on.
- **Recovery.** `IndexRunRecovery` runs at startup. It marks abandoned runs and their in-flight repositories `INTERRUPTED` and frees the lock.
- **Scheduling.** `IndexScheduler` schedules `index.cron` and `cleanup.orphan_symbols_cron` and reschedules either one when the setting changes. `OrphanCleanupJob` deletes orphan symbols in `cleanup.batch_size` chunks under the lock.
- **API.** `IndexRunController` serves `POST/GET /index/runs`, `GET /index/runs/{id}`, `POST /index/runs/{id}/cancel` and `GET /repositories/{id}/runs`.
- **Impact.** `ImpactService` runs inside `ReadSnapshot` (Oracle `SET TRANSACTION READ ONLY`) and fills `versionWarnings` from `MODULE_DEPENDENCY`.

**Tech Stack:**
- Java 25, Spring Boot 4.1.1 (Spring MVC, `ThreadPoolTaskScheduler`, `CronTrigger`, `TransactionTemplate`), HikariCP 7
- Oracle with Flyway, JdbcTemplate
- Testcontainers, JUnit 5, AssertJ, Awaitility (on the test classpath via `spring-boot-starter-test`)

**Spec:** `docs/superpowers/specs/2026-10-05-impact-analyzer-design.md`, covering:
- §3.2 "Paralellik"
- §4.1 `INDEX_RUN`, `INDEX_RUN_REPO`; §4.4 orphan cleanup; §4.5 `INDEX_LOCK`
- §5.6 version warnings
- §6.4 "Cron değişince zamanlayıcı yeniden kurulur; paralellik bir sonraki taramada geçerli olur"
- §8 rows for SCM listing, concurrency and restart
- §10.5 runs endpoints and `/repositories?status=`
- §7.5 USER/ADMIN matrix, enforced in plan 6

Carried items:
- `docs/superpowers/plans/2026-10-08-plan4-followups.md`, section "Carry into plan 5"
- `2026-10-07-plan3-followups.md`: version warnings, read-only snapshot
- `2026-10-06-plan2-followups.md`: Hikari sizing, chunked cleanup under the lock, a `SettingChangedEvent` listener must never throw

**Decided not to do (ruling):** denormalized `usage_count`/`repo_count` (plan-3 follow-up).
- The search orders a page of matches that has already been filtered by name. Both correlated counts use the leading column of `ix_usage_bfs`.
- Keeping stored counts correct under parallel writers would add row-lock contention on popular symbols for no measured gain.
- Revisit if search latency is measured as a problem.

## Plan series

| Plan | Scope | Status |
|---|---|---|
| 1–4 | Indexer, persistence, search/impact, acquisition | merged |
| **5** | **Index run orchestration** (this plan) | — |
| 6 | Authentication (LDAP, local admin, roles), admin APIs, role checks on the endpoints added here | next |
| 7 | Repo graph view | later |

## Global Constraints

- **Environment.**
  - JDK 25: every command runs with `export JAVA_HOME=/opt/homebrew/opt/openjdk@25`; use `./mvnw`.
  - Docker must be running. `mvn` 3.9.x and `git` must be on the PATH.
- **No new dependencies.** Every version comes from the Boot 4.1.1 BOM or the existing `pom.xml`.
- **"Kodda sabit değer yok" (no hardcoded values in code).**
  - Operational values come from `AppSettings`: `index.cron`, `index.parallelism`, `cleanup.orphan_symbols_cron`, `cleanup.batch_size`, `scm.max_deactivation_percent`, `store.connection_reserve`.
  - The three new keys are seeded only in `V5__index_orchestration.sql`.
  - Fixed protocol facts are named constants with a comment: the lock row name, the lock holder prefixes, the actor names `scheduler` and `anonymous`, and the thread counts of the coordinator and scheduler.
  - `app.scheduling.enabled` is a deployment switch, not an operational value. It defaults to on and only tests turn it off.
- **Single application instance.** Startup recovery releases whatever the lock holds. Running two instances against one schema is out of scope; document this on `IndexLock`.
- **Index lock.**
  - At most one index run or orphan cleanup at a time (spec §8).
  - A second request gets `409 Conflict` as a `ProblemDetail` with a `runId` property when the holder is a run.
  - The lock is released in `finally` by whoever took it.
- **Run statuses** (`index_run.status`): `RUNNING`, `SUCCESS`, `FAILED`, `CANCELLED`, `INTERRUPTED`. A run ends:
  - `CANCELLED` if cancellation was requested;
  - otherwise `FAILED` if every connection in scope failed to sync, or the coordinator itself failed;
  - otherwise `SUCCESS`. Per-repository failures live in `INDEX_RUN_REPO` and do not fail the run.
  - `finish` only changes a `RUNNING` run, so the first final status wins.
- **Repository outcomes.** Every repository the executor starts gets exactly one `INDEX_RUN_REPO` row and has its in-progress marker cleared, even if indexing throws an `Error`. A repository that was never started because of cancellation gets no row.
- **Cancellation.** Repositories already being indexed finish. No new repository starts after cancel is requested.
- **Credentials.** No secret ever appears in logs, `index_run.error`, `scm_connection.last_sync_error`, `INDEX_RUN_REPO.error` or API responses. Text that may contain a URL goes through `UrlMasking.mask`. Error columns are cut to 4000 bytes with `Utf8.truncateToBytes`.
- **Actors.** Until plan 6 adds authentication, a manual run's `started_by` is the constant `anonymous`. A scheduled run's is `scheduler`.
- **Schema.** New schema only in `V5__index_orchestration.sql`. V1–V4 are merged and never edited. Text columns are `VARCHAR2(n BYTE)` and timestamps are `TIMESTAMP WITH TIME ZONE`.
- **Feature packages.** Orchestration lives in `com.graphify.indexing`. SCM changes go in `com.graphify.scm`, pool sizing and the snapshot in `com.graphify.store`, version warnings in `com.graphify.impact`, and the 409 type in `com.graphify.common.exception`.
- **Logging.** Use SLF4J (`LoggerFactory.getLogger(X.class)`). Log masked messages, never exception objects whose message may contain a URL.
- **Tests.**
  - Oracle-backed tests extend `com.graphify.OracleIntegrationTest`.
  - Tests that take the lock release it in `@AfterEach`.
  - Tests that change settings use `testsupport.SettingsOverride` and restore in `@AfterEach`.
  - Tests that start background runs wait until the lock is free before finishing.
- **Commits** end with the Co-Authored-By trailer the committing agent's harness provides.

## Review Focus

1. **Two run requests at the same moment** (a double-click, or the scheduler firing while an admin starts a run). Exactly one runs. The other gets 409 naming the running run, and no stray `INDEX_RUN` row is left behind. Tests: Task 1 `IndexLockTest.concurrentAcquirersNeverBothWin`; Task 5 `IndexRunServiceTest.aSecondRunIsAConflictNamingTheRunInProgress`.
2. **The process is killed in the middle of a run.** On the next start the run is `INTERRUPTED`, the repositories it was indexing get `INTERRUPTED` rows, the lock is free, and a new run can start. Tests: Task 1 `IndexRunRecorderTest.recoveryInterruptsRunningRunsAndTheRepositoriesTheyWereIndexing`; Task 5 `IndexRunServiceTest.startupRecoveryInterruptsAbandonedRunsAndFreesTheLock`.
3. **A revoked token or a mistyped project filter shrinks a listing.** No mass deactivation happens. The connection shows `AUTH_FAILED` or `DEACTIVATION_SKIPPED`, and other connections still run. Tests: Task 2 `RepositorySyncTest.aMassDisappearanceIsNotDeactivated`; Task 4 `IndexRunExecutorTest.rejectedCredentialsFailTheConnectionAndTheRun`.
4. **An admin changes `index.cron` at runtime.** The new schedule applies without a restart. A failure while rescheduling never fails or rolls back the settings update. Test: Task 6 `IndexSchedulerTest`.
5. **One repository crashes the indexer with an `Error`** (a `StackOverflowError` on deeply nested code). It is recorded `FAILED`, its marker is cleared, and the other repositories continue. Test: Task 4 `IndexRunExecutorTest.anyThrowableIsRecordedAsAFailedRepository`.

---

## File Structure

| File | Responsibility |
|---|---|
| `db/migration/V5__index_orchestration.sql` | `index_lock`; `index_run.cancel_requested`/`error`; `scm_repository.indexing_run_id`; `scm_connection.last_sync_*`; 3 settings |
| `settings/SettingKeys.java` (modify) | New keys |
| `common/exception/ConflictException.java`, `ApiExceptionHandler.java` (modify) | 409 ProblemDetail with properties |
| `indexing/RunScope.java`, `RunTrigger.java`, `RunStatus.java` | Run enums |
| `indexing/IndexLock.java` | The single lock row |
| `indexing/IndexRunRecorder.java` (modify) | Run lifecycle, cancel flag, in-progress markers, recovery |
| `scm/ConnectionSyncStatus.java`, `scm/ScmConnections.java` (modify) | Connection sync status |
| `scm/RepositorySync.java`, `scm/BitbucketDataCenterClient.java`, `workspace/GitWorkspace.java` (modify) | Mass-deactivation guard, logging, closed HttpClient, safe path segments |
| `store/ConnectionPoolSizer.java` | Grow Hikari to workers + reserve |
| `indexing/IndexRunExecutor.java` | One run: sync, parallel index, finish |
| `indexing/IndexRunService.java`, `IndexRunConflictException.java`, `IndexRunRecovery.java` | Start/cancel, background coordinator, startup recovery |
| `store/SymbolCleanup.java` (modify), `indexing/OrphanCleanupJob.java`, `indexing/IndexScheduler.java` | Chunked cleanup under the lock; cron scheduling |
| `indexing/IndexRunQueries.java`, `IndexRunSummary.java`, `IndexRunRepoView.java`, `IndexRunView.java`, `IndexRunController.java`; `repository/RepositoryQueries.java`, `RepositoryController.java` (modify) | REST API |
| `store/ReadSnapshot.java`, `impact/VersionWarnings.java`, `impact/ImpactResult.java`, `impact/ImpactService.java` (modify) | Read-only snapshot, version warnings |
| test `testsupport/SettingsOverride.java`, `testsupport/ShopScm.java` | Shared test setup |

Main paths are under `src/main/java/com/graphify/` unless they start with `db/`, which is `src/main/resources/db/migration/`.

---

### Task 1: Schema V5, the index lock, run lifecycle records and 409 errors

**Files:**
- Create: `src/main/resources/db/migration/V5__index_orchestration.sql`
- Create: `src/main/java/com/graphify/indexing/RunScope.java`, `RunTrigger.java`, `RunStatus.java`, `IndexLock.java`
- Create: `src/main/java/com/graphify/common/exception/ConflictException.java`
- Modify: `src/main/java/com/graphify/indexing/IndexRunRecorder.java`, `src/main/java/com/graphify/settings/SettingKeys.java`, `src/main/java/com/graphify/common/exception/ApiExceptionHandler.java`
- Modify: `src/test/java/com/graphify/store/StoreFixtures.java`, `src/test/java/com/graphify/indexing/RepositoryIndexerTest.java` (only the `runs.start(...)` call)
- Test: `src/test/java/com/graphify/indexing/IndexLockTest.java`, `IndexRunRecorderTest.java`; `src/test/java/com/graphify/store/SchemaMigrationTest.java` (add a test)

**Interfaces:**
- **Consumes:** `IndexRunRecorder` (plan 4) with `start(String, String, Long, String)`, `record(long, long, RepoIndexOutcome)` and `finish(long, String)`; `RepoIndexOutcome`, `RepoIndexStatus`; `Utf8.truncateToBytes`.
- **Produces (types and settings):**
  - `public enum RunScope { ALL, CONNECTION, REPOSITORY }`.
  - `public enum RunTrigger { SCHEDULED, MANUAL }`.
  - `public enum RunStatus { RUNNING, SUCCESS, FAILED, CANCELLED, INTERRUPTED }`.
  - `SettingKeys.SCM_MAX_DEACTIVATION_PERCENT`, `CLEANUP_BATCH_SIZE`, `STORE_CONNECTION_RESERVE`.
- **Produces (`IndexLock`, a `@Repository`):**
  - `boolean tryAcquire(String holder)`, `Optional<String> holder()`, `void release(String holder)`, `void forceRelease()`.
  - `static String runHolder(long runId)`, `static OptionalLong runIdOf(String holder)`.
  - `static final String CLEANUP_HOLDER = "cleanup"`.
- **Produces (`IndexRunRecorder`, changed):**
  - `long start(RunTrigger, RunScope, Long scopeId, String startedBy)`.
  - `void discard(long runId)` and `void record(...)` (unchanged).
  - `void recordFailure(long runId, long repositoryId, String error, long durationMillis)`.
  - `void markStarted(long runId, long repositoryId)` and `void markFinished(long repositoryId)`.
  - `boolean requestCancel(long runId)` and `boolean isCancelRequested(long runId)`.
  - `Optional<RunStatus> status(long runId)`.
  - `void finish(long runId, RunStatus status, String error)` replaces `finish(long, String)`.
  - `int recoverInterrupted()`.
- **Produces (409 errors):** `public class ConflictException extends RuntimeException` with `ConflictException(String)`, `ConflictException(String, Map<String, Object> properties)` and `Map<String, Object> properties()`. `ApiExceptionHandler` maps it to 409 with title `Conflict` and every property set on the `ProblemDetail`.

- [ ] **Step 1: Write the failing tests**

Append to `src/test/java/com/graphify/store/SchemaMigrationTest.java`, inside the class:

```java
    @Test
    void createsTheOrchestrationSchema() {
        assertThat(jdbc.queryForList("SELECT LOWER(table_name) FROM user_tables", String.class)).contains("index_lock");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM index_lock WHERE lock_name = 'INDEX'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForList("""
                SELECT LOWER(table_name || '.' || column_name) FROM user_tab_columns
                 WHERE table_name IN ('INDEX_RUN', 'SCM_REPOSITORY', 'SCM_CONNECTION')
                """, String.class)).contains("index_run.cancel_requested", "index_run.error",
                "scm_repository.indexing_run_id", "scm_connection.last_sync_status", "scm_connection.last_sync_at",
                "scm_connection.last_sync_error");
        assertThat(jdbc.queryForList("SELECT setting_key FROM app_setting", String.class))
                .contains("scm.max_deactivation_percent", "cleanup.batch_size", "store.connection_reserve");
    }
```

`src/test/java/com/graphify/indexing/IndexLockTest.java`:

```java
package com.graphify.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
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

    @BeforeEach
    @AfterEach
    void free() {
        lock.forceRelease();
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
        assertThat(IndexLock.runIdOf("run:abc")).isEmpty();
        assertThat(IndexLock.runIdOf(null)).isEmpty();
    }
}
```

`src/test/java/com/graphify/indexing/IndexRunRecorderTest.java`:

```java
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
    void markFinishedClearsTheMarkerAndDiscardRemovesAnUnstartedRun() {
        long run = start();
        runs.markStarted(run, repoId);
        runs.markFinished(repoId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scm_repository WHERE indexing_run_id IS NOT NULL",
                Integer.class)).isZero();

        runs.discard(run);

        assertThat(runs.status(run)).isEmpty();
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -Dtest='SchemaMigrationTest,IndexLockTest,IndexRunRecorderTest'`
Expected: BUILD FAILURE: `cannot find symbol` for `IndexLock`, `RunTrigger`, `RunScope` and `RunStatus`.

- [ ] **Step 3: Write the V5 migration**

`src/main/resources/db/migration/V5__index_orchestration.sql` (UTF-8):

```sql
-- Plan 5: the index lock, run cancellation and errors, in-progress markers, connection sync status and
-- orchestration settings.

CREATE TABLE index_lock (
    lock_name   VARCHAR2(30 BYTE) NOT NULL,
    holder      VARCHAR2(100 BYTE),
    acquired_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_index_lock PRIMARY KEY (lock_name)
);
INSERT INTO index_lock (lock_name) VALUES ('INDEX');

ALTER TABLE index_run ADD (
    cancel_requested NUMBER(1) DEFAULT 0 NOT NULL,
    error            VARCHAR2(4000 BYTE)
);
ALTER TABLE index_run ADD CONSTRAINT ck_index_run_cancel CHECK (cancel_requested IN (0, 1));

ALTER TABLE scm_repository ADD (indexing_run_id NUMBER(19));
ALTER TABLE scm_repository ADD CONSTRAINT fk_scm_repository_indexing_run
    FOREIGN KEY (indexing_run_id) REFERENCES index_run (id);

ALTER TABLE scm_connection ADD (
    last_sync_status VARCHAR2(30 BYTE),
    last_sync_at     TIMESTAMP WITH TIME ZONE,
    last_sync_error  VARCHAR2(4000 BYTE)
);
ALTER TABLE scm_connection ADD CONSTRAINT ck_scm_connection_sync CHECK (last_sync_status IS NULL
    OR last_sync_status IN ('SUCCESS', 'AUTH_FAILED', 'FAILED', 'DEACTIVATION_SKIPPED'));

INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('scm.max_deactivation_percent', '50', 'INT',
     'Bir senkronda pasife alınabilecek aktif repo oranı (%); aşılırsa pasife alma atlanır, 100 = sınırsız', 0, 100);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('cleanup.batch_size', '10000', 'INT', 'Yetim sembol temizliğinde bir DELETE ile silinen en fazla satır', 1, 1000000);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('store.connection_reserve', '5', 'INT', 'Tarama işçileri dışında API için boş tutulan veritabanı bağlantısı', 1, 100);
```

- [ ] **Step 4: Add the setting keys, run enums and the lock**

Add to `src/main/java/com/graphify/settings/SettingKeys.java`:

```java
    public static final String SCM_MAX_DEACTIVATION_PERCENT = "scm.max_deactivation_percent";
    public static final String CLEANUP_BATCH_SIZE = "cleanup.batch_size";
    public static final String STORE_CONNECTION_RESERVE = "store.connection_reserve";
```

`src/main/java/com/graphify/indexing/RunScope.java`:

```java
package com.graphify.indexing;

/** What an index run covers (spec §10.5). */
public enum RunScope {
    ALL, CONNECTION, REPOSITORY
}
```

`src/main/java/com/graphify/indexing/RunTrigger.java`:

```java
package com.graphify.indexing;

public enum RunTrigger {
    SCHEDULED, MANUAL
}
```

`src/main/java/com/graphify/indexing/RunStatus.java`:

```java
package com.graphify.indexing;

/** Status of a whole index run; per-repository outcomes are {@link RepoIndexStatus}. */
public enum RunStatus {
    RUNNING, SUCCESS, FAILED, CANCELLED, INTERRUPTED
}
```

`src/main/java/com/graphify/indexing/IndexLock.java`:

```java
package com.graphify.indexing;

import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The single index lock of spec §8 (one index run at a time): one row in index_lock, held by an index run
 * ({@code run:<id>}) or by the orphan-symbol cleanup ({@code cleanup}). Taking it is one conditional UPDATE, so two
 * callers can never both succeed. Assumes one application instance: startup releases whatever a crashed process held
 * ({@link IndexRunRecovery}).
 */
@Repository
public class IndexLock {

    /** Holder name of the orphan-symbol cleanup job. */
    public static final String CLEANUP_HOLDER = "cleanup";

    /** The only row, seeded in V5__index_orchestration.sql. */
    private static final String LOCK_NAME = "INDEX";

    /** Prefix of a run holder; the run id follows. */
    private static final String RUN_PREFIX = "run:";

    private final JdbcTemplate jdbc;

    public IndexLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static String runHolder(long runId) {
        return RUN_PREFIX + runId;
    }

    /** The run id when {@code holder} is an index run. */
    public static OptionalLong runIdOf(String holder) {
        if (holder == null || !holder.startsWith(RUN_PREFIX)) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(holder.substring(RUN_PREFIX.length())));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    public boolean tryAcquire(String holder) {
        return jdbc.update("UPDATE index_lock SET holder = ?, acquired_at = SYSTIMESTAMP "
                + "WHERE lock_name = ? AND holder IS NULL", holder, LOCK_NAME) == 1;
    }

    public Optional<String> holder() {
        return Optional.ofNullable(jdbc.queryForObject("SELECT holder FROM index_lock WHERE lock_name = ?",
                String.class, LOCK_NAME));
    }

    /** Releases the lock if {@code holder} has it; otherwise a no-op, so a late release never frees someone else's lock. */
    public void release(String holder) {
        jdbc.update("UPDATE index_lock SET holder = NULL, acquired_at = NULL WHERE lock_name = ? AND holder = ?",
                LOCK_NAME, holder);
    }

    /** Startup only: whatever held the lock died with the previous process. */
    public void forceRelease() {
        jdbc.update("UPDATE index_lock SET holder = NULL, acquired_at = NULL WHERE lock_name = ?", LOCK_NAME);
    }
}
```

- [ ] **Step 5: Extend the run recorder**

Replace `src/main/java/com/graphify/indexing/IndexRunRecorder.java` with:

```java
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
    private static final String INTERRUPTED_RUN = "The application stopped during this run";

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
        jdbc.update("""
                INSERT INTO index_run_repo (run_id, repo_id, commit_sha, status, classpath_mode, error, symbol_count,
                                            usage_count, warning_count, duration_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, runId, repositoryId, outcome.commit(), outcome.status().name(), outcome.classpathMode(),
                cut(outcome.error()), outcome.symbols(), outcome.usages(), outcome.warnings(), outcome.durationMillis());
    }

    /** Records a repository whose indexing threw, and sets its last_status; {@code error} must already be masked. */
    public void recordFailure(long runId, long repositoryId, String error, long durationMillis) {
        record(runId, repositoryId, new RepoIndexOutcome(RepoIndexStatus.FAILED, null, null, error, 0, 0, 0,
                durationMillis));
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
        List<long[]> inFlight = jdbc.query("SELECT id, indexing_run_id FROM scm_repository "
                + "WHERE indexing_run_id IS NOT NULL", (rs, row) -> new long[] {rs.getLong("id"),
                        rs.getLong("indexing_run_id")});
        for (long[] repository : inFlight) {
            record(repository[1], repository[0], new RepoIndexOutcome(RepoIndexStatus.INTERRUPTED, null, null,
                    INTERRUPTED_REPOSITORY, 0, 0, 0, 0));
            jdbc.update("UPDATE scm_repository SET last_status = ?, indexing_run_id = NULL WHERE id = ?",
                    RepoIndexStatus.INTERRUPTED.name(), repository[0]);
        }
        return jdbc.update("UPDATE index_run SET status = 'INTERRUPTED', error = ?, finished_at = SYSTIMESTAMP "
                + "WHERE status = 'RUNNING'", INTERRUPTED_RUN);
    }

    private static String cut(String error) {
        return error == null ? null : Utf8.truncateToBytes(error, ERROR_BYTES);
    }
}
```

- [ ] **Step 6: Add the 409 type and handler**

`src/main/java/com/graphify/common/exception/ConflictException.java`:

```java
package com.graphify.common.exception;

import java.util.Map;

/** The request conflicts with the current state (spec §8, HTTP 409); {@code properties} are added to the problem. */
public class ConflictException extends RuntimeException {

    private final Map<String, Object> properties;

    public ConflictException(String message) {
        this(message, Map.of());
    }

    public ConflictException(String message, Map<String, Object> properties) {
        super(message);
        this.properties = Map.copyOf(properties);
    }

    public Map<String, Object> properties() {
        return properties;
    }
}
```

Add to `src/main/java/com/graphify/common/exception/ApiExceptionHandler.java`:

```java
    @ExceptionHandler(ConflictException.class)
    ProblemDetail conflict(ConflictException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Conflict");
        e.properties().forEach(problem::setProperty);
        return problem;
    }
```

- [ ] **Step 7: Update the test fixtures and the one caller**

In `src/test/java/com/graphify/store/StoreFixtures.java`, method `cleanIndexTables`:
- Insert `jdbc.update("UPDATE index_lock SET holder = NULL, acquired_at = NULL");` and `jdbc.update("UPDATE scm_repository SET indexing_run_id = NULL");` as its first two statements, before the `index_run_repo`/`index_run` deletes.

In `src/test/java/com/graphify/indexing/RepositoryIndexerTest.java`, change `runId = runs.start("MANUAL", "ALL", null, "test");` to `runId = runs.start(RunTrigger.MANUAL, RunScope.ALL, null, "test");`.

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./mvnw test -Dtest='SchemaMigrationTest,IndexLockTest,IndexRunRecorderTest,AppSettingsTest,RepositoryIndexerTest'`
Expected: all pass. `AppSettingsTest.everyDeclaredKeyIsSeeded` covers the new keys.

Run: `./mvnw test`
Expected: all tests pass.

- [ ] **Step 9: Commit**

```bash
git add src/main src/test
git commit -m "feat(indexing): add the index lock, run lifecycle records and 409 conflicts" -m "<your harness Co-Authored-By trailer>"
```

---

### Task 2: Acquisition hardening: deactivation guard, connection sync status, safe workspace paths

**Files:**
- Create: `src/main/java/com/graphify/scm/ConnectionSyncStatus.java`
- Create: `src/test/java/com/graphify/testsupport/SettingsOverride.java`
- Modify: `src/main/java/com/graphify/scm/RepositorySync.java`, `ScmConnections.java`, `BitbucketDataCenterClient.java`
- Modify: `src/main/java/com/graphify/workspace/GitWorkspace.java`
- Test: `src/test/java/com/graphify/scm/RepositorySyncTest.java` (add a test), `ScmConnectionsTest.java` (new); `src/test/java/com/graphify/workspace/GitWorkspaceTest.java` (add a test)

**Interfaces:**
- **Consumes:** `SettingKeys.SCM_MAX_DEACTIVATION_PERCENT` (Task 1), `AppSettings`, `UrlMasking`, `Utf8`.
- **Produces:**
  - `public enum ConnectionSyncStatus { SUCCESS, AUTH_FAILED, FAILED, DEACTIVATION_SKIPPED }`.
  - `ScmConnections.recordSync(long connectionId, ConnectionSyncStatus status, String error)` masks the error and cuts it to 4000 bytes.
  - `RepositorySync.SyncResult(int listed, int added, int reactivated, int deactivated, int deactivationsSkipped)` keeps a four-argument constructor that passes `deactivationsSkipped = 0`.
  - `RepositorySync` gains an `AppSettings` constructor parameter.
  - **Guard rule.** Let `activeBefore` be the connection's active rows before the sync and `gone` the active rows no longer listed. When `gone > 1` and `gone * 100 > activeBefore * scm.max_deactivation_percent`, nothing is deactivated or removed, and the result reports `deactivationsSkipped = gone`. Setting the percentage to 100 disables the guard. Losing a single repository is always allowed.
  - `GitWorkspace.directoryFor` maps a null, blank or `"."` segment to `_`, so the result is always a strict subdirectory of `index.workspace_dir`.
  - `BitbucketDataCenterClient` closes its JDK `HttpClient` after each listing.
  - Masked `WARN`/`INFO` log lines are added for a skipped over-long URL, a deactivation, a skipped mass deactivation, and a workspace discarded after a failed update.
  - **Test support:** `SettingsOverride` (test only) with `SettingsOverride(AppSettings)`, `SettingsOverride set(String key, String value)` and `void restore()`.

- [ ] **Step 1: Write the settings override helper**

`src/test/java/com/graphify/testsupport/SettingsOverride.java`:

```java
package com.graphify.testsupport;

import com.graphify.settings.AppSettings;
import java.util.LinkedHashMap;
import java.util.Map;

/** Changes settings for one test and puts the previous values back in {@link #restore()}. */
public final class SettingsOverride {

    private final AppSettings settings;
    private final Map<String, String> originals = new LinkedHashMap<>();

    public SettingsOverride(AppSettings settings) {
        this.settings = settings;
    }

    public SettingsOverride set(String key, String value) {
        originals.putIfAbsent(key, settings.all().stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow()
                .value());
        settings.update(key, value, "test");
        return this;
    }

    public void restore() {
        originals.forEach((key, value) -> settings.update(key, value, "test"));
        originals.clear();
    }
}
```

- [ ] **Step 2: Write the failing tests**

Append to `src/test/java/com/graphify/scm/RepositorySyncTest.java`, inside the class. Add the imports `com.graphify.settings.AppSettings`, `com.graphify.settings.SettingKeys` and `com.graphify.testsupport.SettingsOverride`:

```java
    @Autowired
    AppSettings settings;

    @Test
    void aMassDisappearanceIsNotDeactivated() {
        bitbucket.addRepository("SHOP", "a", "https://scm/a.git").addRepository("SHOP", "b", "https://scm/b.git")
                .addRepository("SHOP", "c", "https://scm/c.git");
        sync.sync(connection);
        long aId = jdbc.queryForObject("SELECT id FROM scm_repository WHERE slug = 'a'", Long.class);
        jdbc.update("INSERT INTO maven_module (repo_id, path, classpath_mode) VALUES (?, '.', 'FULL')", aId);
        bitbucket.removeRepository("SHOP", "a");
        bitbucket.removeRepository("SHOP", "b");

        assertThat(sync.sync(connection)).isEqualTo(new RepositorySync.SyncResult(1, 0, 0, 0, 2));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scm_repository WHERE active = 1", Integer.class))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM maven_module", Integer.class)).isEqualTo(1);

        SettingsOverride overrides = new SettingsOverride(settings);
        try {
            overrides.set(SettingKeys.SCM_MAX_DEACTIVATION_PERCENT, "100");

            assertThat(sync.sync(connection)).isEqualTo(new RepositorySync.SyncResult(1, 0, 0, 2, 0));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scm_repository WHERE active = 1", Integer.class))
                    .isEqualTo(1);
        } finally {
            overrides.restore();
        }
    }
```

`src/test/java/com/graphify/scm/ScmConnectionsTest.java`:

```java
package com.graphify.scm;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.common.crypto.SecretCipher;
import com.graphify.store.StoreFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ScmConnectionsTest extends OracleIntegrationTest {

    @Autowired
    ScmConnections connections;

    @Autowired
    SecretCipher cipher;

    private long id;

    @BeforeEach
    void setUp() {
        StoreFixtures.cleanIndexTables(jdbc);
        jdbc.update("""
                INSERT INTO scm_connection (name, type, base_url, username, secret_enc, include_projects, exclude_repos)
                VALUES ('corp', 'BITBUCKET_DC', 'https://scm.corp', 'bob', ?, ' SHOP , PAY ', 'SHOP/old-*')
                """, cipher.encrypt("s3cret"));
        jdbc.update("INSERT INTO scm_connection (name, type, base_url, enabled) VALUES ('off', 'BITBUCKET_DC', "
                + "'https://off', 0)");
        id = jdbc.queryForObject("SELECT id FROM scm_connection WHERE name = 'corp'", Long.class);
    }

    @Test
    void readsEnabledConnectionsWithDecryptedSecretsAndTrimmedLists() {
        assertThat(connections.enabled()).singleElement().satisfies(c -> {
            assertThat(c.name()).isEqualTo("corp");
            assertThat(c.secret()).isEqualTo("s3cret");
            assertThat(c.includeProjects()).containsExactly("SHOP", "PAY");
            assertThat(c.excludeRepos()).containsExactly("SHOP/old-*");
        });
        assertThat(connections.find(id)).isPresent();
    }

    @Test
    void recordsTheLastSyncMaskedAndCut() {
        connections.recordSync(id, ConnectionSyncStatus.FAILED, "GET https://bob:pw123@scm.corp/x " + "y".repeat(5000));

        assertThat(jdbc.queryForObject("SELECT last_sync_status FROM scm_connection WHERE id = ?", String.class, id))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT last_sync_error FROM scm_connection WHERE id = ?", String.class, id))
                .doesNotContain("pw123").contains("***@scm.corp");
        assertThat(jdbc.queryForObject("SELECT LENGTHB(last_sync_error) FROM scm_connection WHERE id = ?",
                Integer.class, id)).isEqualTo(4000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scm_connection WHERE id = ? AND last_sync_at IS NOT NULL",
                Integer.class, id)).isEqualTo(1);

        connections.recordSync(id, ConnectionSyncStatus.SUCCESS, null);

        assertThat(jdbc.queryForObject("SELECT last_sync_status || ':' || NVL(last_sync_error, '-') FROM scm_connection "
                + "WHERE id = ?", String.class, id)).isEqualTo("SUCCESS:-");
    }
}
```

Append to `src/test/java/com/graphify/workspace/GitWorkspaceTest.java`, inside the class:

```java
    @Test
    void dotAndBlankSegmentsStayInsideTheWorkspace() {
        Path root = dir.resolve("ws");

        Path dot = workspace.directoryFor(7, ".", "..");
        Path blank = workspace.directoryFor(7, " ", null);

        assertThat(dot.normalize()).startsWith(root).isNotEqualTo(root).isNotEqualTo(root.resolve("7"));
        assertThat(dot.getFileName().toString()).doesNotContain("..");
        assertThat(blank.normalize()).startsWith(root.resolve("7")).isNotEqualTo(root.resolve("7"));
    }
```

- [ ] **Step 3: Run them to verify they fail**

Run: `./mvnw test -Dtest='RepositorySyncTest,ScmConnectionsTest,GitWorkspaceTest'`
Expected: BUILD FAILURE: `cannot find symbol` for `ConnectionSyncStatus`, `recordSync` and the five-argument `SyncResult`.

- [ ] **Step 4: Implement**

`src/main/java/com/graphify/scm/ConnectionSyncStatus.java`:

```java
package com.graphify.scm;

/** Outcome of the last repository listing of a connection (spec §8 SCM rows), shown with the connection. */
public enum ConnectionSyncStatus {
    SUCCESS, AUTH_FAILED, FAILED, DEACTIVATION_SKIPPED
}
```

Add to `src/main/java/com/graphify/scm/ScmConnections.java`:

```java
    /** Width of scm_connection.last_sync_error in V5__index_orchestration.sql. */
    private static final int ERROR_BYTES = 4000;

    public void recordSync(long connectionId, ConnectionSyncStatus status, String error) {
        String masked = error == null ? null : Utf8.truncateToBytes(UrlMasking.mask(error), ERROR_BYTES);
        jdbc.update("UPDATE scm_connection SET last_sync_status = ?, last_sync_at = SYSTIMESTAMP, last_sync_error = ? "
                + "WHERE id = ?", status.name(), masked, connectionId);
    }
```

with `import com.graphify.common.util.Utf8;`.

In `src/main/java/com/graphify/scm/RepositorySync.java`:

1. Add the logger `private static final Logger log = LoggerFactory.getLogger(RepositorySync.class);` (`org.slf4j`).
2. Add the constructor parameter `AppSettings settings` and a field for it.
3. Replace the record with:

```java
    /** {@code deactivationsSkipped} counts repositories left active because too many disappeared at once. */
    public record SyncResult(int listed, int added, int reactivated, int deactivated, int deactivationsSkipped) {

        public SyncResult(int listed, int added, int reactivated, int deactivated) {
            this(listed, added, reactivated, deactivated, 0);
        }
    }
```

4. At the start of `sync`, after resolving the client, count the active rows before anything changes:

```java
        int activeBefore = jdbc.queryForObject("SELECT COUNT(*) FROM scm_repository WHERE connection_id = ? "
                + "AND active = 1", Integer.class, connection.id());
```

5. In the over-long-URL branch, before `continue`, log:

```java
                log.warn("Connection '{}': the clone URL of {}/{} is longer than {} bytes; its row is left unchanged",
                        connection.name(), repository.projectKey(), repository.slug(), CLONE_URL_BYTES);
```

6. Replace the deactivation loop at the end with:

```java
        int maxPercent = settings.getInt(SettingKeys.SCM_MAX_DEACTIVATION_PERCENT);
        if (gone.size() > 1 && (long) gone.size() * 100 > (long) activeBefore * maxPercent) {
            // a revoked permission or a mistyped project filter must not wipe the index (plan 4 follow-up)
            log.warn("Connection '{}': {} of {} active repositories are no longer listed; deactivation skipped "
                    + "(scm.max_deactivation_percent = {})", connection.name(), gone.size(), activeBefore, maxPercent);
            return new SyncResult(listed.size(), added, reactivated, 0, gone.size());
        }
        for (Long id : gone) {
            writer.remove(id);
            jdbc.update("UPDATE scm_repository SET active = 0 WHERE id = ?", id);
        }
        if (!gone.isEmpty()) {
            log.info("Connection '{}': deactivated {} repositories no longer listed", connection.name(), gone.size());
        }
        return new SyncResult(listed.size(), added, reactivated, gone.size());
```

7. Update the class Javadoc to mention the guard.

In `src/main/java/com/graphify/scm/BitbucketDataCenterClient.java`:
- Create the JDK `HttpClient` in `listRepositories` with try-with-resources (`HttpClient` is `AutoCloseable` on JDK 21+).
- Pass it to `client(...)`, which now takes `(ScmConnection connection, HttpClient http)` and builds `new JdkClientHttpRequestFactory(http)`.
- The paging loop runs inside the try block. Behaviour is otherwise unchanged.

In `src/main/java/com/graphify/workspace/GitWorkspace.java`:
1. Replace `safe` with:

```java
    /** One path segment that can never be empty, "." or "..", so a directory is always strictly inside its parent. */
    private static String safe(String segment) {
        if (segment == null || segment.isBlank()) {
            return "_";
        }
        String cleaned = segment.replaceAll("[^A-Za-z0-9._-]", "_").replace("..", "__");
        return cleaned.equals(".") ? "_" : cleaned;
    }
```

2. Add `private static final Logger log = LoggerFactory.getLogger(GitWorkspace.class);`. In `checkout`, inside the `catch (Exception e)` of the update attempt and before `deleteRecursively(directory)`, add:

```java
                log.warn("Workspace {} could not be updated ({}); cloning it again", directory,
                        UrlMasking.mask(e.getMessage()));
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw test -Dtest='RepositorySyncTest,ScmConnectionsTest,GitWorkspaceTest,BitbucketDataCenterClientTest'`
Expected: all pass. The existing `RepositorySyncTest` assertions still compare equal, because the four-argument constructor sets `deactivationsSkipped` to 0.

Run: `./mvnw test`
Expected: all tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main src/test
git commit -m "feat(scm): guard against mass deactivation and record connection sync status" -m "<your harness Co-Authored-By trailer>"
```

---

### Task 3: Shared SCM test fixture

**Files:**
- Create: `src/test/java/com/graphify/testsupport/ShopScm.java`
- Modify: `src/test/java/com/graphify/indexing/RepositoryIndexerTest.java`, `src/test/java/com/graphify/scm/BitbucketDataCenterClientTest.java`, `src/test/java/com/graphify/maven/ClasspathResolverTest.java`

**Interfaces:**
- **Consumes:** `FakeBitbucket`, `GitFixtures`, `MavenFixtures`, `TestJars`, `SecretCipher`, and `SettingsOverride` (Task 2).
- **Produces:** `ShopScm` (test only) is the shop fixture served the way production sees it.
  - `static ShopScm create(JdbcTemplate jdbc, SecretCipher cipher, Path dir)` builds the three bare repositories (shop-lib, shop-api depending on shop-lib 1.0.0, docs without Java). It publishes shop-lib to a file Maven repository registered in `artifact_repository`, starts a `FakeBitbucket` that requires `Bearer` + `TOKEN`, and inserts the `scm_connection` named `CONNECTION` with the encrypted token. It does not sync.
  - `static final String TOKEN = "bb-token-123"` and `static final String CONNECTION = "corp"`.
  - Accessors: `FakeBitbucket bitbucket()`, `Path apiBare()`, `Set<String> apiJavaFiles()`, `long connectionId(JdbcTemplate)`.
  - `close()` stops the fake Bitbucket.
- **Purpose:** removes the setup duplication before Tasks 4–5 need the same fixture, and replaces the three private `change(...)`/`originals` copies with `SettingsOverride`.

- [ ] **Step 1: Write `ShopScm`**

`src/test/java/com/graphify/testsupport/ShopScm.java`:

```java
package com.graphify.testsupport;

import com.graphify.common.crypto.SecretCipher;
import com.graphify.indexer.TestJars;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The shop fixture as production sees it: a fake Bitbucket lists three git repositories (shop-lib; shop-api, which
 * depends on shop-lib 1.0.0 through Maven; docs, without Java), shop-lib is published to a file-based Maven
 * repository registered in artifact_repository, and the scm_connection {@value #CONNECTION} points at the fake.
 */
public final class ShopScm implements AutoCloseable {

    public static final String TOKEN = "bb-token-123";
    public static final String CONNECTION = "corp";

    private static final String SOURCES = "src/main/java/";

    private final FakeBitbucket bitbucket;
    private final Path apiBare;
    private final Set<String> apiJavaFiles;

    private ShopScm(FakeBitbucket bitbucket, Path apiBare, Set<String> apiJavaFiles) {
        this.bitbucket = bitbucket;
        this.apiBare = apiBare;
        this.apiJavaFiles = apiJavaFiles;
    }

    public static ShopScm create(JdbcTemplate jdbc, SecretCipher cipher, Path dir)
            throws IOException, URISyntaxException {
        Path shop = Path.of(ShopScm.class.getResource("/fixtures/shop").toURI());
        Map<String, String> libSources = sources(shop.resolve("shop-lib/src/main/java"));
        Map<String, String> apiSources = sources(shop.resolve("shop-api/src/main/java"));

        Path repo = MavenFixtures.fileRepository(dir);
        Map<String, String> libJarSources = new HashMap<>();
        libSources.forEach((path, source) -> libJarSources.put(path.substring(SOURCES.length()), source));
        MavenFixtures.publish(repo, "com.shop", "shop-lib", "1.0.0",
                TestJars.jar(dir, "shop-lib", libJarSources, Set.of()));
        jdbc.update("INSERT INTO artifact_repository (name, url) VALUES ('fixture', ?)", repo.toUri().toString());

        Map<String, String> lib = new HashMap<>(libSources);
        lib.put("pom.xml", MavenFixtures.pom("com.shop", "shop-lib", "1.0.0", "", ""));
        Map<String, String> api = new HashMap<>(apiSources);
        api.put("pom.xml", MavenFixtures.pom("com.shop", "shop-api", "1.0.0", "",
                MavenFixtures.dependency("com.shop", "shop-lib", "1.0.0")));
        Path libBare = GitFixtures.bareRepository(dir, "shop-lib", lib);
        Path apiBare = GitFixtures.bareRepository(dir, "shop-api", api);
        Path docsBare = GitFixtures.bareRepository(dir, "docs", Map.of("README.md", "# docs only"));

        FakeBitbucket bitbucket = new FakeBitbucket().start().requireAuthorization("Bearer " + TOKEN)
                .addRepository("SHOP", "shop-lib", GitFixtures.url(libBare))
                .addRepository("SHOP", "shop-api", GitFixtures.url(apiBare))
                .addRepository("SHOP", "docs", GitFixtures.url(docsBare));
        jdbc.update("INSERT INTO scm_connection (name, type, base_url, secret_enc) VALUES (?, 'BITBUCKET_DC', ?, ?)",
                CONNECTION, bitbucket.baseUrl(), cipher.encrypt(TOKEN));
        return new ShopScm(bitbucket, apiBare, Set.copyOf(apiSources.keySet()));
    }

    public FakeBitbucket bitbucket() {
        return bitbucket;
    }

    public Path apiBare() {
        return apiBare;
    }

    /** The repository-relative paths of shop-api's Java files. */
    public Set<String> apiJavaFiles() {
        return apiJavaFiles;
    }

    public long connectionId(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT id FROM scm_connection WHERE name = ?", Long.class, CONNECTION);
    }

    @Override
    public void close() {
        bitbucket.close();
    }

    private static Map<String, String> sources(Path root) throws IOException {
        Map<String, String> sources = new HashMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                sources.put(SOURCES + root.relativize(file).toString().replace('\\', '/'), Files.readString(file));
            }
        }
        return sources;
    }
}
```

- [ ] **Step 2: Refactor the three tests onto the shared helpers**

In `RepositoryIndexerTest`:
1. Replace the body of `setUp` with:

```java
        StoreFixtures.cleanIndexTables(jdbc);
        overrides = new SettingsOverride(settings)
                .set(SettingKeys.INDEX_WORKSPACE_DIR, dir.resolve("ws").toString())
                .set(SettingKeys.INDEX_MAVEN_LOCAL_REPOSITORY,
                        Path.of(System.getProperty("user.home"), ".m2", "repository").toString());
        shop = ShopScm.create(jdbc, cipher, dir);
        sync.sync(connections.enabled().getFirst());
        runId = runs.start(RunTrigger.MANUAL, RunScope.ALL, null, "test");
```

2. Change `tearDown` to `shop.close(); overrides.restore();`.
3. Remove the fields `TOKEN`, `originals`, `bitbucket`, `apiBare` and `apiJavaFiles`. Add `private ShopScm shop;` and `private SettingsOverride overrides;`.
4. Remove the methods `change`, `fixture` and `sources`.
5. In the test bodies, replace `change(` with `overrides.set(`, `apiBare` with `shop.apiBare()`, `apiJavaFiles` with `shop.apiJavaFiles()`, `TOKEN` with `ShopScm.TOKEN` and `bitbucket` with `shop.bitbucket()`.
6. Remove the imports that are now unused.

In `BitbucketDataCenterClientTest` and `ClasspathResolverTest`:
- Replace the private `originals` map and the `change(...)` method with a `SettingsOverride overrides` field, created in `@BeforeEach` from the injected `AppSettings`.
- Turn each `change(k, v)` into `overrides.set(k, v)`, and the restore loop in `@AfterEach` into `overrides.restore()`.
- Assertions do not change.

- [ ] **Step 3: Run the affected tests**

Run: `./mvnw test -Dtest='RepositoryIndexerTest,BitbucketDataCenterClientTest,ClasspathResolverTest'`
Expected: all pass, with the same test counts as before the refactor.

Run: `./mvnw test`
Expected: all tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/test
git commit -m "test: share the SCM fixture and settings overrides across acquisition tests" -m "<your harness Co-Authored-By trailer>"
```

---

### Task 4: The run executor and connection pool sizing

**Files:**
- Create: `src/main/java/com/graphify/store/ConnectionPoolSizer.java`
- Create: `src/main/java/com/graphify/indexing/IndexRunExecutor.java`
- Test: `src/test/java/com/graphify/store/ConnectionPoolSizerTest.java`, `src/test/java/com/graphify/indexing/IndexRunExecutorTest.java`

**Interfaces:**
- **Consumes:**
  - From Task 1: `IndexRunRecorder` (`isCancelRequested`, `markStarted`, `markFinished`, `recordFailure`, `finish`), `RunScope`, `RunStatus`.
  - From Task 2: `ScmConnections.recordSync`, `ConnectionSyncStatus`, `RepositorySync.sync`.
  - From plan 4: `RepositoryIndexer.index(runId, repositoryId, force)`, `ScmAuthenticationException`, `UrlMasking`.
  - `Chunks.placeholders`, `SettingKeys.INDEX_PARALLELISM` / `STORE_CONNECTION_RESERVE`, and `ShopScm`/`SettingsOverride` (Tasks 2–3).
- **Produces:**
  - `public class ConnectionPoolSizer` (a `@Component`) with `int ensureCapacity(int workers)`. It grows the Hikari maximum pool size to `workers + store.connection_reserve`, never shrinks it, and returns the resulting maximum.
  - `public class IndexRunExecutor` (a `@Service`) with `RunStatus execute(long runId, RunScope scope, Long scopeId, boolean force)` and `void indexSafely(long runId, long repositoryId, boolean force)` (package-private, used by tests).
- **Behaviour of `execute`:**
  - **REPOSITORY scope:** indexes `[scopeId]` without syncing.
  - **ALL / CONNECTION scope:** syncs each connection in scope: `connections.enabled()` for ALL, or `connections.find(scopeId)` for CONNECTION. The cancel flag is checked before each one.
    - Success records `SUCCESS`, or `DEACTIVATION_SKIPPED` with a note when `deactivationsSkipped > 0`.
    - `ScmAuthenticationException` records `AUTH_FAILED`; any other `RuntimeException` records `FAILED`. Each failure adds a masked note.
  - **Indexing:** indexes the active repositories of the synced connections, ordered by project key, slug and id. It uses `min(index.parallelism, repository count)` platform threads named `index-run-<id>-N`, after calling `ensureCapacity`.
  - **Final status:** see the Global Constraints. Notes are joined with newlines into `index_run.error`.
  - **Interruption** (application shutdown) finishes the run `INTERRUPTED`.
- **Behaviour of `indexSafely`:**
  - It returns at once if cancel was requested.
  - Otherwise it marks the repository as started, calls the indexer, and on any `Throwable` records a masked `FAILED` outcome via `recordFailure`.
  - It always clears the marker.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/graphify/store/ConnectionPoolSizerTest.java`:

```java
package com.graphify.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ConnectionPoolSizerTest extends OracleIntegrationTest {

    @Autowired
    ConnectionPoolSizer sizer;

    @Autowired
    DataSource dataSource;

    @Autowired
    AppSettings settings;

    @Test
    void growsThePoolToWorkersPlusTheReserveAndNeverShrinksIt() throws Exception {
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        int reserve = settings.getInt(SettingKeys.STORE_CONNECTION_RESERVE);
        int before = hikari.getHikariConfigMXBean().getMaximumPoolSize();

        int grown = sizer.ensureCapacity(before + 3);

        assertThat(grown).isEqualTo(before + 3 + reserve);
        assertThat(hikari.getHikariConfigMXBean().getMaximumPoolSize()).isEqualTo(before + 3 + reserve);
        assertThat(sizer.ensureCapacity(1)).isEqualTo(before + 3 + reserve);
    }
}
```

`src/test/java/com/graphify/indexing/IndexRunExecutorTest.java`:

```java
package com.graphify.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.common.crypto.SecretCipher;
import com.graphify.scm.RepositorySync;
import com.graphify.scm.ScmConnections;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import com.graphify.store.StoreFixtures;
import com.graphify.testsupport.SettingsOverride;
import com.graphify.testsupport.ShopScm;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

/** Real git, Maven and Oracle: the shop fixture behind a fake Bitbucket, indexed by whole runs. */
class IndexRunExecutorTest extends OracleIntegrationTest {

    @Autowired
    IndexRunExecutor executor;

    @Autowired
    IndexRunRecorder runs;

    @Autowired
    RepositorySync sync;

    @Autowired
    ScmConnections connections;

    @Autowired
    SecretCipher cipher;

    @Autowired
    AppSettings settings;

    @TempDir
    Path dir;

    private ShopScm shop;
    private SettingsOverride overrides;

    @BeforeEach
    void setUp() throws Exception {
        StoreFixtures.cleanIndexTables(jdbc);
        overrides = new SettingsOverride(settings)
                .set(SettingKeys.INDEX_WORKSPACE_DIR, dir.resolve("ws").toString())
                .set(SettingKeys.INDEX_MAVEN_LOCAL_REPOSITORY,
                        Path.of(System.getProperty("user.home"), ".m2", "repository").toString())
                .set(SettingKeys.INDEX_PARALLELISM, "2");
        shop = ShopScm.create(jdbc, cipher, dir);
    }

    @AfterEach
    void tearDown() {
        shop.close();
        overrides.restore();
    }

    private long start(RunScope scope, Long id) {
        return runs.start(RunTrigger.MANUAL, scope, id, "test");
    }

    private List<String> outcomes(long run) {
        return jdbc.queryForList("""
                SELECT r.slug || ':' || x.status FROM index_run_repo x JOIN scm_repository r ON r.id = x.repo_id
                 WHERE x.run_id = ? ORDER BY r.slug
                """, String.class, run);
    }

    private String connectionStatus() {
        return jdbc.queryForObject("SELECT last_sync_status FROM scm_connection WHERE name = ?", String.class,
                ShopScm.CONNECTION);
    }

    private long repo(String slug) {
        return jdbc.queryForObject("SELECT id FROM scm_repository WHERE slug = ?", Long.class, slug);
    }

    @Test
    void aFullRunSyncsAndIndexesEveryRepositoryThenSkipsThemWhenUnchanged() {
        long run = start(RunScope.ALL, null);

        assertThat(executor.execute(run, RunScope.ALL, null, false)).isEqualTo(RunStatus.SUCCESS);

        assertThat(outcomes(run)).containsExactly("docs:SKIPPED_NOT_JAVA", "shop-api:SUCCESS", "shop-lib:SUCCESS");
        assertThat(runs.status(run)).contains(RunStatus.SUCCESS);
        assertThat(connectionStatus()).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scm_repository WHERE indexing_run_id IS NOT NULL",
                Integer.class)).isZero();

        long again = start(RunScope.ALL, null);
        executor.execute(again, RunScope.ALL, null, false);

        assertThat(outcomes(again)).containsExactly("docs:SKIPPED_UNCHANGED", "shop-api:SKIPPED_UNCHANGED",
                "shop-lib:SKIPPED_UNCHANGED");
    }

    @Test
    void rejectedCredentialsFailTheConnectionAndTheRun() {
        jdbc.update("UPDATE scm_connection SET secret_enc = ? WHERE name = ?", cipher.encrypt("wrong-token-9"),
                ShopScm.CONNECTION);
        long run = start(RunScope.ALL, null);

        assertThat(executor.execute(run, RunScope.ALL, null, false)).isEqualTo(RunStatus.FAILED);

        assertThat(outcomes(run)).isEmpty();
        assertThat(connectionStatus()).isEqualTo("AUTH_FAILED");
        assertThat(jdbc.queryForObject("SELECT error FROM index_run WHERE id = ?", String.class, run))
                .contains(ShopScm.CONNECTION).contains("AUTH_FAILED").doesNotContain("wrong-token-9");
    }

    @Test
    void aRepositoryRunIndexesOnlyThatRepositoryWithoutSyncing() {
        sync.sync(connections.enabled().getFirst());
        long run = start(RunScope.REPOSITORY, repo("docs"));

        assertThat(executor.execute(run, RunScope.REPOSITORY, repo("docs"), false)).isEqualTo(RunStatus.SUCCESS);

        assertThat(outcomes(run)).containsExactly("docs:SKIPPED_NOT_JAVA");
        assertThat(connectionStatus()).isNull();
    }

    @Test
    void aConnectionRunSyncsAndIndexesThatConnection() {
        long run = start(RunScope.CONNECTION, shop.connectionId(jdbc));

        assertThat(executor.execute(run, RunScope.CONNECTION, shop.connectionId(jdbc), false))
                .isEqualTo(RunStatus.SUCCESS);

        assertThat(outcomes(run)).hasSize(3);
        assertThat(connectionStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void aCancelledRunStartsNothing() {
        long run = start(RunScope.ALL, null);
        runs.requestCancel(run);

        assertThat(executor.execute(run, RunScope.ALL, null, false)).isEqualTo(RunStatus.CANCELLED);

        assertThat(outcomes(run)).isEmpty();
        assertThat(runs.status(run)).contains(RunStatus.CANCELLED);
    }

    @Test
    void anyThrowableIsRecordedAsAFailedRepository() {
        sync.sync(connections.enabled().getFirst());
        long docs = repo("docs");
        jdbc.update("UPDATE scm_repository SET active = 0 WHERE id = ?", docs);
        long run = start(RunScope.REPOSITORY, docs);

        executor.indexSafely(run, docs, false);

        assertThat(outcomes(run)).containsExactly("docs:FAILED");
        assertThat(jdbc.queryForObject("SELECT error FROM index_run_repo WHERE run_id = ?", String.class, run))
                .startsWith("RepositoryNotFoundException");
        assertThat(jdbc.queryForObject("SELECT last_status FROM scm_repository WHERE id = ?", String.class, docs))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT indexing_run_id FROM scm_repository WHERE id = ?", Long.class, docs))
                .isNull();
    }
}
```

`anyThrowableIsRecordedAsAFailedRepository` uses an inactive repository: `RepositoryIndexer.index` throws `RepositoryNotFoundException` while loading it. This exercises the same catch-all path as an `Error`. The catch is for `Throwable`, so a parser `StackOverflowError` takes this path too.

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -Dtest='ConnectionPoolSizerTest,IndexRunExecutorTest'`
Expected: BUILD FAILURE: `cannot find symbol` for `ConnectionPoolSizer` and `IndexRunExecutor`.

- [ ] **Step 3: Write the pool sizer**

`src/main/java/com/graphify/store/ConnectionPoolSizer.java`:

```java
package com.graphify.store;

import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import com.zaxxer.hikari.HikariConfigMXBean;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Grows the Hikari pool before index workers start (plan 2 follow-up): each worker holds a connection while it writes,
 * and store.connection_reserve more stay free for API requests. Never shrinks the pool.
 */
@Component
public class ConnectionPoolSizer {

    private static final Logger log = LoggerFactory.getLogger(ConnectionPoolSizer.class);

    private final DataSource dataSource;
    private final AppSettings settings;

    public ConnectionPoolSizer(DataSource dataSource, AppSettings settings) {
        this.dataSource = dataSource;
        this.settings = settings;
    }

    public int ensureCapacity(int workers) {
        int needed = workers + settings.getInt(SettingKeys.STORE_CONNECTION_RESERVE);
        try {
            if (!dataSource.isWrapperFor(HikariDataSource.class)) {
                return needed;
            }
            HikariConfigMXBean pool = dataSource.unwrap(HikariDataSource.class).getHikariConfigMXBean();
            if (pool.getMaximumPoolSize() < needed) {
                log.info("Growing the connection pool from {} to {} for {} index workers", pool.getMaximumPoolSize(),
                        needed, workers);
                pool.setMaximumPoolSize(needed);
            }
            return pool.getMaximumPoolSize();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not size the connection pool", e);
        }
    }
}
```

- [ ] **Step 4: Write the executor**

`src/main/java/com/graphify/indexing/IndexRunExecutor.java`:

```java
package com.graphify.indexing;

import com.graphify.scm.ConnectionSyncStatus;
import com.graphify.scm.RepositorySync;
import com.graphify.scm.ScmAuthenticationException;
import com.graphify.scm.ScmConnection;
import com.graphify.scm.ScmConnections;
import com.graphify.scm.UrlMasking;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import com.graphify.store.Chunks;
import com.graphify.store.ConnectionPoolSizer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Carries out one index run (spec §3.2, §8): syncs the connections in scope, then indexes their active repositories
 * on index.parallelism workers. A failing connection or repository never stops the others. The caller holds the
 * index lock and has inserted the RUNNING index_run row.
 */
@Service
public class IndexRunExecutor {

    private static final Logger log = LoggerFactory.getLogger(IndexRunExecutor.class);

    private static final String SHUTDOWN = "The application stopped during this run";

    /** What a run indexes, and the connection notes for index_run.error. */
    private record Plan(List<Long> repositoryIds, boolean everyConnectionFailed, List<String> notes) {
    }

    private final JdbcTemplate jdbc;
    private final ScmConnections connections;
    private final RepositorySync sync;
    private final RepositoryIndexer indexer;
    private final IndexRunRecorder runs;
    private final ConnectionPoolSizer pool;
    private final AppSettings settings;

    public IndexRunExecutor(JdbcTemplate jdbc, ScmConnections connections, RepositorySync sync,
            RepositoryIndexer indexer, IndexRunRecorder runs, ConnectionPoolSizer pool, AppSettings settings) {
        this.jdbc = jdbc;
        this.connections = connections;
        this.sync = sync;
        this.indexer = indexer;
        this.runs = runs;
        this.pool = pool;
        this.settings = settings;
    }

    public RunStatus execute(long runId, RunScope scope, Long scopeId, boolean force) {
        RunStatus status;
        String error = null;
        try {
            Plan plan = plan(runId, scope, scopeId);
            error = plan.notes().isEmpty() ? null : String.join("\n", plan.notes());
            indexAll(runId, plan.repositoryIds(), force);
            status = runs.isCancelRequested(runId) ? RunStatus.CANCELLED
                    : plan.everyConnectionFailed() ? RunStatus.FAILED : RunStatus.SUCCESS;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status = RunStatus.INTERRUPTED;
            error = SHUTDOWN;
        } catch (RuntimeException e) {
            status = RunStatus.FAILED;
            error = masked(e);
            log.error("Index run {} failed: {}", runId, error);
        }
        runs.finish(runId, status, error);
        log.info("Index run {} finished: {}", runId, status);
        return status;
    }

    private Plan plan(long runId, RunScope scope, Long scopeId) {
        if (scope == RunScope.REPOSITORY) {
            return new Plan(List.of(scopeId), false, List.of());
        }
        List<ScmConnection> inScope = scope == RunScope.ALL ? connections.enabled()
                : connections.find(scopeId).map(List::of).orElse(List.of());
        List<Long> synced = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (ScmConnection connection : inScope) {
            if (runs.isCancelRequested(runId)) {
                break;
            }
            try {
                RepositorySync.SyncResult result = sync.sync(connection);
                if (result.deactivationsSkipped() > 0) {
                    String note = result.deactivationsSkipped() + " repositories are no longer listed; deactivation "
                            + "skipped (scm.max_deactivation_percent)";
                    connections.recordSync(connection.id(), ConnectionSyncStatus.DEACTIVATION_SKIPPED, note);
                    notes.add("connection '" + connection.name() + "': " + note);
                } else {
                    connections.recordSync(connection.id(), ConnectionSyncStatus.SUCCESS, null);
                }
                synced.add(connection.id());
            } catch (ScmAuthenticationException e) {
                connections.recordSync(connection.id(), ConnectionSyncStatus.AUTH_FAILED, e.getMessage());
                notes.add("connection '" + connection.name() + "': AUTH_FAILED: " + masked(e));
            } catch (RuntimeException e) {
                connections.recordSync(connection.id(), ConnectionSyncStatus.FAILED, masked(e));
                notes.add("connection '" + connection.name() + "': FAILED: " + masked(e));
            }
        }
        List<Long> repositoryIds = synced.isEmpty() ? List.of() : jdbc.queryForList("SELECT id FROM scm_repository "
                + "WHERE active = 1 AND connection_id IN (" + Chunks.placeholders(synced.size()) + ") "
                + "ORDER BY project_key, slug, id", Long.class, synced.toArray());
        return new Plan(repositoryIds, !inScope.isEmpty() && synced.isEmpty(), notes);
    }

    private void indexAll(long runId, List<Long> repositoryIds, boolean force) throws InterruptedException {
        if (repositoryIds.isEmpty()) {
            return;
        }
        int workers = Math.min(settings.getInt(SettingKeys.INDEX_PARALLELISM), repositoryIds.size());
        pool.ensureCapacity(workers);
        ExecutorService executor = Executors.newFixedThreadPool(workers,
                Thread.ofPlatform().name("index-run-" + runId + "-", 1).factory());
        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (Long repositoryId : repositoryIds) {
                tasks.add(executor.submit(() -> indexSafely(runId, repositoryId, force)));
            }
            for (Future<?> task : tasks) {
                try {
                    task.get();
                } catch (ExecutionException e) {
                    // only reachable when even recording the failure failed (database down)
                    log.error("Index run {}: a repository could not be recorded: {}", runId, masked(e.getCause()));
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    /** Indexes one repository and records an outcome whatever happens; always clears its in-progress marker. */
    void indexSafely(long runId, long repositoryId, boolean force) {
        if (runs.isCancelRequested(runId)) {
            return;
        }
        long startedAt = System.nanoTime();
        runs.markStarted(runId, repositoryId);
        try {
            indexer.index(runId, repositoryId, force);
        } catch (Throwable e) { // a parser StackOverflowError must not leave the repository unrecorded
            String error = masked(e);
            log.warn("Index run {}: repository {} failed: {}", runId, repositoryId, error);
            runs.recordFailure(runId, repositoryId, error, (System.nanoTime() - startedAt) / 1_000_000);
        } finally {
            runs.markFinished(repositoryId);
        }
    }

    private static String masked(Throwable e) {
        return UrlMasking.mask(e.getClass().getSimpleName() + ": " + e.getMessage());
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw test -Dtest='ConnectionPoolSizerTest,IndexRunExecutorTest'`
Expected: 7 tests pass. `IndexRunExecutorTest` runs real git and Maven, so it can take a minute.

Run: `./mvnw test`
Expected: all tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main src/test
git commit -m "feat(indexing): run index runs over connections in parallel with per-repository outcomes" -m "<your harness Co-Authored-By trailer>"
```

---

### Task 5: Starting, cancelling and recovering runs

**Files:**
- Create: `src/main/java/com/graphify/indexing/IndexRunService.java`, `IndexRunConflictException.java`, `IndexRunRecovery.java`
- Test: `src/test/java/com/graphify/indexing/IndexRunServiceTest.java`

**Interfaces:**
- **Consumes:**
  - From Task 1: `IndexLock`, `IndexRunRecorder`, `ConflictException`.
  - From Task 4: `IndexRunExecutor.execute`.
  - `InvalidRequestException`, `NotFoundException`, `ShopScm`, `SettingsOverride`.
- **Produces (`IndexRunService`, a `@Service` that implements `DisposableBean`):** `long start(RunScope scope, Long scopeId, boolean force, RunTrigger trigger, String actor)` and `void cancel(long runId)`.
  - **`start`:**
    - Validates first:
      - A null scope, an `id` given with `ALL`, or a missing `id` otherwise is `InvalidRequestException`.
      - An unknown connection or repository is `NotFoundException`.
      - A disabled connection, an inactive repository, or a repository of a disabled connection is `ConflictException`.
    - Then inserts the run and tries the lock. If the lock is busy, it discards the run and throws `IndexRunConflictException(holder)`.
    - Then submits the run to one background coordinator thread (`index-coordinator`). The task catches every `Throwable` (finishing the run `FAILED`) and releases the lock in `finally`.
    - If the coordinator is shut down, the run is finished `FAILED`, the lock released, and a `ConflictException` thrown.
  - **`cancel`:** an unknown run is `NotFoundException`. A run that is not running, or whose cancel request fails, is `ConflictException` naming its status.
- **Produces (exceptions and recovery):**
  - `public class IndexRunConflictException extends ConflictException` takes `String holder` (nullable). Its message names the run or the cleanup, and its properties hold `runId` when the holder is a run.
  - `public class IndexRunRecovery` (a `@Component`) has `void recover()`. It runs on `ApplicationReadyEvent` with the highest precedence: `runs.recoverInterrupted()` followed by `lock.forceRelease()`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/graphify/indexing/IndexRunServiceTest.java`:

```java
package com.graphify.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.graphify.OracleIntegrationTest;
import com.graphify.common.crypto.SecretCipher;
import com.graphify.common.exception.ConflictException;
import com.graphify.common.exception.InvalidRequestException;
import com.graphify.common.exception.NotFoundException;
import com.graphify.scm.RepositorySync;
import com.graphify.scm.ScmConnections;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import com.graphify.store.StoreFixtures;
import com.graphify.testsupport.SettingsOverride;
import com.graphify.testsupport.ShopScm;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

class IndexRunServiceTest extends OracleIntegrationTest {

    private static final Duration RUN_TIMEOUT = Duration.ofMinutes(2);

    @Autowired
    IndexRunService service;

    @Autowired
    IndexRunRecovery recovery;

    @Autowired
    IndexLock lock;

    @Autowired
    IndexRunRecorder runs;

    @Autowired
    RepositorySync sync;

    @Autowired
    ScmConnections connections;

    @Autowired
    SecretCipher cipher;

    @Autowired
    AppSettings settings;

    @TempDir
    Path dir;

    private ShopScm shop;
    private SettingsOverride overrides;

    @BeforeEach
    void setUp() throws Exception {
        StoreFixtures.cleanIndexTables(jdbc);
        overrides = new SettingsOverride(settings)
                .set(SettingKeys.INDEX_WORKSPACE_DIR, dir.resolve("ws").toString())
                .set(SettingKeys.INDEX_MAVEN_LOCAL_REPOSITORY,
                        Path.of(System.getProperty("user.home"), ".m2", "repository").toString());
        shop = ShopScm.create(jdbc, cipher, dir);
        sync.sync(connections.enabled().getFirst());
    }

    @AfterEach
    void tearDown() {
        await().atMost(RUN_TIMEOUT).until(() -> lock.holder().isEmpty());
        shop.close();
        overrides.restore();
    }

    private long repo(String slug) {
        return jdbc.queryForObject("SELECT id FROM scm_repository WHERE slug = ?", Long.class, slug);
    }

    private int runCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM index_run", Integer.class);
    }

    @Test
    void runsInTheBackgroundAndReleasesTheLock() {
        long run = service.start(RunScope.REPOSITORY, repo("docs"), false, RunTrigger.MANUAL, "tester");

        await().atMost(RUN_TIMEOUT).until(() -> runs.status(run).orElseThrow() != RunStatus.RUNNING);

        assertThat(runs.status(run)).contains(RunStatus.SUCCESS);
        assertThat(jdbc.queryForObject("SELECT trigger_type || ':' || scope || ':' || started_by FROM index_run "
                + "WHERE id = ?", String.class, run)).isEqualTo("MANUAL:REPOSITORY:tester");
        await().atMost(RUN_TIMEOUT).until(() -> lock.holder().isEmpty());
    }

    @Test
    void aSecondRunIsAConflictNamingTheRunInProgress() {
        assertThat(lock.tryAcquire(IndexLock.runHolder(77))).isTrue();
        int before = runCount();
        try {
            assertThatThrownBy(() -> service.start(RunScope.ALL, null, false, RunTrigger.MANUAL, "tester"))
                    .isInstanceOf(IndexRunConflictException.class)
                    .hasMessageContaining("77")
                    .satisfies(e -> assertThat(((ConflictException) e).properties()).containsEntry("runId", 77L));
            assertThat(runCount()).isEqualTo(before);
        } finally {
            lock.release(IndexLock.runHolder(77));
        }
    }

    @Test
    void theCleanupJobHoldingTheLockIsAlsoAConflict() {
        assertThat(lock.tryAcquire(IndexLock.CLEANUP_HOLDER)).isTrue();
        try {
            assertThatThrownBy(() -> service.start(RunScope.ALL, null, false, RunTrigger.MANUAL, "tester"))
                    .isInstanceOf(IndexRunConflictException.class)
                    .hasMessageContaining("cleanup");
        } finally {
            lock.release(IndexLock.CLEANUP_HOLDER);
        }
    }

    @Test
    void validatesTheScopeBeforeTakingTheLock() {
        assertThatThrownBy(() -> service.start(null, null, false, RunTrigger.MANUAL, "t"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.start(RunScope.ALL, 5L, false, RunTrigger.MANUAL, "t"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.start(RunScope.CONNECTION, null, false, RunTrigger.MANUAL, "t"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.start(RunScope.CONNECTION, -1L, false, RunTrigger.MANUAL, "t"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.start(RunScope.REPOSITORY, -1L, false, RunTrigger.MANUAL, "t"))
                .isInstanceOf(NotFoundException.class);

        jdbc.update("UPDATE scm_repository SET active = 0 WHERE slug = 'docs'");
        assertThatThrownBy(() -> service.start(RunScope.REPOSITORY, repo("docs"), false, RunTrigger.MANUAL, "t"))
                .isInstanceOf(ConflictException.class).hasMessageContaining("inactive");

        jdbc.update("UPDATE scm_connection SET enabled = 0");
        assertThatThrownBy(() -> service.start(RunScope.CONNECTION, shop.connectionId(jdbc), false,
                RunTrigger.MANUAL, "t")).isInstanceOf(ConflictException.class).hasMessageContaining("disabled");
        assertThatThrownBy(() -> service.start(RunScope.REPOSITORY, repo("shop-api"), false, RunTrigger.MANUAL, "t"))
                .isInstanceOf(ConflictException.class).hasMessageContaining("disabled");

        assertThat(lock.holder()).isEmpty();
        assertThat(runCount()).isZero();
    }

    @Test
    void cancelRequiresARunningRun() {
        long running = runs.start(RunTrigger.MANUAL, RunScope.ALL, null, "t");

        service.cancel(running);

        assertThat(runs.isCancelRequested(running)).isTrue();
        runs.finish(running, RunStatus.CANCELLED, null);
        assertThatThrownBy(() -> service.cancel(running)).isInstanceOf(ConflictException.class)
                .hasMessageContaining("CANCELLED");
        assertThatThrownBy(() -> service.cancel(-1)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void startupRecoveryInterruptsAbandonedRunsAndFreesTheLock() {
        long abandoned = runs.start(RunTrigger.SCHEDULED, RunScope.ALL, null, "scheduler");
        runs.markStarted(abandoned, repo("shop-api"));
        assertThat(lock.tryAcquire(IndexLock.runHolder(abandoned))).isTrue();

        recovery.recover();

        assertThat(runs.status(abandoned)).contains(RunStatus.INTERRUPTED);
        assertThat(lock.holder()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM index_run_repo WHERE run_id = ?", String.class, abandoned))
                .isEqualTo("INTERRUPTED");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw test -Dtest=IndexRunServiceTest`
Expected: BUILD FAILURE: `cannot find symbol` for `IndexRunService`, `IndexRunRecovery` and `IndexRunConflictException`.

- [ ] **Step 3: Implement**

`src/main/java/com/graphify/indexing/IndexRunConflictException.java`:

```java
package com.graphify.indexing;

import com.graphify.common.exception.ConflictException;
import java.util.Map;
import java.util.OptionalLong;

/** The index lock is held (spec §8: a second run request gets 409); names the run in progress when there is one. */
public class IndexRunConflictException extends ConflictException {

    public IndexRunConflictException(String holder) {
        super(message(holder), properties(holder));
    }

    private static String message(String holder) {
        OptionalLong runId = IndexLock.runIdOf(holder);
        if (runId.isPresent()) {
            return "Index run " + runId.getAsLong() + " is in progress";
        }
        if (IndexLock.CLEANUP_HOLDER.equals(holder)) {
            return "The orphan symbol cleanup is in progress";
        }
        return "Another index operation is in progress";
    }

    private static Map<String, Object> properties(String holder) {
        OptionalLong runId = IndexLock.runIdOf(holder);
        return runId.isPresent() ? Map.of("runId", runId.getAsLong()) : Map.of();
    }
}
```

`src/main/java/com/graphify/indexing/IndexRunService.java`:

```java
package com.graphify.indexing;

import com.graphify.common.exception.ConflictException;
import com.graphify.common.exception.InvalidRequestException;
import com.graphify.common.exception.NotFoundException;
import com.graphify.scm.UrlMasking;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
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
        if (!lock.tryAcquire(holder)) {
            runs.discard(runId);
            throw new IndexRunConflictException(lock.holder().orElse(null));
        }
        try {
            coordinator.execute(() -> run(runId, scope, scopeId, force, holder));
        } catch (RejectedExecutionException e) {
            runs.finish(runId, RunStatus.FAILED, "The application is shutting down");
            lock.release(holder);
            throw new ConflictException("The application is shutting down; index run " + runId + " was not started");
        }
        log.info("Index run {} started ({} {}, force={}) by {}", runId, scope, scopeId == null ? "" : scopeId, force,
                actor);
        return runId;
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
            runs.finish(runId, RunStatus.FAILED, UrlMasking.mask(e.getClass().getSimpleName() + ": " + e.getMessage()));
        } finally {
            lock.release(holder);
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
    }
}
```

`src/main/java/com/graphify/indexing/IndexRunRecovery.java`:

```java
package com.graphify.indexing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Spec §8 restart row: at startup, before anything can schedule a run, runs left RUNNING by a stopped process become
 * INTERRUPTED and the index lock is released.
 */
@Component
public class IndexRunRecovery {

    private static final Logger log = LoggerFactory.getLogger(IndexRunRecovery.class);

    private final IndexRunRecorder runs;
    private final IndexLock lock;

    public IndexRunRecovery(IndexRunRecorder runs, IndexLock lock) {
        this.runs = runs;
        this.lock = lock;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void recover() {
        int interrupted = runs.recoverInterrupted();
        lock.forceRelease();
        if (interrupted > 0) {
            log.warn("Marked {} unfinished index run(s) INTERRUPTED", interrupted);
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw test -Dtest=IndexRunServiceTest`
Expected: 6 tests pass.

Run: `./mvnw test`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main src/test
git commit -m "feat(indexing): start runs in the background behind the lock, cancel them and recover after restart" -m "<your harness Co-Authored-By trailer>"
```

---

### Task 6: Cron scheduling and the orphan cleanup job

**Files:**
- Modify: `src/main/java/com/graphify/store/SymbolCleanup.java`
- Create: `src/main/java/com/graphify/indexing/OrphanCleanupJob.java`, `IndexScheduler.java`
- Modify: `src/test/resources/config/application.yml`
- Test: `src/test/java/com/graphify/store/SymbolCleanupTest.java` (add a test), `src/test/java/com/graphify/indexing/OrphanCleanupJobTest.java`, `IndexSchedulerTest.java`

**Interfaces:**
- **Consumes:** `IndexLock`, `IndexRunService.start`, `IndexRunConflictException`/`ConflictException`, `AppSettings.getCron`, `SettingChangedEvent`, `SettingKeys.INDEX_CRON` / `CLEANUP_ORPHAN_SYMBOLS_CRON` / `CLEANUP_BATCH_SIZE`.
- **Produces (cleanup):**
  - `SymbolCleanup.deleteOrphans()` keeps its signature but now deletes in statements of at most `cleanup.batch_size` rows, each committed on its own. It is no longer `@Transactional`. It repeats while a statement deleted a full batch, and returns the total.
  - `public class OrphanCleanupJob` (a `@Component`) has `OptionalInt run()`. It returns empty when the lock is held, and otherwise the deleted count, with the lock released in `finally`.
- **Produces (`IndexScheduler`):**
  - It is a `@Component` with `@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)` and implements `DisposableBean`.
  - It has two constructors: a public one that creates its own `ThreadPoolTaskScheduler`, and a package-private `IndexScheduler(TaskScheduler, AppSettings, IndexRunService, OrphanCleanupJob)`.
  - `start()` handles `ApplicationReadyEvent` at the lowest precedence and schedules both cron jobs.
  - `onSettingChanged(SettingChangedEvent)` handles a change to either cron key by replacing that job's schedule. It never throws.
  - `runScheduledIndex()` starts `ALL` with trigger `SCHEDULED` and actor `scheduler`, and logs and ignores a conflict.
  - `runScheduledCleanup()` calls `cleanup.run()` and logs any failure.
- **Produces (test config):** the test configuration sets `app.scheduling.enabled: false`, so no cron fires during tests.

- [ ] **Step 1: Write the failing tests**

Append to `src/test/java/com/graphify/store/SymbolCleanupTest.java`, inside the class, with the imports `com.graphify.settings.AppSettings`, `com.graphify.settings.SettingKeys` and `com.graphify.testsupport.SettingsOverride`:

```java
    @Autowired
    AppSettings settings;

    @Test
    void deletesInBatchesUntilNoFullBatchIsLeft() {
        symbol("p.A", null);
        symbol("p.B", null);
        symbol("p.C", null);
        SettingsOverride overrides = new SettingsOverride(settings).set(SettingKeys.CLEANUP_BATCH_SIZE, "1");
        try {
            assertThat(cleanup.deleteOrphans()).isEqualTo(3);
        } finally {
            overrides.restore();
        }
        assertThat(exists("p.A") || exists("p.B") || exists("p.C")).isFalse();
    }
```

The existing parent/child tests still expect one deletion per call with the default batch size of 10,000. A pass stops when a statement deletes fewer rows than the batch, and a parent only becomes an orphan after the statement that deleted its children.

`src/test/java/com/graphify/indexing/OrphanCleanupJobTest.java`:

```java
package com.graphify.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.store.StoreFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OrphanCleanupJobTest extends OracleIntegrationTest {

    @Autowired
    OrphanCleanupJob job;

    @Autowired
    IndexLock lock;

    @BeforeEach
    void setUp() {
        StoreFixtures.cleanIndexTables(jdbc);
        jdbc.update("""
                INSERT INTO symbol (symbol_key, kind, class_fqn, display_signature, origin, name_only)
                VALUES ('p.Orphan', 'CLASS', 'p.Orphan', 'p.Orphan', 'SOURCE', 0)
                """);
    }

    @AfterEach
    void free() {
        lock.forceRelease();
    }

    private int symbols() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM symbol", Integer.class);
    }

    @Test
    void skipsWhileAnIndexRunHoldsTheLock() {
        assertThat(lock.tryAcquire(IndexLock.runHolder(5))).isTrue();

        assertThat(job.run()).isEmpty();

        assertThat(symbols()).isEqualTo(1);
        assertThat(lock.holder()).contains(IndexLock.runHolder(5));
    }

    @Test
    void deletesOrphansUnderTheLockAndReleasesIt() {
        assertThat(job.run()).hasValue(1);

        assertThat(symbols()).isZero();
        assertThat(lock.holder()).isEmpty();
    }
}
```

`src/test/java/com/graphify/indexing/IndexSchedulerTest.java`:

```java
package com.graphify.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.graphify.OracleIntegrationTest;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingChangedEvent;
import com.graphify.settings.SettingKeys;
import com.graphify.testsupport.SettingsOverride;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

class IndexSchedulerTest extends OracleIntegrationTest {

    @Autowired
    AppSettings settings;

    @Autowired
    IndexRunService runs;

    @Autowired
    OrphanCleanupJob cleanup;

    @Autowired
    IndexLock lock;

    @Autowired
    ApplicationContext context;

    private final RecordingScheduler recorder = new RecordingScheduler();
    private SettingsOverride overrides;
    private IndexScheduler scheduler;

    @BeforeEach
    void setUp() {
        overrides = new SettingsOverride(settings);
        scheduler = new IndexScheduler(recorder, settings, runs, cleanup);
        lock.forceRelease();
    }

    @AfterEach
    void tearDown() {
        overrides.restore();
        lock.forceRelease();
    }

    @Test
    void isNotABeanWhenSchedulingIsDisabled() {
        assertThat(context.getBeansOfType(IndexScheduler.class)).isEmpty();
    }

    @Test
    void schedulesBothJobsFromTheirCronSettings() {
        scheduler.start();

        assertThat(recorder.expressions()).containsExactly(settings.getCron(SettingKeys.INDEX_CRON),
                settings.getCron(SettingKeys.CLEANUP_ORPHAN_SYMBOLS_CRON));
    }

    @Test
    void aChangedCronReplacesOnlyItsOwnSchedule() {
        scheduler.start();
        overrides.set(SettingKeys.INDEX_CRON, "0 30 1 * * *");

        scheduler.onSettingChanged(new SettingChangedEvent(SettingKeys.INDEX_CRON));
        scheduler.onSettingChanged(new SettingChangedEvent(SettingKeys.API_PAGE_DEFAULT_SIZE));

        assertThat(recorder.expressions()).hasSize(3).last().isEqualTo("0 30 1 * * *");
        assertThat(recorder.futures.get(0).isCancelled()).isTrue();
        assertThat(recorder.futures.get(1).isCancelled()).isFalse();
    }

    @Test
    void aFailingRescheduleNeverEscapesTheListener() {
        scheduler.start();
        recorder.failing = true;

        assertThatCode(() -> scheduler.onSettingChanged(new SettingChangedEvent(SettingKeys.INDEX_CRON)))
                .doesNotThrowAnyException();
    }

    @Test
    void aScheduledRunIsSkippedWhileTheLockIsHeld() {
        assertThat(lock.tryAcquire(IndexLock.CLEANUP_HOLDER)).isTrue();
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM index_run", Integer.class);

        assertThatCode(scheduler::runScheduledIndex).doesNotThrowAnyException();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM index_run", Integer.class)).isEqualTo(before);
    }

    /** Records triggers instead of running them. */
    static final class RecordingScheduler extends ThreadPoolTaskScheduler {

        final List<CronTrigger> triggers = new ArrayList<>();
        final List<RecordedFuture> futures = new ArrayList<>();
        boolean failing;

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
            if (failing) {
                throw new IllegalStateException("scheduler unavailable");
            }
            triggers.add((CronTrigger) trigger);
            RecordedFuture future = new RecordedFuture();
            futures.add(future);
            return future;
        }

        List<String> expressions() {
            return triggers.stream().map(CronTrigger::getExpression).toList();
        }
    }

    static final class RecordedFuture implements ScheduledFuture<Object> {

        private boolean cancelled;

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return cancelled;
        }

        @Override
        public Object get() {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            return null;
        }
    }
}
```


- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -Dtest='SymbolCleanupTest,OrphanCleanupJobTest,IndexSchedulerTest'`
Expected: BUILD FAILURE: `cannot find symbol` for `OrphanCleanupJob` and `IndexScheduler`.

- [ ] **Step 3: Chunk the cleanup**

Replace `src/main/java/com/graphify/store/SymbolCleanup.java` with:

```java
package com.graphify.store;

import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Deletes symbols that no usage or declaration references any more (spec §4.4). A symbol that is still some other
 * symbol's parent is kept, even when nothing references it directly (an owner type used only through its members):
 * deleting it would clear its members' {@code parent_id}. A parent is therefore deleted by a later statement or run,
 * once its children are gone. Must only run under the index lock ({@code OrphanCleanupJob}): an index write may have
 * merged a symbol it has not yet referenced.
 */
@Component
public class SymbolCleanup {

    private static final String DELETE_BATCH = """
            DELETE FROM symbol s
             WHERE NOT EXISTS (SELECT 1 FROM usage u WHERE u.to_symbol_id = s.id)
               AND NOT EXISTS (SELECT 1 FROM usage u WHERE u.from_symbol_id = s.id)
               AND NOT EXISTS (SELECT 1 FROM symbol_declaration d WHERE d.symbol_id = s.id)
               AND NOT EXISTS (SELECT 1 FROM symbol c WHERE c.parent_id = s.id)
               AND ROWNUM <= ?
            """;

    private final JdbcTemplate jdbc;
    private final AppSettings settings;

    public SymbolCleanup(JdbcTemplate jdbc, AppSettings settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    /**
     * Deletes orphans in statements of at most cleanup.batch_size rows, each committed on its own so undo never grows
     * with the whole cleanup; stops after a statement that deleted less than a full batch. Returns the total.
     */
    public int deleteOrphans() {
        int batchSize = settings.getInt(SettingKeys.CLEANUP_BATCH_SIZE);
        int total = 0;
        int deleted;
        do {
            deleted = jdbc.update(DELETE_BATCH, batchSize);
            total += deleted;
        } while (deleted == batchSize);
        return total;
    }
}
```

- [ ] **Step 4: Write the job and the scheduler**

`src/main/java/com/graphify/indexing/OrphanCleanupJob.java`:

```java
package com.graphify.indexing;

import com.graphify.store.SymbolCleanup;
import java.util.OptionalInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Deletes orphan symbols under the index lock (spec §4.4, plan 2 follow-up); skipped while an index run holds it. */
@Component
public class OrphanCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(OrphanCleanupJob.class);

    private final IndexLock lock;
    private final SymbolCleanup cleanup;

    public OrphanCleanupJob(IndexLock lock, SymbolCleanup cleanup) {
        this.lock = lock;
        this.cleanup = cleanup;
    }

    /** The number of symbols deleted, or empty when the lock was held. */
    public OptionalInt run() {
        if (!lock.tryAcquire(IndexLock.CLEANUP_HOLDER)) {
            log.info("Orphan symbol cleanup skipped: {} holds the index lock", lock.holder().orElse("nobody"));
            return OptionalInt.empty();
        }
        try {
            int deleted = cleanup.deleteOrphans();
            log.info("Deleted {} orphan symbols", deleted);
            return OptionalInt.of(deleted);
        } finally {
            lock.release(IndexLock.CLEANUP_HOLDER);
        }
    }
}
```

`src/main/java/com/graphify/indexing/IndexScheduler.java`:

```java
package com.graphify.indexing;

import com.graphify.common.exception.ConflictException;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingChangedEvent;
import com.graphify.settings.SettingKeys;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

/**
 * Schedules the full index run (index.cron) and the orphan cleanup (cleanup.orphan_symbols_cron), and replaces a
 * schedule when its setting changes (spec §6.4: no restart). app.scheduling.enabled=false turns it off (tests).
 */
@Component
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class IndexScheduler implements DisposableBean {

    /** index_run.started_by of scheduled runs. */
    static final String SCHEDULER_ACTOR = "scheduler";

    /** One thread per job, so a long cleanup never delays the start of an index run. */
    private static final int JOB_THREADS = 2;

    private static final Logger log = LoggerFactory.getLogger(IndexScheduler.class);

    private final TaskScheduler scheduler;
    private final AppSettings settings;
    private final IndexRunService runs;
    private final OrphanCleanupJob cleanup;
    private final Map<String, ScheduledFuture<?>> scheduled = new ConcurrentHashMap<>();

    @Autowired
    public IndexScheduler(AppSettings settings, IndexRunService runs, OrphanCleanupJob cleanup) {
        this(ownScheduler(), settings, runs, cleanup);
    }

    IndexScheduler(TaskScheduler scheduler, AppSettings settings, IndexRunService runs, OrphanCleanupJob cleanup) {
        this.scheduler = scheduler;
        this.settings = settings;
        this.runs = runs;
        this.cleanup = cleanup;
    }

    private static ThreadPoolTaskScheduler ownScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(JOB_THREADS);
        scheduler.setThreadNamePrefix("index-scheduler-");
        scheduler.initialize();
        return scheduler;
    }

    /** After {@link IndexRunRecovery}, so a scheduled run never meets a stale lock. */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void start() {
        schedule(SettingKeys.INDEX_CRON);
        schedule(SettingKeys.CLEANUP_ORPHAN_SYMBOLS_CRON);
    }

    /** Never throws: the setting update has already committed, and its caller must not see a scheduling failure. */
    @EventListener
    public void onSettingChanged(SettingChangedEvent event) {
        if (!event.key().equals(SettingKeys.INDEX_CRON) && !event.key().equals(SettingKeys.CLEANUP_ORPHAN_SYMBOLS_CRON)) {
            return;
        }
        try {
            schedule(event.key());
            log.info("Re-scheduled {} to '{}'", event.key(), settings.getCron(event.key()));
        } catch (RuntimeException e) {
            log.error("Could not re-schedule {}: {}", event.key(), e.getMessage());
        }
    }

    private synchronized void schedule(String key) {
        ScheduledFuture<?> previous = scheduled.remove(key);
        if (previous != null) {
            previous.cancel(false);
        }
        Runnable job = key.equals(SettingKeys.INDEX_CRON) ? this::runScheduledIndex : this::runScheduledCleanup;
        scheduled.put(key, scheduler.schedule(job, new CronTrigger(settings.getCron(key))));
    }

    void runScheduledIndex() {
        try {
            long runId = runs.start(RunScope.ALL, null, false, RunTrigger.SCHEDULED, SCHEDULER_ACTOR);
            log.info("Scheduled index run {} started", runId);
        } catch (ConflictException e) {
            log.info("Scheduled index run skipped: {}", e.getMessage());
        } catch (RuntimeException e) {
            log.error("Scheduled index run could not start: {}", e.getMessage());
        }
    }

    void runScheduledCleanup() {
        try {
            cleanup.run();
        } catch (RuntimeException e) {
            log.error("Orphan symbol cleanup failed: {}", e.getMessage());
        }
    }

    @Override
    public void destroy() {
        scheduled.values().forEach(future -> future.cancel(false));
        if (scheduler instanceof ThreadPoolTaskScheduler own) {
            own.shutdown();
        }
    }
}
```

The two `@Order` annotations are only honoured between `@EventListener` methods for the same event. `IndexRunRecovery` and `IndexScheduler` both listen for `ApplicationReadyEvent`, so the recovery (highest precedence) runs first.

- [ ] **Step 5: Turn scheduling off in tests**

In `src/test/resources/config/application.yml`, under the existing `app:` key, add:

```yaml
  scheduling:
    enabled: false
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw test -Dtest='SymbolCleanupTest,OrphanCleanupJobTest,IndexSchedulerTest'`
Expected: all pass.

Run: `./mvnw test`
Expected: all tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/main src/test
git commit -m "feat(indexing): schedule index runs and chunked orphan cleanup from cron settings" -m "<your harness Co-Authored-By trailer>"
```

---

### Task 7: Index run REST API and the repository status filter

**Files:**
- Create: `src/main/java/com/graphify/indexing/IndexRunSummary.java`, `IndexRunRepoView.java`, `IndexRunView.java`, `IndexRunQueries.java`, `IndexRunController.java`
- Modify: `src/main/java/com/graphify/repository/RepositoryQueries.java`, `RepositoryController.java`
- Modify: `README.md`
- Test: `src/test/java/com/graphify/indexing/IndexRunApiTest.java`

**Interfaces:**
- **Consumes:** `IndexRunService` (Task 5), `IndexRunRecorder`, `IndexLock`, `PagingResolver`, `Page`, `RepositoryRef`, `RepoIndexStatus`.
- **Produces (views):**
  - `public record IndexRunSummary(long id, String trigger, String scope, Long scopeId, String status, String startedBy, Instant startedAt, Instant finishedAt, boolean cancelRequested)`.
  - `public record IndexRunRepoView(long runId, RepositoryRef repository, String commit, String status, String classpathMode, String error, int symbolCount, int usageCount, int warningCount, long durationMs, Instant finishedAt)`.
  - `public record IndexRunView(IndexRunSummary run, String error, Map<String, Integer> repositoriesByStatus, List<IndexRunRepoView> repositories, List<RepositoryRef> inProgress)`.
- **Produces (`IndexRunQueries`, a `@Repository`):** `Page<IndexRunSummary> list(Paging)` (newest first), `Optional<IndexRunView> find(long runId)`, `boolean repositoryExists(long id)` and `Page<IndexRunRepoView> repositoryRuns(long repositoryId, Paging)` (newest first).
- **Produces (endpoints, all under `/api/v1`):**
  - `POST /index/runs` takes the body `{scope, id, force}`.
    - It answers `202 Accepted` with `{runId}` and `Location: /api/v1/index/runs/{runId}`.
    - It answers 400, 404 or 409 as raised by the service. An unknown `scope` value is 400.
  - `GET /index/runs?page=&size=`.
  - `GET /index/runs/{runId}` returns `IndexRunView`, or 404.
  - `POST /index/runs/{runId}/cancel` answers 202, 404 or 409.
  - `GET /repositories/{id}/runs?page=&size=` returns the repository's run history, or 404.
  - `GET /repositories?status=` filters by `last_status` case-insensitively. Any value that is not a `RepoIndexStatus` is 400.
  - A manual run's `started_by` is `IndexRunController.ANONYMOUS_ACTOR = "anonymous"` until plan 6.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/graphify/indexing/IndexRunApiTest.java`:

```java
package com.graphify.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.graphify.OracleIntegrationTest;
import com.graphify.store.StoreFixtures;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

class IndexRunApiTest extends OracleIntegrationTest {

    @Autowired
    IndexRunRecorder runs;

    @Autowired
    IndexLock lock;

    private long alpha;
    private long beta;
    private long finished;

    @BeforeEach
    void setUp() {
        StoreFixtures.cleanIndexTables(jdbc);
        alpha = StoreFixtures.newRepository(jdbc, "alpha");
        beta = StoreFixtures.newRepository(jdbc, "beta");
        finished = runs.start(RunTrigger.MANUAL, RunScope.ALL, null, "tester");
        runs.record(finished, alpha, new RepoIndexOutcome(RepoIndexStatus.SUCCESS, "abc", "FULL", null, 10, 20, 1, 1500));
        runs.record(finished, beta, new RepoIndexOutcome(RepoIndexStatus.CLONE_FAILED, null, null,
                "GitException: clone https://***@scm/x.git failed", 0, 0, 0, 30));
        runs.finish(finished, RunStatus.SUCCESS, null);
        jdbc.update("UPDATE scm_repository SET last_status = 'SUCCESS' WHERE id = ?", alpha);
        jdbc.update("UPDATE scm_repository SET last_status = 'CLONE_FAILED' WHERE id = ?", beta);
    }

    @AfterEach
    void tearDown() {
        await().atMost(Duration.ofMinutes(1)).until(() -> lock.holder().isEmpty());
    }

    @Test
    void showsARunWithItsRepositoryOutcomes() {
        assertThat(mvc.get().uri("/api/v1/index/runs/" + finished)).hasStatusOk().bodyJson().satisfies(json -> {
            assertThat(json).extractingPath("$.run.status").isEqualTo("SUCCESS");
            assertThat(json).extractingPath("$.run.startedBy").isEqualTo("tester");
            assertThat(json).extractingPath("$.repositoriesByStatus.SUCCESS").isEqualTo(1);
            assertThat(json).extractingPath("$.repositoriesByStatus.CLONE_FAILED").isEqualTo(1);
            assertThat(json).extractingPath("$.repositories[0].repository.slug").isEqualTo("alpha");
            assertThat(json).extractingPath("$.repositories[0].usageCount").isEqualTo(20);
            assertThat(json).extractingPath("$.repositories[1].error").asString().contains("***@scm");
        });
        assertThat(mvc.get().uri("/api/v1/index/runs/-1")).hasStatus(404);
    }

    @Test
    void listsRunsNewestFirst() {
        long newer = runs.start(RunTrigger.SCHEDULED, RunScope.ALL, null, "scheduler");

        assertThat(mvc.get().uri("/api/v1/index/runs")).hasStatusOk().bodyJson().satisfies(json -> {
            assertThat(json).extractingPath("$.items[0].id").isEqualTo((int) newer);
            assertThat(json).extractingPath("$.items[0].status").isEqualTo("RUNNING");
            assertThat(json).extractingPath("$.total").isEqualTo(2);
        });
    }

    @Test
    void listsARepositorysRunHistory() {
        assertThat(mvc.get().uri("/api/v1/repositories/" + alpha + "/runs")).hasStatusOk().bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.items[0].status").isEqualTo("SUCCESS");
                    assertThat(json).extractingPath("$.items[0].commit").isEqualTo("abc");
                    assertThat(json).extractingPath("$.items[0].runId").isEqualTo((int) finished);
                });
        assertThat(mvc.get().uri("/api/v1/repositories/-1/runs")).hasStatus(404);
    }

    @Test
    void filtersRepositoriesByLastStatus() {
        assertThat(mvc.get().uri("/api/v1/repositories?status=clone_failed")).hasStatusOk().bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.total").isEqualTo(1);
                    assertThat(json).extractingPath("$.items[0].slug").isEqualTo("beta");
                });
        assertThat(mvc.get().uri("/api/v1/repositories?status=NOPE")).hasStatus(400);
    }

    @Test
    void aRunInProgressIsAConflictCarryingItsId() {
        assertThat(lock.tryAcquire(IndexLock.runHolder(finished))).isTrue();
        try {
            assertThat(mvc.post().uri("/api/v1/index/runs").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"scope\":\"ALL\"}")).hasStatus(409).bodyJson().satisfies(json -> {
                        assertThat(json).extractingPath("$.title").isEqualTo("Conflict");
                        assertThat(json).extractingPath("$.runId").isEqualTo((int) finished);
                    });
        } finally {
            lock.release(IndexLock.runHolder(finished));
        }
    }

    @Test
    void rejectsInvalidStartRequests() {
        assertThat(mvc.post().uri("/api/v1/index/runs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"REPOSITORY\"}")).hasStatus(400);
        assertThat(mvc.post().uri("/api/v1/index/runs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"NOPE\"}")).hasStatus(400);
        assertThat(mvc.post().uri("/api/v1/index/runs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"REPOSITORY\",\"id\":-1}")).hasStatus(404);
    }

    @Test
    void startsARunAndPointsToIt() {
        MvcTestResult result = mvc.post().uri("/api/v1/index/runs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"REPOSITORY\",\"id\":" + alpha + "}").exchange();

        assertThat(result).hasStatus(202);
        Number runId = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.runId");
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/api/v1/index/runs/" + runId);
        await().atMost(Duration.ofMinutes(1)).until(() -> lock.holder().isEmpty());
        assertThat(mvc.get().uri("/api/v1/index/runs/" + runId)).hasStatusOk().bodyJson().satisfies(json -> {
            assertThat(json).extractingPath("$.run.status").isEqualTo("SUCCESS");
            assertThat(json).extractingPath("$.run.startedBy").isEqualTo("anonymous");
            assertThat(json).extractingPath("$.repositories[0].status").isEqualTo("CLONE_FAILED");
        });
    }

    @Test
    void cancelsOnlyARunningRun() {
        long running = runs.start(RunTrigger.MANUAL, RunScope.ALL, null, "t");

        assertThat(mvc.post().uri("/api/v1/index/runs/" + running + "/cancel")).hasStatus(202);
        assertThat(runs.isCancelRequested(running)).isTrue();
        assertThat(mvc.post().uri("/api/v1/index/runs/" + finished + "/cancel")).hasStatus(409);
        assertThat(mvc.post().uri("/api/v1/index/runs/-1/cancel")).hasStatus(404);
        runs.finish(running, RunStatus.CANCELLED, null);
    }
}
```

`startsARunAndPointsToIt` indexes `alpha`, whose clone URL is `https://scm.test/...`. The `.test` top-level domain never resolves, so `ls-remote` fails at once and the run ends `SUCCESS` with one `CLONE_FAILED` repository. `getContentAsString()` throws a checked exception: declare `throws Exception` on the test method.

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw test -Dtest=IndexRunApiTest`
Expected: BUILD FAILURE: `cannot find symbol` for `RepoIndexOutcome`/run views, or 404s from missing endpoints.

- [ ] **Step 3: Write the views and queries**

`src/main/java/com/graphify/indexing/IndexRunSummary.java`:

```java
package com.graphify.indexing;

import java.time.Instant;

public record IndexRunSummary(
        long id,
        String trigger,
        String scope,
        Long scopeId,
        String status,
        String startedBy,
        Instant startedAt,
        Instant finishedAt,
        boolean cancelRequested) {
}
```

`src/main/java/com/graphify/indexing/IndexRunRepoView.java`:

```java
package com.graphify.indexing;

import com.graphify.repository.RepositoryRef;
import java.time.Instant;

/** One repository's outcome in one run (index_run_repo); {@code error} is already masked and cut. */
public record IndexRunRepoView(
        long runId,
        RepositoryRef repository,
        String commit,
        String status,
        String classpathMode,
        String error,
        int symbolCount,
        int usageCount,
        int warningCount,
        long durationMs,
        Instant finishedAt) {
}
```

`src/main/java/com/graphify/indexing/IndexRunView.java`:

```java
package com.graphify.indexing;

import com.graphify.repository.RepositoryRef;
import java.util.List;
import java.util.Map;

/** A run with its repository outcomes (at most a few hundred: one per repository) and the repositories still in progress. */
public record IndexRunView(
        IndexRunSummary run,
        String error,
        Map<String, Integer> repositoriesByStatus,
        List<IndexRunRepoView> repositories,
        List<RepositoryRef> inProgress) {
}
```

`src/main/java/com/graphify/indexing/IndexRunQueries.java`:

```java
package com.graphify.indexing;

import com.graphify.api.Page;
import com.graphify.api.Paging;
import com.graphify.repository.RepositoryRef;
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

    private static final String REPO_RUN = """
            SELECT x.run_id, r.id AS repo_id, r.project_key, r.slug, x.commit_sha, x.status, x.classpath_mode, x.error,
                   x.symbol_count, x.usage_count, x.warning_count, x.duration_ms, x.finished_at
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
        long scopeId = rs.getLong("scope_id");
        return new IndexRunSummary(rs.getLong("id"), rs.getString("trigger_type"), rs.getString("scope"),
                rs.wasNull() ? null : scopeId, rs.getString("status"), rs.getString("started_by"),
                instant(rs, "started_at"), instant(rs, "finished_at"), rs.getInt("cancel_requested") == 1);
    }

    private static IndexRunRepoView repoRun(ResultSet rs) throws SQLException {
        return new IndexRunRepoView(rs.getLong("run_id"),
                new RepositoryRef(rs.getLong("repo_id"), rs.getString("project_key"), rs.getString("slug")),
                rs.getString("commit_sha"), rs.getString("status"), rs.getString("classpath_mode"),
                rs.getString("error"), rs.getInt("symbol_count"), rs.getInt("usage_count"),
                rs.getInt("warning_count"), rs.getLong("duration_ms"), instant(rs, "finished_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
```

- [ ] **Step 4: Write the controller**

`src/main/java/com/graphify/indexing/IndexRunController.java`:

```java
package com.graphify.indexing;

import com.graphify.api.Page;
import com.graphify.api.PagingResolver;
import com.graphify.common.exception.InvalidRequestException;
import com.graphify.common.exception.NotFoundException;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Index runs (spec §10.5). Role checks (ADMIN for start/cancel) arrive with authentication in plan 6. */
@RestController
@RequestMapping("/api/v1")
public class IndexRunController {

    /** index_run.started_by of manual runs until plan 6 knows who is calling. */
    static final String ANONYMOUS_ACTOR = "anonymous";

    public record StartRequest(RunScope scope, Long id, Boolean force) {
    }

    public record StartedRun(long runId) {
    }

    private final IndexRunService service;
    private final IndexRunQueries queries;
    private final PagingResolver paging;

    public IndexRunController(IndexRunService service, IndexRunQueries queries, PagingResolver paging) {
        this.service = service;
        this.queries = queries;
        this.paging = paging;
    }

    @PostMapping("/index/runs")
    public ResponseEntity<StartedRun> start(@RequestBody(required = false) StartRequest request) {
        if (request == null) {
            throw new InvalidRequestException("A body {scope, id, force} is required");
        }
        long runId = service.start(request.scope(), request.id(), Boolean.TRUE.equals(request.force()),
                RunTrigger.MANUAL, ANONYMOUS_ACTOR);
        return ResponseEntity.accepted().location(URI.create("/api/v1/index/runs/" + runId)).body(new StartedRun(runId));
    }

    @GetMapping("/index/runs")
    public Page<IndexRunSummary> list(@RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return queries.list(paging.resolve(page, size));
    }

    @GetMapping("/index/runs/{runId}")
    public IndexRunView get(@PathVariable long runId) {
        return queries.find(runId).orElseThrow(() -> new NotFoundException("No index run with id " + runId));
    }

    @PostMapping("/index/runs/{runId}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable long runId) {
        service.cancel(runId);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/repositories/{id}/runs")
    public Page<IndexRunRepoView> repositoryRuns(@PathVariable long id, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        if (!queries.repositoryExists(id)) {
            throw new NotFoundException("No repository with id " + id);
        }
        return queries.repositoryRuns(id, paging.resolve(page, size));
    }
}
```

- [ ] **Step 5: Add the status filter to repositories**

In `src/main/java/com/graphify/repository/RepositoryQueries.java`, change `list(String text, Paging paging)` to `list(String text, String status, Paging paging)`:

```java
    public Page<RepositorySummary> list(String text, String status, Paging paging) {
        String pattern = likePattern(text);
        String where = FILTER + (status == null ? "" : " AND r.last_status = ?");
        List<Object> args = new java.util.ArrayList<>(List.of(pattern, pattern));
        if (status != null) {
            args.add(status);
        }
        List<Object> pageArgs = new java.util.ArrayList<>(args);
        pageArgs.add(paging.offset());
        pageArgs.add(paging.size());
        List<RepositorySummary> items = jdbc.query(SELECT + where
                        + " ORDER BY r.project_key, r.slug OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                (rs, row) -> summary(rs), pageArgs.toArray());
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM scm_repository r" + where, Long.class, args.toArray());
        return new Page<>(items, paging.page(), paging.size(), total == null ? 0 : total);
    }
```

Change `FILTER` to `" WHERE (UPPER(r.slug) LIKE ? ESCAPE '\\' OR UPPER(r.project_key) LIKE ? ESCAPE '\\')"`; the parentheses matter now that an `AND` follows. Use real imports instead of the qualified `ArrayList`.

In `src/main/java/com/graphify/repository/RepositoryController.java`, add a `@RequestParam(required = false) String status` parameter to `list` and pass `status(status)` to `queries.list`:

```java
    /** Upper-cased RepoIndexStatus name, or null when absent; anything else is a 400. */
    private static String status(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return RepoIndexStatus.valueOf(status.strip().toUpperCase(java.util.Locale.ROOT)).name();
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("status must be one of " + java.util.Arrays.toString(RepoIndexStatus.values()));
        }
    }
```

This adds the import `com.graphify.indexing.RepoIndexStatus` (the controller depends on the status enum). Use real imports for `Locale` and `Arrays`.

- [ ] **Step 6: Document the API in `README.md`**

In the API table, add these rows:
- `POST /api/v1/index/runs` (body `{scope: ALL|CONNECTION|REPOSITORY, id, force}`; 202 + `Location`; 409 with `runId` while a run or the cleanup holds the lock)
- `GET /api/v1/index/runs`
- `GET /api/v1/index/runs/{runId}` (`repositoriesByStatus`, `repositories[]`, `inProgress[]`)
- `POST /api/v1/index/runs/{runId}/cancel` (repositories already being indexed finish; no new one starts)
- `GET /api/v1/repositories/{id}/runs`
- `GET /api/v1/repositories?status=`

Below the table, add a short "Index runs" paragraph:
- One run or orphan cleanup runs at a time.
- The nightly run follows `index.cron` and the cleanup follows `cleanup.orphan_symbols_cron`. Both are rescheduled when the setting changes.
- `scm.max_deactivation_percent` keeps a shrunken SCM listing from deactivating repositories in bulk.
- `app.scheduling.enabled=false` turns scheduling off.
- One application instance per schema.
- Role checks come with plan 6.

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./mvnw test -Dtest='IndexRunApiTest,RepositoryApiTest'`
Expected: all pass. The existing `RepositoryApiTest` is unchanged.

Run: `./mvnw test`
Expected: all tests pass.

- [ ] **Step 8: Commit**

```bash
git add src/main src/test README.md
git commit -m "feat(api): expose index runs, cancellation and repository run history" -m "<your harness Co-Authored-By trailer>"
```

---

### Task 8: Version warnings and a read-consistent impact snapshot

**Files:**
- Create: `src/main/java/com/graphify/store/ReadSnapshot.java`, `src/main/java/com/graphify/impact/VersionWarnings.java`
- Modify: `src/main/java/com/graphify/impact/ImpactResult.java`, `ImpactService.java`, `VersionWarning.java` (Javadoc only)
- Modify: `README.md`
- Test: `src/test/java/com/graphify/store/ReadSnapshotTest.java`, `src/test/java/com/graphify/impact/VersionWarningsTest.java`

**Interfaces:**
- **Consumes:** `ImpactEngine`, `ImpactResult`, `RepoState` (its package-private `repositoryRef()` and `RepositoryRef` order), `Chunks`, `ShopFixture`, `AuditLog.record(actor, action, target, details)`.
- **Produces (snapshot):** `public class ReadSnapshot` (a `@Component`) with `<T> T read(Supplier<T> work)`. It runs `work` in a new transaction (`PROPAGATION_REQUIRES_NEW`) whose first statement is `SET TRANSACTION READ ONLY`. Every query in it sees the database as of that moment, and any write fails with ORA-01456.
- **Produces (version warnings):**
  - `public class VersionWarnings` (a `@Component`) has `List<VersionWarning> find(Collection<Long> targetIds, List<RepoState> affectedModules)`.
    - The declared side is the modules that declare a target or its parent symbol, with non-null coordinates.
    - The used side is every `module_dependency` row of an affected module on the same `group:artifact` with a non-null version that is not among the declared versions.
    - Results are ordered by repository (project key, slug, id), module path, then dependency.
  - `ImpactResult.withVersionWarnings(List<VersionWarning>)`.
  - `ImpactService.analyze` runs the engine and the version warnings inside one `ReadSnapshot`.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/graphify/store/ReadSnapshotTest.java`:

```java
package com.graphify.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.graphify.OracleIntegrationTest;
import com.graphify.audit.AuditLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

class ReadSnapshotTest extends OracleIntegrationTest {

    private static final String ACTION = "SNAPSHOT_TEST";

    @Autowired
    ReadSnapshot snapshot;

    @Autowired
    AuditLog auditLog;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM audit_log WHERE action = ?", ACTION);
    }

    private long count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = ?", Long.class, ACTION);
    }

    @Test
    void writesAreRejectedInsideTheSnapshot() {
        assertThatThrownBy(() -> snapshot.read(() -> jdbc.update(
                "UPDATE app_setting SET description = description WHERE ROWNUM = 1")))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ORA-01456");
    }

    @Test
    void everyQuerySeesTheMomentTheSnapshotStarted() throws Exception {
        long[] seen = snapshot.read(() -> {
            long first = count();
            Thread writer = new Thread(() -> auditLog.record("tester", ACTION, "target", "committed meanwhile"));
            writer.start();
            try {
                writer.join();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return new long[] {first, count()};
        });

        assertThat(seen[1]).isEqualTo(seen[0]);
        assertThat(count()).isEqualTo(seen[0] + 1);
    }
}
```

If `AuditLog.record` is `@Transactional`, it commits in its own transaction on the writer thread. If it is not, the `JdbcTemplate` update autocommits. Either way the row is committed before the second count.

`src/test/java/com/graphify/impact/VersionWarningsTest.java`:

```java
package com.graphify.impact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.graphify.OracleIntegrationTest;
import com.graphify.store.RepositoryIndexWriter;
import com.graphify.testsupport.ShopFixture;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

class VersionWarningsTest extends OracleIntegrationTest {

    @Autowired
    RepositoryIndexWriter writer;

    @Autowired
    ImpactService impact;

    @TempDir
    Path work;

    @BeforeEach
    void setUp() throws Exception {
        ShopFixture.load(jdbc, writer, work);
        dependency("shop-api", "com.shop", "shop-lib", "0.9.0");
        dependency("shop-api", "org.other", "thing", "1.0.0");
        dependency("shop-legacy", "com.shop", "shop-lib", "1.0.0");
    }

    private void dependency(String modulePath, String groupId, String artifactId, String version) {
        jdbc.update("""
                INSERT INTO module_dependency (module_id, group_id, artifact_id, version, scope)
                SELECT id, ?, ?, ?, 'compile' FROM maven_module WHERE path = ?
                """, groupId, artifactId, version, modulePath);
    }

    private ImpactResult analyzeFormat() {
        return impact.analyze(new ImpactRequest(
                List.of(ShopFixture.symbolId(jdbc, "com.shop.lib.PriceFormatter#format(int)")), null, 3, null, null));
    }

    @Test
    void flagsAffectedModulesThatUseAnotherVersionOfTheChangedArtifact() {
        assertThat(analyzeFormat().versionWarnings())
                .extracting(w -> w.repository().slug(), VersionWarning::modulePath, VersionWarning::dependency,
                        VersionWarning::usedVersion, VersionWarning::declaredVersion)
                .containsExactly(tuple("shop-api", "shop-api", "com.shop:shop-lib", "0.9.0", "1.0.0"));
    }

    @Test
    void matchingVersionsGiveNoWarning() {
        jdbc.update("UPDATE module_dependency SET version = '1.0.0' WHERE artifact_id = 'shop-lib'");

        assertThat(analyzeFormat().versionWarnings()).isEmpty();
    }
}
```

`ShopFixture` registers shop-lib as `com.shop:shop-lib:1.0.0`. `PriceFormatter` (the parent of the target method) is declared there, and shop-api and shop-legacy are affected modules.

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -Dtest='ReadSnapshotTest,VersionWarningsTest'`
Expected: BUILD FAILURE: `cannot find symbol` for `ReadSnapshot`. Once that compiles, `VersionWarningsTest` fails because `versionWarnings` is empty.

- [ ] **Step 3: Write the snapshot**

`src/main/java/com/graphify/store/ReadSnapshot.java`:

```java
package com.graphify.store;

import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a multi-query read in one Oracle read-only transaction (plan 3 follow-up): every query sees the database as of
 * its first statement, even while index runs commit. Always a new transaction, so SET TRANSACTION is its first
 * statement.
 */
@Component
public class ReadSnapshot {

    private final TransactionTemplate transactions;
    private final JdbcTemplate jdbc;

    public ReadSnapshot(PlatformTransactionManager transactionManager, JdbcTemplate jdbc) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.jdbc = jdbc;
    }

    public <T> T read(Supplier<T> work) {
        return transactions.execute(status -> {
            jdbc.execute("SET TRANSACTION READ ONLY");
            return work.get();
        });
    }
}
```

- [ ] **Step 4: Write the version warnings and wire them in**

`src/main/java/com/graphify/impact/VersionWarnings.java`:

```java
package com.graphify.impact;

import com.graphify.store.Chunks;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Spec §5.6 version difference: an affected module declares a dependency on the changed code's artifact at a version
 * other than the indexed one, so the analysis may not match what that module actually compiles against. The declared
 * side is every module that declares a target or its parent symbol; dependencies without a resolved version are
 * ignored.
 */
@Component
public class VersionWarnings {

    private record Coordinates(String groupId, String artifactId) {

        String text() {
            return groupId + ":" + artifactId;
        }
    }

    private final JdbcTemplate jdbc;

    public VersionWarnings(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<VersionWarning> find(Collection<Long> targetIds, List<RepoState> affectedModules) {
        if (targetIds.isEmpty() || affectedModules.isEmpty()) {
            return List.of();
        }
        Map<Coordinates, Set<String>> declared = declaredVersions(List.copyOf(targetIds));
        if (declared.isEmpty()) {
            return List.of();
        }
        Map<Long, RepoState> modules = new LinkedHashMap<>();
        affectedModules.forEach(state -> modules.put(state.moduleId(), state));
        List<VersionWarning> warnings = new ArrayList<>();
        for (List<Long> chunk : Chunks.of(List.copyOf(modules.keySet()), Chunks.MAX_IN_LIST)) {
            jdbc.query("SELECT module_id, group_id, artifact_id, version FROM module_dependency "
                    + "WHERE version IS NOT NULL AND module_id IN (" + Chunks.placeholders(chunk.size()) + ")", rs -> {
                        Coordinates coordinates = new Coordinates(rs.getString("group_id"), rs.getString("artifact_id"));
                        Set<String> versions = declared.get(coordinates);
                        String used = rs.getString("version");
                        if (versions != null && !versions.contains(used)) {
                            RepoState state = modules.get(rs.getLong("module_id"));
                            warnings.add(new VersionWarning(state.repositoryRef(), state.modulePath(),
                                    coordinates.text(), used, String.join(", ", versions)));
                        }
                    }, chunk.toArray());
        }
        warnings.sort(Comparator.comparing(VersionWarning::repository, RepoState.REPOSITORY_ORDER)
                .thenComparing(VersionWarning::modulePath).thenComparing(VersionWarning::dependency));
        return warnings;
    }

    private Map<Coordinates, Set<String>> declaredVersions(List<Long> targetIds) {
        Map<Coordinates, Set<String>> declared = new LinkedHashMap<>();
        for (List<Long> chunk : Chunks.of(targetIds, Chunks.MAX_IN_LIST)) {
            String in = Chunks.placeholders(chunk.size());
            List<Object> args = new ArrayList<>(chunk);
            args.addAll(chunk);
            jdbc.query("""
                    SELECT DISTINCT m.group_id, m.artifact_id, m.version
                      FROM symbol_declaration d JOIN maven_module m ON m.id = d.module_id
                     WHERE m.group_id IS NOT NULL AND m.artifact_id IS NOT NULL AND m.version IS NOT NULL
                       AND d.symbol_id IN (SELECT id FROM symbol WHERE id IN (%s)
                                           UNION SELECT parent_id FROM symbol WHERE id IN (%s) AND parent_id IS NOT NULL)
                    """.formatted(in, in), rs -> {
                        declared.computeIfAbsent(new Coordinates(rs.getString("group_id"), rs.getString("artifact_id")),
                                k -> new TreeSet<>()).add(rs.getString("version"));
                    }, args.toArray());
        }
        return declared;
    }
}
```

In `src/main/java/com/graphify/impact/ImpactResult.java`, replace the Javadoc's last clause with "`versionWarnings` comes from `MODULE_DEPENDENCY` (`VersionWarnings`)", and add:

```java
    public ImpactResult withVersionWarnings(List<VersionWarning> warnings) {
        return new ImpactResult(summary, nodes, edges, entryPoints, staleness, List.copyOf(warnings), truncated);
    }
```

In `VersionWarning.java`, change the Javadoc sentence "Plan 3 never reports one; plan 4 fills them in." to "Computed by `VersionWarnings`."

Replace the body of `src/main/java/com/graphify/impact/ImpactService.java` with:

```java
/**
 * Runs the impact engine with limits from AppSettings and rules/labels from the database, in one read-only snapshot
 * so an index run committing meanwhile cannot produce a half-old, half-new graph.
 */
@Service
public class ImpactService {

    private final JdbcImpactGraph graph;
    private final ImpactRules rules;
    private final VersionWarnings versionWarnings;
    private final ReadSnapshot snapshot;
    private final AppSettings settings;

    public ImpactService(JdbcImpactGraph graph, ImpactRules rules, VersionWarnings versionWarnings,
            ReadSnapshot snapshot, AppSettings settings) {
        this.graph = graph;
        this.rules = rules;
        this.versionWarnings = versionWarnings;
        this.snapshot = snapshot;
        this.settings = settings;
    }

    public ImpactResult analyze(ImpactRequest request) {
        ImpactLimits limits = new ImpactLimits(
                settings.getInt(SettingKeys.IMPACT_DEFAULT_DEPTH),
                settings.getInt(SettingKeys.IMPACT_MAX_DEPTH),
                settings.getInt(SettingKeys.IMPACT_MAX_RESULTS));
        return snapshot.read(() -> {
            ImpactResult result = new ImpactEngine(graph).analyze(request, limits, rules.rules(),
                    rules.entryPointLabels());
            return result.withVersionWarnings(versionWarnings.find(request.symbolIds(), result.staleness()));
        });
    }
}
```

Add the import `com.graphify.store.ReadSnapshot`. Settings are read before the snapshot opens; they come from the settings cache.

In `README.md`, change the `versionWarnings[]` bullet to: "`versionWarnings[]`: affected modules whose `MODULE_DEPENDENCY` names the changed artifact at a different version than the indexed one." Add one line: "An impact analysis reads one consistent snapshot even while an index run commits."

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw test -Dtest='ReadSnapshotTest,VersionWarningsTest,ImpactServiceTest,ImpactApiTest'`
Expected: all pass.

Run: `./mvnw test`
Expected: all tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main src/test README.md
git commit -m "feat(impact): report dependency version differences and read one consistent snapshot" -m "<your harness Co-Authored-By trailer>"
```
