# Plan 8: Repository Graph View Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show one repository as a graph at module, package, class or method level (spec §9). After every index, store the repository's analyses: communities, critical classes, package cycles and entry points. Serve a summary report and a GraphML/JSON export. Also purge the index of repositories a repointed connection no longer lists (plan-7 carry-over).

**Architecture:**
- **Class graph (input to everything).** `ClassGraphLoader` reads a repository's class graph with SQL `GROUP BY` over `usage`. Nodes are the types declared in the repository. Edges are usages aggregated per class pair and usage kind. Usages of types outside the repository are optional and grouped by their source: another repository, or a library artifact.
- **Analyses after each write.** `RepoGraphAnalyzer` is pure Java on JGraphT and computes degrees, transitive dependents, label-propagation communities (seeded) and package strongly connected components. `RepoGraphStore` replaces them in one transaction, and `RepositoryIndexer` triggers this after every successful write. The analyses are derived data: a failure is logged, the index outcome is unchanged, and the report shows the analysis as stale.
- **Graph view.** `GraphBuilder` is pure. It builds the requested level from the class graph (plus a member graph at METHOD level). It rolls up one level at a time while the node count exceeds the limit, and attaches the stored class metrics.
- **REST.** `RepoGraphController` serves `/repositories/{id}/graph`, `/graph/report` and `/graph/export?format=graphml|json`. All limits come from settings.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Oracle with Flyway, JdbcTemplate, **JGraphT 1.5.2 (new dependency, spec §3.1)**, JDK StAX for GraphML, Testcontainers, JUnit 5, AssertJ.

**Spec:** `docs/superpowers/specs/2026-10-05-impact-analyzer-design.md`
- §3.2 step 9 (analyses after indexing)
- §4.1 (`SCM_REPOSITORY.active`: "its data is deleted")
- §6.3 (`graph.max_nodes`, `graph.community_seed`)
- §9 (levels, rollup, external nodes, analyses, tables)
- §10.5 (graph endpoints, USER)
- §11 ("Repo graf" tests: level rollup, `max_nodes` rollup, known cycle detection, deterministic communities with a fixed seed)

**Carried items:**
- From `docs/superpowers/plans/2026-10-11-plan7-followups.md`: repositories left inactive after a repoint, which the new host does not list, keep their graph data in impact results. Task 1 purges them.

**Rulings taken while planning:**
- **Communities use JGraphT `LabelPropagationClustering`, not CWTS Leiden.** Spec §3.1 allows this fallback. JGraphT is needed anyway for the strongly connected components, so this adds one dependency instead of two. The result is deterministic for a fixed `graph.community_seed` and the same graph. Community ids are numbered by size (largest first), then by smallest member name.
- **`repo_graph_analysis` (one row per repository) is added to the §9.3 tables.** It records which commit the stored analyses describe, so the report can say "stale" when the last analysis failed.
- **Rollup when a graph exceeds the limit:**
  - METHOD rolls up to CLASS, focused on the class's package.
  - CLASS rolls up to PACKAGE, keeping the focus.
  - PACKAGE rolls up to MODULE, where the focus is dropped.
  - MODULE is never rolled up.
  - The response carries `requestedLevel`, `level`, `truncated: true` and a `suggestion`.
- **Focus:**
  - At PACKAGE and CLASS level, `focus` is a package prefix (`com.shop` matches `com.shop` and `com.shop.api`). Only edges between nodes inside the focus are shown.
  - At METHOD level, `focus` is a class FQN and is required. The class's members are shown with their callers and callees in the repository.
  - MODULE ignores `focus`.
- **Edge scope:** only usages written in this repository are drawn. Usages of this repository by other repositories are what impact analysis is for.
- **Export** uses the same builder with its own limit `graph.export_max_nodes`.

## Plan series

| Plan | Scope | Status |
|---|---|---|
| 1–7 | Indexer, persistence, search/impact, acquisition, orchestration, authentication, admin APIs | merged |
| **8** | **Repository graph view + inactive-repository purge** (this plan) | — |
| later | React UI; LLM purpose/misuse layer | — |

## Global Constraints

- **Environment.**
  - JDK 25: every command runs with `export JAVA_HOME=/opt/homebrew/opt/openjdk@25`; use `./mvnw`.
  - Docker must be running.
- **Dependencies.** Exactly one new dependency, `org.jgrapht:jgrapht-core:1.5.2`, added in Task 2. No others.
- **"Kodda sabit değer yok" (no hardcoded values in code).**
  - Every limit and tuning value is a setting: `graph.max_nodes`, `graph.community_seed`, and the new `graph.community_max_iterations`, `graph.report_top_n` and `graph.export_max_nodes`.
  - New settings are seeded only in `V8__repo_graph.sql`.
  - Constants in code are protocol or schema facts with a comment: the GraphML namespace, node id prefixes, and the label of the default package.
- **Schema.** New schema only in `V8__repo_graph.sql`; V1–V7 are never edited.
- **Errors.**
  - An unknown repository is 404.
  - At METHOD level, an unknown or foreign focus class is 404 and a missing focus is 400.
  - A bad `level` or `format` is 400.
- **Authorization.** The graph endpoints are USER (`/api/**` already requires USER); nothing new in `SecurityConfiguration`.
- **Determinism.**
  - Nodes are ordered by id and edges by (from, to).
  - Analyses for the same graph and seed are identical between runs.
- **Tests.**
  - Oracle-backed tests extend `OracleIntegrationTest` and use `mvc` or `as(...)`/`anonymous()`.
  - **Never use spring-security-test's `csrf()`.**
  - Tests restore every setting and seeded row they change.
- **Commits** end with the trailer lines the committing agent's harness provides.

## Review Focus

1. **A repository with more classes than the limit.** The view rolls up, `truncated` is true, and the suggestion names the counts; it never returns an unbounded payload. Test: Task 4 `GraphBuilderTest.rollsUpOneLevelAtATimeUntilTheGraphFits`.
2. **Communities are stable for a fixed seed.** The same graph and seed give the same community per class after a re-analysis. Test: Task 3 `RepoGraphServiceTest.analysesAreStoredAndStableForTheSameSeed`.
3. **A repointed connection whose new host lists only some repositories.** The unlisted ones lose their index (and graph). A listing where most disappear purges nothing. Tests: Task 1 `RepositorySyncTest.aRepositoryLeftInactiveByARepointIsPurgedWhenTheNewHostDoesNotListIt` and `aMassDisappearanceAfterARepointPurgesNothing`.
4. **The report after an index the analysis did not follow.** The report says `stale: true`. Test: Task 5 `RepoGraphApiTest.theReportSummarisesTheAnalysesAndFlagsAStaleOne`.
5. **METHOD level with a missing, unknown or foreign class.** The answer is 400 or 404, never a 500 or an empty 200. Test: Task 4 `RepoGraphApiTest.rejectsBadLevelsFocusesAndRepositories`.

---

## File Structure

| File | Responsibility |
|---|---|
| `pom.xml` | JGraphT dependency |
| `db/migration/V8__repo_graph.sql` | Graph tables, graph settings |
| `scm/RepositorySync.java` (modify) | Purge the index of inactive repositories the host no longer lists |
| `store/RepositoryIndexWriter.java` (modify) | `remove` also clears graph rows |
| `repograph/ClassGraph.java`, `ClassNode.java`, `ClassEdge.java`, `ExternalEdge.java`, `ExternalGroup.java`, `NodeType.java`, `ClassMetrics.java` | Class graph model |
| `repograph/MemberRef.java`, `MemberUse.java`, `MemberGraph.java` | Member graph model (METHOD level) |
| `repograph/ClassGraphLoader.java` | SQL reads of the class and member graphs |
| `repograph/RepoGraphAnalyzer.java` | Degrees, dependents, communities, package cycles |
| `repograph/RepoGraphStore.java`, `AnalysisState.java`, `CriticalClass.java`, `CommunitySummary.java`, `PackageCycle.java` | Stored analyses: write and read |
| `repograph/RepoGraphService.java`; `indexing/RepositoryIndexer.java` (modify) | Analyze after each write; serve the view, report and export |
| `repograph/GraphLevel.java`, `GraphNode.java`, `GraphEdge.java`, `RepoGraph.java`, `GraphBuilder.java` | Level view and rollup |
| `repograph/RepoGraphReport.java`, `GraphMl.java`, `RepoGraphController.java` | REST, report, export |
| test `fixtures/graph-repo/**`, `testsupport/GraphFixture.java`, `repograph/SampleGraphs.java` | Fixtures |
| `README.md` (modify) | Endpoints |

Main paths are under `src/main/java/com/graphify/` unless they start with `db/` (`src/main/resources/db/migration/`) or `pom.xml`.

---

### Task 1: Purge inactive repositories the new host does not list (plan-7 carry-over)

**Files:**
- Modify: `src/main/java/com/graphify/scm/RepositorySync.java`, `README.md`
- Test: `src/test/java/com/graphify/scm/RepositorySyncTest.java`

**Interfaces:**
- **Consumes:**
  - `RepositorySync.sync(ScmConnection)`, which currently:
    - counts `activeBefore`, lists, inserts or reactivates the listed repositories (a reactivation rewrites `clone_url`);
    - computes `gone` (active and not listed) and applies the `scm.max_deactivation_percent` guard;
    - calls `writer.remove(id)` and sets `active = 0`.
  - `RepositoryIndexWriter.remove(long)`, which deletes the index rows and sets `last_indexed_commit = NULL`.
  - `SyncResult(listed, added, reactivated, deactivated, deactivationsSkipped)` with a 4-argument convenience constructor.
  - A repoint (plan 7) sets `active = 0` on all of a connection's repositories and keeps their index.
- **Produces:**
  - `SyncResult` gains two components: `SyncResult(int listed, int added, int reactivated, int deactivated, int deactivationsSkipped, int purged, int purgesSkipped)`. It keeps the 4-argument and 5-argument constructors, which pass 0 for the new components. Existing tests and `IndexRunExecutor` compile unchanged.
  - `sync` counts `indexedInactiveBefore` (`active = 0 AND last_indexed_commit IS NOT NULL`) before listing.
  - After the listing loop and before the deactivation guard, the stale repositories are those still inactive with an index and not listed:
    - **Guard:** if `stale.size() > 1` and `stale * 100 > indexedInactiveBefore * scm.max_deactivation_percent`, nothing is purged, a WARN is logged, and `purgesSkipped = stale.size()`.
    - **Otherwise:** `writer.remove(id)` runs for each, and `purged = stale.size()`.
  - The early return of the deactivation guard also carries `purged`/`purgesSkipped`.

- [ ] **Step 1: Write the failing tests**

Append to `RepositorySyncTest` (inside the class):

```java
    private void indexAllThenRepoint() {
        // what an index followed by ScmConnectionAdministration's repoint leaves behind
        jdbc.update("UPDATE scm_repository SET last_indexed_commit = 'c1', active = 0");
        jdbc.update("INSERT INTO maven_module (repo_id, path, classpath_mode) SELECT id, '.', 'FULL' FROM scm_repository");
    }

    private List<String> indexedSlugs() {
        return jdbc.queryForList("SELECT r.slug FROM maven_module m JOIN scm_repository r ON r.id = m.repo_id "
                + "ORDER BY r.slug", String.class);
    }

    @Test
    void aRepositoryLeftInactiveByARepointIsPurgedWhenTheNewHostDoesNotListIt() {
        bitbucket.addRepository("SHOP", "api", "https://scm/a.git").addRepository("SHOP", "lib", "https://scm/l.git")
                .addRepository("SHOP", "web", "https://scm/w.git");
        sync.sync(connection);
        indexAllThenRepoint();
        bitbucket.removeRepository("SHOP", "web");

        assertThat(sync.sync(connection)).isEqualTo(new RepositorySync.SyncResult(2, 0, 2, 0, 0, 1, 0));
        assertThat(rows()).containsExactly("SHOP/api:1:https://scm/a.git", "SHOP/lib:1:https://scm/l.git",
                "SHOP/web:0:https://scm/w.git");
        assertThat(indexedSlugs()).containsExactly("api", "lib");
        assertThat(jdbc.queryForObject("SELECT last_indexed_commit FROM scm_repository WHERE slug = 'web'",
                String.class)).isNull();

        assertThat(sync.sync(connection)).isEqualTo(new RepositorySync.SyncResult(2, 0, 0, 0));
    }

    @Test
    void aMassDisappearanceAfterARepointPurgesNothing() {
        bitbucket.addRepository("SHOP", "api", "https://scm/a.git").addRepository("SHOP", "lib", "https://scm/l.git")
                .addRepository("SHOP", "web", "https://scm/w.git");
        sync.sync(connection);
        indexAllThenRepoint();
        bitbucket.removeRepository("SHOP", "api").removeRepository("SHOP", "lib").removeRepository("SHOP", "web");

        assertThat(sync.sync(connection)).isEqualTo(new RepositorySync.SyncResult(0, 0, 0, 0, 0, 0, 3));
        assertThat(indexedSlugs()).containsExactly("api", "lib", "web");
    }
```

If `FakeBitbucket.removeRepository` does not return the fake for chaining, call it three times on separate lines.

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -Dtest=RepositorySyncTest`
Expected: compile error (7-argument `SyncResult`).

- [ ] **Step 3: Implement**

In `RepositorySync.java`:

1. Replace the `SyncResult` record with:

```java
    /**
     * {@code deactivationsSkipped} counts repositories left active because too many disappeared at once;
     * {@code purged}/{@code purgesSkipped} count inactive repositories (a repointed connection) whose index was
     * deleted, or kept by the same guard, because the host no longer lists them.
     */
    public record SyncResult(int listed, int added, int reactivated, int deactivated, int deactivationsSkipped,
            int purged, int purgesSkipped) {

        public SyncResult(int listed, int added, int reactivated, int deactivated, int deactivationsSkipped) {
            this(listed, added, reactivated, deactivated, deactivationsSkipped, 0, 0);
        }

        public SyncResult(int listed, int added, int reactivated, int deactivated) {
            this(listed, added, reactivated, deactivated, 0);
        }
    }

    private record Purge(int purged, int skipped) {
    }
```

2. In `sync`:
   - Next to `activeBefore`, add:

     ```java
     int indexedInactiveBefore = jdbc.queryForObject("SELECT COUNT(*) FROM scm_repository WHERE connection_id = ? "
             + "AND active = 0 AND last_indexed_commit IS NOT NULL", Integer.class, connection.id());
     ```

   - Move `int maxPercent = settings.getInt(SettingKeys.SCM_MAX_DEACTIVATION_PERCENT);` up to just after the listing loop.
   - Directly after the loop, add `Purge purge = purgeUnlisted(connection, seen, indexedInactiveBefore, maxPercent);`.
   - Return `new SyncResult(listed.size(), added, reactivated, 0, gone.size(), purge.purged(), purge.skipped())` from the guard branch.
   - Return `new SyncResult(listed.size(), added, reactivated, gone.size(), 0, purge.purged(), purge.skipped())` at the end.

3. Add:

```java
    /**
     * A repository left inactive with its index (its connection was repointed) that the host does not list is gone
     * from the new host: its index is deleted (spec §4.1), under the same guard as deactivation.
     */
    private Purge purgeUnlisted(ScmConnection connection, Set<String> seen, int indexedInactiveBefore, int maxPercent) {
        List<Long> stale = jdbc.query("SELECT id, project_key, slug FROM scm_repository WHERE connection_id = ? "
                        + "AND active = 0 AND last_indexed_commit IS NOT NULL ORDER BY id",
                        (rs, row) -> seen.contains(rs.getString("project_key") + "/" + rs.getString("slug"))
                                ? null : rs.getLong("id"),
                        connection.id())
                .stream().filter(Objects::nonNull).toList();
        if (stale.size() > 1 && (long) stale.size() * 100 > (long) indexedInactiveBefore * maxPercent) {
            log.warn("Connection '{}': {} of {} inactive indexed repositories are not listed; their index is kept "
                    + "(scm.max_deactivation_percent = {})", connection.name(), stale.size(), indexedInactiveBefore,
                    maxPercent);
            return new Purge(0, stale.size());
        }
        stale.forEach(writer::remove);
        if (!stale.isEmpty()) {
            log.info("Connection '{}': deleted the index of {} inactive repositories no longer listed",
                    connection.name(), stale.size());
        }
        return new Purge(stale.size(), 0);
    }
```

Import `java.util.Objects`, and replace the existing `java.util.Objects::nonNull` with `Objects::nonNull`.

In `README.md`, extend the `GET, PUT, DELETE /admin/scm-connections/{id}` row: "…until the next sync, which reactivates the repositories the new host lists and deletes the index of the others (same `scm.max_deactivation_percent` guard)".

- [ ] **Step 4: Run the tests**

Run: `./mvnw test -Dtest='RepositorySyncTest,IndexRunExecutorTest,ScmConnectionAdminApiTest'`
Expected: all pass.

Run: `./mvnw test`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main src/test README.md
git commit -m "fix(scm): delete the index of inactive repositories a repointed host no longer lists" -m "<your harness trailer lines>"
```

---

### Task 2: Graph schema, class graph model, loader and analyzer

**Files:**
- Modify: `pom.xml`, `src/main/java/com/graphify/settings/SettingKeys.java`, `src/main/java/com/graphify/store/RepositoryIndexWriter.java`, `src/test/java/com/graphify/store/StoreFixtures.java`
- Create: `src/main/resources/db/migration/V8__repo_graph.sql`, `src/main/java/com/graphify/repograph/{ClassGraph,ClassNode,ClassEdge,ExternalEdge,ExternalGroup,NodeType,ClassMetrics,MemberRef,MemberUse,MemberGraph,ClassGraphLoader,RepoGraphAnalyzer}.java`
- Create (test): `src/test/resources/fixtures/graph-repo/**` (7 Java files), `src/test/java/com/graphify/testsupport/GraphFixture.java`, `src/test/java/com/graphify/repograph/SampleGraphs.java`, `RepoGraphAnalyzerTest.java`, `ClassGraphLoaderTest.java`

**Interfaces:**
- **Consumes:**
  - Tables `symbol`, `symbol_declaration`, `usage`, `maven_module` and `scm_repository` (see V1). `symbol.class_fqn` is the owning type's key, and a type symbol's `symbol_key` equals its `class_fqn`.
  - `UsageKind`, `SymbolKind` (`com.graphify.indexer.model`).
  - `JavaRepositoryIndexer`, `IndexRequest`, `ModuleSource`, `IndexerOptions`, `RepositoryIndexWriter.replace`, `RepositoryIndex`, `ModuleRecord(path, groupId, artifactId, version, ClasspathMode)` and `StoreFixtures.newRepository` (as `ShopFixture` uses them).
- **Produces (schema and settings):**
  - V8 tables `repo_graph_analysis`, `repo_graph_community`, `repo_graph_metric` and `repo_graph_cycle` (the DDL below). Their symbol foreign keys are `ON DELETE CASCADE`.
  - Settings `graph.community_max_iterations` (100), `graph.report_top_n` (20) and `graph.export_max_nodes` (20000), with constants `GRAPH_COMMUNITY_MAX_ITERATIONS`, `GRAPH_REPORT_TOP_N` and `GRAPH_EXPORT_MAX_NODES`.
  - `RepositoryIndexWriter.remove` also deletes the repository's graph rows. `StoreFixtures.cleanIndexTables` deletes the graph tables first.
- **Produces (model records, all public in `com.graphify.repograph`):**
  - `ClassNode(long symbolId, String fqn, String packageName, String modulePath)`
  - `ClassEdge(String fromFqn, String toFqn, UsageKind kind, long weight)`
  - `ExternalGroup(String key, String label, NodeType type)`
  - `ExternalEdge(String fromFqn, ExternalGroup group, String toFqn, UsageKind kind, long weight)`
  - `ClassGraph(long repositoryId, Map<String, ClassNode> classes, List<ClassEdge> edges, List<ExternalEdge> external)`. Classes are kept sorted by FQN. It has `static String packageOf(String fqn)` and `Map<String, ExternalGroup> externalGroups()` (external class FQN to its group).
  - `enum NodeType { MODULE, PACKAGE, CLASS, METHOD, CONSTRUCTOR, FIELD, EXTERNAL_REPOSITORY, EXTERNAL_LIBRARY }`
  - `ClassMetrics(int inDegree, int outDegree, int dependents, boolean entryPoint, Integer communityId, String communityLabel)`
  - `MemberRef(long symbolId, String key, String classFqn, SymbolKind kind, String signature)`, `MemberUse(MemberRef from, MemberRef to, UsageKind kind, long weight)`, `MemberGraph(List<MemberRef> focusMembers, List<MemberUse> uses)`
- **Produces (`ClassGraphLoader`, a `@Repository`):**
  - `ClassGraph load(long repositoryId, boolean includeExternal)`:
    - Edges come only from usages in this repository's modules.
    - Self edges (same class) are dropped.
    - An external target goes to `repo:<PROJECT>/<slug>` when another repository declares its class (the first by project and slug). Otherwise it goes to `lib:<artifact>` when the symbol has an artifact, and to `lib:other` if it has none.
  - `MemberGraph members(long repositoryId, String classFqn)`: the class's methods, constructors and fields, plus every usage in this repository from or to the class, aggregated per (from, to, kind).
- **Produces (`RepoGraphAnalyzer`, final, static API):**
  - `Analysis analyze(ClassGraph graph, Set<String> entryClasses, long seed, int maxIterations)`, with `record Analysis(Map<String, ClassMetrics> metrics, List<List<String>> cycles)`.
  - Metrics are per class FQN:
    - in/out degree counts distinct other classes;
    - `dependents` is the number of repository classes that reach this class transitively, itself excluded;
    - `entryPoint` comes from `entryClasses`.
  - Communities:
    - label propagation on the undirected class graph with `new Random(seed)`;
    - ids from 1, ordered by size descending, then by smallest member FQN;
    - the label is the most common package among the members, ties broken by the smallest name, `(default package)` for "".
  - Cycles: strongly connected components of size > 1 in the package graph. Each is sorted, and the list is sorted by first package.

- [ ] **Step 1: Add the fixture repository**

`src/test/resources/fixtures/graph-repo/core/src/main/java/com/g/a/Alpha.java`:

```java
package com.g.a;

import com.g.b.Beta;

public class Alpha {

    private final Beta beta = new Beta();

    public int run() {
        return beta.value() + AlphaHelper.one();
    }
}
```

`…/core/src/main/java/com/g/a/AlphaHelper.java`:

```java
package com.g.a;

public final class AlphaHelper {

    private AlphaHelper() {
    }

    public static int one() {
        return 1;
    }
}
```

`…/core/src/main/java/com/g/b/Beta.java`:

```java
package com.g.b;

import com.g.a.AlphaHelper;

public class Beta {

    public int value() {
        return AlphaHelper.one() + 1;
    }
}
```

`…/app/src/main/java/com/g/c/Gamma.java`:

```java
package com.g.c;

import com.g.a.Alpha;

public class Gamma {

    public int total() {
        return new Alpha().run() + new Delta().size();
    }
}
```

`…/app/src/main/java/com/g/c/Delta.java`:

```java
package com.g.c;

public class Delta {

    public int size() {
        return new Gamma() == null ? 0 : 2;
    }
}
```

`…/app/src/main/java/com/g/web/Endpoint.java`:

```java
package com.g.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Endpoint {
}
```

`…/app/src/main/java/com/g/web/Api.java`:

```java
package com.g.web;

import com.g.c.Gamma;

public class Api {

    @Endpoint
    public int handle() {
        return new Gamma().total();
    }
}
```

The facts the tests rely on are classes (7), packages (4) and modules (core, app). The class edges are:
- Alpha→Beta, Alpha→AlphaHelper, Beta→AlphaHelper
- Gamma→Alpha, Gamma→Delta, Delta→Gamma
- Api→Endpoint, Api→Gamma

They form one package cycle: `com.g.a`↔`com.g.b`.

`src/test/java/com/graphify/testsupport/GraphFixture.java`:

```java
package com.graphify.testsupport;

import com.graphify.indexer.IndexRequest;
import com.graphify.indexer.IndexerOptions;
import com.graphify.indexer.JavaRepositoryIndexer;
import com.graphify.indexer.ModuleSource;
import com.graphify.store.ClasspathMode;
import com.graphify.store.ModuleRecord;
import com.graphify.store.RepositoryIndex;
import com.graphify.store.RepositoryIndexWriter;
import com.graphify.store.StoreFixtures;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Indexes {@code fixtures/graph-repo} into Oracle as one repository with modules {@code core} (packages com.g.a and
 * com.g.b, which depend on each other) and {@code app} (com.g.c and com.g.web, whose {@code Api#handle()} carries
 * {@code @com.g.web.Endpoint}).
 */
public final class GraphFixture {

    public static final String REPO = "graph-repo";
    public static final String COMMIT = "graph-1";
    public static final String ENTRY_ANNOTATION = "com.g.web.Endpoint";

    private GraphFixture() {
    }

    public static long load(JdbcTemplate jdbc, RepositoryIndexWriter writer) {
        StoreFixtures.cleanIndexTables(jdbc);
        Path root = root();
        long repo = StoreFixtures.newRepository(jdbc, REPO);
        Path core = root.resolve("core/src/main/java");
        Path app = root.resolve("app/src/main/java");
        writer.replace(new RepositoryIndex(repo, COMMIT,
                List.of(new ModuleRecord("core", "com.g", "core", "1.0.0", ClasspathMode.FULL),
                        new ModuleRecord("app", "com.g", "app", "1.0.0", ClasspathMode.FULL)),
                new JavaRepositoryIndexer().index(new IndexRequest(root, List.of(
                        new ModuleSource("core", List.of(core), List.of()),
                        new ModuleSource("app", List.of(app), List.of())), new IndexerOptions(50, 300)))));
        return repo;
    }

    private static Path root() {
        try {
            return Path.of(GraphFixture.class.getResource("/fixtures/graph-repo").toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

`src/test/java/com/graphify/repograph/SampleGraphs.java` (an in-memory copy of the fixture for unit tests):

```java
package com.graphify.repograph;

import com.graphify.indexer.model.UsageKind;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The graph-repo fixture as an in-memory class graph (symbol ids 1..7), plus one external library use. */
final class SampleGraphs {

    static final ExternalGroup LIB = new ExternalGroup("lib:com.lib:util:1.0", "com.lib:util:1.0",
            NodeType.EXTERNAL_LIBRARY);

    private SampleGraphs() {
    }

    static ClassGraph sample() {
        Map<String, ClassNode> classes = new LinkedHashMap<>();
        add(classes, 1, "com.g.a.Alpha", "core");
        add(classes, 2, "com.g.a.AlphaHelper", "core");
        add(classes, 3, "com.g.b.Beta", "core");
        add(classes, 4, "com.g.c.Gamma", "app");
        add(classes, 5, "com.g.c.Delta", "app");
        add(classes, 6, "com.g.web.Endpoint", "app");
        add(classes, 7, "com.g.web.Api", "app");
        List<ClassEdge> edges = List.of(
                new ClassEdge("com.g.a.Alpha", "com.g.b.Beta", UsageKind.TYPE_REF, 1),
                new ClassEdge("com.g.a.Alpha", "com.g.b.Beta", UsageKind.INSTANTIATION, 1),
                new ClassEdge("com.g.a.Alpha", "com.g.b.Beta", UsageKind.CALL, 1),
                new ClassEdge("com.g.a.Alpha", "com.g.a.AlphaHelper", UsageKind.CALL, 1),
                new ClassEdge("com.g.b.Beta", "com.g.a.AlphaHelper", UsageKind.CALL, 1),
                new ClassEdge("com.g.c.Gamma", "com.g.a.Alpha", UsageKind.INSTANTIATION, 1),
                new ClassEdge("com.g.c.Gamma", "com.g.a.Alpha", UsageKind.CALL, 1),
                new ClassEdge("com.g.c.Gamma", "com.g.c.Delta", UsageKind.CALL, 1),
                new ClassEdge("com.g.c.Delta", "com.g.c.Gamma", UsageKind.INSTANTIATION, 1),
                new ClassEdge("com.g.web.Api", "com.g.web.Endpoint", UsageKind.ANNOTATION, 1),
                new ClassEdge("com.g.web.Api", "com.g.c.Gamma", UsageKind.CALL, 1));
        List<ExternalEdge> external = List.of(
                new ExternalEdge("com.g.a.Alpha", LIB, "com.lib.Strings", UsageKind.CALL, 2),
                new ExternalEdge("com.g.b.Beta", LIB, "com.lib.Numbers", UsageKind.CALL, 1));
        return new ClassGraph(1, classes, edges, external);
    }

    private static void add(Map<String, ClassNode> classes, long id, String fqn, String module) {
        classes.put(fqn, new ClassNode(id, fqn, ClassGraph.packageOf(fqn), module));
    }
}
```

- [ ] **Step 2: Write the failing tests**

`src/test/java/com/graphify/repograph/RepoGraphAnalyzerTest.java`:

```java
package com.graphify.repograph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RepoGraphAnalyzerTest {

    private static RepoGraphAnalyzer.Analysis analyze(long seed) {
        return RepoGraphAnalyzer.analyze(SampleGraphs.sample(), Set.of("com.g.web.Api"), seed, 100);
    }

    @Test
    void computesDegreesTransitiveDependentsAndEntryPoints() {
        Map<String, ClassMetrics> metrics = analyze(42).metrics();

        assertThat(metrics.get("com.g.a.AlphaHelper")).satisfies(m -> {
            assertThat(m.inDegree()).isEqualTo(2);
            assertThat(m.outDegree()).isZero();
            assertThat(m.dependents()).isEqualTo(5);
        });
        assertThat(metrics.get("com.g.b.Beta").dependents()).isEqualTo(4);
        assertThat(metrics.get("com.g.a.Alpha").dependents()).isEqualTo(3);
        assertThat(metrics.get("com.g.c.Gamma").dependents()).isEqualTo(2);
        assertThat(metrics.get("com.g.c.Gamma").inDegree()).isEqualTo(2);
        assertThat(metrics.get("com.g.web.Api").dependents()).isZero();
        assertThat(metrics.get("com.g.web.Api").outDegree()).isEqualTo(2);
        assertThat(metrics).allSatisfy((fqn, m) -> assertThat(m.entryPoint()).isEqualTo(fqn.equals("com.g.web.Api")));
    }

    @Test
    void findsThePackageCycle() {
        assertThat(analyze(42).cycles()).containsExactly(List.of("com.g.a", "com.g.b"));
    }

    @Test
    void communitiesCoverEveryClassAndAreStableForASeed() {
        RepoGraphAnalyzer.Analysis first = analyze(42);

        assertThat(first.metrics()).hasSize(7).allSatisfy((fqn, m) -> {
            assertThat(m.communityId()).isPositive();
            assertThat(m.communityLabel()).isIn("com.g.a", "com.g.b", "com.g.c", "com.g.web");
        });
        List<Integer> ids = first.metrics().values().stream().map(ClassMetrics::communityId).distinct().sorted()
                .toList();
        assertThat(ids.getFirst()).isEqualTo(1);
        assertThat(ids.getLast()).isEqualTo(ids.size());
        assertThat(analyze(42).metrics()).isEqualTo(first.metrics());
    }

    @Test
    void anEmptyRepositoryHasNoAnalyses() {
        RepoGraphAnalyzer.Analysis empty = RepoGraphAnalyzer.analyze(
                new ClassGraph(1, Map.of(), List.of(), List.of()), Set.of(), 42, 100);

        assertThat(empty.metrics()).isEmpty();
        assertThat(empty.cycles()).isEmpty();
    }
}
```

`src/test/java/com/graphify/repograph/ClassGraphLoaderTest.java`:

```java
package com.graphify.repograph;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.indexer.model.UsageKind;
import com.graphify.store.RepositoryIndexWriter;
import com.graphify.testsupport.GraphFixture;
import com.graphify.testsupport.ShopFixture;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

class ClassGraphLoaderTest extends OracleIntegrationTest {

    @Autowired
    ClassGraphLoader loader;

    @Autowired
    RepositoryIndexWriter writer;

    @TempDir
    Path work;

    @Test
    void loadsTheRepositoryClassesAndTheirUsagesBetweenEachOther() {
        long repo = GraphFixture.load(jdbc, writer);

        ClassGraph graph = loader.load(repo, false);

        assertThat(graph.classes().keySet()).containsExactly("com.g.a.Alpha", "com.g.a.AlphaHelper", "com.g.b.Beta",
                "com.g.c.Delta", "com.g.c.Gamma", "com.g.web.Api", "com.g.web.Endpoint");
        assertThat(graph.classes().get("com.g.a.Alpha").modulePath()).isEqualTo("core");
        assertThat(graph.classes().get("com.g.c.Gamma").modulePath()).isEqualTo("app");
        assertThat(graph.edges()).extracting(e -> e.fromFqn() + ">" + e.toFqn()).contains(
                "com.g.a.Alpha>com.g.b.Beta", "com.g.b.Beta>com.g.a.AlphaHelper", "com.g.c.Gamma>com.g.a.Alpha",
                "com.g.c.Delta>com.g.c.Gamma", "com.g.web.Api>com.g.web.Endpoint")
                .noneMatch(pair -> pair.split(">")[0].equals(pair.split(">")[1]));
        assertThat(graph.edges()).anySatisfy(e -> {
            assertThat(e.fromFqn()).isEqualTo("com.g.b.Beta");
            assertThat(e.kind()).isEqualTo(UsageKind.CALL);
        });
        assertThat(graph.external()).isEmpty();
    }

    @Test
    void groupsTypesFromOtherRepositoriesWhenAsked() throws Exception {
        ShopFixture.load(jdbc, writer, work);
        long api = ShopFixture.repositoryId(jdbc, ShopFixture.API_REPO);

        assertThat(loader.load(api, true).external()).extracting(ExternalEdge::group)
                .contains(new ExternalGroup("repo:TEST/shop-lib", "TEST/shop-lib", NodeType.EXTERNAL_REPOSITORY));
        assertThat(loader.load(api, false).external()).isEmpty();
    }

    @Test
    void loadsAClassMembersWithTheirCallersAndCallees() {
        long repo = GraphFixture.load(jdbc, writer);

        MemberGraph members = loader.members(repo, "com.g.a.Alpha");

        assertThat(members.focusMembers()).extracting(MemberRef::key).contains("com.g.a.Alpha#run()");
        assertThat(members.uses()).extracting(u -> u.from().key() + ">" + u.to().key()).contains(
                "com.g.a.Alpha#run()>com.g.a.AlphaHelper#one()", "com.g.c.Gamma#total()>com.g.a.Alpha#run()");
    }
}
```

- [ ] **Step 3: Run them to verify they fail**

Run: `./mvnw test -Dtest='RepoGraphAnalyzerTest,ClassGraphLoaderTest'`
Expected: compile errors (package `com.graphify.repograph` does not exist).

- [ ] **Step 4: Implement**

`pom.xml`, after the `org.eclipse.jdt.core` dependency:

```xml
		<dependency>
			<groupId>org.jgrapht</groupId>
			<artifactId>jgrapht-core</artifactId>
			<version>1.5.2</version>
		</dependency>
```

`src/main/resources/db/migration/V8__repo_graph.sql` (UTF-8):

```sql
-- Plan 8: stored repository graph analyses (spec §9.2, §9.3) and the graph settings they read.

CREATE TABLE repo_graph_analysis (
    repo_id         NUMBER(19)                             NOT NULL,
    commit_hash     VARCHAR2(64 BYTE)                      NOT NULL,
    analyzed_at     TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    class_count     NUMBER(10)                             NOT NULL,
    community_count NUMBER(10)                             NOT NULL,
    cycle_count     NUMBER(10)                             NOT NULL,
    CONSTRAINT pk_repo_graph_analysis PRIMARY KEY (repo_id),
    CONSTRAINT fk_repo_graph_analysis_repo FOREIGN KEY (repo_id) REFERENCES scm_repository (id)
);

CREATE TABLE repo_graph_community (
    repo_id         NUMBER(19)          NOT NULL,
    commit_hash     VARCHAR2(64 BYTE)   NOT NULL,
    symbol_id       NUMBER(19)          NOT NULL,
    community_id    NUMBER(10)          NOT NULL,
    community_label VARCHAR2(2000 BYTE) NOT NULL,
    CONSTRAINT pk_repo_graph_community PRIMARY KEY (repo_id, symbol_id),
    CONSTRAINT fk_repo_graph_community_repo FOREIGN KEY (repo_id) REFERENCES scm_repository (id),
    CONSTRAINT fk_repo_graph_community_symbol FOREIGN KEY (symbol_id) REFERENCES symbol (id) ON DELETE CASCADE
);

CREATE TABLE repo_graph_metric (
    repo_id        NUMBER(19) NOT NULL,
    symbol_id      NUMBER(19) NOT NULL,
    in_degree      NUMBER(10) NOT NULL,
    out_degree     NUMBER(10) NOT NULL,
    dependents     NUMBER(10) NOT NULL,
    is_entry_point NUMBER(1)  NOT NULL,
    CONSTRAINT pk_repo_graph_metric PRIMARY KEY (repo_id, symbol_id),
    CONSTRAINT fk_repo_graph_metric_repo FOREIGN KEY (repo_id) REFERENCES scm_repository (id),
    CONSTRAINT fk_repo_graph_metric_symbol FOREIGN KEY (symbol_id) REFERENCES symbol (id) ON DELETE CASCADE,
    CONSTRAINT ck_repo_graph_metric_entry CHECK (is_entry_point IN (0, 1))
);

CREATE TABLE repo_graph_cycle (
    repo_id      NUMBER(19)          NOT NULL,
    cycle_id     NUMBER(10)          NOT NULL,
    package_name VARCHAR2(2000 BYTE) NOT NULL,
    CONSTRAINT pk_repo_graph_cycle PRIMARY KEY (repo_id, cycle_id, package_name),
    CONSTRAINT fk_repo_graph_cycle_repo FOREIGN KEY (repo_id) REFERENCES scm_repository (id)
);

INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('graph.community_max_iterations', '100', 'INT', 'Topluluk tespitinde azami etiket yayılımı turu', 1, 10000);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('graph.report_top_n', '20', 'INT', 'Graf raporunda listelenen kritik sınıf, topluluk ve giriş noktası sayısı',
     1, 500);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('graph.export_max_nodes', '20000', 'INT', 'Graf dışa aktarımında azami düğüm', 10, 200000);
```

Add to `SettingKeys.java`:

```java
    public static final String GRAPH_COMMUNITY_MAX_ITERATIONS = "graph.community_max_iterations";
    public static final String GRAPH_REPORT_TOP_N = "graph.report_top_n";
    public static final String GRAPH_EXPORT_MAX_NODES = "graph.export_max_nodes";
```

In `RepositoryIndexWriter.remove`, right after `lock(repositoryId);`:

```java
        for (String table : List.of("repo_graph_cycle", "repo_graph_community", "repo_graph_metric",
                "repo_graph_analysis")) {
            jdbc.update("DELETE FROM " + table + " WHERE repo_id = ?", repositoryId);
        }
```

The table names are literal constants, never input.

In `StoreFixtures.cleanIndexTables`, add as the first deletes (after the `index_lock` and `indexing_run_id` updates):

```java
        jdbc.update("DELETE FROM repo_graph_cycle");
        jdbc.update("DELETE FROM repo_graph_community");
        jdbc.update("DELETE FROM repo_graph_metric");
        jdbc.update("DELETE FROM repo_graph_analysis");
```

Model files (package `com.graphify.repograph`). Each gets a one-line Javadoc:

```java
/** A type declared in the repository: a node of the class graph. */
public record ClassNode(long symbolId, String fqn, String packageName, String modulePath) {
}
```

```java
/** Usages written in one repository class of another repository class, for one usage kind. */
public record ClassEdge(String fromFqn, String toFqn, UsageKind kind, long weight) {
}
```

```java
/** Where types outside the repository come from: another repository or a library artifact. */
public record ExternalGroup(String key, String label, NodeType type) {
}
```

```java
/** Usages written in a repository class of a type outside the repository, for one usage kind. */
public record ExternalEdge(String fromFqn, ExternalGroup group, String toFqn, UsageKind kind, long weight) {
}
```

```java
/** Node types of a repository graph view. */
public enum NodeType {
    MODULE, PACKAGE, CLASS, METHOD, CONSTRUCTOR, FIELD, EXTERNAL_REPOSITORY, EXTERNAL_LIBRARY
}
```

```java
/** Stored analyses of one class (spec §9.2); the community is null for a class the last analysis did not see. */
public record ClassMetrics(int inDegree, int outDegree, int dependents, boolean entryPoint, Integer communityId,
        String communityLabel) {
}
```

```java
/** A member (or the class itself, for field initializers) at METHOD level. */
public record MemberRef(long symbolId, String key, String classFqn, SymbolKind kind, String signature) {
}
```

```java
/** Usages from one member to another, for one usage kind. */
public record MemberUse(MemberRef from, MemberRef to, UsageKind kind, long weight) {
}
```

```java
/** A class's members and every usage in the repository from or to the class. */
public record MemberGraph(List<MemberRef> focusMembers, List<MemberUse> uses) {
}
```

`ClassGraph.java`:

```java
package com.graphify.repograph;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** A repository's class graph: its declared types (sorted by FQN), usages between them and, optionally, outside. */
public record ClassGraph(long repositoryId, Map<String, ClassNode> classes, List<ClassEdge> edges,
        List<ExternalEdge> external) {

    public ClassGraph {
        classes = Collections.unmodifiableMap(new TreeMap<>(classes));
        edges = List.copyOf(edges);
        external = List.copyOf(external);
    }

    /** The package of a type key: everything before the last dot ({@code a.b.C$D} is in {@code a.b}). */
    public static String packageOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
    }

    /** External type FQN to the group it is shown in. */
    public Map<String, ExternalGroup> externalGroups() {
        Map<String, ExternalGroup> groups = new HashMap<>();
        for (ExternalEdge edge : external) {
            groups.putIfAbsent(edge.toFqn(), edge.group());
        }
        return groups;
    }
}
```

`ClassGraphLoader.java`:

```java
package com.graphify.repograph;

import com.graphify.indexer.model.SymbolKind;
import com.graphify.indexer.model.UsageKind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads a repository's class and member graphs from the index with GROUP BY over USAGE (spec §9.1). */
@Repository
public class ClassGraphLoader {

    /** Group key prefixes of external nodes. */
    static final String REPOSITORY_GROUP = "repo:";
    static final String LIBRARY_GROUP = "lib:";

    /** The group of external types with no recorded artifact (e.g. the JDK). */
    static final ExternalGroup OTHER_LIBRARIES = new ExternalGroup(LIBRARY_GROUP + "other",
            "Other libraries (no artifact recorded)", NodeType.EXTERNAL_LIBRARY);

    private static final String CLASSES = """
            SELECT s.id, s.symbol_key, m.path
              FROM symbol s
              JOIN symbol_declaration d ON d.symbol_id = s.id
              JOIN maven_module m ON m.id = d.module_id
             WHERE m.repo_id = ? AND s.kind IN ('CLASS', 'INTERFACE', 'ENUM', 'RECORD', 'ANNOTATION_TYPE')
             ORDER BY s.symbol_key, m.path
            """;

    private static final String EDGES = """
            SELECT f.class_fqn AS from_class, t.class_fqn AS to_class, u.kind, COUNT(*) AS weight,
                   MAX(t.artifact) AS artifact
              FROM usage u
              JOIN maven_module m ON m.id = u.module_id
              JOIN symbol f ON f.id = u.from_symbol_id
              JOIN symbol t ON t.id = u.to_symbol_id
             WHERE m.repo_id = ?
             GROUP BY f.class_fqn, t.class_fqn, u.kind
            """;

    private static final String DECLARING_REPOSITORIES = """
            SELECT DISTINCT t.class_fqn, r.project_key, r.slug
              FROM usage u
              JOIN maven_module m ON m.id = u.module_id
              JOIN symbol t ON t.id = u.to_symbol_id
              JOIN symbol c ON c.symbol_key = t.class_fqn
              JOIN symbol_declaration d ON d.symbol_id = c.id
              JOIN maven_module dm ON dm.id = d.module_id
              JOIN scm_repository r ON r.id = dm.repo_id
             WHERE m.repo_id = ? AND dm.repo_id <> ?
             ORDER BY t.class_fqn, r.project_key, r.slug
            """;

    private static final String FOCUS_MEMBERS = """
            SELECT id, symbol_key, class_fqn, kind, display_signature
              FROM symbol
             WHERE class_fqn = ? AND kind IN ('METHOD', 'CONSTRUCTOR', 'FIELD')
             ORDER BY symbol_key
            """;

    private static final String MEMBER_USES = """
            SELECT f.id AS f_id, f.symbol_key AS f_key, f.class_fqn AS f_class, f.kind AS f_kind,
                   f.display_signature AS f_signature,
                   t.id AS t_id, t.symbol_key AS t_key, t.class_fqn AS t_class, t.kind AS t_kind,
                   t.display_signature AS t_signature, u.kind, COUNT(*) AS weight
              FROM usage u
              JOIN maven_module m ON m.id = u.module_id
              JOIN symbol f ON f.id = u.from_symbol_id
              JOIN symbol t ON t.id = u.to_symbol_id
             WHERE m.repo_id = ? AND (f.class_fqn = ? OR t.class_fqn = ?)
             GROUP BY f.id, f.symbol_key, f.class_fqn, f.kind, f.display_signature,
                      t.id, t.symbol_key, t.class_fqn, t.kind, t.display_signature, u.kind
             ORDER BY f.symbol_key, t.symbol_key, u.kind
            """;

    private record RawExternal(String fromFqn, String toFqn, String artifact, UsageKind kind, long weight) {
    }

    private final JdbcTemplate jdbc;

    public ClassGraphLoader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ClassGraph load(long repositoryId, boolean includeExternal) {
        Map<String, ClassNode> classes = new TreeMap<>();
        jdbc.query(CLASSES, rs -> {
            // a type declared in two modules is placed in the first by path
            String fqn = rs.getString("symbol_key");
            classes.putIfAbsent(fqn, new ClassNode(rs.getLong("id"), fqn, ClassGraph.packageOf(fqn),
                    rs.getString("path")));
        }, repositoryId);
        List<ClassEdge> edges = new ArrayList<>();
        List<RawExternal> raw = new ArrayList<>();
        jdbc.query(EDGES, rs -> {
            String from = rs.getString("from_class");
            String to = rs.getString("to_class");
            if (!classes.containsKey(from) || from.equals(to)) {
                return;
            }
            UsageKind kind = UsageKind.valueOf(rs.getString("kind"));
            long weight = rs.getLong("weight");
            if (classes.containsKey(to)) {
                edges.add(new ClassEdge(from, to, kind, weight));
            } else if (includeExternal) {
                raw.add(new RawExternal(from, to, rs.getString("artifact"), kind, weight));
            }
        }, repositoryId);
        edges.sort(Comparator.comparing(ClassEdge::fromFqn).thenComparing(ClassEdge::toFqn)
                .thenComparing(ClassEdge::kind));
        return new ClassGraph(repositoryId, classes, edges, external(repositoryId, raw));
    }

    public MemberGraph members(long repositoryId, String classFqn) {
        List<MemberRef> focus = jdbc.query(FOCUS_MEMBERS, (rs, row) -> new MemberRef(rs.getLong("id"),
                rs.getString("symbol_key"), rs.getString("class_fqn"), SymbolKind.valueOf(rs.getString("kind")),
                rs.getString("display_signature")), classFqn);
        List<MemberUse> uses = jdbc.query(MEMBER_USES, (rs, row) -> new MemberUse(ref(rs, "f_"), ref(rs, "t_"),
                UsageKind.valueOf(rs.getString("kind")), rs.getLong("weight")), repositoryId, classFqn, classFqn);
        return new MemberGraph(focus, uses);
    }

    private List<ExternalEdge> external(long repositoryId, List<RawExternal> raw) {
        if (raw.isEmpty()) {
            return List.of();
        }
        Map<String, String> declaring = new HashMap<>();
        jdbc.query(DECLARING_REPOSITORIES, rs -> {
            declaring.putIfAbsent(rs.getString("class_fqn"), rs.getString("project_key") + "/" + rs.getString("slug"));
        }, repositoryId, repositoryId);
        List<ExternalEdge> external = new ArrayList<>();
        for (RawExternal edge : raw) {
            String repository = declaring.get(edge.toFqn());
            ExternalGroup group = repository != null
                    ? new ExternalGroup(REPOSITORY_GROUP + repository, repository, NodeType.EXTERNAL_REPOSITORY)
                    : edge.artifact() != null
                            ? new ExternalGroup(LIBRARY_GROUP + edge.artifact(), edge.artifact(), NodeType.EXTERNAL_LIBRARY)
                            : OTHER_LIBRARIES;
            external.add(new ExternalEdge(edge.fromFqn(), group, edge.toFqn(), edge.kind(), edge.weight()));
        }
        external.sort(Comparator.comparing(ExternalEdge::fromFqn).thenComparing(ExternalEdge::toFqn)
                .thenComparing(ExternalEdge::kind));
        return external;
    }

    private static MemberRef ref(ResultSet rs, String prefix) throws SQLException {
        return new MemberRef(rs.getLong(prefix + "id"), rs.getString(prefix + "key"), rs.getString(prefix + "class"),
                SymbolKind.valueOf(rs.getString(prefix + "kind")), rs.getString(prefix + "signature"));
    }
}
```

`RepoGraphAnalyzer.java`:

```java
package com.graphify.repograph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jgrapht.Graph;
import org.jgrapht.alg.clustering.LabelPropagationClustering;
import org.jgrapht.alg.connectivity.KosarajuStrongConnectivityInspector;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DefaultUndirectedGraph;

/** The stored repository graph analyses (spec §9.2): degrees, dependents, communities and package cycles. */
public final class RepoGraphAnalyzer {

    /** Label of the unnamed package. */
    static final String DEFAULT_PACKAGE = "(default package)";

    /** Metrics per class FQN, with its community; package cycles, each sorted, ordered by their first package. */
    public record Analysis(Map<String, ClassMetrics> metrics, List<List<String>> cycles) {
    }

    private record Community(int id, String label) {
    }

    private RepoGraphAnalyzer() {
    }

    public static Analysis analyze(ClassGraph graph, Set<String> entryClasses, long seed, int maxIterations) {
        List<String> fqns = new ArrayList<>(graph.classes().keySet());
        Map<String, Set<String>> out = new HashMap<>();
        Map<String, Set<String>> in = new HashMap<>();
        for (String fqn : fqns) {
            out.put(fqn, new TreeSet<>());
            in.put(fqn, new TreeSet<>());
        }
        for (ClassEdge edge : graph.edges()) {
            out.get(edge.fromFqn()).add(edge.toFqn());
            in.get(edge.toFqn()).add(edge.fromFqn());
        }
        Map<String, Community> communities = communities(fqns, out, seed, maxIterations);
        Map<String, ClassMetrics> metrics = new TreeMap<>();
        for (String fqn : fqns) {
            Community community = communities.get(fqn);
            metrics.put(fqn, new ClassMetrics(in.get(fqn).size(), out.get(fqn).size(), dependents(fqn, in),
                    entryClasses.contains(fqn), community.id(), community.label()));
        }
        return new Analysis(metrics, cycles(graph));
    }

    /** Repository classes that reach {@code start} through usages, transitively; {@code start} itself excluded. */
    private static int dependents(String start, Map<String, Set<String>> in) {
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(in.get(start));
        while (!queue.isEmpty()) {
            String next = queue.poll();
            if (seen.add(next)) {
                queue.addAll(in.get(next));
            }
        }
        seen.remove(start);
        return seen.size();
    }

    private static Map<String, Community> communities(List<String> fqns, Map<String, Set<String>> out, long seed,
            int maxIterations) {
        if (fqns.isEmpty()) {
            return Map.of();
        }
        Graph<String, DefaultEdge> undirected = new DefaultUndirectedGraph<>(DefaultEdge.class);
        fqns.forEach(undirected::addVertex);
        for (String from : fqns) {
            for (String to : out.get(from)) {
                if (!undirected.containsEdge(from, to)) {
                    undirected.addEdge(from, to);
                }
            }
        }
        List<List<String>> clusters = new LabelPropagationClustering<>(undirected, maxIterations, new Random(seed))
                .getClustering().getClusters().stream()
                .map(cluster -> cluster.stream().sorted().toList())
                .sorted(Comparator.comparingInt((List<String> cluster) -> -cluster.size())
                        .thenComparing(cluster -> cluster.getFirst()))
                .toList();
        Map<String, Community> byClass = new HashMap<>();
        for (int i = 0; i < clusters.size(); i++) {
            Community community = new Community(i + 1, dominantPackage(clusters.get(i)));
            clusters.get(i).forEach(fqn -> byClass.put(fqn, community));
        }
        return byClass;
    }

    private static String dominantPackage(List<String> members) {
        Map<String, Integer> counts = new TreeMap<>();
        members.forEach(fqn -> counts.merge(ClassGraph.packageOf(fqn), 1, Integer::sum));
        String best = null;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (best == null || entry.getValue() > counts.get(best)) {
                best = entry.getKey();
            }
        }
        return best.isEmpty() ? DEFAULT_PACKAGE : best;
    }

    private static List<List<String>> cycles(ClassGraph graph) {
        Graph<String, DefaultEdge> packages = new DefaultDirectedGraph<>(DefaultEdge.class);
        graph.classes().values().forEach(node -> packages.addVertex(node.packageName()));
        for (ClassEdge edge : graph.edges()) {
            String from = ClassGraph.packageOf(edge.fromFqn());
            String to = ClassGraph.packageOf(edge.toFqn());
            if (!from.equals(to) && !packages.containsEdge(from, to)) {
                packages.addEdge(from, to);
            }
        }
        return new KosarajuStrongConnectivityInspector<>(packages).stronglyConnectedSets().stream()
                .filter(component -> component.size() > 1)
                .map(component -> component.stream().sorted().toList())
                .sorted(Comparator.comparing((List<String> cycle) -> cycle.getFirst()))
                .toList();
    }
}
```

`TreeMap` iteration plus a strict `>` gives the smallest name on ties.

- [ ] **Step 5: Run the tests**

Run: `./mvnw test -Dtest='RepoGraphAnalyzerTest,ClassGraphLoaderTest,SchemaMigrationTest,RepositoryIndexWriterTest,RepositorySyncTest'`
Expected: all pass.

**If a fixture fact differs from the real index:** for example, the JDT index adds a class edge not listed above. Adapt the loader test's `contains` lists to what the index really holds. Keep the analyzer unit test on `SampleGraphs`. Report what differed.

Run: `./mvnw test`
Expected: all tests pass.

- [ ] **Step 6: Commit**

```bash
git add pom.xml src/main src/test
git commit -m "feat(repograph): load the class graph and compute repository graph analyses" -m "<your harness trailer lines>"
```

---

### Task 3: Store the analyses after every index

**Files:**
- Create: `src/main/java/com/graphify/repograph/{RepoGraphStore,AnalysisState,CriticalClass,CommunitySummary,PackageCycle,RepoGraphService}.java`
- Modify: `src/main/java/com/graphify/indexing/RepositoryIndexer.java`
- Test: `src/test/java/com/graphify/repograph/RepoGraphServiceTest.java`; `src/test/java/com/graphify/indexing/RepositoryIndexerTest.java`

**Interfaces:**
- **Consumes:** Task 2 (`ClassGraphLoader`, `RepoGraphAnalyzer`, settings keys, V8 tables), `AppSettings`, `SettingKeys.STORE_JDBC_BATCH_SIZE`, `Chunks.of(List, int)`, `RepositoryIndexer.write(RepositoryIndex)`, `RepositoryIndexer.rethrowIfInterrupted`, `UrlMasking.mask`.
- **Produces (records, public):**
  - `AnalysisState(String commit, Instant analyzedAt, int classCount, int communityCount, int cycleCount)`
  - `CriticalClass(long symbolId, String fqn, int inDegree, int outDegree, int dependents, boolean entryPoint)`
  - `CommunitySummary(int id, String label, int size)`
  - `PackageCycle(int id, List<String> packages)`
- **Produces (`RepoGraphStore`, a `@Repository`):**
  - `@Transactional void replace(long repositoryId, String commit, ClassGraph graph, RepoGraphAnalyzer.Analysis analysis, int batchSize)` and `void clear(long repositoryId)`.
  - Reads:
    - `Optional<AnalysisState> state(long)`
    - `Map<Long, ClassMetrics> metrics(long)`, keyed by class symbol id
    - `List<CriticalClass> critical(long, int limit)`: `dependents` desc, `in_degree` desc, FQN
    - `List<CommunitySummary> communities(long, int limit)`, by id
    - `List<PackageCycle> cycles(long)`
    - `int entryPointCount(long)`, `List<String> entryPointClasses(long, int limit)`
- **Produces (`RepoGraphService`, a `@Service`):** `void analyze(long repositoryId, String commit)`.
  - It loads the class graph without externals.
  - Entry classes are classes with an `ANNOTATION` usage, written in this repository, whose target key is an enabled `entry_point_annotation` (subquery, no IN-list).
  - It analyzes with `graph.community_seed` and `graph.community_max_iterations`, then stores.
- **Produces (`RepositoryIndexer`):**
  - The constructor gains `RepoGraphService graphs`.
  - `write(...)` calls `analyzeGraph(repositoryId, commit)` after a successful `writer.replace`, so it runs for both the indexed and the `SKIPPED_NOT_JAVA` branches.
  - `analyzeGraph` rethrows interruptions. It logs any other failure at WARN (masked) and leaves the outcome unchanged.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/graphify/repograph/RepoGraphServiceTest.java`:

```java
package com.graphify.repograph;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.store.RepositoryIndexWriter;
import com.graphify.testsupport.GraphFixture;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RepoGraphServiceTest extends OracleIntegrationTest {

    @Autowired
    RepoGraphService graphs;

    @Autowired
    RepoGraphStore store;

    @Autowired
    RepositoryIndexWriter writer;

    private long repo;

    @BeforeEach
    void setUp() {
        repo = GraphFixture.load(jdbc, writer);
        jdbc.update("INSERT INTO entry_point_annotation (annotation_fqn, label, enabled) VALUES (?, 'Test endpoint', 1)",
                GraphFixture.ENTRY_ANNOTATION);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM entry_point_annotation WHERE annotation_fqn = ?", GraphFixture.ENTRY_ANNOTATION);
    }

    private Map<String, Integer> communityBySymbolKey() {
        Map<String, Integer> communities = new java.util.TreeMap<>();
        jdbc.query("SELECT s.symbol_key, c.community_id FROM repo_graph_community c JOIN symbol s ON s.id = c.symbol_id "
                + "WHERE c.repo_id = ?", rs -> {
                    communities.put(rs.getString(1), rs.getInt(2));
                }, repo);
        return communities;
    }

    @Test
    void analysesAreStoredAndStableForTheSameSeed() {
        graphs.analyze(repo, GraphFixture.COMMIT);

        assertThat(store.state(repo)).hasValueSatisfying(state -> {
            assertThat(state.commit()).isEqualTo(GraphFixture.COMMIT);
            assertThat(state.classCount()).isEqualTo(7);
            assertThat(state.cycleCount()).isEqualTo(1);
        });
        assertThat(store.critical(repo, 2)).extracting(CriticalClass::fqn, CriticalClass::dependents)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("com.g.a.AlphaHelper", 5),
                        org.assertj.core.groups.Tuple.tuple("com.g.b.Beta", 4));
        assertThat(store.cycles(repo)).containsExactly(new PackageCycle(1, List.of("com.g.a", "com.g.b")));
        assertThat(store.entryPointClasses(repo, 10)).containsExactly("com.g.web.Api");
        assertThat(store.entryPointCount(repo)).isEqualTo(1);
        Map<String, Integer> first = communityBySymbolKey();
        assertThat(first).hasSize(7);

        graphs.analyze(repo, GraphFixture.COMMIT);

        assertThat(communityBySymbolKey()).isEqualTo(first);
        assertThat(store.metrics(repo)).hasSize(7);
    }

    @Test
    void removingTheIndexRemovesTheAnalyses() {
        graphs.analyze(repo, GraphFixture.COMMIT);

        writer.remove(repo);

        assertThat(store.state(repo)).isEmpty();
        assertThat(store.metrics(repo)).isEmpty();
        assertThat(store.cycles(repo)).isEmpty();
    }
}
```

Use real imports for `TreeMap` and `Tuple` (`org.assertj.core.groups.Tuple.tuple` as a static import) instead of the qualified names.

In `RepositoryIndexerTest.indexesRepositoriesEndToEndAndImpactFindsTheCrossRepositoryCaller`, add at the end:

```java
        // every indexed repository has its graph analysis for the commit it was indexed at
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scm_repository r JOIN repo_graph_analysis a "
                + "ON a.repo_id = r.id AND a.commit_hash = r.last_indexed_commit", Integer.class))
                .isEqualTo(jdbc.queryForObject("SELECT COUNT(*) FROM scm_repository WHERE last_indexed_commit IS NOT NULL",
                        Integer.class)).isPositive();
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -Dtest='RepoGraphServiceTest,RepositoryIndexerTest'`
Expected: compile errors (`RepoGraphService`, `RepoGraphStore`).

- [ ] **Step 3: Implement**

Records (package `com.graphify.repograph`, one-line Javadocs):

```java
/** Which commit the stored analyses describe. */
public record AnalysisState(String commit, Instant analyzedAt, int classCount, int communityCount, int cycleCount) {
}
```

```java
/** A class ranked by how much of the repository depends on it (spec §9.2 "Kritik sınıflar"). */
public record CriticalClass(long symbolId, String fqn, int inDegree, int outDegree, int dependents,
        boolean entryPoint) {
}
```

```java
public record CommunitySummary(int id, String label, int size) {
}
```

```java
public record PackageCycle(int id, List<String> packages) {
}
```

`RepoGraphStore.java`:

```java
package com.graphify.repograph;

import com.graphify.store.Chunks;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** The stored repository graph analyses (spec §9.3); replaced as a whole after each index. */
@Repository
public class RepoGraphStore {

    private final JdbcTemplate jdbc;

    public RepoGraphStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void replace(long repositoryId, String commit, ClassGraph graph, RepoGraphAnalyzer.Analysis analysis,
            int batchSize) {
        clear(repositoryId);
        List<Object[]> metrics = new ArrayList<>();
        List<Object[]> communities = new ArrayList<>();
        analysis.metrics().forEach((fqn, m) -> {
            long symbolId = graph.classes().get(fqn).symbolId();
            metrics.add(new Object[] {repositoryId, symbolId, m.inDegree(), m.outDegree(), m.dependents(),
                    m.entryPoint() ? 1 : 0});
            communities.add(new Object[] {repositoryId, commit, symbolId, m.communityId(), m.communityLabel()});
        });
        List<Object[]> cycles = new ArrayList<>();
        for (int i = 0; i < analysis.cycles().size(); i++) {
            for (String packageName : analysis.cycles().get(i)) {
                cycles.add(new Object[] {repositoryId, i + 1, packageName});
            }
        }
        for (List<Object[]> chunk : Chunks.of(metrics, batchSize)) {
            jdbc.batchUpdate("INSERT INTO repo_graph_metric (repo_id, symbol_id, in_degree, out_degree, dependents, "
                    + "is_entry_point) VALUES (?, ?, ?, ?, ?, ?)", chunk);
        }
        for (List<Object[]> chunk : Chunks.of(communities, batchSize)) {
            jdbc.batchUpdate("INSERT INTO repo_graph_community (repo_id, commit_hash, symbol_id, community_id, "
                    + "community_label) VALUES (?, ?, ?, ?, ?)", chunk);
        }
        for (List<Object[]> chunk : Chunks.of(cycles, batchSize)) {
            jdbc.batchUpdate("INSERT INTO repo_graph_cycle (repo_id, cycle_id, package_name) VALUES (?, ?, ?)", chunk);
        }
        long communityCount = analysis.metrics().values().stream().map(ClassMetrics::communityId).distinct().count();
        jdbc.update("INSERT INTO repo_graph_analysis (repo_id, commit_hash, class_count, community_count, cycle_count) "
                + "VALUES (?, ?, ?, ?, ?)", repositoryId, commit, analysis.metrics().size(), communityCount,
                analysis.cycles().size());
    }

    @Transactional
    public void clear(long repositoryId) {
        for (String table : List.of("repo_graph_cycle", "repo_graph_community", "repo_graph_metric",
                "repo_graph_analysis")) {
            jdbc.update("DELETE FROM " + table + " WHERE repo_id = ?", repositoryId);
        }
    }

    public Optional<AnalysisState> state(long repositoryId) {
        return jdbc.query("SELECT commit_hash, analyzed_at, class_count, community_count, cycle_count "
                + "FROM repo_graph_analysis WHERE repo_id = ?", (rs, row) -> new AnalysisState(
                        rs.getString("commit_hash"), rs.getObject("analyzed_at", OffsetDateTime.class).toInstant(),
                        rs.getInt("class_count"), rs.getInt("community_count"), rs.getInt("cycle_count")),
                repositoryId).stream().findFirst();
    }

    public Map<Long, ClassMetrics> metrics(long repositoryId) {
        Map<Long, ClassMetrics> metrics = new HashMap<>();
        jdbc.query("""
                SELECT m.symbol_id, m.in_degree, m.out_degree, m.dependents, m.is_entry_point, c.community_id,
                       c.community_label
                  FROM repo_graph_metric m
                  LEFT JOIN repo_graph_community c ON c.repo_id = m.repo_id AND c.symbol_id = m.symbol_id
                 WHERE m.repo_id = ?
                """, rs -> {
                    metrics.put(rs.getLong("symbol_id"), new ClassMetrics(rs.getInt("in_degree"),
                            rs.getInt("out_degree"), rs.getInt("dependents"), rs.getInt("is_entry_point") == 1,
                            rs.getObject("community_id", Integer.class), rs.getString("community_label")));
                }, repositoryId);
        return metrics;
    }

    public List<CriticalClass> critical(long repositoryId, int limit) {
        return jdbc.query("""
                SELECT m.symbol_id, s.symbol_key, m.in_degree, m.out_degree, m.dependents, m.is_entry_point
                  FROM repo_graph_metric m JOIN symbol s ON s.id = m.symbol_id
                 WHERE m.repo_id = ?
                 ORDER BY m.dependents DESC, m.in_degree DESC, s.symbol_key
                 FETCH FIRST ? ROWS ONLY
                """, (rs, row) -> new CriticalClass(rs.getLong("symbol_id"), rs.getString("symbol_key"),
                        rs.getInt("in_degree"), rs.getInt("out_degree"), rs.getInt("dependents"),
                        rs.getInt("is_entry_point") == 1), repositoryId, limit);
    }

    public List<CommunitySummary> communities(long repositoryId, int limit) {
        return jdbc.query("""
                SELECT community_id, MAX(community_label) AS label, COUNT(*) AS members
                  FROM repo_graph_community WHERE repo_id = ?
                 GROUP BY community_id ORDER BY community_id
                 FETCH FIRST ? ROWS ONLY
                """, (rs, row) -> new CommunitySummary(rs.getInt("community_id"), rs.getString("label"),
                        rs.getInt("members")), repositoryId, limit);
    }

    public List<PackageCycle> cycles(long repositoryId) {
        Map<Integer, List<String>> cycles = new LinkedHashMap<>();
        jdbc.query("SELECT cycle_id, package_name FROM repo_graph_cycle WHERE repo_id = ? ORDER BY cycle_id, "
                + "package_name", rs -> {
                    cycles.computeIfAbsent(rs.getInt("cycle_id"), id -> new ArrayList<>())
                            .add(rs.getString("package_name"));
                }, repositoryId);
        return cycles.entrySet().stream().map(e -> new PackageCycle(e.getKey(), List.copyOf(e.getValue()))).toList();
    }

    public int entryPointCount(long repositoryId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM repo_graph_metric WHERE repo_id = ? "
                + "AND is_entry_point = 1", Integer.class, repositoryId);
        return count == null ? 0 : count;
    }

    public List<String> entryPointClasses(long repositoryId, int limit) {
        return jdbc.queryForList("""
                SELECT s.symbol_key FROM repo_graph_metric m JOIN symbol s ON s.id = m.symbol_id
                 WHERE m.repo_id = ? AND m.is_entry_point = 1
                 ORDER BY s.symbol_key FETCH FIRST ? ROWS ONLY
                """, String.class, repositoryId, limit);
    }
}
```

`RepoGraphService.java` (Task 3 part; Tasks 4 and 5 add methods):

```java
package com.graphify.repograph;

import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import java.util.HashSet;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Repository graph analyses (spec §9.2) and, from Task 4, the graph view, report and export. */
@Service
public class RepoGraphService {

    /** Classes with an enabled entry-point annotation on themselves or a member (spec §9.2 "Giriş noktaları"). */
    private static final String ENTRY_CLASSES = """
            SELECT DISTINCT f.class_fqn
              FROM usage u
              JOIN maven_module m ON m.id = u.module_id
              JOIN symbol f ON f.id = u.from_symbol_id
              JOIN symbol t ON t.id = u.to_symbol_id
             WHERE m.repo_id = ? AND u.kind = 'ANNOTATION'
               AND t.symbol_key IN (SELECT annotation_fqn FROM entry_point_annotation WHERE enabled = 1)
            """;

    private final JdbcTemplate jdbc;
    private final ClassGraphLoader loader;
    private final RepoGraphStore store;
    private final AppSettings settings;

    public RepoGraphService(JdbcTemplate jdbc, ClassGraphLoader loader, RepoGraphStore store, AppSettings settings) {
        this.jdbc = jdbc;
        this.loader = loader;
        this.store = store;
        this.settings = settings;
    }

    /** Recomputes and stores a repository's analyses for the commit just written (spec §3.2 step 9). */
    public void analyze(long repositoryId, String commit) {
        ClassGraph graph = loader.load(repositoryId, false);
        Set<String> entryClasses = new HashSet<>(jdbc.queryForList(ENTRY_CLASSES, String.class, repositoryId));
        RepoGraphAnalyzer.Analysis analysis = RepoGraphAnalyzer.analyze(graph, entryClasses,
                settings.getInt(SettingKeys.GRAPH_COMMUNITY_SEED),
                settings.getInt(SettingKeys.GRAPH_COMMUNITY_MAX_ITERATIONS));
        store.replace(repositoryId, commit, graph, analysis, settings.getInt(SettingKeys.STORE_JDBC_BATCH_SIZE));
    }
}
```

In `RepositoryIndexer.java`:
1. Add the constructor parameter and field `RepoGraphService graphs` (last parameter). Add `private static final Logger log = LoggerFactory.getLogger(RepositoryIndexer.class);` if the class has no logger.
2. Replace `write(...)`:

```java
    private WriteSummary write(RepositoryIndex index) {
        WriteSummary summary;
        try {
            summary = writer.replace(index);
        } catch (PessimisticLockingFailureException e) {
            summary = writer.replace(index);
        }
        analyzeGraph(index.repositoryId(), index.commit());
        return summary;
    }

    /** Graph analyses are derived data: a failure keeps the index and the previous analysis (reported as stale). */
    private void analyzeGraph(long repositoryId, String commit) {
        try {
            graphs.analyze(repositoryId, commit);
        } catch (RuntimeException e) {
            rethrowIfInterrupted(e);
            log.warn("Repository {}: the graph analysis failed: {}", repositoryId,
                    UrlMasking.mask(String.valueOf(e.getMessage())));
        }
    }
```

Any test that constructs `RepositoryIndexer` by hand must pass the new argument. Search `new RepositoryIndexer(`; most tests autowire it.

- [ ] **Step 4: Run the tests**

Run: `./mvnw test -Dtest='RepoGraphServiceTest,RepositoryIndexerTest,IndexRunExecutorTest,RepoGraphAnalyzerTest'`
Expected: all pass.

Run: `./mvnw test`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main src/test
git commit -m "feat(repograph): store communities, critical classes, cycles and entry points after each index" -m "<your harness trailer lines>"
```

---

### Task 4: Graph view with levels, focus, external nodes and rollup

**Files:**
- Create: `src/main/java/com/graphify/repograph/{GraphLevel,GraphNode,GraphEdge,RepoGraph,GraphBuilder,RepoGraphController}.java`
- Modify: `src/main/java/com/graphify/repograph/RepoGraphService.java`, `README.md`
- Test: `src/test/java/com/graphify/repograph/GraphBuilderTest.java`, `RepoGraphApiTest.java`

**Interfaces:**
- **Consumes:** Tasks 2 and 3; `NotFoundException`, `InvalidRequestException`; `SettingKeys.GRAPH_MAX_NODES`.
- **Produces (types):**
  - `enum GraphLevel { MODULE, PACKAGE, CLASS, METHOD }`, with package-private `up()`.
  - `GraphNode(String id, String label, NodeType type, int size, ClassMetrics metrics)`. Ids by node type:
    - `module:<path>`, `package:<name>`, `class:<fqn>`
    - `member:<symbol key>`
    - `external:<group key>`

    `size` is the number of classes rolled into the node: 1 for a class or member, and the number of distinct types for an external node. `metrics` is set only on class nodes that have a stored analysis.
  - `GraphEdge(String from, String to, long weight, Map<UsageKind, Long> kinds)`.
  - `RepoGraph(long repositoryId, GraphLevel requestedLevel, GraphLevel level, String focus, boolean truncated, String suggestion, List<GraphNode> nodes, List<GraphEdge> edges)`.
- **Produces (`GraphBuilder`, package-private):** `new GraphBuilder(ClassGraph, Map<Long, ClassMetrics>)` with `RepoGraph build(GraphLevel requested, String focus, MemberGraph members, int maxNodes)`, following the rollup and focus rulings in the plan header.
  - The suggestion is "`<n>` `<level>` nodes exceed the node limit (`<max>`); shown by `<level>`, narrow it with focus". Levels are lower-cased.
- **Produces (`RepoGraphService`):** `RepoGraph graph(long repositoryId, GraphLevel level, String focus, boolean includeExternal)` with the limit `graph.max_nodes`. Package-private `RepoGraph graph(long, GraphLevel, String, boolean, int maxNodes)` is shared with export. It answers:
  - 404 for an unknown repository;
  - at METHOD level, 400 for a missing focus and 404 for a class not declared in the repository.
- **Produces (`RepoGraphController`):** `GET /api/v1/repositories/{id}/graph?level=&focus=&includeExternal=`.
  - `level` defaults to PACKAGE, is case-insensitive, and anything else is 400.
  - `includeExternal` defaults to false.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/graphify/repograph/GraphBuilderTest.java`:

```java
package com.graphify.repograph;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.indexer.model.SymbolKind;
import com.graphify.indexer.model.UsageKind;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GraphBuilderTest {

    private final GraphBuilder builder = new GraphBuilder(SampleGraphs.sample(),
            Map.of(2L, new ClassMetrics(2, 0, 5, false, 1, "com.g.a")));

    private static List<String> ids(RepoGraph graph) {
        return graph.nodes().stream().map(GraphNode::id).toList();
    }

    private static List<String> edges(RepoGraph graph) {
        return graph.edges().stream().map(e -> e.from() + ">" + e.to()).toList();
    }

    @Test
    void packagesAndModulesAggregateUsagesBetweenThem() {
        RepoGraph packages = builder.build(GraphLevel.PACKAGE, null, null, 500);

        // the sample carries one external library, so it is part of every view
        assertThat(ids(packages)).containsExactly("external:lib:com.lib:util:1.0", "package:com.g.a",
                "package:com.g.b", "package:com.g.c", "package:com.g.web");
        assertThat(edges(packages)).containsExactly("package:com.g.a>external:lib:com.lib:util:1.0",
                "package:com.g.a>package:com.g.b", "package:com.g.b>external:lib:com.lib:util:1.0",
                "package:com.g.b>package:com.g.a", "package:com.g.c>package:com.g.a", "package:com.g.web>package:com.g.c");
        GraphEdge aToB = packages.edges().get(1);
        assertThat(aToB.weight()).isEqualTo(3);
        assertThat(aToB.kinds()).containsEntry(UsageKind.TYPE_REF, 1L).containsEntry(UsageKind.INSTANTIATION, 1L)
                .containsEntry(UsageKind.CALL, 1L);
        assertThat(packages.truncated()).isFalse();

        RepoGraph modules = builder.build(GraphLevel.MODULE, "ignored", null, 500);
        assertThat(ids(modules)).containsExactly("external:lib:com.lib:util:1.0", "module:app", "module:core");
        assertThat(edges(modules)).containsExactly("module:app>module:core",
                "module:core>external:lib:com.lib:util:1.0");
        assertThat(modules.focus()).isNull();
    }

    @Test
    void aFocusKeepsClassesUnderThePackagePrefixAndCarriesStoredMetrics() {
        RepoGraph classes = builder.build(GraphLevel.CLASS, "com.g.a", null, 500);

        assertThat(ids(classes)).containsExactly("class:com.g.a.Alpha", "class:com.g.a.AlphaHelper",
                "external:lib:com.lib:util:1.0");
        assertThat(edges(classes)).containsExactly("class:com.g.a.Alpha>class:com.g.a.AlphaHelper",
                "class:com.g.a.Alpha>external:lib:com.lib:util:1.0");
        assertThat(classes.nodes().get(1).metrics().dependents()).isEqualTo(5);
        assertThat(classes.nodes().getFirst().metrics()).isNull();
        assertThat(ids(builder.build(GraphLevel.CLASS, "com.g", null, 500))).hasSize(8);
    }

    @Test
    void rollsUpOneLevelAtATimeUntilTheGraphFits() {
        RepoGraph toPackages = builder.build(GraphLevel.CLASS, null, null, 5);
        assertThat(toPackages.level()).isEqualTo(GraphLevel.PACKAGE);
        assertThat(toPackages.requestedLevel()).isEqualTo(GraphLevel.CLASS);
        assertThat(toPackages.truncated()).isTrue();
        assertThat(toPackages.suggestion()).contains("8 class nodes exceed the node limit (5)");

        RepoGraph toModules = builder.build(GraphLevel.CLASS, null, null, 3);
        assertThat(toModules.level()).isEqualTo(GraphLevel.MODULE);
        assertThat(ids(toModules)).hasSize(3);

        RepoGraph keepsFocus = builder.build(GraphLevel.CLASS, "com.g.a", null, 2);
        assertThat(keepsFocus.level()).isEqualTo(GraphLevel.PACKAGE);
        assertThat(keepsFocus.focus()).isEqualTo("com.g.a");
        assertThat(ids(keepsFocus)).containsExactly("external:lib:com.lib:util:1.0", "package:com.g.a");
    }

    @Test
    void externalTypesAreGroupedPerSource() {
        RepoGraph packages = builder.build(GraphLevel.PACKAGE, null, null, 500);

        assertThat(ids(packages)).contains("external:lib:com.lib:util:1.0");
        assertThat(packages.nodes()).filteredOn(n -> n.type() == NodeType.EXTERNAL_LIBRARY).singleElement()
                .satisfies(n -> assertThat(n.size()).isEqualTo(2));
        assertThat(edges(packages)).contains("package:com.g.a>external:lib:com.lib:util:1.0",
                "package:com.g.b>external:lib:com.lib:util:1.0");
    }

    @Test
    void theMethodLevelShowsAClassMembersWithTheirNeighbours() {
        MemberRef run = new MemberRef(11, "com.g.a.Alpha#run()", "com.g.a.Alpha", SymbolKind.METHOD, "int run()");
        MemberRef one = new MemberRef(12, "com.g.a.AlphaHelper#one()", "com.g.a.AlphaHelper", SymbolKind.METHOD,
                "int one()");
        MemberRef total = new MemberRef(13, "com.g.c.Gamma#total()", "com.g.c.Gamma", SymbolKind.METHOD,
                "int total()");
        MemberRef strings = new MemberRef(14, "com.lib.Strings#trim()", "com.lib.Strings", SymbolKind.METHOD,
                "String trim()");
        MemberGraph members = new MemberGraph(List.of(run), List.of(
                new MemberUse(run, one, UsageKind.CALL, 1), new MemberUse(total, run, UsageKind.CALL, 1),
                new MemberUse(run, strings, UsageKind.CALL, 2)));

        RepoGraph method = builder.build(GraphLevel.METHOD, "com.g.a.Alpha", members, 500);
        assertThat(ids(method)).containsExactly("external:lib:com.lib:util:1.0", "member:com.g.a.Alpha#run()",
                "member:com.g.a.AlphaHelper#one()", "member:com.g.c.Gamma#total()");
        assertThat(edges(method)).contains("member:com.g.a.Alpha#run()>member:com.g.a.AlphaHelper#one()",
                "member:com.g.c.Gamma#total()>member:com.g.a.Alpha#run()",
                "member:com.g.a.Alpha#run()>external:lib:com.lib:util:1.0");

        RepoGraph rolledUp = builder.build(GraphLevel.METHOD, "com.g.a.Alpha", members, 3);
        assertThat(rolledUp.level()).isEqualTo(GraphLevel.CLASS);
        assertThat(rolledUp.focus()).isEqualTo("com.g.a");
    }
}
```

`SampleGraphs` always carries its external library, so every view of it includes the `external:lib:com.lib:util:1.0` node: node ids sort `class:` < `external:` < `member:`/`module:`/`package:`. In the last test, the rolled-up CLASS graph focused on `com.g.a` has 3 nodes (Alpha, AlphaHelper and the library), which is within 3, so it stops at CLASS.

`src/test/java/com/graphify/repograph/RepoGraphApiTest.java`:

```java
package com.graphify.repograph;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.store.RepositoryIndexWriter;
import com.graphify.testsupport.GraphFixture;
import com.graphify.testsupport.ShopFixture;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

class RepoGraphApiTest extends OracleIntegrationTest {

    @Autowired
    RepositoryIndexWriter writer;

    @Autowired
    RepoGraphService graphs;

    @TempDir
    Path work;

    @Test
    void showsThePackageGraphByDefaultAndClassesWithTheirMetrics() {
        long repo = GraphFixture.load(jdbc, writer);
        graphs.analyze(repo, GraphFixture.COMMIT);

        assertThat(mvc.get().uri("/api/v1/repositories/" + repo + "/graph")).hasStatusOk().bodyJson().satisfies(json -> {
            assertThat(json).extractingPath("$.level").isEqualTo("PACKAGE");
            assertThat(json).extractingPath("$.nodes.length()").isEqualTo(4);
            assertThat(json).extractingPath("$.truncated").isEqualTo(false);
        });
        assertThat(mvc.get().uri("/api/v1/repositories/" + repo + "/graph?level=class&focus=com.g.a")).hasStatusOk()
                .bodyJson().satisfies(json -> {
                    assertThat(json).extractingPath("$.nodes[*].id").asArray()
                            .containsExactly("class:com.g.a.Alpha", "class:com.g.a.AlphaHelper");
                    assertThat(json).extractingPath("$.nodes[1].metrics.dependents").isEqualTo(5);
                });
    }

    @Test
    void theMethodLevelShowsCallersAndCallees() {
        long repo = GraphFixture.load(jdbc, writer);

        assertThat(mvc.get().uri("/api/v1/repositories/" + repo + "/graph?level=METHOD&focus=com.g.a.Alpha"))
                .hasStatusOk().bodyJson().satisfies(json -> {
                    assertThat(json).extractingPath("$.nodes[*].id").asArray().contains("member:com.g.a.Alpha#run()",
                            "member:com.g.a.AlphaHelper#one()", "member:com.g.c.Gamma#total()");
                    assertThat(json).extractingPath("$.edges[?(@.from == 'member:com.g.a.Alpha#run()' "
                            + "&& @.to == 'member:com.g.a.AlphaHelper#one()')].kinds.CALL").asArray()
                            .containsExactly(1);
                });
    }

    @Test
    void otherRepositoriesAppearAsExternalNodesOnlyWhenAsked() throws Exception {
        ShopFixture.load(jdbc, writer, work);
        long api = ShopFixture.repositoryId(jdbc, ShopFixture.API_REPO);

        assertThat(mvc.get().uri("/api/v1/repositories/" + api + "/graph?includeExternal=true")).hasStatusOk()
                .bodyJson().extractingPath("$.nodes[?(@.type == 'EXTERNAL_REPOSITORY')].id").asArray()
                .containsExactly("external:repo:TEST/shop-lib");
        assertThat(mvc.get().uri("/api/v1/repositories/" + api + "/graph")).hasStatusOk().bodyJson()
                .extractingPath("$.nodes[?(@.type == 'EXTERNAL_REPOSITORY')]").asArray().isEmpty();
    }

    @Test
    void rejectsBadLevelsFocusesAndRepositories() {
        long repo = GraphFixture.load(jdbc, writer);
        String base = "/api/v1/repositories/" + repo + "/graph";

        assertThat(mvc.get().uri(base + "?level=METHOD")).hasStatus(400);
        assertThat(mvc.get().uri(base + "?level=METHOD&focus=com.nowhere.Nope")).hasStatus(404);
        assertThat(mvc.get().uri(base + "?level=METHOD&focus=java.lang.String")).hasStatus(404);
        assertThat(mvc.get().uri(base + "?level=FILE")).hasStatus(400);
        assertThat(mvc.get().uri("/api/v1/repositories/999999/graph")).hasStatus(404);
        assertThat(anonymous().get().uri(base)).hasStatus(401);
    }
}
```

The JSONPath `kinds.CALL` count of 1 assumes `run()` calls `one()` once. If the index records it differently, assert `isNotEmpty()` and report it.

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -Dtest='GraphBuilderTest,RepoGraphApiTest'`
Expected: compile errors, then 404s on `/graph`.

- [ ] **Step 3: Implement**

`GraphLevel.java`:

```java
package com.graphify.repograph;

/** Levels of the repository graph view (spec §9.1); a graph over the node limit is shown one level up. */
public enum GraphLevel {
    MODULE, PACKAGE, CLASS, METHOD;

    GraphLevel up() {
        return switch (this) {
            case METHOD -> CLASS;
            case CLASS -> PACKAGE;
            case PACKAGE, MODULE -> MODULE;
        };
    }
}
```

`GraphNode.java`, `GraphEdge.java` and `RepoGraph.java`:

```java
/** A node of the graph view; {@code metrics} only on class nodes with a stored analysis. */
public record GraphNode(String id, String label, NodeType type, int size, ClassMetrics metrics) {
}
```

```java
/** Usages from one node to another: their total and their count per usage kind. */
public record GraphEdge(String from, String to, long weight, Map<UsageKind, Long> kinds) {
}
```

```java
/** A repository graph at {@code level}; {@code truncated} when it was rolled up from {@code requestedLevel}. */
public record RepoGraph(long repositoryId, GraphLevel requestedLevel, GraphLevel level, String focus,
        boolean truncated, String suggestion, List<GraphNode> nodes, List<GraphEdge> edges) {
}
```

`GraphBuilder.java`:

```java
package com.graphify.repograph;

import com.graphify.indexer.model.SymbolKind;
import com.graphify.indexer.model.UsageKind;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Builds one level of a repository graph from its class graph, rolling up while over the node limit (spec §9.1). */
final class GraphBuilder {

    /** Node id prefixes, one per node family. */
    private static final String MODULE_ID = "module:";
    private static final String PACKAGE_ID = "package:";
    private static final String CLASS_ID = "class:";
    private static final String MEMBER_ID = "member:";
    private static final String EXTERNAL_ID = "external:";

    private static final class NodeAcc {

        private final String id;
        private final String label;
        private final NodeType type;
        private final ClassMetrics metrics;
        private final Set<String> members = new HashSet<>();

        private NodeAcc(String id, String label, NodeType type, ClassMetrics metrics) {
            this.id = id;
            this.label = label;
            this.type = type;
            this.metrics = metrics;
        }

        private GraphNode node() {
            return new GraphNode(id, label, type, members.size(), metrics);
        }
    }

    private static final class EdgeAcc {

        private long weight;
        private final EnumMap<UsageKind, Long> kinds = new EnumMap<>(UsageKind.class);

        private void add(UsageKind kind, long count) {
            weight += count;
            kinds.merge(kind, count, Long::sum);
        }
    }

    private record Pair(String from, String to) implements Comparable<Pair> {

        @Override
        public int compareTo(Pair other) {
            int byFrom = from.compareTo(other.from);
            return byFrom != 0 ? byFrom : to.compareTo(other.to);
        }
    }

    private record Assembly(List<GraphNode> nodes, List<GraphEdge> edges) {
    }

    private final ClassGraph graph;
    private final Map<Long, ClassMetrics> metrics;

    GraphBuilder(ClassGraph graph, Map<Long, ClassMetrics> metrics) {
        this.graph = graph;
        this.metrics = metrics;
    }

    RepoGraph build(GraphLevel requested, String focus, MemberGraph members, int maxNodes) {
        GraphLevel level = requested;
        String levelFocus = requested == GraphLevel.MODULE ? null : focus;
        Assembly first = null;
        while (true) {
            Assembly assembly = level == GraphLevel.METHOD ? members(members) : rollup(level, levelFocus);
            if (first == null) {
                first = assembly;
            }
            if (assembly.nodes().size() <= maxNodes || level == GraphLevel.MODULE) {
                boolean truncated = level != requested;
                String suggestion = truncated ? first.nodes().size() + " " + name(requested)
                        + " nodes exceed the node limit (" + maxNodes + "); shown by " + name(level)
                        + ", narrow it with focus" : null;
                return new RepoGraph(graph.repositoryId(), requested, level, levelFocus, truncated, suggestion,
                        assembly.nodes(), assembly.edges());
            }
            levelFocus = switch (level) {
                case METHOD -> ClassGraph.packageOf(levelFocus);
                case CLASS -> levelFocus;
                case PACKAGE, MODULE -> null;
            };
            level = level.up();
        }
    }

    private Assembly rollup(GraphLevel level, String focus) {
        Map<String, NodeAcc> nodes = new TreeMap<>();
        Map<String, String> nodeOfClass = new HashMap<>();
        for (ClassNode node : graph.classes().values()) {
            if (!inFocus(node, level, focus)) {
                continue;
            }
            NodeAcc acc = nodes.computeIfAbsent(nodeId(level, node), id -> internalNode(id, level, node));
            acc.members.add(node.fqn());
            nodeOfClass.put(node.fqn(), acc.id);
        }
        Map<Pair, EdgeAcc> edges = new HashMap<>();
        for (ClassEdge edge : graph.edges()) {
            String from = nodeOfClass.get(edge.fromFqn());
            String to = nodeOfClass.get(edge.toFqn());
            if (from != null && to != null && !from.equals(to)) {
                edges.computeIfAbsent(new Pair(from, to), pair -> new EdgeAcc()).add(edge.kind(), edge.weight());
            }
        }
        for (ExternalEdge edge : graph.external()) {
            String from = nodeOfClass.get(edge.fromFqn());
            if (from == null) {
                continue;
            }
            NodeAcc external = externalNode(nodes, edge.group());
            external.members.add(edge.toFqn());
            edges.computeIfAbsent(new Pair(from, external.id), pair -> new EdgeAcc()).add(edge.kind(), edge.weight());
        }
        return assemble(nodes, edges);
    }

    private Assembly members(MemberGraph members) {
        Map<String, NodeAcc> nodes = new TreeMap<>();
        Map<String, ExternalGroup> groups = graph.externalGroups();
        members.focusMembers().forEach(ref -> memberNode(nodes, ref));
        Map<Pair, EdgeAcc> edges = new HashMap<>();
        for (MemberUse use : members.uses()) {
            String from = endpoint(nodes, use.from(), groups);
            String to = endpoint(nodes, use.to(), groups);
            if (from != null && to != null && !from.equals(to)) {
                edges.computeIfAbsent(new Pair(from, to), pair -> new EdgeAcc()).add(use.kind(), use.weight());
            }
        }
        return assemble(nodes, edges);
    }

    /** A member of a repository class is its own node; others go to their external group, or are left out. */
    private String endpoint(Map<String, NodeAcc> nodes, MemberRef ref, Map<String, ExternalGroup> groups) {
        if (graph.classes().containsKey(ref.classFqn())) {
            return memberNode(nodes, ref).id;
        }
        ExternalGroup group = groups.get(ref.classFqn());
        if (group == null) {
            return null;
        }
        NodeAcc external = externalNode(nodes, group);
        external.members.add(ref.classFqn());
        return external.id;
    }

    private static NodeAcc memberNode(Map<String, NodeAcc> nodes, MemberRef ref) {
        NodeAcc acc = nodes.computeIfAbsent(MEMBER_ID + ref.key(),
                id -> new NodeAcc(id, ref.signature(), typeOf(ref.kind()), null));
        acc.members.add(ref.key());
        return acc;
    }

    private static NodeAcc externalNode(Map<String, NodeAcc> nodes, ExternalGroup group) {
        return nodes.computeIfAbsent(EXTERNAL_ID + group.key(), id -> new NodeAcc(id, group.label(), group.type(), null));
    }

    private static boolean inFocus(ClassNode node, GraphLevel level, String focus) {
        return focus == null || level == GraphLevel.MODULE || node.packageName().equals(focus)
                || node.packageName().startsWith(focus + ".");
    }

    private static String nodeId(GraphLevel level, ClassNode node) {
        return switch (level) {
            case MODULE -> MODULE_ID + node.modulePath();
            case PACKAGE -> PACKAGE_ID + node.packageName();
            case CLASS -> CLASS_ID + node.fqn();
            case METHOD -> throw new IllegalArgumentException("METHOD nodes are members");
        };
    }

    private NodeAcc internalNode(String id, GraphLevel level, ClassNode node) {
        return switch (level) {
            case MODULE -> new NodeAcc(id, node.modulePath(), NodeType.MODULE, null);
            case PACKAGE -> new NodeAcc(id, node.packageName().isEmpty() ? RepoGraphAnalyzer.DEFAULT_PACKAGE
                    : node.packageName(), NodeType.PACKAGE, null);
            case CLASS -> new NodeAcc(id, node.fqn(), NodeType.CLASS, metrics.get(node.symbolId()));
            case METHOD -> throw new IllegalArgumentException("METHOD nodes are members");
        };
    }

    private static NodeType typeOf(SymbolKind kind) {
        return switch (kind) {
            case METHOD -> NodeType.METHOD;
            case CONSTRUCTOR -> NodeType.CONSTRUCTOR;
            case FIELD -> NodeType.FIELD;
            default -> NodeType.CLASS;
        };
    }

    private static Assembly assemble(Map<String, NodeAcc> nodes, Map<Pair, EdgeAcc> edges) {
        List<GraphNode> nodeList = nodes.values().stream().map(NodeAcc::node).toList();
        List<GraphEdge> edgeList = edges.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> new GraphEdge(e.getKey().from(), e.getKey().to(), e.getValue().weight,
                        Collections.unmodifiableMap(new EnumMap<>(e.getValue().kinds))))
                .toList();
        return new Assembly(nodeList, edgeList);
    }

    private static String name(GraphLevel level) {
        return level.name().toLowerCase(Locale.ROOT);
    }
}
```

Add to `RepoGraphService.java` (imports `InvalidRequestException`, `NotFoundException`, `List`):

```java
    public RepoGraph graph(long repositoryId, GraphLevel level, String focus, boolean includeExternal) {
        return graph(repositoryId, level, focus, includeExternal, settings.getInt(SettingKeys.GRAPH_MAX_NODES));
    }

    RepoGraph graph(long repositoryId, GraphLevel level, String focus, boolean includeExternal, int maxNodes) {
        requireRepository(repositoryId);
        String normalized = focus == null || focus.isBlank() ? null : focus.strip();
        ClassGraph graph = loader.load(repositoryId, includeExternal);
        MemberGraph members = null;
        if (level == GraphLevel.METHOD) {
            if (normalized == null) {
                throw new InvalidRequestException("focus (a class name) is required at METHOD level");
            }
            if (!graph.classes().containsKey(normalized)) {
                throw new NotFoundException("No class " + normalized + " is declared in repository " + repositoryId);
            }
            members = loader.members(repositoryId, normalized);
        }
        return new GraphBuilder(graph, store.metrics(repositoryId)).build(level, normalized, members, maxNodes);
    }

    /** The repository's last indexed commit (null when never indexed); 404 for an unknown repository. */
    String requireRepository(long repositoryId) {
        List<String> commits = jdbc.query("SELECT last_indexed_commit FROM scm_repository WHERE id = ?",
                (rs, row) -> rs.getString(1), repositoryId);
        if (commits.isEmpty()) {
            throw new NotFoundException("No repository with id " + repositoryId);
        }
        return commits.getFirst();
    }
```

`RepoGraphController.java`:

```java
package com.graphify.repograph;

import com.graphify.common.exception.InvalidRequestException;
import java.util.Arrays;
import java.util.Locale;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The repository graph (spec §10.5): view; Task 5 adds the report and the export. */
@RestController
@RequestMapping("/api/v1/repositories/{id}/graph")
public class RepoGraphController {

    private final RepoGraphService graphs;

    public RepoGraphController(RepoGraphService graphs) {
        this.graphs = graphs;
    }

    @GetMapping
    public RepoGraph graph(@PathVariable long id, @RequestParam(required = false) String level,
            @RequestParam(required = false) String focus, @RequestParam(defaultValue = "false") boolean includeExternal) {
        return graphs.graph(id, level(level), focus, includeExternal);
    }

    /** PACKAGE when absent (spec §9.1); case-insensitive; anything else is a 400. */
    static GraphLevel level(String level) {
        if (level == null || level.isBlank()) {
            return GraphLevel.PACKAGE;
        }
        try {
            return GraphLevel.valueOf(level.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("level must be one of " + Arrays.toString(GraphLevel.values()));
        }
    }
}
```

In `README.md`, add a row to the API table: `GET /repositories/{id}/graph?level=&focus=&includeExternal=`. It returns MODULE/PACKAGE (default)/CLASS/METHOD graphs:
- `focus` is a package prefix, or the class at METHOD level (required there).
- `includeExternal` groups other repositories and libraries.
- Over `graph.max_nodes` the graph is shown one level up with `truncated` and a `suggestion` (USER).

- [ ] **Step 4: Run the tests**

Run: `./mvnw test -Dtest='GraphBuilderTest,RepoGraphApiTest,RepoGraphServiceTest'`
Expected: all pass.

Run: `./mvnw test`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main src/test README.md
git commit -m "feat(repograph): serve the repository graph by level with focus, external nodes and rollup" -m "<your harness trailer lines>"
```

---

### Task 5: Graph report and GraphML/JSON export

**Files:**
- Create: `src/main/java/com/graphify/repograph/RepoGraphReport.java`, `GraphMl.java`
- Modify: `src/main/java/com/graphify/repograph/RepoGraphService.java`, `RepoGraphController.java`, `README.md`
- Test: `src/test/java/com/graphify/repograph/RepoGraphApiTest.java` (extend), `GraphMlTest.java`

**Interfaces:**
- **Consumes:** Tasks 3 and 4; `SettingKeys.GRAPH_REPORT_TOP_N`, `GRAPH_EXPORT_MAX_NODES`; `SettingsOverride` (tests).
- **Produces:**
  - `public record RepoGraphReport(long repositoryId, String indexedCommit, String analyzedCommit, Instant analyzedAt, boolean stale, int moduleCount, int packageCount, int classCount, long dependencyCount, int communityCount, List<CriticalClass> criticalClasses, List<CommunitySummary> communities, List<PackageCycle> cycles, int entryPointCount, List<String> entryPointClasses)`:
    - The counts come from the live class graph. `dependencyCount` counts distinct class pairs.
    - The lists come from the stored analyses, capped at `graph.report_top_n` (except `cycles`).
    - `stale` = indexed and (no analysis, or `analyzedCommit` ≠ `indexedCommit`).
  - `RepoGraphService.report(long)` and `RepoGraph exportGraph(long, GraphLevel, String, boolean)`, the latter capped by `graph.export_max_nodes`.
  - `GraphMl.write(RepoGraph) → byte[]`: GraphML 1.0, UTF-8, `edgedefault="directed"`.
    - Node data keys: `label`, `type`, `size`, `community`, `dependents`.
    - Edge data keys: `weight`, `kinds` (`CALL=3,TYPE_REF=1`).
  - Controller:
    - `GET /graph/report`.
    - `GET /graph/export?format=graphml|json&level=&focus=&includeExternal=`: a missing or unknown format is 400.
      - `graphml` answers `application/xml`; `json` answers `application/json` (the `RepoGraph` body).
      - Both send `Content-Disposition: attachment; filename="repository-<id>-<level>.<graphml|json>"`, where `<level>` is the shown level, lower-cased.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/graphify/repograph/GraphMlTest.java`:

```java
package com.graphify.repograph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

class GraphMlTest {

    @Test
    void writesNodesEdgesAndTheirDataAsGraphMl() throws Exception {
        RepoGraph graph = new GraphBuilder(SampleGraphs.sample(),
                Map.of(2L, new ClassMetrics(2, 0, 5, false, 1, "com.g.a"))).build(GraphLevel.CLASS, "com.g.a", null, 500);

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document xml = factory.newDocumentBuilder().parse(new ByteArrayInputStream(GraphMl.write(graph)));

        assertThat(xml.getDocumentElement().getLocalName()).isEqualTo("graphml");
        assertThat(xml.getDocumentElement().getNamespaceURI()).isEqualTo("http://graphml.graphdrawing.org/xmlns");
        assertThat(xml.getElementsByTagNameNS("*", "node").getLength()).isEqualTo(3);
        assertThat(xml.getElementsByTagNameNS("*", "edge").getLength()).isEqualTo(2);
        assertThat(new String(GraphMl.write(graph), java.nio.charset.StandardCharsets.UTF_8))
                .contains("class:com.g.a.AlphaHelper").contains(">5<").contains("CALL=1");
    }
}
```

Import `StandardCharsets` properly instead of the qualified name.

Append to `RepoGraphApiTest` (add the fields and imports: `@Autowired AppSettings settings;`, `SettingsOverride`, `SettingKeys`, `DocumentBuilderFactory`, `ByteArrayInputStream`):

```java
    @Test
    void theReportSummarisesTheAnalysesAndFlagsAStaleOne() {
        long repo = GraphFixture.load(jdbc, writer);
        jdbc.update("INSERT INTO entry_point_annotation (annotation_fqn, label, enabled) VALUES (?, 'Test', 1)",
                GraphFixture.ENTRY_ANNOTATION);
        try {
            jdbc.update("UPDATE scm_repository SET last_indexed_commit = ? WHERE id = ?", GraphFixture.COMMIT, repo);
            assertThat(mvc.get().uri("/api/v1/repositories/" + repo + "/graph/report")).hasStatusOk().bodyJson()
                    .satisfies(json -> {
                        assertThat(json).extractingPath("$.analyzedCommit").isNull();
                        assertThat(json).extractingPath("$.stale").isEqualTo(true);
                        assertThat(json).extractingPath("$.classCount").isEqualTo(7);
                    });

            graphs.analyze(repo, GraphFixture.COMMIT);

            assertThat(mvc.get().uri("/api/v1/repositories/" + repo + "/graph/report")).hasStatusOk().bodyJson()
                    .satisfies(json -> {
                        assertThat(json).extractingPath("$.stale").isEqualTo(false);
                        assertThat(json).extractingPath("$.moduleCount").isEqualTo(2);
                        assertThat(json).extractingPath("$.packageCount").isEqualTo(4);
                        assertThat(json).extractingPath("$.criticalClasses[0].fqn").isEqualTo("com.g.a.AlphaHelper");
                        assertThat(json).extractingPath("$.criticalClasses[0].dependents").isEqualTo(5);
                        assertThat(json).extractingPath("$.cycles[0].packages").asArray()
                                .containsExactly("com.g.a", "com.g.b");
                        assertThat(json).extractingPath("$.entryPointClasses").asArray()
                                .containsExactly("com.g.web.Api");
                    });

            SettingsOverride overrides = new SettingsOverride(settings);
            overrides.set(SettingKeys.GRAPH_REPORT_TOP_N, "1");
            try {
                assertThat(mvc.get().uri("/api/v1/repositories/" + repo + "/graph/report")).hasStatusOk().bodyJson()
                        .extractingPath("$.criticalClasses.length()").isEqualTo(1);
            } finally {
                overrides.restore();
            }

            jdbc.update("UPDATE scm_repository SET last_indexed_commit = 'graph-2' WHERE id = ?", repo);
            assertThat(mvc.get().uri("/api/v1/repositories/" + repo + "/graph/report")).hasStatusOk().bodyJson()
                    .extractingPath("$.stale").isEqualTo(true);
        } finally {
            jdbc.update("DELETE FROM entry_point_annotation WHERE annotation_fqn = ?", GraphFixture.ENTRY_ANNOTATION);
        }
    }

    @Test
    void exportsGraphMlAndJsonAsAttachments() throws Exception {
        long repo = GraphFixture.load(jdbc, writer);
        String base = "/api/v1/repositories/" + repo + "/graph/export";

        var graphml = mvc.get().uri(base + "?format=graphml").exchange();
        assertThat(graphml).hasStatusOk().hasContentTypeCompatibleWith("application/xml")
                .headers().hasValue("Content-Disposition", "attachment; filename=\"repository-" + repo + "-package.graphml\"");
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        var xml = factory.newDocumentBuilder().parse(new ByteArrayInputStream(graphml.getResponse().getContentAsByteArray()));
        assertThat(xml.getElementsByTagNameNS("*", "node").getLength()).isEqualTo(4);

        assertThat(mvc.get().uri(base + "?format=json&level=class&focus=com.g.a")).hasStatusOk().bodyJson()
                .extractingPath("$.nodes.length()").isEqualTo(2);
        assertThat(mvc.get().uri(base)).hasStatus(400);
        assertThat(mvc.get().uri(base + "?format=csv")).hasStatus(400);
    }
```

Check the exact name of the restore method on `SettingsOverride` in the test sources (plan 7 used `restore()`). Adapt the header assertion to the AssertJ MVC API (`.headers().hasValue(...)`). If that API differs, read the header from `graphml.getResponse().getHeader("Content-Disposition")`.

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -Dtest='GraphMlTest,RepoGraphApiTest'`
Expected: compile errors, then 404s for `/report` and `/export`.

- [ ] **Step 3: Implement**

`RepoGraphReport.java`:

```java
package com.graphify.repograph;

import java.time.Instant;
import java.util.List;

/**
 * A repository graph summary (spec §10.5): live counts plus the stored analyses; {@code stale} when the index moved on
 * since the last analysis.
 */
public record RepoGraphReport(
        long repositoryId,
        String indexedCommit,
        String analyzedCommit,
        Instant analyzedAt,
        boolean stale,
        int moduleCount,
        int packageCount,
        int classCount,
        long dependencyCount,
        int communityCount,
        List<CriticalClass> criticalClasses,
        List<CommunitySummary> communities,
        List<PackageCycle> cycles,
        int entryPointCount,
        List<String> entryPointClasses) {
}
```

Add to `RepoGraphService.java` (imports `Optional`, `Set`, `TreeSet`, `Collectors`):

```java
    public RepoGraph exportGraph(long repositoryId, GraphLevel level, String focus, boolean includeExternal) {
        return graph(repositoryId, level, focus, includeExternal, settings.getInt(SettingKeys.GRAPH_EXPORT_MAX_NODES));
    }

    public RepoGraphReport report(long repositoryId) {
        String indexedCommit = requireRepository(repositoryId);
        ClassGraph graph = loader.load(repositoryId, false);
        Optional<AnalysisState> state = store.state(repositoryId);
        int top = settings.getInt(SettingKeys.GRAPH_REPORT_TOP_N);
        Set<String> modules = graph.classes().values().stream().map(ClassNode::modulePath)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> packages = graph.classes().values().stream().map(ClassNode::packageName)
                .collect(Collectors.toCollection(TreeSet::new));
        long dependencies = graph.edges().stream().map(e -> e.fromFqn() + "\n" + e.toFqn()).distinct().count();
        String analyzedCommit = state.map(AnalysisState::commit).orElse(null);
        boolean stale = indexedCommit != null && !indexedCommit.equals(analyzedCommit);
        return new RepoGraphReport(repositoryId, indexedCommit, analyzedCommit,
                state.map(AnalysisState::analyzedAt).orElse(null), stale, modules.size(), packages.size(),
                graph.classes().size(), dependencies, state.map(AnalysisState::communityCount).orElse(0),
                store.critical(repositoryId, top), store.communities(repositoryId, top), store.cycles(repositoryId),
                store.entryPointCount(repositoryId), store.entryPointClasses(repositoryId, top));
    }
```

`GraphMl.java`:

```java
package com.graphify.repograph;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/** Writes a repository graph as GraphML (for Gephi, yEd and similar tools). */
final class GraphMl {

    /** The GraphML namespace (graphml.graphdrawing.org), a format fact. */
    private static final String NAMESPACE = "http://graphml.graphdrawing.org/xmlns";

    private GraphMl() {
    }

    static byte[] write(RepoGraph graph) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            XMLStreamWriter xml = XMLOutputFactory.newFactory().createXMLStreamWriter(out,
                    StandardCharsets.UTF_8.name());
            xml.writeStartDocument(StandardCharsets.UTF_8.name(), "1.0");
            xml.writeStartElement("graphml");
            xml.writeDefaultNamespace(NAMESPACE);
            key(xml, "label", "node", "string");
            key(xml, "type", "node", "string");
            key(xml, "size", "node", "int");
            key(xml, "community", "node", "int");
            key(xml, "dependents", "node", "int");
            key(xml, "weight", "edge", "long");
            key(xml, "kinds", "edge", "string");
            xml.writeStartElement("graph");
            xml.writeAttribute("id", "repository-" + graph.repositoryId());
            xml.writeAttribute("edgedefault", "directed");
            for (GraphNode node : graph.nodes()) {
                xml.writeStartElement("node");
                xml.writeAttribute("id", node.id());
                data(xml, "label", node.label());
                data(xml, "type", node.type().name());
                data(xml, "size", Integer.toString(node.size()));
                if (node.metrics() != null) {
                    if (node.metrics().communityId() != null) {
                        data(xml, "community", node.metrics().communityId().toString());
                    }
                    data(xml, "dependents", Integer.toString(node.metrics().dependents()));
                }
                xml.writeEndElement();
            }
            int index = 0;
            for (GraphEdge edge : graph.edges()) {
                xml.writeStartElement("edge");
                xml.writeAttribute("id", "e" + index++);
                xml.writeAttribute("source", edge.from());
                xml.writeAttribute("target", edge.to());
                data(xml, "weight", Long.toString(edge.weight()));
                data(xml, "kinds", edge.kinds().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                        .collect(Collectors.joining(",")));
                xml.writeEndElement();
            }
            xml.writeEndElement();
            xml.writeEndElement();
            xml.writeEndDocument();
            xml.close();
        } catch (XMLStreamException e) {
            throw new IllegalStateException("The graph could not be written as GraphML", e);
        }
        return out.toByteArray();
    }

    private static void key(XMLStreamWriter xml, String id, String target, String type) throws XMLStreamException {
        xml.writeEmptyElement("key");
        xml.writeAttribute("id", id);
        xml.writeAttribute("for", target);
        xml.writeAttribute("attr.name", id);
        xml.writeAttribute("attr.type", type);
    }

    private static void data(XMLStreamWriter xml, String key, String value) throws XMLStreamException {
        xml.writeStartElement("data");
        xml.writeAttribute("key", key);
        xml.writeCharacters(value);
        xml.writeEndElement();
    }
}
```

Add to `RepoGraphController.java` (imports `ContentDisposition`, `HttpHeaders`, `MediaType`, `ResponseEntity`):

```java
    /** Export formats and their file extensions. */
    private enum ExportFormat {
        GRAPHML, JSON
    }

    @GetMapping("/report")
    public RepoGraphReport report(@PathVariable long id) {
        return graphs.report(id);
    }

    @GetMapping("/export")
    public ResponseEntity<?> export(@PathVariable long id, @RequestParam(required = false) String format,
            @RequestParam(required = false) String level, @RequestParam(required = false) String focus,
            @RequestParam(defaultValue = "false") boolean includeExternal) {
        ExportFormat exportFormat = format(format);
        RepoGraph graph = graphs.exportGraph(id, level(level), focus, includeExternal);
        String extension = exportFormat.name().toLowerCase(Locale.ROOT);
        String filename = "repository-" + id + "-" + graph.level().name().toLowerCase(Locale.ROOT) + "." + extension;
        ResponseEntity.BodyBuilder response = ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(filename).build().toString());
        return exportFormat == ExportFormat.GRAPHML
                ? response.contentType(MediaType.APPLICATION_XML).body(GraphMl.write(graph))
                : response.contentType(MediaType.APPLICATION_JSON).body(graph);
    }

    private static ExportFormat format(String format) {
        if (format != null) {
            for (ExportFormat candidate : ExportFormat.values()) {
                if (candidate.name().equalsIgnoreCase(format.strip())) {
                    return candidate;
                }
            }
        }
        throw new InvalidRequestException("format must be graphml or json");
    }
```

In `README.md`, add the rows:
- `GET /repositories/{id}/graph/report`: counts, the most depended-on classes, communities, package cycles and entry-point classes (lists capped by `graph.report_top_n`); `stale` when the last analysis is not for the indexed commit (USER).
- `GET /repositories/{id}/graph/export?format=graphml|json&level=&focus=&includeExternal=`: the same graph as an attachment, capped by `graph.export_max_nodes` (USER).

Add one sentence under the table: analyses are recomputed after every index, and communities are deterministic for `graph.community_seed`.

- [ ] **Step 4: Run the tests**

Run: `./mvnw test -Dtest='GraphMlTest,RepoGraphApiTest,GraphBuilderTest,RepoGraphServiceTest'`
Expected: all pass.

Run: `./mvnw test`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main src/test README.md
git commit -m "feat(repograph): add the graph report and GraphML/JSON export" -m "<your harness trailer lines>"
```
