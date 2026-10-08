# Plan 17: Orphan Cleanup After Each Run, Readable Usage Tables — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stale symbols disappear right after the index run that orphaned them, and the usage tables on the symbol and impact pages fit the page with full, readable badges.

**Architecture:** `IndexRunExecutor.execute` calls the existing `SymbolCleanup.deleteOrphans()` after the indexing work and before writing the run's final status, while the run still holds the index lock. Cleanup failures are logged and never change the run's status. On the frontend, a shared `SnippetCode` component wraps long snippets, the confidence and usage-kind badges stop clipping their labels, and the usage table cells wrap long keys and paths.

**Tech Stack:** Spring Boot 4.1.1 / JDK 25 / Oracle (JdbcTemplate), React + Mantine + Vitest.

**Spec:** No new spec. This plan implements the follow-ups found while testing cross-repo impact on 2026-10-08, against the existing spec `docs/superpowers/specs/2026-10-05-impact-analyzer-design.md` §4.4 (orphan symbol cleanup). The weekly `cleanup.orphan_symbols_cron` job stays as it is.

## Background (verified on the test WildFly, 2026-10-08)

- **Stale symbols.**
  - Symbol `com.zeus.framework.correlation.CorrelationId#get/0` (BINARY, name_only=1) has no usages, no declaration and no children. It came from an earlier, partly resolved index. After Plan 16 the calls resolve EXACTLY to `#get()`, so the name-only row became an orphan.
  - The test database holds 750 such orphans. They stay until the weekly cron (Sunday 04:00).
  - Meanwhile they appear in search, and the impact view shows them as TWIN nodes.
- **Usage table layout.**
  - The usages table on `/symbols/:id` is 1486px wide in a 913px container, because the `<Code>` snippet never wraps.
  - The auto table layout then squeezes the badge columns. A Mantine Badge is `display: inline-grid` and its label has `overflow: hidden`, so its min-content width collapses: the "Tür" badge measured 1px (an empty circle) and the "Güven" badge showed "K…".
  - Tried live in the browser:
    - snippet `white-space: pre-wrap; word-break: break-word`;
    - every cell `overflow-wrap: anywhere`;
    - badge cells `white-space: nowrap`;
    - badge label `overflow: visible`.
    
    Result: the table is 913px and the badges are 125px and 58px with full labels.

## Global Constraints

- No hardcoded operational values. Cleanup batch size stays `cleanup.batch_size`. Add no new constants that look like options.
- UI is Turkish only, with English software terms. `terms.test.ts` must stay green. This plan adds no new UI text.
- Tests use temp directories only. In `~/.m2`, only `com/graphify/testfixture/*` may be written (existing `MavenFixtures.deleteFixtureGroups` cleanup).
- Commit with pathspec (`git commit <paths> -m ...`), never `git add -A`. Never touch `notes/`.
- Backend test command:
  ```
  export JAVA_HOME=/opt/homebrew/opt/openjdk@25; ./mvnw test -Dfrontend.skip=true -Dtest=<Class>
  ```
- Frontend test command: `cd frontend && npm test -- --run`.
- Commit trailer:
  ```
  Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01XpYgYKeBYFNw4tb6EwJ44V
  ```

## Review Focus

1. **Interrupted run (application shutdown).** Cleanup must not run: ojdbc fails on an interrupted thread, and the interrupt must still be restored after `finish`.
2. **Cleanup throws (e.g. ORA error).** The run keeps its computed status (SUCCESS stays SUCCESS) and the failure is logged. Tested in Task 1.
3. **Cancelled run.** Cleanup still runs: its finished repositories may have orphaned symbols. Tested in Task 1.
4. **Very long single-token snippet, symbol key or file path** (no spaces). The table must still fit its container. Covered by `overflow-wrap: anywhere` and `word-break: break-word`; the Task 2 tests assert the styles.
5. **Badges outside tables** (run detail and elsewhere) must look unchanged. Only `ConfidenceBadge` and `UsageKindBadge` change, and only their label overflow.

---

### Task 1: Delete orphan symbols at the end of every index run

**Files:**
- Modify: `src/main/java/com/graphify/indexing/IndexRunExecutor.java` (constructor, `execute`)
- Modify: `src/test/java/com/graphify/indexing/WorkspaceArtifactsTest.java:111` (constructor call)
- Modify: `src/test/java/com/graphify/indexing/IndexRunServiceFailureTest.java:30` (constructor call)
- Test: `src/test/java/com/graphify/indexing/IndexRunExecutorTest.java`
- Modify: `README.md:220-221`

**Interfaces:**
- Consumes: `com.graphify.store.SymbolCleanup` (public, `@Component`): `public int deleteOrphans()`, constructor `SymbolCleanup(JdbcTemplate, AppSettings)`.
- Produces: `IndexRunExecutor` constructor gains a trailing `SymbolCleanup cleanup` parameter:
  `IndexRunExecutor(JdbcTemplate, ScmConnections, RepositorySync, RepositoryIndexer, IndexRunRecorder, ConnectionPoolSizer, AppSettings, ModuleCoordinates, ArtifactInstaller, SymbolCleanup)`.

- [ ] **Step 1: Write the failing tests** in `IndexRunExecutorTest`. Add these fields and tests. Reuse the class's existing `start(...)`, `shop` and `executor`; read how `aRepositoryRunIndexesOnlyThatRepositoryWithoutSyncing` picks a repository id and use the same lookup.

```java
    @Autowired
    RepositoryIndexer indexer;

    @Autowired
    ConnectionPoolSizer pool;

    @Autowired
    ModuleCoordinates coordinates;

    @Autowired
    ArtifactInstaller installer;

    private void insertOrphan() {
        jdbc.update("""
                INSERT INTO symbol (symbol_key, kind, class_fqn, member_name, display_signature, origin, name_only)
                VALUES ('p.Stale#get/0', 'METHOD', 'p.Stale', 'get', 'p.Stale#get/0', 'BINARY', 1)
                """);
    }

    private int orphans() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM symbol WHERE symbol_key = 'p.Stale#get/0'", Integer.class);
    }

    @Test
    void aRunDeletesTheSymbolsNothingReferencesAnyMore() {
        insertOrphan();
        long repository = /* the same repository lookup aRepositoryRunIndexesOnlyThatRepositoryWithoutSyncing uses */;
        long run = start(RunScope.REPOSITORY, repository);

        assertThat(executor.execute(run, RunScope.REPOSITORY, repository, false)).isEqualTo(RunStatus.SUCCESS);

        assertThat(orphans()).isZero();
        // symbols the run indexed are referenced, so they stay
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM symbol", Integer.class)).isPositive();
    }

    @Test
    void aCancelledRunStillDeletesOrphans() {
        insertOrphan();
        long repository = /* same lookup */;
        long run = start(RunScope.REPOSITORY, repository);
        runs.requestCancel(run);

        assertThat(executor.execute(run, RunScope.REPOSITORY, repository, false)).isEqualTo(RunStatus.CANCELLED);

        assertThat(orphans()).isZero();
    }

    @Test
    void aFailingCleanupNeverChangesTheRunsStatus() {
        insertOrphan();
        SymbolCleanup failing = new SymbolCleanup(jdbc, settings) {
            @Override
            public int deleteOrphans() {
                throw new IllegalStateException("ORA-00060: deadlock detected");
            }
        };
        IndexRunExecutor withFailingCleanup = new IndexRunExecutor(jdbc, connections, sync, indexer, runs, pool,
                settings, coordinates, installer, failing);
        long repository = /* same lookup */;
        long run = start(RunScope.REPOSITORY, repository);

        assertThat(withFailingCleanup.execute(run, RunScope.REPOSITORY, repository, false))
                .isEqualTo(RunStatus.SUCCESS);

        assertThat(runs.status(run)).contains(RunStatus.SUCCESS);
        assertThat(orphans()).isEqualTo(1);
    }
```

Replace each `/* … lookup */` with the exact expression the existing repository-run test uses. If `requestCancel` is not public on `IndexRunRecorder`, use whatever `IndexRunApiTest.cancelsOnlyARunningRun` and `IndexRunService.cancel` use (`runs.requestCancel(runId)` is called from `IndexRunService`, so it is accessible in the package). If the type names `ConnectionPoolSizer`, `ModuleCoordinates` or `ArtifactInstaller` live in packages not yet imported, import them from the same packages as in `IndexRunExecutor.java` (`com.graphify.store.ConnectionPoolSizer`, `com.graphify.maven.ArtifactInstaller`; `ModuleCoordinates` is in `com.graphify.indexing`). Add `import com.graphify.store.SymbolCleanup;`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@25; ./mvnw test -Dfrontend.skip=true -Dtest=IndexRunExecutorTest`
Expected: compilation failure (the constructor has no `SymbolCleanup` parameter yet).

- [ ] **Step 3: Implement it in `IndexRunExecutor`**

Add the import `com.graphify.store.SymbolCleanup`, a field `private final SymbolCleanup cleanup;`, and the trailing constructor parameter (assign it). In `execute`, between the `if (Thread.interrupted() && !interrupted) { … }` block and the `try { runs.finish(…) }` block, insert:

```java
        if (!interrupted) {
            deleteOrphans(runId);
        }
```

and add this method next to `execute`:

```java
    /**
     * Symbols this run stopped referencing (a name-only guess now resolved, a removed method) are deleted while the
     * run still holds the index lock and every write is done (spec §4.4), so search and impact never show them. A
     * failure only logs: the weekly cleanup.orphan_symbols_cron job removes them later.
     */
    private void deleteOrphans(long runId) {
        try {
            int deleted = cleanup.deleteOrphans();
            if (deleted > 0) {
                log.info("Index run {} deleted {} orphan symbols", runId, deleted);
            }
        } catch (RuntimeException e) {
            log.warn("Index run {} could not delete orphan symbols: {}", runId, masked(e));
        }
    }
```

(`masked(e)` already exists in the class and is used for the run error.)

Update the two other constructor calls:
- `WorkspaceArtifactsTest.java:111`: append `, cleanup` after `installer`-equivalent last argument. Add `@Autowired SymbolCleanup cleanup;` to that test class if it is a Spring test (it extends `OracleIntegrationTest`), otherwise `new SymbolCleanup(jdbc, settings)`.
- `IndexRunServiceFailureTest.java:30`: append one more `null` argument.

- [ ] **Step 4: Run the tests to verify they pass**

Run:
```
export JAVA_HOME=/opt/homebrew/opt/openjdk@25; ./mvnw test -Dfrontend.skip=true -Dtest='IndexRunExecutorTest,WorkspaceArtifactsTest,IndexRunServiceFailureTest,IndexRunServiceTest,IndexRunExecutorInterruptionTest,OrphanCleanupJobTest'
```
Expected: all pass.

- [ ] **Step 5: Update the README.** In `README.md` lines 220-221, after the sentence about `cleanup.orphan_symbols_cron`, add: `Every index run also deletes the orphan symbols it leaves behind before it ends, so the weekly cleanup only catches what a failed run left.` Keep the surrounding wording.

- [ ] **Step 6: Run the full backend suite**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@25; ./mvnw test -Dfrontend.skip=true`
Expected: BUILD SUCCESS. Some existing tests may count symbols after a run and relied on orphans surviving. If one fails for that reason, fix its expectation and explain it in the report. Do not weaken the cleanup.

- [ ] **Step 7: Commit**

```bash
git commit src/main/java/com/graphify/indexing/IndexRunExecutor.java \
  src/test/java/com/graphify/indexing/IndexRunExecutorTest.java \
  src/test/java/com/graphify/indexing/WorkspaceArtifactsTest.java \
  src/test/java/com/graphify/indexing/IndexRunServiceFailureTest.java README.md \
  -m "feat(indexing): delete orphan symbols at the end of every index run" \
  -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01XpYgYKeBYFNw4tb6EwJ44V"
```
(Add any other test file you had to adjust to the pathspec.)

---

### Task 2: Usage tables fit the page with full badges

**Files:**
- Create: `frontend/src/components/SnippetCode.tsx`
- Create: `frontend/src/components/SnippetCode.test.tsx`
- Modify: `frontend/src/components/Badges.tsx` (`ConfidenceBadge`, `UsageKindBadge`)
- Modify: `frontend/src/features/symbol/UsagesTable.tsx`
- Modify: `frontend/src/features/impact/ImpactResultView.tsx` (location table, around lines 185-210)
- Test: `frontend/src/features/symbol/symbol.test.tsx`

**Interfaces:**
- Produces: `export function SnippetCode({ children }: { children: string })` and `export const WRAP_CELL = { overflowWrap: 'anywhere' } as const;` plus `export const BADGE_CELL = { whiteSpace: 'nowrap' } as const;`. All three are exported from `frontend/src/components/SnippetCode.tsx`.

- [ ] **Step 1: Write the failing tests**

`frontend/src/components/SnippetCode.test.tsx`. Follow the render helper the other component tests use: check `Pager.test.tsx` for how it wraps in `MantineProvider`, and copy that pattern exactly.

```tsx
import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { SnippetCode } from './SnippetCode';
// + the same render helper import as Pager.test.tsx

describe('SnippetCode', () => {
  it('wraps a long snippet instead of widening its table', () => {
    renderWithMantine(<SnippetCode>{'CorrelationId.get()'.repeat(20)}</SnippetCode>);
    const code = screen.getByText('CorrelationId.get()'.repeat(20));
    expect(code.style.whiteSpace).toBe('pre-wrap');
    expect(code.style.wordBreak).toBe('break-word');
  });
});
```

In `frontend/src/features/symbol/symbol.test.tsx`, add a test next to the existing usages-table test. It reuses that test's mocked usage response, which must contain a `snippet` and `kind: 'CALL'`, `confidence: 'EXACT'`.

```tsx
  it('keeps the kind and confidence badges whole and wraps the long cells', async () => {
    // render the symbol page / UsagesTable exactly like the existing usages test does
    const kind = await screen.findByText('Method çağrısı');
    const confidence = screen.getAllByText('Kesin')[0];
    expect(kind.closest('td')?.style.whiteSpace).toBe('nowrap');
    expect(confidence.closest('td')?.style.whiteSpace).toBe('nowrap');
    expect(kind.style.overflow || kind.closest('.mantine-Badge-label')?.getAttribute('style')).toContain('visible');
    const snippet = screen.getByText(/* the mocked snippet text */);
    expect(snippet.closest('td')?.style.overflowWrap).toBe('anywhere');
  });
```

If "Kesin" also appears in a filter or summary, scope the query to the table with `within(screen.getByRole('table', …))`. Adapt to the existing test's structure, but keep the four assertions:
1. kind cell nowrap;
2. confidence cell nowrap;
3. badge label overflow visible;
4. snippet cell `overflow-wrap: anywhere`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd frontend && npm test -- --run src/components/SnippetCode.test.tsx src/features/symbol/symbol.test.tsx`
Expected: FAIL (module `./SnippetCode` not found; style assertions fail).

- [ ] **Step 3: Implement**

`frontend/src/components/SnippetCode.tsx`:

```tsx
import { Code } from '@mantine/core';

/** A table cell that wraps long symbol keys and paths instead of widening the table. */
export const WRAP_CELL = { overflowWrap: 'anywhere' } as const;

/** A table cell holding a badge: never wrapped, so the column keeps the badge's full width. */
export const BADGE_CELL = { whiteSpace: 'nowrap' } as const;

/** A source snippet in a table: wraps (keeping indentation) so a long line never pushes the table off the page. */
export function SnippetCode({ children }: { children: string }) {
  return <Code style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{children}</Code>;
}
```

`frontend/src/components/Badges.tsx`: change only these two:

```tsx
/**
 * Mantine clips a badge label (overflow: hidden), which lets an auto-layout table squeeze it to an empty pill; these
 * two sit in usage tables, so their label is never clipped.
 */
const UNCLIPPED = { label: { overflow: 'visible' } } as const;

export function ConfidenceBadge({ value }: { value?: string }) {
  return <Badge variant="light" color={CONFIDENCE_COLOR[value ?? ''] ?? 'gray'} styles={UNCLIPPED}>{enumLabel(tr.enums.confidence, value)}</Badge>;
}

export function UsageKindBadge({ value }: { value?: string }) {
  return <Badge variant="outline" styles={UNCLIPPED}>{enumLabel(tr.enums.usageKind, value)}</Badge>;
}
```

`frontend/src/features/symbol/UsagesTable.tsx`: import `{ BADGE_CELL, SnippetCode, WRAP_CELL }` from `'../../components/SnippetCode'`, and drop `Code` from the `@mantine/core` import if it becomes unused. Then change the body row to:

```tsx
                <Table.Tr key={usage.id}>
                  <Table.Td style={WRAP_CELL}><SymbolLink symbol={usage.from} /></Table.Td>
                  <Table.Td style={BADGE_CELL}><UsageKindBadge value={usage.kind} /></Table.Td>
                  <Table.Td style={BADGE_CELL}><ConfidenceBadge value={usage.confidence} /></Table.Td>
                  <Table.Td style={WRAP_CELL}>
                    <Text size="sm">{usage.modulePath ? `${formatRepository(usage.repository)} · ${usage.modulePath}` : formatRepository(usage.repository)}</Text>
                    <Text size="xs" c="dimmed">{formatLocation(usage.filePath, usage.line)}</Text>
                  </Table.Td>
                  <Table.Td style={WRAP_CELL}>{usage.snippet && <SnippetCode>{usage.snippet}</SnippetCode>}</Table.Td>
                </Table.Tr>
```

`frontend/src/features/impact/ImpactResultView.tsx`: same idea for the location table rows. Do the same import, and drop `Code` from the Mantine import only if nothing else in the file uses it.

```tsx
                      <Table.Tr key={`${page}-${index}`}>
                        <Table.Td style={WRAP_CELL}>
                          <SymbolLink symbol={{ id: edge.fromSymbolId, key: from?.key, display: from?.display }} />
                        </Table.Td>
                        <Table.Td style={BADGE_CELL}><UsageKindBadge value={edge.kind} /></Table.Td>
                        <Table.Td style={BADGE_CELL}><ConfidenceBadge value={edge.confidence} /></Table.Td>
                        <Table.Td style={WRAP_CELL}>{repoModule(edge.repository, edge.modulePath)}</Table.Td>
                        <Table.Td style={WRAP_CELL}>{formatLocation(edge.filePath, edge.line)}</Table.Td>
                        <Table.Td style={WRAP_CELL}>{edge.snippet ? <SnippetCode>{edge.snippet}</SnippetCode> : tr.common.none}</Table.Td>
                      </Table.Tr>
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd frontend && npm test -- --run && npm run lint --if-present && npx tsc --noEmit -p .`
Expected: all green (including `terms.test.ts`).

- [ ] **Step 5: Commit**

```bash
git commit frontend/src/components/SnippetCode.tsx frontend/src/components/SnippetCode.test.tsx \
  frontend/src/components/Badges.tsx frontend/src/features/symbol/UsagesTable.tsx \
  frontend/src/features/impact/ImpactResultView.tsx frontend/src/features/symbol/symbol.test.tsx \
  -m "fix(ui): usage tables wrap long snippets and keep their badges whole" \
  -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01XpYgYKeBYFNw4tb6EwJ44V"
```

---

## After the tasks (controller)

1. Final whole-branch review.
2. Merge menu.
3. On the user's request, deploy to the test WildFly. There, check:
   - after one forced run, `CorrelationId#get/0` is gone from search;
   - the orphan count is 0;
   - with Playwright, the usages table on `/graphify/symbols/8833` is no wider than its container and the badges read "Method çağrısı" and "Kesin".
