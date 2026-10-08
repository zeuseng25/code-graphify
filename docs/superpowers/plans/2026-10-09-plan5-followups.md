# Plan 5 follow-ups: rulings and deferred findings

## Rulings made during execution

- **Denormalized search counts: not done.** The search orders an already name-filtered page using indexed correlated counts. Stored counts would add row-lock contention on popular symbols under parallel writers. Revisit if search latency is measured as a problem.
- **Interrupted runs (Task 4 I1).** `execute()` writes `finish` before it restores the interrupt flag, because ojdbc11 uses NIO and JDBC fails on an interrupted thread. The coordinator in `IndexRunService` follows the same rule for its catch/finally.
- **Interrupted workers (Task 4 I2).** An interrupted worker records nothing and leaves its in-progress marker, so recovery writes the single `INTERRUPTED` row and the previous index is kept. `markStarted` is inside the try, and the connection loop stops when the coordinator is interrupted.
- **`scopeId` for ALL runs (Task 7).** `scope_id` is read with `getObject(..., Long.class)`, so ALL runs report `scopeId: null`.
- **Stale lock takeover (final review I1).** `IndexLock` keeps the set of holders acquired in this JVM. A database holder that this process does not hold is stale; it is taken over atomically, and a stale run is finished `INTERRUPTED` together with its in-flight repositories. This relies on the documented single-instance assumption.
- **Recovery before the web server (final review I2).** Startup recovery runs in `SmartInitializingSingleton.afterSingletonsInstantiated`: after Flyway and before the web server accepts requests.
- **Small fixes folded into the final wave:**
  - A skipped cleanup is logged at WARN.
  - Scheduler logs are masked.
  - A CONNECTION run re-checks that the connection is enabled.
  - `destroy()` waits for the coordinator with a bounded wait.
  - Recovery adds an outcome row only when none exists yet.
  - Rescheduling schedules the new trigger first and only then cancels the old one.
  - The interrupted-run text is a single constant.

## Carry into plan 6 (authentication and admin APIs)

- Add ADMIN role checks on `POST /index/runs` and `POST /index/runs/{id}/cancel`. Record the authenticated user as `started_by` instead of `anonymous`.
- Expose `scm_connection.last_sync_status/at/error` in the connection admin API.
- Move `IndexRunService.validate` onto the connection/repository repositories that the admin APIs introduce.
- **Lock hardening:**
  - Fence the stale-holder takeover on `acquired_at` or a generation counter. The reusable `cleanup` holder name can otherwise be taken over in a millisecond race.
  - Two concurrent takeovers of the same stale run can insert duplicate INTERRUPTED rows. Add a unique `(run_id, repo_id)` constraint, or serialize the takeover.
  - The takeover's error text reads "application stopped". Give it its own message.
- `RepositoryIndexer` catches `RuntimeException` around git, Maven and the write. An interrupt wrapped in such an exception is therefore recorded as `FAILED`/`CLONE_FAILED` instead of `INTERRUPTED`. Rethrow interruptions there.
- **Missing tests:**
  - one repository failing among others in a single `execute`
  - cancelling in the middle of a run
  - a deterministic interruption test for `indexSafely`
  - the DB-failure paths of `abandon()`
  - the success path of `runScheduledIndex`
  - `OrphanCleanupJob` releasing the lock when it throws
  - guard boundaries (exactly at the percentage; one repository gone)
  - paging and `cancelRequested` in the run API
- Run a pending orphan cleanup after an index run that blocked the weekly cron.

## Deferred (later or opportunistic)

- `indexAll` does not wait for workers after `shutdownNow`. A plain `InterruptedIOException` that is not a timeout is treated as an interruption.
- Add `VersionWarningsTest` cases: null versions, several declared versions, ordering, more than 1000 ids.
- Remove the package cycle `repository → indexing` (`RepoIndexStatus`).
