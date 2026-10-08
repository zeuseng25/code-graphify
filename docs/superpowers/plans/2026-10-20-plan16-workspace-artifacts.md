# Plan 16: Installing Scanned Repositories' Artifacts and Nested Maven Projects — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Two improvements to how a scan resolves Maven classpaths.
- **Provider repositories:** a scanned repository that produces a parent POM, a BOM or a jar used by another scanned repository is installed into graphify's local Maven repository (`index.maven_local_repository`) first, in dependency order, so the consumer's classpath resolves and its usages link EXACTly.
- **Nested projects:** a repository without a root `pom.xml` has its independent Maven projects found in sub-folders, and each gets its own classpath.

**Architecture:**
- **Reading:** `MavenProjectReader` also reads each module's `packaging`, its parent coordinates, its imported BOMs and its project root. When the root has no `pom.xml`, it walks the checkout up to `index.pom_search_depth` levels and makes every POM that is not a module of an earlier one a project root.
- **Classpath:** `ClasspathResolver` runs its unchanged Maven command once per project root. The process code moves to a shared `MavenInvocation`, which the new `ArtifactInstaller` (`mvn … install`) also uses.
- **Matching:** `ArtifactProviders`, a pure class, matches consumer references (parent, BOM imports, dependencies) to modules by exact `groupId:artifactId:version`. It returns, per provider, the consumed artifacts and project roots, and orders providers in layers with Kahn's algorithm; cycles go last.
- **Run order:** `IndexRunExecutor` runs a scan in three phases:
  1. **prepare:** `ls-remote`, checkout and read every repository in parallel;
  2. **install:** providers layer by layer, including providers found outside the run;
  3. **index:** as today, with the skip rule plus "re-index a consumer whose provider was just installed".
- **Recording:** results go to two new `index_run_repo` columns, which the run detail screen shows.

**Tech Stack:** Spring Boot 4.1.1, JDBC, Flyway (Oracle), JGit, `ProcessBuilder` + Maven 3.9, JUnit 5 + Testcontainers Oracle, React 19 + Mantine 9 + Vitest + MSW. No new dependency.

**Spec:** `docs/superpowers/specs/2026-10-08-workspace-artifacts-design.md` (user-approved, commit 8050ab1). Background:
- `docs/superpowers/specs/2026-10-05-impact-analyzer-design.md` §3.2 (indexing) and §6 (schema).
- `docs/superpowers/specs/2026-10-06-web-ui-design.md` §4.2 (run detail).

**Code facts the plan relies on (read from main at 8050ab1):**
- **`IndexRunExecutor` (`indexing/IndexRunExecutor.java`):**
  - `execute` calls `plan()` (sync, then the ids of active repositories `ORDER BY project_key, slug, id`), then `indexAll(runId, ids, force)`.
  - `indexAll` submits `indexSafely(runId, id, force)` for every id to a fixed pool of `min(index.parallelism, ids)` threads and waits on the futures.
  - `indexSafely`:
    - returns early when cancel was requested;
    - calls `runs.markStarted`, then `indexer.index(runId, id, force)`;
    - on an interruption keeps the marker so startup recovery writes INTERRUPTED; on any other Throwable calls `runs.recordFailure`;
    - then `clearMarker`.
  - The constructor takes 7 arguments; `IndexRunServiceFailureTest` builds a stub with 7 nulls.
- **`RepositoryIndexer.index(runId, id, force)`:**
  - loads the row (a private `RepositoryRow`), checks the connection's `baseUrl` is unchanged (repoint guard), then `run(...)`;
  - then sets `last_status` unless SKIPPED_UNCHANGED, and calls `runs.record(runId, id, outcome)`.
- **`RepositoryIndexer.run(...)`:**
  1. `workspace.remoteHead` (failure → CLONE_FAILED), then saves `default_branch`.
  2. **Skip rule:** `!force && head.commit == last_indexed_commit && last_status != SUCCESS_PARTIAL` → SKIPPED_UNCHANGED, after re-running a stale graph analysis.
  3. `workspace.checkout(directoryFor(...), cloneUrl, branch, auth)` → commit (failure → CLONE_FAILED).
  4. `mavenReader.read(checkout, settings.getList(INDEX_SOURCE_ROOTS))`.
  5. **SKIPPED_NOT_JAVA:** no module has a `.java` file.
  6. `classpathResolver.resolve(checkout, project.modules())`, then `ModuleSource`/`ModuleRecord` per module, `javaIndexer.index`, then `write` (replace + graph analysis).
  7. **Status:** SUCCESS when every module with sources got a classpath, else SUCCESS_PARTIAL. Mode is FULL/PARTIAL/NONE. Notes = reader warnings + classpath error, masked.
- **`IndexRunRecorder.record`** inserts `index_run_repo (run_id, repo_id, commit_sha, status, classpath_mode, error, symbol_count, usage_count, warning_count, duration_ms)`. `error` is cut to 4000 bytes. There is no unique key on `(run_id, repo_id)`.
- **`MavenProjectReader.read(checkout, patterns)`:**
  - with no or unparsable root `pom.xml` → `noPom`: one module `"."`, `hasPom=false`;
  - else `collect` walks `<modules>` recursively with a `visited` set, building `MavenModule(path, groupId, artifactId, version, sourceRoots, dependencies, hasPom)`;
  - `chain` follows in-checkout parents and `properties` merges them; `interpolate` and `unresolvedToNull` are used;
  - `Pom.managed` holds `dependencyManagement` dependencies, including their `scope`;
  - there is no `packaging` in `Pom` yet.
- **`ClasspathResolver.resolve(root, modules)`:**
  - returns empty when no module `hasPom`;
  - deletes each module's `target/graphify-classpath.txt`, writes an owner-only temp `settings.xml` (`SettingsXmlWriter.write(repositories.enabled(), token)`) and a log;
  - `run(root, settingsFile, log)` runs `executable + MAVEN_FLAGS + -s + -Dmaven.repo.local + MAVEN_GOALS` in `root` with `childEnvironment(System.getenv())`, a closed stdin, the `index.maven_timeout` timeout, and on timeout kills descendants then waits `KILL_WAIT`;
  - non-zero exit → `"Maven exited with N:\n" + tail(...)`;
  - it then reads each module's output file.
  - `MavenExecutableValidator` (Plan 15) uses `childEnvironment`, `KILL_WAIT`, `privateTempFile`, `readLenient` and `deleteQuietly` from it.
- **`ModuleWriter`** MERGEs `maven_module (repo_id, path, group_id, artifact_id, version, classpath_mode)`. `StoreLimits` has `GROUP_ID_BYTES` 300, `ARTIFACT_ID_BYTES` 300, `VERSION_BYTES` 100.
- **`GitWorkspace.checkout`** checks out a branch head; there is no "check out commit X".
- **Settings:** types are `INT`, `DURATION`, `STRING`, `LIST`, `CRON`… (`V2__seed_settings.sql` uses `'INT'` with `min_value`/`max_value`). The latest migration is `V12__maven_check_timeout.sql`.
- **Frontend:**
  - `features/runs/RunRepositoriesTable.tsx` renders `IndexRunRepoView` rows on the run page and on the repository pages.
  - Badges live in `components/Badges.tsx`, with labels from `tr.enums`.
  - `terms.test.ts` rejects the translated term "bağımlılık".
- **Tests:**
  - `IndexRunExecutorTest`, `IndexRunServiceTest`, `RepositoryIndexerTest` and `ClasspathResolverTest` set `index.maven_local_repository` to `~/.m2/repository`, where plugins and the fixtures' artifacts are already cached.
  - `ShopScm` publishes `com.shop:shop-lib:1.0.0` into a file repository; `shop-api` depends on it.

## Rulings

1. **Out-of-run providers install their default-branch head.**
   - The spec (§3.2 step 1) says the provider's last indexed commit is checked out. `GitWorkspace` can only check out a branch head; the checkout is shallow (`index.git_depth`), so an older commit cannot be fetched by name.
   - The provider is therefore prepared exactly like a repository in the run: `ls-remote`, then a checkout of its default branch.
   - Its `index_run_repo` row records the commit that was installed.
   - Cost if wrong: for a short time the installed artifact can be newer than that provider's index. The provider's next scan indexes the same commit.
2. **`force` also forces installs.** A forced run skips the UP_TO_DATE check, since the admin asked for everything to be redone.
3. **Every exact GAV match counts, SNAPSHOT or release.**
   - `ShopScm`'s `shop-lib:1.0.0` becomes a provider in the existing executor tests; that is correct.
   - Installing a release locally shadows the remote copy of the same version. That copy is the same code the repository declares.
4. **Packaging and the presence check:**
   - The `.pom` file is always checked.
   - The `.jar` file is checked only when the packaging is `jar`, or is null (rows written before V13).
   - Other packagings (`pom`, `war`, `maven-plugin`, …) are checked by their `.pom` only.
5. **Imports.** Only a module's own `dependencyManagement` entries with `scope=import` count as BOM references. An imported BOM declared by an out-of-checkout parent cannot be seen without that parent; this is the documented limit.
6. **The setting type is `INT`, not `INTEGER`.** The spec wrote INTEGER, but the codebase's type name is `INT`.
7. **UI wording.** `terms.test.ts` forbids "bağımlılık", so the spec's "Döngüsel bağımlılık" label becomes **"Döngüsel dependency"** (software term in English).
8. **Cyclic providers:**
   - A provider in a cycle is installed in the last layer.
   - If it succeeds, `artifact_install = INSTALLED` and `artifact_install_error` carries the note `Cyclic dependency between scanned repositories: <slugs>`.
   - If it fails, `artifact_install = CYCLE_FAILED` with the Maven output.
9. **Skipped folders.** The nested-project walk skips `.git`, `target`, `node_modules`, `build` and every name starting with `.`.
   - These are conventions, not operational values, so they are a documented constant.
   - Symbolic links are not followed.
10. **Old call sites keep working:**
    - `MavenProjectReader.read(checkout, patterns)` keeps its signature; it means "no nested search". The indexer calls the new `read(checkout, patterns, pomSearchDepth)`.
    - `MavenModule`'s 7-argument constructor stays (project root `"."` when `hasPom`, else null; packaging, parent and imports empty).
    - `ModuleRecord` keeps its 5- and 6-argument constructors (packaging null).
    - `IndexRunRecorder.record(runId, repoId, outcome)` stays (no install outcome).
    - `RepositoryIndexer.index(runId, id, force)` stays, as prepare plus complete with no install.
    - `IndexRunExecutor.indexSafely` stays for its test.
11. **The local Maven repository in tests stays `~/.m2/repository`, as today.**
    - Plugins resolve offline from there, and the existing fixtures already cache `com.shop` artifacts there.
    - New fixtures use the test-only group `com.graphify.testfixture.acme` (SNAPSHOT versions). Tests delete only that group's folder, in setUp and tearDown, so each test starts from a clean state.
12. **A single project root keeps today's error text.** With several roots each error line is prefixed `[<root>] `.

## Plan series

| Plan | Scope | Status |
|---|---|---|
| 1–8 | Backend | merged |
| 9–12 | Web foundation, user screens, repository graph screen, admin screens | merged |
| 13–15 | Repo connection types; GitHub own repositories; settings checks | merged |
| **16** | **Installing scanned repositories' artifacts; nested Maven projects** (this plan) | — |

## Global Constraints

- **Environment:**
  - Backend: `export JAVA_HOME=/opt/homebrew/opt/openjdk@25`; `./mvnw -q test -Dfrontend.skip=true` (or a `-Dtest=` subset while iterating). Oracle comes from Testcontainers, and the real `mvn` on the PATH runs Maven fixtures.
  - Frontend: commands run in `frontend/` (Node 24, npm 11). `npm test` runs `check:api`, the typecheck, lint and Vitest; `npm run build` must pass.
  - Both suites are green at the end of every task.
  - No new dependency.
- **API contract:** when the backend API shape changes (Task 6), run
  `./mvnw test -Dfrontend.skip=true -Dtest=OpenApiSnapshotTest -Dopenapi.update=true`,
  then `npm run generate:api` in `frontend/`.
- **"Kodda sabit değer yok" (no hardcoded values):**
  - Timeouts, parallelism, output tail, the local repository, the executable and the search depth come from settings (`index.maven_timeout`, `index.parallelism`, `index.maven_output_tail_lines`, `index.maven_local_repository`, `index.maven_executable`, `index.pom_search_depth`).
  - Allowed: Maven flags and goals, the skipped folder names (Ruling 9), file names such as `pom.xml`, and column widths with their migration named in a comment.
- **Processes:**
  - Maven runs only through `ProcessBuilder` with an argument list (no shell), with the environment reduced to `ClasspathResolver.INHERITED_ENVIRONMENT`.
  - stdin is closed and output goes to an owner-only temp file.
  - The process tree is killed on timeout or interrupt.
- **Secrets:** never log the environment, `settings.xml` or Maven output. Mask output with `UrlMasking.mask` before it reaches a message or a column.
- **Text:**
  - Every user-visible text lives in `frontend/src/i18n/tr.ts`, in Turkish sentences. Software terms stay English (artifact, install, dependency, class, module…).
  - `frontend/src/i18n/terms.test.ts` must keep passing.
  - Backend messages are English. Setting descriptions in migrations are Turkish.
- **Tests:**
  - Tests use `@TempDir` for workspaces, bare repositories and file repositories, never `/tmp/graphify` or `/data`.
  - Integration tests keep `~/.m2/repository` as the local repository (Ruling 11) and only delete `~/.m2/repository/com/graphify/testfixture/acme`.
- **Running test environment:** the user is using these; never stop, restart or redeploy them (the controller redeploys after the merge, with the user's consent):
  - the test WildFly on 9080 (`/tmp/wildfly-gate/wildfly-41.0.0.Final`);
  - Oracle `graphify-wildfly-db` on 1522;
  - LDAP `graphify-ldap` on 1389.

  Never touch the user's WildFly on 8080 or container `fw-batch-oracle`.
- **Staging:**
  - The working tree may hold the user's own files (`notes/` is git-ignored).
  - Add new files with `git add <path>`; commit only your paths, with `git commit <path> … -m …`; check `git show --stat HEAD`.
  - Never `git add -A` / `git add .` / `git stash` / `git reset`.
- **Commits** end with:
  ```
  Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01XpYgYKeBYFNw4tb6EwJ44V
  ```

## Review Focus

1. **A consumer whose parent POM lives in another scanned repository gets a FULL classpath** in the same run. This is the `zeus-sample-*` case. Test: Task 5 `WorkspaceArtifactsTest` "a parent and a library from other repositories are installed first".
2. **A cross-repository usage through an installed SNAPSHOT jar is EXACT.** Test: the same test asserts an EXACT impact edge from the app to `com.graphify.testfixture.acme.lib.Greeter#greet()`.
3. **An unchanged second run does not rebuild:**
   - providers are UP_TO_DATE;
   - the consumer is SKIPPED_UNCHANGED;
   - the installed jar's modification time is unchanged;
   - an artifact deleted from the local repository is reinstalled and its consumer re-indexed.

   Test: Task 5 "an unchanged second run is up to date; a deleted artifact is installed again".
4. **A provider that fails to install never stops the run:**
   - the provider is still indexed;
   - its row says FAILED with the Maven output;
   - the consumer is SUCCESS_PARTIAL.

   Test: Task 5 "a provider that does not compile fails its install but is still indexed".
5. **A repository without a root `pom.xml`:**
   - independent sub-projects (one in a folder with a space) each get a classpath;
   - a module of a sub-project is not a second root;
   - `node_modules`, `target` and hidden folders and anything past the depth limit are ignored.

   Tests: Task 2 `MavenProjectReaderTest` and `ClasspathResolverTest`; Task 5 "a repository without a root pom indexes its sub-projects".

---

## File Structure

**Backend — create**
- `src/main/resources/db/migration/V13__workspace_artifacts.sql`: new columns, CHECK and the `index.pom_search_depth` seed.
- `src/main/java/com/graphify/maven/Gav.java`: a `groupId:artifactId:version` coordinate.
- `src/main/java/com/graphify/maven/MavenInvocation.java`: runs Maven with the generated `settings.xml` (process code moved from `ClasspathResolver`).
- `src/main/java/com/graphify/maven/ArtifactInstaller.java`: `install` per project root, plus `present`.
- `src/main/java/com/graphify/indexing/ArtifactProviders.java`: pure matching and layering.
- `src/main/java/com/graphify/indexing/ArtifactInstallStatus.java`, `ArtifactInstallOutcome.java`.
- `src/main/java/com/graphify/indexing/ModuleCoordinates.java`: which active repositories declare a GAV.
- `src/main/java/com/graphify/indexing/PreparedRepository.java`, `Preparation.java`.

**Backend — modify**
- `maven/MavenModule.java`, `maven/MavenProject.java`, `maven/MavenProjectReader.java`, `maven/ClasspathResolver.java`.
- `store/ModuleRecord.java`, `store/ModuleWriter.java`, `store/StoreLimits.java`.
- `settings/SettingKeys.java`.
- `indexing/RepositoryIndexer.java`, `indexing/IndexRunExecutor.java`, `indexing/IndexRunRecorder.java`, `indexing/IndexRunRepoView.java`, `indexing/IndexRunQueries.java`.

**Backend — tests**
- Create: `maven/GavTest.java`, `maven/ArtifactInstallerTest.java`, `indexing/ArtifactProvidersTest.java`, `indexing/WorkspaceArtifactsTest.java`, `testsupport/AcmeScm.java`.
- Modify: `store/SchemaMigrationTest.java`, `maven/MavenProjectReaderTest.java`, `maven/ClasspathResolverTest.java`, `indexing/RepositoryIndexerTest.java`, `indexing/IndexRunServiceFailureTest.java`, `indexing/IndexRunApiTest.java`.

**Frontend**
- Modify: `features/runs/RunRepositoriesTable.tsx`, `components/Badges.tsx`, `i18n/tr.ts`, `features/runs/runs.test.tsx`, `openapi.json`, `src/api/schema.d.ts` (generated).

**Docs:** `README.md`.

---

### Task 1: Schema, coordinates and packaging

**Files:**
- Create: `src/main/resources/db/migration/V13__workspace_artifacts.sql`, `src/main/java/com/graphify/maven/Gav.java`, `src/test/java/com/graphify/maven/GavTest.java`
- Modify: `maven/MavenModule.java`, `maven/MavenProjectReader.java`, `store/ModuleRecord.java`, `store/ModuleWriter.java`, `store/StoreLimits.java`, `settings/SettingKeys.java`, `indexing/RepositoryIndexer.java` (pass packaging)
- Test: `store/SchemaMigrationTest.java`, `maven/MavenProjectReaderTest.java`, `indexing/RepositoryIndexerTest.java`

**Interfaces:**
- **Produces:**
  - `record Gav(String groupId, String artifactId, String version)` with `static Gav of(String, String, String)` (null when any part is null, blank or still contains `${`) and `toString()` `g:a:v`.
  - `MavenModule` gains `String packaging`, `Gav parent`, `List<Gav> imports`, `String projectRoot`. Canonical order: `(path, groupId, artifactId, version, sourceRoots, dependencies, hasPom, packaging, parent, imports, projectRoot)`. The 7-argument constructor stays.
  - `ModuleRecord` gains `String packaging`, last in canonical order. The 5- and 6-argument constructors stay.
  - `SettingKeys.INDEX_POM_SEARCH_DEPTH = "index.pom_search_depth"`.
  - V13 columns: `scm_repository.last_installed_commit`, `maven_module.packaging`, `index_run_repo.artifact_install` and `artifact_install_error`.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/graphify/maven/GavTest.java`:
```java
package com.graphify.maven;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GavTest {

    @Test
    void aCoordinateNeedsAllThreePartsResolved() {
        assertThat(Gav.of("com.acme", "lib", "1.0-SNAPSHOT")).hasToString("com.acme:lib:1.0-SNAPSHOT");
        assertThat(Gav.of(null, "lib", "1")).isNull();
        assertThat(Gav.of("com.acme", " ", "1")).isNull();
        assertThat(Gav.of("com.acme", "lib", "${revision}")).isNull();
    }
}
```

Append to `SchemaMigrationTest` (same style as `seedsTheMavenCheckTimeout`):
```java
    @Test
    void addsTheWorkspaceArtifactColumnsAndTheSearchDepth() {
        assertThat(jdbc.queryForObject("SELECT value_type || ' ' || setting_value || ' ' || min_value || ' ' || max_value "
                + "FROM app_setting WHERE setting_key = 'index.pom_search_depth'", String.class)).isEqualTo("INT 3 1 10");
        assertThat(jdbc.queryForList("SELECT table_name || '.' || column_name FROM user_tab_columns WHERE "
                + "(table_name = 'SCM_REPOSITORY' AND column_name = 'LAST_INSTALLED_COMMIT') "
                + "OR (table_name = 'MAVEN_MODULE' AND column_name = 'PACKAGING') "
                + "OR (table_name = 'INDEX_RUN_REPO' AND column_name IN ('ARTIFACT_INSTALL', 'ARTIFACT_INSTALL_ERROR'))",
                String.class)).hasSize(4);
        assertThat(jdbc.queryForObject("SELECT search_condition_vc FROM user_constraints "
                + "WHERE constraint_name = 'CK_INDEX_RUN_REPO_INSTALL'", String.class))
                .contains("INSTALLED", "UP_TO_DATE", "FAILED", "CYCLE_FAILED");
    }
```

Append to `MavenProjectReaderTest` (it has `write(relative, content)` and `reader`):
```java
    @Test
    void readsPackagingTheOutsideParentAndImportedBoms() throws Exception {
        write("pom.xml", """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.zeus</groupId><artifactId>zeus-bff-parent</artifactId><version>${zeus.version}</version>
                    <relativePath/></parent>
                  <artifactId>app</artifactId><packaging>war</packaging>
                  <properties><zeus.version>2.0.0-SNAPSHOT</zeus.version></properties>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>com.zeus</groupId><artifactId>zeus-dependencies</artifactId>
                      <version>${zeus.version}</version><type>pom</type><scope>import</scope></dependency>
                    <dependency><groupId>org.x</groupId><artifactId>managed</artifactId><version>1</version></dependency>
                  </dependencies></dependencyManagement>
                </project>
                """);
        write("lib/pom.xml", MavenFixtures.pom("com.zeus", "lib", "1", "", ""));

        MavenModule app = reader.read(dir, List.of("src/main/java")).modules().getFirst();

        assertThat(app.packaging()).isEqualTo("war");
        assertThat(app.parent()).isEqualTo(new Gav("com.zeus", "zeus-bff-parent", "2.0.0-SNAPSHOT"));
        assertThat(app.imports()).containsExactly(new Gav("com.zeus", "zeus-dependencies", "2.0.0-SNAPSHOT"));
        assertThat(app.projectRoot()).isEqualTo(".");
    }

    @Test
    void packagingDefaultsToJar() throws Exception {
        write("pom.xml", MavenFixtures.pom("com.acme", "lib", "1", "", ""));

        assertThat(reader.read(dir, List.of("src/main/java")).modules().getFirst().packaging()).isEqualTo("jar");
    }
```
(Import `com.graphify.testsupport.MavenFixtures` if the file does not already. If the test class's temp directory field is not named `dir`, use its name.)

In `RepositoryIndexerTest.indexesRepositoriesEndToEndAndImpactFindsTheCrossRepositoryCaller`, add after the `module_dependency` assertion:
```java
        assertThat(jdbc.queryForList("SELECT DISTINCT packaging FROM maven_module", String.class)).containsExactly("jar");
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q test -Dfrontend.skip=true -Dtest='GavTest,SchemaMigrationTest,MavenProjectReaderTest,RepositoryIndexerTest'`
Expected: compilation errors (`Gav`, `packaging()`, `parent()`, `imports()`, `projectRoot()`), then the missing-column/seed failures.

- [ ] **Step 3: Implement**

`src/main/resources/db/migration/V13__workspace_artifacts.sql`:
```sql
-- V13: artifacts of scanned repositories installed for each other, and nested Maven projects (Plan 16)
ALTER TABLE scm_repository ADD (last_installed_commit VARCHAR2(64 BYTE));
ALTER TABLE maven_module ADD (packaging VARCHAR2(40 BYTE));
ALTER TABLE index_run_repo ADD (artifact_install VARCHAR2(20 BYTE), artifact_install_error CLOB);
ALTER TABLE index_run_repo ADD CONSTRAINT ck_index_run_repo_install CHECK (artifact_install IS NULL
    OR artifact_install IN ('INSTALLED', 'UP_TO_DATE', 'FAILED', 'CYCLE_FAILED'));
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('index.pom_search_depth', '3', 'INT', 'Kökte pom.xml yoksa alt klasörlerde aranacak en fazla derinlik', 1, 10);
```

`src/main/java/com/graphify/maven/Gav.java`:
```java
package com.graphify.maven;

/** A Maven coordinate; {@link #of} refuses partial or unresolved ones, which never match anything. */
public record Gav(String groupId, String artifactId, String version) {

    public static Gav of(String groupId, String artifactId, String version) {
        if (resolved(groupId) && resolved(artifactId) && resolved(version)) {
            return new Gav(groupId.strip(), artifactId.strip(), version.strip());
        }
        return null;
    }

    private static boolean resolved(String part) {
        return part != null && !part.isBlank() && !part.contains("${");
    }

    @Override
    public String toString() {
        return groupId + ":" + artifactId + ":" + version;
    }
}
```

`MavenModule.java`:
```java
package com.graphify.maven;

import com.graphify.store.DependencyRecord;
import java.nio.file.Path;
import java.util.List;

/**
 * One module of a checkout: its coordinates, existing source roots and declared dependencies, plus what other
 * repositories may have to provide (parent, imported BOMs) and the project root whose reactor builds it.
 * {@code projectRoot} is "." for the checkout root, a checkout-relative path for a nested project, or null when
 * the checkout has no pom.
 */
public record MavenModule(String path, String groupId, String artifactId, String version, List<Path> sourceRoots,
        List<DependencyRecord> dependencies, boolean hasPom, String packaging, Gav parent, List<Gav> imports,
        String projectRoot) {

    public MavenModule {
        sourceRoots = List.copyOf(sourceRoots);
        dependencies = List.copyOf(dependencies);
        imports = imports == null ? List.of() : List.copyOf(imports);
    }

    public MavenModule(String path, String groupId, String artifactId, String version, List<Path> sourceRoots,
            List<DependencyRecord> dependencies, boolean hasPom) {
        this(path, groupId, artifactId, version, sourceRoots, dependencies, hasPom, null, null, List.of(),
                hasPom ? "." : null);
    }

    /** This module's own coordinate, or null when it is not fully resolved. */
    public Gav gav() {
        return Gav.of(groupId, artifactId, version);
    }
}
```

`MavenProjectReader.java`:
- **`Pom` record:** add `String packaging` after `testSourceDirectory`. `parse` passes `text(project, "packaging")`.
- **`Dependency` record:** gains `String type`. `dependencyList` reads `text(dependency, "type")`. All `new Dependency(...)` calls are inside `dependencyList`, so update that one call.
- **`collect` signature:** gains `String projectRoot` after `moduleDir`. The call from `read` passes `"."`, and the recursive call passes the same `projectRoot`.
- **`collect` building the module:** replace the `out.add(new MavenModule(...))` call with:
```java
        String packaging = pom.packaging() == null ? "jar" : interpolate(pom.packaging(), properties);
        Gav parent = pom.parentArtifactId() == null ? null : Gav.of(interpolate(pom.parentGroupId(), properties),
                interpolate(pom.parentArtifactId(), properties), interpolate(pom.parentVersion(), properties));
        out.add(new MavenModule(relative.isEmpty() ? "." : relative, groupId,
                interpolate(pom.artifactId(), properties), unresolvedToNull(version),
                sourceRoots(root, moduleDir, patterns, pom, properties), dependencies(pom, chain, properties), true,
                packaging, parent, imports(pom, properties), projectRoot));
```
- **New helper:**
```java
    /** The BOMs this pom imports in its own dependencyManagement (scope import). */
    private static List<Gav> imports(Pom pom, Map<String, String> properties) {
        List<Gav> imports = new ArrayList<>();
        for (Dependency managed : pom.managed()) {
            if ("import".equals(interpolate(managed.scope(), properties))) {
                Gav gav = Gav.of(interpolate(managed.groupId(), properties), interpolate(managed.artifactId(), properties),
                        interpolate(managed.version(), properties));
                if (gav != null) {
                    imports.add(gav);
                }
            }
        }
        return imports;
    }
```
`noPom` keeps using the 7-argument constructor.

`StoreLimits.java`: add `static final int PACKAGING_BYTES = 40; // maven_module.packaging (V13)`.

`ModuleRecord.java`:
```java
public record ModuleRecord(String path, String groupId, String artifactId, String version, ClasspathMode classpathMode,
        List<DependencyRecord> dependencies, String packaging) {

    public ModuleRecord {
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
    }

    public ModuleRecord(String path, String groupId, String artifactId, String version, ClasspathMode classpathMode,
            List<DependencyRecord> dependencies) {
        this(path, groupId, artifactId, version, classpathMode, dependencies, null);
    }

    public ModuleRecord(String path, String groupId, String artifactId, String version, ClasspathMode classpathMode) {
        this(path, groupId, artifactId, version, classpathMode, List.of(), null);
    }
}
```
Keep the class Javadoc and add: "`packaging` is null when unknown (a repository without a pom)."

`ModuleWriter.java`: the MERGE gains `packaging`:
```java
    private static final String MERGE = """
            MERGE INTO maven_module m
            USING (SELECT ? AS repo_id, ? AS path, ? AS group_id, ? AS artifact_id, ? AS version,
                          ? AS classpath_mode, ? AS packaging FROM dual) n
            ON (m.repo_id = n.repo_id AND m.path = n.path)
            WHEN MATCHED THEN UPDATE SET m.group_id = n.group_id, m.artifact_id = n.artifact_id,
                    m.version = n.version, m.classpath_mode = n.classpath_mode, m.packaging = n.packaging
            WHEN NOT MATCHED THEN INSERT (repo_id, path, group_id, artifact_id, version, classpath_mode, packaging)
                VALUES (n.repo_id, n.path, n.group_id, n.artifact_id, n.version, n.classpath_mode, n.packaging)
            """;
```
In the batch arguments, add `cut(module.packaging(), StoreLimits.PACKAGING_BYTES)` after `module.classpathMode().name()`.

`SettingKeys.java`: add `public static final String INDEX_POM_SEARCH_DEPTH = "index.pom_search_depth";` next to `INDEX_SOURCE_ROOTS`.

`RepositoryIndexer.java`: the `ModuleRecord` built in `run` becomes
```java
                records.add(new ModuleRecord(module.path(), module.groupId(), module.artifactId(), module.version(),
                        classpath == null ? ClasspathMode.NONE : ClasspathMode.FULL, module.dependencies(),
                        module.packaging()));
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -q test -Dfrontend.skip=true -Dtest='GavTest,SchemaMigrationTest,MavenProjectReaderTest,RepositoryIndexerTest,RepositoryIndexWriterTest'`
Expected: PASS. Then run the full backend suite and `cd frontend && npm test` (no API change yet).

- [ ] **Step 5: Commit**
```bash
git add src/main/resources/db/migration/V13__workspace_artifacts.sql src/main/java/com/graphify/maven/Gav.java src/test/java/com/graphify/maven/GavTest.java
git commit src/main/resources/db/migration/V13__workspace_artifacts.sql src/main/java/com/graphify/maven/Gav.java \
  src/main/java/com/graphify/maven/MavenModule.java src/main/java/com/graphify/maven/MavenProjectReader.java \
  src/main/java/com/graphify/store/ModuleRecord.java src/main/java/com/graphify/store/ModuleWriter.java \
  src/main/java/com/graphify/store/StoreLimits.java src/main/java/com/graphify/settings/SettingKeys.java \
  src/main/java/com/graphify/indexing/RepositoryIndexer.java src/test/java/com/graphify/maven/GavTest.java \
  src/test/java/com/graphify/store/SchemaMigrationTest.java src/test/java/com/graphify/maven/MavenProjectReaderTest.java \
  src/test/java/com/graphify/indexing/RepositoryIndexerTest.java \
  -m "feat(maven): read packaging, parent and imported BOMs; V13 workspace artifact columns" \
  -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01XpYgYKeBYFNw4tb6EwJ44V"
```

---

### Task 2: Nested Maven projects and per-root classpaths

**Files:**
- Modify: `maven/MavenProject.java`, `maven/MavenProjectReader.java`, `maven/ClasspathResolver.java`, `indexing/RepositoryIndexer.java`
- Test: `maven/MavenProjectReaderTest.java`, `maven/ClasspathResolverTest.java`

**Interfaces:**
- **Consumes:** Task 1's `MavenModule.projectRoot`.
- **Produces:**
  - `MavenProject(List<MavenModule> modules, List<String> warnings, List<String> roots)`. `roots` holds checkout-relative project roots in discovery order (`"."` for a root pom) and is empty without a pom. The 2-argument constructor stays and derives `roots`: `["."]` when any module `hasPom`, else empty.
  - `MavenProjectReader.read(Path checkout, List<String> sourceRootPatterns, int pomSearchDepth)`. The 2-argument `read` calls it with depth 0, which means no nested search.
  - `ClasspathResolver.resolve(Path checkout, List<MavenModule> modules)` keeps its signature. It runs Maven once per distinct `projectRoot` of the modules with `hasPom`.

- [ ] **Step 1: Write the failing tests**

Append to `MavenProjectReaderTest` (it has `write` and `mkdirs` helpers):
```java
    @Test
    void withoutARootPomEveryIndependentProjectInSubFoldersIsARoot() throws Exception {
        write("Jwt Demo/pom.xml", MavenFixtures.pom("com.demo", "jwt", "1", "", ""));
        write("Jwt Demo/src/main/java/demo/A.java", "package demo; class A {}");
        write("services/payment/pom.xml", MavenFixtures.pom("com.demo", "payment", "1", "", ""));
        write("services/gateway/pom.xml", MavenFixtures.pom("com.demo", "gateway", "1",
                "<packaging>pom</packaging><modules><module>core</module></modules>", ""));
        write("services/gateway/core/pom.xml", MavenFixtures.pom("com.demo", "gateway-core", "1", "", ""));
        write("node_modules/x/pom.xml", MavenFixtures.pom("x", "x", "1", "", ""));
        write("services/payment/target/pom.xml", MavenFixtures.pom("x", "copied", "1", "", ""));
        write(".hidden/pom.xml", MavenFixtures.pom("x", "hidden", "1", "", ""));
        write("a/b/c/d/pom.xml", MavenFixtures.pom("x", "too-deep", "1", "", ""));

        MavenProject project = reader.read(dir, List.of("src/main/java"), 3);

        assertThat(project.roots()).containsExactly("Jwt Demo", "services/gateway", "services/payment");
        assertThat(project.modules()).extracting(MavenModule::path, MavenModule::projectRoot).containsExactly(
                org.assertj.core.groups.Tuple.tuple("Jwt Demo", "Jwt Demo"),
                org.assertj.core.groups.Tuple.tuple("services/gateway", "services/gateway"),
                org.assertj.core.groups.Tuple.tuple("services/gateway/core", "services/gateway"),
                org.assertj.core.groups.Tuple.tuple("services/payment", "services/payment"));
        assertThat(project.modules().getFirst().sourceRoots()).singleElement()
                .satisfies(root -> assertThat(root.toString()).endsWith("Jwt Demo/src/main/java"));
    }

    @Test
    void theDepthLimitIsInclusiveAndTheTwoArgumentReadDoesNotSearch() throws Exception {
        write("a/b/c/pom.xml", MavenFixtures.pom("x", "three", "1", "", ""));

        assertThat(reader.read(dir, List.of("src/main/java"), 3).roots()).containsExactly("a/b/c");
        assertThat(reader.read(dir, List.of("src/main/java"), 2).roots()).isEmpty();
        assertThat(reader.read(dir, List.of("src/main/java")).modules()).singleElement()
                .satisfies(module -> assertThat(module.hasPom()).isFalse());
    }

    @Test
    void symbolicLinksAreNotFollowed() throws Exception {
        write("real/pom.xml", MavenFixtures.pom("x", "real", "1", "", ""));
        java.nio.file.Files.createSymbolicLink(dir.resolve("link"), dir.resolve("real"));

        assertThat(reader.read(dir, List.of("src/main/java"), 3).roots()).containsExactly("real");
    }

    @Test
    void aRootPomStillMeansOneRoot() throws Exception {
        write("pom.xml", MavenFixtures.pom("x", "root", "1", "", ""));
        write("other/pom.xml", MavenFixtures.pom("x", "other", "1", "", ""));

        assertThat(reader.read(dir, List.of("src/main/java"), 3).roots()).containsExactly(".");
    }
```

Append to `ClasspathResolverTest` (it already publishes `fixture-lib` and has `write` rooted at `project`):
```java
    @Test
    void eachNestedProjectResolvesInItsOwnFolderAndAFailingOneFailsAlone() throws Exception {
        Path nested = dir.resolve("nested");
        java.nio.file.Files.createDirectories(nested);
        writeIn(nested, "Lib Project/pom.xml", MavenFixtures.pom(GROUP, "nested-lib", "1", "",
                MavenFixtures.dependency(GROUP, "fixture-lib", "1.0.0")));
        writeIn(nested, "Lib Project/src/main/java/n/L.java", "package n; public class L {}");
        writeIn(nested, "broken/pom.xml", MavenFixtures.pom(GROUP, "nested-broken", "1", "",
                MavenFixtures.dependency(GROUP, "missing", "9")));
        writeIn(nested, "broken/src/main/java/n/B.java", "package n; public class B {}");

        ClasspathResult result = resolver.resolve(nested, reader.read(nested, List.of("src/main/java"), 3).modules());

        assertThat(result.classpaths()).containsOnlyKeys("Lib Project");
        assertThat(result.classpaths().get("Lib Project"))
                .anySatisfy(p -> assertThat(p.toString()).endsWith("fixture-lib-1.0.0.jar"));
        assertThat(result.error()).startsWith("[broken] ").contains("missing");
    }

    private static void writeIn(Path base, String relative, String content) throws Exception {
        Path file = base.resolve(relative);
        java.nio.file.Files.createDirectories(file.getParent());
        java.nio.file.Files.writeString(file, content);
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q test -Dfrontend.skip=true -Dtest='MavenProjectReaderTest,ClasspathResolverTest'`
Expected: compilation errors (`roots()`, the 3-argument `read`).

- [ ] **Step 3: Implement**

`MavenProject.java`:
```java
package com.graphify.maven;

import java.util.List;

/** A checkout's modules; {@code roots} are the checkout-relative folders whose reactors build them ("." = the root). */
public record MavenProject(List<MavenModule> modules, List<String> warnings, List<String> roots) {

    public MavenProject {
        modules = List.copyOf(modules);
        warnings = List.copyOf(warnings);
        roots = List.copyOf(roots);
    }

    public MavenProject(List<MavenModule> modules, List<String> warnings) {
        this(modules, warnings, modules.stream().anyMatch(MavenModule::hasPom) ? List.of(".") : List.of());
    }
}
```

`MavenProjectReader.java`:
```java
    /** Folders the nested-project walk never enters: VCS data, build output, dependencies (Plan 16 Ruling 9). */
    static final Set<String> SKIPPED_FOLDERS = Set.of(".git", "target", "node_modules", "build");

    public MavenProject read(Path checkout, List<String> sourceRootPatterns) {
        return read(checkout, sourceRootPatterns, 0);
    }

    /**
     * Reads the root pom's module tree, or, without a root pom, every independent project found up to
     * {@code pomSearchDepth} folders deep (0 = do not search). A pom inside an earlier project's module tree is not
     * a project of its own.
     */
    public MavenProject read(Path checkout, List<String> sourceRootPatterns, int pomSearchDepth) {
        Path root = checkout.toAbsolutePath().normalize();
        List<String> warnings = new ArrayList<>();
        Map<Path, Optional<Pom>> cache = new HashMap<>();
        List<MavenModule> modules = new ArrayList<>();
        Set<Path> visited = new LinkedHashSet<>();
        List<String> roots = new ArrayList<>();
        if (Files.isRegularFile(root.resolve(POM))) {
            if (load(root.resolve(POM), cache, warnings).isEmpty()) {
                return noPom(root, warnings);
            }
            collect(root, root, ".", sourceRootPatterns, cache, warnings, modules, visited);
            return new MavenProject(modules, warnings, List.of("."));
        }
        for (Path projectDir : pomFolders(root, pomSearchDepth, warnings)) {
            if (visited.contains(projectDir) || load(projectDir.resolve(POM), cache, warnings).isEmpty()) {
                continue;
            }
            String relative = relativeName(root, projectDir);
            roots.add(relative);
            collect(root, projectDir, relative, sourceRootPatterns, cache, warnings, modules, visited);
        }
        return roots.isEmpty() ? noPom(root, warnings) : new MavenProject(modules, warnings, roots);
    }

    /** Folders holding a pom.xml, shallowest first then by path; links and SKIPPED_FOLDERS are never entered. */
    private static List<Path> pomFolders(Path root, int maxDepth, List<String> warnings) {
        List<Path> found = new ArrayList<>();
        List<Path> level = List.of(root);
        for (int depth = 1; depth <= maxDepth && !level.isEmpty(); depth++) {
            List<Path> next = new ArrayList<>();
            for (Path folder : level) {
                List<Path> children;
                try (Stream<Path> listing = Files.list(folder)) {
                    children = listing.sorted().toList();
                } catch (IOException e) {
                    warnings.add("Could not list " + relativeName(root, folder) + ": " + e.getClass().getSimpleName());
                    continue;
                }
                for (Path child : children) {
                    String name = child.getFileName().toString();
                    if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) || name.startsWith(".")
                            || SKIPPED_FOLDERS.contains(name)) {
                        continue;
                    }
                    if (Files.isRegularFile(child.resolve(POM), LinkOption.NOFOLLOW_LINKS)) {
                        found.add(child);
                    }
                    next.add(child);
                }
            }
            level = next;
        }
        return found;
    }
```
- `noPom` builds `new MavenProject(List.of(new MavenModule(".", null, null, null, List.of(root), List.of(), false)), warnings, List.of())`.
- Add the imports `java.io.IOException`, `java.nio.file.LinkOption` and `java.util.stream.Stream`.
- `relativeName(root, root)` returns `""` and is never called with the root here.

`ClasspathResolver.resolve`: run once per project root. Replace the body with:
```java
    public ClasspathResult resolve(Path checkout, List<MavenModule> modules) {
        Map<String, List<MavenModule>> byRoot = new LinkedHashMap<>();
        modules.stream().filter(MavenModule::hasPom)
                .forEach(m -> byRoot.computeIfAbsent(m.projectRoot() == null ? "." : m.projectRoot(), r -> new ArrayList<>()).add(m));
        if (byRoot.isEmpty()) {
            return new ClasspathResult(Map.of(), null);
        }
        Map<String, List<Path>> classpaths = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        for (Map.Entry<String, List<MavenModule>> entry : byRoot.entrySet()) {
            ClasspathResult one = resolveRoot(checkout, entry.getKey(), entry.getValue());
            classpaths.putAll(one.classpaths());
            if (one.error() != null) {
                errors.add(byRoot.size() == 1 ? one.error() : "[" + entry.getKey() + "] " + one.error());
            }
        }
        return new ClasspathResult(classpaths, errors.isEmpty() ? null : String.join("\n", errors));
    }

    /** One reactor run in {@code projectRoot}; module paths stay checkout-relative. */
    private ClasspathResult resolveRoot(Path checkout, String projectRoot, List<MavenModule> modules) {
        Path directory = projectRoot.equals(".") ? checkout : checkout.resolve(projectRoot);
        // ... the former body from "Path settingsFile = null;" to the end, with run(directory, …) and
        //     moduleDir(checkout, module) for the output files ...
    }
```
`moduleDir(checkout, module)` stays checkout-relative because module paths are checkout-relative. Task 4 moves the process code into `MavenInvocation`; this task keeps `run` as it is.

`RepositoryIndexer.run`: the read becomes
```java
            MavenProject project = mavenReader.read(checkout, settings.getList(SettingKeys.INDEX_SOURCE_ROOTS),
                    settings.getInt(SettingKeys.INDEX_POM_SEARCH_DEPTH));
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -q test -Dfrontend.skip=true -Dtest='MavenProjectReaderTest,ClasspathResolverTest,RepositoryIndexerTest'`
Expected: PASS. Then run the full backend suite.

- [ ] **Step 5: Commit**
```bash
git commit src/main/java/com/graphify/maven/MavenProject.java src/main/java/com/graphify/maven/MavenProjectReader.java \
  src/main/java/com/graphify/maven/ClasspathResolver.java src/main/java/com/graphify/indexing/RepositoryIndexer.java \
  src/test/java/com/graphify/maven/MavenProjectReaderTest.java src/test/java/com/graphify/maven/ClasspathResolverTest.java \
  -m "feat(maven): find independent projects in sub-folders and resolve each one's classpath" \
  -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01XpYgYKeBYFNw4tb6EwJ44V"
```

---

### Task 3: Provider matching and layering (pure)

**Files:**
- Create: `src/main/java/com/graphify/indexing/ArtifactProviders.java`, `src/test/java/com/graphify/indexing/ArtifactProvidersTest.java`

**Interfaces:**
- **Consumes:** `MavenModule.gav()`, `parent()`, `imports()`, `dependencies()`, `projectRoot()`, `packaging()`; `Gav`.
- **Produces:**
```java
public final class ArtifactProviders {
    public record Provision(long repositoryId, Map<Gav, String> consumed, Set<String> projectRoots) {}
    public record Plan(Map<Long, Provision> provisions, Map<Long, Set<Long>> providersOf,
                       List<List<Long>> layers, Set<Long> cyclic) {}
    public static Set<Gav> references(MavenModule module);
    public static Set<Gav> unmatched(Map<Long, List<MavenModule>> modulesByRepository);
    public static Plan plan(Map<Long, List<MavenModule>> modulesByRepository);
}
```
  - `consumed` maps each consumed GAV to its packaging.
  - The iteration order of `modulesByRepository` is the tie-break order: the executor passes run order, `project_key, slug, id`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/graphify/indexing/ArtifactProvidersTest.java`:
```java
package com.graphify.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.maven.Gav;
import com.graphify.maven.MavenModule;
import com.graphify.store.DependencyRecord;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ArtifactProvidersTest {

    private static MavenModule module(String path, String root, String artifactId, String packaging, Gav parent,
            List<Gav> imports, DependencyRecord... dependencies) {
        return new MavenModule(path, "com.acme", artifactId, "1.0-SNAPSHOT", List.of(), List.of(dependencies), true,
                packaging, parent, imports, root);
    }

    private static Gav acme(String artifactId) {
        return new Gav("com.acme", artifactId, "1.0-SNAPSHOT");
    }

    private static DependencyRecord on(String artifactId) {
        return new DependencyRecord("com.acme", artifactId, "1.0-SNAPSHOT", "compile");
    }

    @Test
    void aParentALibraryAndABomMakeTheirRepositoriesProvidersInDependencyOrder() {
        Map<Long, List<MavenModule>> read = new LinkedHashMap<>();
        read.put(3L, List.of(module(".", ".", "app", "jar", acme("parent"), List.of(acme("bom")), on("lib"),
                new DependencyRecord("org.slf4j", "slf4j-api", "2.0.9", "compile"))));
        read.put(2L, List.of(module(".", ".", "lib", "jar", acme("parent"), List.of())));
        read.put(1L, List.of(module(".", ".", "parent", "pom", null, List.of()),
                module("bom", ".", "bom", "pom", null, List.of())));

        ArtifactProviders.Plan plan = ArtifactProviders.plan(read);

        assertThat(plan.providersOf().get(3L)).containsExactlyInAnyOrder(1L, 2L);
        assertThat(plan.providersOf().get(2L)).containsExactly(1L);
        assertThat(plan.layers()).containsExactly(List.of(1L), List.of(2L));
        assertThat(plan.provisions().get(1L).consumed()).containsOnlyKeys(acme("parent"), acme("bom"))
                .containsEntry(acme("parent"), "pom");
        assertThat(plan.provisions().get(2L).consumed()).containsExactly(Map.entry(acme("lib"), "jar"));
        assertThat(plan.cyclic()).isEmpty();
        assertThat(ArtifactProviders.unmatched(read)).containsExactly(new Gav("org.slf4j", "slf4j-api", "2.0.9"));
    }

    @Test
    void aRepositoryIsNeverItsOwnProviderAndOnlyRootsWithConsumedModulesAreInstalled() {
        Map<Long, List<MavenModule>> read = new LinkedHashMap<>();
        read.put(1L, List.of(module("svc-a", "svc-a", "a", "jar", null, List.of()),
                module("svc-b", "svc-b", "b", "jar", null, List.of(), on("a"))));
        read.put(2L, List.of(module(".", ".", "app", "jar", null, List.of(), on("b"))));

        ArtifactProviders.Plan plan = ArtifactProviders.plan(read);

        assertThat(plan.providersOf()).containsOnlyKeys(2L);
        assertThat(plan.provisions().get(1L).projectRoots()).containsExactly("svc-b");
    }

    @Test
    void repositoriesInACycleAreInstalledLastAndMarked() {
        Map<Long, List<MavenModule>> read = new LinkedHashMap<>();
        read.put(1L, List.of(module(".", ".", "x", "jar", null, List.of(), on("y"))));
        read.put(2L, List.of(module(".", ".", "y", "jar", null, List.of(), on("x"))));
        read.put(3L, List.of(module(".", ".", "base", "pom", null, List.of())));
        read.put(4L, List.of(module(".", ".", "app", "jar", acme("base"), List.of(), on("x"))));

        ArtifactProviders.Plan plan = ArtifactProviders.plan(read);

        assertThat(plan.layers()).containsExactly(List.of(3L), List.of(1L, 2L));
        assertThat(plan.cyclic()).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void unresolvedVersionsNeverMatch() {
        Map<Long, List<MavenModule>> read = new LinkedHashMap<>();
        read.put(1L, List.of(new MavenModule(".", "com.acme", "lib", null, List.of(), List.of(), true, "jar", null,
                List.of(), ".")));
        read.put(2L, List.of(module(".", ".", "app", "jar", null, List.of(),
                new DependencyRecord("com.acme", "lib", null, "compile"))));

        assertThat(ArtifactProviders.plan(read).provisions()).isEmpty();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q test -Dfrontend.skip=true -Dtest=ArtifactProvidersTest`
Expected: compilation error, because `ArtifactProviders` does not exist.

- [ ] **Step 3: Implement**

`src/main/java/com/graphify/indexing/ArtifactProviders.java`:
```java
package com.graphify.indexing;

import com.graphify.maven.Gav;
import com.graphify.maven.MavenModule;
import com.graphify.store.DependencyRecord;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which scanned repositories must install artifacts for which others (spec §3.2): a module's parent, imported BOMs
 * and dependencies are matched to the modules of other repositories by exact groupId:artifactId:version. Providers
 * are ordered in layers (Kahn's algorithm, ties in the caller's order); repositories in a cycle form a last layer.
 */
public final class ArtifactProviders {

    /** What one provider installs: the consumed coordinates with their packaging, and the project roots to build. */
    public record Provision(long repositoryId, Map<Gav, String> consumed, Set<String> projectRoots) {
    }

    public record Plan(Map<Long, Provision> provisions, Map<Long, Set<Long>> providersOf, List<List<Long>> layers,
            Set<Long> cyclic) {
    }

    private ArtifactProviders() {
    }

    /** Everything a module may need from elsewhere; partial or unresolved coordinates are left out. */
    public static Set<Gav> references(MavenModule module) {
        Set<Gav> references = new LinkedHashSet<>();
        if (module.parent() != null) {
            references.add(module.parent());
        }
        references.addAll(module.imports());
        for (DependencyRecord dependency : module.dependencies()) {
            Gav gav = Gav.of(dependency.groupId(), dependency.artifactId(), dependency.version());
            if (gav != null) {
                references.add(gav);
            }
        }
        return references;
    }

    /** References that no given module declares (third-party libraries, or repositories not read yet). */
    public static Set<Gav> unmatched(Map<Long, List<MavenModule>> modulesByRepository) {
        Map<Gav, Long> declared = declarations(modulesByRepository);
        Set<Gav> unmatched = new LinkedHashSet<>();
        modulesByRepository.values().forEach(modules -> modules.forEach(module -> references(module).stream()
                .filter(reference -> !declared.containsKey(reference)).forEach(unmatched::add)));
        return unmatched;
    }

    public static Plan plan(Map<Long, List<MavenModule>> modulesByRepository) {
        Map<Gav, Long> declared = declarations(modulesByRepository);
        Map<Gav, MavenModule> declaringModule = new HashMap<>();
        modulesByRepository.values().forEach(modules -> modules.forEach(module -> {
            if (module.gav() != null) {
                declaringModule.putIfAbsent(module.gav(), module);
            }
        }));
        Map<Long, Set<Long>> providersOf = new LinkedHashMap<>();
        Map<Long, Map<Gav, String>> consumed = new LinkedHashMap<>();
        Map<Long, Set<String>> roots = new LinkedHashMap<>();
        modulesByRepository.forEach((consumer, modules) -> {
            for (MavenModule module : modules) {
                for (Gav reference : references(module)) {
                    Long provider = declared.get(reference);
                    if (provider == null || provider.equals(consumer)) {
                        continue;
                    }
                    providersOf.computeIfAbsent(consumer, c -> new LinkedHashSet<>()).add(provider);
                    MavenModule declaring = declaringModule.get(reference);
                    consumed.computeIfAbsent(provider, p -> new LinkedHashMap<>()).put(reference, declaring.packaging());
                    roots.computeIfAbsent(provider, p -> new LinkedHashSet<>())
                            .add(declaring.projectRoot() == null ? "." : declaring.projectRoot());
                }
            }
        });
        Map<Long, Provision> provisions = new LinkedHashMap<>();
        for (Long repository : modulesByRepository.keySet()) {
            if (consumed.containsKey(repository)) {
                provisions.put(repository, new Provision(repository, Map.copyOf(consumed.get(repository)),
                        Set.copyOf(roots.get(repository))));
            }
        }
        List<List<Long>> layers = new ArrayList<>();
        Set<Long> placed = new LinkedHashSet<>();
        Set<Long> remaining = new LinkedHashSet<>(provisions.keySet());
        while (!remaining.isEmpty()) {
            List<Long> layer = new ArrayList<>();
            for (Long provider : remaining) {
                Set<Long> needs = providersOf.getOrDefault(provider, Set.of());
                if (needs.stream().filter(provisions::containsKey).allMatch(placed::contains)) {
                    layer.add(provider);
                }
            }
            if (layer.isEmpty()) {
                break;
            }
            layers.add(layer);
            placed.addAll(layer);
            remaining.removeAll(layer);
        }
        Set<Long> cyclic = Set.copyOf(remaining);
        if (!remaining.isEmpty()) {
            layers.add(new ArrayList<>(remaining));
        }
        return new Plan(provisions, providersOf, layers, cyclic);
    }

    /** Coordinate → declaring repository; the first repository in the caller's order wins a duplicate. */
    private static Map<Gav, Long> declarations(Map<Long, List<MavenModule>> modulesByRepository) {
        Map<Gav, Long> declared = new HashMap<>();
        modulesByRepository.forEach((repository, modules) -> modules.forEach(module -> {
            if (module.gav() != null) {
                declared.putIfAbsent(module.gav(), repository);
            }
        }));
        return declared;
    }
}
```
- Note on the `Provision` record: `Map.copyOf` and `Set.copyOf` lose insertion order. Order does not matter there: the installer sorts the roots it receives, and `consumed` is only checked.
- Note on `providersOf`: it keeps insertion order but is never used for ordering, since `layers` is the order.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -q test -Dfrontend.skip=true -Dtest=ArtifactProvidersTest`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/graphify/indexing/ArtifactProviders.java src/test/java/com/graphify/indexing/ArtifactProvidersTest.java
git commit src/main/java/com/graphify/indexing/ArtifactProviders.java src/test/java/com/graphify/indexing/ArtifactProvidersTest.java \
  -m "feat(indexing): match consumer references to scanned modules and order providers in layers" \
  -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01XpYgYKeBYFNw4tb6EwJ44V"
```

---

### Task 4: Shared Maven invocation and the artifact installer

**Files:**
- Create: `src/main/java/com/graphify/maven/MavenInvocation.java`, `src/main/java/com/graphify/maven/ArtifactInstaller.java`, `src/test/java/com/graphify/maven/ArtifactInstallerTest.java`
- Modify: `maven/ClasspathResolver.java` (delegates its process code)
- Test: `maven/ClasspathResolverTest.java` and `maven/MavenExecutableValidatorTest.java` keep passing unchanged.

**Interfaces:**
- **Produces:**
  - `final class MavenInvocation` (package-private): `MavenInvocation(AppSettings, ArtifactRepositories)`. `String run(Path directory, List<String> arguments)` returns null on success, else a masked error; it throws `IOException` only when the temp files cannot be written.
  - `@Component public class ArtifactInstaller`:
    - `String install(Path checkout, Collection<String> projectRoots)` returns null when every root installed, else the joined errors. With several roots each line is prefixed `[root]`.
    - `boolean present(Gav gav, String packaging)`.
    - `static final List<String> INSTALL_ARGUMENTS`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/graphify/maven/ArtifactInstallerTest.java`:
```java
package com.graphify.maven;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import com.graphify.testsupport.SettingsOverride;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

/** Runs the real `mvn install` into ~/.m2/repository under a test-only group that each test removes. */
class ArtifactInstallerTest extends OracleIntegrationTest {

    static final String GROUP = "com.graphify.testfixture.acme";
    static final Path LOCAL = Path.of(System.getProperty("user.home"), ".m2", "repository");
    static final Path GROUP_FOLDER = LOCAL.resolve(GROUP.replace('.', '/'));

    @Autowired
    ArtifactInstaller installer;

    @Autowired
    AppSettings settings;

    @TempDir
    Path dir;

    private SettingsOverride overrides;

    @BeforeEach
    void setUp() throws Exception {
        overrides = new SettingsOverride(settings).set(SettingKeys.INDEX_MAVEN_LOCAL_REPOSITORY, LOCAL.toString());
        jdbc.update("DELETE FROM artifact_repository");
        deleteGroup();
    }

    @AfterEach
    void tearDown() throws Exception {
        overrides.restore();
        deleteGroup();
    }

    static void deleteGroup() throws Exception {
        if (Files.exists(GROUP_FOLDER)) {
            try (Stream<Path> files = Files.walk(GROUP_FOLDER)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(file);
                }
            }
        }
    }

    private void write(String relative, String content) throws Exception {
        Path file = dir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    static String pom(String artifactId, String packaging, String modulesXml) {
        return """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId><artifactId>%s</artifactId><version>1.0-SNAPSHOT</version>
                  <packaging>%s</packaging>%s
                  <properties><maven.compiler.release>17</maven.compiler.release>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
                </project>
                """.formatted(GROUP, artifactId, packaging, modulesXml);
    }

    @Test
    void installsTheRequestedRootAndReportsWhatIsPresent() throws Exception {
        write("lib/pom.xml", pom("lib", "jar", ""));
        write("lib/src/main/java/acme/Lib.java", "package acme; public class Lib {}");
        write("other/pom.xml", pom("other", "jar", ""));
        write("other/src/main/java/acme/Other.java", "package acme; public class Other {}");
        Gav lib = new Gav(GROUP, "lib", "1.0-SNAPSHOT");
        Gav other = new Gav(GROUP, "other", "1.0-SNAPSHOT");

        assertThat(installer.present(lib, "jar")).isFalse();
        assertThat(installer.install(dir, List.of("lib"))).isNull();

        assertThat(installer.present(lib, "jar")).isTrue();
        assertThat(installer.present(other, "jar")).isFalse();
    }

    @Test
    void aPomOnlyProviderNeedsOnlyItsPom() throws Exception {
        write("pom.xml", pom("parent", "pom", ""));

        assertThat(installer.install(dir, List.of("."))).isNull();
        assertThat(installer.present(new Gav(GROUP, "parent", "1.0-SNAPSHOT"), "pom")).isTrue();
        assertThat(installer.present(new Gav(GROUP, "parent", "1.0-SNAPSHOT"), "jar")).isFalse();
    }

    @Test
    void aProjectThatDoesNotCompileReturnsTheMavenOutput() throws Exception {
        write("pom.xml", pom("broken", "jar", ""));
        write("src/main/java/acme/Broken.java", "package acme; public class Broken { int x = ; }");

        String error = installer.install(dir, List.of("."));

        assertThat(error).startsWith("Maven exited with").contains("COMPILATION ERROR");
        assertThat(installer.present(new Gav(GROUP, "broken", "1.0-SNAPSHOT"), "jar")).isFalse();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q test -Dfrontend.skip=true -Dtest=ArtifactInstallerTest`
Expected: compilation error, because `ArtifactInstaller` does not exist.

- [ ] **Step 3: Implement**

`src/main/java/com/graphify/maven/MavenInvocation.java`, which moves `ClasspathResolver.run` and the settings/log handling:
```java
package com.graphify.maven;

import com.graphify.scm.UrlMasking;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Runs the configured Maven with the generated settings.xml and local repository, without a shell and with the
 * environment reduced to {@link ClasspathResolver#INHERITED_ENVIRONMENT}; the settings file and the output log are
 * owner-only temp files deleted afterwards. Returns null on success, else a masked error with the output's tail.
 */
final class MavenInvocation {

    private final AppSettings settings;
    private final ArtifactRepositories repositories;

    MavenInvocation(AppSettings settings, ArtifactRepositories repositories) {
        this.settings = settings;
        this.repositories = repositories;
    }

    String run(Path directory, List<String> arguments) throws IOException {
        Path settingsFile = null;
        Path log = null;
        try {
            String token = UUID.randomUUID().toString().replace("-", "");
            settingsFile = ClasspathResolver.privateTempFile("graphify-settings", ".xml");
            Files.writeString(settingsFile, SettingsXmlWriter.write(repositories.enabled(), token), StandardCharsets.UTF_8);
            log = ClasspathResolver.privateTempFile("graphify-maven", ".log");
            return execute(directory, arguments, settingsFile, log);
        } finally {
            ClasspathResolver.deleteQuietly(settingsFile);
            ClasspathResolver.deleteQuietly(log);
        }
    }

    private String execute(Path directory, List<String> arguments, Path settingsFile, Path log) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(settings.getString(SettingKeys.INDEX_MAVEN_EXECUTABLE));
        command.addAll(List.of("-s", settingsFile.toString(),
                "-Dmaven.repo.local=" + settings.getString(SettingKeys.INDEX_MAVEN_LOCAL_REPOSITORY)));
        command.addAll(arguments);
        // ... the body of the former ClasspathResolver.run from "ProcessBuilder builder = …" to its end, unchanged
        //     (directory instead of root) ...
    }
}
```
- The argument order changes from `executable FLAGS -s … -Dmaven.repo.local=… GOALS` to `executable -s … -Dmaven.repo.local=… FLAGS GOALS`. Maven accepts options in any order before or between goals.
- Keep the old order if `ClasspathResolverTest` checks the command line; it does not today.

`ClasspathResolver`:
- **New field:** `private final MavenInvocation maven;`, initialised in the constructor with `new MavenInvocation(settings, repositories)`.
- **`resolveRoot`** keeps deleting the output files, then calls `String error = maven.run(directory, arguments())`, where `arguments()` is `MAVEN_FLAGS` followed by `MAVEN_GOALS`.
- **Then** it reads the classpath files as before, keeping the `IOException` catch.
- **Removed:** the old `run` and its settings and log handling.
- **Kept:** `privateTempFile`, `deleteQuietly`, `readLenient`, `tail`, `childEnvironment`, `KILL_WAIT` and `INHERITED_ENVIRONMENT` stay where they are, package-private or wider as today; `MavenExecutableValidator` uses them.

`src/main/java/com/graphify/maven/ArtifactInstaller.java`:
```java
package com.graphify.maven;

import com.graphify.scm.UrlMasking;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Installs a scanned repository's artifacts into the local Maven repository so other scanned repositories resolve
 * them (spec §3.2): `install` without tests in each given project root, and a presence check by file layout.
 */
@Component
public class ArtifactInstaller {

    /** Batch, quiet, fail at end, no test compilation or run; the install phase builds and installs the reactor. */
    static final List<String> INSTALL_ARGUMENTS = List.of("-B", "-q", "-fae", "-Dmaven.test.skip=true", "install");

    private final AppSettings settings;
    private final MavenInvocation maven;

    public ArtifactInstaller(AppSettings settings, ArtifactRepositories repositories) {
        this.settings = settings;
        this.maven = new MavenInvocation(settings, repositories);
    }

    /** Null when every root installed; otherwise the errors, each prefixed with its root when there are several. */
    public String install(Path checkout, Collection<String> projectRoots) {
        List<String> roots = projectRoots.stream().sorted().toList();
        List<String> errors = new ArrayList<>();
        for (String root : roots) {
            Path directory = root.equals(".") ? checkout : checkout.resolve(root);
            String error;
            try {
                error = maven.run(directory, INSTALL_ARGUMENTS);
            } catch (IOException e) {
                error = "Maven install failed: " + UrlMasking.mask(e.getMessage());
            }
            if (error != null) {
                errors.add(roots.size() == 1 ? error : "[" + root + "] " + error);
            }
        }
        return errors.isEmpty() ? null : String.join("\n", errors);
    }

    /** Whether the local repository holds the coordinate's pom, and its jar when the packaging is jar (or unknown). */
    public boolean present(Gav gav, String packaging) {
        Path folder = Path.of(settings.getString(SettingKeys.INDEX_MAVEN_LOCAL_REPOSITORY))
                .resolve(gav.groupId().replace('.', '/')).resolve(gav.artifactId()).resolve(gav.version());
        String base = gav.artifactId() + "-" + gav.version();
        boolean pom = Files.isRegularFile(folder.resolve(base + ".pom"));
        boolean jarNeeded = packaging == null || packaging.equals("jar");
        return pom && (!jarNeeded || Files.isRegularFile(folder.resolve(base + ".jar")));
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -q test -Dfrontend.skip=true -Dtest='ArtifactInstallerTest,ClasspathResolverTest,MavenExecutableValidatorTest'`
Expected: PASS. Then run the full backend suite.

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/graphify/maven/MavenInvocation.java src/main/java/com/graphify/maven/ArtifactInstaller.java src/test/java/com/graphify/maven/ArtifactInstallerTest.java
git commit src/main/java/com/graphify/maven/MavenInvocation.java src/main/java/com/graphify/maven/ArtifactInstaller.java \
  src/main/java/com/graphify/maven/ClasspathResolver.java src/test/java/com/graphify/maven/ArtifactInstallerTest.java \
  -m "feat(maven): install a repository's project roots into the local repository" \
  -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01XpYgYKeBYFNw4tb6EwJ44V"
```

---

### Task 5: Three-phase runs: prepare, install providers, index

**Files:**
- Create:
  - `indexing/ArtifactInstallStatus.java`, `indexing/ArtifactInstallOutcome.java`, `indexing/PreparedRepository.java`, `indexing/Preparation.java`, `indexing/ModuleCoordinates.java`
  - test fixture `src/test/java/com/graphify/testsupport/AcmeScm.java`
  - test `src/test/java/com/graphify/indexing/WorkspaceArtifactsTest.java`
- Modify: `indexing/RepositoryIndexer.java`, `indexing/IndexRunExecutor.java`, `indexing/IndexRunRecorder.java`
- Test: `indexing/IndexRunServiceFailureTest.java` (the stub constructor), plus the existing `IndexRunExecutorTest`, `RepositoryIndexerTest`, `RepositoryIndexerInterruptionTest`, `IndexRunServiceTest`.

**Interfaces:**
- **Consumes:** Task 1 (`MavenModule` fields, V13 columns), Task 2 (`read(…, depth)`), Task 3 (`ArtifactProviders`), Task 4 (`ArtifactInstaller`).
- **Produces:**
  - `enum ArtifactInstallStatus { INSTALLED, UP_TO_DATE, FAILED, CYCLE_FAILED }`
  - `record ArtifactInstallOutcome(ArtifactInstallStatus status, String error)`; `error` is masked.
  - `record PreparedRepository(long repositoryId, String slug, String commit, Path checkout, MavenProject project, boolean unchanged, String lastInstalledCommit)`
  - `record Preparation(PreparedRepository prepared, RepoIndexOutcome finished)`; exactly one of the two is non-null.
  - **`RepositoryIndexer`:**
    - `Preparation prepare(long repositoryId, boolean force)`
    - `RepoIndexOutcome complete(long runId, PreparedRepository prepared, boolean reindex, ArtifactInstallOutcome install, long startedAt)`
    - `RepoIndexOutcome finish(long runId, long repositoryId, RepoIndexOutcome finished)`
    - `void recordInstalled(long repositoryId, String commit)`
    - `index(runId, id, force)` is kept.
  - **`IndexRunRecorder`:**
    - `record(runId, repoId, outcome, ArtifactInstallOutcome install)`
    - `recordInstall(runId, repoId, commit, ArtifactInstallOutcome install)`
    - `recordFailure(runId, repoId, error, durationMillis, ArtifactInstallOutcome install)`
    - The old overloads are kept.
  - `ModuleCoordinates.declaringRepositories()` returns `Map<Gav, List<Long>>` over active repositories, ordered by `project_key, slug, id`.
  - `IndexRunExecutor` constructor: `(JdbcTemplate, ScmConnections, RepositorySync, RepositoryIndexer, IndexRunRecorder, ConnectionPoolSizer, AppSettings, ModuleCoordinates, ArtifactInstaller)`.

- [ ] **Step 1: Write the fixture and the failing tests**

`src/test/java/com/graphify/testsupport/AcmeScm.java`. Four git repositories behind a fake Bitbucket:
- `acme-parent`: a pom-only parent;
- `acme-lib`: a jar with `Greeter`, whose parent is acme-parent;
- `acme-app`: uses `Greeter`; its parent is acme-parent and it depends on acme-lib;
- `acme-gateway`: no root pom, two independent sub-projects, one in a folder with a space.

Nothing is published to a file repository, so the app resolves only after the installs.
```java
package com.graphify.testsupport;

import com.graphify.common.crypto.SecretCipher;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** Repositories that provide artifacts to each other (Plan 16); group com.graphify.testfixture.acme, SNAPSHOT. */
public final class AcmeScm implements AutoCloseable {

    public static final String TOKEN = "acme-token";
    public static final String GROUP = "com.graphify.testfixture.acme";
    public static final String VERSION = "1.0-SNAPSHOT";

    private final FakeBitbucket bitbucket;
    private final Path libBare;

    private AcmeScm(FakeBitbucket bitbucket, Path libBare) {
        this.bitbucket = bitbucket;
        this.libBare = libBare;
    }

    static String properties() {
        return "<properties><maven.compiler.release>17</maven.compiler.release>"
                + "<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>";
    }

    static String parentRef() {
        return "<parent><groupId>" + GROUP + "</groupId><artifactId>acme-parent</artifactId><version>" + VERSION
                + "</version><relativePath/></parent>";
    }

    public static AcmeScm create(JdbcTemplate jdbc, SecretCipher cipher, Path dir) throws IOException {
        Path parentBare = GitFixtures.bareRepository(dir, "acme-parent", Map.of("pom.xml",
                MavenFixtures.pom(GROUP, "acme-parent", VERSION, "<packaging>pom</packaging>" + properties(), "")));
        Path libBare = GitFixtures.bareRepository(dir, "acme-lib", Map.of(
                "pom.xml", MavenFixtures.pom(GROUP, "acme-lib", VERSION, parentRef(), ""),
                "src/main/java/com/graphify/testfixture/acme/lib/Greeter.java",
                "package com.graphify.testfixture.acme.lib; public class Greeter { public String greet() { return \"hi\"; } }"));
        Path appBare = GitFixtures.bareRepository(dir, "acme-app", Map.of(
                "pom.xml", MavenFixtures.pom(GROUP, "acme-app", VERSION, parentRef(),
                        MavenFixtures.dependency(GROUP, "acme-lib", VERSION)),
                "src/main/java/com/graphify/testfixture/acme/app/App.java",
                "package com.graphify.testfixture.acme.app; import com.graphify.testfixture.acme.lib.Greeter; "
                        + "public class App { String run() { return new Greeter().greet(); } }"));
        Path gatewayBare = GitFixtures.bareRepository(dir, "acme-gateway", Map.of(
                "Payment Service/pom.xml", MavenFixtures.pom(GROUP, "payment", VERSION, properties(), ""),
                "Payment Service/src/main/java/acme/pay/Pay.java", "package acme.pay; public class Pay {}",
                "orders/pom.xml", MavenFixtures.pom(GROUP, "orders", VERSION, properties(), ""),
                "orders/src/main/java/acme/orders/Order.java", "package acme.orders; public class Order {}"));
        FakeBitbucket bitbucket = new FakeBitbucket().start().requireAuthorization("Bearer " + TOKEN)
                .addRepository("ACME", "acme-parent", GitFixtures.url(parentBare))
                .addRepository("ACME", "acme-lib", GitFixtures.url(libBare))
                .addRepository("ACME", "acme-app", GitFixtures.url(appBare))
                .addRepository("ACME", "acme-gateway", GitFixtures.url(gatewayBare));
        jdbc.update("INSERT INTO scm_connection (name, type, base_url, secret_enc) VALUES ('acme', 'BITBUCKET_DC', ?, ?)",
                bitbucket.baseUrl(), cipher.encrypt(TOKEN));
        return new AcmeScm(bitbucket, libBare);
    }

    public Path libBare() {
        return libBare;
    }

    @Override
    public void close() {
        bitbucket.close();
    }
}
```
(`MavenFixtures.pom` places its `parentAndModulesXml` argument before the coordinates, so passing a `<parent>` or `<packaging>`/`<properties>` snippet there is valid POM.)

`src/test/java/com/graphify/indexing/WorkspaceArtifactsTest.java`:
```java
package com.graphify.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.common.crypto.SecretCipher;
import com.graphify.impact.ImpactEdge;
import com.graphify.impact.ImpactRequest;
import com.graphify.impact.ImpactService;
import com.graphify.indexer.model.Confidence;
import com.graphify.maven.ArtifactInstallerTest;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import com.graphify.store.StoreFixtures;
import com.graphify.testsupport.AcmeScm;
import com.graphify.testsupport.GitFixtures;
import com.graphify.testsupport.SettingsOverride;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

/** Spec §8: whole runs over repositories that provide parents and jars to each other, with the real Maven. */
class WorkspaceArtifactsTest extends OracleIntegrationTest {

    private static final Path LOCAL = Path.of(System.getProperty("user.home"), ".m2", "repository");
    private static final Path LIB_JAR = LOCAL.resolve("com/graphify/testfixture/acme/acme-lib/1.0-SNAPSHOT/"
            + "acme-lib-1.0-SNAPSHOT.jar");

    @Autowired IndexRunExecutor executor;
    @Autowired IndexRunRecorder runs;
    @Autowired SecretCipher cipher;
    @Autowired AppSettings settings;
    @Autowired ImpactService impact;

    @TempDir
    Path dir;

    private AcmeScm acme;
    private SettingsOverride overrides;

    @BeforeEach
    void setUp() throws Exception {
        StoreFixtures.cleanIndexTables(jdbc);
        jdbc.update("DELETE FROM artifact_repository");
        ArtifactInstallerTest.deleteGroup();
        overrides = new SettingsOverride(settings)
                .set(SettingKeys.INDEX_WORKSPACE_DIR, dir.resolve("ws").toString())
                .set(SettingKeys.INDEX_MAVEN_LOCAL_REPOSITORY, LOCAL.toString())
                .set(SettingKeys.INDEX_PARALLELISM, "2");
        acme = AcmeScm.create(jdbc, cipher, dir);
    }

    @AfterEach
    void tearDown() throws Exception {
        acme.close();
        overrides.restore();
        ArtifactInstallerTest.deleteGroup();
    }

    private long run(RunScope scope, Long scopeId) {
        long runId = runs.start(RunTrigger.MANUAL, scope, scopeId, "test");
        executor.execute(runId, scope, scopeId, false);
        return runId;
    }

    private Map<String, Object> row(long runId, String slug) {
        return jdbc.queryForMap("SELECT x.status, x.classpath_mode, x.artifact_install, "
                + "DBMS_LOB.SUBSTR(x.artifact_install_error, 4000, 1) AS install_error FROM index_run_repo x "
                + "JOIN scm_repository r ON r.id = x.repo_id WHERE x.run_id = ? AND r.slug = ?", runId, slug);
    }

    private long repo(String slug) {
        return jdbc.queryForObject("SELECT id FROM scm_repository WHERE slug = ?", Long.class, slug);
    }

    @Test
    void aParentAndALibraryFromOtherRepositoriesAreInstalledFirst() {
        long runId = run(RunScope.ALL, null);

        assertThat(row(runId, "acme-parent")).containsEntry("STATUS", "SKIPPED_NOT_JAVA")
                .containsEntry("ARTIFACT_INSTALL", "INSTALLED");
        assertThat(row(runId, "acme-lib")).containsEntry("STATUS", "SUCCESS").containsEntry("ARTIFACT_INSTALL", "INSTALLED");
        assertThat(row(runId, "acme-app")).containsEntry("STATUS", "SUCCESS").containsEntry("CLASSPATH_MODE", "FULL")
                .containsEntry("ARTIFACT_INSTALL", null);
        assertThat(LIB_JAR).isRegularFile();
        assertThat(jdbc.queryForObject("SELECT last_installed_commit FROM scm_repository WHERE slug = 'acme-lib'",
                String.class)).isNotNull();
        long greet = jdbc.queryForObject("SELECT id FROM symbol WHERE symbol_key = ?", Long.class,
                "com.graphify.testfixture.acme.lib.Greeter#greet()");
        assertThat(impact.analyze(new ImpactRequest(List.of(greet), null, 1, null, null)).edges())
                .filteredOn(e -> e.level() == 1).extracting(ImpactEdge::confidence).contains(Confidence.EXACT);
    }

    @Test
    void anUnchangedSecondRunIsUpToDateAndADeletedArtifactIsInstalledAgain() throws Exception {
        run(RunScope.ALL, null);
        FileTime installed = Files.getLastModifiedTime(LIB_JAR);

        long second = run(RunScope.ALL, null);
        assertThat(row(second, "acme-lib")).containsEntry("ARTIFACT_INSTALL", "UP_TO_DATE")
                .containsEntry("STATUS", "SKIPPED_UNCHANGED");
        assertThat(row(second, "acme-app")).containsEntry("STATUS", "SKIPPED_UNCHANGED");
        assertThat(Files.getLastModifiedTime(LIB_JAR)).isEqualTo(installed);

        Files.delete(LIB_JAR);
        long third = run(RunScope.ALL, null);
        assertThat(row(third, "acme-lib")).containsEntry("ARTIFACT_INSTALL", "INSTALLED");
        assertThat(row(third, "acme-app")).containsEntry("STATUS", "SUCCESS");
        assertThat(LIB_JAR).isRegularFile();
    }

    @Test
    void providersOutsideARepositoryScanAreInstalledAndListedInTheRun() throws Exception {
        run(RunScope.ALL, null);
        ArtifactInstallerTest.deleteGroup();

        long runId = run(RunScope.REPOSITORY, repo("acme-app"));

        assertThat(row(runId, "acme-app")).containsEntry("STATUS", "SUCCESS").containsEntry("CLASSPATH_MODE", "FULL");
        assertThat(row(runId, "acme-lib")).containsEntry("STATUS", "SKIPPED_UNCHANGED")
                .containsEntry("ARTIFACT_INSTALL", "INSTALLED");
        assertThat(row(runId, "acme-parent")).containsEntry("ARTIFACT_INSTALL", "INSTALLED");
    }

    @Test
    void aProviderThatDoesNotCompileFailsItsInstallButIsStillIndexed() throws Exception {
        GitFixtures.commit(acme.libBare(), Map.of("src/main/java/com/graphify/testfixture/acme/lib/Broken.java",
                "package com.graphify.testfixture.acme.lib; class Broken { int x = ; }"), "break the build");

        long runId = run(RunScope.ALL, null);

        Map<String, Object> lib = row(runId, "acme-lib");
        assertThat(lib).containsEntry("ARTIFACT_INSTALL", "FAILED").containsEntry("STATUS", "SUCCESS");
        assertThat((String) lib.get("INSTALL_ERROR")).contains("COMPILATION ERROR");
        assertThat(row(runId, "acme-app")).containsEntry("STATUS", "SUCCESS_PARTIAL");
    }

    @Test
    void aRepositoryWithoutARootPomIndexesItsSubProjects() {
        long runId = run(RunScope.ALL, null);

        assertThat(row(runId, "acme-gateway")).containsEntry("STATUS", "SUCCESS").containsEntry("CLASSPATH_MODE", "FULL");
        assertThat(jdbc.queryForList("SELECT m.path FROM maven_module m JOIN scm_repository r ON r.id = m.repo_id "
                + "WHERE r.slug = 'acme-gateway' ORDER BY m.path", String.class))
                .containsExactly("Payment Service", "orders");
    }
}
```
- Adapt `GitFixtures.commit(bare, files, message)` to its real signature; it exists and returns the new commit.
- `acme-lib`'s `Broken.java` does not stop JDT indexing, which is lenient, so its status stays SUCCESS.
- The app is SUCCESS_PARTIAL in the last test only because `deleteGroup()` ran in setUp.

`IndexRunServiceFailureTest`: the stub becomes `new IndexRunExecutor(null, null, null, null, null, null, null, null, null) {`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q test -Dfrontend.skip=true -Dtest='WorkspaceArtifactsTest,IndexRunServiceFailureTest'`
Expected: `IndexRunServiceFailureTest` fails to compile (constructor arity). Once that compiles, the `WorkspaceArtifactsTest` tests fail: `acme-app` is SUCCESS_PARTIAL and `ARTIFACT_INSTALL` is null everywhere.

- [ ] **Step 3: Implement**

`ArtifactInstallStatus.java`:
```java
package com.graphify.indexing;

/** What happened to a provider repository's artifacts in one run (index_run_repo.artifact_install, V13). */
public enum ArtifactInstallStatus {
    INSTALLED, UP_TO_DATE, FAILED, CYCLE_FAILED
}
```
`ArtifactInstallOutcome.java`:
```java
package com.graphify.indexing;

/** A provider's install result; {@code error} is masked Maven output or a note, null when there is nothing to say. */
public record ArtifactInstallOutcome(ArtifactInstallStatus status, String error) {
}
```
`PreparedRepository.java`:
```java
package com.graphify.indexing;

import com.graphify.maven.MavenProject;
import java.nio.file.Path;

/**
 * A repository checked out and read in a run's first phase. {@code unchanged} is today's skip rule (same commit, last
 * run not partial, not forced); the index phase applies it unless a provider of this repository was just installed.
 */
public record PreparedRepository(long repositoryId, String slug, String commit, Path checkout, MavenProject project,
        boolean unchanged, String lastInstalledCommit) {
}
```
`Preparation.java`:
```java
package com.graphify.indexing;

/** Either a prepared repository or its final outcome (the head check or the checkout failed). */
public record Preparation(PreparedRepository prepared, RepoIndexOutcome finished) {
}
```
`ModuleCoordinates.java`:
```java
package com.graphify.indexing;

import com.graphify.maven.Gav;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Which active repositories last declared each module coordinate (maven_module), to find providers outside a run. */
@Repository
public class ModuleCoordinates {

    private final JdbcTemplate jdbc;

    public ModuleCoordinates(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<Gav, List<Long>> declaringRepositories() {
        Map<Gav, List<Long>> declared = new LinkedHashMap<>();
        jdbc.query("""
                SELECT m.repo_id, m.group_id, m.artifact_id, m.version
                  FROM maven_module m JOIN scm_repository r ON r.id = m.repo_id
                 WHERE r.active = 1 AND m.group_id IS NOT NULL AND m.artifact_id IS NOT NULL AND m.version IS NOT NULL
                 ORDER BY r.project_key, r.slug, r.id
                """, rs -> {
                    Gav gav = Gav.of(rs.getString("group_id"), rs.getString("artifact_id"), rs.getString("version"));
                    if (gav != null) {
                        declared.computeIfAbsent(gav, g -> new ArrayList<>()).add(rs.getLong("repo_id"));
                    }
                });
        return declared;
    }
}
```

`IndexRunRecorder`:
```java
    public void record(long runId, long repositoryId, RepoIndexOutcome outcome) {
        record(runId, repositoryId, outcome, null);
    }

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
```
`artifact_install_error` is a CLOB, so it is not cut. The Maven tail is already bounded by `index.maven_output_tail_lines` and masked.

`RepositoryIndexer`: split `run` into the phases. `RepositoryRow` gains `lastInstalledCommit`; `load` selects `r.last_installed_commit`.
```java
    /** Phase 1: the head check, checkout and pom reading; the skip rule is only noted (spec §3.1). */
    public Preparation prepare(long repositoryId, boolean force) {
        long startedAt = System.nanoTime();
        RepositoryRow repo = load(repositoryId);
        ScmConnection connection = connectionOf(repo);
        RemoteHead head;
        try {
            head = workspace.remoteHead(repo.cloneUrl(), connection.gitAuthorization());
        } catch (RuntimeException e) {
            rethrowIfInterrupted(e);
            return new Preparation(null, outcome(RepoIndexStatus.CLONE_FAILED, null, null, message(e), startedAt));
        }
        jdbc.update("UPDATE scm_repository SET default_branch = ? WHERE id = ?", head.branch(), repo.id());
        Path checkout;
        String commit;
        try {
            checkout = workspace.directoryFor(repo.connectionId(), repo.projectKey(), repo.slug());
            commit = workspace.checkout(checkout, repo.cloneUrl(), head.branch(), connection.gitAuthorization());
        } catch (RuntimeException e) {
            rethrowIfInterrupted(e);
            return new Preparation(null, outcome(RepoIndexStatus.CLONE_FAILED, null, null, message(e), startedAt));
        }
        MavenProject project = mavenReader.read(checkout, settings.getList(SettingKeys.INDEX_SOURCE_ROOTS),
                settings.getInt(SettingKeys.INDEX_POM_SEARCH_DEPTH));
        // a partial index is retried on the same commit: the classpath may resolve now
        boolean unchanged = !force && commit.equals(repo.lastIndexedCommit())
                && !RepoIndexStatus.SUCCESS_PARTIAL.name().equals(repo.lastStatus());
        return new Preparation(new PreparedRepository(repo.id(), repo.slug(), commit, checkout, project, unchanged,
                repo.lastInstalledCommit()), null);
    }

    /** Phase 3: index a prepared repository (or skip it as unchanged), then record its outcome and install. */
    public RepoIndexOutcome complete(long runId, PreparedRepository prepared, boolean reindex,
            ArtifactInstallOutcome install, long startedAt) {
        RepoIndexOutcome outcome;
        if (prepared.unchanged() && !reindex) {
            // a failed or interrupted analysis is redone at the same commit
            if (!graphs.isCurrent(prepared.repositoryId(), prepared.commit())) {
                analyzeGraph(prepared.repositoryId(), prepared.commit());
            }
            outcome = outcome(RepoIndexStatus.SKIPPED_UNCHANGED, prepared.commit(), null, null, startedAt);
        } else {
            outcome = indexPrepared(prepared, startedAt);
        }
        if (outcome.status() != RepoIndexStatus.SKIPPED_UNCHANGED) {
            jdbc.update("UPDATE scm_repository SET last_status = ? WHERE id = ?", outcome.status().name(),
                    prepared.repositoryId());
        }
        runs.record(runId, prepared.repositoryId(), outcome, install);
        return outcome;
    }

    /** Records a repository whose preparation already ended it (CLONE_FAILED). */
    public RepoIndexOutcome finish(long runId, long repositoryId, RepoIndexOutcome finished) {
        jdbc.update("UPDATE scm_repository SET last_status = ? WHERE id = ?", finished.status().name(), repositoryId);
        runs.record(runId, repositoryId, finished);
        return finished;
    }

    public void recordInstalled(long repositoryId, String commit) {
        jdbc.update("UPDATE scm_repository SET last_installed_commit = ? WHERE id = ?", commit, repositoryId);
    }

    /** One repository on its own (no install phase): prepare, then complete. */
    public RepoIndexOutcome index(long runId, long repositoryId, boolean force) {
        long startedAt = System.nanoTime();
        Preparation preparation = prepare(repositoryId, force);
        return preparation.finished() != null ? finish(runId, repositoryId, preparation.finished())
                : complete(runId, preparation.prepared(), false, null, startedAt);
    }
```
- **`connectionOf(repo)`** holds the former repoint guard: the `connections.find(...)` lookup and the `baseUrl` comparison that throws `RepositoryNotFoundException`.
- **`indexPrepared(prepared, startedAt)`** is the former `run` body from `List<MavenModule> withSources = …` to the end. It uses `prepared.project()`, `prepared.checkout()`, `prepared.commit()` and `prepared.repositoryId()`, and keeps the `catch (RuntimeException e) { rethrowIfInterrupted(e); return outcome(FAILED, commit, …); }`.
- **The read in `prepare`** happens outside that catch, as before: the read never throws for a broken pom; it warns. Wrap it in the same FAILED handling if it is not obviously safe.
- **The old `run` method is removed.**
- **SKIPPED_NOT_JAVA keeps the module coordinates.** A pom-only provider (a parent or a BOM repository, such as `acme-parent` or `zeus-fw`'s parents) has no Java. Today its `maven_module` rows are deleted, which would hide it from `ModuleCoordinates` and so from a later REPOSITORY-scope run (spec §3.2 "taramada olmayan sağlayıcı").
  - In `indexPrepared`, the SKIPPED_NOT_JAVA write passes `records` with `ClasspathMode.NONE` for every module with `hasPom`, instead of `List.of()`, together with the empty `IndexResult`.
  - Update `RepositoryIndexerTest.aCommitWithoutJavaClearsTheIndexAndIsNotRescanned`: `assertThat(modules(api)).isEqualTo(1);`, commented "the pom's coordinates stay, so the repository can still provide it". Its usages and declarations are still zero.

`IndexRunExecutor`: inject `ModuleCoordinates coordinates` and `ArtifactInstaller installer`, then replace `indexAll`.
```java
    /** A provider install phase's result: install per repository, and each consumer's providers. */
    private record Provided(Map<Long, ArtifactInstallOutcome> installs, Map<Long, Set<Long>> providersOf) {
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
            Map<Long, PreparedRepository> prepared = prepareAll(executor, runId, repositoryIds, force);
            if (runs.isCancelRequested(runId)) {
                prepared.keySet().forEach(this::clearMarker);
                return;
            }
            Provided provided = provide(executor, runId, prepared, force);
            List<Future<?>> tasks = new ArrayList<>();
            for (PreparedRepository repository : prepared.values()) {
                boolean reindex = provided.providersOf().getOrDefault(repository.repositoryId(), Set.of()).stream()
                        .map(provided.installs()::get)
                        .anyMatch(i -> i != null && i.status() == ArtifactInstallStatus.INSTALLED);
                ArtifactInstallOutcome install = provided.installs().get(repository.repositoryId());
                tasks.add(executor.submit(() -> completeSafely(runId, repository, reindex, install)));
            }
            await(runId, tasks);
        } finally {
            executor.shutdownNow();
        }
    }

    private Map<Long, PreparedRepository> prepareAll(ExecutorService executor, long runId, List<Long> repositoryIds,
            boolean force) throws InterruptedException {
        Map<Long, Future<PreparedRepository>> tasks = new LinkedHashMap<>();
        for (Long repositoryId : repositoryIds) {
            tasks.put(repositoryId, executor.submit(() -> prepareSafely(runId, repositoryId, force)));
        }
        Map<Long, PreparedRepository> prepared = new LinkedHashMap<>();
        for (Map.Entry<Long, Future<PreparedRepository>> task : tasks.entrySet()) {
            try {
                PreparedRepository repository = task.getValue().get();
                if (repository != null) {
                    prepared.put(task.getKey(), repository);
                }
            } catch (ExecutionException e) {
                log.error("Index run {}: repository {} could not be prepared: {}", runId, task.getKey(),
                        masked(e.getCause()));
            }
        }
        return prepared;
    }

    /** Phase 1 for one repository; null when it already has its outcome, was cancelled or was interrupted. */
    PreparedRepository prepareSafely(long runId, long repositoryId, boolean force) {
        if (runs.isCancelRequested(runId)) {
            return null;
        }
        long startedAt = System.nanoTime();
        try {
            runs.markStarted(runId, repositoryId);
            Preparation preparation = indexer.prepare(repositoryId, force);
            if (preparation.finished() == null) {
                return preparation.prepared();
            }
            indexer.finish(runId, repositoryId, preparation.finished());
        } catch (Throwable e) {
            if (isInterruption(e, Thread.currentThread().isInterrupted())) {
                log.warn("Index run {}: repository {} was interrupted; startup recovery records it", runId,
                        repositoryId);
                return null;
            }
            String error = masked(e);
            log.warn("Index run {}: repository {} failed: {}", runId, repositoryId, error);
            runs.recordFailure(runId, repositoryId, error, (System.nanoTime() - startedAt) / 1_000_000);
        }
        clearMarker(repositoryId);
        return null;
    }

    /** Phase 3 for one repository; the marker set in phase 1 is cleared here. */
    void completeSafely(long runId, PreparedRepository repository, boolean reindex, ArtifactInstallOutcome install) {
        long repositoryId = repository.repositoryId();
        if (runs.isCancelRequested(runId)) {
            clearMarker(repositoryId);
            return;
        }
        long startedAt = System.nanoTime();
        try {
            indexer.complete(runId, repository, reindex, install, startedAt);
        } catch (Throwable e) {
            if (isInterruption(e, Thread.currentThread().isInterrupted())) {
                log.warn("Index run {}: repository {} was interrupted; startup recovery records it", runId,
                        repositoryId);
                return;
            }
            String error = masked(e);
            log.warn("Index run {}: repository {} failed: {}", runId, repositoryId, error);
            runs.recordFailure(runId, repositoryId, error, (System.nanoTime() - startedAt) / 1_000_000, install);
        }
        clearMarker(repositoryId);
    }

    /**
     * Phase 2 (spec §3.2): find providers, including active repositories outside the run that last declared a
     * referenced coordinate, then install them layer by layer. A provider outside the run gets its own row.
     */
    private Provided provide(ExecutorService executor, long runId, Map<Long, PreparedRepository> inRun, boolean force)
            throws InterruptedException {
        Map<Long, PreparedRepository> all = new LinkedHashMap<>(inRun);
        Set<Long> outside = new LinkedHashSet<>();
        Set<Long> attempted = new LinkedHashSet<>(inRun.keySet());
        Map<Gav, List<Long>> declared = coordinates.declaringRepositories();
        while (true) {
            Set<Long> wanted = new LinkedHashSet<>();
            for (Gav reference : ArtifactProviders.unmatched(modulesOf(all))) {
                declared.getOrDefault(reference, List.of()).stream().filter(attempted::add).forEach(wanted::add);
            }
            if (wanted.isEmpty() || runs.isCancelRequested(runId)) {
                break;
            }
            for (Long repositoryId : wanted) {
                PreparedRepository repository = prepareOutside(runId, repositoryId);
                if (repository != null) {
                    all.put(repositoryId, repository);
                    outside.add(repositoryId);
                }
            }
        }
        ArtifactProviders.Plan plan = ArtifactProviders.plan(modulesOf(all));
        Map<Long, ArtifactInstallOutcome> installs = new HashMap<>();
        for (List<Long> layer : plan.layers()) {
            if (runs.isCancelRequested(runId)) {
                break;
            }
            Map<Long, Future<ArtifactInstallOutcome>> tasks = new LinkedHashMap<>();
            for (Long provider : layer) {
                String note = plan.cyclic().contains(provider) ? "Cyclic dependency between scanned repositories: "
                        + plan.cyclic().stream().map(id -> all.get(id).slug()).sorted().collect(Collectors.joining(", "))
                        : null;
                tasks.put(provider, executor.submit(() -> installSafely(all.get(provider),
                        plan.provisions().get(provider), note, force)));
            }
            for (Map.Entry<Long, Future<ArtifactInstallOutcome>> task : tasks.entrySet()) {
                try {
                    installs.put(task.getKey(), task.getValue().get());
                } catch (ExecutionException e) {
                    installs.put(task.getKey(), new ArtifactInstallOutcome(ArtifactInstallStatus.FAILED,
                            masked(e.getCause())));
                }
            }
        }
        for (Long repositoryId : outside) {
            ArtifactInstallOutcome install = installs.get(repositoryId);
            if (install != null) {
                runs.recordInstall(runId, repositoryId, all.get(repositoryId).commit(), install);
            }
        }
        return new Provided(installs, plan.providersOf());
    }

    /** A provider outside the run is prepared like one in it (Plan 16 Ruling 1); null when that fails. */
    private PreparedRepository prepareOutside(long runId, long repositoryId) {
        try {
            Preparation preparation = indexer.prepare(repositoryId, false);
            if (preparation.prepared() == null) {
                log.warn("Index run {}: provider repository {} could not be checked out: {}", runId, repositoryId,
                        preparation.finished().error());
            }
            return preparation.prepared();
        } catch (RuntimeException e) {
            if (isInterruption(e, Thread.currentThread().isInterrupted())) {
                throw e;
            }
            log.warn("Index run {}: provider repository {} could not be prepared: {}", runId, repositoryId, masked(e));
            return null;
        }
    }

    ArtifactInstallOutcome installSafely(PreparedRepository provider, ArtifactProviders.Provision provision,
            String cycleNote, boolean force) {
        try {
            boolean upToDate = !force && provider.commit().equals(provider.lastInstalledCommit())
                    && provision.consumed().entrySet().stream()
                    .allMatch(e -> installer.present(e.getKey(), e.getValue()));
            if (upToDate) {
                return new ArtifactInstallOutcome(ArtifactInstallStatus.UP_TO_DATE, cycleNote);
            }
            String error = installer.install(provider.checkout(), provision.projectRoots());
            if (error == null) {
                indexer.recordInstalled(provider.repositoryId(), provider.commit());
                return new ArtifactInstallOutcome(ArtifactInstallStatus.INSTALLED, cycleNote);
            }
            return new ArtifactInstallOutcome(cycleNote == null ? ArtifactInstallStatus.FAILED
                    : ArtifactInstallStatus.CYCLE_FAILED, cycleNote == null ? error : cycleNote + "\n" + error);
        } catch (RuntimeException e) {
            if (isInterruption(e, Thread.currentThread().isInterrupted())) {
                throw e;
            }
            return new ArtifactInstallOutcome(ArtifactInstallStatus.FAILED, masked(e));
        }
    }

    private static Map<Long, List<MavenModule>> modulesOf(Map<Long, PreparedRepository> repositories) {
        Map<Long, List<MavenModule>> modules = new LinkedHashMap<>();
        repositories.forEach((id, repository) -> modules.put(id, repository.project().modules()));
        return modules;
    }

    private void await(long runId, List<Future<?>> tasks) throws InterruptedException {
        for (Future<?> task : tasks) {
            try {
                task.get();
            } catch (ExecutionException e) {
                // only reachable when even recording the failure failed (database down)
                log.error("Index run {}: a repository could not be recorded: {}", runId, masked(e.getCause()));
            }
        }
    }
```
- **`indexSafely(runId, id, force)` stays** for `IndexRunExecutorTest`. It still calls `indexer.index(runId, id, force)`.
- **Imports:** `com.graphify.maven.Gav`, `MavenModule`, `ArtifactInstaller`, `java.util.HashMap`, `LinkedHashMap`, `LinkedHashSet`, `Map`, `java.util.stream.Collectors`.
- **Providers inside the run** keep their install in `provided.installs()`. `completeSafely` passes it to `indexer.complete`, which writes it with the repository's own row.
- **A provider whose own index row is SKIPPED_UNCHANGED** still shows its UP_TO_DATE or INSTALLED install. This is how `acme-lib` appears in the second-run test.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -q test -Dfrontend.skip=true -Dtest='WorkspaceArtifactsTest,IndexRunExecutorTest,IndexRunServiceTest,IndexRunServiceFailureTest,RepositoryIndexerTest,RepositoryIndexerInterruptionTest,IndexRunApiTest'`
Expected: PASS. In `IndexRunExecutorTest`, `shop-lib` is now a provider (Ruling 3); its existing assertions do not look at installs. Then run the full backend suite.

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/graphify/indexing/ArtifactInstallStatus.java src/main/java/com/graphify/indexing/ArtifactInstallOutcome.java \
  src/main/java/com/graphify/indexing/PreparedRepository.java src/main/java/com/graphify/indexing/Preparation.java \
  src/main/java/com/graphify/indexing/ModuleCoordinates.java src/test/java/com/graphify/testsupport/AcmeScm.java \
  src/test/java/com/graphify/indexing/WorkspaceArtifactsTest.java
git commit src/main/java/com/graphify/indexing/ArtifactInstallStatus.java src/main/java/com/graphify/indexing/ArtifactInstallOutcome.java \
  src/main/java/com/graphify/indexing/PreparedRepository.java src/main/java/com/graphify/indexing/Preparation.java \
  src/main/java/com/graphify/indexing/ModuleCoordinates.java src/main/java/com/graphify/indexing/RepositoryIndexer.java \
  src/main/java/com/graphify/indexing/IndexRunExecutor.java src/main/java/com/graphify/indexing/IndexRunRecorder.java \
  src/test/java/com/graphify/testsupport/AcmeScm.java src/test/java/com/graphify/indexing/WorkspaceArtifactsTest.java \
  src/test/java/com/graphify/indexing/IndexRunServiceFailureTest.java src/test/java/com/graphify/indexing/RepositoryIndexerTest.java \
  -m "feat(indexing): install provider repositories before indexing their consumers" \
  -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01XpYgYKeBYFNw4tb6EwJ44V"
```

---

### Task 6: API, run detail badges and README

**Files:**
- Modify:
  - backend: `indexing/IndexRunRepoView.java`, `indexing/IndexRunQueries.java`;
  - API: `frontend/openapi.json` and `frontend/src/api/schema.d.ts` (generated);
  - frontend: `frontend/src/features/runs/RunRepositoriesTable.tsx`, `frontend/src/components/Badges.tsx`, `frontend/src/i18n/tr.ts`;
  - docs: `README.md`.
- Test: `indexing/IndexRunApiTest.java`, `frontend/src/features/runs/runs.test.tsx`

**Interfaces:**
- **Consumes:** Task 5's columns and recorder.
- **Produces:**
  - `IndexRunRepoView` gains `String artifactInstall`, `String artifactInstallError` after `finishedAt`.
  - Frontend: `ArtifactInstallBadge`, `tr.enums.artifactInstall`, `tr.repositories.runColumns.artifact`, `tr.runs.installOutput`.

- [ ] **Step 1: Write the failing tests**

`IndexRunApiTest`: next to the existing `runs.record(finished, alpha, …)` calls, record one with an install:
```java
        runs.record(finished, gamma, new RepoIndexOutcome(RepoIndexStatus.SUCCESS, "def", "FULL", null, 1, 1, 0, 10),
                new ArtifactInstallOutcome(ArtifactInstallStatus.FAILED, "Maven exited with 1:\n[ERROR] boom"));
```
- `gamma` is a third repository created the way `alpha`/`beta` are; follow the file's existing helper.
- Then assert on the run detail JSON:
```java
                .extractingPath("$.repositories[?(@.repository.slug == 'gamma')].artifactInstall").asArray()
                .containsExactly("FAILED");
```
- Also assert that `alpha`'s `artifactInstall` is absent or null, following the file's JSON assertion style.

`runs.test.tsx`: add a test for the run page.
```tsx
  it('shows each repository artifact install with its output', async () => {
    signedIn();
    server.use(http.get(apiUrl('/api/v1/index/runs/7'), () => HttpResponse.json({
      run: { id: 7, trigger: 'MANUAL', scope: 'ALL', status: 'SUCCESS', startedBy: 'admin', startedAt: '2026-10-08T10:00:00Z', cancelRequested: false },
      byStatus: { SUCCESS: 2 },
      inProgress: [],
      repositories: [
        { runId: 7, repository: { id: 1, projectKey: 'ACME', slug: 'acme-lib' }, status: 'SUCCESS', artifactInstall: 'INSTALLED', symbolCount: 1, usageCount: 1, warningCount: 0, durationMs: 1000 },
        { runId: 7, repository: { id: 2, projectKey: 'ACME', slug: 'acme-bad' }, status: 'SUCCESS', artifactInstall: 'FAILED', artifactInstallError: 'Maven exited with 1:\n[ERROR] COMPILATION ERROR', symbolCount: 1, usageCount: 1, warningCount: 0, durationMs: 1000 },
      ],
    })));
    renderApp('/runs/7');

    expect(await screen.findByText(tr.enums.artifactInstall.INSTALLED)).toBeInTheDocument();
    expect(screen.getByText(tr.enums.artifactInstall.FAILED)).toBeInTheDocument();
    await userEvent.click(screen.getByText(tr.runs.installOutput));
    expect(screen.getByText(/COMPILATION ERROR/)).toBeVisible();
  });
```
Match the file's existing run-detail mock shape (field names of `IndexRunView`) and imports. If its existing run-detail test builds the response through a helper, reuse it.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q test -Dfrontend.skip=true -Dtest=IndexRunApiTest` and `cd frontend && npx vitest run src/features/runs/runs.test.tsx`
Expected: FAIL. The JSON has no `artifactInstall`, and the UI shows no badge.

- [ ] **Step 3: Implement**

`IndexRunRepoView`: add the two fields at the end:
```java
        Instant finishedAt,
        String artifactInstall,
        String artifactInstallError) {
```

`IndexRunQueries`:
- `REPO_RUN` also selects `x.artifact_install, DBMS_LOB.SUBSTR(x.artifact_install_error, 4000, 1) AS artifact_install_error`.
- `repoRun` passes `rs.getString("artifact_install")` and `rs.getString("artifact_install_error")`.
- `DBMS_LOB.SUBSTR` keeps the API response bounded. The column holds at most `index.maven_output_tail_lines` lines, which are masked.

Refresh the contract: `./mvnw test -Dfrontend.skip=true -Dtest=OpenApiSnapshotTest -Dopenapi.update=true`, then `cd frontend && npm run generate:api`.

`tr.ts`:
- **`enums`:** add
  ```ts
      artifactInstall: { INSTALLED: 'Kuruldu', UP_TO_DATE: 'Güncel', FAILED: 'Kurulum başarısız', CYCLE_FAILED: 'Döngüsel dependency' },
  ```
- **`repositories.runColumns`:** add `artifact: 'Artifact kurulumu'`.
- **`runs`:** add `installOutput: 'Kurulum çıktısı'`.

`Badges.tsx`:
```tsx
const ARTIFACT_INSTALL_COLOR: Record<string, string> = {
  INSTALLED: 'green', UP_TO_DATE: 'gray', FAILED: 'red', CYCLE_FAILED: 'orange',
};

/** A provider repository's artifact install in a run; nothing for a repository that provides nothing. */
export function ArtifactInstallBadge({ value }: { value?: string }) {
  if (!value) {
    return null;
  }
  return <Badge variant="light" color={ARTIFACT_INSTALL_COLOR[value] ?? 'gray'}>{enumLabel(tr.enums.artifactInstall, value)}</Badge>;
}
```

`RunRepositoriesTable.tsx`:
- **Header:** after the classpath column add `<Table.Th>{columns.artifact}</Table.Th>`.
- **Cell:** after the classpath cell add
```tsx
            <Table.Td>
              <ArtifactInstallBadge value={row.artifactInstall} />
              {row.artifactInstallError && (
                <details>
                  <summary>{tr.runs.installOutput}</summary>
                  <Code block>{row.artifactInstallError}</Code>
                </details>
              )}
            </Table.Td>
```
- Import `ArtifactInstallBadge`.

`README.md`: after the "### Repo connection types" section, add:
```markdown
### How a scan resolves Maven projects

A scan runs in three phases:

1. **Prepare:** every repository in the run is checked out and its poms are read, in parallel (`index.parallelism`).
2. **Install providers:** a repository that declares a module another scanned repository uses as its parent, an
   imported BOM or a dependency (exact `groupId:artifactId:version`) is a *provider*.
   - Providers are installed into the local Maven repository (`index.maven_local_repository`) with
     `mvn -B -q -fae -Dmaven.test.skip=true install`, in dependency order. Providers in a cycle go last.
   - A provider outside the run, for example when only one repository is scanned, is checked out at its default branch, installed and listed in the run.
   - A provider whose commit was already installed and whose artifacts are still present is "Güncel" (up to date). A provider that fails to install is still indexed; its consumers may stay partial.
3. **Index:** as before. A consumer whose provider was just installed is indexed again even when its own commit is unchanged.

Installing runs the providers' build plugins (tests are skipped) with the same reduced environment as classpath
resolution. Only repositories that provide something to another scanned repository are built.

A repository without a root `pom.xml` is searched for independent Maven projects up to `index.pom_search_depth`
folders deep. `.git`, `target`, `node_modules`, `build` and hidden folders are skipped, and links are not followed.
Each project found gets its own classpath, and a module of a found project is not a project of its own.
```
Also, in the API table, the run detail row (`GET /index/runs/{id}`) mentions that each repository carries `artifactInstall` and `artifactInstallError`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -q test -Dfrontend.skip=true -Dtest='IndexRunApiTest,OpenApiSnapshotTest'`, then `cd frontend && npm test && npm run build`
Expected: PASS. Then run the full backend suite.

- [ ] **Step 5: Commit**
```bash
git commit src/main/java/com/graphify/indexing/IndexRunRepoView.java src/main/java/com/graphify/indexing/IndexRunQueries.java \
  src/test/java/com/graphify/indexing/IndexRunApiTest.java frontend/openapi.json frontend/src/api/schema.d.ts \
  frontend/src/features/runs/RunRepositoriesTable.tsx frontend/src/components/Badges.tsx frontend/src/i18n/tr.ts \
  frontend/src/features/runs/runs.test.tsx README.md \
  -m "feat(runs): show each provider repository's artifact install on the run detail" \
  -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01XpYgYKeBYFNw4tb6EwJ44V"
```

---

## Self-review

**Spec coverage:**

| Spec section | Where it is implemented |
|---|---|
| §1 scope | Tasks 2–5 |
| §2 definitions | Task 3 (`references`: parent, imports, dependencies; same repository excluded) |
| §3.1 prepare | Task 5 `prepare`, with the skip rule only noted |
| §3.2 matching (run + DB) | Tasks 3 and 5 (`ModuleCoordinates`, `unmatched` closure) |
| §3.2 layers and cycles | Task 3 |
| §3.2 install command, roots, timeout, environment, tail | Task 4 (`MavenInvocation`) |
| §3.2 UP_TO_DATE, presence and `last_installed_commit` | Tasks 4 and 5 |
| §3.2 failure handling | Task 5 `installSafely` |
| §3.3 index, skip rule and re-index after install | Task 5 |
| §4 columns, rows for providers outside the run, API, UI | Tasks 1, 5 and 6 |
| §5 nested projects, depth, skipped folders, links, modules not roots, per-root resolution, status rule | Task 2 |
| §6 V13 | Task 1 |
| §7 environment and settings file | Task 4 reuses Plan 7/15's process rules |
| §8 tests | Task 3 covers the cycle; Task 5 covers parent, jar + EXACT, outside the run, up to date, deleted artifact, failing provider and consumer re-index; Tasks 2 and 5 cover nested projects |

**Deviations (in the Rulings):**
- Ruling 1: an out-of-run provider installs its branch head.
- Ruling 6: the setting type is `INT`.
- Ruling 7: the cycle label is "Döngüsel dependency".

**Type consistency:** `Gav`, `MavenModule(… packaging, parent, imports, projectRoot)`, `ArtifactProviders.Plan` and `Provision`, `PreparedRepository`, `Preparation`, `ArtifactInstallOutcome` and `IndexRunRepoView`'s new fields are used with the same names in every task.
