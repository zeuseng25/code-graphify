# Plan 2 follow-ups (carry into plans 3–5)

From the plan 2 subagent-driven run: controller rulings and deferred review findings.

## Plan 4 must handle
- Retry the whole `RepositoryIndexWriter.replace()` once on `PessimisticLockingFailureException` (rare ORA-00060 in the LINK_PARENT phase, and conflicts beyond the parallelism bound).
- Optionally commit `SymbolWriter.upsert` in its own short transaction, for first-run throughput and less lock pressure.
- Size the Hikari pool from `index.parallelism` (the default pool is 10; the setting allows up to 32).
- Run `SymbolCleanup.deleteOrphans` under the index lock; chunk it with a batch size from AppSettings; capture EXPLAIN at scale.
- `MODULE_DEPENDENCY` FK: delete dependency rows before ModuleWriter removes stale modules, or add ON DELETE CASCADE. Removing a repository must delete usage, declarations and modules in order.
- The first `SettingChangedEvent` listener must not let exceptions escape `update()` after commit.

## Plan 3
- Consider a covering index `usage(to_symbol_id, kind, from_symbol_id, module_id)` for index-only BFS.
- Add an end-to-end test of NAME_ONLY nested receivers (`org.vendor.Client.Builder` → `Client$Builder`).

## Plan 5
- Settings cache generation counter (AtomicLong + compareAndSet; invalidate in afterCompletion) before the admin API exposes concurrent updates.

## Rulings
- Ruling: fix it — invalidate the cache and publish the event in an afterCommit TransactionSynchronization (or immediately if no transaction is active); update() returns the row read fresh from the repository, not from the cache — spec §6.4 says a change must take effect without a restart, and this was my plan's defect — if wrong: a few ms longer before a change becomes visible
- Ruling: fix it — truncate groupId (300 B), artifactId (300 B) and version (100 B) with Utf8; reject in validate a path > 1000 B or a commit > 64 B; inject the rollback-test failure with a test-only Oracle trigger on usage (RAISE_APPLICATION_ERROR when file_path = 'boom.java'), assert DataAccessException with "injected" and unchanged usage/declaration/commit; make rejectsInconsistentInput write a valid index first; fix the ModuleWriter Javadoc — global constraint "strings are cut to the column's byte width"; my plan used the overflow itself as the failure injection — if wrong: a truncated version string in MAVEN_MODULE (shown truncated in the UI)
- Ruling: I1 — the retry bound comes from the index.parallelism setting (maxConcurrentWriters + 1), passed into SymbolWriter.upsert; 4-writer barrier test — each ORA-00001 implies another writer committed, so parallelism is the true bound; also removes a hardcoded tuning value — if wrong: a pathological chain of more than parallelism conflicts fails one repo for one night
- Ruling: I2 — cleanup keeps symbols that still have children (NOT EXISTS child.parent_id); per-predicate tests — the owner type must survive while members are referenced — if wrong: parent symbols linger one extra cleanup cycle
- Ruling: fold into the same wave: M3 deadlock comment, M4 SOURCE-equal refresh when kind/display change, M5 settings value/actor width checks, M6 identity CACHE 1000, M7 TIMESTAMP WITH TIME ZONE (edit V1 in place — never released), M8 AL32UTF8 startup check, M9 schema test rename + real usage-kind test, M10 drop the Base64 cause — all cheap, and expensive to change once real data exists — if wrong: slightly larger fix diff
- Ruling: defer to later plans — cache generation counter (plan 5), whole-replace retry on lock failure, separate symbol transaction, Hikari sizing, MODULE_DEPENDENCY FK, chunked orphan delete (plan 4), covering BFS index (plan 3) — none blocks plan 2's contract — if wrong: plan 4/5 inherit these as first tasks (recorded in the followups doc)

## Deferred minors
- Task 1: minor (deferred): no end-to-end test of the NAME_ONLY effect of binaryName (e.g. org.vendor.Client.Builder receiver → Client$Builder)
- Task 1: minor (deferred): binaryName/looksQualified duplicate the first-uppercase scan
- Task 1: minor (deferred, pre-existing): a static-field receiver `a.B.INSTANCE.foo()` is keyed as nested type a.B$INSTANCE
- Task 2: minor (deferred, plan-mandated): SchemaMigrationTest.rejectsUnknownUsageKinds actually tests symbol.kind — rename or add a usage.kind case
- Task 2: minor (deferred): index column order not asserted
- Task 2: minor (deferred → plan 4): FKs have no ON DELETE; removing a repo must delete usage/declaration/module in order (or add cascade)
- Task 3: minor (deferred): RED was simulated by moving sources aside
- Task 3: minor (deferred): values re-parsed on every get
- Task 3: minor (deferred): rare cache poisoning — an in-tx read with a cold cache (after a concurrent commit) caches an uncommitted value; a stale reader can overwrite the cleared cache. A generation counter would close both
- Task 3: minor (deferred): a listener exception in afterCommit surfaces from update() after a successful commit
- Task 4: minor (deferred): Base64 IAE cause exposes one character of a malformed key in the startup stack trace
- Task 4: minor (deferred): null plaintext/key → NPE; key bytes not zeroed
- Task 5: minor (deferred → final review; matters for plan 4 parallelism): deadlock window in the LINK_PARENT phase (ORA-00060), and the comment "cannot deadlock" overstates the guarantee
- Task 5: minor (deferred, plan-mandated): retry cap of 3 can run out with >3 concurrent writers; arguably a tuning value
- Task 5: minor (deferred): concurrency test depends on timing (no barrier); no equal-rank test; skip test covers only a long key
- Task 6: minor (deferred → plan 4): full delete+reinsert per write is heavy at scale; repos sharing any symbol key serialize on MERGE row locks (throughput for index.parallelism)
- Task 6: minor (deferred): ModuleWriter MERGE not chunked
- Task 7: minor (deferred → plan 4): the orphan DELETE is a single statement in a single transaction; chunk it with a batch size from AppSettings and capture EXPLAIN at scale
- Task 7: minor (deferred): cleanup test doesn't prove each NOT EXISTS predicate individually; warm-up call comment is cryptic
- Final: minor (deferred → plan 4): I1 Javadoc bound wording overstates the guarantee (a new transaction started during a chunk's wait can add one more conflict); plan 4's whole-replace() retry absorbs it
- Final: minor (deferred): the 4-writer test cannot tell cap 3 from cap 5 (lock-step chunks) — a discriminating test needs interleaved writer keys; barrier/invokeAll have no timeout
- Final: minor (deferred → plan 4): M4 refresh makes the row flip-flop between repos that declare the same FQN differently (last writer wins)
