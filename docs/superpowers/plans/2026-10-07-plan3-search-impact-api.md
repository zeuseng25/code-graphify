# Plan 3 — Search, Impact Analysis & REST API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Answer the product's core questions over the persisted index:
- "where is X used"
- "in how many projects"
- "what is affected, up to N levels, if X changes"
- "which endpoints and jobs are hit"

Each answer is served through a documented REST API.

**Architecture:**
- **Search and detail.** `SymbolSearch` and `SymbolDetails` are SQL queries over `symbol` and `usage`. Search uses new case-insensitive virtual columns.
- **Impact engine.** `ImpactEngine` is a pure-Java BFS over a narrow `ImpactGraph` port. It applies spec §5's seed, propagation, dispatch, name-only, truncation and entry-point rules. Rules and labels come from the new `IMPACT_RELATION_RULE` and `ENTRY_POINT_ANNOTATION` tables. Limits come from `AppSettings`.
- **Storage adapter.** `JdbcImpactGraph` implements the port against Oracle, chunking every `IN` list.
- **Controllers.** Spring MVC controllers expose `/api/v1/...` with RFC 7807 errors. springdoc serves the OpenAPI document.

**Tech Stack:**
- Java 25, Spring Boot 4.1.1 (`spring-boot-starter-webmvc`, `spring-boot-starter-webmvc-test`)
- springdoc-openapi 3.1.1 (`springdoc-openapi-starter-webmvc-api`)
- Jackson 3 (Boot BOM)
- Oracle with Flyway, Testcontainers
- JUnit 5, AssertJ, `MockMvcTester`

**Spec:** `docs/superpowers/specs/2026-10-05-impact-analyzer-design.md`. This plan implements §5 (impact analysis), §10.1, §10.3, §10.4, and the `GET /repositories[/{id}]` part of §10.5. Carried items come from `docs/superpowers/plans/2026-10-06-plan2-followups.md`, section "Plan 3".

## Plan series

This is plan 3 of 6. Plans 1 (indexer) and 2 (persistence and settings) are merged.

| Plan | Scope |
|---|---|
| 4 | Bitbucket, git, Maven classpath, `MODULE_DEPENDENCY`, index runs, scheduler. Spec §5.6 *version warnings* are deferred to plan 4 because they need `MODULE_DEPENDENCY`. |
| 5 | Auth and admin APIs. Until plan 5, the endpoints in this plan are unauthenticated. |
| 6 | Repo graph. |

## Global Constraints

- **JDK 25.** Every command runs with `export JAVA_HOME=/opt/homebrew/opt/openjdk@25`. Docker must be running, and the image `gvenzl/oracle-free:23-slim-faststart` is already pulled.
- **Dependency versions** come from the Spring Boot 4.1.1 BOM. The one exception is springdoc: `springdoc.version` = `3.1.1`, pinned because it is not in the BOM.
- **No hardcoded values ("kodda sabit değer yok").** These values come only from `AppSettings` (seed rows already exist):
  - depth defaults and limits: `impact.default_depth`, `impact.max_depth`
  - result cap: `impact.max_results`
  - page sizes: `api.page_default_size`, `api.page_max_size`

  Propagation rules come only from `impact_relation_rule`, and entry-point annotations only from `entry_point_annotation`. Spring's annotation semantics count as language facts, not settings. Examples: an annotation whose simple name ends in `Mapping` carries an HTTP path, and `GetMapping` means GET. Oracle's 1000-item `IN` limit stays a named constant.
- **Schema.** New schema changes go in `V3__search_and_impact.sql`. V1 and V2 are merged and must not be edited. Every text column is `VARCHAR2(n BYTE)`.
- **API.**
  - Prefix: `/api/v1`. Bodies are JSON.
  - Errors are RFC 7807 `ProblemDetail`: 400 for invalid input, 404 for an unknown id, never 500 for a user mistake.
  - Pagination: `?page=0&size=N`. A missing `size` uses `api.page_default_size`. A `size` above `api.page_max_size`, or below 1, is a 400.
  - The OpenAPI document is at `/api/v1/openapi.json`.
- **Impact semantics (spec §5).**
  - Level 1 uses the kinds with `shown_at_level1 = 1`. Levels ≥ 2 use the kinds with `propagates = 1`.
  - Only propagating edges expand the next frontier.
  - Dispatch:
    - A method's overridden methods have their callers reported with `viaDispatch = true`.
    - `OVERRIDES` edges are never taken from a dispatch target, so sibling implementations are not reported.
    - Dispatch is applied to the seeds and to every newly affected symbol.
  - A type target seeds itself and all its descendant members.
  - A method or constructor target also seeds its name-only twin `Class#name/argCount`.
  - `SIGNATURE` forces depth 1 and turns dispatch off. `BEHAVIOR` is the default.
  - When affected (non-seed) symbols reach `impact.max_results`, no new symbol is added and `truncated = true`.
- **Feature packages.**
  - `com.graphify.search` (search, detail, usages)
  - `com.graphify.impact`
  - `com.graphify.repository` (repository listing)
  - `com.graphify.api` (paging)
  - `com.graphify.common.exception` (`NotFoundException`, `ApiExceptionHandler`)
- **Tests.** All Oracle-backed tests extend `com.graphify.OracleIntegrationTest`. After Task 2 it also carries `@AutoConfigureMockMvc` and a `MockMvcTester mvc`, so every integration test shares one context and one container. Tests that need indexed data load `ShopFixture` (Task 1).
- **Commits** end with the Co-Authored-By trailer the committing agent's harness provides.

## Review Focus

1. **A symbol with a very large fan-in.** Example: `java.lang.String#equals` with tens of thousands of callers. Impact must stay bounded: it stops at `impact.max_results` with `truncated = true`, and a frontier with more than 1000 ids must not hit ORA-01795. Tests: Task 5 `ImpactEngineTest.resultLimitTruncatesInsteadOfGrowingWithoutBound`; Task 7 `JdbcImpactGraphTest.usagesToHandlesMoreThanAThousandTargets`.
2. **Cycles in the call graph** (mutual recursion, a method calling itself) must terminate. Each symbol appears once, at its first level. Test: Task 5 `ImpactEngineTest.cyclesTerminateAndKeepTheFirstLevel`.
3. **An interface with several implementations.** Changing one implementation must report callers of the interface method (dispatch) but not the sibling implementations. Test: Task 5 `ImpactEngineTest.dispatchReportsCallersOfTheOverriddenMethodButNotSiblings`.
4. **User text containing LIKE or SQL metacharacters** (`%`, `_`, `'`) in a search or repository filter is matched literally. It must never become a wildcard or a SQL error. Tests: Task 2 `RepositoryApiTest.filterTreatsWildcardsLiterally`; Task 3 `SymbolSearchTest.metacharactersAreLiteral`.
5. **Invalid API input** must return 400 or 404 `ProblemDetail`, never 500. This covers an unknown enum value, a depth out of range, a page size above the maximum, an unknown symbol id, and an unsupported export format. Tests: Task 3 `SymbolApiTest`, Task 4 `SymbolDetailApiTest`, Task 8 `ImpactApiTest.invalidRequestsAreProblemDetails`.

---

## File Structure

| File | Responsibility |
|---|---|
| `src/main/resources/db/migration/V3__search_and_impact.sql` | Search virtual columns and indexes, covering BFS index, `entry_point_annotation`, `impact_relation_rule` and their seeds |
| `src/main/java/com/graphify/indexer/NameOnlyResolver.java`, `SymbolKeys.java`, `MethodKeys.java` (modify) | Spelled (nested) receiver names for NAME_ONLY calls (plan-2 follow-up) |
| `src/test/resources/fixtures/shop/**` | Three-module fixture: library, API with endpoint/job/dispatch, legacy module without classpath |
| `src/test/java/com/graphify/testsupport/ShopFixture.java` | Index the shop fixture into Oracle as two repositories |
| `src/main/java/com/graphify/store/Chunks.java` (modify, make public) | `IN`-list chunking shared by query adapters |
| `src/main/java/com/graphify/common/exception/NotFoundException.java`, `ApiExceptionHandler.java` | 404 / 400 ProblemDetail mapping |
| `src/main/java/com/graphify/api/Page.java`, `Paging.java`, `PagingResolver.java` | Settings-bound pagination |
| `src/main/java/com/graphify/repository/*` | Repository list and detail queries + controller |
| `src/main/java/com/graphify/search/SymbolQuery.java`, `SymbolHit.java`, `SymbolSearch.java` | Parse "RestTemplate.exchange"-style text; search |
| `src/main/java/com/graphify/search/SymbolRef.java`, `SymbolDetail.java`, `DeclarationView.java`, `UsageView.java`, `UsageSummary.java`, `SymbolDetails.java`, `SymbolController.java` | Detail, usage list, usage summary, symbol endpoints |
| `src/main/java/com/graphify/impact/*` (model records, `ImpactGraph`, `ImpactEngine`, `MappingPaths`, `EntryPointFinder`) | Pure impact BFS and entry points |
| `src/main/java/com/graphify/impact/JdbcImpactGraph.java`, `ImpactRules.java`, `ImpactService.java` | Oracle adapter, DB rules/labels, settings-bound service |
| `src/main/java/com/graphify/impact/ImpactController.java`, `ImpactCsv.java` | `/impact` and `/impact/export` |

---

### Task 1: Search/impact schema, nested NAME_ONLY receivers, and the shop fixture

**Files:**
- Create: `src/main/resources/db/migration/V3__search_and_impact.sql`
- Modify: `src/main/java/com/graphify/indexer/SymbolKeys.java`, `MethodKeys.java`, `NameOnlyResolver.java`
- Modify: `src/test/java/com/graphify/store/StoreFixtures.java` (make public)
- Modify: `src/test/java/com/graphify/store/SchemaMigrationTest.java`
- Create: the fixture files under `src/test/resources/fixtures/shop/` listed in Step 6
- Create: `src/test/java/com/graphify/testsupport/ShopFixture.java`
- Test: `src/test/java/com/graphify/indexer/NameOnlyResolverTest.java` (add a case), `src/test/java/com/graphify/store/ShopFixtureTest.java`

**Interfaces:**
- **Consumes:**
  - `RepositoryIndexWriter.replace(RepositoryIndex)`.
  - `StoreFixtures.cleanIndexTables` and `StoreFixtures.newRepository(jdbc, slug)`. `newRepository` creates a repository with `project_key = 'TEST'`.
  - `TestJars.jar(...)` (public).
  - The plan-1 indexer API.
- **Produces:**
  - Columns `symbol.search_class` and `symbol.search_member`. These are upper-cased virtual columns holding the simple class name and the member name.
  - Index `ix_usage_bfs (to_symbol_id, kind, from_symbol_id, module_id)`, which replaces `ix_usage_to`.
  - Tables `entry_point_annotation (id, annotation_fqn, label, enabled)`, seeded with 11 rows, and `impact_relation_rule (usage_kind, propagates, shown_at_level1)`, seeded with 10 rows.
  - `SymbolKeys.spelledName(ITypeBinding)`, package-private.
  - `public final class ShopFixture` with `static void load(JdbcTemplate, RepositoryIndexWriter, Path workDir)`, `static long symbolId(JdbcTemplate, String key)`, and constants `LIB_REPO = "shop-lib"` and `API_REPO = "shop-api"`.

Verified in an Oracle spike, these are the details that matter:
- A virtual column needs an explicit type.
- `REGEXP_SUBSTR` and `UPPER` both widen the result to 4000 bytes. The expression is therefore bounded with `SUBSTRB(…, 1, 500)` and then `CAST(… AS VARCHAR2(500 BYTE))`.
- Index keys over the two 500-byte columns fit easily.

- [ ] **Step 1: Write the failing resolver test**

Append to `src/test/java/com/graphify/indexer/NameOnlyResolverTest.java`, inside the class:

```java
    @Test
    void nestedReceiverOfMissingTypeKeepsItsSpelledOuterType() throws Exception {
        CompilationUnit unit = parse("""
                package com.corp.order;
                import com.vendor.Client;
                class Api { Object build(Client.Builder builder) { return builder.build(); } }
                """);
        MethodInvocation call = ParsedSources.find(unit, MethodInvocation.class).getFirst();

        assertThat(new NameOnlyResolver(new ImportResolver(unit)).receiverClass(call.getExpression()))
                .contains("com.vendor.Client$Builder");
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw test -Dtest=NameOnlyResolverTest`
Expected: FAIL. `nestedReceiverOfMissingTypeKeepsItsSpelledOuterType` gets an empty Optional, because the recovered binding's `getName()` is `Builder`, which no import resolves.

- [ ] **Step 3: Resolve recovered receivers from their spelling**

In `src/main/java/com/graphify/indexer/SymbolKeys.java`, add this method to the class, after `parameterTypeName`:

```java
    /**
     * JDT keeps the source spelling of a type that is missing from the classpath as its binary name
     * ({@code OrderDto}, {@code Outer.Inner}, {@code com.corp.dto.OrderDto}) without type arguments;
     * {@code getName()} keeps only the last segment.
     */
    static String spelledName(ITypeBinding type) {
        String spelled = type.getBinaryName();
        String name = spelled == null || spelled.isEmpty() ? type.getName() : spelled;
        int typeArguments = name.indexOf('<');
        return typeArguments < 0 ? name : name.substring(0, typeArguments);
    }
```

In `src/main/java/com/graphify/indexer/MethodKeys.java`, delete the private `spelledName` method. Replace its one call, `spelledName(element.getErasure())`, with `SymbolKeys.spelledName(element.getErasure())`.

In `src/main/java/com/graphify/indexer/NameOnlyResolver.java`, method `receiverClass`, replace:

```java
        if (type != null) {
            return imports.resolve(type.getErasure().getName());
        }
```

with:

```java
        if (type != null) {
            return imports.resolve(SymbolKeys.spelledName(type.getErasure()));
        }
```

Run: `./mvnw test -Dtest='NameOnlyResolverTest,RecoveredSignatureTest,ReferenceVisitorCallsTest'`
Expected: all pass.

- [ ] **Step 4: Write the failing schema assertions**

In `src/test/java/com/graphify/store/SchemaMigrationTest.java`, in `indexesTheColumnsImpactQueriesFilterOn`, change `"ix_usage_to"` to `"ix_usage_bfs"` and add `"ix_symbol_search", "ix_symbol_search_member"` to the list. Add the import `java.util.Map`, then append these two tests inside the class:

```java
    @Test
    void seedsEntryPointsAndImpactRules() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM entry_point_annotation WHERE enabled = 1", Integer.class))
                .isEqualTo(11);
        assertThat(jdbc.queryForObject("SELECT label FROM entry_point_annotation WHERE annotation_fqn = "
                + "'org.springframework.web.bind.annotation.PostMapping'", String.class)).isEqualTo("HTTP");
        Map<String, Object> call = jdbc.queryForMap(
                "SELECT propagates, shown_at_level1 FROM impact_relation_rule WHERE usage_kind = 'CALL'");
        Map<String, Object> typeRef = jdbc.queryForMap(
                "SELECT propagates, shown_at_level1 FROM impact_relation_rule WHERE usage_kind = 'TYPE_REF'");
        assertThat(((Number) call.get("PROPAGATES")).intValue()).isEqualTo(1);
        assertThat(((Number) typeRef.get("PROPAGATES")).intValue()).isZero();
        assertThat(((Number) typeRef.get("SHOWN_AT_LEVEL1")).intValue()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM impact_relation_rule", Integer.class)).isEqualTo(10);
    }

    @Test
    void searchColumnsHoldUpperCasedSimpleNames() {
        jdbc.update("""
                INSERT INTO symbol (symbol_key, kind, class_fqn, member_name, display_signature, origin, name_only)
                VALUES ('a.b.Outer$Inner#run()', 'METHOD', 'a.b.Outer$Inner', 'run', 'Inner.run()', 'SOURCE', 0)
                """);
        try {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT search_class, search_member FROM symbol WHERE symbol_key = 'a.b.Outer$Inner#run()'");
            assertThat(row).containsEntry("SEARCH_CLASS", "INNER").containsEntry("SEARCH_MEMBER", "RUN");
        } finally {
            jdbc.update("DELETE FROM symbol WHERE symbol_key = 'a.b.Outer$Inner#run()'");
        }
    }
```

Run: `./mvnw test -Dtest=SchemaMigrationTest`
Expected: FAIL. `indexesTheColumnsImpactQueriesFilterOn` is missing `ix_usage_bfs`, `seedsEntryPointsAndImpactRules` fails with ORA-00942 (table or view does not exist), and `searchColumnsHoldUpperCasedSimpleNames` fails with ORA-00904 (invalid identifier).

- [ ] **Step 5: Write the V3 migration**

`src/main/resources/db/migration/V3__search_and_impact.sql`:

```sql
-- Plan 3: case-insensitive symbol search, index-only impact BFS, entry-point and propagation rules.

-- Upper-cased simple class name and member name. SUBSTRB + CAST bound the width: UPPER/SUBSTR alone widen to 4000.
ALTER TABLE symbol ADD (
    search_class  VARCHAR2(500 BYTE) GENERATED ALWAYS AS (CAST(SUBSTRB(UPPER(SUBSTR(class_fqn,
                      GREATEST(INSTR(class_fqn, '.', -1), INSTR(class_fqn, '$', -1)) + 1)), 1, 500)
                      AS VARCHAR2(500 BYTE))) VIRTUAL,
    search_member VARCHAR2(500 BYTE) GENERATED ALWAYS AS (CAST(SUBSTRB(UPPER(member_name), 1, 500)
                      AS VARCHAR2(500 BYTE))) VIRTUAL
);
CREATE INDEX ix_symbol_search ON symbol (search_class, search_member);
CREATE INDEX ix_symbol_search_member ON symbol (search_member);

-- Impact BFS filters usage by target and reads kind/from/module: covering index; it also backs fk_usage_to.
CREATE INDEX ix_usage_bfs ON usage (to_symbol_id, kind, from_symbol_id, module_id);
DROP INDEX ix_usage_to;

CREATE TABLE entry_point_annotation (
    id             NUMBER(19) GENERATED BY DEFAULT AS IDENTITY,
    annotation_fqn VARCHAR2(500 BYTE) NOT NULL,
    label          VARCHAR2(200 BYTE) NOT NULL,
    enabled        NUMBER(1) DEFAULT 1 NOT NULL,
    CONSTRAINT pk_entry_point_annotation PRIMARY KEY (id),
    CONSTRAINT uq_entry_point_annotation UNIQUE (annotation_fqn),
    CONSTRAINT ck_entry_point_enabled CHECK (enabled IN (0, 1))
);
INSERT INTO entry_point_annotation (annotation_fqn, label) VALUES ('org.springframework.web.bind.annotation.RequestMapping', 'HTTP');
INSERT INTO entry_point_annotation (annotation_fqn, label) VALUES ('org.springframework.web.bind.annotation.GetMapping', 'HTTP');
INSERT INTO entry_point_annotation (annotation_fqn, label) VALUES ('org.springframework.web.bind.annotation.PostMapping', 'HTTP');
INSERT INTO entry_point_annotation (annotation_fqn, label) VALUES ('org.springframework.web.bind.annotation.PutMapping', 'HTTP');
INSERT INTO entry_point_annotation (annotation_fqn, label) VALUES ('org.springframework.web.bind.annotation.DeleteMapping', 'HTTP');
INSERT INTO entry_point_annotation (annotation_fqn, label) VALUES ('org.springframework.web.bind.annotation.PatchMapping', 'HTTP');
INSERT INTO entry_point_annotation (annotation_fqn, label) VALUES ('org.springframework.scheduling.annotation.Scheduled', 'Zamanlanmış görev');
INSERT INTO entry_point_annotation (annotation_fqn, label) VALUES ('org.springframework.kafka.annotation.KafkaListener', 'Kafka dinleyici');
INSERT INTO entry_point_annotation (annotation_fqn, label) VALUES ('org.springframework.jms.annotation.JmsListener', 'JMS dinleyici');
INSERT INTO entry_point_annotation (annotation_fqn, label) VALUES ('org.springframework.amqp.rabbit.annotation.RabbitListener', 'RabbitMQ dinleyici');
INSERT INTO entry_point_annotation (annotation_fqn, label) VALUES ('org.springframework.context.event.EventListener', 'Uygulama olayı');

CREATE TABLE impact_relation_rule (
    usage_kind      VARCHAR2(20 BYTE) NOT NULL,
    propagates      NUMBER(1)         NOT NULL,
    shown_at_level1 NUMBER(1)         NOT NULL,
    CONSTRAINT pk_impact_relation_rule PRIMARY KEY (usage_kind),
    CONSTRAINT ck_impact_rule_kind CHECK (usage_kind IN ('CALL', 'INSTANTIATION', 'METHOD_REF', 'TYPE_REF', 'EXTENDS',
                                                         'IMPLEMENTS', 'OVERRIDES', 'FIELD_READ', 'FIELD_WRITE',
                                                         'ANNOTATION')),
    CONSTRAINT ck_impact_rule_flags CHECK (propagates IN (0, 1) AND shown_at_level1 IN (0, 1))
);
INSERT INTO impact_relation_rule VALUES ('CALL', 1, 1);
INSERT INTO impact_relation_rule VALUES ('INSTANTIATION', 1, 1);
INSERT INTO impact_relation_rule VALUES ('METHOD_REF', 1, 1);
INSERT INTO impact_relation_rule VALUES ('OVERRIDES', 1, 1);
INSERT INTO impact_relation_rule VALUES ('EXTENDS', 1, 1);
INSERT INTO impact_relation_rule VALUES ('IMPLEMENTS', 1, 1);
INSERT INTO impact_relation_rule VALUES ('TYPE_REF', 0, 1);
INSERT INTO impact_relation_rule VALUES ('ANNOTATION', 0, 1);
INSERT INTO impact_relation_rule VALUES ('FIELD_READ', 0, 1);
INSERT INTO impact_relation_rule VALUES ('FIELD_WRITE', 0, 1);
```

Save the file as UTF-8, since it contains Turkish labels.

Run: `./mvnw test -Dtest=SchemaMigrationTest`
Expected: all pass.

- [ ] **Step 6: Create the shop fixture**

`src/test/resources/fixtures/shop/shop-lib/src/main/java/com/shop/lib/PriceFormatter.java`:

```java
package com.shop.lib;

public class PriceFormatter {
    public String format(int cents) { return String.valueOf(cents); }
    public String format(String raw) { return raw; }
}
```

`src/test/resources/fixtures/shop/shop-lib/src/main/java/com/shop/lib/PaymentGateway.java`:

```java
package com.shop.lib;

public interface PaymentGateway {
    String charge(int cents);
}
```

`src/test/resources/fixtures/shop/shop-api/src/main/java/com/shop/api/CheckoutService.java`:

```java
package com.shop.api;

import com.shop.lib.PaymentGateway;
import com.shop.lib.PriceFormatter;

public class CheckoutService {
    private final PriceFormatter formatter = new PriceFormatter();
    private final PaymentGateway gateway;

    public CheckoutService(PaymentGateway gateway) {
        this.gateway = gateway;
    }

    public String checkout(int cents) {
        gateway.charge(cents);
        return label(cents);
    }

    String label(int cents) {
        return formatter.format(cents);
    }
}
```

`src/test/resources/fixtures/shop/shop-api/src/main/java/com/shop/api/CardGateway.java`:

```java
package com.shop.api;

import com.shop.lib.PaymentGateway;

public class CardGateway implements PaymentGateway {
    @Override
    public String charge(int cents) {
        return "card:" + cents;
    }
}
```

`src/test/resources/fixtures/shop/shop-api/src/main/java/com/shop/api/OrderController.java`:

```java
package com.shop.api;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
public class OrderController {
    private final CheckoutService service = new CheckoutService(new CardGateway());

    @PostMapping(value = "/checkout", produces = "application/json")
    public String checkout() {
        return service.checkout(100);
    }
}
```

`src/test/resources/fixtures/shop/shop-api/src/main/java/com/shop/api/NightlyJob.java`:

```java
package com.shop.api;

import org.springframework.scheduling.annotation.Scheduled;

public class NightlyJob {
    private final CheckoutService service = new CheckoutService(new CardGateway());

    @Scheduled(cron = "0 0 1 * * *")
    public void run() {
        service.checkout(1);
    }
}
```

`src/test/resources/fixtures/shop/shop-legacy/src/main/java/com/shop/legacy/LegacyReport.java`:

```java
package com.shop.legacy;

import com.shop.lib.PriceFormatter;
import com.vendor.Client;

public class LegacyReport {
    public String print() { return new PriceFormatter().format(5); }
    public Object build(Client.Builder builder) { return builder.build(); }
}
```

There is no Spring and no `com.vendor` on any classpath. Spring annotations therefore resolve to NAME_ONLY through imports, and `shop-legacy` sees `PriceFormatter` only by name.

- [ ] **Step 7: Make `StoreFixtures` public and write `ShopFixture`**

In `src/test/java/com/graphify/store/StoreFixtures.java`, change `final class StoreFixtures` to `public final class StoreFixtures`. Make `cleanIndexTables`, `newRepository` and `symbol` `public static`.

`src/test/java/com/graphify/testsupport/ShopFixture.java`:

```java
package com.graphify.testsupport;

import com.graphify.indexer.IndexRequest;
import com.graphify.indexer.IndexerOptions;
import com.graphify.indexer.JavaRepositoryIndexer;
import com.graphify.indexer.ModuleSource;
import com.graphify.indexer.TestJars;
import com.graphify.indexer.model.IndexResult;
import com.graphify.store.ClasspathMode;
import com.graphify.store.ModuleRecord;
import com.graphify.store.RepositoryIndex;
import com.graphify.store.RepositoryIndexWriter;
import com.graphify.store.StoreFixtures;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Indexes {@code fixtures/shop} into Oracle as two repositories:
 * <ul>
 *   <li>{@code shop-lib} (module {@code shop-lib}, from source)</li>
 *   <li>{@code shop-api} (module {@code shop-api} with shop-lib's jar on its classpath, and module
 *       {@code shop-legacy} with no classpath)</li>
 * </ul>
 */
public final class ShopFixture {

    public static final String LIB_REPO = "shop-lib";
    public static final String API_REPO = "shop-api";

    private ShopFixture() {
    }

    public static void load(JdbcTemplate jdbc, RepositoryIndexWriter writer, Path workDir) throws IOException {
        StoreFixtures.cleanIndexTables(jdbc);
        Path root = root();
        Path libSources = root.resolve("shop-lib/src/main/java");
        Path libJar = TestJars.jar(workDir, "shop-lib", sources(libSources), Set.of());
        long libRepo = StoreFixtures.newRepository(jdbc, LIB_REPO);
        long apiRepo = StoreFixtures.newRepository(jdbc, API_REPO);

        writer.replace(new RepositoryIndex(libRepo, "lib-1",
                List.of(new ModuleRecord("shop-lib", "com.shop", "shop-lib", "1.0.0", ClasspathMode.FULL)),
                index(root, new ModuleSource("shop-lib", List.of(libSources), List.of()))));
        writer.replace(new RepositoryIndex(apiRepo, "api-1",
                List.of(new ModuleRecord("shop-api", "com.shop", "shop-api", "1.0.0", ClasspathMode.FULL),
                        new ModuleRecord("shop-legacy", "com.shop", "shop-legacy", "1.0.0", ClasspathMode.NONE)),
                index(root,
                        new ModuleSource("shop-api", List.of(root.resolve("shop-api/src/main/java")), List.of(libJar)),
                        new ModuleSource("shop-legacy", List.of(root.resolve("shop-legacy/src/main/java")),
                                List.of()))));
    }

    public static long symbolId(JdbcTemplate jdbc, String key) {
        return jdbc.queryForObject("SELECT id FROM symbol WHERE symbol_key = ?", Long.class, key);
    }

    private static IndexResult index(Path root, ModuleSource... modules) {
        return new JavaRepositoryIndexer().index(new IndexRequest(root, List.of(modules), new IndexerOptions(50, 300)));
    }

    private static Path root() {
        try {
            return Path.of(ShopFixture.class.getResource("/fixtures/shop").toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String> sources(Path root) throws IOException {
        Map<String, String> sources = new HashMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                sources.put(root.relativize(file).toString().replace('\\', '/'), Files.readString(file));
            }
        }
        return sources;
    }
}
```

- [ ] **Step 8: Write the fixture test**

`src/test/java/com/graphify/store/ShopFixtureTest.java`:

```java
package com.graphify.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.testsupport.ShopFixture;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

/** Pins the facts about the shop fixture that the search and impact tests rely on. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopFixtureTest extends OracleIntegrationTest {

    @Autowired
    RepositoryIndexWriter writer;

    @TempDir
    static Path work;

    @BeforeAll
    void load() throws Exception {
        ShopFixture.load(jdbc, writer, work);
    }

    private List<Map<String, Object>> edges(String from, String kind, String to) {
        return jdbc.queryForList("""
                SELECT u.confidence, u.snippet
                  FROM usage u JOIN symbol f ON f.id = u.from_symbol_id JOIN symbol t ON t.id = u.to_symbol_id
                 WHERE f.symbol_key = ? AND u.kind = ? AND t.symbol_key = ?
                """, from, kind, to);
    }

    @Test
    void containsTheEdgesTheImpactScenariosNeed() {
        assertThat(edges("com.shop.api.CheckoutService#label(int)", "CALL", "com.shop.lib.PriceFormatter#format(int)"))
                .singleElement().satisfies(e -> assertThat(e).containsEntry("CONFIDENCE", "EXACT"));
        assertThat(edges("com.shop.legacy.LegacyReport#print()", "CALL", "com.shop.lib.PriceFormatter#format/1"))
                .singleElement().satisfies(e -> assertThat(e).containsEntry("CONFIDENCE", "NAME_ONLY"));
        assertThat(edges("com.shop.legacy.LegacyReport#build(com.vendor.Client$Builder)", "CALL",
                "com.vendor.Client$Builder#build/0")).singleElement()
                .satisfies(e -> assertThat(e).containsEntry("CONFIDENCE", "NAME_ONLY"));
        assertThat(edges("com.shop.api.CardGateway#charge(int)", "OVERRIDES", "com.shop.lib.PaymentGateway#charge(int)"))
                .hasSize(1);
        assertThat(edges("com.shop.api.CheckoutService#checkout(int)", "CALL", "com.shop.lib.PaymentGateway#charge(int)"))
                .hasSize(1);
        assertThat(edges("com.shop.api.OrderController#checkout()", "ANNOTATION",
                "org.springframework.web.bind.annotation.PostMapping")).singleElement().satisfies(e -> assertThat(e)
                .containsEntry("SNIPPET", "@PostMapping(value = \"/checkout\", produces = \"application/json\")"));
        assertThat(edges("com.shop.api.OrderController", "ANNOTATION",
                "org.springframework.web.bind.annotation.RequestMapping")).singleElement()
                .satisfies(e -> assertThat(e).containsEntry("SNIPPET", "@RequestMapping(\"/orders\")"));
    }

    @Test
    void sourceDeclarationsWinOverTheJarCopies() {
        assertThat(jdbc.queryForObject("SELECT origin FROM symbol WHERE symbol_key = 'com.shop.lib.PriceFormatter#format(int)'",
                String.class)).isEqualTo("SOURCE");
        assertThat(jdbc.queryForList("SELECT m.path || ':' || m.classpath_mode FROM maven_module m ORDER BY m.path",
                String.class)).containsExactly("shop-api:FULL", "shop-legacy:NONE", "shop-lib:FULL");
    }
}
```

- [ ] **Step 9: Run the tests**

Run: `./mvnw test -Dtest='ShopFixtureTest,SchemaMigrationTest,NameOnlyResolverTest'`
Expected: all pass.

If a fixture edge is missing, read the actual rows before changing anything. Example query: `SELECT f.symbol_key, u.kind, t.symbol_key FROM usage u JOIN symbol f … WHERE f.symbol_key LIKE 'com.shop.legacy%'`. Fix the indexer only if the indexer is wrong. The fixture text is part of the contract and must not change.

Run: `./mvnw test`
Expected: all tests pass.

- [ ] **Step 10: Commit**

```bash
git add src/main src/test docs
git commit -m "feat(search): add search/impact schema, nested name-only receivers and the shop fixture" -m "<your harness Co-Authored-By trailer>"
```

---

### Task 2: API foundation and repository endpoints

**Files:**
- Modify: `pom.xml`, `src/main/resources/application.yml`, `src/test/java/com/graphify/OracleIntegrationTest.java`, `src/main/java/com/graphify/store/Chunks.java`
- Create: `src/main/java/com/graphify/common/exception/NotFoundException.java`, `ApiExceptionHandler.java`
- Create: `src/main/java/com/graphify/api/Page.java`, `Paging.java`, `PagingResolver.java`
- Create: `src/main/java/com/graphify/repository/RepositorySummary.java`, `ModuleView.java`, `RepositoryDetail.java`, `RepositoryQueries.java`, `RepositoryController.java`
- Test: `src/test/java/com/graphify/repository/RepositoryApiTest.java`

**Interfaces:**
- Consumes `AppSettings.getInt(SettingKeys.API_PAGE_DEFAULT_SIZE / API_PAGE_MAX_SIZE)`.
- Produces:

**Common and paging**

- `public class NotFoundException extends RuntimeException` (`NotFoundException(String message)`), which maps to 404.
- `IllegalArgumentException` maps to 400. Both responses are `ProblemDetail`.
- `public record Page<T>(List<T> items, int page, int size, long total)`.
- `public record Paging(int page, int size)` with `long offset()`.
- `public class PagingResolver` (a `@Component`) with `Paging resolve(Integer page, Integer size)`.

**Chunks**

- `public final class Chunks` with:
  - `public static <T> List<List<T>> of(List<T> items, int size)`
  - `public static String placeholders(int count)`
  - `public static final int MAX_IN_LIST = 1000`

**Repository endpoints**

- `GET /api/v1/repositories?q=&page=&size=` returns `Page<RepositorySummary>`.
- `GET /api/v1/repositories/{id}` returns `RepositoryDetail`, or 404.

**Test base**

- `OracleIntegrationTest` now has `@AutoConfigureMockMvc` and `@Autowired protected MockMvcTester mvc`.

Verified in a spike:
- `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc` and `org.springframework.test.web.servlet.assertj.MockMvcTester` work with Boot 4.1.1.
- `springdoc.api-docs.path: /api/v1/openapi.json` serves the document.
- A `ProblemDetail` returned from an `@ExceptionHandler` is rendered as `application/problem+json`.

- [ ] **Step 1: Add the dependencies and configuration**

In `pom.xml`, add `<springdoc.version>3.1.1</springdoc.version>` to `<properties>`. Add these dependencies after `spring-boot-starter-flyway`:

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-webmvc</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springdoc</groupId>
			<artifactId>springdoc-openapi-starter-webmvc-api</artifactId>
			<version>${springdoc.version}</version>
		</dependency>
```

and this one after `spring-boot-starter-test`:

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-webmvc-test</artifactId>
			<scope>test</scope>
		</dependency>
```

In `src/main/resources/application.yml`, add under the existing `spring:` key, at the same level as `datasource:`:

```yaml
  mvc:
    problemdetails:
      enabled: true
```

and add a new top-level key:

```yaml
springdoc:
  api-docs:
    path: /api/v1/openapi.json
```

Replace `src/test/java/com/graphify/OracleIntegrationTest.java` with:

```java
package com.graphify;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** Base for tests against the real Oracle schema and MVC layer. All subclasses share one context and container. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class OracleIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MockMvcTester mvc;
}
```

In `src/main/java/com/graphify/store/Chunks.java`, make the class and `of` `public`, and add the following inside the class:

```java
    /** Oracle rejects IN lists longer than 1000 items (ORA-01795). */
    public static final int MAX_IN_LIST = 1000;

    /** {@code "?,?,?"} for {@code count} bind parameters. */
    public static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }
```

In `src/main/java/com/graphify/store/SymbolWriter.java`, delete its private `MAX_IN_LIST` constant and its comment. Use `Chunks.MAX_IN_LIST` and `Chunks.placeholders(chunk.size())` in `ids(...)`, and drop the now-unused `java.util.Collections` import.

Run: `./mvnw test`
Expected: all tests still pass. This proves the shared context with MockMvc starts.

- [ ] **Step 2: Write the failing test**

`src/test/java/com/graphify/repository/RepositoryApiTest.java`:

```java
package com.graphify.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.store.StoreFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RepositoryApiTest extends OracleIntegrationTest {

    private long alpha;

    @BeforeEach
    void setUp() {
        StoreFixtures.cleanIndexTables(jdbc);
        alpha = StoreFixtures.newRepository(jdbc, "alpha-service");
        StoreFixtures.newRepository(jdbc, "beta_service");
        jdbc.update("INSERT INTO maven_module (repo_id, path, classpath_mode) VALUES (?, 'core', 'FULL')", alpha);
        jdbc.update("UPDATE scm_repository SET last_indexed_commit = 'abc123', last_indexed_at = SYSTIMESTAMP "
                + "WHERE id = ?", alpha);
    }

    @Test
    void listsRepositoriesWithModuleCountsInStableOrder() {
        assertThat(mvc.get().uri("/api/v1/repositories")).hasStatusOk().bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.total").isEqualTo(2);
                    assertThat(json).extractingPath("$.items[0].slug").isEqualTo("alpha-service");
                    assertThat(json).extractingPath("$.items[0].moduleCount").isEqualTo(1);
                    assertThat(json).extractingPath("$.items[0].lastIndexedCommit").isEqualTo("abc123");
                    assertThat(json).extractingPath("$.items[1].slug").isEqualTo("beta_service");
                    assertThat(json).extractingPath("$.size").isEqualTo(50);
                });
    }

    @Test
    void filtersCaseInsensitivelyByText() {
        assertThat(mvc.get().uri("/api/v1/repositories?q=ALPHA")).hasStatusOk().bodyJson()
                .extractingPath("$.items[*].slug").asArray().containsExactly("alpha-service");
    }

    @Test
    void filterTreatsWildcardsLiterally() {
        assertThat(mvc.get().uri("/api/v1/repositories?q=_")).hasStatusOk().bodyJson()
                .extractingPath("$.items[*].slug").asArray().containsExactly("beta_service");
        assertThat(mvc.get().uri("/api/v1/repositories?q=%25")).hasStatusOk().bodyJson()
                .extractingPath("$.total").isEqualTo(0);
        assertThat(mvc.get().uri("/api/v1/repositories?q='")).hasStatusOk().bodyJson()
                .extractingPath("$.total").isEqualTo(0);
    }

    @Test
    void returnsDetailWithModulesOr404() {
        assertThat(mvc.get().uri("/api/v1/repositories/" + alpha)).hasStatusOk().bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.repository.slug").isEqualTo("alpha-service");
                    assertThat(json).extractingPath("$.modules[0].path").isEqualTo("core");
                    assertThat(json).extractingPath("$.modules[0].classpathMode").isEqualTo("FULL");
                });
        assertThat(mvc.get().uri("/api/v1/repositories/-1")).hasStatus(404).bodyJson()
                .extractingPath("$.title").isEqualTo("Not found");
    }

    @Test
    void rejectsInvalidPagingAsProblemDetail() {
        assertThat(mvc.get().uri("/api/v1/repositories?size=100000")).hasStatus(400).bodyJson()
                .extractingPath("$.detail").asString().contains("500");
        assertThat(mvc.get().uri("/api/v1/repositories?page=-1")).hasStatus(400);
        assertThat(mvc.get().uri("/api/v1/repositories?size=abc")).hasStatus(400);
    }

    @Test
    void publishesTheOpenApiDocument() {
        assertThat(mvc.get().uri("/api/v1/openapi.json")).hasStatusOk().bodyJson()
                .extractingPath("$.paths").asMap().containsKey("/api/v1/repositories");
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `./mvnw test -Dtest=RepositoryApiTest`
Expected: tests fail with 404 for `/api/v1/repositories`, because no controller exists yet. `publishesTheOpenApiDocument` fails on the missing path.

- [ ] **Step 4: Write the common error handling and paging**

`src/main/java/com/graphify/common/exception/NotFoundException.java`:

```java
package com.graphify.common.exception;

/** The requested resource does not exist; rendered as HTTP 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
```

`src/main/java/com/graphify/common/exception/ApiExceptionHandler.java`:

```java
package com.graphify.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps domain exceptions to RFC 7807 responses (spec §10.1). Spring's own errors use spring.mvc.problemdetails. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Not found");
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid request");
        return problem;
    }
}
```

`src/main/java/com/graphify/api/Page.java`:

```java
package com.graphify.api;

import java.util.List;

public record Page<T>(List<T> items, int page, int size, long total) {

    public Page {
        items = List.copyOf(items);
    }
}
```

`src/main/java/com/graphify/api/Paging.java`:

```java
package com.graphify.api;

public record Paging(int page, int size) {

    public long offset() {
        return (long) page * size;
    }
}
```

`src/main/java/com/graphify/api/PagingResolver.java`:

```java
package com.graphify.api;

import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import org.springframework.stereotype.Component;

/** Applies api.page_default_size / api.page_max_size (spec §10.1); out-of-range values are a 400. */
@Component
public class PagingResolver {

    private final AppSettings settings;

    public PagingResolver(AppSettings settings) {
        this.settings = settings;
    }

    public Paging resolve(Integer page, Integer size) {
        int resolvedPage = page == null ? 0 : page;
        int maxSize = settings.getInt(SettingKeys.API_PAGE_MAX_SIZE);
        int resolvedSize = size == null ? settings.getInt(SettingKeys.API_PAGE_DEFAULT_SIZE) : size;
        if (resolvedPage < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        if (resolvedSize < 1 || resolvedSize > maxSize) {
            throw new IllegalArgumentException("size must be between 1 and " + maxSize);
        }
        return new Paging(resolvedPage, resolvedSize);
    }
}
```

- [ ] **Step 5: Write the repository queries and controller**

`src/main/java/com/graphify/repository/RepositorySummary.java`:

```java
package com.graphify.repository;

import java.time.Instant;

public record RepositorySummary(
        long id,
        String projectKey,
        String slug,
        String defaultBranch,
        String lastIndexedCommit,
        Instant lastIndexedAt,
        String lastStatus,
        boolean active,
        int moduleCount) {
}
```

`src/main/java/com/graphify/repository/ModuleView.java`:

```java
package com.graphify.repository;

public record ModuleView(long id, String path, String groupId, String artifactId, String version, String classpathMode) {
}
```

`src/main/java/com/graphify/repository/RepositoryDetail.java`:

```java
package com.graphify.repository;

import java.util.List;

public record RepositoryDetail(RepositorySummary repository, List<ModuleView> modules) {
}
```

`src/main/java/com/graphify/repository/RepositoryQueries.java`:

```java
package com.graphify.repository;

import com.graphify.api.Page;
import com.graphify.api.Paging;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RepositoryQueries {

    private static final String SELECT = """
            SELECT r.id, r.project_key, r.slug, r.default_branch, r.last_indexed_commit, r.last_indexed_at,
                   r.last_status, r.active,
                   (SELECT COUNT(*) FROM maven_module m WHERE m.repo_id = r.id) AS module_count
              FROM scm_repository r
            """;

    private static final String FILTER =
            " WHERE UPPER(r.slug) LIKE ? ESCAPE '\\' OR UPPER(r.project_key) LIKE ? ESCAPE '\\'";

    private final JdbcTemplate jdbc;

    public RepositoryQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Page<RepositorySummary> list(String text, Paging paging) {
        String pattern = likePattern(text);
        List<RepositorySummary> items = jdbc.query(SELECT + FILTER
                        + " ORDER BY r.project_key, r.slug OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                (rs, row) -> summary(rs), pattern, pattern, paging.offset(), paging.size());
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM scm_repository r" + FILTER, Long.class, pattern,
                pattern);
        return new Page<>(items, paging.page(), paging.size(), total == null ? 0 : total);
    }

    public Optional<RepositoryDetail> find(long id) {
        List<RepositorySummary> found = jdbc.query(SELECT + " WHERE r.id = ?", (rs, row) -> summary(rs), id);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        List<ModuleView> modules = jdbc.query("""
                SELECT id, path, group_id, artifact_id, version, classpath_mode
                  FROM maven_module WHERE repo_id = ? ORDER BY path
                """, (rs, row) -> new ModuleView(rs.getLong("id"), rs.getString("path"), rs.getString("group_id"),
                rs.getString("artifact_id"), rs.getString("version"), rs.getString("classpath_mode")), id);
        return Optional.of(new RepositoryDetail(found.getFirst(), modules));
    }

    /** Upper-cased {@code %text%} with LIKE metacharacters escaped, so user text matches literally. */
    private static String likePattern(String text) {
        if (text == null || text.isBlank()) {
            return "%";
        }
        String escaped = text.strip().toUpperCase(Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private static RepositorySummary summary(ResultSet rs) throws SQLException {
        OffsetDateTime indexedAt = rs.getObject("last_indexed_at", OffsetDateTime.class);
        return new RepositorySummary(
                rs.getLong("id"),
                rs.getString("project_key"),
                rs.getString("slug"),
                rs.getString("default_branch"),
                rs.getString("last_indexed_commit"),
                indexedAt == null ? null : indexedAt.toInstant(),
                rs.getString("last_status"),
                rs.getInt("active") == 1,
                rs.getInt("module_count"));
    }
}
```

`src/main/java/com/graphify/repository/RepositoryController.java`:

```java
package com.graphify.repository;

import com.graphify.api.Page;
import com.graphify.api.PagingResolver;
import com.graphify.common.exception.NotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/repositories")
public class RepositoryController {

    private final RepositoryQueries queries;
    private final PagingResolver paging;

    public RepositoryController(RepositoryQueries queries, PagingResolver paging) {
        this.queries = queries;
        this.paging = paging;
    }

    @GetMapping
    public Page<RepositorySummary> list(@RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return queries.list(q, paging.resolve(page, size));
    }

    @GetMapping("/{id}")
    public RepositoryDetail get(@PathVariable long id) {
        return queries.find(id).orElseThrow(() -> new NotFoundException("No repository with id " + id));
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=RepositoryApiTest`
Expected: 6 tests pass.

The `size=abc` case relies on `spring.mvc.problemdetails.enabled`. Spring raises `MethodArgumentTypeMismatchException`, which renders as a 400 `ProblemDetail`.

Run: `./mvnw test`
Expected: all tests pass.

- [ ] **Step 7: Commit**

```bash
git add pom.xml src/main src/test
git commit -m "feat(api): add REST foundation with problem details, paging and repository endpoints" -m "<your harness Co-Authored-By trailer>"
```

---

### Task 3: Symbol search

**Files:**
- Create: `src/main/java/com/graphify/search/SymbolQuery.java`, `SymbolHit.java`, `SymbolSearch.java`, `SymbolController.java`
- Test: `src/test/java/com/graphify/search/SymbolQueryTest.java`, `SymbolSearchTest.java`, `SymbolApiTest.java`

**Interfaces:**
- Consumes the `search_class`/`search_member` columns (Task 1), `ShopFixture`, `Paging`/`Page`/`PagingResolver` (Task 2), `Chunks`.
- Produces:
  - `public record SymbolQuery(String qualifiedClass, String simpleClass, String member)` with `static SymbolQuery parse(String text)`. It throws `IllegalArgumentException` for blank or meaningless text.
  - `public record SymbolHit(long id, String key, SymbolKind kind, String display, SymbolOrigin origin, boolean nameOnly, long usageCount, int repositoryCount)`.
  - `public class SymbolSearch` (a `@Repository`) with `Page<SymbolHit> search(String text, SymbolKind kind, String repository, Paging paging)`.
  - `public class SymbolController` (a `@RestController`) on `/api/v1/symbols` with `GET /search?q=&kind=&repo=&page=&size=`. Task 4 adds more endpoints to the same class.

Query rules:
- A `(...)` suffix is ignored.
- `A#b` means class `A`, member `b`.
- With no `#`, a last dotted segment that starts lowercase is a member (`RestTemplate.exchange`). Otherwise the whole text is a class.
- A dotted class (`org.x.RestTemplate`) matches the FQN exactly, with `$` written as `.`.
- Matching is case-insensitive.
- With no member and no kind filter, only types are returned.
- Order: SOURCE before BINARY, bound before name-only, more usages first, then key.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/graphify/search/SymbolQueryTest.java`:

```java
package com.graphify.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class SymbolQueryTest {

    @Test
    void parsesTheFormsPeopleType() {
        assertThat(SymbolQuery.parse("RestTemplate.exchange"))
                .isEqualTo(new SymbolQuery(null, "RESTTEMPLATE", "EXCHANGE"));
        assertThat(SymbolQuery.parse("RestTemplate#exchange(java.lang.String, java.lang.Class)"))
                .isEqualTo(new SymbolQuery(null, "RESTTEMPLATE", "EXCHANGE"));
        assertThat(SymbolQuery.parse("  resttemplate "))
                .isEqualTo(new SymbolQuery(null, "RESTTEMPLATE", null));
        assertThat(SymbolQuery.parse("org.springframework.web.client.RestTemplate"))
                .isEqualTo(new SymbolQuery("org.springframework.web.client.RestTemplate", "RESTTEMPLATE", null));
        assertThat(SymbolQuery.parse("com.corp.Outer$Inner#run"))
                .isEqualTo(new SymbolQuery("com.corp.Outer.Inner", "INNER", "RUN"));
        assertThat(SymbolQuery.parse("exchange")).isEqualTo(new SymbolQuery(null, null, "EXCHANGE"));
        assertThat(SymbolQuery.parse("PriceFormatter#<init>")).isEqualTo(new SymbolQuery(null, "PRICEFORMATTER", "<INIT>"));
    }

    @Test
    void rejectsTextWithNothingToMatch() {
        assertThatIllegalArgumentException().isThrownBy(() -> SymbolQuery.parse("   "));
        assertThatIllegalArgumentException().isThrownBy(() -> SymbolQuery.parse(null));
        assertThatIllegalArgumentException().isThrownBy(() -> SymbolQuery.parse("#"));
        assertThatIllegalArgumentException().isThrownBy(() -> SymbolQuery.parse("(int)"));
    }
}
```

`src/test/java/com/graphify/search/SymbolSearchTest.java`:

```java
package com.graphify.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.graphify.OracleIntegrationTest;
import com.graphify.api.Page;
import com.graphify.api.Paging;
import com.graphify.indexer.model.SymbolKind;
import com.graphify.store.RepositoryIndexWriter;
import com.graphify.testsupport.ShopFixture;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SymbolSearchTest extends OracleIntegrationTest {

    private static final Paging FIRST_PAGE = new Paging(0, 50);

    @Autowired
    RepositoryIndexWriter writer;

    @Autowired
    SymbolSearch search;

    @TempDir
    static Path work;

    @BeforeAll
    void load() throws Exception {
        ShopFixture.load(jdbc, writer, work);
    }

    @Test
    void classAndMemberFindOverloadsBeforeTheNameOnlyGuess() {
        Page<SymbolHit> page = search.search("PriceFormatter.format", null, null, FIRST_PAGE);

        assertThat(page.items()).extracting(SymbolHit::key, SymbolHit::usageCount, SymbolHit::repositoryCount)
                .containsExactly(
                        tuple("com.shop.lib.PriceFormatter#format(int)", 1L, 1),
                        tuple("com.shop.lib.PriceFormatter#format(java.lang.String)", 0L, 0),
                        tuple("com.shop.lib.PriceFormatter#format/1", 1L, 1));
        assertThat(page.items().getLast().nameOnly()).isTrue();
        assertThat(page.total()).isEqualTo(3);
    }

    @Test
    void typeSearchIsCaseInsensitiveAndReturnsOnlyTypes() {
        assertThat(search.search("priceFORMATTER", null, null, FIRST_PAGE).items())
                .extracting(SymbolHit::key, SymbolHit::kind)
                .containsExactly(tuple("com.shop.lib.PriceFormatter", SymbolKind.CLASS));
    }

    @Test
    void qualifiedNamesAndSignaturesNarrowTheMatch() {
        assertThat(search.search("com.shop.lib.PriceFormatter#format(int)", null, null, FIRST_PAGE).total())
                .isEqualTo(3);
        assertThat(search.search("com.other.PriceFormatter#format", null, null, FIRST_PAGE).total()).isZero();
    }

    @Test
    void memberSearchSpansClassesOrderedByUsage() {
        assertThat(search.search("charge", null, null, FIRST_PAGE).items()).extracting(SymbolHit::key)
                .containsExactly("com.shop.lib.PaymentGateway#charge(int)", "com.shop.api.CardGateway#charge(int)");
    }

    @Test
    void kindAndRepositoryFiltersApply() {
        assertThat(search.search("PriceFormatter", SymbolKind.CONSTRUCTOR, null, FIRST_PAGE).items())
                .extracting(SymbolHit::key)
                .containsExactly("com.shop.lib.PriceFormatter#<init>()", "com.shop.lib.PriceFormatter#<init>/0");
        assertThat(search.search("PriceFormatter.format", null, ShopFixture.LIB_REPO, FIRST_PAGE).items())
                .extracting(SymbolHit::key)
                .containsExactly("com.shop.lib.PriceFormatter#format(int)",
                        "com.shop.lib.PriceFormatter#format(java.lang.String)");
    }

    @Test
    void pagesThroughResults() {
        Page<SymbolHit> second = search.search("PriceFormatter.format", null, null, new Paging(1, 1));

        assertThat(second.items()).extracting(SymbolHit::key)
                .containsExactly("com.shop.lib.PriceFormatter#format(java.lang.String)");
        assertThat(second.total()).isEqualTo(3);
        assertThat(second.page()).isEqualTo(1);
    }

    @Test
    void metacharactersAreLiteral() {
        assertThat(search.search("%", null, null, FIRST_PAGE).total()).isZero();
        assertThat(search.search("Price_ormatter", null, null, FIRST_PAGE).total()).isZero();
        assertThat(search.search("x' OR '1'='1", null, null, FIRST_PAGE).total()).isZero();
    }
}
```

`src/test/java/com/graphify/search/SymbolApiTest.java`:

```java
package com.graphify.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.store.RepositoryIndexWriter;
import com.graphify.testsupport.ShopFixture;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SymbolApiTest extends OracleIntegrationTest {

    @Autowired
    RepositoryIndexWriter writer;

    @TempDir
    static Path work;

    @BeforeAll
    void load() throws Exception {
        ShopFixture.load(jdbc, writer, work);
    }

    @Test
    void searchReturnsAPage() {
        assertThat(mvc.get().uri("/api/v1/symbols/search?q=PriceFormatter.format&size=2")).hasStatusOk().bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.total").isEqualTo(3);
                    assertThat(json).extractingPath("$.items.length()").isEqualTo(2);
                    assertThat(json).extractingPath("$.items[0].key").isEqualTo("com.shop.lib.PriceFormatter#format(int)");
                    assertThat(json).extractingPath("$.items[0].origin").isEqualTo("SOURCE");
                });
    }

    @Test
    void invalidSearchInputIsA400ProblemDetail() {
        assertThat(mvc.get().uri("/api/v1/symbols/search")).hasStatus(400);
        assertThat(mvc.get().uri("/api/v1/symbols/search?q=%20")).hasStatus(400).bodyJson()
                .extractingPath("$.title").isEqualTo("Invalid request");
        assertThat(mvc.get().uri("/api/v1/symbols/search?q=x&kind=WIDGET")).hasStatus(400);
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -Dtest='SymbolQueryTest,SymbolSearchTest,SymbolApiTest'`
Expected: BUILD FAILURE, `cannot find symbol` for `SymbolQuery`, `SymbolSearch` and `SymbolHit`.

- [ ] **Step 3: Write `SymbolQuery` and `SymbolHit`**

`src/main/java/com/graphify/search/SymbolQuery.java`:

```java
package com.graphify.search;

import java.util.Locale;

/**
 * What the user typed, split into an optional exact class FQN (with {@code $} written as {@code .}), an upper-cased
 * simple class name and an upper-cased member name. Matching is case-insensitive because people rarely type the
 * exact case of a class they are looking for.
 */
public record SymbolQuery(String qualifiedClass, String simpleClass, String member) {

    public static SymbolQuery parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("q must not be blank");
        }
        String query = text.strip();
        int paren = query.indexOf('(');
        if (paren >= 0) {
            query = query.substring(0, paren).strip();
        }
        String classPart;
        String member;
        int hash = query.indexOf('#');
        if (hash >= 0) {
            classPart = query.substring(0, hash);
            member = query.substring(hash + 1);
        } else {
            int dot = query.lastIndexOf('.');
            String last = query.substring(dot + 1);
            if (!last.isEmpty() && Character.isLowerCase(last.charAt(0))) {
                classPart = dot < 0 ? "" : query.substring(0, dot);
                member = last;
            } else {
                classPart = query;
                member = "";
            }
        }
        classPart = classPart.strip().replace('$', '.');
        member = member.strip();
        if (classPart.isEmpty() && member.isEmpty()) {
            throw new IllegalArgumentException("q names no class or member: " + text);
        }
        String simple = classPart.isEmpty() ? null
                : classPart.substring(classPart.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT);
        String qualified = classPart.contains(".") ? classPart : null;
        return new SymbolQuery(qualified, simple, member.isEmpty() ? null : member.toUpperCase(Locale.ROOT));
    }
}
```

`src/main/java/com/graphify/search/SymbolHit.java`:

```java
package com.graphify.search;

import com.graphify.indexer.model.SymbolKind;
import com.graphify.indexer.model.SymbolOrigin;

/** A search candidate; {@code repositoryCount} answers "in how many projects is it used" (spec §1.2). */
public record SymbolHit(
        long id,
        String key,
        SymbolKind kind,
        String display,
        SymbolOrigin origin,
        boolean nameOnly,
        long usageCount,
        int repositoryCount) {
}
```

- [ ] **Step 4: Write `SymbolSearch`**

`src/main/java/com/graphify/search/SymbolSearch.java`:

```java
package com.graphify.search;

import com.graphify.api.Page;
import com.graphify.api.Paging;
import com.graphify.indexer.model.SymbolKind;
import com.graphify.indexer.model.SymbolOrigin;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Case-insensitive symbol search over the {@code search_class}/{@code search_member} virtual columns. */
@Repository
public class SymbolSearch {

    private static final String TYPE_KINDS = "('CLASS', 'INTERFACE', 'ENUM', 'RECORD', 'ANNOTATION_TYPE')";

    private final JdbcTemplate jdbc;

    public SymbolSearch(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Page<SymbolHit> search(String text, SymbolKind kind, String repository, Paging paging) {
        SymbolQuery query = SymbolQuery.parse(text);
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (query.simpleClass() != null) {
            where.append(" AND s.search_class = ?");
            args.add(query.simpleClass());
        }
        if (query.qualifiedClass() != null) {
            where.append(" AND REPLACE(s.class_fqn, '$', '.') = ?");
            args.add(query.qualifiedClass());
        }
        if (query.member() != null) {
            where.append(" AND s.search_member = ?");
            args.add(query.member());
        } else if (kind == null) {
            where.append(" AND s.kind IN ").append(TYPE_KINDS);
        }
        if (kind != null) {
            where.append(" AND s.kind = ?");
            args.add(kind.name());
        }
        if (repository != null && !repository.isBlank()) {
            where.append("""
                     AND (EXISTS (SELECT 1 FROM usage u JOIN maven_module m ON m.id = u.module_id
                                  JOIN scm_repository r ON r.id = m.repo_id
                                  WHERE u.to_symbol_id = s.id AND r.slug = ?)
                       OR EXISTS (SELECT 1 FROM symbol_declaration d JOIN maven_module m ON m.id = d.module_id
                                  JOIN scm_repository r ON r.id = m.repo_id
                                  WHERE d.symbol_id = s.id AND r.slug = ?))
                    """);
            args.add(repository.strip());
            args.add(repository.strip());
        }

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(paging.offset());
        pageArgs.add(paging.size());
        List<SymbolHit> items = jdbc.query("""
                SELECT s.id, s.symbol_key, s.kind, s.display_signature, s.origin, s.name_only,
                       (SELECT COUNT(*) FROM usage u WHERE u.to_symbol_id = s.id) AS usage_count,
                       (SELECT COUNT(DISTINCT m.repo_id) FROM usage u JOIN maven_module m ON m.id = u.module_id
                         WHERE u.to_symbol_id = s.id) AS repo_count
                  FROM symbol s
                """ + where + """
                 ORDER BY CASE s.origin WHEN 'SOURCE' THEN 0 ELSE 1 END, s.name_only, usage_count DESC, s.symbol_key
                 OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
                """, (rs, row) -> new SymbolHit(
                        rs.getLong("id"),
                        rs.getString("symbol_key"),
                        SymbolKind.valueOf(rs.getString("kind")),
                        rs.getString("display_signature"),
                        SymbolOrigin.valueOf(rs.getString("origin")),
                        rs.getInt("name_only") == 1,
                        rs.getLong("usage_count"),
                        rs.getInt("repo_count")),
                pageArgs.toArray());
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM symbol s" + where, Long.class, args.toArray());
        return new Page<>(items, paging.page(), paging.size(), total == null ? 0 : total);
    }
}
```

- [ ] **Step 5: Write the controller (search endpoint)**

`src/main/java/com/graphify/search/SymbolController.java`:

```java
package com.graphify.search;

import com.graphify.api.Page;
import com.graphify.api.PagingResolver;
import com.graphify.indexer.model.SymbolKind;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/symbols")
public class SymbolController {

    private final SymbolSearch search;
    private final PagingResolver paging;

    public SymbolController(SymbolSearch search, PagingResolver paging) {
        this.search = search;
        this.paging = paging;
    }

    @GetMapping("/search")
    public Page<SymbolHit> search(@RequestParam String q, @RequestParam(required = false) SymbolKind kind,
            @RequestParam(required = false) String repo, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return search.search(q, kind, repo, paging.resolve(page, size));
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw test -Dtest='SymbolQueryTest,SymbolSearchTest,SymbolApiTest'`
Expected: 11 tests pass.

If an expected order differs, print the actual hits and compare them with the ordering rule in the Interfaces block. Fix the SQL, not the expectation.

Run: `./mvnw test`
Expected: all tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/graphify/search src/test/java/com/graphify/search
git commit -m "feat(search): add case-insensitive symbol search endpoint" -m "<your harness Co-Authored-By trailer>"
```

---

### Task 4: Symbol detail, usage list and usage summary

**Files:**
- Create: `src/main/java/com/graphify/search/SymbolRef.java`, `DeclarationView.java`, `SymbolDetail.java`, `UsageView.java`, `UsageSummary.java`, `SymbolDetails.java`
- Modify: `src/main/java/com/graphify/search/SymbolController.java`
- Test: `src/test/java/com/graphify/search/SymbolDetailsTest.java`, `SymbolDetailApiTest.java`

**Interfaces:**
- Consumes `ShopFixture`, `Paging`/`Page`/`PagingResolver`, `NotFoundException` and `Chunks`.
- Produces:
  - `public record SymbolRef(long id, String key, SymbolKind kind, String display)`.
  - `public record DeclarationView(String repository, String modulePath, String filePath, int line)`.
  - `public record SymbolDetail(SymbolRef symbol, SymbolOrigin origin, boolean nameOnly, SymbolRef parent, List<DeclarationView> declarations, List<SymbolRef> members, List<SymbolRef> overrides, List<SymbolRef> overriddenBy, List<SymbolRef> supertypes, List<SymbolRef> subtypes)`.
  - `public record UsageView(long id, SymbolRef from, UsageKind kind, Confidence confidence, String repository, String modulePath, String filePath, int line, int column, String snippet)`.
  - `public record UsageSummary(long usages, int repositories, List<RepositoryUsage> byRepository)`. Its nested records are `RepositoryUsage(String repository, long usages, List<ModuleUsage> modules)`, `ModuleUsage(String modulePath, long usages, List<ClassUsage> classes)` and `ClassUsage(String classFqn, long usages)`.
  - `public class SymbolDetails` (a `@Repository`) with these methods:
    - `Optional<SymbolDetail> find(long id)`
    - `Page<UsageView> usages(long id, Set<Confidence> confidences, Set<UsageKind> kinds, String repository, Paging paging)`
    - `UsageSummary summary(long id)`
  - Endpoints:
    - `GET /api/v1/symbols/{id}`
    - `GET /api/v1/symbols/{id}/usages?confidence=&kind=&repo=&page=&size=`
    - `GET /api/v1/symbols/{id}/usages/summary`

    All three return 404 for an unknown id.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/graphify/search/SymbolDetailsTest.java`:

```java
package com.graphify.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.graphify.OracleIntegrationTest;
import com.graphify.api.Page;
import com.graphify.api.Paging;
import com.graphify.indexer.model.Confidence;
import com.graphify.indexer.model.UsageKind;
import com.graphify.store.RepositoryIndexWriter;
import com.graphify.testsupport.ShopFixture;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SymbolDetailsTest extends OracleIntegrationTest {

    private static final Paging FIRST_PAGE = new Paging(0, 50);

    @Autowired
    RepositoryIndexWriter writer;

    @Autowired
    SymbolDetails details;

    @TempDir
    static Path work;

    @BeforeAll
    void load() throws Exception {
        ShopFixture.load(jdbc, writer, work);
    }

    private long id(String key) {
        return ShopFixture.symbolId(jdbc, key);
    }

    @Test
    void methodDetailShowsParentDeclarationAndOverrides() {
        SymbolDetail charge = details.find(id("com.shop.lib.PaymentGateway#charge(int)")).orElseThrow();

        assertThat(charge.parent().key()).isEqualTo("com.shop.lib.PaymentGateway");
        assertThat(charge.declarations()).extracting(DeclarationView::repository, DeclarationView::modulePath,
                DeclarationView::filePath).containsExactly(tuple("shop-lib", "shop-lib",
                "shop-lib/src/main/java/com/shop/lib/PaymentGateway.java"));
        assertThat(charge.overriddenBy()).extracting(SymbolRef::key).containsExactly("com.shop.api.CardGateway#charge(int)");
        assertThat(charge.overrides()).isEmpty();
    }

    @Test
    void typeDetailShowsMembersAndHierarchy() {
        SymbolDetail gateway = details.find(id("com.shop.lib.PaymentGateway")).orElseThrow();
        SymbolDetail card = details.find(id("com.shop.api.CardGateway")).orElseThrow();

        assertThat(gateway.members()).extracting(SymbolRef::key).containsExactly("com.shop.lib.PaymentGateway#charge(int)");
        assertThat(gateway.subtypes()).extracting(SymbolRef::key).containsExactly("com.shop.api.CardGateway");
        assertThat(card.supertypes()).extracting(SymbolRef::key).containsExactly("com.shop.lib.PaymentGateway");
        assertThat(details.find(-1L)).isEmpty();
    }

    @Test
    void usagesAreFilteredAndPaged() {
        long format = id("com.shop.lib.PriceFormatter#format(int)");

        Page<UsageView> all = details.usages(format, Set.of(), Set.of(), null, FIRST_PAGE);
        assertThat(all.items()).singleElement().satisfies(u -> {
            assertThat(u.from().key()).isEqualTo("com.shop.api.CheckoutService#label(int)");
            assertThat(u.kind()).isEqualTo(UsageKind.CALL);
            assertThat(u.confidence()).isEqualTo(Confidence.EXACT);
            assertThat(u.repository()).isEqualTo("shop-api");
            assertThat(u.snippet()).isEqualTo("return formatter.format(cents);");
        });
        assertThat(details.usages(format, EnumSet.of(Confidence.NAME_ONLY), Set.of(), null, FIRST_PAGE).total()).isZero();
        assertThat(details.usages(format, Set.of(), Set.of(), "shop-lib", FIRST_PAGE).total()).isZero();
        long charge = id("com.shop.lib.PaymentGateway#charge(int)");
        assertThat(details.usages(charge, Set.of(), EnumSet.of(UsageKind.OVERRIDES), null, FIRST_PAGE).items())
                .extracting(u -> u.from().key()).containsExactly("com.shop.api.CardGateway#charge(int)");
    }

    @Test
    void summaryGroupsUsagesByRepositoryModuleAndClass() {
        UsageSummary summary = details.summary(id("com.shop.lib.PaymentGateway#charge(int)"));

        assertThat(summary.usages()).isEqualTo(2);
        assertThat(summary.repositories()).isEqualTo(1);
        assertThat(summary.byRepository()).singleElement().satisfies(repo -> {
            assertThat(repo.repository()).isEqualTo("shop-api");
            assertThat(repo.modules()).singleElement().satisfies(module -> {
                assertThat(module.modulePath()).isEqualTo("shop-api");
                assertThat(module.classes()).extracting(UsageSummary.ClassUsage::classFqn, UsageSummary.ClassUsage::usages)
                        .containsExactly(tuple("com.shop.api.CardGateway", 1L), tuple("com.shop.api.CheckoutService", 1L));
            });
        });
    }
}
```

`src/test/java/com/graphify/search/SymbolDetailApiTest.java`:

```java
package com.graphify.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.store.RepositoryIndexWriter;
import com.graphify.testsupport.ShopFixture;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SymbolDetailApiTest extends OracleIntegrationTest {

    @Autowired
    RepositoryIndexWriter writer;

    @TempDir
    static Path work;

    @BeforeAll
    void load() throws Exception {
        ShopFixture.load(jdbc, writer, work);
    }

    @Test
    void servesDetailUsagesAndSummary() {
        long format = ShopFixture.symbolId(jdbc, "com.shop.lib.PriceFormatter#format(int)");

        assertThat(mvc.get().uri("/api/v1/symbols/" + format)).hasStatusOk().bodyJson()
                .extractingPath("$.parent.key").isEqualTo("com.shop.lib.PriceFormatter");
        assertThat(mvc.get().uri("/api/v1/symbols/" + format + "/usages?confidence=EXACT,RECOVERED")).hasStatusOk()
                .bodyJson().extractingPath("$.items[0].from.key").isEqualTo("com.shop.api.CheckoutService#label(int)");
        assertThat(mvc.get().uri("/api/v1/symbols/" + format + "/usages/summary")).hasStatusOk().bodyJson()
                .extractingPath("$.byRepository[0].repository").isEqualTo("shop-api");
    }

    @Test
    void unknownIdsAndBadFiltersAreProblemDetails() {
        assertThat(mvc.get().uri("/api/v1/symbols/-1")).hasStatus(404);
        assertThat(mvc.get().uri("/api/v1/symbols/-1/usages")).hasStatus(404);
        assertThat(mvc.get().uri("/api/v1/symbols/-1/usages/summary")).hasStatus(404);
        long format = ShopFixture.symbolId(jdbc, "com.shop.lib.PriceFormatter#format(int)");
        assertThat(mvc.get().uri("/api/v1/symbols/" + format + "/usages?confidence=MAYBE")).hasStatus(400);
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -Dtest='SymbolDetailsTest,SymbolDetailApiTest'`
Expected: BUILD FAILURE, `cannot find symbol` for `SymbolDetails`, `SymbolDetail`, and the other new classes.

- [ ] **Step 3: Write the view records**

`src/main/java/com/graphify/search/SymbolRef.java`:

```java
package com.graphify.search;

import com.graphify.indexer.model.SymbolKind;

public record SymbolRef(long id, String key, SymbolKind kind, String display) {
}
```

`src/main/java/com/graphify/search/DeclarationView.java`:

```java
package com.graphify.search;

public record DeclarationView(String repository, String modulePath, String filePath, int line) {
}
```

`src/main/java/com/graphify/search/SymbolDetail.java`:

```java
package com.graphify.search;

import com.graphify.indexer.model.SymbolOrigin;
import java.util.List;

/** Everything shown on a symbol's page: where it is declared, what it contains and how it relates to other types. */
public record SymbolDetail(
        SymbolRef symbol,
        SymbolOrigin origin,
        boolean nameOnly,
        SymbolRef parent,
        List<DeclarationView> declarations,
        List<SymbolRef> members,
        List<SymbolRef> overrides,
        List<SymbolRef> overriddenBy,
        List<SymbolRef> supertypes,
        List<SymbolRef> subtypes) {
}
```

`src/main/java/com/graphify/search/UsageView.java`:

```java
package com.graphify.search;

import com.graphify.indexer.model.Confidence;
import com.graphify.indexer.model.UsageKind;

public record UsageView(
        long id,
        SymbolRef from,
        UsageKind kind,
        Confidence confidence,
        String repository,
        String modulePath,
        String filePath,
        int line,
        int column,
        String snippet) {
}
```

`src/main/java/com/graphify/search/UsageSummary.java`:

```java
package com.graphify.search;

import java.util.List;

/** Usages of one symbol grouped repository → module → using class (spec §10.3 "usages/summary"). */
public record UsageSummary(long usages, int repositories, List<RepositoryUsage> byRepository) {

    public record RepositoryUsage(String repository, long usages, List<ModuleUsage> modules) {
    }

    public record ModuleUsage(String modulePath, long usages, List<ClassUsage> classes) {
    }

    public record ClassUsage(String classFqn, long usages) {
    }
}
```

- [ ] **Step 4: Write `SymbolDetails`**

`src/main/java/com/graphify/search/SymbolDetails.java`:

```java
package com.graphify.search;

import com.graphify.api.Page;
import com.graphify.api.Paging;
import com.graphify.indexer.model.Confidence;
import com.graphify.indexer.model.SymbolKind;
import com.graphify.indexer.model.SymbolOrigin;
import com.graphify.indexer.model.UsageKind;
import com.graphify.store.Chunks;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SymbolDetails {

    private static final String REF_COLUMNS = "s.id, s.symbol_key, s.kind, s.display_signature";

    private final JdbcTemplate jdbc;

    public SymbolDetails(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<SymbolDetail> find(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT " + REF_COLUMNS + ", s.origin, s.name_only, s.parent_id FROM symbol s WHERE s.id = ?", id);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.getFirst();
        SymbolRef self = new SymbolRef(id, (String) row.get("SYMBOL_KEY"), SymbolKind.valueOf((String) row.get("KIND")),
                (String) row.get("DISPLAY_SIGNATURE"));
        Number parentId = (Number) row.get("PARENT_ID");
        SymbolRef parent = parentId == null ? null
                : refs("SELECT " + REF_COLUMNS + " FROM symbol s WHERE s.id = ?", parentId.longValue()).getFirst();
        List<DeclarationView> declarations = jdbc.query("""
                SELECT r.slug, m.path, d.file_path, d.line_no
                  FROM symbol_declaration d JOIN maven_module m ON m.id = d.module_id
                  JOIN scm_repository r ON r.id = m.repo_id
                 WHERE d.symbol_id = ? ORDER BY r.slug, m.path, d.file_path, d.line_no
                """, (rs, n) -> new DeclarationView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)), id);
        return Optional.of(new SymbolDetail(
                self,
                SymbolOrigin.valueOf((String) row.get("ORIGIN")),
                ((Number) row.get("NAME_ONLY")).intValue() == 1,
                parent,
                declarations,
                refs("SELECT " + REF_COLUMNS + " FROM symbol s WHERE s.parent_id = ? ORDER BY s.symbol_key", id),
                related("u.from_symbol_id = ? AND u.kind = 'OVERRIDES'", "u.to_symbol_id", id),
                related("u.to_symbol_id = ? AND u.kind = 'OVERRIDES'", "u.from_symbol_id", id),
                related("u.from_symbol_id = ? AND u.kind IN ('EXTENDS', 'IMPLEMENTS')", "u.to_symbol_id", id),
                related("u.to_symbol_id = ? AND u.kind IN ('EXTENDS', 'IMPLEMENTS')", "u.from_symbol_id", id)));
    }

    public Page<UsageView> usages(long id, Set<Confidence> confidences, Set<UsageKind> kinds, String repository,
            Paging paging) {
        StringBuilder where = new StringBuilder(" WHERE u.to_symbol_id = ?");
        List<Object> args = new ArrayList<>(List.of(id));
        if (confidences != null && !confidences.isEmpty()) {
            where.append(" AND u.confidence IN (").append(Chunks.placeholders(confidences.size())).append(')');
            confidences.forEach(c -> args.add(c.name()));
        }
        if (kinds != null && !kinds.isEmpty()) {
            where.append(" AND u.kind IN (").append(Chunks.placeholders(kinds.size())).append(')');
            kinds.forEach(k -> args.add(k.name()));
        }
        if (repository != null && !repository.isBlank()) {
            where.append(" AND r.slug = ?");
            args.add(repository.strip());
        }
        String from = """
                  FROM usage u JOIN symbol f ON f.id = u.from_symbol_id
                  JOIN maven_module m ON m.id = u.module_id JOIN scm_repository r ON r.id = m.repo_id
                """;
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(paging.offset());
        pageArgs.add(paging.size());
        List<UsageView> items = jdbc.query("""
                SELECT u.id, f.id AS from_id, f.symbol_key, f.kind AS from_kind, f.display_signature, u.kind,
                       u.confidence, r.slug, m.path, u.file_path, u.line_no, u.column_no, u.snippet
                """ + from + where + """
                 ORDER BY r.slug, m.path, u.file_path, u.line_no, u.column_no, u.id
                 OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
                """, (rs, n) -> new UsageView(
                        rs.getLong("id"),
                        new SymbolRef(rs.getLong("from_id"), rs.getString("symbol_key"),
                                SymbolKind.valueOf(rs.getString("from_kind")), rs.getString("display_signature")),
                        UsageKind.valueOf(rs.getString("kind")),
                        Confidence.valueOf(rs.getString("confidence")),
                        rs.getString("slug"),
                        rs.getString("path"),
                        rs.getString("file_path"),
                        rs.getInt("line_no"),
                        rs.getInt("column_no"),
                        rs.getString("snippet")),
                pageArgs.toArray());
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from + where, Long.class, args.toArray());
        return new Page<>(items, paging.page(), paging.size(), total == null ? 0 : total);
    }

    public UsageSummary summary(long id) {
        Map<String, Map<String, List<UsageSummary.ClassUsage>>> tree = new LinkedHashMap<>();
        jdbc.query("""
                SELECT r.slug, m.path, f.class_fqn, COUNT(*) AS usages
                  FROM usage u JOIN symbol f ON f.id = u.from_symbol_id
                  JOIN maven_module m ON m.id = u.module_id JOIN scm_repository r ON r.id = m.repo_id
                 WHERE u.to_symbol_id = ?
                 GROUP BY r.slug, m.path, f.class_fqn
                 ORDER BY r.slug, m.path, f.class_fqn
                """, rs -> {
                    tree.computeIfAbsent(rs.getString("slug"), k -> new LinkedHashMap<>())
                            .computeIfAbsent(rs.getString("path"), k -> new ArrayList<>())
                            .add(new UsageSummary.ClassUsage(rs.getString("class_fqn"), rs.getLong("usages")));
                }, id);
        List<UsageSummary.RepositoryUsage> repositories = new ArrayList<>();
        long total = 0;
        for (Map.Entry<String, Map<String, List<UsageSummary.ClassUsage>>> repo : tree.entrySet()) {
            List<UsageSummary.ModuleUsage> modules = new ArrayList<>();
            long repoTotal = 0;
            for (Map.Entry<String, List<UsageSummary.ClassUsage>> module : repo.getValue().entrySet()) {
                long moduleTotal = module.getValue().stream().mapToLong(UsageSummary.ClassUsage::usages).sum();
                modules.add(new UsageSummary.ModuleUsage(module.getKey(), moduleTotal, module.getValue()));
                repoTotal += moduleTotal;
            }
            repositories.add(new UsageSummary.RepositoryUsage(repo.getKey(), repoTotal, modules));
            total += repoTotal;
        }
        return new UsageSummary(total, repositories.size(), repositories);
    }

    private List<SymbolRef> related(String condition, String otherColumn, long id) {
        return refs("SELECT DISTINCT " + REF_COLUMNS + " FROM usage u JOIN symbol s ON s.id = " + otherColumn
                + " WHERE " + condition + " ORDER BY s.symbol_key", id);
    }

    private List<SymbolRef> refs(String sql, long id) {
        return jdbc.query(sql, (rs, n) -> new SymbolRef(rs.getLong("id"), rs.getString("symbol_key"),
                SymbolKind.valueOf(rs.getString("kind")), rs.getString("display_signature")), id);
    }
}
```

- [ ] **Step 5: Add the detail endpoints to the controller**

Replace `src/main/java/com/graphify/search/SymbolController.java` with:

```java
package com.graphify.search;

import com.graphify.api.Page;
import com.graphify.api.PagingResolver;
import com.graphify.common.exception.NotFoundException;
import com.graphify.indexer.model.Confidence;
import com.graphify.indexer.model.SymbolKind;
import com.graphify.indexer.model.UsageKind;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/symbols")
public class SymbolController {

    private final SymbolSearch search;
    private final SymbolDetails details;
    private final PagingResolver paging;

    public SymbolController(SymbolSearch search, SymbolDetails details, PagingResolver paging) {
        this.search = search;
        this.details = details;
        this.paging = paging;
    }

    @GetMapping("/search")
    public Page<SymbolHit> search(@RequestParam String q, @RequestParam(required = false) SymbolKind kind,
            @RequestParam(required = false) String repo, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return search.search(q, kind, repo, paging.resolve(page, size));
    }

    @GetMapping("/{id}")
    public SymbolDetail get(@PathVariable long id) {
        return details.find(id).orElseThrow(() -> notFound(id));
    }

    @GetMapping("/{id}/usages")
    public Page<UsageView> usages(@PathVariable long id, @RequestParam(required = false) Set<Confidence> confidence,
            @RequestParam(required = false) Set<UsageKind> kind, @RequestParam(required = false) String repo,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        requireSymbol(id);
        return details.usages(id, confidence, kind, repo, paging.resolve(page, size));
    }

    @GetMapping("/{id}/usages/summary")
    public UsageSummary summary(@PathVariable long id) {
        requireSymbol(id);
        return details.summary(id);
    }

    private void requireSymbol(long id) {
        if (details.find(id).isEmpty()) {
            throw notFound(id);
        }
    }

    private static NotFoundException notFound(long id) {
        return new NotFoundException("No symbol with id " + id);
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw test -Dtest='SymbolDetailsTest,SymbolDetailApiTest,SymbolApiTest'`
Expected: all pass.

Run: `./mvnw test`
Expected: all tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/graphify/search src/test/java/com/graphify/search
git commit -m "feat(search): add symbol detail, usage list and usage summary endpoints" -m "<your harness Co-Authored-By trailer>"
```

---

### Task 5: The impact engine (BFS, seeds, dispatch, rules, truncation)

**Files:**
- Create in `src/main/java/com/graphify/impact/`:
  - `ChangeType.java`, `ImpactRequest.java`, `ImpactLimits.java`, `ImpactRule.java`
  - `ImpactSymbol.java`, `ImpactUsage.java`, `AnnotationUse.java`, `RepoState.java`
  - `NodeRole.java`, `ImpactNode.java`, `ImpactEdge.java`, `EntryPoint.java`, `ImpactSummary.java`, `ImpactResult.java`
  - `ImpactGraph.java`, `ImpactEngine.java`
- Test: `src/test/java/com/graphify/impact/InMemoryImpactGraph.java`, `ImpactEngineTest.java`

**Interfaces:**
- Consumes `NotFoundException` and the plan-1 enums `SymbolKind`, `UsageKind` and `Confidence`.
- Produces the records below and `public final class ImpactEngine` with `ImpactEngine(ImpactGraph graph)` and `ImpactResult analyze(ImpactRequest request, ImpactLimits limits, Map<UsageKind, ImpactRule> rules, Map<String, String> entryPointLabels)`. In this task `entryPoints` is always empty. Task 6 fills it.
- `public interface ImpactGraph`:
  - `Map<Long, ImpactSymbol> symbols(Collection<Long> ids)`
  - `List<Long> descendants(long typeId)`
  - `Optional<Long> idOfKey(String key)`
  - `List<ImpactUsage> usagesTo(Collection<Long> targetIds, Set<UsageKind> kinds, Set<Confidence> confidences)`
  - `Map<Long, List<Long>> overriddenMethods(Collection<Long> symbolIds)`
  - `List<AnnotationUse> annotationsOn(Collection<Long> symbolIds, Collection<String> annotationKeys)`
  - `Map<Long, RepoState> repoStates(Collection<Long> moduleIds)`

- [ ] **Step 1: Write the model records and the port**

`src/main/java/com/graphify/impact/ChangeType.java`:

```java
package com.graphify.impact;

/** SIGNATURE: callers stop compiling (level 1 + overrides, no dispatch). BEHAVIOR: transitive callers (spec §5.3). */
public enum ChangeType {
    SIGNATURE, BEHAVIOR
}
```

`src/main/java/com/graphify/impact/ImpactRequest.java`:

```java
package com.graphify.impact;

import com.graphify.indexer.model.Confidence;
import java.util.List;
import java.util.Set;

/** Null {@code changeType}, {@code depth} or {@code includeDispatch} mean BEHAVIOR, the configured default depth, true. */
public record ImpactRequest(
        List<Long> symbolIds,
        ChangeType changeType,
        Integer depth,
        Set<Confidence> confidences,
        Boolean includeDispatch) {
}
```

`src/main/java/com/graphify/impact/ImpactLimits.java`:

```java
package com.graphify.impact;

/** From impact.default_depth, impact.max_depth and impact.max_results. */
public record ImpactLimits(int defaultDepth, int maxDepth, int maxResults) {
}
```

`src/main/java/com/graphify/impact/ImpactRule.java`:

```java
package com.graphify.impact;

import com.graphify.indexer.model.UsageKind;

/** One row of impact_relation_rule. */
public record ImpactRule(UsageKind kind, boolean propagates, boolean shownAtLevel1) {
}
```

`src/main/java/com/graphify/impact/ImpactSymbol.java`:

```java
package com.graphify.impact;

import com.graphify.indexer.model.SymbolKind;

public record ImpactSymbol(long id, String key, SymbolKind kind, String display, String classFqn, Long parentId) {
}
```

`src/main/java/com/graphify/impact/ImpactUsage.java`:

```java
package com.graphify.impact;

import com.graphify.indexer.model.Confidence;
import com.graphify.indexer.model.UsageKind;

/** One usage row as the engine needs it. */
public record ImpactUsage(
        long id,
        long fromId,
        long toId,
        UsageKind kind,
        Confidence confidence,
        long moduleId,
        String filePath,
        int line,
        int column,
        String snippet) {
}
```

`src/main/java/com/graphify/impact/AnnotationUse.java`:

```java
package com.graphify.impact;

/** An ANNOTATION usage: {@code symbolId} carries {@code annotationKey}, written as {@code snippet}. */
public record AnnotationUse(long symbolId, String annotationKey, String snippet, long moduleId) {
}
```

`src/main/java/com/graphify/impact/RepoState.java`:

```java
package com.graphify.impact;

import java.time.Instant;

/** Where a module lives and how fresh and complete its index is (spec §5.6). */
public record RepoState(
        long moduleId,
        long repositoryId,
        String repository,
        String modulePath,
        String classpathMode,
        String lastIndexedCommit,
        Instant lastIndexedAt) {
}
```

`src/main/java/com/graphify/impact/NodeRole.java`:

```java
package com.graphify.impact;

/** SEED: what is changing. AFFECTED: reached by the BFS. DISPATCH: an overridden method whose callers were followed. */
public enum NodeRole {
    SEED, AFFECTED, DISPATCH
}
```

`src/main/java/com/graphify/impact/ImpactNode.java`:

```java
package com.graphify.impact;

import com.graphify.indexer.model.SymbolKind;

public record ImpactNode(long symbolId, String key, SymbolKind kind, String display, int level, NodeRole role) {
}
```

`src/main/java/com/graphify/impact/ImpactEdge.java`:

```java
package com.graphify.impact;

import com.graphify.indexer.model.Confidence;
import com.graphify.indexer.model.UsageKind;

public record ImpactEdge(
        long fromSymbolId,
        long toSymbolId,
        UsageKind kind,
        Confidence confidence,
        int level,
        boolean viaDispatch,
        long moduleId,
        String repository,
        String modulePath,
        String filePath,
        int line,
        int column,
        String snippet) {
}
```

`src/main/java/com/graphify/impact/EntryPoint.java`:

```java
package com.graphify.impact;

/** An affected endpoint, job or listener; {@code httpMethod}/{@code httpPath} only for request mappings. */
public record EntryPoint(
        long symbolId,
        String key,
        String display,
        String repository,
        String modulePath,
        String label,
        String annotation,
        String httpMethod,
        String httpPath) {
}
```

`src/main/java/com/graphify/impact/ImpactSummary.java`:

```java
package com.graphify.impact;

import com.graphify.indexer.model.Confidence;
import java.util.List;
import java.util.Map;

/** The headline numbers of spec §5.6; {@code partialClasspathRepositories} have modules indexed without a full classpath. */
public record ImpactSummary(
        int repositories,
        int modules,
        int classes,
        int methods,
        int usages,
        Map<Integer, Integer> usagesByLevel,
        Map<Confidence, Integer> usagesByConfidence,
        List<String> partialClasspathRepositories) {
}
```

`src/main/java/com/graphify/impact/ImpactResult.java`:

```java
package com.graphify.impact;

import java.util.List;

public record ImpactResult(
        ImpactSummary summary,
        List<ImpactNode> nodes,
        List<ImpactEdge> edges,
        List<EntryPoint> entryPoints,
        List<RepoState> repositories,
        boolean truncated) {
}
```

`src/main/java/com/graphify/impact/ImpactGraph.java`:

```java
package com.graphify.impact;

import com.graphify.indexer.model.Confidence;
import com.graphify.indexer.model.UsageKind;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** What the impact engine reads from the index. Implementations must accept collections of any size. */
public interface ImpactGraph {

    Map<Long, ImpactSymbol> symbols(Collection<Long> ids);

    /** All symbols whose parent chain leads to {@code typeId} (members, nested types and their members). */
    List<Long> descendants(long typeId);

    Optional<Long> idOfKey(String key);

    List<ImpactUsage> usagesTo(Collection<Long> targetIds, Set<UsageKind> kinds, Set<Confidence> confidences);

    /** For each given symbol, the methods it overrides (its OVERRIDES usages). */
    Map<Long, List<Long>> overriddenMethods(Collection<Long> symbolIds);

    List<AnnotationUse> annotationsOn(Collection<Long> symbolIds, Collection<String> annotationKeys);

    Map<Long, RepoState> repoStates(Collection<Long> moduleIds);
}
```

- [ ] **Step 2: Write the in-memory graph**

`src/test/java/com/graphify/impact/InMemoryImpactGraph.java`:

```java
package com.graphify.impact;

import com.graphify.indexer.model.Confidence;
import com.graphify.indexer.model.SymbolKind;
import com.graphify.indexer.model.UsageKind;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** An impact graph built in a test, so the engine's rules are checked without a database. */
final class InMemoryImpactGraph implements ImpactGraph {

    private final Map<Long, ImpactSymbol> symbols = new LinkedHashMap<>();
    private final List<ImpactUsage> usages = new ArrayList<>();
    private final List<AnnotationUse> annotations = new ArrayList<>();
    private final Map<Long, RepoState> modules = new HashMap<>();
    private long nextId = 1;

    long symbol(String key, SymbolKind kind, Long parentId) {
        long id = nextId++;
        String classFqn = key.contains("#") ? key.substring(0, key.indexOf('#')) : key;
        symbols.put(id, new ImpactSymbol(id, key, kind, key, classFqn, parentId));
        return id;
    }

    void usage(long from, long to, UsageKind kind, Confidence confidence, long moduleId) {
        usages.add(new ImpactUsage(nextId++, from, to, kind, confidence, moduleId, "F.java", 1, 1, "snippet"));
    }

    void annotation(long symbolId, String annotationKey, String snippet, long moduleId) {
        annotations.add(new AnnotationUse(symbolId, annotationKey, snippet, moduleId));
    }

    void module(long moduleId, String repository, String path, String classpathMode) {
        modules.put(moduleId, new RepoState(moduleId, repository.hashCode(), repository, path, classpathMode, "c1", null));
    }

    @Override
    public Map<Long, ImpactSymbol> symbols(Collection<Long> ids) {
        Map<Long, ImpactSymbol> found = new LinkedHashMap<>();
        ids.forEach(id -> {
            if (symbols.containsKey(id)) {
                found.put(id, symbols.get(id));
            }
        });
        return found;
    }

    @Override
    public List<Long> descendants(long typeId) {
        List<Long> found = new ArrayList<>();
        for (ImpactSymbol symbol : symbols.values()) {
            if (symbol.parentId() != null && symbol.parentId() == typeId) {
                found.add(symbol.id());
                found.addAll(descendants(symbol.id()));
            }
        }
        return found;
    }

    @Override
    public Optional<Long> idOfKey(String key) {
        return symbols.values().stream().filter(s -> s.key().equals(key)).map(ImpactSymbol::id).findFirst();
    }

    @Override
    public List<ImpactUsage> usagesTo(Collection<Long> targetIds, Set<UsageKind> kinds, Set<Confidence> confidences) {
        return usages.stream()
                .filter(u -> targetIds.contains(u.toId()) && kinds.contains(u.kind())
                        && confidences.contains(u.confidence()))
                .toList();
    }

    @Override
    public Map<Long, List<Long>> overriddenMethods(Collection<Long> symbolIds) {
        Map<Long, List<Long>> found = new LinkedHashMap<>();
        usages.stream().filter(u -> u.kind() == UsageKind.OVERRIDES && symbolIds.contains(u.fromId()))
                .forEach(u -> found.computeIfAbsent(u.fromId(), k -> new ArrayList<>()).add(u.toId()));
        return found;
    }

    @Override
    public List<AnnotationUse> annotationsOn(Collection<Long> symbolIds, Collection<String> annotationKeys) {
        return annotations.stream()
                .filter(a -> symbolIds.contains(a.symbolId()) && annotationKeys.contains(a.annotationKey()))
                .toList();
    }

    @Override
    public Map<Long, RepoState> repoStates(Collection<Long> moduleIds) {
        Map<Long, RepoState> found = new LinkedHashMap<>();
        moduleIds.forEach(id -> {
            if (modules.containsKey(id)) {
                found.put(id, modules.get(id));
            }
        });
        return found;
    }
}
```

- [ ] **Step 3: Write the failing engine test**

`src/test/java/com/graphify/impact/ImpactEngineTest.java`:

```java
package com.graphify.impact;

import static com.graphify.indexer.model.Confidence.EXACT;
import static com.graphify.indexer.model.Confidence.NAME_ONLY;
import static com.graphify.indexer.model.SymbolKind.CLASS;
import static com.graphify.indexer.model.SymbolKind.CONSTRUCTOR;
import static com.graphify.indexer.model.SymbolKind.INTERFACE;
import static com.graphify.indexer.model.SymbolKind.METHOD;
import static com.graphify.indexer.model.UsageKind.CALL;
import static com.graphify.indexer.model.UsageKind.OVERRIDES;
import static com.graphify.indexer.model.UsageKind.TYPE_REF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.graphify.common.exception.NotFoundException;
import com.graphify.indexer.model.Confidence;
import com.graphify.indexer.model.UsageKind;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ImpactEngineTest {

    static final ImpactLimits LIMITS = new ImpactLimits(3, 10, 5000);
    static final long MODULE = 1;
    static final long LEGACY = 2;

    /** Mirrors the V3 seed rows of impact_relation_rule. */
    static Map<UsageKind, ImpactRule> defaultRules() {
        Map<UsageKind, ImpactRule> rules = new EnumMap<>(UsageKind.class);
        for (UsageKind kind : UsageKind.values()) {
            boolean propagates = switch (kind) {
                case CALL, INSTANTIATION, METHOD_REF, OVERRIDES, EXTENDS, IMPLEMENTS -> true;
                default -> false;
            };
            rules.put(kind, new ImpactRule(kind, propagates, true));
        }
        return rules;
    }

    InMemoryImpactGraph graph;
    ImpactEngine engine;

    @BeforeEach
    void setUp() {
        graph = new InMemoryImpactGraph();
        graph.module(MODULE, "shop-api", "shop-api", "FULL");
        graph.module(LEGACY, "shop-api", "shop-legacy", "NONE");
        engine = new ImpactEngine(graph);
    }

    ImpactResult run(List<Long> ids, ChangeType type, Integer depth, Set<Confidence> confidences, Boolean dispatch) {
        return engine.analyze(new ImpactRequest(ids, type, depth, confidences, dispatch), LIMITS, defaultRules(), Map.of());
    }

    @Test
    void behaviorChangeFollowsCallersLevelByLevel() {
        long format = graph.symbol("lib.F#format(int)", METHOD, null);
        long label = graph.symbol("api.S#label(int)", METHOD, null);
        long checkout = graph.symbol("api.S#checkout(int)", METHOD, null);
        long endpoint = graph.symbol("api.C#post()", METHOD, null);
        graph.usage(label, format, CALL, EXACT, MODULE);
        graph.usage(checkout, label, CALL, EXACT, MODULE);
        graph.usage(endpoint, checkout, CALL, EXACT, MODULE);

        ImpactResult three = run(List.of(format), null, null, null, null);
        ImpactResult two = run(List.of(format), ChangeType.BEHAVIOR, 2, null, null);

        assertThat(three.nodes()).extracting(ImpactNode::key, ImpactNode::level, ImpactNode::role).containsExactly(
                tuple("lib.F#format(int)", 0, NodeRole.SEED),
                tuple("api.S#label(int)", 1, NodeRole.AFFECTED),
                tuple("api.S#checkout(int)", 2, NodeRole.AFFECTED),
                tuple("api.C#post()", 3, NodeRole.AFFECTED));
        assertThat(three.edges()).extracting(ImpactEdge::level).containsExactly(1, 2, 3);
        assertThat(three.summary().usagesByLevel()).containsExactly(Map.entry(1, 1), Map.entry(2, 1), Map.entry(3, 1));
        assertThat(two.nodes()).extracting(ImpactNode::key).doesNotContain("api.C#post()");
        assertThat(three.truncated()).isFalse();
    }

    @Test
    void nonPropagatingKindsShowAtLevelOneOnly() {
        long type = graph.symbol("lib.T", CLASS, null);
        long holder = graph.symbol("api.H#hold(lib.T)", METHOD, null);
        long caller = graph.symbol("api.X#run()", METHOD, null);
        graph.usage(holder, type, TYPE_REF, EXACT, MODULE);
        graph.usage(caller, holder, CALL, EXACT, MODULE);

        ImpactResult result = run(List.of(type), null, 3, null, null);

        assertThat(result.edges()).extracting(ImpactEdge::kind, ImpactEdge::level).containsExactly(tuple(TYPE_REF, 1));
        assertThat(result.nodes()).extracting(ImpactNode::key).doesNotContain("api.X#run()");
    }

    @Test
    void aSymbolFirstSeenThroughATypeRefStillPropagatesWhenAlsoCalled() {
        long type = graph.symbol("lib.T", CLASS, null);
        long ctor = graph.symbol("lib.T#<init>()", CONSTRUCTOR, type);
        long user = graph.symbol("api.U#make()", METHOD, null);
        long caller = graph.symbol("api.X#run()", METHOD, null);
        graph.usage(user, type, TYPE_REF, EXACT, MODULE);
        graph.usage(user, ctor, UsageKind.INSTANTIATION, EXACT, MODULE);
        graph.usage(caller, user, CALL, EXACT, MODULE);

        ImpactResult result = run(List.of(type), null, 2, null, null);

        assertThat(result.nodes()).extracting(ImpactNode::key).contains("api.X#run()");
    }

    @Test
    void typeTargetSeedsItsMembers() {
        long type = graph.symbol("lib.T", CLASS, null);
        long member = graph.symbol("lib.T#go()", METHOD, type);
        long caller = graph.symbol("api.X#run()", METHOD, null);
        graph.usage(caller, member, CALL, EXACT, MODULE);

        ImpactResult result = run(List.of(type), null, 1, null, null);

        assertThat(result.nodes()).extracting(ImpactNode::key, ImpactNode::role).contains(
                tuple("lib.T#go()", NodeRole.SEED), tuple("api.X#run()", NodeRole.AFFECTED));
    }

    @Test
    void dispatchReportsCallersOfTheOverriddenMethodButNotSiblings() {
        long iface = graph.symbol("lib.G", INTERFACE, null);
        long ifaceCharge = graph.symbol("lib.G#charge(int)", METHOD, iface);
        long card = graph.symbol("api.Card#charge(int)", METHOD, null);
        long cash = graph.symbol("api.Cash#charge(int)", METHOD, null);
        long checkout = graph.symbol("api.S#checkout(int)", METHOD, null);
        graph.usage(card, ifaceCharge, OVERRIDES, EXACT, MODULE);
        graph.usage(cash, ifaceCharge, OVERRIDES, EXACT, MODULE);
        graph.usage(checkout, ifaceCharge, CALL, EXACT, MODULE);

        ImpactResult withDispatch = run(List.of(card), null, 1, null, null);
        ImpactResult without = run(List.of(card), null, 1, null, false);

        assertThat(withDispatch.edges()).extracting(ImpactEdge::fromSymbolId, ImpactEdge::viaDispatch)
                .containsExactly(tuple(checkout, true));
        assertThat(withDispatch.nodes()).extracting(ImpactNode::key, ImpactNode::role).contains(
                tuple("lib.G#charge(int)", NodeRole.DISPATCH), tuple("api.S#checkout(int)", NodeRole.AFFECTED));
        assertThat(withDispatch.nodes()).extracting(ImpactNode::key).doesNotContain("api.Cash#charge(int)");
        assertThat(without.edges()).isEmpty();
    }

    @Test
    void signatureChangeStopsAtLevelOneWithoutDispatchButKeepsOverrides() {
        long ifaceCharge = graph.symbol("lib.G#charge(int)", METHOD, null);
        long card = graph.symbol("api.Card#charge(int)", METHOD, null);
        long checkout = graph.symbol("api.S#checkout(int)", METHOD, null);
        long endpoint = graph.symbol("api.C#post()", METHOD, null);
        graph.usage(card, ifaceCharge, OVERRIDES, EXACT, MODULE);
        graph.usage(checkout, ifaceCharge, CALL, EXACT, MODULE);
        graph.usage(endpoint, checkout, CALL, EXACT, MODULE);

        ImpactResult result = run(List.of(ifaceCharge), ChangeType.SIGNATURE, 5, null, true);

        assertThat(result.edges()).extracting(ImpactEdge::kind, ImpactEdge::level)
                .containsExactlyInAnyOrder(tuple(OVERRIDES, 1), tuple(CALL, 1));
    }

    @Test
    void nameOnlyTwinOfAMethodIsSeededAndFilterable() {
        long format = graph.symbol("lib.F#format(int)", METHOD, null);
        long twin = graph.symbol("lib.F#format/1", METHOD, null);
        long legacy = graph.symbol("old.R#print()", METHOD, null);
        graph.usage(legacy, twin, CALL, NAME_ONLY, LEGACY);

        ImpactResult all = run(List.of(format), null, 1, null, null);
        ImpactResult exactOnly = run(List.of(format), null, 1, Set.of(EXACT), null);

        assertThat(all.edges()).extracting(ImpactEdge::fromSymbolId, ImpactEdge::confidence)
                .containsExactly(tuple(legacy, NAME_ONLY));
        assertThat(all.summary().partialClasspathRepositories()).containsExactly("shop-api");
        assertThat(exactOnly.edges()).isEmpty();
    }

    @Test
    void cyclesTerminateAndKeepTheFirstLevel() {
        long a = graph.symbol("p.A#a()", METHOD, null);
        long b = graph.symbol("p.B#b()", METHOD, null);
        graph.usage(b, a, CALL, EXACT, MODULE);
        graph.usage(a, b, CALL, EXACT, MODULE);
        graph.usage(a, a, CALL, EXACT, MODULE);

        ImpactResult result = run(List.of(a), null, 10, null, null);

        assertThat(result.nodes()).extracting(ImpactNode::key, ImpactNode::level)
                .containsExactly(tuple("p.A#a()", 0), tuple("p.B#b()", 1));
    }

    @Test
    void resultLimitTruncatesInsteadOfGrowingWithoutBound() {
        long target = graph.symbol("lib.F#hot()", METHOD, null);
        for (int i = 0; i < 10; i++) {
            graph.usage(graph.symbol("api.C" + i + "#m()", METHOD, null), target, CALL, EXACT, MODULE);
        }

        ImpactResult result = engine.analyze(new ImpactRequest(List.of(target), null, 2, null, null),
                new ImpactLimits(3, 10, 3), defaultRules(), Map.of());

        assertThat(result.truncated()).isTrue();
        assertThat(result.nodes()).filteredOn(n -> n.role() == NodeRole.AFFECTED).hasSize(3);
        assertThat(result.edges()).hasSize(3);
    }

    @Test
    void rulesComeFromTheCaller() {
        long format = graph.symbol("lib.F#format(int)", METHOD, null);
        long label = graph.symbol("api.S#label(int)", METHOD, null);
        long checkout = graph.symbol("api.S#checkout(int)", METHOD, null);
        graph.usage(label, format, CALL, EXACT, MODULE);
        graph.usage(checkout, label, CALL, EXACT, MODULE);
        Map<UsageKind, ImpactRule> noCallPropagation = defaultRules();
        noCallPropagation.put(CALL, new ImpactRule(CALL, false, true));

        ImpactResult result = engine.analyze(new ImpactRequest(List.of(format), null, 3, null, null), LIMITS,
                noCallPropagation, Map.of());

        assertThat(result.nodes()).extracting(ImpactNode::key).containsExactly("lib.F#format(int)", "api.S#label(int)");
    }

    @Test
    void summaryCountsWhatWasReached() {
        long format = graph.symbol("lib.F#format(int)", METHOD, null);
        long label = graph.symbol("api.S#label(int)", METHOD, null);
        long legacy = graph.symbol("old.R#print()", METHOD, null);
        graph.usage(label, format, CALL, EXACT, MODULE);
        graph.usage(legacy, format, CALL, Confidence.RECOVERED, LEGACY);

        ImpactSummary summary = run(List.of(format), null, 1, null, null).summary();

        assertThat(summary.repositories()).isEqualTo(1);
        assertThat(summary.modules()).isEqualTo(2);
        assertThat(summary.classes()).isEqualTo(2);
        assertThat(summary.methods()).isEqualTo(2);
        assertThat(summary.usages()).isEqualTo(2);
        assertThat(summary.usagesByConfidence()).containsEntry(EXACT, 1).containsEntry(Confidence.RECOVERED, 1);
    }

    @Test
    void invalidRequestsAreRejected() {
        long format = graph.symbol("lib.F#format(int)", METHOD, null);

        assertThatIllegalArgumentException().isThrownBy(() -> run(List.of(), null, null, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> run(null, null, null, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> run(List.of(format), null, 0, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> run(List.of(format), null, 11, null, null))
                .withMessageContaining("10");
        assertThatThrownBy(() -> run(List.of(99L), null, null, null, null)).isInstanceOf(NotFoundException.class);
    }

}
```

- [ ] **Step 4: Run it to verify it fails**

Run: `./mvnw test -Dtest=ImpactEngineTest`
Expected: BUILD FAILURE, `cannot find symbol ... class ImpactEngine`.

- [ ] **Step 5: Write `ImpactEngine`**

`src/main/java/com/graphify/impact/ImpactEngine.java`:

```java
package com.graphify.impact;

import com.graphify.common.exception.NotFoundException;
import com.graphify.indexer.model.Confidence;
import com.graphify.indexer.model.SymbolKind;
import com.graphify.indexer.model.UsageKind;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Breadth-first impact analysis (spec §5). Level 1 lists usages of the seeds whose kind is shown at level 1; deeper
 * levels follow only propagating kinds. Overridden methods are followed as dispatch targets, never their OVERRIDES
 * edges, so sibling implementations are not reported.
 */
public final class ImpactEngine {

    private static final Set<SymbolKind> TYPE_KINDS = EnumSet.of(SymbolKind.CLASS, SymbolKind.INTERFACE,
            SymbolKind.ENUM, SymbolKind.RECORD, SymbolKind.ANNOTATION_TYPE);
    private static final Set<SymbolKind> CALLABLE_KINDS = EnumSet.of(SymbolKind.METHOD, SymbolKind.CONSTRUCTOR);

    private final ImpactGraph graph;

    public ImpactEngine(ImpactGraph graph) {
        this.graph = graph;
    }

    public ImpactResult analyze(ImpactRequest request, ImpactLimits limits, Map<UsageKind, ImpactRule> rules,
            Map<String, String> entryPointLabels) {
        Plan plan = Plan.of(request, limits);
        Map<Long, ImpactSymbol> targets = graph.symbols(plan.symbolIds());
        for (Long id : plan.symbolIds()) {
            if (!targets.containsKey(id)) {
                throw new NotFoundException("No symbol with id " + id);
            }
        }
        Set<UsageKind> levelOneKinds = kinds(rules, ImpactRule::shownAtLevel1);
        Set<UsageKind> propagating = kinds(rules, ImpactRule::propagates);

        Set<Long> seeds = seeds(targets.values());
        Search search = new Search(seeds, propagating, limits.maxResults());
        Set<Long> frontier = seeds;
        Set<Long> dispatchFrontier = plan.dispatch() ? search.dispatchTargets(graph.overriddenMethods(seeds), 0)
                : Set.of();
        for (int level = 1; level <= plan.depth(); level++) {
            if (frontier.isEmpty() && dispatchFrontier.isEmpty()) {
                break;
            }
            Set<UsageKind> kinds = level == 1 ? levelOneKinds : propagating;
            Set<UsageKind> dispatchKinds = EnumSet.noneOf(UsageKind.class);
            dispatchKinds.addAll(kinds);
            dispatchKinds.remove(UsageKind.OVERRIDES);
            Set<Long> next = new LinkedHashSet<>();
            search.collect(usagesTo(frontier, kinds, plan.confidences()), false, level, next);
            search.collect(usagesTo(dispatchFrontier, dispatchKinds, plan.confidences()), true, level, next);
            frontier = next;
            dispatchFrontier = plan.dispatch() && !next.isEmpty()
                    ? search.dispatchTargets(graph.overriddenMethods(next), level) : Set.of();
            if (search.truncated) {
                break;
            }
        }
        return assemble(search, entryPointLabels);
    }

    private List<ImpactUsage> usagesTo(Set<Long> targets, Set<UsageKind> kinds, Set<Confidence> confidences) {
        return targets.isEmpty() || kinds.isEmpty() ? List.of() : graph.usagesTo(targets, kinds, confidences);
    }

    private Set<Long> seeds(Collection<ImpactSymbol> targets) {
        Set<Long> seeds = new LinkedHashSet<>();
        for (ImpactSymbol target : targets) {
            seeds.add(target.id());
            if (TYPE_KINDS.contains(target.kind())) {
                seeds.addAll(graph.descendants(target.id()));
            }
            if (CALLABLE_KINDS.contains(target.kind())) {
                nameOnlyKey(target.key()).flatMap(graph::idOfKey).ifPresent(seeds::add);
            }
        }
        return seeds;
    }

    /** {@code C#m(a,b)} → {@code C#m/2}: the key the indexer gives a call it could not bind (spec §4.2). */
    static Optional<String> nameOnlyKey(String key) {
        int hash = key.indexOf('#');
        int open = hash < 0 ? -1 : key.indexOf('(', hash);
        if (open < 0 || !key.endsWith(")")) {
            return Optional.empty();
        }
        String parameters = key.substring(open + 1, key.length() - 1);
        int count = parameters.isEmpty() ? 0 : parameters.split(",", -1).length;
        return Optional.of(key.substring(0, open) + "/" + count);
    }

    private ImpactResult assemble(Search search, Map<String, String> entryPointLabels) {
        Set<Long> ids = new LinkedHashSet<>(search.levels.keySet());
        ids.addAll(search.dispatchLevels.keySet());
        Map<Long, ImpactSymbol> info = graph.symbols(ids);
        Set<Long> moduleIds = new LinkedHashSet<>();
        search.found.forEach(f -> moduleIds.add(f.usage().moduleId()));
        Map<Long, RepoState> states = graph.repoStates(moduleIds);

        List<ImpactNode> nodes = new ArrayList<>();
        search.levels.forEach((id, level) -> node(info.get(id), level,
                search.seeds.contains(id) ? NodeRole.SEED : NodeRole.AFFECTED).ifPresent(nodes::add));
        search.dispatchLevels.forEach((id, level) -> {
            if (!search.levels.containsKey(id)) {
                node(info.get(id), level, NodeRole.DISPATCH).ifPresent(nodes::add);
            }
        });
        List<ImpactEdge> edges = new ArrayList<>();
        for (Found found : search.found) {
            ImpactUsage u = found.usage();
            RepoState state = states.get(u.moduleId());
            edges.add(new ImpactEdge(u.fromId(), u.toId(), u.kind(), u.confidence(), found.level(),
                    found.viaDispatch(), u.moduleId(), state == null ? null : state.repository(),
                    state == null ? null : state.modulePath(), u.filePath(), u.line(), u.column(), u.snippet()));
        }
        List<EntryPoint> entryPoints = List.of();
        List<RepoState> repositories = states.values().stream()
                .sorted(Comparator.comparing(RepoState::repository).thenComparing(RepoState::modulePath))
                .toList();
        return new ImpactResult(summary(nodes, edges, info, states), nodes, edges, entryPoints, repositories,
                search.truncated);
    }

    private static Optional<ImpactNode> node(ImpactSymbol symbol, int level, NodeRole role) {
        return symbol == null ? Optional.empty()
                : Optional.of(new ImpactNode(symbol.id(), symbol.key(), symbol.kind(), symbol.display(), level, role));
    }

    private static ImpactSummary summary(List<ImpactNode> nodes, List<ImpactEdge> edges, Map<Long, ImpactSymbol> info,
            Map<Long, RepoState> states) {
        Set<String> classes = new HashSet<>();
        int methods = 0;
        for (ImpactNode node : nodes) {
            if (node.role() != NodeRole.AFFECTED) {
                continue;
            }
            classes.add(info.get(node.symbolId()).classFqn());
            if (CALLABLE_KINDS.contains(node.kind())) {
                methods++;
            }
        }
        Map<Integer, Integer> byLevel = new TreeMap<>();
        Map<Confidence, Integer> byConfidence = new EnumMap<>(Confidence.class);
        Set<Long> modules = new HashSet<>();
        Set<Long> repositories = new HashSet<>();
        Set<String> partial = new TreeSet<>();
        for (ImpactEdge edge : edges) {
            byLevel.merge(edge.level(), 1, Integer::sum);
            byConfidence.merge(edge.confidence(), 1, Integer::sum);
            modules.add(edge.moduleId());
            RepoState state = states.get(edge.moduleId());
            if (state != null) {
                repositories.add(state.repositoryId());
                if (!"FULL".equals(state.classpathMode())) {
                    partial.add(state.repository());
                }
            }
        }
        return new ImpactSummary(repositories.size(), modules.size(), classes.size(), methods, edges.size(), byLevel,
                byConfidence, List.copyOf(partial));
    }

    private static Set<UsageKind> kinds(Map<UsageKind, ImpactRule> rules, Predicate<ImpactRule> test) {
        Set<UsageKind> kinds = EnumSet.noneOf(UsageKind.class);
        rules.values().stream().filter(test).forEach(rule -> kinds.add(rule.kind()));
        return kinds;
    }

    private record Found(ImpactUsage usage, int level, boolean viaDispatch) {
    }

    /** Mutable state of one BFS run. */
    private static final class Search {

        final Set<Long> seeds;
        final Set<UsageKind> propagating;
        final int maxResults;
        final Map<Long, Integer> levels = new LinkedHashMap<>();
        final Map<Long, Integer> dispatchLevels = new LinkedHashMap<>();
        final Set<Long> expanded = new HashSet<>();
        final Set<Long> seenUsages = new HashSet<>();
        final List<Found> found = new ArrayList<>();
        boolean truncated;

        Search(Set<Long> seeds, Set<UsageKind> propagating, int maxResults) {
            this.seeds = seeds;
            this.propagating = propagating;
            this.maxResults = maxResults;
            seeds.forEach(id -> levels.put(id, 0));
            expanded.addAll(seeds);
        }

        void collect(List<ImpactUsage> usages, boolean viaDispatch, int level, Set<Long> next) {
            for (ImpactUsage usage : usages) {
                if (!seenUsages.add(usage.id())) {
                    continue;
                }
                boolean known = levels.containsKey(usage.fromId());
                if (!known && levels.size() - seeds.size() >= maxResults) {
                    truncated = true;
                    continue;
                }
                found.add(new Found(usage, level, viaDispatch));
                if (!known) {
                    levels.put(usage.fromId(), level);
                }
                if (propagating.contains(usage.kind()) && expanded.add(usage.fromId())) {
                    next.add(usage.fromId());
                }
            }
        }

        Set<Long> dispatchTargets(Map<Long, List<Long>> overridden, int level) {
            Set<Long> targets = new LinkedHashSet<>();
            overridden.values().forEach(list -> list.forEach(id -> {
                if (!levels.containsKey(id) && !dispatchLevels.containsKey(id)) {
                    dispatchLevels.put(id, level);
                    targets.add(id);
                }
            }));
            return targets;
        }
    }

    /** The validated request: defaults applied, SIGNATURE forcing depth 1 and no dispatch (spec §5.3). */
    private record Plan(List<Long> symbolIds, int depth, Set<Confidence> confidences, boolean dispatch) {

        static Plan of(ImpactRequest request, ImpactLimits limits) {
            if (request == null || request.symbolIds() == null || request.symbolIds().isEmpty()) {
                throw new IllegalArgumentException("symbolIds must not be empty");
            }
            if (request.symbolIds().contains(null)) {
                throw new IllegalArgumentException("symbolIds must not contain null");
            }
            int depth = request.depth() == null ? limits.defaultDepth() : request.depth();
            if (depth < 1 || depth > limits.maxDepth()) {
                throw new IllegalArgumentException("depth must be between 1 and " + limits.maxDepth());
            }
            Set<Confidence> confidences = request.confidences() == null || request.confidences().isEmpty()
                    ? EnumSet.allOf(Confidence.class) : EnumSet.copyOf(request.confidences());
            boolean dispatch = request.includeDispatch() == null || request.includeDispatch();
            if (request.changeType() == ChangeType.SIGNATURE) {
                depth = 1;
                dispatch = false;
            }
            return new Plan(List.copyOf(new LinkedHashSet<>(request.symbolIds())), depth, confidences, dispatch);
        }
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./mvnw test -Dtest=ImpactEngineTest`
Expected: 12 tests pass.

If a test fails, decide whether the engine or the test contradicts the Global Constraints' "Impact semantics". Fix whichever one contradicts them, and say which in the report.

Run: `./mvnw test`
Expected: all tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/graphify/impact src/test/java/com/graphify/impact
git commit -m "feat(impact): add breadth-first impact engine with dispatch, rules and truncation" -m "<your harness Co-Authored-By trailer>"
```

---

### Task 6: Entry points (endpoints, jobs, listeners)

**Files:**
- Create: `src/main/java/com/graphify/impact/MappingPaths.java`, `EntryPointFinder.java`
- Modify: `src/main/java/com/graphify/impact/ImpactEngine.java`
- Test: `src/test/java/com/graphify/impact/MappingPathsTest.java`; `ImpactEngineTest.java` (add tests)

**Interfaces:**
- **Consumes:** `ImpactGraph.annotationsOn` and `repoStates` (Task 5), plus the `ImpactNode`/`ImpactSymbol` records.
- **Produces:**
  - `final class MappingPaths` with:
    - `static boolean isMapping(String annotationKey)`
    - `static Optional<String> path(String snippet)`
    - `static String httpMethod(String annotationKey, String snippet)` (null when unknown)
    - `static String join(String prefix, String path)`
  - `final class EntryPointFinder` with `EntryPointFinder(ImpactGraph)` and `List<EntryPoint> find(Collection<ImpactNode> nodes, Map<Long, ImpactSymbol> info, Map<String, String> labels)`.
  - `ImpactEngine.analyze` now fills `entryPoints`. Entry points are taken from SEED and AFFECTED callables only, never from DISPATCH nodes, and are sorted by repository and then key.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/graphify/impact/MappingPathsTest.java`:

```java
package com.graphify.impact;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MappingPathsTest {

    private static final String POST = "org.springframework.web.bind.annotation.PostMapping";
    private static final String REQUEST = "org.springframework.web.bind.annotation.RequestMapping";

    @Test
    void readsPositionalAndNamedPaths() {
        assertThat(MappingPaths.path("@RequestMapping(\"/orders\")")).contains("/orders");
        assertThat(MappingPaths.path("@PostMapping(value = \"/checkout\", produces = \"application/json\")"))
                .contains("/checkout");
        assertThat(MappingPaths.path("@GetMapping(produces = \"json\", path = {\"/a\", \"/b\"})")).contains("/a");
        assertThat(MappingPaths.path("@PostMapping")).isEmpty();
        assertThat(MappingPaths.path("@GetMapping(produces = \"json\")")).isEmpty();
    }

    @Test
    void derivesTheHttpMethod() {
        assertThat(MappingPaths.httpMethod(POST, "@PostMapping(\"/x\")")).isEqualTo("POST");
        assertThat(MappingPaths.httpMethod(REQUEST, "@RequestMapping(value = \"/x\", method = RequestMethod.PUT)"))
                .isEqualTo("PUT");
        assertThat(MappingPaths.httpMethod(REQUEST, "@RequestMapping(\"/x\")")).isNull();
        assertThat(MappingPaths.isMapping(POST)).isTrue();
        assertThat(MappingPaths.isMapping("org.springframework.scheduling.annotation.Scheduled")).isFalse();
    }

    @Test
    void joinsClassAndMethodPaths() {
        assertThat(MappingPaths.join("/orders", "/checkout")).isEqualTo("/orders/checkout");
        assertThat(MappingPaths.join("/orders/", "checkout")).isEqualTo("/orders/checkout");
        assertThat(MappingPaths.join(null, "/checkout")).isEqualTo("/checkout");
        assertThat(MappingPaths.join("/orders", null)).isEqualTo("/orders");
        assertThat(MappingPaths.join(null, null)).isEqualTo("/");
    }
}
```

Append these tests to `src/test/java/com/graphify/impact/ImpactEngineTest.java`, inside the class:

```java
    static final String POST_MAPPING = "org.springframework.web.bind.annotation.PostMapping";
    static final String REQUEST_MAPPING = "org.springframework.web.bind.annotation.RequestMapping";
    static final String SCHEDULED = "org.springframework.scheduling.annotation.Scheduled";
    static final Map<String, String> LABELS = Map.of(POST_MAPPING, "HTTP", REQUEST_MAPPING, "HTTP",
            SCHEDULED, "Zamanlanmış görev");

    @Test
    void reachedEndpointsAndJobsAreReportedWithTheirHttpPath() {
        long format = graph.symbol("lib.F#format(int)", METHOD, null);
        long controller = graph.symbol("api.OrderController", CLASS, null);
        long post = graph.symbol("api.OrderController#checkout()", METHOD, controller);
        long job = graph.symbol("api.NightlyJob#run()", METHOD, null);
        graph.usage(post, format, CALL, EXACT, MODULE);
        graph.usage(job, format, CALL, EXACT, MODULE);
        graph.annotation(controller, REQUEST_MAPPING, "@RequestMapping(\"/orders\")", MODULE);
        graph.annotation(post, POST_MAPPING, "@PostMapping(value = \"/checkout\")", MODULE);
        graph.annotation(job, SCHEDULED, "@Scheduled(cron = \"0 0 1 * * *\")", MODULE);

        ImpactResult result = engine.analyze(new ImpactRequest(List.of(format), null, 1, null, null), LIMITS,
                defaultRules(), LABELS);

        assertThat(result.entryPoints())
                .extracting(EntryPoint::key, EntryPoint::label, EntryPoint::httpMethod, EntryPoint::httpPath,
                        EntryPoint::repository)
                .containsExactly(
                        tuple("api.NightlyJob#run()", "Zamanlanmış görev", null, null, "shop-api"),
                        tuple("api.OrderController#checkout()", "HTTP", "POST", "/orders/checkout", "shop-api"));
    }

    @Test
    void aChangedEndpointIsItsOwnEntryPointAndDisabledLabelsAreIgnored() {
        long post = graph.symbol("api.C#post()", METHOD, null);
        graph.annotation(post, POST_MAPPING, "@PostMapping(\"/p\")", MODULE);

        ImpactResult enabled = engine.analyze(new ImpactRequest(List.of(post), null, 1, null, null), LIMITS,
                defaultRules(), LABELS);
        ImpactResult disabled = engine.analyze(new ImpactRequest(List.of(post), null, 1, null, null), LIMITS,
                defaultRules(), Map.of(SCHEDULED, "Zamanlanmış görev"));

        assertThat(enabled.entryPoints()).extracting(EntryPoint::httpPath).containsExactly("/p");
        assertThat(disabled.entryPoints()).isEmpty();
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -Dtest='MappingPathsTest,ImpactEngineTest'`
Expected: BUILD FAILURE, `cannot find symbol ... MappingPaths`. After `MappingPaths` exists, the two new engine tests fail because `entryPoints` is empty.

- [ ] **Step 3: Write `MappingPaths`**

`src/main/java/com/graphify/impact/MappingPaths.java`:

```java
package com.graphify.impact;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads HTTP mappings from annotation source text such as {@code @PostMapping(value = "/checkout")}. Spring's
 * {@code *Mapping} annotations put the path in {@code value}/{@code path} or as the single positional value.
 */
final class MappingPaths {

    private static final Pattern NAMED_PATH = Pattern.compile("\\b(?:value|path)\\s*=\\s*\\{?\\s*\"([^\"]*)\"");
    private static final Pattern POSITIONAL_PATH = Pattern.compile("^@[\\w.]+\\s*\\(\\s*\\{?\\s*\"([^\"]*)\"");
    private static final Pattern REQUEST_METHOD = Pattern.compile("RequestMethod\\.([A-Z]+)");

    private MappingPaths() {
    }

    static boolean isMapping(String annotationKey) {
        return simpleName(annotationKey).endsWith("Mapping");
    }

    static Optional<String> path(String snippet) {
        if (snippet == null) {
            return Optional.empty();
        }
        Matcher named = NAMED_PATH.matcher(snippet);
        if (named.find()) {
            return Optional.of(named.group(1));
        }
        Matcher positional = POSITIONAL_PATH.matcher(snippet);
        return positional.find() ? Optional.of(positional.group(1)) : Optional.empty();
    }

    static String httpMethod(String annotationKey, String snippet) {
        return switch (simpleName(annotationKey)) {
            case "GetMapping" -> "GET";
            case "PostMapping" -> "POST";
            case "PutMapping" -> "PUT";
            case "DeleteMapping" -> "DELETE";
            case "PatchMapping" -> "PATCH";
            default -> {
                Matcher method = snippet == null ? null : REQUEST_METHOD.matcher(snippet);
                yield method != null && method.find() ? method.group(1) : null;
            }
        };
    }

    static String join(String prefix, String path) {
        String left = prefix == null ? "" : prefix.strip();
        String right = path == null ? "" : path.strip();
        while (left.endsWith("/")) {
            left = left.substring(0, left.length() - 1);
        }
        if (!right.isEmpty() && !right.startsWith("/")) {
            right = "/" + right;
        }
        String joined = left + right;
        if (joined.isEmpty()) {
            return "/";
        }
        return joined.startsWith("/") ? joined : "/" + joined;
    }

    private static String simpleName(String annotationKey) {
        return annotationKey.substring(annotationKey.lastIndexOf('.') + 1);
    }
}
```

- [ ] **Step 4: Write `EntryPointFinder` and wire it in**

`src/main/java/com/graphify/impact/EntryPointFinder.java`:

```java
package com.graphify.impact;

import com.graphify.indexer.model.SymbolKind;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Which reached methods are entry points, by the ENTRY_POINT_ANNOTATION list (spec §5.6). */
final class EntryPointFinder {

    private static final Set<SymbolKind> CALLABLE_KINDS = EnumSet.of(SymbolKind.METHOD, SymbolKind.CONSTRUCTOR);

    private final ImpactGraph graph;

    EntryPointFinder(ImpactGraph graph) {
        this.graph = graph;
    }

    List<EntryPoint> find(Collection<ImpactNode> nodes, Map<Long, ImpactSymbol> info, Map<String, String> labels) {
        if (labels.isEmpty()) {
            return List.of();
        }
        Set<Long> callables = new LinkedHashSet<>();
        for (ImpactNode node : nodes) {
            if (node.role() != NodeRole.DISPATCH && CALLABLE_KINDS.contains(node.kind())) {
                callables.add(node.symbolId());
            }
        }
        if (callables.isEmpty()) {
            return List.of();
        }
        List<AnnotationUse> uses = graph.annotationsOn(callables, labels.keySet());
        Set<Long> owners = new LinkedHashSet<>();
        uses.forEach(use -> {
            Long parent = info.get(use.symbolId()).parentId();
            if (parent != null) {
                owners.add(parent);
            }
        });
        List<String> mappingKeys = labels.keySet().stream().filter(MappingPaths::isMapping).toList();
        Map<Long, String> classPrefixes = new HashMap<>();
        if (!owners.isEmpty() && !mappingKeys.isEmpty()) {
            for (AnnotationUse classUse : graph.annotationsOn(owners, mappingKeys)) {
                MappingPaths.path(classUse.snippet()).ifPresent(p -> classPrefixes.putIfAbsent(classUse.symbolId(), p));
            }
        }
        Set<Long> moduleIds = new LinkedHashSet<>();
        uses.forEach(use -> moduleIds.add(use.moduleId()));
        Map<Long, RepoState> states = graph.repoStates(moduleIds);

        List<EntryPoint> entryPoints = new ArrayList<>();
        for (AnnotationUse use : uses) {
            ImpactSymbol symbol = info.get(use.symbolId());
            RepoState state = states.get(use.moduleId());
            String httpMethod = null;
            String httpPath = null;
            if (MappingPaths.isMapping(use.annotationKey())) {
                httpMethod = MappingPaths.httpMethod(use.annotationKey(), use.snippet());
                String prefix = symbol.parentId() == null ? null : classPrefixes.get(symbol.parentId());
                httpPath = MappingPaths.join(prefix, MappingPaths.path(use.snippet()).orElse(null));
            }
            entryPoints.add(new EntryPoint(symbol.id(), symbol.key(), symbol.display(),
                    state == null ? null : state.repository(), state == null ? null : state.modulePath(),
                    labels.get(use.annotationKey()), use.snippet(), httpMethod, httpPath));
        }
        entryPoints.sort(Comparator.comparing((EntryPoint e) -> e.repository() == null ? "" : e.repository())
                .thenComparing(EntryPoint::key));
        return entryPoints;
    }
}
```

In `src/main/java/com/graphify/impact/ImpactEngine.java`, method `assemble`, replace:

```java
        List<EntryPoint> entryPoints = List.of();
```

with:

```java
        List<EntryPoint> entryPoints = new EntryPointFinder(graph).find(nodes, info, entryPointLabels);
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw test -Dtest='MappingPathsTest,ImpactEngineTest'`
Expected: all pass, 3 + 14 tests.

Run: `./mvnw test`
Expected: all tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/graphify/impact src/test/java/com/graphify/impact
git commit -m "feat(impact): report affected endpoints, jobs and listeners" -m "<your harness Co-Authored-By trailer>"
```

---

### Task 7: Oracle impact adapter and the impact service

**Files:**
- Create: `src/main/java/com/graphify/impact/JdbcImpactGraph.java`, `ImpactRules.java`, `ImpactService.java`
- Test: `src/test/java/com/graphify/impact/JdbcImpactGraphTest.java`, `ImpactServiceTest.java`

**Interfaces:**
- **Consumes:**
  - `ImpactGraph`, `ImpactEngine` and the records (Tasks 5–6).
  - `Chunks.of`, `Chunks.placeholders` and `Chunks.MAX_IN_LIST`.
  - `AppSettings`.
  - `ShopFixture`.
- **Produces:**
  - `public class JdbcImpactGraph implements ImpactGraph` (a `@Repository`).
  - `public class ImpactRules` (a `@Repository`) with:
    - `Map<UsageKind, ImpactRule> rules()`
    - `Map<String, String> entryPointLabels()`, which returns enabled rows only
  - `public class ImpactService` (a `@Service`) with `ImpactResult analyze(ImpactRequest request)`. It reads limits from `impact.default_depth`, `impact.max_depth` and `impact.max_results`.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/graphify/impact/JdbcImpactGraphTest.java`:

```java
package com.graphify.impact;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.indexer.model.Confidence;
import com.graphify.indexer.model.UsageKind;
import com.graphify.store.RepositoryIndexWriter;
import com.graphify.testsupport.ShopFixture;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcImpactGraphTest extends OracleIntegrationTest {

    @Autowired
    RepositoryIndexWriter writer;

    @Autowired
    JdbcImpactGraph graph;

    @TempDir
    static Path work;

    @BeforeAll
    void load() throws Exception {
        ShopFixture.load(jdbc, writer, work);
    }

    private long id(String key) {
        return ShopFixture.symbolId(jdbc, key);
    }

    @Test
    void readsSymbolsDescendantsAndKeys() {
        long gateway = id("com.shop.lib.PaymentGateway");

        assertThat(graph.symbols(List.of(gateway)).get(gateway).classFqn()).isEqualTo("com.shop.lib.PaymentGateway");
        assertThat(graph.descendants(gateway)).containsExactly(id("com.shop.lib.PaymentGateway#charge(int)"));
        assertThat(graph.idOfKey("com.shop.lib.PriceFormatter#format/1")).isPresent();
        assertThat(graph.idOfKey("no.such.Key")).isEmpty();
    }

    @Test
    void readsUsagesOverridesAnnotationsAndRepoStates() {
        long charge = id("com.shop.lib.PaymentGateway#charge(int)");
        long card = id("com.shop.api.CardGateway#charge(int)");
        long post = id("com.shop.api.OrderController#checkout()");

        List<ImpactUsage> usages = graph.usagesTo(List.of(charge), EnumSet.of(UsageKind.CALL),
                EnumSet.allOf(Confidence.class));
        assertThat(usages).singleElement().satisfies(u -> assertThat(u.fromId())
                .isEqualTo(id("com.shop.api.CheckoutService#checkout(int)")));
        assertThat(graph.overriddenMethods(List.of(card))).containsEntry(card, List.of(charge));
        assertThat(graph.annotationsOn(List.of(post), List.of("org.springframework.web.bind.annotation.PostMapping")))
                .singleElement().satisfies(a -> assertThat(a.snippet()).startsWith("@PostMapping"));
        long module = usages.getFirst().moduleId();
        assertThat(graph.repoStates(List.of(module)).get(module))
                .satisfies(state -> {
                    assertThat(state.repository()).isEqualTo("shop-api");
                    assertThat(state.classpathMode()).isEqualTo("FULL");
                    assertThat(state.lastIndexedCommit()).isEqualTo("api-1");
                    assertThat(state.lastIndexedAt()).isNotNull();
                });
    }

    @Test
    void usagesToHandlesMoreThanAThousandTargets() {
        List<Long> targets = new ArrayList<>();
        for (long i = 0; i < 1500; i++) {
            targets.add(-i - 1);
        }
        targets.add(id("com.shop.lib.PaymentGateway#charge(int)"));

        assertThat(graph.usagesTo(targets, EnumSet.allOf(UsageKind.class), EnumSet.allOf(Confidence.class)))
                .hasSize(2);
        assertThat(graph.symbols(targets)).hasSize(1);
    }
}
```

`src/test/java/com/graphify/impact/ImpactServiceTest.java`:

```java
package com.graphify.impact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.graphify.OracleIntegrationTest;
import com.graphify.indexer.model.Confidence;
import com.graphify.store.RepositoryIndexWriter;
import com.graphify.testsupport.ShopFixture;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

/** The spec §5 scenarios end to end on the shop fixture. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImpactServiceTest extends OracleIntegrationTest {

    @Autowired
    RepositoryIndexWriter writer;

    @Autowired
    ImpactService impact;

    @TempDir
    static Path work;

    @BeforeAll
    void load() throws Exception {
        ShopFixture.load(jdbc, writer, work);
    }

    private long id(String key) {
        return ShopFixture.symbolId(jdbc, key);
    }

    @Test
    void behaviorChangeOfALibraryMethodReachesEndpointsAcrossRepositories() {
        ImpactResult result = impact.analyze(new ImpactRequest(
                List.of(id("com.shop.lib.PriceFormatter#format(int)")), null, 3, null, null));

        assertThat(result.nodes()).extracting(ImpactNode::key, ImpactNode::level).contains(
                tuple("com.shop.api.CheckoutService#label(int)", 1),
                tuple("com.shop.legacy.LegacyReport#print()", 1),
                tuple("com.shop.api.CheckoutService#checkout(int)", 2),
                tuple("com.shop.api.OrderController#checkout()", 3),
                tuple("com.shop.api.NightlyJob#run()", 3));
        assertThat(result.entryPoints())
                .extracting(EntryPoint::key, EntryPoint::label, EntryPoint::httpMethod, EntryPoint::httpPath)
                .containsExactly(
                        tuple("com.shop.api.NightlyJob#run()", "Zamanlanmış görev", null, null),
                        tuple("com.shop.api.OrderController#checkout()", "HTTP", "POST", "/orders/checkout"));
        assertThat(result.summary().repositories()).isEqualTo(1);
        assertThat(result.summary().partialClasspathRepositories()).containsExactly(ShopFixture.API_REPO);
        assertThat(result.repositories()).extracting(RepoState::lastIndexedCommit).containsOnly("api-1");
    }

    @Test
    void exactOnlyDropsTheNameOnlyGuess() {
        ImpactResult result = impact.analyze(new ImpactRequest(
                List.of(id("com.shop.lib.PriceFormatter#format(int)")), null, 1, Set.of(Confidence.EXACT), null));

        assertThat(result.nodes()).extracting(ImpactNode::key).doesNotContain("com.shop.legacy.LegacyReport#print()");
        assertThat(result.summary().partialClasspathRepositories()).isEmpty();
    }

    @Test
    void implementationChangeReachesCallersThroughDispatch() {
        ImpactResult result = impact.analyze(new ImpactRequest(
                List.of(id("com.shop.api.CardGateway#charge(int)")), null, 2, null, null));

        assertThat(result.edges()).filteredOn(ImpactEdge::viaDispatch).extracting(ImpactEdge::level)
                .containsExactly(1);
        assertThat(result.nodes()).extracting(ImpactNode::key)
                .contains("com.shop.api.CheckoutService#checkout(int)", "com.shop.api.OrderController#checkout()");
    }

    @Test
    void signatureChangeOfAnInterfaceMethodListsImplementationsAndDirectCallers() {
        ImpactResult result = impact.analyze(new ImpactRequest(
                List.of(id("com.shop.lib.PaymentGateway#charge(int)")), ChangeType.SIGNATURE, null, null, null));

        assertThat(result.edges()).extracting(e -> e.kind().name(), ImpactEdge::level).containsExactlyInAnyOrder(
                tuple("CALL", 1), tuple("OVERRIDES", 1));
    }

    @Test
    void rulesAndEntryPointLabelsAreReadFromTheDatabase() {
        long format = id("com.shop.lib.PriceFormatter#format(int)");
        try {
            jdbc.update("UPDATE impact_relation_rule SET propagates = 0 WHERE usage_kind = 'CALL'");
            jdbc.update("UPDATE entry_point_annotation SET enabled = 0 "
                    + "WHERE annotation_fqn = 'org.springframework.scheduling.annotation.Scheduled'");

            ImpactResult noPropagation = impact.analyze(new ImpactRequest(List.of(format), null, 3, null, null));
            assertThat(noPropagation.nodes()).extracting(ImpactNode::level).containsOnly(0, 1);

            jdbc.update("UPDATE impact_relation_rule SET propagates = 1 WHERE usage_kind = 'CALL'");
            ImpactResult noJob = impact.analyze(new ImpactRequest(List.of(format), null, 3, null, null));
            assertThat(noJob.entryPoints()).extracting(EntryPoint::key)
                    .containsExactly("com.shop.api.OrderController#checkout()");
        } finally {
            jdbc.update("UPDATE impact_relation_rule SET propagates = 1 WHERE usage_kind = 'CALL'");
            jdbc.update("UPDATE entry_point_annotation SET enabled = 1");
        }
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -Dtest='JdbcImpactGraphTest,ImpactServiceTest'`
Expected: BUILD FAILURE, `cannot find symbol` for `JdbcImpactGraph` and `ImpactService`.

- [ ] **Step 3: Write `JdbcImpactGraph`**

`src/main/java/com/graphify/impact/JdbcImpactGraph.java`:

```java
package com.graphify.impact;

import com.graphify.indexer.model.Confidence;
import com.graphify.indexer.model.SymbolKind;
import com.graphify.indexer.model.UsageKind;
import com.graphify.store.Chunks;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@link ImpactGraph} over Oracle; every IN list is split at Oracle's 1000-item limit. */
@Repository
public class JdbcImpactGraph implements ImpactGraph {

    private final JdbcTemplate jdbc;

    public JdbcImpactGraph(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<Long, ImpactSymbol> symbols(Collection<Long> ids) {
        Map<Long, ImpactSymbol> found = new LinkedHashMap<>();
        for (List<Long> chunk : chunks(ids)) {
            jdbc.query("SELECT id, symbol_key, kind, display_signature, class_fqn, parent_id FROM symbol WHERE id IN ("
                    + Chunks.placeholders(chunk.size()) + ")", rs -> {
                        long id = rs.getLong("id");
                        long parent = rs.getLong("parent_id");
                        found.put(id, new ImpactSymbol(id, rs.getString("symbol_key"),
                                SymbolKind.valueOf(rs.getString("kind")), rs.getString("display_signature"),
                                rs.getString("class_fqn"), rs.wasNull() ? null : parent));
                    }, chunk.toArray());
        }
        return found;
    }

    @Override
    public List<Long> descendants(long typeId) {
        return jdbc.queryForList("SELECT id FROM symbol START WITH parent_id = ? CONNECT BY PRIOR id = parent_id",
                Long.class, typeId);
    }

    @Override
    public Optional<Long> idOfKey(String key) {
        return jdbc.queryForList("SELECT id FROM symbol WHERE symbol_key = ?", Long.class, key).stream().findFirst();
    }

    @Override
    public List<ImpactUsage> usagesTo(Collection<Long> targetIds, Set<UsageKind> kinds, Set<Confidence> confidences) {
        if (targetIds.isEmpty() || kinds.isEmpty() || confidences.isEmpty()) {
            return List.of();
        }
        List<ImpactUsage> found = new ArrayList<>();
        for (List<Long> chunk : chunks(targetIds)) {
            List<Object> args = new ArrayList<>(chunk);
            kinds.forEach(k -> args.add(k.name()));
            confidences.forEach(c -> args.add(c.name()));
            found.addAll(jdbc.query("""
                    SELECT id, from_symbol_id, to_symbol_id, kind, confidence, module_id, file_path, line_no, column_no,
                           snippet
                      FROM usage
                     WHERE to_symbol_id IN (%s) AND kind IN (%s) AND confidence IN (%s)
                     ORDER BY id
                    """.formatted(Chunks.placeholders(chunk.size()), Chunks.placeholders(kinds.size()),
                            Chunks.placeholders(confidences.size())),
                    (rs, row) -> new ImpactUsage(rs.getLong("id"), rs.getLong("from_symbol_id"),
                            rs.getLong("to_symbol_id"), UsageKind.valueOf(rs.getString("kind")),
                            Confidence.valueOf(rs.getString("confidence")), rs.getLong("module_id"),
                            rs.getString("file_path"), rs.getInt("line_no"), rs.getInt("column_no"),
                            rs.getString("snippet")),
                    args.toArray()));
        }
        return found;
    }

    @Override
    public Map<Long, List<Long>> overriddenMethods(Collection<Long> symbolIds) {
        Map<Long, List<Long>> found = new LinkedHashMap<>();
        for (List<Long> chunk : chunks(symbolIds)) {
            jdbc.query("SELECT from_symbol_id, to_symbol_id FROM usage WHERE kind = 'OVERRIDES' AND from_symbol_id IN ("
                    + Chunks.placeholders(chunk.size()) + ") ORDER BY id", rs -> {
                        found.computeIfAbsent(rs.getLong("from_symbol_id"), k -> new ArrayList<>())
                                .add(rs.getLong("to_symbol_id"));
                    }, chunk.toArray());
        }
        return found;
    }

    @Override
    public List<AnnotationUse> annotationsOn(Collection<Long> symbolIds, Collection<String> annotationKeys) {
        if (symbolIds.isEmpty() || annotationKeys.isEmpty()) {
            return List.of();
        }
        List<String> keys = List.copyOf(new LinkedHashSet<>(annotationKeys));
        List<AnnotationUse> found = new ArrayList<>();
        for (List<Long> ids : chunks(symbolIds)) {
            for (List<String> keyChunk : Chunks.of(keys, Chunks.MAX_IN_LIST)) {
                List<Object> args = new ArrayList<>(ids);
                args.addAll(keyChunk);
                found.addAll(jdbc.query("""
                        SELECT u.from_symbol_id, t.symbol_key, u.snippet, u.module_id
                          FROM usage u JOIN symbol t ON t.id = u.to_symbol_id
                         WHERE u.kind = 'ANNOTATION' AND u.from_symbol_id IN (%s) AND t.symbol_key IN (%s)
                         ORDER BY u.id
                        """.formatted(Chunks.placeholders(ids.size()), Chunks.placeholders(keyChunk.size())),
                        (rs, row) -> new AnnotationUse(rs.getLong("from_symbol_id"), rs.getString("symbol_key"),
                                rs.getString("snippet"), rs.getLong("module_id")),
                        args.toArray()));
            }
        }
        return found;
    }

    @Override
    public Map<Long, RepoState> repoStates(Collection<Long> moduleIds) {
        Map<Long, RepoState> found = new LinkedHashMap<>();
        for (List<Long> chunk : chunks(moduleIds)) {
            jdbc.query("""
                    SELECT m.id AS module_id, r.id AS repo_id, r.slug, m.path, m.classpath_mode,
                           r.last_indexed_commit, r.last_indexed_at
                      FROM maven_module m JOIN scm_repository r ON r.id = m.repo_id
                     WHERE m.id IN (%s)
                    """.formatted(Chunks.placeholders(chunk.size())), rs -> {
                        OffsetDateTime at = rs.getObject("last_indexed_at", OffsetDateTime.class);
                        found.put(rs.getLong("module_id"), new RepoState(rs.getLong("module_id"), rs.getLong("repo_id"),
                                rs.getString("slug"), rs.getString("path"), rs.getString("classpath_mode"),
                                rs.getString("last_indexed_commit"), at == null ? null : at.toInstant()));
                    }, chunk.toArray());
        }
        return found;
    }

    private static List<List<Long>> chunks(Collection<Long> ids) {
        return Chunks.of(List.copyOf(new LinkedHashSet<>(ids)), Chunks.MAX_IN_LIST);
    }
}
```

- [ ] **Step 4: Write `ImpactRules` and `ImpactService`**

`src/main/java/com/graphify/impact/ImpactRules.java`:

```java
package com.graphify.impact;

import com.graphify.indexer.model.UsageKind;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads impact_relation_rule and the enabled rows of entry_point_annotation (spec §6.2). */
@Repository
public class ImpactRules {

    private final JdbcTemplate jdbc;

    public ImpactRules(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<UsageKind, ImpactRule> rules() {
        Map<UsageKind, ImpactRule> rules = new EnumMap<>(UsageKind.class);
        jdbc.query("SELECT usage_kind, propagates, shown_at_level1 FROM impact_relation_rule", rs -> {
            UsageKind kind = UsageKind.valueOf(rs.getString("usage_kind"));
            rules.put(kind, new ImpactRule(kind, rs.getInt("propagates") == 1, rs.getInt("shown_at_level1") == 1));
        });
        return rules;
    }

    public Map<String, String> entryPointLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        jdbc.query("SELECT annotation_fqn, label FROM entry_point_annotation WHERE enabled = 1 ORDER BY annotation_fqn",
                rs -> {
                    labels.put(rs.getString("annotation_fqn"), rs.getString("label"));
                });
        return labels;
    }
}
```

`src/main/java/com/graphify/impact/ImpactService.java`:

```java
package com.graphify.impact;

import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import org.springframework.stereotype.Service;

/** Runs the impact engine with limits from AppSettings and rules/labels from the database. */
@Service
public class ImpactService {

    private final JdbcImpactGraph graph;
    private final ImpactRules rules;
    private final AppSettings settings;

    public ImpactService(JdbcImpactGraph graph, ImpactRules rules, AppSettings settings) {
        this.graph = graph;
        this.rules = rules;
        this.settings = settings;
    }

    public ImpactResult analyze(ImpactRequest request) {
        ImpactLimits limits = new ImpactLimits(
                settings.getInt(SettingKeys.IMPACT_DEFAULT_DEPTH),
                settings.getInt(SettingKeys.IMPACT_MAX_DEPTH),
                settings.getInt(SettingKeys.IMPACT_MAX_RESULTS));
        return new ImpactEngine(graph).analyze(request, limits, rules.rules(), rules.entryPointLabels());
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw test -Dtest='JdbcImpactGraphTest,ImpactServiceTest'`
Expected: 8 tests pass.

If a scenario differs, compare the engine's unit test for the same rule (Task 5) with the fixture edges pinned in `ShopFixtureTest`. Fix the adapter or the engine. Do not change the expected scenario.

Run: `./mvnw test`
Expected: all tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/graphify/impact src/test/java/com/graphify/impact
git commit -m "feat(impact): add Oracle impact graph and settings-driven impact service" -m "<your harness Co-Authored-By trailer>"
```

---

### Task 8: Impact REST API, CSV export and documentation

**Files:**
- Create: `src/main/java/com/graphify/impact/ImpactController.java`, `ImpactCsv.java`
- Modify: `README.md`
- Test: `src/test/java/com/graphify/impact/ImpactCsvTest.java`, `ImpactApiTest.java`

**Interfaces:**
- Consumes `ImpactService.analyze(ImpactRequest)` and `ImpactResult`.
- Produces:
  - `POST /api/v1/impact` with an `ImpactRequest` JSON body, returning `ImpactResult` JSON.
  - `POST /api/v1/impact/export?format=csv` with the same body, returning `text/csv; charset=UTF-8` with `Content-Disposition: attachment; filename="impact.csv"`.
  - `final class ImpactCsv` with `static String write(ImpactResult result)`.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/graphify/impact/ImpactCsvTest.java`:

```java
package com.graphify.impact;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.indexer.model.Confidence;
import com.graphify.indexer.model.SymbolKind;
import com.graphify.indexer.model.UsageKind;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ImpactCsvTest {

    @Test
    void writesOneRowPerEdgeAndEscapesFields() {
        ImpactNode seed = new ImpactNode(1, "lib.F#format(int)", SymbolKind.METHOD, "F.format(int)", 0, NodeRole.SEED);
        ImpactNode caller = new ImpactNode(2, "api.S#label(int)", SymbolKind.METHOD, "S.label(int)", 1, NodeRole.AFFECTED);
        ImpactEdge edge = new ImpactEdge(2, 1, UsageKind.CALL, Confidence.EXACT, 1, false, 7, "shop-api", "shop-api",
                "S.java", 12, 5, "return f.format(\"a,b\");");
        ImpactResult result = new ImpactResult(new ImpactSummary(1, 1, 1, 1, 1, Map.of(), Map.of(), List.of()),
                List.of(seed, caller), List.of(edge), List.of(), List.of(), false);

        String csv = ImpactCsv.write(result);

        assertThat(csv.lines().toList()).containsExactly(
                "level,repository,module,file,line,from,kind,to,confidence,via_dispatch,snippet",
                "1,shop-api,shop-api,S.java,12,S.label(int),CALL,F.format(int),EXACT,false,"
                        + "\"return f.format(\"\"a,b\"\");\"");
    }
}
```

`src/test/java/com/graphify/impact/ImpactApiTest.java`:

```java
package com.graphify.impact;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.store.RepositoryIndexWriter;
import com.graphify.testsupport.ShopFixture;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImpactApiTest extends OracleIntegrationTest {

    @Autowired
    RepositoryIndexWriter writer;

    @TempDir
    static Path work;

    private long format;

    @BeforeAll
    void load() throws Exception {
        ShopFixture.load(jdbc, writer, work);
        format = ShopFixture.symbolId(jdbc, "com.shop.lib.PriceFormatter#format(int)");
    }

    private String body(String json) {
        return json.replace("FORMAT", Long.toString(format));
    }

    @Test
    void analyzesImpactAsJson() {
        assertThat(mvc.post().uri("/api/v1/impact").contentType(MediaType.APPLICATION_JSON)
                .content(body("{\"symbolIds\":[FORMAT],\"depth\":3}"))).hasStatusOk().bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.summary.repositories").isEqualTo(1);
                    assertThat(json).extractingPath("$.truncated").isEqualTo(false);
                    assertThat(json).extractingPath("$.entryPoints[1].httpPath").isEqualTo("/orders/checkout");
                    assertThat(json).extractingPath("$.nodes[0].role").isEqualTo("SEED");
                });
    }

    @Test
    void exportsCsv() {
        assertThat(mvc.post().uri("/api/v1/impact/export?format=csv").contentType(MediaType.APPLICATION_JSON)
                .content(body("{\"symbolIds\":[FORMAT],\"depth\":1,\"confidences\":[\"EXACT\"]}")))
                .hasStatusOk()
                .hasHeader("Content-Disposition", "attachment; filename=\"impact.csv\"")
                .hasContentTypeCompatibleWith("text/csv")
                .bodyText().startsWith("level,repository,module,file,line,from,kind,to,confidence,via_dispatch,snippet")
                .contains("CheckoutService.label(int),CALL,PriceFormatter.format(int),EXACT");
    }

    @Test
    void invalidRequestsAreProblemDetails() {
        assertThat(mvc.post().uri("/api/v1/impact").contentType(MediaType.APPLICATION_JSON)
                .content(body("{\"symbolIds\":[FORMAT],\"depth\":99}"))).hasStatus(400).bodyJson()
                .extractingPath("$.detail").asString().contains("between 1 and 10");
        assertThat(mvc.post().uri("/api/v1/impact").contentType(MediaType.APPLICATION_JSON)
                .content("{\"symbolIds\":[]}")).hasStatus(400);
        assertThat(mvc.post().uri("/api/v1/impact").contentType(MediaType.APPLICATION_JSON)
                .content("{\"symbolIds\":[-1]}")).hasStatus(404);
        assertThat(mvc.post().uri("/api/v1/impact").contentType(MediaType.APPLICATION_JSON)
                .content(body("{\"symbolIds\":[FORMAT],\"changeType\":\"SOMETIMES\"}"))).hasStatus(400);
        assertThat(mvc.post().uri("/api/v1/impact").contentType(MediaType.APPLICATION_JSON)
                .content("not json")).hasStatus(400);
        assertThat(mvc.post().uri("/api/v1/impact/export?format=xlsx").contentType(MediaType.APPLICATION_JSON)
                .content(body("{\"symbolIds\":[FORMAT]}"))).hasStatus(400);
    }

    @Test
    void openApiDocumentListsTheImpactAndSymbolEndpoints() {
        assertThat(mvc.get().uri("/api/v1/openapi.json")).hasStatusOk().bodyJson().extractingPath("$.paths").asMap()
                .containsKeys("/api/v1/impact", "/api/v1/impact/export", "/api/v1/symbols/search",
                        "/api/v1/symbols/{id}", "/api/v1/symbols/{id}/usages", "/api/v1/repositories");
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -Dtest='ImpactCsvTest,ImpactApiTest'`
Expected: BUILD FAILURE, `cannot find symbol ... ImpactCsv`. Once that compiles, the API tests return 404 because there is no controller yet.

- [ ] **Step 3: Write `ImpactCsv` and `ImpactController`**

`src/main/java/com/graphify/impact/ImpactCsv.java`:

```java
package com.graphify.impact;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/** One CSV row per impact edge (RFC 4180 quoting), for spreadsheets. */
final class ImpactCsv {

    private static final List<String> HEADER = List.of("level", "repository", "module", "file", "line", "from",
            "kind", "to", "confidence", "via_dispatch", "snippet");

    private ImpactCsv() {
    }

    static String write(ImpactResult result) {
        Map<Long, String> display = new HashMap<>();
        result.nodes().forEach(node -> display.put(node.symbolId(), node.display()));
        StringBuilder csv = new StringBuilder(String.join(",", HEADER)).append("\r\n");
        for (ImpactEdge edge : result.edges()) {
            StringJoiner row = new StringJoiner(",");
            row.add(Integer.toString(edge.level()));
            row.add(field(edge.repository()));
            row.add(field(edge.modulePath()));
            row.add(field(edge.filePath()));
            row.add(Integer.toString(edge.line()));
            row.add(field(display.getOrDefault(edge.fromSymbolId(), Long.toString(edge.fromSymbolId()))));
            row.add(edge.kind().name());
            row.add(field(display.getOrDefault(edge.toSymbolId(), Long.toString(edge.toSymbolId()))));
            row.add(edge.confidence().name());
            row.add(Boolean.toString(edge.viaDispatch()));
            row.add(field(edge.snippet()));
            csv.append(row).append("\r\n");
        }
        return csv.toString();
    }

    private static String field(String value) {
        if (value == null) {
            return "";
        }
        boolean quote = value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r");
        return quote ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }
}
```

`src/main/java/com/graphify/impact/ImpactController.java`:

```java
package com.graphify.impact;

import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/impact")
public class ImpactController {

    private static final MediaType TEXT_CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final ImpactService impact;

    public ImpactController(ImpactService impact) {
        this.impact = impact;
    }

    @PostMapping
    public ImpactResult analyze(@RequestBody ImpactRequest request) {
        return impact.analyze(request);
    }

    @PostMapping("/export")
    public ResponseEntity<String> export(@RequestParam String format, @RequestBody ImpactRequest request) {
        if (!"csv".equalsIgnoreCase(format)) {
            throw new IllegalArgumentException("Unsupported export format: " + format + " (supported: csv)");
        }
        return ResponseEntity.ok()
                .contentType(TEXT_CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"impact.csv\"")
                .body(ImpactCsv.write(impact.analyze(request)));
    }
}
```

- [ ] **Step 4: Document the API in `README.md`**

Add this section before `## Tests`:

````markdown
## API

All endpoints are under `/api/v1`. The OpenAPI document is served at `/api/v1/openapi.json`. Errors are RFC 7807
problem details: 400 for invalid input, 404 for an unknown id.

| Method | Path | Purpose |
|---|---|---|
| GET | `/symbols/search?q=RestTemplate.exchange&kind=&repo=` | Find classes/methods/fields (case-insensitive); overloads are separate hits |
| GET | `/symbols/{id}` | Declarations, members, overrides, super/subtypes |
| GET | `/symbols/{id}/usages?confidence=&kind=&repo=` | Every usage site, paged |
| GET | `/symbols/{id}/usages/summary` | Usages grouped by repository → module → class |
| POST | `/impact` | Impact analysis (`{"symbolIds":[..],"changeType":"BEHAVIOR","depth":3}`) |
| POST | `/impact/export?format=csv` | The same analysis as CSV |
| GET | `/repositories`, `/repositories/{id}` | Indexed repositories, modules and freshness |

Paging uses `?page=0&size=N`. The defaults and limits come from the `api.page_*` settings; impact depth and result
limits come from the `impact.*` settings.
````

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw test -Dtest='ImpactCsvTest,ImpactApiTest'`
Expected: 5 tests pass.

The `"not json"` and `"SOMETIMES"` cases rely on `spring.mvc.problemdetails.enabled`. Jackson fails to read the body, and Spring renders a 400 `ProblemDetail` for `HttpMessageNotReadableException`.

Run: `./mvnw test`
Expected: all tests pass, BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/graphify/impact src/test/java/com/graphify/impact README.md
git commit -m "feat(impact): expose impact analysis and CSV export over REST" -m "<your harness Co-Authored-By trailer>"
```
