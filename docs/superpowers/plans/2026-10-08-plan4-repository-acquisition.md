# Plan 4: Repository Acquisition and Per-Repository Indexing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Index any repository a Bitbucket Data Center connection can see, end to end. The pipeline lists the repositories, clones or updates them, reads their Maven modules and dependencies, resolves each module's classpath, indexes the code and writes the result. Each run is recorded per repository with the statuses in spec §8.

**Architecture:**
- **SCM listing.** `ScmClient` has one implementation, `BitbucketDataCenterClient`. It uses Spring's `RestClient` over the JDK `HttpClient` and lists accessible repositories page by page. Include and exclude filters, retries and timeouts all come from settings.
- **Workspace.** `GitWorkspace` (JGit) reads the remote head with `ls-remote`. It shallow-clones a repository or fetches and hard-resets it, and on corruption it deletes the workspace and clones once more.
- **Maven model.** `MavenProjectReader` parses the pom tree with a hardened DOM parser. It handles modules, inherited coordinates, properties, managed versions and source roots.
- **Classpath.** `ClasspathResolver` runs Maven once per repository with a generated `settings.xml`:
  ```
  compile dependency:build-classpath -Dmaven.main.skip -Dmaven.resources.skip -Dmaven.test.skip -fae
  ```
  - A spike confirmed this resolves sibling modules through `target/classes` without an install, even when sources don't compile.
  - A module whose resolution fails gets `NONE` without affecting the others.
- **Orchestration.** `RepositoryIndexer` combines these steps for one repository. `IndexRunRecorder` writes `INDEX_RUN` and `INDEX_RUN_REPO`. Running many repositories in parallel, the lock and the scheduler come in plan 5.
- **Plan 3 contract fix.** Task 1 first closes the plan-3 contract gap: name-only twins become `TWIN` nodes, so every edge endpoint is a node.

**Tech Stack:**
- Java 25, Spring Boot 4.1.1 (`RestClient`, `JdkClientHttpRequestFactory`)
- JGit `7.8.0.202609011348-r`
- Apache Maven 3.9.x on the host, invoked as a process
- Oracle with Flyway; Testcontainers
- JUnit 5 and AssertJ; the JDK `com.sun.net.httpserver` for a fake Bitbucket

**Spec:** `docs/superpowers/specs/2026-10-05-impact-analyzer-design.md`, covering:
- §3.2 steps 1–8
- §4.1 `MODULE_DEPENDENCY`, `INDEX_RUN`, `INDEX_RUN_REPO`
- §6.2 `ARTIFACT_REPOSITORY`
- §8 error rows for SCM, clone, project type, classpath and write
- §11 rows for the Bitbucket client, Maven classpath and end-to-end acceptance

Carried items: `docs/superpowers/plans/2026-10-07-plan3-followups.md` ("Fix first"), plus the plan-2 follow-ups "MODULE_DEPENDENCY FK" and "retry the whole replace() once".

## Plan series (renumbered)

| Plan | Scope | Status |
|---|---|---|
| 1 | Indexer | merged |
| 2 | Persistence/settings | merged |
| 3 | Search/impact/API | merged |
| **4** | **Repository acquisition and per-repository indexing** | this plan |
| 5 | Orchestration: index runs, `INDEX_LOCK`, parallel workers (`index.parallelism`, Hikari sizing), cancel and INTERRUPTED recovery, cron scheduler with reschedule on `SettingChangedEvent`, orphan cleanup under the lock (chunked), `/index/runs` and `/repositories/{id}/runs` endpoints, version warnings from `MODULE_DEPENDENCY`, denormalized search counts, read-only snapshot for impact | next |
| 6 | Authentication (LDAP, local admin, roles) and admin APIs, including SCM connections, artifact repositories and settings | later |
| 7 | Repo graph view | later |

## Global Constraints

- **JDK and tools.**
  - JDK 25: every command runs with `export JAVA_HOME=/opt/homebrew/opt/openjdk@25`.
  - Docker must be running, and the `gvenzl/oracle-free:23-slim-faststart` image is already pulled.
  - `mvn` (Apache Maven 3.9.x) and its default plugins must be on the PATH. Tests that run Maven use the developer's `~/.m2/repository` as the local repository so cached plugins are reused; the first run may download plugins.
- **Dependencies.**
  - JGit is `org.eclipse.jgit:org.eclipse.jgit:${jgit.version}` with `jgit.version` = `7.8.0.202609011348-r`. It is not in the Boot BOM, so it is pinned.
  - Every other version comes from the Boot 4.1.1 BOM.
- **"Kodda sabit değer yok" (no hardcoded values in code).** Every operational value comes from `AppSettings`:
  - `index.workspace_dir`, `index.maven_executable`, `index.maven_local_repository`, `index.maven_timeout`, `index.maven_output_tail_lines`, `index.git_depth`, `index.git_timeout`, `index.source_roots`, `index.parse_batch_size`
  - `usage.snippet_max_length`
  - `scm.page_size`, `scm.retry_count`, `scm.retry_backoff`, `scm.connect_timeout`, `scm.read_timeout`

  New keys are seeded only in `V4__repository_acquisition.sql`. Fixed protocol facts are named constants with a comment:
  - the Bitbucket REST paths
  - the classpath output file name
  - the Maven flags
- **Credentials.**
  - Credentials come only from `scm_connection` and `artifact_repository`, decrypted with `SecretCipher`.
  - Secrets never appear in logs, exception messages, `INDEX_RUN_REPO.error`, `toString()` or test output.
  - Any text that may contain a URL goes through `UrlMasking.mask`, which turns `scheme://user:pass@host` into `scheme://***@host`.
- **Authorization header (REST and git over HTTP).** With a blank username, send `Bearer <secret>`. Otherwise send `Basic base64(username:secret)`. `AuthorizationHeader.of(username, secret)` builds it. If the secret is null, no header is sent.
- **Repository statuses are exactly spec §8:** `SUCCESS`, `SUCCESS_PARTIAL`, `FAILED`, `CLONE_FAILED`, `SKIPPED_UNCHANGED`, `SKIPPED_NOT_JAVA`, `INTERRUPTED`.
  - The classpath mode of a module is `FULL` when Maven produced its classpath file, and `NONE` otherwise.
  - The repository-level `classpath_mode` in `INDEX_RUN_REPO` is `FULL` when every module with sources is FULL, `NONE` when none is, and `PARTIAL` otherwise.
  - A repository whose source-bearing modules are not all FULL ends `SUCCESS_PARTIAL`.
- **Rules that come from the spec.**
  - A failure in one repository never deletes its previous index. `RepositoryIndexWriter.replace` stays the only writer of index rows, and it is retried once on `PessimisticLockingFailureException`.
  - A repository that has disappeared from SCM is deactivated (`active = 0`), and `RepositoryIndexWriter.remove` deletes its index rows. Its `INDEX_RUN_REPO` history is kept.
- **Schema.** New schema lives only in `V4__repository_acquisition.sql`. V1–V3 are merged and must not be edited. Every text column is `VARCHAR2(n BYTE)`, and timestamps are `TIMESTAMP WITH TIME ZONE`.
- **Feature packages.**
  - `com.graphify.scm`: connections, the client, sync, auth header, URL masking
  - `com.graphify.workspace`: git
  - `com.graphify.maven`: pom model, settings.xml, classpath
  - `com.graphify.indexing`: one-repository pipeline, run recorder
- **Tests.**
  - Oracle-backed tests extend `com.graphify.OracleIntegrationTest`.
  - Tests that change settings restore them in `finally` or `@AfterEach`.
  - Git test repositories are local bare repositories reached by `file://` URLs.
- **Commits** end with the Co-Authored-By trailer the committing agent's harness provides.

## Review Focus

1. **Bitbucket returns 5xx, rejects the token, or pages its results.** Expect a retry with backoff on 5xx, a non-retried `ScmAuthenticationException` on 401/403, and every page read. Tests: Task 3, `BitbucketDataCenterClientTest`.
2. **A workspace directory is corrupt or half-deleted** (a crash mid-clone, or a manual `rm .git/index`). Expect it to be deleted and cloned again once, and the run to continue. Test: Task 5, `GitWorkspaceTest.corruptWorkspaceIsRecloned`.
3. **A multi-module project whose modules depend on each other has never been `install`ed.** Each module must still get its classpath. A module with an unresolvable dependency gets `NONE` without hiding the others' classpaths. Tests: Task 7, `ClasspathResolverTest`.
4. **Maven hangs** (a network stall, a plugin waiting for input). Expect it to be killed at `index.maven_timeout`, the modules to be `NONE`, and the run to continue. Test: Task 7, `ClasspathResolverTest.hungMavenIsKilledAtTheTimeout`.
5. **A token embedded in a URL or used in a header must never leak** into an exception message, `INDEX_RUN_REPO.error` or a log. Tests: Task 3 `UrlMaskingTest`; Task 5 `GitWorkspaceTest.failuresNeverExposeCredentials`; Task 8 `RepositoryIndexerTest.cloneFailureIsRecordedWithoutCredentials`.

---

## File Structure

| File | Responsibility |
|---|---|
| `impact/NodeRole.java`, `ImpactEngine.java`, `EntryPointFinder.java`, `README.md` (modify) | TWIN nodes; document API fields (plan-3 carry-over) |
| `db/migration/V4__repository_acquisition.sql` | `artifact_repository`, `module_dependency`, `index_run`, `index_run_repo`; new settings seeds |
| `store/DependencyRecord.java`, `ModuleRecord.java`, `ModuleWriter.java`, `RepositoryIndexWriter.java` (modify) | Persist declared dependencies; `remove(repositoryId)` |
| `settings/SettingKeys.java` (modify) | New keys |
| `scm/ScmType.java`, `ScmConnection.java`, `ScmConnections.java`, `RemoteRepository.java`, `ScmClient.java`, `ScmException.java`, `ScmAuthenticationException.java`, `AuthorizationHeader.java`, `UrlMasking.java`, `RepositoryFilter.java`, `BitbucketDataCenterClient.java` | Connections and Bitbucket DC listing |
| `scm/RepositorySync.java` | Upsert/deactivate `scm_repository` rows from a listing |
| `workspace/RemoteHead.java`, `GitException.java`, `GitWorkspace.java` | ls-remote, clone, fetch/reset, recovery |
| `maven/MavenModule.java`, `MavenProject.java`, `MavenProjectReader.java` | pom tree → modules, coordinates, dependencies, source roots |
| `maven/ArtifactRepository.java`, `ArtifactRepositories.java`, `SettingsXmlWriter.java`, `ClasspathResult.java`, `ClasspathResolver.java` | settings.xml and Maven classpath resolution |
| `indexing/RepoIndexStatus.java`, `RepoIndexOutcome.java`, `IndexRunRecorder.java`, `RepositoryIndexer.java` | One-repository pipeline and run records |
| test: `testsupport/FakeBitbucket.java`, `testsupport/GitFixtures.java`, `testsupport/MavenFixtures.java` | Fake SCM, bare git repos, file-based Maven repository |

All main paths are under `src/main/java/com/graphify/` unless they start with `db/`, which is `src/main/resources/db/migration/`.

---

### Task 1: TWIN nodes (plan-3 contract fix) and API documentation

**Files:**
- Modify: `src/main/java/com/graphify/impact/NodeRole.java`
- Modify: `src/main/java/com/graphify/impact/ImpactEngine.java`
- Modify: `src/main/java/com/graphify/impact/EntryPointFinder.java`
- Modify: `README.md`
- Test: `src/test/java/com/graphify/impact/ImpactEngineTest.java`, `ImpactCsvTest.java` (add tests; adjust any assertion that expected a seed twin to be `SEED`)

**Interfaces:**
- **Consumes.** The current engine is in the state plan 3's final fix wave left it:
  - `Search` holds `levels`, `dispatchLevels`, `seeds`, `expanded`, `confidences` and `found`.
  - `seeds(...)` adds each callable's twin.
  - `dispatchTargets(...)` adds the twins of dispatch targets with `addDispatchTarget`.
  - `withTwins(search, next)` adds the twins of the frontier.
  - `assemble` builds nodes from `levels` and `dispatchLevels`.
- **Produces.**
  - `NodeRole` gains `TWIN`.
  - Every name-only twin the engine adds (of a seed, a frontier node or a dispatch target) is emitted as an `ImpactNode` with role `TWIN`, `nameOnly = true`, the level of the node it stands for, and that node's confidence.
  - Every edge's `fromSymbolId` and `toSymbolId` is now a node id.
  - TWIN nodes are excluded from the AFFECTED counts (`classes`, `methods`, `nodesByConfidence`) and from entry-point detection.
  - A symbol the user requested directly stays `SEED`, even if it is name-only.

- [ ] **Step 1: Write the failing tests**

Append to `src/test/java/com/graphify/impact/ImpactEngineTest.java`, inside the class. If any constant or helper you need is not already there, add these static imports: `SymbolKind.METHOD`, `UsageKind.CALL`, `UsageKind.OVERRIDES`, `Confidence.EXACT`, `Confidence.NAME_ONLY`.

```java
    @Test
    void everyEdgeEndpointIsANodeAndTwinsAreMarked() {
        long format = graph.symbol("lib.F#format(int)", METHOD, null);
        long formatTwin = graph.symbol("lib.F#format/1", METHOD, null);
        long label = graph.symbol("api.S#label(int)", METHOD, null);
        long labelTwin = graph.symbol("api.S#label/1", METHOD, null);
        long iface = graph.symbol("lib.G#charge(int)", METHOD, null);
        long ifaceTwin = graph.symbol("lib.G#charge/1", METHOD, null);
        long card = graph.symbol("api.Card#charge(int)", METHOD, null);
        long legacy = graph.symbol("old.R#print()", METHOD, null);
        long legacyLabel = graph.symbol("old.R#label()", METHOD, null);
        long legacyCharge = graph.symbol("old.R#charge()", METHOD, null);
        graph.usage(label, format, CALL, EXACT, MODULE);
        graph.usage(legacy, formatTwin, CALL, NAME_ONLY, LEGACY);
        graph.usage(legacyLabel, labelTwin, CALL, NAME_ONLY, LEGACY);
        graph.usage(card, iface, OVERRIDES, EXACT, MODULE);
        graph.usage(legacyCharge, ifaceTwin, CALL, NAME_ONLY, LEGACY);

        ImpactResult byFormat = run(List.of(format), null, 2, null, null);
        ImpactResult byCard = run(List.of(card), null, 1, null, null);

        for (ImpactResult result : List.of(byFormat, byCard)) {
            Set<Long> nodeIds = result.nodes().stream().map(ImpactNode::symbolId).collect(java.util.stream.Collectors.toSet());
            assertThat(result.edges()).allSatisfy(e -> {
                assertThat(nodeIds).contains(e.fromSymbolId());
                assertThat(nodeIds).contains(e.toSymbolId());
            });
        }
        assertThat(byFormat.nodes()).extracting(ImpactNode::key, ImpactNode::role, ImpactNode::level, ImpactNode::nameOnly)
                .contains(
                        tuple("lib.F#format/1", NodeRole.TWIN, 0, true),
                        tuple("api.S#label/1", NodeRole.TWIN, 1, true));
        assertThat(byCard.nodes()).extracting(ImpactNode::key, ImpactNode::role)
                .contains(tuple("lib.G#charge/1", NodeRole.TWIN));
        assertThat(byFormat.summary().methods()).isEqualTo(3);
    }

    @Test
    void aRequestedNameOnlySymbolStaysASeed() {
        long twin = graph.symbol("lib.F#format/1", METHOD, null);
        long legacy = graph.symbol("old.R#print()", METHOD, null);
        graph.usage(legacy, twin, CALL, NAME_ONLY, LEGACY);

        assertThat(run(List.of(twin), null, 1, null, null).nodes())
                .extracting(ImpactNode::key, ImpactNode::role)
                .contains(tuple("lib.F#format/1", NodeRole.SEED));
    }
```

The `methods() == 3` expectation counts AFFECTED callables only: `label` (level 1), `print` (level 1, reached via the seed twin) and `legacyLabel` (level 2, reached via `label`'s twin). Twins and seeds are not counted.

Append to `src/test/java/com/graphify/impact/ImpactCsvTest.java`, inside the class:

```java
    @Test
    void twinTargetsShowTheirDisplayInsteadOfAnId() {
        ImpactNode twin = new ImpactNode(5, "lib.F#format/1", SymbolKind.METHOD, "F.format(1 args)", 0, NodeRole.TWIN,
                Confidence.EXACT, true);
        ImpactNode caller = new ImpactNode(6, "old.R#print()", SymbolKind.METHOD, "R.print()", 1, NodeRole.AFFECTED,
                Confidence.NAME_ONLY, false);
        ImpactEdge edge = new ImpactEdge(6, 5, UsageKind.CALL, Confidence.NAME_ONLY, 1, false, 7, null, "legacy",
                "R.java", 3, 1, "f.format(5);");
        ImpactResult result = new ImpactResult(new ImpactSummary(1, 1, 1, 1, 1, Map.of(), Map.of(), Map.of(), List.of()), List.of(twin, caller), List.of(edge),
                List.of(), List.of(), List.of(), false);

        assertThat(ImpactCsv.write(result)).contains(",R.print(),CALL,F.format(1 args),NAME_ONLY,");
    }
```

The summary construction matches the existing tests in `ImpactCsvTest`. Add imports for `ImpactSummary`, `NodeRole`, `Confidence`, `SymbolKind`, `UsageKind` and `java.util.Map` if they are missing.

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -Dtest='ImpactEngineTest,ImpactCsvTest'`
Expected: the tests compile and FAIL. Twin nodes are missing (`toSymbolId` is not in the node ids). `NodeRole.TWIN` is not yet defined, so the build fails at compile time first. Add the enum constant (Step 3) and then rerun to see the assertion failures.

- [ ] **Step 3: Implement TWIN nodes**

1. In `NodeRole.java`, add `TWIN`. Replace the Javadoc with:
   `SEED: what is changing. AFFECTED: reached by the BFS. DISPATCH: an overridden method whose callers were followed. TWIN: the name-only key (Class#name/argCount) standing for calls the indexer could not bind to a SEED, AFFECTED or DISPATCH node; it has that node's level and confidence.`
2. In `ImpactEngine.Search`, add `final Set<Long> twins = new HashSet<>();` and `final Map<Long, Integer> twinLevels = new LinkedHashMap<>();`.
3. Record every twin the engine adds:
   - In `seeds(...)`, every `twin` that is added for a non-null owner and is not itself requested. Do this after `Search` is created: iterate the seeds again and mark the ids that were added as twins of another seed. Simplest approach: `seeds(...)` returns the twin ids through an extra `Set<Long> twinIds` parameter, and `analyze` copies them into `search.twins` with level 0.
   - In `dispatchTargets(...)`, when `addDispatchTarget(search, twin, ...)` adds a twin, do `search.twins.add(twin)`.
   - Change `withTwins(search, next)` to `withTwins(search, next, level)`, and pass the current `level`. When a twin is added, also do `search.twins.add(twin); search.twinLevels.putIfAbsent(twin, level);`.
4. In `assemble`:
   - Add `search.twinLevels.keySet()` to `ids` before `graph.symbols(ids)`.
   - Role for ids in `levels`: `search.twins.contains(id) ? TWIN : search.seeds.contains(id) && !search.twins.contains(id) ? SEED : AFFECTED`. In other words, twin first, then seed, otherwise affected.
   - Role for ids only in `dispatchLevels`: `search.twins.contains(id) ? TWIN : DISPATCH`.
   - Ids only in `twinLevels` become `TWIN` nodes at their recorded level.
   - Every TWIN node takes `search.confidenceOf(id)` and `nameOnly = true`.
5. `summary(...)` already counts only `AFFECTED`. Keep it that way.
6. In `EntryPointFinder`, change `if (node.role() != NodeRole.DISPATCH && ...)` to `if ((node.role() == NodeRole.SEED || node.role() == NodeRole.AFFECTED) && ...)`.

- [ ] **Step 4: Document the API fields in `README.md`**

In the `## API` section, add after the table:

```markdown
Impact results (`POST /impact`):

- `nodes[]`: `role` is `SEED` (what you changed), `AFFECTED`, `DISPATCH` (an overridden method whose callers were followed) or `TWIN` (the name-only key `Class#name/argCount` standing for calls the indexer could not bind); `confidence` is the weakest edge on the path that reached the node; `nameOnly` marks twins.
- `edges[]`: every `fromSymbolId`/`toSymbolId` is a node; `repository` is `{id, projectKey, slug}`.
- `entryPoints[]`: `http` is true for request mappings; `httpPath` is null when the path is not a plain literal (never guessed).
- `summary`: counts cover `AFFECTED` nodes; `usagesByConfidence` and `nodesByConfidence` always list all three confidences; `usagesByLevel` replaces the spec's `levels[]`.
- `staleness[]` lists each affected module's last indexed commit; `versionWarnings[]` is filled from plan 5 on.
- A lone search word (`charge`) matches type names and member names; with `kind=` it matches either within that kind.
- Field reads/writes and type references are shown at level 1 but do not propagate (configurable in `impact_relation_rule`).

CSV export: one row per edge; `repository` is `projectKey/slug`; cells starting with `= + - @` are prefixed with `'`.
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw test -Dtest='ImpactEngineTest,ImpactCsvTest,ImpactServiceTest,ImpactApiTest'`
Expected: all pass. Some existing tests assert that a seed twin has role `SEED`. Change those to `TWIN`, which is the intended contract change, and list each one in your report.

Run: `./mvnw test`
Expected: all tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/graphify/impact src/test/java/com/graphify/impact README.md
git commit -m "fix(impact): emit name-only twins as TWIN nodes so every edge endpoint is a node" -m "<your harness Co-Authored-By trailer>"
```

---

### Task 2: Schema V4, dependency persistence, and repository removal

**Files:**
- Create: `src/main/resources/db/migration/V4__repository_acquisition.sql`
- Create: `src/main/java/com/graphify/store/DependencyRecord.java`
- Modify: `src/main/java/com/graphify/store/ModuleRecord.java`, `ModuleWriter.java`, `RepositoryIndexWriter.java`
- Modify: `src/main/java/com/graphify/settings/SettingKeys.java`
- Modify: `src/test/java/com/graphify/store/StoreFixtures.java`
- Test: `src/test/java/com/graphify/store/RepositoryIndexWriterTest.java` (add tests), `src/test/java/com/graphify/store/SchemaMigrationTest.java` (add test)

**Interfaces:**
- Produces:
  - `public record DependencyRecord(String groupId, String artifactId, String version, String scope)`.
  - `ModuleRecord` gains a sixth component, `List<DependencyRecord> dependencies`. A five-argument constructor stays for existing callers and passes an empty list.
  - `RepositoryIndexWriter.replace` now replaces the repository's `module_dependency` rows.
  - `public void remove(long repositoryId)` (`@Transactional`) deletes the repository's usages, declarations, dependencies and modules, and clears `last_indexed_commit` and `last_indexed_at`.
  - Tables: `artifact_repository`, `module_dependency`, `index_run`, `index_run_repo`.
  - New `SettingKeys`: `INDEX_MAVEN_EXECUTABLE`, `INDEX_MAVEN_LOCAL_REPOSITORY`, `INDEX_GIT_DEPTH`, `INDEX_GIT_TIMEOUT`, `INDEX_SOURCE_ROOTS`, `SCM_CONNECT_TIMEOUT`, `SCM_READ_TIMEOUT`.

- [ ] **Step 1: Write the failing tests**

Append to `src/test/java/com/graphify/store/SchemaMigrationTest.java`, inside the class:

```java
    @Test
    void createsTheAcquisitionTablesAndSeedsTheirSettings() {
        assertThat(jdbc.queryForList("SELECT LOWER(table_name) FROM user_tables", String.class))
                .contains("artifact_repository", "module_dependency", "index_run", "index_run_repo");
        assertThat(jdbc.queryForList("SELECT setting_key FROM app_setting", String.class)).contains(
                "index.maven_executable", "index.maven_local_repository", "index.git_depth", "index.git_timeout",
                "index.source_roots", "scm.connect_timeout", "scm.read_timeout");
    }
```

Append to `src/test/java/com/graphify/store/RepositoryIndexWriterTest.java`, inside the class. Add the import `java.util.List` if it is missing.

```java
    @Test
    void storesAndReplacesDeclaredDependencies() {
        List<ModuleRecord> withDeps = List.of(
                new ModuleRecord("common-lib", "com.corp", "common-lib", "1.0.0", ClasspathMode.FULL,
                        List.of(new DependencyRecord("org.slf4j", "slf4j-api", "2.0.17", "compile"))),
                new ModuleRecord("order-service", "com.corp", "order-service", "1.0.0", ClasspathMode.FULL,
                        List.of(new DependencyRecord("com.corp", "common-lib", "1.0.0", "compile"),
                                new DependencyRecord("org.junit.jupiter", "junit-jupiter", null, "test"))));
        writer.replace(new RepositoryIndex(repoId, "abc123", withDeps, corp));

        assertThat(jdbc.queryForList("""
                SELECT m.path || ':' || d.group_id || ':' || d.artifact_id || ':' || NVL(d.version, '-') || ':' || d.scope
                  FROM module_dependency d JOIN maven_module m ON m.id = d.module_id ORDER BY 1
                """, String.class)).containsExactly(
                "common-lib:org.slf4j:slf4j-api:2.0.17:compile",
                "order-service:com.corp:common-lib:1.0.0:compile",
                "order-service:org.junit.jupiter:junit-jupiter:-:test");

        writer.replace(new RepositoryIndex(repoId, "def456", List.of(withDeps.getFirst()),
                new com.graphify.indexer.JavaRepositoryIndexer().index(new com.graphify.indexer.IndexRequest(CORP_REPO,
                        List.of(module("common-lib")), new com.graphify.indexer.IndexerOptions(50, 300)))));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM module_dependency", Integer.class)).isEqualTo(1);
    }

    @Test
    void removeClearsTheIndexButKeepsTheRepository() {
        writer.replace(new RepositoryIndex(repoId, "abc123", MODULES, corp));

        writer.remove(repoId);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM usage", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM symbol_declaration", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM maven_module", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT last_indexed_commit FROM scm_repository WHERE id = ?", String.class, repoId))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scm_repository WHERE id = ?", Integer.class, repoId))
                .isEqualTo(1);
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -Dtest='SchemaMigrationTest,RepositoryIndexWriterTest'`
Expected: BUILD FAILURE, with `cannot find symbol` for `DependencyRecord`, the six-argument `ModuleRecord`, and `remove`.

- [ ] **Step 3: Write the V4 migration**

`src/main/resources/db/migration/V4__repository_acquisition.sql`:

```sql
-- Plan 4: Maven repositories, declared dependencies, index run history and acquisition settings.

CREATE TABLE artifact_repository (
    id         NUMBER(19) GENERATED BY DEFAULT AS IDENTITY,
    name       VARCHAR2(200 BYTE)  NOT NULL,
    url        VARCHAR2(1000 BYTE) NOT NULL,
    username   VARCHAR2(200 BYTE),
    secret_enc VARCHAR2(4000 BYTE),
    mirror_of  VARCHAR2(200 BYTE),
    sort_order NUMBER(10) DEFAULT 0 NOT NULL,
    enabled    NUMBER(1) DEFAULT 1 NOT NULL,
    CONSTRAINT pk_artifact_repository PRIMARY KEY (id),
    CONSTRAINT uq_artifact_repository_name UNIQUE (name),
    CONSTRAINT ck_artifact_repository_enabled CHECK (enabled IN (0, 1))
);

CREATE TABLE module_dependency (
    id          NUMBER(19) GENERATED BY DEFAULT AS IDENTITY,
    module_id   NUMBER(19)         NOT NULL,
    group_id    VARCHAR2(300 BYTE) NOT NULL,
    artifact_id VARCHAR2(300 BYTE) NOT NULL,
    version     VARCHAR2(100 BYTE),
    scope       VARCHAR2(20 BYTE)  NOT NULL,
    CONSTRAINT pk_module_dependency PRIMARY KEY (id),
    CONSTRAINT fk_module_dependency_module FOREIGN KEY (module_id) REFERENCES maven_module (id)
);
CREATE INDEX ix_module_dependency_module ON module_dependency (module_id);
CREATE INDEX ix_module_dependency_artifact ON module_dependency (group_id, artifact_id);

CREATE TABLE index_run (
    id           NUMBER(19) GENERATED BY DEFAULT AS IDENTITY,
    trigger_type VARCHAR2(20 BYTE)  NOT NULL,
    scope        VARCHAR2(20 BYTE)  NOT NULL,
    scope_id     NUMBER(19),
    status       VARCHAR2(20 BYTE)  NOT NULL,
    started_by   VARCHAR2(200 BYTE) NOT NULL,
    started_at   TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    finished_at  TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_index_run PRIMARY KEY (id),
    CONSTRAINT ck_index_run_trigger CHECK (trigger_type IN ('SCHEDULED', 'MANUAL')),
    CONSTRAINT ck_index_run_scope CHECK (scope IN ('ALL', 'CONNECTION', 'REPOSITORY')),
    CONSTRAINT ck_index_run_status CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED', 'CANCELLED', 'INTERRUPTED'))
);
CREATE INDEX ix_index_run_started ON index_run (started_at);

CREATE TABLE index_run_repo (
    id             NUMBER(19) GENERATED BY DEFAULT AS IDENTITY,
    run_id         NUMBER(19)          NOT NULL,
    repo_id        NUMBER(19)          NOT NULL,
    commit_sha     VARCHAR2(64 BYTE),
    status         VARCHAR2(20 BYTE)   NOT NULL,
    classpath_mode VARCHAR2(10 BYTE),
    error          VARCHAR2(4000 BYTE),
    symbol_count   NUMBER(10) DEFAULT 0 NOT NULL,
    usage_count    NUMBER(10) DEFAULT 0 NOT NULL,
    warning_count  NUMBER(10) DEFAULT 0 NOT NULL,
    duration_ms    NUMBER(19) DEFAULT 0 NOT NULL,
    finished_at    TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_index_run_repo PRIMARY KEY (id),
    CONSTRAINT fk_index_run_repo_run FOREIGN KEY (run_id) REFERENCES index_run (id),
    CONSTRAINT fk_index_run_repo_repo FOREIGN KEY (repo_id) REFERENCES scm_repository (id),
    CONSTRAINT ck_index_run_repo_status CHECK (status IN ('SUCCESS', 'SUCCESS_PARTIAL', 'FAILED', 'CLONE_FAILED',
                                                          'SKIPPED_UNCHANGED', 'SKIPPED_NOT_JAVA', 'INTERRUPTED')),
    CONSTRAINT ck_index_run_repo_cp CHECK (classpath_mode IS NULL OR classpath_mode IN ('FULL', 'PARTIAL', 'NONE'))
);
CREATE INDEX ix_index_run_repo_run ON index_run_repo (run_id);
CREATE INDEX ix_index_run_repo_repo ON index_run_repo (repo_id, finished_at);

INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('index.maven_executable', 'mvn', 'STRING', 'Maven komutu (PATH üzerinde ya da tam yol)', NULL, NULL);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('index.maven_local_repository', '/data/impact-analyzer/m2', 'STRING', 'Maven yerel depo dizini (maven.repo.local)', NULL, NULL);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('index.git_depth', '1', 'INT', 'Git klon/fetch derinliği (commit sayısı)', 1, 1000);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('index.git_timeout', 'PT5M', 'DURATION', 'Git ağ işlemi zaman aşımı', NULL, NULL);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('index.source_roots', 'src/main/java,src/test/java', 'LIST', 'Modül başına indekslenen kaynak dizinleri (modüle göre)', NULL, NULL);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('scm.connect_timeout', 'PT10S', 'DURATION', 'SCM API bağlantı zaman aşımı', NULL, NULL);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('scm.read_timeout', 'PT60S', 'DURATION', 'SCM API okuma zaman aşımı', NULL, NULL);
```

Save the file as UTF-8.

- [ ] **Step 4: Add the setting keys**

Add these to `src/main/java/com/graphify/settings/SettingKeys.java`:

```java
    public static final String INDEX_MAVEN_EXECUTABLE = "index.maven_executable";
    public static final String INDEX_MAVEN_LOCAL_REPOSITORY = "index.maven_local_repository";
    public static final String INDEX_GIT_DEPTH = "index.git_depth";
    public static final String INDEX_GIT_TIMEOUT = "index.git_timeout";
    public static final String INDEX_SOURCE_ROOTS = "index.source_roots";
    public static final String SCM_CONNECT_TIMEOUT = "scm.connect_timeout";
    public static final String SCM_READ_TIMEOUT = "scm.read_timeout";
```

- [ ] **Step 5: Persist dependencies and add `remove`**

`src/main/java/com/graphify/store/DependencyRecord.java`:

```java
package com.graphify.store;

/** A dependency declared in a module's pom ({@code version} may be null when it could not be resolved). */
public record DependencyRecord(String groupId, String artifactId, String version, String scope) {
}
```

Replace `src/main/java/com/graphify/store/ModuleRecord.java` with:

```java
package com.graphify.store;

import java.util.List;

/**
 * One Maven module of a repository. {@code path} matches the indexer's {@code ModuleSource.modulePath} and must
 * not be blank (Oracle stores '' as NULL); the root module of a single-module repository is {@code "."}.
 */
public record ModuleRecord(String path, String groupId, String artifactId, String version, ClasspathMode classpathMode,
        List<DependencyRecord> dependencies) {

    public ModuleRecord {
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
    }

    public ModuleRecord(String path, String groupId, String artifactId, String version, ClasspathMode classpathMode) {
        this(path, groupId, artifactId, version, classpathMode, List.of());
    }
}
```

In `src/main/java/com/graphify/store/StoreLimits.java`, add `static final int SCOPE_BYTES = 20;`.

In `src/main/java/com/graphify/store/ModuleWriter.java`, method `replace`, make these changes:
- Insert this as the first statement:

  ```java
          jdbc.update("DELETE FROM module_dependency WHERE module_id IN (SELECT id FROM maven_module WHERE repo_id = ?)",
                  repositoryId);
  ```

- Before `return ids;`, insert the dependency rows:

  ```java
          List<Object[]> dependencies = new java.util.ArrayList<>();
          for (ModuleRecord module : modules) {
              for (DependencyRecord dependency : module.dependencies()) {
                  if (dependency.groupId() == null || dependency.artifactId() == null
                          || !StoreLimits.fits(dependency.groupId(), StoreLimits.GROUP_ID_BYTES)
                          || !StoreLimits.fits(dependency.artifactId(), StoreLimits.ARTIFACT_ID_BYTES)) {
                      continue;
                  }
                  dependencies.add(new Object[] {ids.get(module.path()), dependency.groupId(), dependency.artifactId(),
                          cut(dependency.version(), StoreLimits.VERSION_BYTES),
                          cut(dependency.scope() == null ? "compile" : dependency.scope(), StoreLimits.SCOPE_BYTES)});
              }
          }
          jdbc.batchUpdate("INSERT INTO module_dependency (module_id, group_id, artifact_id, version, scope) "
                  + "VALUES (?, ?, ?, ?, ?)", dependencies);
  ```

- Update the class Javadoc to say: "Caller removes their usages and declarations first; dependency rows are replaced here."

In `src/main/java/com/graphify/store/RepositoryIndexWriter.java`, add:

```java
    /** Deletes a repository's index rows (spec §4.1: a repository gone from SCM); keeps the repository and its run history. */
    @Transactional
    public void remove(long repositoryId) {
        lock(repositoryId);
        String modules = "(SELECT id FROM maven_module WHERE repo_id = ?)";
        jdbc.update("DELETE FROM usage WHERE module_id IN " + modules, repositoryId);
        jdbc.update("DELETE FROM symbol_declaration WHERE module_id IN " + modules, repositoryId);
        jdbc.update("DELETE FROM module_dependency WHERE module_id IN " + modules, repositoryId);
        jdbc.update("DELETE FROM maven_module WHERE repo_id = ?", repositoryId);
        jdbc.update("UPDATE scm_repository SET last_indexed_commit = NULL, last_indexed_at = NULL WHERE id = ?",
                repositoryId);
    }
```

In `src/test/java/com/graphify/store/StoreFixtures.java`, method `cleanIndexTables`, add `jdbc.update("DELETE FROM index_run_repo");` and `jdbc.update("DELETE FROM index_run");` first. Add `jdbc.update("DELETE FROM module_dependency");` before `DELETE FROM maven_module`. Add `jdbc.update("DELETE FROM artifact_repository");` at the end.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw test -Dtest='SchemaMigrationTest,RepositoryIndexWriterTest,AppSettingsTest'`
Expected: all pass. `AppSettingsTest.everyDeclaredKeyIsSeeded` covers the new keys.

Run: `./mvnw test`
Expected: all tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/main src/test
git commit -m "feat(store): add acquisition schema, declared dependencies and repository removal" -m "<your harness Co-Authored-By trailer>"
```

---

### Task 3: SCM connections and the Bitbucket Data Center client

**Files:**
- Create in `src/main/java/com/graphify/scm/`:
  - `ScmType.java`, `ScmConnection.java`, `ScmConnections.java`, `RemoteRepository.java`, `ScmClient.java`
  - `ScmException.java`, `ScmAuthenticationException.java`
  - `AuthorizationHeader.java`, `UrlMasking.java`, `RepositoryFilter.java`, `BitbucketDataCenterClient.java`
- Create: `src/test/java/com/graphify/testsupport/FakeBitbucket.java`
- Test: `src/test/java/com/graphify/scm/UrlMaskingTest.java`, `RepositoryFilterTest.java`, `BitbucketDataCenterClientTest.java`

**Interfaces:**
- **Consumes:** `SecretCipher.encrypt/decrypt` (plan 2), `AppSettings`, and `SettingKeys` (`SCM_PAGE_SIZE`, `SCM_RETRY_COUNT`, `SCM_RETRY_BACKOFF`, `SCM_CONNECT_TIMEOUT`, `SCM_READ_TIMEOUT`).
- **Produces (types):**
  - `public enum ScmType { BITBUCKET_DC }`.
  - `public record ScmConnection(long id, String name, ScmType type, String baseUrl, String username, String secret, List<String> includeProjects, List<String> excludeRepos)`. Its `toString()` omits `secret`.
  - `public record RemoteRepository(String projectKey, String slug, String name, String cloneUrl)`.
  - `public interface ScmClient { ScmType type(); List<RemoteRepository> listRepositories(ScmConnection connection); }`.
  - `public class ScmException extends RuntimeException` and `public class ScmAuthenticationException extends ScmException`.
- **Produces (beans and helpers):**
  - `public class ScmConnections`, a `@Repository` with `List<ScmConnection> enabled()` and `Optional<ScmConnection> find(long id)`. It decrypts `secret_enc` and splits the comma lists.
  - `public final class AuthorizationHeader` with `static Optional<String> of(String username, String secret)`.
  - `public final class UrlMasking` with `static String mask(String text)`.
  - `final class RepositoryFilter` with `static boolean accepts(ScmConnection, RemoteRepository)`:
    - `includeProjects` is a case-insensitive list of project keys; an empty list means all projects.
    - `excludeRepos` holds case-insensitive `PROJECT/slug` globs using `*` and `?`.
  - `public class BitbucketDataCenterClient implements ScmClient`, a `@Component`. It pages through `GET {base}/rest/api/1.0/repos?start=&limit=`, skips archived repositories, uses the `http` clone link, and applies `RepositoryFilter`.
  - **Retry rule for the client.** It retries `5xx` and I/O errors `scm.retry_count` times, with a backoff of `scm.retry_backoff` that doubles each attempt. A `401` or `403` throws `ScmAuthenticationException` and is never retried. Any other `4xx` throws `ScmException`.
- **Produces (test helper):** `FakeBitbucket` (test only):
  - `start()`, `close()` and `String baseUrl()`.
  - `addRepository(projectKey, slug, cloneUrl)` and `addArchived(projectKey, slug, cloneUrl)`.
  - `requireAuthorization(String headerValue)`.
  - `failNext(int status, int times)`.
  - `List<String> requestedStarts()` and `List<String> authorizations()`.

Verified in a spike: Spring 7's `RestClient` with Jackson 3 maps the Bitbucket page JSON into records with `@JsonIgnoreProperties(ignoreUnknown = true)`. A record component cannot be named `clone`, so it is `@JsonProperty("clone") List<Link> cloneLinks`.

- [ ] **Step 1: Write the fake Bitbucket**

`src/test/java/com/graphify/testsupport/FakeBitbucket.java`:

```java
package com.graphify.testsupport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** A minimal Bitbucket Data Center serving {@code GET /rest/api/1.0/repos} with paging, auth checks and injected failures. */
public final class FakeBitbucket implements AutoCloseable {

    private record Repo(String projectKey, String slug, String cloneUrl, boolean archived) {
    }

    private final List<Repo> repos = new ArrayList<>();
    private final List<String> requestedStarts = new ArrayList<>();
    private final List<String> authorizations = new ArrayList<>();
    private final AtomicInteger failuresLeft = new AtomicInteger();
    private volatile int failureStatus;
    private volatile String requiredAuthorization;
    private HttpServer server;

    public FakeBitbucket start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rest/api/1.0/repos", this::handle);
        server.start();
        return this;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public FakeBitbucket addRepository(String projectKey, String slug, String cloneUrl) {
        repos.add(new Repo(projectKey, slug, cloneUrl, false));
        return this;
    }

    public FakeBitbucket addArchived(String projectKey, String slug, String cloneUrl) {
        repos.add(new Repo(projectKey, slug, cloneUrl, true));
        return this;
    }

    public FakeBitbucket requireAuthorization(String headerValue) {
        requiredAuthorization = headerValue;
        return this;
    }

    public FakeBitbucket failNext(int status, int times) {
        failureStatus = status;
        failuresLeft.set(times);
        return this;
    }

    public synchronized List<String> requestedStarts() {
        return List.copyOf(requestedStarts);
    }

    public synchronized List<String> authorizations() {
        return List.copyOf(authorizations);
    }

    public synchronized void removeRepository(String projectKey, String slug) {
        repos.removeIf(r -> r.projectKey().equals(projectKey) && r.slug().equals(slug));
    }

    private void handle(HttpExchange exchange) throws IOException {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        int start = intParam(exchange.getRequestURI(), "start", 0);
        int limit = intParam(exchange.getRequestURI(), "limit", 25);
        synchronized (this) {
            requestedStarts.add(Integer.toString(start));
            authorizations.add(authorization);
        }
        if (failuresLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            respond(exchange, failureStatus, "{\"errors\":[{\"message\":\"injected\"}]}");
            return;
        }
        if (requiredAuthorization != null && !requiredAuthorization.equals(authorization)) {
            respond(exchange, 401, "{\"errors\":[{\"message\":\"Authentication failed\"}]}");
            return;
        }
        List<Repo> snapshot;
        synchronized (this) {
            snapshot = List.copyOf(repos);
        }
        int end = Math.min(snapshot.size(), start + limit);
        StringBuilder values = new StringBuilder();
        for (int i = start; i < end; i++) {
            Repo repo = snapshot.get(i);
            if (values.length() > 0) {
                values.append(',');
            }
            values.append("{\"slug\":\"").append(repo.slug()).append("\",\"id\":").append(i + 1)
                    .append(",\"name\":\"").append(repo.slug()).append("\",\"scmId\":\"git\",\"archived\":")
                    .append(repo.archived()).append(",\"project\":{\"key\":\"").append(repo.projectKey())
                    .append("\",\"id\":1},\"links\":{\"clone\":[{\"href\":\"ssh://git@scm:7999/x.git\",\"name\":\"ssh\"},")
                    .append("{\"href\":\"").append(repo.cloneUrl()).append("\",\"name\":\"http\"}]}}");
        }
        boolean last = end >= snapshot.size();
        String body = "{\"size\":" + (end - start) + ",\"limit\":" + limit + ",\"start\":" + start + ",\"isLastPage\":"
                + last + (last ? "" : ",\"nextPageStart\":" + end) + ",\"values\":[" + values + "]}";
        respond(exchange, 200, body);
    }

    private static int intParam(URI uri, String name, int fallback) {
        String query = uri.getRawQuery();
        if (query == null) {
            return fallback;
        }
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts[0].equals(name) && parts.length == 2) {
                return Integer.parseInt(parts[1]);
            }
        }
        return fallback;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
        }
    }
}
```

- [ ] **Step 2: Write the failing tests**

`src/test/java/com/graphify/scm/UrlMaskingTest.java`:

```java
package com.graphify.scm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class UrlMaskingTest {

    @Test
    void masksUserInfoInEveryUrl() {
        assertThat(UrlMasking.mask("clone https://bob:s3cr3t@scm.corp/scm/a.git failed; retry http://t0k@h/x"))
                .isEqualTo("clone https://***@scm.corp/scm/a.git failed; retry http://***@h/x")
                .doesNotContain("s3cr3t").doesNotContain("t0k");
        assertThat(UrlMasking.mask("no url here")).isEqualTo("no url here");
        assertThat(UrlMasking.mask(null)).isNull();
    }

    @Test
    void buildsBearerOrBasicAuthorization() {
        assertThat(AuthorizationHeader.of(null, "tok")).contains("Bearer tok");
        assertThat(AuthorizationHeader.of(" ", "tok")).contains("Bearer tok");
        assertThat(AuthorizationHeader.of("bob", "pw")).contains("Basic Ym9iOnB3");
        assertThat(AuthorizationHeader.of("bob", null)).isEqualTo(Optional.empty());
    }
}
```

`src/test/java/com/graphify/scm/RepositoryFilterTest.java`:

```java
package com.graphify.scm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RepositoryFilterTest {

    private static ScmConnection connection(List<String> include, List<String> exclude) {
        return new ScmConnection(1, "c", ScmType.BITBUCKET_DC, "http://x", null, "t", include, exclude);
    }

    private static RemoteRepository repo(String project, String slug) {
        return new RemoteRepository(project, slug, slug, "http://x/" + slug + ".git");
    }

    @Test
    void includeProjectsAndExcludeGlobsApplyCaseInsensitively() {
        ScmConnection filtered = connection(List.of("shop", "PAY"), List.of("SHOP/*-archive", "pay/legacy?"));

        assertThat(RepositoryFilter.accepts(filtered, repo("SHOP", "api"))).isTrue();
        assertThat(RepositoryFilter.accepts(filtered, repo("SHOP", "old-archive"))).isFalse();
        assertThat(RepositoryFilter.accepts(filtered, repo("PAY", "legacy1"))).isFalse();
        assertThat(RepositoryFilter.accepts(filtered, repo("PAY", "legacy12"))).isTrue();
        assertThat(RepositoryFilter.accepts(filtered, repo("HR", "api"))).isFalse();
        assertThat(RepositoryFilter.accepts(connection(List.of(), List.of()), repo("HR", "api"))).isTrue();
    }

    @Test
    void connectionToStringNeverShowsTheSecret() {
        assertThat(connection(List.of(), List.of()).toString()).doesNotContain("t,").doesNotContain("secret");
        assertThat(new ScmConnection(1, "c", ScmType.BITBUCKET_DC, "http://x", "u", "TOPSECRET", List.of(), List.of())
                .toString()).doesNotContain("TOPSECRET");
    }
}
```

`src/test/java/com/graphify/scm/BitbucketDataCenterClientTest.java`:

```java
package com.graphify.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.graphify.OracleIntegrationTest;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import com.graphify.testsupport.FakeBitbucket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class BitbucketDataCenterClientTest extends OracleIntegrationTest {

    @Autowired
    BitbucketDataCenterClient client;

    @Autowired
    AppSettings settings;

    private final Map<String, String> originals = new HashMap<>();
    private FakeBitbucket bitbucket;

    @BeforeEach
    void setUp() throws Exception {
        change(SettingKeys.SCM_PAGE_SIZE, "2");
        change(SettingKeys.SCM_RETRY_BACKOFF, "PT0.01S");
        bitbucket = new FakeBitbucket().start();
    }

    @AfterEach
    void tearDown() {
        bitbucket.close();
        originals.forEach((key, value) -> settings.update(key, value, "test"));
    }

    private void change(String key, String value) {
        originals.putIfAbsent(key, settings.all().stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow()
                .value());
        settings.update(key, value, "test");
    }

    private ScmConnection connection(String username, String secret, List<String> include, List<String> exclude) {
        return new ScmConnection(1, "corp", ScmType.BITBUCKET_DC, bitbucket.baseUrl(), username, secret, include, exclude);
    }

    @Test
    void readsEveryPageSkipsArchivedAndAppliesFilters() {
        bitbucket.addRepository("SHOP", "api", "https://scm/scm/shop/api.git")
                .addRepository("SHOP", "lib", "https://scm/scm/shop/lib.git")
                .addArchived("SHOP", "old", "https://scm/scm/shop/old.git")
                .addRepository("SHOP", "tmp-archive", "https://scm/scm/shop/tmp-archive.git")
                .addRepository("HR", "portal", "https://scm/scm/hr/portal.git");

        List<RemoteRepository> repositories = client.listRepositories(
                connection(null, "tok", List.of("SHOP"), List.of("SHOP/*-archive")));

        assertThat(repositories).extracting(RemoteRepository::projectKey, RemoteRepository::slug, RemoteRepository::cloneUrl)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("SHOP", "api", "https://scm/scm/shop/api.git"),
                        org.assertj.core.api.Assertions.tuple("SHOP", "lib", "https://scm/scm/shop/lib.git"));
        assertThat(bitbucket.requestedStarts()).containsExactly("0", "2", "4");
        assertThat(bitbucket.authorizations()).containsOnly("Bearer tok");
    }

    @Test
    void retriesServerErrorsWithBackoff() {
        bitbucket.addRepository("SHOP", "api", "https://scm/scm/shop/api.git").failNext(503, 2);

        assertThat(client.listRepositories(connection(null, "tok", List.of(), List.of()))).hasSize(1);
        assertThat(bitbucket.requestedStarts()).hasSize(3);
    }

    @Test
    void givesUpAfterTheConfiguredRetries() {
        bitbucket.failNext(500, 10);

        assertThatThrownBy(() -> client.listRepositories(connection(null, "tok", List.of(), List.of())))
                .isInstanceOf(ScmException.class)
                .isNotInstanceOf(ScmAuthenticationException.class);
        assertThat(bitbucket.requestedStarts()).hasSize(1 + settings.getInt(SettingKeys.SCM_RETRY_COUNT));
    }

    @Test
    void rejectedCredentialsAreNotRetriedAndNotLeaked() {
        bitbucket.requireAuthorization("Bearer right");

        assertThatThrownBy(() -> client.listRepositories(connection(null, "wrong-token-xyz", List.of(), List.of())))
                .isInstanceOf(ScmAuthenticationException.class)
                .hasMessageNotContaining("wrong-token-xyz");
        assertThat(bitbucket.requestedStarts()).hasSize(1);
    }
}
```

- [ ] **Step 3: Run them to verify they fail**

Run: `./mvnw test -Dtest='UrlMaskingTest,RepositoryFilterTest,BitbucketDataCenterClientTest'`
Expected: BUILD FAILURE: `cannot find symbol` for the new `scm` types.

- [ ] **Step 4: Write the SCM types and helpers**

`src/main/java/com/graphify/scm/ScmType.java`:

```java
package com.graphify.scm;

public enum ScmType {
    BITBUCKET_DC
}
```

`src/main/java/com/graphify/scm/ScmConnection.java`:

```java
package com.graphify.scm;

import java.util.List;

/** A configured SCM connection with its secret already decrypted; never log it (toString omits the secret). */
public record ScmConnection(long id, String name, ScmType type, String baseUrl, String username, String secret,
        List<String> includeProjects, List<String> excludeRepos) {

    public ScmConnection {
        includeProjects = includeProjects == null ? List.of() : List.copyOf(includeProjects);
        excludeRepos = excludeRepos == null ? List.of() : List.copyOf(excludeRepos);
    }

    @Override
    public String toString() {
        return "ScmConnection[id=" + id + ", name=" + name + ", type=" + type + ", baseUrl=" + baseUrl + "]";
    }
}
```

`src/main/java/com/graphify/scm/RemoteRepository.java`:

```java
package com.graphify.scm;

/** A repository as the SCM lists it; {@code cloneUrl} is the HTTP(S) clone link. */
public record RemoteRepository(String projectKey, String slug, String name, String cloneUrl) {
}
```

`src/main/java/com/graphify/scm/ScmClient.java`:

```java
package com.graphify.scm;

import java.util.List;

/** Lists the repositories a connection can read. One implementation per {@link ScmType}. */
public interface ScmClient {

    ScmType type();

    List<RemoteRepository> listRepositories(ScmConnection connection);
}
```

`src/main/java/com/graphify/scm/ScmException.java`:

```java
package com.graphify.scm;

/** The SCM could not be read. Messages never contain credentials. */
public class ScmException extends RuntimeException {

    public ScmException(String message) {
        super(message);
    }

    public ScmException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

`src/main/java/com/graphify/scm/ScmAuthenticationException.java`:

```java
package com.graphify.scm;

/** The SCM rejected the connection's credentials (HTTP 401/403); retrying will not help (spec §8 AUTH_FAILED). */
public class ScmAuthenticationException extends ScmException {

    public ScmAuthenticationException(String message) {
        super(message);
    }
}
```

`src/main/java/com/graphify/scm/AuthorizationHeader.java`:

```java
package com.graphify.scm;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/** Bitbucket DC accepts an HTTP access token as {@code Bearer}, or user + password/token as {@code Basic}. */
public final class AuthorizationHeader {

    private AuthorizationHeader() {
    }

    public static Optional<String> of(String username, String secret) {
        if (secret == null) {
            return Optional.empty();
        }
        if (username == null || username.isBlank()) {
            return Optional.of("Bearer " + secret);
        }
        String pair = username.strip() + ":" + secret;
        return Optional.of("Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8)));
    }
}
```

`src/main/java/com/graphify/scm/UrlMasking.java`:

```java
package com.graphify.scm;

import java.util.regex.Pattern;

/** Hides credentials embedded in URLs ({@code https://user:pass@host} → {@code https://***@host}) in any text. */
public final class UrlMasking {

    private static final Pattern USER_INFO = Pattern.compile("(?<=://)[^/@\\s]+@");

    private UrlMasking() {
    }

    public static String mask(String text) {
        return text == null ? null : USER_INFO.matcher(text).replaceAll("***@");
    }
}
```

`src/main/java/com/graphify/scm/RepositoryFilter.java`:

```java
package com.graphify.scm;

import java.util.regex.Pattern;

/** Applies a connection's include_projects (project keys) and exclude_repos ({@code PROJECT/slug} globs). */
final class RepositoryFilter {

    private RepositoryFilter() {
    }

    static boolean accepts(ScmConnection connection, RemoteRepository repository) {
        if (!connection.includeProjects().isEmpty() && connection.includeProjects().stream()
                .noneMatch(project -> project.equalsIgnoreCase(repository.projectKey()))) {
            return false;
        }
        String fullName = repository.projectKey() + "/" + repository.slug();
        return connection.excludeRepos().stream().noneMatch(pattern -> glob(pattern).matcher(fullName).matches());
    }

    private static Pattern glob(String pattern) {
        StringBuilder regex = new StringBuilder();
        for (char ch : pattern.toCharArray()) {
            switch (ch) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(ch)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
    }
}
```

`src/main/java/com/graphify/scm/ScmConnections.java`:

```java
package com.graphify.scm;

import com.graphify.common.crypto.SecretCipher;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads scm_connection rows and decrypts their secrets. */
@Repository
public class ScmConnections {

    private static final String SELECT = """
            SELECT id, name, type, base_url, username, secret_enc, include_projects, exclude_repos FROM scm_connection
            """;

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;

    public ScmConnections(JdbcTemplate jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    public List<ScmConnection> enabled() {
        return jdbc.query(SELECT + " WHERE enabled = 1 ORDER BY id", this::map);
    }

    public Optional<ScmConnection> find(long id) {
        return jdbc.query(SELECT + " WHERE id = ?", this::map, id).stream().findFirst();
    }

    private ScmConnection map(ResultSet rs, int row) throws SQLException {
        String secret = rs.getString("secret_enc");
        return new ScmConnection(rs.getLong("id"), rs.getString("name"), ScmType.valueOf(rs.getString("type")),
                rs.getString("base_url"), rs.getString("username"), secret == null ? null : cipher.decrypt(secret),
                split(rs.getString("include_projects")), split(rs.getString("exclude_repos")));
    }

    static List<String> split(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
```

- [ ] **Step 5: Write the Bitbucket client**

`src/main/java/com/graphify/scm/BitbucketDataCenterClient.java`:

```java
package com.graphify.scm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** Lists repositories through Bitbucket Data Center's REST API ({@code /rest/api/1.0/repos}, paged). */
@Component
public class BitbucketDataCenterClient implements ScmClient {

    /** Bitbucket DC REST resource listing every repository the caller can read. */
    private static final String REPOS_PATH = "/rest/api/1.0/repos?start={start}&limit={limit}";
    /** Name Bitbucket gives the HTTP(S) entry among a repository's clone links. */
    private static final String HTTP_CLONE_LINK = "http";

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Link(String href, String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Links(@JsonProperty("clone") List<Link> cloneLinks) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Project(String key) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Repo(String slug, String name, Project project, Links links, Boolean archived) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Page(List<Repo> values, boolean isLastPage, Integer nextPageStart) {
    }

    private final AppSettings settings;

    public BitbucketDataCenterClient(AppSettings settings) {
        this.settings = settings;
    }

    @Override
    public ScmType type() {
        return ScmType.BITBUCKET_DC;
    }

    @Override
    public List<RemoteRepository> listRepositories(ScmConnection connection) {
        RestClient client = client(connection);
        int pageSize = settings.getInt(SettingKeys.SCM_PAGE_SIZE);
        List<RemoteRepository> repositories = new ArrayList<>();
        int start = 0;
        while (true) {
            int pageStart = start;
            Page page = withRetry(connection, () -> client.get().uri(REPOS_PATH, pageStart, pageSize).retrieve()
                    .body(Page.class));
            if (page == null || page.values() == null) {
                break;
            }
            for (Repo repo : page.values()) {
                String cloneUrl = httpCloneUrl(repo);
                if (Boolean.TRUE.equals(repo.archived()) || cloneUrl == null || repo.project() == null) {
                    continue;
                }
                RemoteRepository remote = new RemoteRepository(repo.project().key(), repo.slug(), repo.name(), cloneUrl);
                if (RepositoryFilter.accepts(connection, remote)) {
                    repositories.add(remote);
                }
            }
            if (page.isLastPage() || page.nextPageStart() == null) {
                break;
            }
            start = page.nextPageStart();
        }
        return repositories;
    }

    private RestClient client(ScmConnection connection) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(settings.getDuration(SettingKeys.SCM_CONNECT_TIMEOUT)).build());
        factory.setReadTimeout(settings.getDuration(SettingKeys.SCM_READ_TIMEOUT));
        RestClient.Builder builder = RestClient.builder().requestFactory(factory).baseUrl(connection.baseUrl());
        AuthorizationHeader.of(connection.username(), connection.secret())
                .ifPresent(header -> builder.defaultHeader("Authorization", header));
        return builder.build();
    }

    private <T> T withRetry(ScmConnection connection, Supplier<T> call) {
        int retries = settings.getInt(SettingKeys.SCM_RETRY_COUNT);
        Duration backoff = settings.getDuration(SettingKeys.SCM_RETRY_BACKOFF);
        for (int attempt = 0; ; attempt++) {
            try {
                return call.get();
            } catch (HttpClientErrorException e) {
                HttpStatusCode status = e.getStatusCode();
                if (status.value() == 401 || status.value() == 403) {
                    throw new ScmAuthenticationException("Bitbucket rejected the credentials of connection '"
                            + connection.name() + "' (HTTP " + status.value() + ")");
                }
                throw new ScmException("Bitbucket request for connection '" + connection.name() + "' failed with HTTP "
                        + status.value());
            } catch (HttpServerErrorException | ResourceAccessException e) {
                if (attempt >= retries) {
                    throw new ScmException("Bitbucket request for connection '" + connection.name() + "' failed after "
                            + (attempt + 1) + " attempts: " + UrlMasking.mask(e.getMessage()));
                }
                sleep(backoff.multipliedBy(1L << Math.min(attempt, 20)));
            }
        }
    }

    private static String httpCloneUrl(Repo repo) {
        if (repo.links() == null || repo.links().cloneLinks() == null) {
            return null;
        }
        return repo.links().cloneLinks().stream().filter(link -> HTTP_CLONE_LINK.equals(link.name()))
                .map(Link::href).findFirst().orElse(null);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScmException("Interrupted while waiting to retry Bitbucket");
        }
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw test -Dtest='UrlMaskingTest,RepositoryFilterTest,BitbucketDataCenterClientTest'`
Expected: 8 tests pass.

Run: `./mvnw test`
Expected: all tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/graphify/scm src/test/java/com/graphify/scm src/test/java/com/graphify/testsupport/FakeBitbucket.java
git commit -m "feat(scm): list Bitbucket Data Center repositories with filters, retries and masked errors" -m "<your harness Co-Authored-By trailer>"
```

---

### Task 4: Repository sync

**Files:**
- Create: `src/main/java/com/graphify/scm/RepositorySync.java`
- Test: `src/test/java/com/graphify/scm/RepositorySyncTest.java`

**Interfaces:**
- **Consumes:**
  - `List<ScmClient>`, which is every client bean.
  - `RepositoryIndexWriter.remove(long)` (Task 2).
  - `FakeBitbucket` (Task 3).
  - `StoreFixtures.cleanIndexTables`.
- **Produces:**
  - `public class RepositorySync`, a `@Service`, with `SyncResult sync(ScmConnection connection)`.
  - `public record SyncResult(int listed, int added, int reactivated, int deactivated)`.
  - **Sync rules.** For every listed repository, `sync` upserts the `scm_repository` row (on `connection_id`, `project_key`, `slug`), sets its `clone_url`, and sets `active = 1`. Any active row of the connection that is no longer listed is deactivated: `writer.remove(id)` runs and the row gets `active = 0`.
  - **Clone URL limit.** A clone URL longer than 1000 bytes is skipped, which is the column width.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/graphify/scm/RepositorySyncTest.java`:

```java
package com.graphify.scm;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.store.StoreFixtures;
import com.graphify.testsupport.FakeBitbucket;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RepositorySyncTest extends OracleIntegrationTest {

    @Autowired
    RepositorySync sync;

    private FakeBitbucket bitbucket;
    private ScmConnection connection;

    @BeforeEach
    void setUp() throws Exception {
        StoreFixtures.cleanIndexTables(jdbc);
        bitbucket = new FakeBitbucket().start();
        jdbc.update("INSERT INTO scm_connection (name, type, base_url) VALUES ('corp', 'BITBUCKET_DC', ?)",
                bitbucket.baseUrl());
        long id = jdbc.queryForObject("SELECT id FROM scm_connection WHERE name = 'corp'", Long.class);
        connection = new ScmConnection(id, "corp", ScmType.BITBUCKET_DC, bitbucket.baseUrl(), null, null, List.of(),
                List.of());
    }

    @AfterEach
    void tearDown() {
        bitbucket.close();
    }

    private List<String> rows() {
        return jdbc.queryForList("SELECT project_key || '/' || slug || ':' || active || ':' || clone_url "
                + "FROM scm_repository ORDER BY project_key, slug", String.class);
    }

    @Test
    void addsUpdatesDeactivatesAndReactivatesRepositories() {
        bitbucket.addRepository("SHOP", "api", "https://scm/a.git").addRepository("SHOP", "lib", "https://scm/l.git");

        assertThat(sync.sync(connection)).isEqualTo(new RepositorySync.SyncResult(2, 2, 0, 0));
        assertThat(rows()).containsExactly("SHOP/api:1:https://scm/a.git", "SHOP/lib:1:https://scm/l.git");

        long libId = jdbc.queryForObject("SELECT id FROM scm_repository WHERE slug = 'lib'", Long.class);
        jdbc.update("INSERT INTO maven_module (repo_id, path, classpath_mode) VALUES (?, '.', 'FULL')", libId);
        bitbucket.removeRepository("SHOP", "lib");

        assertThat(sync.sync(connection)).isEqualTo(new RepositorySync.SyncResult(1, 0, 0, 1));
        assertThat(rows()).containsExactly("SHOP/api:1:https://scm/a.git", "SHOP/lib:0:https://scm/l.git");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM maven_module", Integer.class)).isZero();

        bitbucket.addRepository("SHOP", "lib", "https://scm/l2.git");

        assertThat(sync.sync(connection)).isEqualTo(new RepositorySync.SyncResult(2, 0, 1, 0));
        assertThat(rows()).containsExactly("SHOP/api:1:https://scm/a.git", "SHOP/lib:1:https://scm/l2.git");
    }

    @Test
    void sameSlugInAnotherProjectIsADifferentRepository() {
        bitbucket.addRepository("SHOP", "api", "https://scm/s.git").addRepository("PAY", "api", "https://scm/p.git");

        sync.sync(connection);

        assertThat(rows()).containsExactly("PAY/api:1:https://scm/p.git", "SHOP/api:1:https://scm/s.git");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw test -Dtest=RepositorySyncTest`
Expected: BUILD FAILURE: `cannot find symbol ... RepositorySync`.

- [ ] **Step 3: Write `RepositorySync`**

`src/main/java/com/graphify/scm/RepositorySync.java`:

```java
package com.graphify.scm;

import com.graphify.common.util.Utf8;
import com.graphify.store.RepositoryIndexWriter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Makes a connection's scm_repository rows match what its SCM lists (spec §3.2 step 1, §4.1 active flag). */
@Service
public class RepositorySync {

    /** Width of scm_repository.clone_url in V1__core_schema.sql. */
    private static final int CLONE_URL_BYTES = 1000;

    public record SyncResult(int listed, int added, int reactivated, int deactivated) {
    }

    private final JdbcTemplate jdbc;
    private final List<ScmClient> clients;
    private final RepositoryIndexWriter writer;

    public RepositorySync(JdbcTemplate jdbc, List<ScmClient> clients, RepositoryIndexWriter writer) {
        this.jdbc = jdbc;
        this.clients = clients;
        this.writer = writer;
    }

    public SyncResult sync(ScmConnection connection) {
        ScmClient client = clients.stream().filter(c -> c.type() == connection.type()).findFirst()
                .orElseThrow(() -> new IllegalStateException("No SCM client for " + connection.type()));
        List<RemoteRepository> listed = client.listRepositories(connection);
        Set<String> seen = new HashSet<>();
        int added = 0;
        int reactivated = 0;
        for (RemoteRepository repository : listed) {
            if (Utf8.byteLength(repository.cloneUrl()) > CLONE_URL_BYTES) {
                continue;
            }
            seen.add(repository.projectKey() + "/" + repository.slug());
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
        List<Long> gone = jdbc.query("SELECT id, project_key, slug FROM scm_repository WHERE connection_id = ? "
                        + "AND active = 1 ORDER BY id",
                        (rs, row) -> seen.contains(rs.getString("project_key") + "/" + rs.getString("slug"))
                                ? null : rs.getLong("id"),
                        connection.id())
                .stream().filter(java.util.Objects::nonNull).toList();
        for (Long id : gone) {
            writer.remove(id);
            jdbc.update("UPDATE scm_repository SET active = 0 WHERE id = ?", id);
        }
        return new SyncResult(listed.size(), added, reactivated, gone.size());
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw test -Dtest=RepositorySyncTest`
Expected: 2 tests pass.

Run: `./mvnw test`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphify/scm/RepositorySync.java src/test/java/com/graphify/scm/RepositorySyncTest.java
git commit -m "feat(scm): sync repository rows from the SCM listing and deactivate removed ones" -m "<your harness Co-Authored-By trailer>"
```

---

### Task 5: Git workspace (JGit)

**Files:**
- Modify: `pom.xml` (add JGit)
- Create: `src/main/java/com/graphify/workspace/RemoteHead.java`, `GitException.java`, `GitWorkspace.java`
- Create: `src/test/java/com/graphify/testsupport/GitFixtures.java`
- Test: `src/test/java/com/graphify/workspace/GitWorkspaceTest.java`

**Interfaces:**
- Consumes `AuthorizationHeader` and `UrlMasking` (Task 3), plus `AppSettings` with `INDEX_WORKSPACE_DIR`, `INDEX_GIT_DEPTH` and `INDEX_GIT_TIMEOUT`.
- Produces:
  - `public record RemoteHead(String branch, String commit)`, where `branch` is a short name such as `main`.
  - `public class GitException extends RuntimeException`. Its messages are masked.
  - `public class GitWorkspace` (a `@Component`) with:
    - `Path directoryFor(long connectionId, String projectKey, String slug)`. The result lives under `index.workspace_dir`; any character outside `[A-Za-z0-9._-]` becomes `_`.
    - `RemoteHead remoteHead(String cloneUrl, String username, String secret)`. It uses `ls-remote`. The HEAD symref names the branch. If HEAD is not symbolic, the branch whose commit equals HEAD is used, preferring `main` and then `master`.
    - `String checkout(Path directory, String cloneUrl, String branch, String username, String secret)`. It returns the checked-out commit.
  - Checkout behaviour, in order:
    - When the directory is missing, it shallow-clones to depth `index.git_depth`, single branch.
    - Otherwise it fetches that branch to `refs/remotes/origin/<branch>` with the same depth, hard-resets to it, and cleans untracked files and directories.
    - If opening, fetching or resetting fails, the directory is deleted and cloned once more. A second failure throws `GitException`.
  - Every transport command uses `index.git_timeout`. Over HTTP it sends `AuthorizationHeader.of(username, secret)` as an extra header.
  - `GitFixtures` (test only):
    - `static Path bareRepository(Path dir, String name, Map<String, String> files)` returns the bare repository's path, with branch `main` and one commit.
    - `static String commit(Path bare, Map<String, String> files, String message)` adds a commit to `main` and returns its id.
    - `static String url(Path bare)` returns the `file://` URI.

Verified in a spike with JGit 7.8:
- A `file://` shallow clone (`setDepth(1)`) works.
- Fetch followed by a hard reset updates the checkout.
- `lsRemoteRepository().callAsMap()` returns `HEAD` as a symbolic ref targeting `refs/heads/main`.

- [ ] **Step 1: Add JGit to `pom.xml`**

Add `<jgit.version>7.8.0.202609011348-r</jgit.version>` to `<properties>`, and add this dependency:

```xml
		<dependency>
			<groupId>org.eclipse.jgit</groupId>
			<artifactId>org.eclipse.jgit</artifactId>
			<version>${jgit.version}</version>
		</dependency>
```

- [ ] **Step 2: Write the git fixtures**

`src/test/java/com/graphify/testsupport/GitFixtures.java`:

```java
package com.graphify.testsupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;

/** Local bare git repositories on branch {@code main}, reachable by {@code file://} URLs. */
public final class GitFixtures {

    private GitFixtures() {
    }

    public static Path bareRepository(Path dir, String name, Map<String, String> files) throws IOException {
        Path bare = dir.resolve(name + ".git");
        try {
            Git.init().setBare(true).setInitialBranch("main").setDirectory(bare.toFile()).call().close();
            Path seed = dir.resolve(name + "-seed");
            try (Git git = Git.init().setInitialBranch("main").setDirectory(seed.toFile()).call()) {
                git.remoteAdd().setName("origin").setUri(new URIish(url(bare))).call();
                write(seed, files);
                git.add().addFilepattern(".").call();
                git.commit().setMessage("initial").setAuthor("t", "t@t").setCommitter("t", "t@t").call();
                git.push().setRemote("origin").setRefSpecs(new RefSpec("main:main")).call();
            }
            return bare;
        } catch (GitAPIException | java.net.URISyntaxException e) {
            throw new IOException(e);
        }
    }

    public static String commit(Path bare, Map<String, String> files, String message) throws IOException {
        Path work = Files.createTempDirectory("git-fixture");
        try (Git git = Git.cloneRepository().setURI(url(bare)).setDirectory(work.toFile()).setBranch("main").call()) {
            write(work, files);
            git.add().addFilepattern(".").call();
            String id = git.commit().setMessage(message).setAuthor("t", "t@t").setCommitter("t", "t@t").call().name();
            git.push().setRemote("origin").setRefSpecs(new RefSpec("main:main")).call();
            return id;
        } catch (GitAPIException e) {
            throw new IOException(e);
        }
    }

    public static String url(Path bare) {
        return bare.toUri().toString();
    }

    private static void write(Path root, Map<String, String> files) throws IOException {
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path path = root.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue());
        }
    }
}
```

- [ ] **Step 3: Write the failing test**

`src/test/java/com/graphify/workspace/GitWorkspaceTest.java`:

```java
package com.graphify.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.graphify.OracleIntegrationTest;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import com.graphify.testsupport.GitFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

class GitWorkspaceTest extends OracleIntegrationTest {

    @Autowired
    GitWorkspace workspace;

    @Autowired
    AppSettings settings;

    @TempDir
    Path dir;

    private String originalWorkspace;
    private Path bare;

    @BeforeEach
    void setUp() throws Exception {
        originalWorkspace = settings.getString(SettingKeys.INDEX_WORKSPACE_DIR);
        settings.update(SettingKeys.INDEX_WORKSPACE_DIR, dir.resolve("ws").toString(), "test");
        bare = GitFixtures.bareRepository(dir, "app", Map.of("src/main/java/p/A.java", "package p; class A {}"));
    }

    @AfterEach
    void tearDown() {
        settings.update(SettingKeys.INDEX_WORKSPACE_DIR, originalWorkspace, "test");
    }

    @Test
    void readsTheRemoteHeadWithoutCloning() {
        RemoteHead head = workspace.remoteHead(GitFixtures.url(bare), null, null);

        assertThat(head.branch()).isEqualTo("main");
        assertThat(head.commit()).hasSize(40);
        assertThat(Files.exists(dir.resolve("ws"))).isFalse();
    }

    @Test
    void clonesShallowThenFetchesAndResets() throws Exception {
        Path target = workspace.directoryFor(7, "SHOP", "app/../x");
        RemoteHead first = workspace.remoteHead(GitFixtures.url(bare), null, null);

        assertThat(workspace.checkout(target, GitFixtures.url(bare), "main", null, null)).isEqualTo(first.commit());
        assertThat(target.getFileName().toString()).doesNotContain("/").doesNotContain("..");
        assertThat(Files.exists(target.resolve(".git/shallow"))).isTrue();

        String second = GitFixtures.commit(bare, Map.of("src/main/java/p/B.java", "package p; class B {}"), "add B");
        Files.writeString(target.resolve("stray.txt"), "untracked");
        Files.writeString(target.resolve("src/main/java/p/A.java"), "locally modified");

        assertThat(workspace.checkout(target, GitFixtures.url(bare), "main", null, null)).isEqualTo(second);
        assertThat(target.resolve("src/main/java/p/B.java")).exists();
        assertThat(target.resolve("stray.txt")).doesNotExist();
        assertThat(Files.readString(target.resolve("src/main/java/p/A.java"))).isEqualTo("package p; class A {}");
    }

    @Test
    void corruptWorkspaceIsRecloned() throws Exception {
        Path target = workspace.directoryFor(7, "SHOP", "app");
        workspace.checkout(target, GitFixtures.url(bare), "main", null, null);
        Files.delete(target.resolve(".git/HEAD"));
        Files.writeString(target.resolve(".git/config"), "this is not git config [[[");

        String commit = workspace.checkout(target, GitFixtures.url(bare), "main", null, null);

        assertThat(commit).isEqualTo(workspace.remoteHead(GitFixtures.url(bare), null, null).commit());
        assertThat(target.resolve("src/main/java/p/A.java")).exists();
    }

    @Test
    void failuresNeverExposeCredentials() {
        String url = "https://bob:hunter2@127.0.0.1:1/scm/x.git";

        assertThatThrownBy(() -> workspace.remoteHead(url, "bob", "tok-secret-1"))
                .isInstanceOf(GitException.class)
                .hasMessageNotContaining("hunter2")
                .hasMessageNotContaining("tok-secret-1");
        assertThatThrownBy(() -> workspace.checkout(dir.resolve("never"), url, "main", "bob", "tok-secret-1"))
                .isInstanceOf(GitException.class)
                .hasMessageNotContaining("hunter2")
                .hasMessageNotContaining("tok-secret-1");
    }
}
```

- [ ] **Step 4: Run it to verify it fails**

Run: `./mvnw test -Dtest=GitWorkspaceTest`
Expected: BUILD FAILURE: `cannot find symbol` for `GitWorkspace`, `RemoteHead` and `GitException`.

- [ ] **Step 5: Write the workspace classes**

`src/main/java/com/graphify/workspace/RemoteHead.java`:

```java
package com.graphify.workspace;

/** A repository's default branch (short name) and the commit it points to. */
public record RemoteHead(String branch, String commit) {
}
```

`src/main/java/com/graphify/workspace/GitException.java`:

```java
package com.graphify.workspace;

import com.graphify.scm.UrlMasking;

/** A git operation failed. Messages are masked and never carry credentials. */
public class GitException extends RuntimeException {

    public GitException(String message) {
        super(UrlMasking.mask(message));
    }
}
```

`src/main/java/com/graphify/workspace/GitWorkspace.java`:

```java
package com.graphify.workspace;

import com.graphify.scm.AuthorizationHeader;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.TransportHttp;
import org.springframework.stereotype.Component;

/** Shallow clones and updates of repositories under index.workspace_dir (spec §3.2 steps 2–3, §8 clone rows). */
@Component
public class GitWorkspace {

    private static final String HEADS = "refs/heads/";

    private final AppSettings settings;

    public GitWorkspace(AppSettings settings) {
        this.settings = settings;
    }

    public Path directoryFor(long connectionId, String projectKey, String slug) {
        return Path.of(settings.getString(SettingKeys.INDEX_WORKSPACE_DIR))
                .resolve(Long.toString(connectionId)).resolve(safe(projectKey)).resolve(safe(slug));
    }

    public RemoteHead remoteHead(String cloneUrl, String username, String secret) {
        try {
            Map<String, Ref> refs = configure(Git.lsRemoteRepository().setRemote(cloneUrl), username, secret)
                    .callAsMap();
            Ref head = refs.get("HEAD");
            if (head == null) {
                throw new GitException("Remote " + cloneUrl + " advertises no HEAD");
            }
            if (head.isSymbolic()) {
                Ref target = refs.getOrDefault(head.getTarget().getName(), head.getTarget());
                return new RemoteHead(shortName(head.getTarget().getName()), id(target));
            }
            ObjectId commit = head.getObjectId();
            Optional<Ref> branch = Stream.of("main", "master").map(name -> refs.get(HEADS + name))
                    .filter(ref -> ref != null && commit.equals(ref.getObjectId())).findFirst();
            Ref chosen = branch.orElseGet(() -> refs.values().stream()
                    .filter(ref -> ref.getName().startsWith(HEADS) && commit.equals(ref.getObjectId()))
                    .min(Comparator.comparing(Ref::getName))
                    .orElseThrow(() -> new GitException("Remote " + cloneUrl + " has no branch at HEAD")));
            return new RemoteHead(shortName(chosen.getName()), commit.name());
        } catch (GitException e) {
            throw e;
        } catch (Exception e) {
            throw new GitException("ls-remote " + cloneUrl + " failed: " + e.getMessage());
        }
    }

    public String checkout(Path directory, String cloneUrl, String branch, String username, String secret) {
        if (Files.isDirectory(directory.resolve(".git"))) {
            try {
                return update(directory, branch, username, secret);
            } catch (Exception e) {
                deleteRecursively(directory);
            }
        } else if (Files.exists(directory)) {
            deleteRecursively(directory);
        }
        try {
            return cloneFresh(directory, cloneUrl, branch, username, secret);
        } catch (Exception first) {
            deleteRecursively(directory);
            try {
                return cloneFresh(directory, cloneUrl, branch, username, secret);
            } catch (Exception second) {
                deleteRecursively(directory);
                throw new GitException("clone " + cloneUrl + " (" + branch + ") failed: " + second.getMessage());
            }
        }
    }

    private String cloneFresh(Path directory, String cloneUrl, String branch, String username, String secret)
            throws Exception {
        Files.createDirectories(directory.getParent());
        try (Git git = configure(Git.cloneRepository().setURI(cloneUrl).setDirectory(directory.toFile())
                .setBranch(branch).setCloneAllBranches(false).setDepth(settings.getInt(SettingKeys.INDEX_GIT_DEPTH)),
                username, secret).call()) {
            return git.getRepository().resolve("HEAD").name();
        }
    }

    private String update(Path directory, String branch, String username, String secret) throws Exception {
        try (Git git = Git.open(directory.toFile())) {
            String remoteRef = "refs/remotes/origin/" + branch;
            configure(git.fetch().setRemote("origin").setRefSpecs(new RefSpec("+" + HEADS + branch + ":" + remoteRef))
                    .setDepth(settings.getInt(SettingKeys.INDEX_GIT_DEPTH)), username, secret).call();
            ObjectId target = git.getRepository().resolve(remoteRef);
            if (target == null) {
                throw new GitException("fetch did not produce " + remoteRef);
            }
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(target.name()).call();
            git.clean().setCleanDirectories(true).setForce(true).setIgnore(false).call();
            return target.name();
        }
    }

    private <C extends TransportCommand<C, ?>> C configure(C command, String username, String secret) {
        int timeoutSeconds = (int) Math.max(1, settings.getDuration(SettingKeys.INDEX_GIT_TIMEOUT).toSeconds());
        Optional<String> header = AuthorizationHeader.of(username, secret);
        return command.setTimeout(timeoutSeconds).setTransportConfigCallback(transport -> {
            if (transport instanceof TransportHttp http && header.isPresent()) {
                http.setAdditionalHeaders(Map.of("Authorization", header.get()));
            }
        });
    }

    private static String id(Ref ref) {
        if (ref.getObjectId() == null) {
            throw new GitException("Remote branch " + ref.getName() + " has no commit");
        }
        return ref.getObjectId().name();
    }

    private static String shortName(String refName) {
        return refName.startsWith(HEADS) ? refName.substring(HEADS.length()) : refName;
    }

    private static String safe(String segment) {
        return segment.replaceAll("[^A-Za-z0-9._-]", "_").replace("..", "__");
    }

    private static void deleteRecursively(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new GitException("could not delete workspace " + directory + ": " + e.getMessage());
        }
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./mvnw test -Dtest=GitWorkspaceTest`
Expected: 4 tests pass.

For `failuresNeverExposeCredentials`, the URL points at port 1 on localhost, so the connection is refused at once. If JGit's message for that failure includes the URL, the `GitException` constructor masks it. Do not weaken the assertion.

Run: `./mvnw test`
Expected: all tests pass.

- [ ] **Step 7: Commit**

```bash
git add pom.xml src/main/java/com/graphify/workspace src/test/java/com/graphify/workspace src/test/java/com/graphify/testsupport/GitFixtures.java
git commit -m "feat(workspace): shallow clone, update and self-heal repository workspaces with JGit" -m "<your harness Co-Authored-By trailer>"
```

---

### Task 6: Maven project model

**Files:**
- Create: `src/main/java/com/graphify/maven/MavenModule.java`, `MavenProject.java`, `MavenProjectReader.java`
- Test: `src/test/java/com/graphify/maven/MavenProjectReaderTest.java`

**Interfaces:**
- Consumes `DependencyRecord` (Task 2).
- Produces:
  - `public record MavenModule(String path, String groupId, String artifactId, String version, List<Path> sourceRoots, List<DependencyRecord> dependencies, boolean hasPom)`. `path` is relative to the checkout, uses `/` separators, and the root is `"."`. `sourceRoots` are absolute paths, and every one of them exists.
  - `public record MavenProject(List<MavenModule> modules, List<String> warnings)`.
  - `public class MavenProjectReader` (a `@Component`) with `MavenProject read(Path root, List<String> sourceRootPatterns)`.

Behaviour of `read`:

- **No root `pom.xml`.** Returns a single module `"."` with `sourceRoots = [root]` and `hasPom = false`.
- **Module tree.**
  - Modules are read from the root's `<modules>` recursively, in declaration order with the root first. Profile modules are not read.
  - A module whose pom is missing, outside the root, or unparsable is skipped and a warning is added.
  - If the root pom itself is unparsable, the result is the no-pom fallback plus a warning.
- **Coordinates.**
  - `groupId` and `version` fall back to `<parent>` when not set.
  - A parent pom inside the checkout is found through `relativePath`, which defaults to `../pom.xml`. That parent supplies properties and `dependencyManagement` along the chain.
- **Properties.**
  - `${name}` placeholders are resolved from the pom's own properties, then the parent chain. The built-ins `project.groupId`, `project.artifactId`, `project.version`, `project.parent.version`, `pom.version` and `version` are also available.
  - Substitution repeats up to 10 times to handle nested references.
  - A value that still contains `${` is treated as unresolved. That means null for versions, and the raw text elsewhere.
- **Dependencies.**
  - Only direct `<dependencies>` are read, not those under `<dependencyManagement>`.
  - A missing version is looked up in `dependencyManagement` of the pom and then its parents.
  - The scope defaults to `compile`.
- **Source roots.** Each pattern in `sourceRootPatterns` is resolved against the module directory. An interpolated `<build><sourceDirectory>` or `<testSourceDirectory>` is also used when present. Only existing directories are kept, without duplicates.
- **XML hardening.** XML is parsed with DOCTYPEs disallowed. That blocks XXE, and a pom carrying a DOCTYPE is treated as unparsable.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/graphify/maven/MavenProjectReaderTest.java`:

```java
package com.graphify.maven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.graphify.store.DependencyRecord;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenProjectReaderTest {

    private static final List<String> ROOTS = List.of("src/main/java", "src/test/java");

    @TempDir
    Path root;

    private final MavenProjectReader reader = new MavenProjectReader();

    private void write(String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private void mkdirs(String relative) throws Exception {
        Files.createDirectories(root.resolve(relative));
    }

    @Test
    void readsTheModuleTreeWithInheritedCoordinatesAndResolvedVersions() throws Exception {
        write("pom.xml", """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>com.shop</groupId><artifactId>shop</artifactId><version>2.1.0</version><packaging>pom</packaging>
                  <properties><lib.version>3.4.5</lib.version><revision>2.1.0</revision></properties>
                  <modules><module>core</module><module>app</module></modules>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>com.x</groupId><artifactId>lib</artifactId><version>${lib.version}</version></dependency>
                  </dependencies></dependencyManagement>
                </project>
                """);
        write("core/pom.xml", """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.shop</groupId><artifactId>shop</artifactId><version>2.1.0</version></parent>
                  <artifactId>core</artifactId>
                  <dependencies>
                    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId>
                      <version>5.12.0</version><scope>test</scope></dependency>
                  </dependencies>
                </project>
                """);
        write("app/pom.xml", """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.shop</groupId><artifactId>shop</artifactId><version>2.1.0</version></parent>
                  <artifactId>app</artifactId>
                  <build><sourceDirectory>src/java</sourceDirectory></build>
                  <dependencies>
                    <dependency><groupId>com.shop</groupId><artifactId>core</artifactId><version>${project.version}</version></dependency>
                    <dependency><groupId>com.x</groupId><artifactId>lib</artifactId></dependency>
                    <dependency><groupId>com.y</groupId><artifactId>unknown</artifactId><version>${no.such}</version></dependency>
                  </dependencies>
                </project>
                """);
        mkdirs("core/src/main/java");
        mkdirs("core/src/test/java");
        mkdirs("app/src/java");

        MavenProject project = reader.read(root, ROOTS);

        assertThat(project.warnings()).isEmpty();
        assertThat(project.modules()).extracting(MavenModule::path, MavenModule::groupId, MavenModule::artifactId,
                MavenModule::version).containsExactly(
                tuple(".", "com.shop", "shop", "2.1.0"),
                tuple("core", "com.shop", "core", "2.1.0"),
                tuple("app", "com.shop", "app", "2.1.0"));
        MavenModule core = project.modules().get(1);
        MavenModule app = project.modules().get(2);
        assertThat(core.sourceRoots()).containsExactly(root.resolve("core/src/main/java"), root.resolve("core/src/test/java"));
        assertThat(app.sourceRoots()).containsExactly(root.resolve("app/src/java"));
        assertThat(core.dependencies()).containsExactly(
                new DependencyRecord("org.junit.jupiter", "junit-jupiter", "5.12.0", "test"));
        assertThat(app.dependencies()).containsExactly(
                new DependencyRecord("com.shop", "core", "2.1.0", "compile"),
                new DependencyRecord("com.x", "lib", "3.4.5", "compile"),
                new DependencyRecord("com.y", "unknown", null, "compile"));
        assertThat(project.modules()).allMatch(MavenModule::hasPom);
    }

    @Test
    void aRepositoryWithoutAPomIsOneModuleRootedAtTheCheckout() throws Exception {
        write("src/p/A.java", "package p; class A {}");

        MavenProject project = reader.read(root, ROOTS);

        assertThat(project.modules()).singleElement().satisfies(module -> {
            assertThat(module.path()).isEqualTo(".");
            assertThat(module.sourceRoots()).containsExactly(root);
            assertThat(module.hasPom()).isFalse();
        });
    }

    @Test
    void brokenOrMissingModulePomsAreSkippedWithAWarning() throws Exception {
        write("pom.xml", """
                <project><groupId>g</groupId><artifactId>r</artifactId><version>1</version>
                  <modules><module>ok</module><module>broken</module><module>missing</module><module>../outside</module></modules>
                </project>
                """);
        write("ok/pom.xml", "<project><parent><groupId>g</groupId><artifactId>r</artifactId><version>1</version></parent>"
                + "<artifactId>ok</artifactId></project>");
        write("broken/pom.xml", "<project><artifactId>broken");

        MavenProject project = reader.read(root, ROOTS);

        assertThat(project.modules()).extracting(MavenModule::path).containsExactly(".", "ok");
        assertThat(project.warnings()).hasSize(3);
    }

    @Test
    void doctypesAreRejectedSoExternalEntitiesCannotBeRead() throws Exception {
        write("pom.xml", """
                <?xml version="1.0"?>
                <!DOCTYPE project [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <project><groupId>&xxe;</groupId><artifactId>r</artifactId><version>1</version></project>
                """);

        MavenProject project = reader.read(root, ROOTS);

        assertThat(project.modules()).singleElement().satisfies(m -> assertThat(m.hasPom()).isFalse());
        assertThat(project.warnings()).singleElement().asString().contains("pom.xml");
        assertThat(project.modules().getFirst().groupId()).isNull();
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw test -Dtest=MavenProjectReaderTest`
Expected: BUILD FAILURE: `cannot find symbol` for `MavenProjectReader`, `MavenModule` and `MavenProject`.

- [ ] **Step 3: Write the model and the reader**

`src/main/java/com/graphify/maven/MavenModule.java`:

```java
package com.graphify.maven;

import com.graphify.store.DependencyRecord;
import java.nio.file.Path;
import java.util.List;

/** One module of a checkout: its coordinates, existing source roots and declared dependencies. */
public record MavenModule(String path, String groupId, String artifactId, String version, List<Path> sourceRoots,
        List<DependencyRecord> dependencies, boolean hasPom) {

    public MavenModule {
        sourceRoots = List.copyOf(sourceRoots);
        dependencies = List.copyOf(dependencies);
    }
}
```

`src/main/java/com/graphify/maven/MavenProject.java`:

```java
package com.graphify.maven;

import java.util.List;

public record MavenProject(List<MavenModule> modules, List<String> warnings) {

    public MavenProject {
        modules = List.copyOf(modules);
        warnings = List.copyOf(warnings);
    }
}
```

`src/main/java/com/graphify/maven/MavenProjectReader.java`:

```java
package com.graphify.maven;

import com.graphify.store.DependencyRecord;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Reads a checkout's pom tree without running Maven (spec §3.2 step 4). */
@Component
public class MavenProjectReader {

    private static final String POM = "pom.xml";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");
    /** Enough rounds for nested property references; a cycle stops here instead of looping. */
    private static final int MAX_INTERPOLATION_ROUNDS = 10;

    private record Dependency(String groupId, String artifactId, String version, String scope) {
    }

    private record Pom(Path file, String groupId, String artifactId, String version, String parentGroupId,
            String parentArtifactId, String parentVersion, String parentRelativePath, Map<String, String> properties,
            List<String> modules, List<Dependency> dependencies, List<Dependency> managed, String sourceDirectory,
            String testSourceDirectory) {
    }

    public MavenProject read(Path root, List<String> sourceRootPatterns) {
        Path rootPom = root.resolve(POM);
        List<String> warnings = new ArrayList<>();
        if (!Files.isRegularFile(rootPom)) {
            return noPom(root, warnings);
        }
        Map<Path, Optional<Pom>> cache = new HashMap<>();
        if (load(rootPom, cache, warnings).isEmpty()) {
            return noPom(root, warnings);
        }
        List<MavenModule> modules = new ArrayList<>();
        Set<Path> visited = new LinkedHashSet<>();
        collect(root, root.normalize(), sourceRootPatterns, cache, warnings, modules, visited);
        return new MavenProject(modules, warnings);
    }

    private static MavenProject noPom(Path root, List<String> warnings) {
        return new MavenProject(List.of(new MavenModule(".", null, null, null, List.of(root), List.of(), false)),
                warnings);
    }

    private void collect(Path root, Path moduleDir, List<String> patterns, Map<Path, Optional<Pom>> cache,
            List<String> warnings, List<MavenModule> out, Set<Path> visited) {
        if (!visited.add(moduleDir)) {
            return;
        }
        Optional<Pom> loaded = load(moduleDir.resolve(POM), cache, warnings);
        if (loaded.isEmpty()) {
            return;
        }
        Pom pom = loaded.get();
        List<Pom> chain = chain(pom, root, cache, warnings);
        Map<String, String> properties = properties(chain);
        String groupId = interpolate(firstNonNull(pom.groupId(), pom.parentGroupId()), properties);
        String version = interpolate(firstNonNull(pom.version(), pom.parentVersion()), properties);
        String relative = root.relativize(moduleDir).toString().replace('\\', '/');
        out.add(new MavenModule(relative.isEmpty() ? "." : relative, groupId,
                interpolate(pom.artifactId(), properties), unresolvedToNull(version),
                sourceRoots(moduleDir, patterns, pom, properties), dependencies(pom, chain, properties), true));
        for (String module : pom.modules()) {
            Path child = moduleDir.resolve(module.strip()).normalize();
            if (!child.startsWith(root)) {
                warnings.add("Module '" + module + "' of " + relativeName(root, pom.file()) + " is outside the checkout");
                continue;
            }
            if (!Files.isRegularFile(child.resolve(POM))) {
                warnings.add("Module '" + module + "' of " + relativeName(root, pom.file()) + " has no pom.xml");
                continue;
            }
            collect(root, child, patterns, cache, warnings, out, visited);
        }
    }

    /** The pom followed by its parents found inside the checkout. */
    private List<Pom> chain(Pom pom, Path root, Map<Path, Optional<Pom>> cache, List<String> warnings) {
        List<Pom> chain = new ArrayList<>();
        Set<Path> seen = new LinkedHashSet<>();
        Pom current = pom;
        while (current != null && seen.add(current.file())) {
            chain.add(current);
            if (current.parentArtifactId() == null) {
                break;
            }
            Path parentPath = current.file().getParent()
                    .resolve(current.parentRelativePath() == null ? "../pom.xml" : current.parentRelativePath())
                    .normalize();
            if (Files.isDirectory(parentPath)) {
                parentPath = parentPath.resolve(POM);
            }
            if (!parentPath.startsWith(root) || !Files.isRegularFile(parentPath)) {
                break;
            }
            Optional<Pom> parent = load(parentPath, cache, warnings);
            current = parent.filter(p -> current.parentArtifactId().equals(p.artifactId())).orElse(null);
        }
        return chain;
    }

    private static Map<String, String> properties(List<Pom> chain) {
        Map<String, String> properties = new HashMap<>();
        for (int i = chain.size() - 1; i >= 0; i--) {
            properties.putAll(chain.get(i).properties());
        }
        Pom pom = chain.getFirst();
        String version = firstNonNull(pom.version(), pom.parentVersion());
        String groupId = firstNonNull(pom.groupId(), pom.parentGroupId());
        if (version != null) {
            properties.put("project.version", version);
            properties.put("pom.version", version);
            properties.put("version", version);
        }
        if (groupId != null) {
            properties.put("project.groupId", groupId);
        }
        if (pom.artifactId() != null) {
            properties.put("project.artifactId", pom.artifactId());
        }
        if (pom.parentVersion() != null) {
            properties.put("project.parent.version", pom.parentVersion());
        }
        return properties;
    }

    private static List<Path> sourceRoots(Path moduleDir, List<String> patterns, Pom pom, Map<String, String> properties) {
        Set<Path> roots = new LinkedHashSet<>();
        for (String declared : new String[] {pom.sourceDirectory(), pom.testSourceDirectory()}) {
            if (declared != null) {
                roots.add(moduleDir.resolve(interpolate(declared, properties)).normalize());
            }
        }
        if (pom.sourceDirectory() == null) {
            patterns.forEach(pattern -> roots.add(moduleDir.resolve(pattern).normalize()));
        }
        return roots.stream().filter(Files::isDirectory).toList();
    }

    private static List<DependencyRecord> dependencies(Pom pom, List<Pom> chain, Map<String, String> properties) {
        Map<String, String> managed = new LinkedHashMap<>();
        for (Pom link : chain) {
            for (Dependency dependency : link.managed()) {
                managed.putIfAbsent(dependency.groupId() + ":" + dependency.artifactId(), dependency.version());
            }
        }
        List<DependencyRecord> records = new ArrayList<>();
        for (Dependency dependency : pom.dependencies()) {
            String groupId = interpolate(dependency.groupId(), properties);
            String artifactId = interpolate(dependency.artifactId(), properties);
            String version = dependency.version() != null ? dependency.version()
                    : managed.get(dependency.groupId() + ":" + dependency.artifactId());
            String scope = dependency.scope() == null ? "compile" : interpolate(dependency.scope(), properties);
            records.add(new DependencyRecord(groupId, artifactId, unresolvedToNull(interpolate(version, properties)),
                    scope));
        }
        return records;
    }

    private static String interpolate(String text, Map<String, String> properties) {
        if (text == null) {
            return null;
        }
        String result = text.strip();
        for (int round = 0; round < MAX_INTERPOLATION_ROUNDS && result.contains("${"); round++) {
            Matcher matcher = PLACEHOLDER.matcher(result);
            StringBuilder next = new StringBuilder();
            boolean changed = false;
            while (matcher.find()) {
                String value = properties.get(matcher.group(1));
                if (value != null) {
                    changed = true;
                }
                matcher.appendReplacement(next, Matcher.quoteReplacement(value != null ? value : matcher.group()));
            }
            matcher.appendTail(next);
            result = next.toString();
            if (!changed) {
                break;
            }
        }
        return result;
    }

    private static String unresolvedToNull(String value) {
        return value == null || value.contains("${") ? null : value;
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    private Optional<Pom> load(Path file, Map<Path, Optional<Pom>> cache, List<String> warnings) {
        Path key = file.normalize();
        Optional<Pom> cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        Optional<Pom> parsed;
        try {
            parsed = Optional.of(parse(key));
        } catch (Exception e) {
            warnings.add("Could not read " + key.getFileName() + " in " + key.getParent().getFileName() + ": "
                    + e.getClass().getSimpleName());
            parsed = Optional.empty();
        }
        cache.put(key, parsed);
        return parsed;
    }

    private static Pom parse(Path file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Element project = builder.parse(file.toFile()).getDocumentElement();
        Element parent = child(project, "parent");
        Map<String, String> properties = new LinkedHashMap<>();
        Element props = child(project, "properties");
        if (props != null) {
            for (Element property : children(props)) {
                properties.put(property.getTagName(), property.getTextContent().strip());
            }
        }
        List<String> modules = new ArrayList<>();
        Element modulesElement = child(project, "modules");
        if (modulesElement != null) {
            for (Element module : children(modulesElement)) {
                if (module.getTagName().equals("module")) {
                    modules.add(module.getTextContent().strip());
                }
            }
        }
        Element management = child(project, "dependencyManagement");
        Element build = child(project, "build");
        return new Pom(file, text(project, "groupId"), text(project, "artifactId"), text(project, "version"),
                parent == null ? null : text(parent, "groupId"), parent == null ? null : text(parent, "artifactId"),
                parent == null ? null : text(parent, "version"), parent == null ? null : text(parent, "relativePath"),
                properties, modules, dependencyList(child(project, "dependencies")),
                dependencyList(management == null ? null : child(management, "dependencies")),
                build == null ? null : text(build, "sourceDirectory"),
                build == null ? null : text(build, "testSourceDirectory"));
    }

    private static List<Dependency> dependencyList(Element dependencies) {
        List<Dependency> list = new ArrayList<>();
        if (dependencies == null) {
            return list;
        }
        for (Element dependency : children(dependencies)) {
            if (dependency.getTagName().equals("dependency") && text(dependency, "groupId") != null
                    && text(dependency, "artifactId") != null) {
                list.add(new Dependency(text(dependency, "groupId"), text(dependency, "artifactId"),
                        text(dependency, "version"), text(dependency, "scope")));
            }
        }
        return list;
    }

    private static Element child(Element parent, String name) {
        for (Element element : children(parent)) {
            if (element.getTagName().equals(name)) {
                return element;
            }
        }
        return null;
    }

    private static List<Element> children(Element parent) {
        List<Element> elements = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) {
                elements.add(element);
            }
        }
        return elements;
    }

    private static String text(Element parent, String name) {
        Element element = child(parent, name);
        if (element == null) {
            return null;
        }
        String value = element.getTextContent().strip();
        return value.isEmpty() ? null : value;
    }

    private static String relativeName(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw test -Dtest=MavenProjectReaderTest`
Expected: 4 tests pass.

- `brokenOrMissingModulePomsAreSkippedWithAWarning` expects three warnings: an unparsable `broken`, a `missing` pom, and `../outside`. If the count differs, print `project.warnings()` and check each one against the behaviour rules in the Interfaces block.
- The XXE test expects the DOCTYPE pom to be rejected (warning plus no-pom fallback).

Run: `./mvnw test`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphify/maven src/test/java/com/graphify/maven
git commit -m "feat(maven): read pom trees with inherited coordinates, properties and managed versions" -m "<your harness Co-Authored-By trailer>"
```

---

### Task 7: settings.xml and Maven classpath resolution

**Files:**
- Create: `src/main/java/com/graphify/maven/ArtifactRepository.java`, `ArtifactRepositories.java`, `SettingsXmlWriter.java`, `ClasspathResult.java`, `ClasspathResolver.java`
- Create: `src/test/java/com/graphify/testsupport/MavenFixtures.java`
- Test: `src/test/java/com/graphify/maven/SettingsXmlWriterTest.java`, `ClasspathResolverTest.java`

**Interfaces:**
- **Consumes:**
  - `MavenModule` (Task 6), `SecretCipher`, `UrlMasking`.
  - `AppSettings` keys: `INDEX_MAVEN_EXECUTABLE`, `INDEX_MAVEN_LOCAL_REPOSITORY`, `INDEX_MAVEN_TIMEOUT`, `INDEX_MAVEN_OUTPUT_TAIL_LINES`.
  - `TestJars` (plan 1).
- **Produces (types):**
  - `public record ArtifactRepository(long id, String name, String url, String username, String secret, String mirrorOf)`. Its `toString()` omits `secret`.
  - `public record ClasspathResult(Map<String, List<Path>> classpaths, String error)`. `classpaths` maps module path to entries for modules Maven produced a file for. `error` is null on success; otherwise it holds the masked last lines of Maven output, or a timeout or start-failure note.
- **Produces (beans and helpers):**
  - `public class ArtifactRepositories` (a `@Repository`) with `List<ArtifactRepository> enabled()`, ordered by `sort_order`, then `id`, with secrets decrypted.
  - `final class SettingsXmlWriter` with `static String write(List<ArtifactRepository> repositories)`:
    - Every repository becomes a `<server id="graphify-{id}">` when it has a username or secret.
    - A repository with `mirror_of` becomes a `<mirror>`.
    - A repository without `mirror_of` becomes a `<repository>` and `<pluginRepository>` in an always-active profile named `graphify`.
    - Built with DOM, so values are escaped.
  - `public class ClasspathResolver` (a `@Component`) with `ClasspathResult resolve(Path root, List<MavenModule> modules)`:
    - When no module has a pom, it returns an empty map and a null error, without running Maven.
    - Otherwise it writes `settings.xml` to a temp file (owner-only permissions where POSIX applies) and deletes stale classpath files.
    - It runs the Maven command below in `root`, merging output into a temp log, and waits `index.maven_timeout`.
    - On timeout it destroys the process tree.
    - It then reads `<module>/target/graphify-classpath.txt` for each module, split on `File.pathSeparator` and keeping only existing entries.
    - It deletes the settings file and the log in `finally`.

Maven command (the flags are fixed protocol facts):

```
<index.maven_executable> -B -q -fae -s <settings> -Dmaven.repo.local=<index.maven_local_repository>
  -Dmaven.main.skip=true -Dmaven.resources.skip=true -Dmaven.test.skip=true
  -Dmdep.outputFile=target/graphify-classpath.txt compile dependency:build-classpath
```

- `MavenFixtures` (test only) provides:
  - `static Path fileRepository(Path dir)`
  - `static void publish(Path repo, String groupId, String artifactId, String version, Path jar)`, which writes `<g>/<a>/<v>/<a>-<v>.jar` and `.pom`
  - `static String pom(String groupId, String artifactId, String version, String parentAndModulesXml, String dependenciesXml)`

Verified in a spike with Maven 3.9.11: the command produces a classpath file for every module of a reactor whose modules depend on each other, without an install. A sibling resolves to its `target/classes` directory, even when the sources do not compile. With `-fae`, a module whose dependency cannot be resolved fails alone.

- [ ] **Step 1: Write the Maven fixtures**

`src/test/java/com/graphify/testsupport/MavenFixtures.java`:

```java
package com.graphify.testsupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** A file-based Maven repository and pom snippets for tests that run Maven. */
public final class MavenFixtures {

    private MavenFixtures() {
    }

    public static Path fileRepository(Path dir) throws IOException {
        return Files.createDirectories(dir.resolve("maven-repo"));
    }

    public static void publish(Path repo, String groupId, String artifactId, String version, Path jar)
            throws IOException {
        Path folder = repo.resolve(groupId.replace('.', '/')).resolve(artifactId).resolve(version);
        Files.createDirectories(folder);
        Files.copy(jar, folder.resolve(artifactId + "-" + version + ".jar"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(folder.resolve(artifactId + "-" + version + ".pom"),
                pom(groupId, artifactId, version, "", ""));
    }

    public static String pom(String groupId, String artifactId, String version, String parentAndModulesXml,
            String dependenciesXml) {
        return """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                  %s
                  <groupId>%s</groupId><artifactId>%s</artifactId><version>%s</version>
                  <dependencies>%s</dependencies>
                </project>
                """.formatted(parentAndModulesXml, groupId, artifactId, version, dependenciesXml);
    }

    public static String dependency(String groupId, String artifactId, String version) {
        return "<dependency><groupId>" + groupId + "</groupId><artifactId>" + artifactId + "</artifactId><version>"
                + version + "</version></dependency>";
    }
}
```

- [ ] **Step 2: Write the failing tests**

`src/test/java/com/graphify/maven/SettingsXmlWriterTest.java`:

```java
package com.graphify.maven;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SettingsXmlWriterTest {

    @Test
    void writesServersMirrorsAndAnActiveProfileWithEscapedValues() {
        String xml = SettingsXmlWriter.write(List.of(
                new ArtifactRepository(1, "nexus", "https://nexus/repo?a=1&b=2", "ci", "p<w>d", "*"),
                new ArtifactRepository(2, "extra", "file:///repo", null, null, null)));

        assertThat(xml).contains("<id>graphify-1</id>", "<username>ci</username>", "<password>p&lt;w&gt;d</password>",
                "<mirrorOf>*</mirrorOf>", "<url>https://nexus/repo?a=1&amp;b=2</url>",
                "<activeProfile>graphify</activeProfile>", "<url>file:///repo</url>");
        assertThat(xml).doesNotContain("<id>graphify-2</id><username>");
        assertThat(xml.indexOf("<id>graphify-2</id>")).isGreaterThan(xml.indexOf("<profile>"));
    }

    @Test
    void artifactRepositoryToStringOmitsTheSecret() {
        assertThat(new ArtifactRepository(1, "n", "u", "user", "S3CR3T", null).toString()).doesNotContain("S3CR3T");
    }
}
```

`src/test/java/com/graphify/maven/ClasspathResolverTest.java`:

```java
package com.graphify.maven;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.indexer.TestJars;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import com.graphify.testsupport.MavenFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

/** Runs the real `mvn` on the PATH against a file-based repository; plugins come from the developer's ~/.m2. */
class ClasspathResolverTest extends OracleIntegrationTest {

    private static final String GROUP = "com.graphify.testfixture";

    @Autowired
    ClasspathResolver resolver;

    @Autowired
    MavenProjectReader reader;

    @Autowired
    AppSettings settings;

    @TempDir
    Path dir;

    private final Map<String, String> originals = new HashMap<>();
    private Path project;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM artifact_repository");
        change(SettingKeys.INDEX_MAVEN_LOCAL_REPOSITORY,
                Path.of(System.getProperty("user.home"), ".m2", "repository").toString());
        Path repo = MavenFixtures.fileRepository(dir);
        Path jar = TestJars.jar(dir, "fixture-lib", Map.of("com/graphify/testfixture/Lib.java",
                "package com.graphify.testfixture; public class Lib { public static int one() { return 1; } }"), Set.of());
        MavenFixtures.publish(repo, GROUP, "fixture-lib", "1.0.0", jar);
        jdbc.update("INSERT INTO artifact_repository (name, url, sort_order) VALUES ('fixture', ?, 0)",
                repo.toUri().toString());

        project = dir.resolve("project");
        String parent = "<parent><groupId>" + GROUP + "</groupId><artifactId>root</artifactId><version>1</version></parent>";
        write("pom.xml", MavenFixtures.pom(GROUP, "root", "1",
                "<packaging>pom</packaging><modules><module>a</module><module>b</module><module>c</module></modules>", ""));
        write("a/pom.xml", MavenFixtures.pom(GROUP, "a", "1", parent, MavenFixtures.dependency(GROUP, "fixture-lib", "1.0.0")));
        write("b/pom.xml", MavenFixtures.pom(GROUP, "b", "1", parent, MavenFixtures.dependency(GROUP, "a", "1")));
        write("c/pom.xml", MavenFixtures.pom(GROUP, "c", "1", parent, MavenFixtures.dependency(GROUP, "missing", "9")));
        write("a/src/main/java/a/A.java", "package a; public class A {}");
        write("b/src/main/java/b/B.java", "package b; public class B { int x = ; }");
        write("c/src/main/java/c/C.java", "package c; public class C {}");
    }

    @AfterEach
    void tearDown() {
        originals.forEach((key, value) -> settings.update(key, value, "test"));
        jdbc.update("DELETE FROM artifact_repository");
    }

    private void change(String key, String value) {
        originals.putIfAbsent(key, settings.all().stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow()
                .value());
        settings.update(key, value, "test");
    }

    private void write(String relative, String content) throws Exception {
        Path file = project.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private ClasspathResult resolve() {
        return resolver.resolve(project, reader.read(project, List.of("src/main/java")).modules());
    }

    @Test
    void siblingModulesResolveWithoutInstallAndAFailingModuleFailsAlone() {
        ClasspathResult result = resolve();

        assertThat(result.classpaths()).containsKeys("a", "b").doesNotContainKey("c");
        assertThat(result.classpaths().get("a")).anySatisfy(p -> assertThat(p.toString()).endsWith("fixture-lib-1.0.0.jar"));
        assertThat(result.classpaths().get("b")).anySatisfy(p -> assertThat(p.toString()).endsWith("fixture-lib-1.0.0.jar"));
        assertThat(result.error()).isNotNull().contains("missing");
        assertThat(project.resolve("b/target/graphify-classpath.txt")).exists();
    }

    @Test
    void hungMavenIsKilledAtTheTimeout() {
        change(SettingKeys.INDEX_MAVEN_TIMEOUT, "PT0.2S");

        ClasspathResult result = resolve();

        assertThat(result.classpaths()).isEmpty();
        assertThat(result.error()).contains("timed out");
    }

    @Test
    void aMissingMavenExecutableIsReportedNotThrown() {
        change(SettingKeys.INDEX_MAVEN_EXECUTABLE, dir.resolve("no-such-mvn").toString());

        ClasspathResult result = resolve();

        assertThat(result.classpaths()).isEmpty();
        assertThat(result.error()).contains("could not start");
    }

    @Test
    void aRepositoryWithoutPomsNeedsNoMaven() throws Exception {
        Path plain = dir.resolve("plain");
        Files.createDirectories(plain.resolve("src"));

        ClasspathResult result = resolver.resolve(plain, reader.read(plain, List.of("src/main/java")).modules());

        assertThat(result.classpaths()).isEmpty();
        assertThat(result.error()).isNull();
    }
}
```

- [ ] **Step 3: Run them to verify they fail**

Run: `./mvnw test -Dtest='SettingsXmlWriterTest,ClasspathResolverTest'`
Expected: BUILD FAILURE: `cannot find symbol` for the new `maven` classes.

- [ ] **Step 4: Write the artifact repository model and settings.xml writer**

`src/main/java/com/graphify/maven/ArtifactRepository.java`:

```java
package com.graphify.maven;

/** A Maven repository from artifact_repository with its secret decrypted; toString never shows the secret. */
public record ArtifactRepository(long id, String name, String url, String username, String secret, String mirrorOf) {

    @Override
    public String toString() {
        return "ArtifactRepository[id=" + id + ", name=" + name + ", url=" + url + ", mirrorOf=" + mirrorOf + "]";
    }
}
```

`src/main/java/com/graphify/maven/ArtifactRepositories.java`:

```java
package com.graphify.maven;

import com.graphify.common.crypto.SecretCipher;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ArtifactRepositories {

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;

    public ArtifactRepositories(JdbcTemplate jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    public List<ArtifactRepository> enabled() {
        return jdbc.query("""
                SELECT id, name, url, username, secret_enc, mirror_of FROM artifact_repository
                 WHERE enabled = 1 ORDER BY sort_order, id
                """, (rs, row) -> {
                    String secret = rs.getString("secret_enc");
                    return new ArtifactRepository(rs.getLong("id"), rs.getString("name"), rs.getString("url"),
                            rs.getString("username"), secret == null ? null : cipher.decrypt(secret),
                            rs.getString("mirror_of"));
                });
    }
}
```

`src/main/java/com/graphify/maven/SettingsXmlWriter.java`:

```java
package com.graphify.maven;

import java.io.StringWriter;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Builds the Maven settings.xml for one resolution from artifact_repository rows (spec §6.4). */
final class SettingsXmlWriter {

    /** Profile that carries repositories without mirror_of; always active. */
    private static final String PROFILE = "graphify";

    private SettingsXmlWriter() {
    }

    static String write(List<ArtifactRepository> repositories) {
        try {
            Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
            Element settings = document.createElement("settings");
            document.appendChild(settings);
            Element servers = append(document, settings, "servers");
            Element mirrors = append(document, settings, "mirrors");
            Element profile = append(document, append(document, settings, "profiles"), "profile");
            text(document, profile, "id", PROFILE);
            Element repos = append(document, profile, "repositories");
            Element pluginRepos = append(document, profile, "pluginRepositories");
            for (ArtifactRepository repository : repositories) {
                String id = "graphify-" + repository.id();
                if (repository.username() != null || repository.secret() != null) {
                    Element server = append(document, servers, "server");
                    text(document, server, "id", id);
                    if (repository.username() != null) {
                        text(document, server, "username", repository.username());
                    }
                    if (repository.secret() != null) {
                        text(document, server, "password", repository.secret());
                    }
                }
                if (repository.mirrorOf() != null && !repository.mirrorOf().isBlank()) {
                    Element mirror = append(document, mirrors, "mirror");
                    text(document, mirror, "id", id);
                    text(document, mirror, "mirrorOf", repository.mirrorOf());
                    text(document, mirror, "url", repository.url());
                } else {
                    for (Element parent : new Element[] {repos, pluginRepos}) {
                        Element repo = append(document, parent,
                                parent == repos ? "repository" : "pluginRepository");
                        text(document, repo, "id", id);
                        text(document, repo, "url", repository.url());
                        text(document, append(document, repo, "releases"), "enabled", "true");
                        text(document, append(document, repo, "snapshots"), "enabled", "true");
                    }
                }
            }
            text(document, append(document, settings, "activeProfiles"), "activeProfile", PROFILE);
            var transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "no");
            StringWriter out = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(out));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Could not build settings.xml", e);
        }
    }

    private static Element append(Document document, Element parent, String name) {
        Element element = document.createElement(name);
        parent.appendChild(element);
        return element;
    }

    private static void text(Document document, Element parent, String name, String value) {
        append(document, parent, name).setTextContent(value);
    }
}
```

- [ ] **Step 5: Write the classpath resolver**

`src/main/java/com/graphify/maven/ClasspathResult.java`:

```java
package com.graphify.maven;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Classpath per module path (only modules Maven produced one for); {@code error} is null when Maven succeeded. */
public record ClasspathResult(Map<String, List<Path>> classpaths, String error) {

    public ClasspathResult {
        classpaths = Map.copyOf(classpaths);
    }
}
```

`src/main/java/com/graphify/maven/ClasspathResolver.java`:

```java
package com.graphify.maven;

import com.graphify.scm.UrlMasking;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Resolves each module's classpath with one Maven run per checkout (spec §3.2 step 5). The compile phase is entered
 * with compilation skipped, so sibling modules resolve to their target/classes without an install and broken sources
 * do not matter; -fae lets every resolvable module produce its file even if another module fails.
 */
@Component
public class ClasspathResolver {

    /** File the maven-dependency-plugin writes per module (relative to the module's basedir). */
    static final String OUTPUT_FILE = "target/graphify-classpath.txt";

    private final AppSettings settings;
    private final ArtifactRepositories repositories;

    public ClasspathResolver(AppSettings settings, ArtifactRepositories repositories) {
        this.settings = settings;
        this.repositories = repositories;
    }

    public ClasspathResult resolve(Path root, List<MavenModule> modules) {
        if (modules.stream().noneMatch(MavenModule::hasPom)) {
            return new ClasspathResult(Map.of(), null);
        }
        Path settingsFile = null;
        Path log = null;
        try {
            for (MavenModule module : modules) {
                Files.deleteIfExists(moduleDir(root, module).resolve(OUTPUT_FILE));
            }
            settingsFile = privateTempFile("graphify-settings", ".xml");
            Files.writeString(settingsFile, SettingsXmlWriter.write(repositories.enabled()), StandardCharsets.UTF_8);
            log = privateTempFile("graphify-maven", ".log");
            String error = run(root, settingsFile, log);
            Map<String, List<Path>> classpaths = new LinkedHashMap<>();
            for (MavenModule module : modules) {
                Path output = moduleDir(root, module).resolve(OUTPUT_FILE);
                if (Files.isRegularFile(output)) {
                    classpaths.put(module.path(), entries(Files.readString(output)));
                }
            }
            return new ClasspathResult(classpaths, error);
        } catch (IOException e) {
            return new ClasspathResult(Map.of(), "Maven classpath resolution failed: " + UrlMasking.mask(e.getMessage()));
        } finally {
            deleteQuietly(settingsFile);
            deleteQuietly(log);
        }
    }

    private String run(Path root, Path settingsFile, Path log) throws IOException {
        List<String> command = List.of(settings.getString(SettingKeys.INDEX_MAVEN_EXECUTABLE), "-B", "-q", "-fae",
                "-s", settingsFile.toString(),
                "-Dmaven.repo.local=" + settings.getString(SettingKeys.INDEX_MAVEN_LOCAL_REPOSITORY),
                "-Dmaven.main.skip=true", "-Dmaven.resources.skip=true", "-Dmaven.test.skip=true",
                "-Dmdep.outputFile=" + OUTPUT_FILE, "compile", "dependency:build-classpath");
        Process process;
        try {
            process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true)
                    .redirectOutput(log.toFile()).start();
        } catch (IOException e) {
            return "Maven could not start (" + command.getFirst() + "): " + e.getMessage();
        }
        Duration timeout = settings.getDuration(SettingKeys.INDEX_MAVEN_TIMEOUT);
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                return "Maven timed out after " + timeout;
            }
        } catch (InterruptedException e) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return "Maven was interrupted";
        }
        return process.exitValue() == 0 ? null : "Maven exited with " + process.exitValue() + ":\n" + tail(log);
    }

    private String tail(Path log) throws IOException {
        List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
        int keep = settings.getInt(SettingKeys.INDEX_MAVEN_OUTPUT_TAIL_LINES);
        return UrlMasking.mask(String.join("\n", lines.subList(Math.max(0, lines.size() - keep), lines.size())));
    }

    private static List<Path> entries(String classpath) {
        List<Path> entries = new ArrayList<>();
        Arrays.stream(classpath.strip().split(File.pathSeparator)).map(String::strip).filter(s -> !s.isEmpty())
                .map(Path::of).filter(Files::exists).forEach(entries::add);
        return entries;
    }

    private static Path moduleDir(Path root, MavenModule module) {
        return module.path().equals(".") ? root : root.resolve(module.path());
    }

    private static Path privateTempFile(String prefix, String suffix) throws IOException {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return Files.createTempFile(prefix, suffix,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        return Files.createTempFile(prefix, suffix);
    }

    private static void deleteQuietly(Path file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // a leftover temp file is harmless; the settings file is owner-only
            }
        }
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw test -Dtest='SettingsXmlWriterTest,ClasspathResolverTest'`
Expected: 6 tests pass. The first Maven run may take a few seconds.

If `siblingModulesResolveWithoutInstallAndAFailingModuleFailsAlone` fails:
- Check the actual exception before changing anything. Plugin download failures with no network show up in `result.error()`.
- Do not weaken the assertion that `b` resolves.
- If `fixture-lib` resolves from `~/.m2` but `missing` fails, the test is behaving as intended.

`hungMavenIsKilledAtTheTimeout` relies on Maven needing more than 200 ms to start, which it always does.

Run: `./mvnw test`
Expected: all tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/graphify/maven src/test/java/com/graphify/maven src/test/java/com/graphify/testsupport/MavenFixtures.java
git commit -m "feat(maven): resolve per-module classpaths with generated settings.xml and a timeout" -m "<your harness Co-Authored-By trailer>"
```

---

### Task 8: One-repository indexing pipeline, run records and end-to-end acceptance

**Files:**
- Create: `src/main/java/com/graphify/indexing/RepoIndexStatus.java`, `RepoIndexOutcome.java`, `IndexRunRecorder.java`, `RepositoryIndexer.java`
- Test: `src/test/java/com/graphify/indexing/RepositoryIndexerTest.java`

**Interfaces:**
- **Consumes:**
  - Everything from Tasks 2–7.
  - `JavaRepositoryIndexer`, `IndexRequest`, `ModuleSource` and `IndexerOptions` (plan 1).
  - `RepositoryIndexWriter.replace` and `WriteSummary` (plan 2).
  - `ImpactService` (plan 3), in the test.
- **Produces (types):**
  - `public enum RepoIndexStatus` with the spec §8 values.
  - `public record RepoIndexOutcome(RepoIndexStatus status, String commit, String classpathMode, String error, int symbols, int usages, int warnings, long durationMillis)`.
- **Produces (`IndexRunRecorder`, a `@Repository`):**
  - `long start(String trigger, String scope, Long scopeId, String startedBy)` inserts a RUNNING `index_run` and returns its id.
  - `void record(long runId, long repositoryId, RepoIndexOutcome outcome)`.
  - `void finish(long runId, String status)`.
- **Produces (`RepositoryIndexer`, a `@Service`):** `RepoIndexOutcome index(long runId, long repositoryId, boolean force)`. Steps:
  1. Load the active `scm_repository` row and its `ScmConnection`. A missing row throws `RepositoryNotFoundException`.
  2. Call `remoteHead`. On failure the status is `CLONE_FAILED`. Otherwise store `default_branch`.
  3. Unless `force`, a head commit equal to `last_indexed_commit` gives `SKIPPED_UNCHANGED`.
  4. Call `checkout`. On failure the status is `CLONE_FAILED`.
  5. Read the Maven project with `index.source_roots`. If no source root contains a `.java` file, the status is `SKIPPED_NOT_JAVA`.
  6. Resolve classpaths.
  7. Index with `IndexerOptions(index.parse_batch_size, usage.snippet_max_length)`.
  8. Call `writer.replace`. On a `PessimisticLockingFailureException` it is retried once. Module records carry the module's classpath mode and declared dependencies.
  9. If every module with sources is FULL, the status is `SUCCESS`; otherwise it is `SUCCESS_PARTIAL`. Store `last_status`.
  10. Record the `index_run_repo` row.
- **Error rules:**
  - Any other exception in steps 5–8 gives `FAILED`. The previous index is kept, because the writer is transactional.
  - The error text is masked and cut to 4000 bytes.
  - Every outcome is recorded, and `last_status` is set for every outcome except `SKIPPED_UNCHANGED`, which keeps the previous status.

- [ ] **Step 1: Write the failing end-to-end test**

`src/test/java/com/graphify/indexing/RepositoryIndexerTest.java`:

```java
package com.graphify.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.common.crypto.SecretCipher;
import com.graphify.impact.ImpactEdge;
import com.graphify.impact.ImpactRequest;
import com.graphify.impact.ImpactResult;
import com.graphify.impact.ImpactService;
import com.graphify.indexer.TestJars;
import com.graphify.indexer.model.Confidence;
import com.graphify.scm.RepositorySync;
import com.graphify.scm.ScmConnections;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import com.graphify.store.StoreFixtures;
import com.graphify.testsupport.FakeBitbucket;
import com.graphify.testsupport.GitFixtures;
import com.graphify.testsupport.MavenFixtures;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Spec §11 acceptance: a fake Bitbucket lists two git repositories (Maven projects), the pipeline clones, resolves
 * the classpath through a file-based Maven repository, indexes and writes them, and impact analysis then finds the
 * cross-repository caller EXACTly.
 */
class RepositoryIndexerTest extends OracleIntegrationTest {

    private static final String TOKEN = "bb-token-123";

    @Autowired
    RepositoryIndexer indexer;

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

    @Autowired
    ImpactService impact;

    @TempDir
    Path dir;

    private final Map<String, String> originals = new HashMap<>();
    private FakeBitbucket bitbucket;
    private Path apiBare;
    private long runId;

    @BeforeEach
    void setUp() throws Exception {
        StoreFixtures.cleanIndexTables(jdbc);
        change(SettingKeys.INDEX_WORKSPACE_DIR, dir.resolve("ws").toString());
        change(SettingKeys.INDEX_MAVEN_LOCAL_REPOSITORY,
                Path.of(System.getProperty("user.home"), ".m2", "repository").toString());

        Path shop = fixture();
        Map<String, String> libSources = sources(shop.resolve("shop-lib/src/main/java"), "src/main/java/");
        Map<String, String> apiSources = sources(shop.resolve("shop-api/src/main/java"), "src/main/java/");

        Path repo = MavenFixtures.fileRepository(dir);
        Map<String, String> libJarSources = new HashMap<>();
        libSources.forEach((k, v) -> libJarSources.put(k.substring("src/main/java/".length()), v));
        MavenFixtures.publish(repo, "com.shop", "shop-lib", "1.0.0", TestJars.jar(dir, "shop-lib", libJarSources, Set.of()));
        jdbc.update("INSERT INTO artifact_repository (name, url) VALUES ('fixture', ?)", repo.toUri().toString());

        Map<String, String> lib = new HashMap<>(libSources);
        lib.put("pom.xml", MavenFixtures.pom("com.shop", "shop-lib", "1.0.0", "", ""));
        Map<String, String> api = new HashMap<>(apiSources);
        api.put("pom.xml", MavenFixtures.pom("com.shop", "shop-api", "1.0.0", "",
                MavenFixtures.dependency("com.shop", "shop-lib", "1.0.0")));
        Path libBare = GitFixtures.bareRepository(dir, "shop-lib", lib);
        apiBare = GitFixtures.bareRepository(dir, "shop-api", api);
        Path docsBare = GitFixtures.bareRepository(dir, "docs", Map.of("README.md", "# docs only"));

        bitbucket = new FakeBitbucket().start().requireAuthorization("Bearer " + TOKEN)
                .addRepository("SHOP", "shop-lib", GitFixtures.url(libBare))
                .addRepository("SHOP", "shop-api", GitFixtures.url(apiBare))
                .addRepository("SHOP", "docs", GitFixtures.url(docsBare));
        jdbc.update("INSERT INTO scm_connection (name, type, base_url, secret_enc) VALUES ('corp', 'BITBUCKET_DC', ?, ?)",
                bitbucket.baseUrl(), cipher.encrypt(TOKEN));
        sync.sync(connections.enabled().getFirst());
        runId = runs.start("MANUAL", "ALL", null, "test");
    }

    @AfterEach
    void tearDown() {
        bitbucket.close();
        originals.forEach((key, value) -> settings.update(key, value, "test"));
    }

    private void change(String key, String value) {
        originals.putIfAbsent(key, settings.all().stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow()
                .value());
        settings.update(key, value, "test");
    }

    private long repo(String slug) {
        return jdbc.queryForObject("SELECT id FROM scm_repository WHERE slug = ?", Long.class, slug);
    }

    private long symbol(String key) {
        return jdbc.queryForObject("SELECT id FROM symbol WHERE symbol_key = ?", Long.class, key);
    }

    @Test
    void indexesRepositoriesEndToEndAndImpactFindsTheCrossRepositoryCaller() {
        RepoIndexOutcome lib = indexer.index(runId, repo("shop-lib"), false);
        RepoIndexOutcome api = indexer.index(runId, repo("shop-api"), false);

        assertThat(lib.status()).isEqualTo(RepoIndexStatus.SUCCESS);
        assertThat(api.status()).isEqualTo(RepoIndexStatus.SUCCESS);
        assertThat(api.classpathMode()).isEqualTo("FULL");
        assertThat(api.usages()).isPositive();
        assertThat(jdbc.queryForList("SELECT d.group_id || ':' || d.artifact_id || ':' || d.version FROM module_dependency d",
                String.class)).containsExactly("com.shop:shop-lib:1.0.0");

        ImpactResult result = impact.analyze(new ImpactRequest(
                List.of(symbol("com.shop.lib.PriceFormatter#format(int)")), null, 1, null, null));
        assertThat(result.edges()).filteredOn(e -> e.level() == 1).extracting(ImpactEdge::confidence)
                .contains(Confidence.EXACT);
        assertThat(jdbc.queryForList("SELECT status FROM index_run_repo WHERE run_id = ? ORDER BY id", String.class,
                runId)).containsExactly("SUCCESS", "SUCCESS");
    }

    @Test
    void unchangedRepositoriesAreSkippedAndNewCommitsAreReindexed() throws IOException {
        long api = repo("shop-api");
        String first = indexer.index(runId, api, false).commit();

        assertThat(indexer.index(runId, api, false).status()).isEqualTo(RepoIndexStatus.SKIPPED_UNCHANGED);

        String second = GitFixtures.commit(apiBare, Map.of("src/main/java/com/shop/api/Extra.java", """
                package com.shop.api;
                import com.shop.lib.PriceFormatter;
                class Extra { String x() { return new PriceFormatter().format(7); } }
                """), "add Extra");
        RepoIndexOutcome again = indexer.index(runId, api, false);

        assertThat(again.status()).isEqualTo(RepoIndexStatus.SUCCESS);
        assertThat(again.commit()).isEqualTo(second).isNotEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM symbol WHERE symbol_key = 'com.shop.api.Extra#x()'",
                Integer.class)).isEqualTo(1);
        assertThat(indexer.index(runId, api, true).status()).isEqualTo(RepoIndexStatus.SUCCESS);
    }

    @Test
    void aRepositoryWithoutJavaIsSkipped() {
        assertThat(indexer.index(runId, repo("docs"), false).status()).isEqualTo(RepoIndexStatus.SKIPPED_NOT_JAVA);
        assertThat(jdbc.queryForObject("SELECT last_status FROM scm_repository WHERE slug = 'docs'", String.class))
                .isEqualTo("SKIPPED_NOT_JAVA");
    }

    @Test
    void anUnresolvableClasspathStillIndexesButIsPartial() {
        jdbc.update("UPDATE artifact_repository SET enabled = 0");
        change(SettingKeys.INDEX_MAVEN_LOCAL_REPOSITORY, dir.resolve("empty-m2").toString());
        change(SettingKeys.INDEX_MAVEN_TIMEOUT, "PT60S");

        RepoIndexOutcome api = indexer.index(runId, repo("shop-api"), false);

        assertThat(api.status()).isEqualTo(RepoIndexStatus.SUCCESS_PARTIAL);
        assertThat(api.classpathMode()).isEqualTo("NONE");
        assertThat(api.error()).isNotBlank();
        assertThat(api.usages()).isPositive();
    }

    @Test
    void cloneFailureIsRecordedWithoutCredentials() {
        jdbc.update("UPDATE scm_repository SET clone_url = 'https://bob:hunter2@127.0.0.1:1/scm/x.git' "
                + "WHERE slug = 'shop-api'");

        RepoIndexOutcome outcome = indexer.index(runId, repo("shop-api"), false);

        assertThat(outcome.status()).isEqualTo(RepoIndexStatus.CLONE_FAILED);
        assertThat(outcome.error()).doesNotContain("hunter2").doesNotContain(TOKEN);
        assertThat(jdbc.queryForObject("SELECT error FROM index_run_repo WHERE run_id = ? AND status = 'CLONE_FAILED'",
                String.class, runId)).doesNotContain("hunter2").doesNotContain(TOKEN);
        assertThat(jdbc.queryForObject("SELECT last_status FROM scm_repository WHERE slug = 'shop-api'", String.class))
                .isEqualTo("CLONE_FAILED");
    }

    private static Path fixture() throws URISyntaxException {
        return Path.of(RepositoryIndexerTest.class.getResource("/fixtures/shop").toURI());
    }

    private static Map<String, String> sources(Path root, String prefix) throws IOException {
        Map<String, String> sources = new HashMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                sources.put(prefix + root.relativize(file).toString().replace('\\', '/'), Files.readString(file));
            }
        }
        return sources;
    }
}
```

`anUnresolvableClasspathStillIndexesButIsPartial` points Maven at an empty local repository. Maven then has to download plugins from Maven Central. That is fine on a connected machine. Without network access the build fails quickly, which yields the same expected outcome: `NONE` classpath, `SUCCESS_PARTIAL`, and an error text.

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw test -Dtest=RepositoryIndexerTest`
Expected: BUILD FAILURE: `cannot find symbol` for `RepositoryIndexer`, `IndexRunRecorder`, `RepoIndexStatus` and `RepoIndexOutcome`.

- [ ] **Step 3: Write the run model and recorder**

`src/main/java/com/graphify/indexing/RepoIndexStatus.java`:

```java
package com.graphify.indexing;

/** Outcome of indexing one repository in one run (spec §8). */
public enum RepoIndexStatus {
    SUCCESS, SUCCESS_PARTIAL, FAILED, CLONE_FAILED, SKIPPED_UNCHANGED, SKIPPED_NOT_JAVA, INTERRUPTED
}
```

`src/main/java/com/graphify/indexing/RepoIndexOutcome.java`:

```java
package com.graphify.indexing;

/** {@code classpathMode} is FULL, PARTIAL or NONE over the modules with sources; null when nothing was resolved. */
public record RepoIndexOutcome(RepoIndexStatus status, String commit, String classpathMode, String error, int symbols,
        int usages, int warnings, long durationMillis) {
}
```

`src/main/java/com/graphify/indexing/IndexRunRecorder.java`:

```java
package com.graphify.indexing;

import com.graphify.common.util.Utf8;
import java.sql.PreparedStatement;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** Writes index_run and index_run_repo (spec §4.1). Plan 5 builds scheduling and parallelism on top of it. */
@Repository
public class IndexRunRecorder {

    /** Width of index_run_repo.error in V4__repository_acquisition.sql. */
    private static final int ERROR_BYTES = 4000;

    private final JdbcTemplate jdbc;

    public IndexRunRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long start(String trigger, String scope, Long scopeId, String startedBy) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("INSERT INTO index_run (trigger_type, scope, "
                    + "scope_id, status, started_by) VALUES (?, ?, ?, 'RUNNING', ?)", new String[] {"id"});
            statement.setString(1, trigger);
            statement.setString(2, scope);
            statement.setObject(3, scopeId);
            statement.setString(4, startedBy);
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    public void record(long runId, long repositoryId, RepoIndexOutcome outcome) {
        jdbc.update("""
                INSERT INTO index_run_repo (run_id, repo_id, commit_sha, status, classpath_mode, error, symbol_count,
                                            usage_count, warning_count, duration_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, runId, repositoryId, outcome.commit(), outcome.status().name(), outcome.classpathMode(),
                outcome.error() == null ? null : Utf8.truncateToBytes(outcome.error(), ERROR_BYTES), outcome.symbols(),
                outcome.usages(), outcome.warnings(), outcome.durationMillis());
    }

    public void finish(long runId, String status) {
        jdbc.update("UPDATE index_run SET status = ?, finished_at = SYSTIMESTAMP WHERE id = ?", status, runId);
    }
}
```

- [ ] **Step 4: Write the pipeline**

`src/main/java/com/graphify/indexing/RepositoryIndexer.java`:

```java
package com.graphify.indexing;

import com.graphify.indexer.IndexRequest;
import com.graphify.indexer.IndexerOptions;
import com.graphify.indexer.JavaRepositoryIndexer;
import com.graphify.indexer.ModuleSource;
import com.graphify.indexer.model.IndexResult;
import com.graphify.maven.ClasspathResolver;
import com.graphify.maven.ClasspathResult;
import com.graphify.maven.MavenModule;
import com.graphify.maven.MavenProject;
import com.graphify.maven.MavenProjectReader;
import com.graphify.scm.ScmConnection;
import com.graphify.scm.ScmConnections;
import com.graphify.scm.UrlMasking;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import com.graphify.store.ClasspathMode;
import com.graphify.store.ModuleRecord;
import com.graphify.store.RepositoryIndex;
import com.graphify.store.RepositoryIndexWriter;
import com.graphify.store.RepositoryNotFoundException;
import com.graphify.store.WriteSummary;
import com.graphify.workspace.GitWorkspace;
import com.graphify.workspace.RemoteHead;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Indexes one repository: head check, checkout, Maven model, classpath, JDT index, write (spec §3.2 steps 2–8). */
@Service
public class RepositoryIndexer {

    private record RepositoryRow(long id, long connectionId, String projectKey, String slug, String cloneUrl,
            String lastIndexedCommit) {
    }

    private final JdbcTemplate jdbc;
    private final ScmConnections connections;
    private final GitWorkspace workspace;
    private final MavenProjectReader mavenReader;
    private final ClasspathResolver classpathResolver;
    private final RepositoryIndexWriter writer;
    private final IndexRunRecorder runs;
    private final AppSettings settings;
    private final JavaRepositoryIndexer javaIndexer = new JavaRepositoryIndexer();

    public RepositoryIndexer(JdbcTemplate jdbc, ScmConnections connections, GitWorkspace workspace,
            MavenProjectReader mavenReader, ClasspathResolver classpathResolver, RepositoryIndexWriter writer,
            IndexRunRecorder runs, AppSettings settings) {
        this.jdbc = jdbc;
        this.connections = connections;
        this.workspace = workspace;
        this.mavenReader = mavenReader;
        this.classpathResolver = classpathResolver;
        this.writer = writer;
        this.runs = runs;
        this.settings = settings;
    }

    public RepoIndexOutcome index(long runId, long repositoryId, boolean force) {
        long startedAt = System.nanoTime();
        RepositoryRow repo = load(repositoryId);
        ScmConnection connection = connections.find(repo.connectionId())
                .orElseThrow(() -> new IllegalStateException("Repository " + repositoryId + " has no connection"));
        RepoIndexOutcome outcome = run(repo, connection, force, startedAt);
        if (outcome.status() != RepoIndexStatus.SKIPPED_UNCHANGED) {
            jdbc.update("UPDATE scm_repository SET last_status = ? WHERE id = ?", outcome.status().name(), repositoryId);
        }
        runs.record(runId, repositoryId, outcome);
        return outcome;
    }

    private RepoIndexOutcome run(RepositoryRow repo, ScmConnection connection, boolean force, long startedAt) {
        RemoteHead head;
        Path checkout;
        try {
            head = workspace.remoteHead(repo.cloneUrl(), connection.username(), connection.secret());
            jdbc.update("UPDATE scm_repository SET default_branch = ? WHERE id = ?", head.branch(), repo.id());
            if (!force && head.commit().equals(repo.lastIndexedCommit())) {
                return outcome(RepoIndexStatus.SKIPPED_UNCHANGED, head.commit(), null, null, startedAt);
            }
            checkout = workspace.directoryFor(repo.connectionId(), repo.projectKey(), repo.slug());
            workspace.checkout(checkout, repo.cloneUrl(), head.branch(), connection.username(), connection.secret());
        } catch (RuntimeException e) {
            return outcome(RepoIndexStatus.CLONE_FAILED, null, null, message(e), startedAt);
        }
        try {
            MavenProject project = mavenReader.read(checkout, settings.getList(SettingKeys.INDEX_SOURCE_ROOTS));
            List<MavenModule> withSources = project.modules().stream()
                    .filter(m -> m.sourceRoots().stream().anyMatch(RepositoryIndexer::containsJava)).toList();
            if (withSources.isEmpty()) {
                return outcome(RepoIndexStatus.SKIPPED_NOT_JAVA, head.commit(), null, null, startedAt);
            }
            ClasspathResult classpaths = classpathResolver.resolve(checkout, project.modules());
            List<ModuleSource> sources = new ArrayList<>();
            List<ModuleRecord> records = new ArrayList<>();
            for (MavenModule module : project.modules()) {
                List<Path> classpath = classpaths.classpaths().get(module.path());
                sources.add(new ModuleSource(module.path(), module.sourceRoots(), classpath == null ? List.of() : classpath));
                records.add(new ModuleRecord(module.path(), module.groupId(), module.artifactId(), module.version(),
                        classpath == null ? ClasspathMode.NONE : ClasspathMode.FULL, module.dependencies()));
            }
            IndexResult result = javaIndexer.index(new IndexRequest(checkout, sources, new IndexerOptions(
                    settings.getInt(SettingKeys.INDEX_PARSE_BATCH_SIZE),
                    settings.getInt(SettingKeys.USAGE_SNIPPET_MAX_LENGTH))));
            WriteSummary summary = write(new RepositoryIndex(repo.id(), head.commit(), records, result));
            long full = withSources.stream().filter(m -> classpaths.classpaths().containsKey(m.path())).count();
            String mode = full == withSources.size() ? "FULL" : full == 0 ? "NONE" : "PARTIAL";
            RepoIndexStatus status = full == withSources.size() ? RepoIndexStatus.SUCCESS : RepoIndexStatus.SUCCESS_PARTIAL;
            List<String> notes = new ArrayList<>(project.warnings());
            if (classpaths.error() != null) {
                notes.add(classpaths.error());
            }
            return new RepoIndexOutcome(status, head.commit(), mode, notes.isEmpty() ? null : String.join("\n", notes),
                    summary.symbols(), summary.usages(), result.warnings().size() + project.warnings().size(),
                    elapsed(startedAt));
        } catch (RuntimeException e) {
            return outcome(RepoIndexStatus.FAILED, head.commit(), null, message(e), startedAt);
        }
    }

    /** One retry when another repository's write held the same symbol rows (plan 2 follow-up). */
    private WriteSummary write(RepositoryIndex index) {
        try {
            return writer.replace(index);
        } catch (PessimisticLockingFailureException e) {
            return writer.replace(index);
        }
    }

    private RepositoryRow load(long repositoryId) {
        return jdbc.query("""
                SELECT id, connection_id, project_key, slug, clone_url, last_indexed_commit
                  FROM scm_repository WHERE id = ? AND active = 1
                """, (rs, row) -> new RepositoryRow(rs.getLong("id"), rs.getLong("connection_id"),
                        rs.getString("project_key"), rs.getString("slug"), rs.getString("clone_url"),
                        rs.getString("last_indexed_commit")), repositoryId)
                .stream().findFirst().orElseThrow(() -> new RepositoryNotFoundException(repositoryId));
    }

    private static boolean containsJava(Path root) {
        try (Stream<Path> files = Files.walk(root)) {
            return files.anyMatch(f -> f.toString().endsWith(".java") && Files.isRegularFile(f));
        } catch (IOException e) {
            return false;
        }
    }

    private static RepoIndexOutcome outcome(RepoIndexStatus status, String commit, String mode, String error,
            long startedAt) {
        return new RepoIndexOutcome(status, commit, mode, error, 0, 0, 0, elapsed(startedAt));
    }

    private static String message(RuntimeException e) {
        String text = e.getClass().getSimpleName() + ": " + e.getMessage();
        return UrlMasking.mask(text);
    }

    private static long elapsed(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw test -Dtest=RepositoryIndexerTest`
Expected: 5 tests pass. The first run takes a while because Maven and git operations run for real.

If `indexesRepositoriesEndToEndAndImpactFindsTheCrossRepositoryCaller` reports `SUCCESS_PARTIAL` for shop-api, read `api.error()`. It holds Maven's output and tells you whether the file repository or a plugin failed to resolve. Fix the setup and do not relax the assertion.

Run: `./mvnw test`
Expected: all tests pass, BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/graphify/indexing src/test/java/com/graphify/indexing
git commit -m "feat(indexing): index one repository end to end and record its run outcome" -m "<your harness Co-Authored-By trailer>"
```
