# Plan 2 — Persistence & Settings Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist each repository's index result in Oracle, so that "where is X used and in how many projects" can be answered with SQL. Also add the DB-backed settings that make "no hardcoded values" possible, and the secret encryption used by every later credential.

**Architecture:**
- **Oracle schema:** owned by Flyway migrations. The plain `JdbcTemplate` stores the data; there is no JPA.
- **`RepositoryIndexWriter`:** replaces one repository's modules, declarations and usages in a single transaction. Symbols are shared across repositories. They are merged by `symbol_key` with a precedence rank, so a SOURCE declaration wins over a BINARY reference and a binding wins over a name-only guess.
- **Settings:** `AppSettings` reads typed values from `APP_SETTING`. Seeds live only in Flyway SQL. It validates updates, writes `AUDIT_LOG` and publishes a change event.
- **Secrets:** `SecretCipher` encrypts them with AES-GCM using `APP_MASTER_KEY`.
- **Indexer fixes:** Task 1 first closes the plan-1 key gaps that would otherwise be written into the database.

**Tech Stack:** Java 25, Spring Boot 4.1.1 (`spring-boot-starter-jdbc`, `spring-boot-starter-flyway`), Flyway 12.4.0 + `flyway-database-oracle`, Oracle JDBC `ojdbc11` 23.26.3.0.0, Testcontainers 2.0.5 `testcontainers-oracle-free` with `gvenzl/oracle-free:23-slim-faststart`, JUnit 5 + AssertJ.

**Spec:** `docs/superpowers/specs/2026-10-05-impact-analyzer-design.md` (§4 data model, §6 configuration, §8 error rules, §11 Oracle row). Carried items: `docs/superpowers/plans/2026-10-06-plan1-followups.md` ("Must do first in plan 2").

## Plan series

Plan 2 of 6. Done: plan 1, the Java indexer core. Next:
- Plan 3: search, impact BFS, entry points and the REST API. Seeds `ENTRY_POINT_ANNOTATION` and `IMPACT_RELATION_RULE`.
- Plan 4: Bitbucket, git, the Maven classpath, `MODULE_DEPENDENCY`, `INDEX_RUN`/`INDEX_RUN_REPO`/`INDEX_LOCK`, the scheduler (including orphan cleanup), and building `IndexerOptions` from settings.
- Plan 5: auth, `LDAP_CONFIG`/`LOCAL_ACCOUNT`/`APP_USER`, and the admin APIs, including `/admin/settings` over `AppSettings`.
- Plan 6: repo graph.

## Global Constraints

- JDK 25: every command runs with `export JAVA_HOME=/opt/homebrew/opt/openjdk@25`. Integration tests need Docker running. The image `gvenzl/oracle-free:23-slim-faststart` is already pulled.
- Spring Boot parent `4.1.1`, `<java.version>25</java.version>`. All dependency versions come from Boot's BOM: Flyway 12.4.0, ojdbc11 23.26.3.0.0 and Testcontainers 2.0.5. Do not pin versions.
- **"Kodda sabit değer yok"** (no hardcoded values in code, spec §6.1):
  - Tuning values (batch sizes and the like) are read from `AppSettings`.
  - Their defaults exist only as Flyway seed rows.
  - The only environment-derived values are `DB_URL`, `DB_USER`, `DB_PASSWORD`, `APP_MASTER_KEY` and `SPRING_PROFILES_ACTIVE`.
  - Fixed platform limits are named constants with a comment, never settings: Oracle's 1000-item `IN` list, the column byte widths in the V1 schema, and the AES-GCM parameters.
- **Data access:** plain `JdbcTemplate`, no JPA. Controller ruling for this plan.
- **Schema names:** table and column names are exactly those in `V1__core_schema.sql` (Task 2). `usage.line_no` and `usage.column_no` stand for the spec's `line` and `col`.
- **Text widths:** all text columns are `VARCHAR2(n BYTE)`. Strings are cut to the byte width with `Utf8.truncateToBytes`, which never splits a code point. A symbol whose `symbol_key` (> 4000 bytes), `class_fqn` (> 2000 bytes) or `member_name` (> 1000 bytes) does not fit is skipped and counted. The same applies to any row whose `file_path` exceeds 1000 bytes.
- **Symbol precedence rank:** name-only = 0 < BINARY = 1 < SOURCE = 2. A MERGE updates a stored symbol only when the incoming rank is strictly higher.
- **Module paths:** must be non-blank, because Oracle stores `''` as NULL. The root module of a single-module repository uses the path `.`.
- **Feature packages:**
  - `com.graphify.settings`
  - `com.graphify.audit`
  - `com.graphify.common.crypto`
  - `com.graphify.common.util`
  - `com.graphify.store`
  - `com.graphify.indexer` (Task 1 only)
- **Integration tests:** every Oracle-backed test extends `com.graphify.OracleIntegrationTest` (Task 2), so all of them share one cached Spring context and one container.
- **Commits:** messages end with the Co-Authored-By attribution trailer the committing agent's harness provides.

## Review Focus

1. **Two repositories indexed at the same time that share symbols.** Every repo references `java.lang.String`, for example. This must not fail with ORA-00001 or deadlock. Test: Task 5 `SymbolWriterTest.concurrentWritersOfTheSameNewSymbolsBothSucceed`.
2. **Re-indexing a repository at the same commit,** or after code changes, must not duplicate rows or leave stale ones. Test: Task 6 `RepositoryIndexWriterTest.rewritingReplacesInsteadOfDuplicating`.
3. **A write that fails partway** (a DB error after the old rows were deleted) must leave the previous index intact. Test: Task 6 `RepositoryIndexWriterTest.failedWriteKeepsThePreviousIndex`.
4. **Multibyte text longer than a column's byte width,** for example Turkish snippets, must be stored truncated, not rejected with ORA-12899. Test: Task 6 `RepositoryIndexWriterTest.oversizedMultibyteSnippetIsTruncatedToTheColumnWidth`.
5. **The app started without `APP_MASTER_KEY`, or with a malformed key,** must fail at startup with a message that names the variable, not later when a secret is first used. Test: Task 4 `SecretCipherTest.rejectsKeysThatAreNotBase64Of32Bytes`.

---

## File Structure

| File | Responsibility |
|---|---|
| `src/main/java/com/graphify/indexer/MethodKeys.java` (modify) | Qualify recovered parameter types from their source spelling |
| `src/main/java/com/graphify/indexer/ImportResolver.java` (modify) | Qualified nested names → binary `$` form |
| `src/main/java/com/graphify/indexer/SourceLines.java` (modify) | Surrogate-safe truncation |
| `src/main/java/com/graphify/indexer/model/Symbol.java`, `SymbolRegistry.java` (modify) | `nameOnly` flag for DB precedence |
| `pom.xml`, `src/main/resources/application.yml`, `README.md` (modify) | JDBC/Flyway/Oracle/Testcontainers wiring; env-only connection config |
| `src/main/resources/db/migration/V1__core_schema.sql` | Tables: `app_setting`, `audit_log`, `scm_connection`, `scm_repository`, `maven_module`, `symbol`, `symbol_declaration`, `usage` |
| `src/main/resources/db/migration/V2__seed_settings.sql` | Spec §6.3 seed rows + `store.jdbc_batch_size` |
| `src/main/java/com/graphify/common/util/Utf8.java` | Byte-length and code-point-safe byte truncation |
| `src/main/java/com/graphify/audit/AuditLog.java` | Append audit rows |
| `src/main/java/com/graphify/settings/*.java` | `SettingType`, `Setting`, `SettingKeys`, `SettingsRepository`, `AppSettings`, `SettingChangedEvent`, exceptions |
| `src/main/java/com/graphify/common/crypto/SecretCipher.java`, `SecretDecryptionException.java` | AES-256-GCM secret encryption |
| `src/main/java/com/graphify/store/Chunks.java`, `StoreLimits.java` | Partitioning; column byte limits |
| `src/main/java/com/graphify/store/SymbolWriter.java` | Rank-aware symbol MERGE, key → id lookup, parent links |
| `src/main/java/com/graphify/store/ModuleWriter.java` | Replace a repository's module rows |
| `src/main/java/com/graphify/store/ClasspathMode.java`, `ModuleRecord.java`, `RepositoryIndex.java`, `WriteSummary.java`, `RepositoryNotFoundException.java` | Public write API types |
| `src/main/java/com/graphify/store/RepositoryIndexWriter.java` | Transactional replace of one repository's index |
| `src/main/java/com/graphify/store/SymbolCleanup.java` | Delete symbols nothing references |
| `src/test/java/com/graphify/TestcontainersConfiguration.java`, `OracleIntegrationTest.java`, `TestGraphifyApplication.java` | Shared Oracle container, test base class, local dev runner |
| `src/test/java/com/graphify/store/StoreFixtures.java` | Insert test repositories; clean index tables |
| `src/test/resources/config/application.yml` | Test-only `app.master-key` |

---

### Task 1: Close plan-1 key gaps before keys reach the database

**Files:**
- Modify: `src/main/java/com/graphify/indexer/MethodKeys.java`
- Modify: `src/main/java/com/graphify/indexer/ImportResolver.java`
- Modify: `src/main/java/com/graphify/indexer/SourceLines.java`
- Modify: `src/main/java/com/graphify/indexer/model/Symbol.java`
- Modify: `src/main/java/com/graphify/indexer/SymbolRegistry.java`
- Modify: `docs/superpowers/plans/2026-10-06-plan1-followups.md`
- Test: `src/test/java/com/graphify/indexer/RecoveredSignatureTest.java`, `ImportResolverTest.java`, `SourceLinesTest.java`, `SymbolRegistryTest.java`

**Interfaces:**
- Consumes the existing plan-1 code. `MethodKeys.parameterTypeName` currently builds the name with `simpleName(element.getErasure())`. `ImportResolver.resolve` returns a lowercase-qualified name unchanged. `SourceLines.truncate` uses `substring`.
- Produces:
  - `public record Symbol(String key, SymbolKind kind, String classFqn, String memberName, String displaySignature, String parentKey, SymbolOrigin origin, boolean nameOnly)`. `nameOnly` is true only for symbols created by `SymbolRegistry.nameOnly*` that no binding has since replaced.
  - Method keys qualify a missing parameter type from its source spelling.

Background, verified with JDT 3.47: for a parameter type missing from the classpath, `ITypeBinding.getBinaryName()` returns the spelling as written in source, without type arguments. Examples are `OrderDto`, `Outer.Inner` and `com.corp.dto.OrderDto`. `getName()` returns only the last segment, which is why `void f(com.corp.dto.OrderDto d)` was keyed `com.corp.app.S#f(com.corp.app.OrderDto)`.

- [ ] **Step 1: Write the failing tests**

Append to `src/test/java/com/graphify/indexer/RecoveredSignatureTest.java`, inside the class:

```java
    @Test
    void qualifiedSpellingOfMissingTypeKeepsItsOwnPackage() throws Exception {
        IndexResult result = TempRepo.at(dir).java("app", "com/corp/app/Q.java", """
                package com.corp.app;
                public class Q { public void handle(com.corp.dto.OrderDto d) {} }
                """).index();

        assertThat(result.declarations()).extracting(Declaration::symbolKey)
                .contains("com.corp.app.Q#handle(com.corp.dto.OrderDto)");
        assertThat(result.warnings()).noneMatch(w -> w.message().contains("OrderDto"));
    }

    @Test
    void nestedSpellingsResolveToTheBinaryNestedName() throws Exception {
        IndexResult result = TempRepo.at(dir).java("app", "com/corp/app/N.java", """
                package com.corp.app;
                import com.x.Outer;
                public class N {
                    public void take(Outer.Inner i) {}
                    public void full(com.x.Outer.Inner i) {}
                }
                """).index();

        assertThat(result.declarations()).extracting(Declaration::symbolKey).contains(
                "com.corp.app.N#take(com.x.Outer$Inner)",
                "com.corp.app.N#full(com.x.Outer$Inner)");
    }

    @Test
    void qualifiedAndNestedSpellingsMatchTheKeysBuiltWithTheJar() throws Exception {
        Path jar = TestJars.jar(dir.resolve("jars"), "x", Map.of(
                "com/x/Outer.java", "package com.x; public class Outer { public static class Inner {} }",
                "com/corp/dto/OrderDto.java", "package com.corp.dto; public class OrderDto {}"), Set.of());
        String source = """
                package com.corp.app;
                import com.x.Outer;
                public class J {
                    public void take(Outer.Inner i) {}
                    public void handle(com.corp.dto.OrderDto d) {}
                }
                """;

        IndexResult without = TempRepo.at(dir.resolve("a")).java("app", "com/corp/app/J.java", source).index();
        IndexResult with = TempRepo.at(dir.resolve("b")).java("app", "com/corp/app/J.java", source)
                .classpath("app", jar).index();

        String take = "com.corp.app.J#take(com.x.Outer$Inner)";
        String handle = "com.corp.app.J#handle(com.corp.dto.OrderDto)";
        assertThat(without.declarations()).extracting(Declaration::symbolKey).contains(take, handle);
        assertThat(with.declarations()).extracting(Declaration::symbolKey).contains(take, handle);
    }
```

Append to `src/test/java/com/graphify/indexer/ImportResolverTest.java`, inside the class:

```java
    @Test
    void qualifiedNestedNamesBecomeBinaryNames() throws Exception {
        ImportResolver resolver = resolverFor("package p; class A {}");

        assertThat(resolver.resolve("com.x.Outer.Inner")).contains("com.x.Outer$Inner");
        assertThat(resolver.resolve("com.x.Outer.Inner.Deep")).contains("com.x.Outer$Inner$Deep");
        assertThat(resolver.resolve("org.vendor.Client")).contains("org.vendor.Client");
    }
```

Append to `src/test/java/com/graphify/indexer/SourceLinesTest.java`, inside the class:

```java
    @Test
    void truncateNeverSplitsASurrogatePair() {
        String withEmoji = "a😀b";

        assertThat(SourceLines.truncate(withEmoji, 2)).isEqualTo("a");
        assertThat(SourceLines.truncate(withEmoji, 3)).isEqualTo("a😀");
    }
```

Append to `src/test/java/com/graphify/indexer/SymbolRegistryTest.java`, inside the class. If missing, add the import `org.eclipse.jdt.core.dom.AbstractTypeDeclaration`; it is already imported in the current file.

```java
    @Test
    void nameOnlyFlagMarksGuessesUntilABindingReplacesThem() throws Exception {
        CompilationUnit unit = ParsedSources.parse(dir, Map.of("com/acme/Api.java", """
                package com.acme;
                public interface Api {}
                """), List.of()).get("com/acme/Api.java");
        SymbolRegistry registry = new SymbolRegistry();

        registry.nameOnlyMethod("org.x.Rest", "exchange", 2);
        registry.nameOnlyType("com.acme.Api");
        registry.type(ParsedSources.find(unit, AbstractTypeDeclaration.class).getFirst().resolveBinding());

        assertThat(registry.all()).extracting(Symbol::key, Symbol::nameOnly).containsExactlyInAnyOrder(
                tuple("org.x.Rest", true),
                tuple("org.x.Rest#exchange/2", true),
                tuple("com.acme.Api", false));
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest='RecoveredSignatureTest,ImportResolverTest,SourceLinesTest,SymbolRegistryTest'`
Expected: test compilation fails with `cannot find symbol ... method nameOnly()` in `SymbolRegistryTest`. Temporarily comment out that one new test and rerun. The other new tests then fail:
- `qualifiedSpellingOfMissingTypeKeepsItsOwnPackage` finds `com.corp.app.Q#handle(com.corp.app.OrderDto)`.
- `qualifiedNestedNamesBecomeBinaryNames` expects `com.x.Outer$Inner` but gets `com.x.Outer.Inner`.
- `truncateNeverSplitsASurrogatePair` expects `"a"`.

Restore the commented test.

- [ ] **Step 3: Add the `nameOnly` flag**

Replace `src/main/java/com/graphify/indexer/model/Symbol.java` with:

```java
package com.graphify.indexer.model;

/**
 * A class, method, constructor or field, identified by its spec §4.2 key.
 *
 * @param classFqn    key of the owning type (the type's own key for type symbols)
 * @param memberName  method/field name, {@code <init>} for constructors, {@code null} for types
 * @param parentKey   enclosing type for members and nested types, {@code null} for top-level types
 * @param nameOnly    {@code true} when the symbol is a guess from imports and names, not from a binding
 */
public record Symbol(
        String key,
        SymbolKind kind,
        String classFqn,
        String memberName,
        String displaySignature,
        String parentKey,
        SymbolOrigin origin,
        boolean nameOnly) {
}
```

In `src/main/java/com/graphify/indexer/SymbolRegistry.java`, add a final `false` argument to the three `putResolved(new Symbol(...))` constructions, in `type`, `method` and `field`. Add a final `true` argument to the three `putNameOnly(new Symbol(...))` constructions, in `nameOnlyType`, `nameOnlyMethod` and `nameOnlyConstructor`. For example, in `type`:

```java
        putResolved(new Symbol(key, kindOf(type), key, null, type.getName(), parent, originOf(type), false));
```

and in `nameOnlyType`:

```java
        putNameOnly(new Symbol(classFqn, SymbolKind.CLASS, classFqn, null, simpleName(classFqn), null,
                SymbolOrigin.BINARY, true));
```

- [ ] **Step 4: Qualify recovered parameter types from their spelling**

In `src/main/java/com/graphify/indexer/MethodKeys.java`, method `parameterTypeName`, replace:

```java
        String name = simpleName(element.getErasure());
```

with:

```java
        String name = spelledName(element.getErasure());
```

Then replace the `simpleName` method at the bottom of the class with:

```java
    /**
     * JDT keeps the source spelling of a type that is missing from the classpath as its binary name
     * ({@code OrderDto}, {@code Outer.Inner}, {@code com.corp.dto.OrderDto}); {@code getName()} keeps only the
     * last segment and would drop a qualifier the source spelled out.
     */
    private static String spelledName(ITypeBinding type) {
        String spelled = type.getBinaryName();
        String name = spelled == null || spelled.isEmpty() ? type.getName() : spelled;
        int typeArguments = name.indexOf('<');
        return typeArguments < 0 ? name : name.substring(0, typeArguments);
    }
```

In `src/main/java/com/graphify/indexer/ImportResolver.java`, method `resolve`, replace:

```java
        if (Character.isLowerCase(typeName.charAt(0))) {
            return looksQualified(typeName) ? Optional.of(typeName) : Optional.empty();
        }
```

with:

```java
        if (Character.isLowerCase(typeName.charAt(0))) {
            return looksQualified(typeName) ? Optional.of(binaryName(typeName)) : Optional.empty();
        }
```

and add this method after `looksQualified`:

```java
    /** {@code com.x.Outer.Inner} → {@code com.x.Outer$Inner}: segments after the first type name are nested types. */
    private static String binaryName(String qualifiedName) {
        String[] segments = qualifiedName.split("\\.");
        StringBuilder binary = new StringBuilder(segments[0]);
        boolean insideType = false;
        for (int i = 1; i < segments.length; i++) {
            binary.append(insideType ? '$' : '.').append(segments[i]);
            insideType |= !segments[i].isEmpty() && Character.isUpperCase(segments[i].charAt(0));
        }
        return binary.toString();
    }
```

- [ ] **Step 5: Make `SourceLines.truncate` surrogate-safe**

In `src/main/java/com/graphify/indexer/SourceLines.java`, replace the `truncate` method with:

```java
    static String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        int end = Character.isHighSurrogate(text.charAt(maxLength - 1)) ? maxLength - 1 : maxLength;
        return text.substring(0, end);
    }
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw test -Dtest='RecoveredSignatureTest,ImportResolverTest,SourceLinesTest,SymbolRegistryTest'`
Expected: all pass.

Run: `./mvnw test`
Expected: `Tests run: 78` (72 existing + 6 new), 0 failures, BUILD SUCCESS.

- [ ] **Step 7: Record what is done in the follow-ups file**

In `docs/superpowers/plans/2026-10-06-plan1-followups.md`, replace the `## Must do first in plan 2` section with:

```markdown
## Must do first in plan 2 (done in plan 2, Task 1)
- DONE: a recovered parameter type spelled qualified (`com.x.T`, `Outer.Inner`) is now keyed from its source spelling (`ITypeBinding.getBinaryName()`), and qualified nested names become `$` binary names.
- RULING (no change): with a single wildcard import, the wildcard keeps precedence over the same package for missing types. A same-package type from the repo's own sources is on the sourcepath and resolves, so a still-missing type is more likely the wildcard's. If wrong: such keys don't join with the classpath-resolved key.
- DONE: `SourceLines.truncate` no longer splits surrogate pairs. Byte-width truncation for Oracle is `Utf8.truncateToBytes` (plan 2, Task 3).
```

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/graphify/indexer src/test/java/com/graphify/indexer docs/superpowers/plans/2026-10-06-plan1-followups.md
git commit -m "fix(indexer): key missing parameter types from their source spelling; add nameOnly flag" -m "<your harness Co-Authored-By trailer>"
```

---

### Task 2: Oracle, Flyway and the core schema

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.yml`
- Modify: `README.md`
- Modify: `src/test/java/com/graphify/GraphifyApplicationTests.java`
- Create: `src/main/resources/db/migration/V1__core_schema.sql`
- Create: `src/test/java/com/graphify/TestcontainersConfiguration.java`
- Create: `src/test/java/com/graphify/OracleIntegrationTest.java`
- Create: `src/test/java/com/graphify/TestGraphifyApplication.java`
- Test: `src/test/java/com/graphify/store/SchemaMigrationTest.java`

**Interfaces:**
- Produces:
  - The V1 tables, with exactly the column names below.
  - `public abstract class OracleIntegrationTest` with `@Autowired protected JdbcTemplate jdbc`. It is annotated `@SpringBootTest @Import(TestcontainersConfiguration.class)`.
  - `TestcontainersConfiguration`, a `@TestConfiguration` with a `@ServiceConnection OracleContainer` bean.

- [ ] **Step 1: Add dependencies to `pom.xml`**

Add inside `<dependencies>`, after the JDT dependency:

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-jdbc</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-flyway</artifactId>
		</dependency>
		<dependency>
			<groupId>org.flywaydb</groupId>
			<artifactId>flyway-database-oracle</artifactId>
		</dependency>
		<dependency>
			<groupId>com.oracle.database.jdbc</groupId>
			<artifactId>ojdbc11</artifactId>
			<scope>runtime</scope>
		</dependency>
```

Add after `spring-boot-starter-test`:

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-testcontainers</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.testcontainers</groupId>
			<artifactId>testcontainers-oracle-free</artifactId>
			<scope>test</scope>
		</dependency>
```

- [ ] **Step 2: Point the datasource at environment variables only**

Replace `src/main/resources/application.yml` with:

```yaml
spring:
  application:
    name: graphify
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:dev}
  datasource:
    url: ${DB_URL}
    username: ${DB_USER}
    password: ${DB_PASSWORD}
```

- [ ] **Step 3: Write the test infrastructure**

`src/test/java/com/graphify/TestcontainersConfiguration.java`:

```java
package com.graphify;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;

/** One Oracle Free container per test JVM; Spring Boot wires the datasource to it. */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    OracleContainer oracleContainer() {
        return new OracleContainer(DockerImageName.parse("gvenzl/oracle-free:23-slim-faststart"));
    }
}
```

`src/test/java/com/graphify/OracleIntegrationTest.java`:

```java
package com.graphify;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** Base for tests against the real Oracle schema. All subclasses share one cached context and container. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
public abstract class OracleIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;
}
```

`src/test/java/com/graphify/TestGraphifyApplication.java`:

```java
package com.graphify;

import org.springframework.boot.SpringApplication;

/** Runs the application locally against a throwaway Oracle container: {@code ./mvnw spring-boot:test-run}. */
public class TestGraphifyApplication {

    public static void main(String[] args) {
        SpringApplication.from(GraphifyApplication::main).with(TestcontainersConfiguration.class).run(args);
    }
}
```

Replace `src/test/java/com/graphify/GraphifyApplicationTests.java` with:

```java
package com.graphify;

import org.junit.jupiter.api.Test;

class GraphifyApplicationTests extends OracleIntegrationTest {

    @Test
    void contextLoads() {
    }
}
```

- [ ] **Step 4: Write the failing schema test**

`src/test/java/com/graphify/store/SchemaMigrationTest.java`:

```java
package com.graphify.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.graphify.OracleIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class SchemaMigrationTest extends OracleIntegrationTest {

    @Test
    void createsTheCoreTables() {
        assertThat(jdbc.queryForList("SELECT LOWER(table_name) FROM user_tables", String.class)).contains(
                "app_setting", "audit_log", "scm_connection", "scm_repository", "maven_module",
                "symbol", "symbol_declaration", "usage");
    }

    @Test
    void indexesTheColumnsImpactQueriesFilterOn() {
        assertThat(jdbc.queryForList("SELECT LOWER(index_name) FROM user_indexes", String.class)).contains(
                "ix_usage_to", "ix_usage_from", "ix_usage_module", "ix_symbol_class_member",
                "ix_symbol_declaration_symbol", "ix_symbol_declaration_module", "uq_symbol_key");
    }

    @Test
    void rejectsUnknownUsageKinds() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO symbol (symbol_key, kind, class_fqn, display_signature, origin, name_only) "
                        + "VALUES ('p.A', 'WIDGET', 'p.A', 'A', 'SOURCE', 0)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
```

- [ ] **Step 5: Run the test to verify it fails**

Run: `./mvnw test -Dtest=SchemaMigrationTest`
Expected: FAIL. `createsTheCoreTables` reports that the actual list does not contain `app_setting, …`, because there is no migration yet.

- [ ] **Step 6: Write the V1 migration**

`src/main/resources/db/migration/V1__core_schema.sql`:

```sql
-- Core schema for plan 2. Text columns are BYTE-sized; writers truncate with Utf8.truncateToBytes.

CREATE TABLE app_setting (
    setting_key   VARCHAR2(100 BYTE)  NOT NULL,
    setting_value VARCHAR2(4000 BYTE) NOT NULL,
    value_type    VARCHAR2(20 BYTE)   NOT NULL,
    description   VARCHAR2(500 BYTE)  NOT NULL,
    min_value     NUMBER(19),
    max_value     NUMBER(19),
    updated_by    VARCHAR2(200 BYTE),
    updated_at    TIMESTAMP,
    CONSTRAINT pk_app_setting PRIMARY KEY (setting_key),
    CONSTRAINT ck_app_setting_type CHECK (value_type IN ('INT', 'STRING', 'BOOL', 'CRON', 'LIST', 'DURATION'))
);

CREATE TABLE audit_log (
    id      NUMBER(19) GENERATED BY DEFAULT AS IDENTITY,
    actor   VARCHAR2(200 BYTE)  NOT NULL,
    action  VARCHAR2(100 BYTE)  NOT NULL,
    target  VARCHAR2(500 BYTE)  NOT NULL,
    details VARCHAR2(4000 BYTE),
    at      TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_audit_log PRIMARY KEY (id)
);
CREATE INDEX ix_audit_log_at ON audit_log (at);

CREATE TABLE scm_connection (
    id               NUMBER(19) GENERATED BY DEFAULT AS IDENTITY,
    name             VARCHAR2(200 BYTE)  NOT NULL,
    type             VARCHAR2(30 BYTE)   NOT NULL,
    base_url         VARCHAR2(1000 BYTE) NOT NULL,
    username         VARCHAR2(200 BYTE),
    secret_enc       VARCHAR2(4000 BYTE),
    include_projects VARCHAR2(4000 BYTE),
    exclude_repos    VARCHAR2(4000 BYTE),
    enabled          NUMBER(1) DEFAULT 1 NOT NULL,
    last_test_status VARCHAR2(30 BYTE),
    last_test_at     TIMESTAMP,
    CONSTRAINT pk_scm_connection PRIMARY KEY (id),
    CONSTRAINT uq_scm_connection_name UNIQUE (name),
    CONSTRAINT ck_scm_connection_type CHECK (type IN ('BITBUCKET_DC')),
    CONSTRAINT ck_scm_connection_enabled CHECK (enabled IN (0, 1))
);

CREATE TABLE scm_repository (
    id                  NUMBER(19) GENERATED BY DEFAULT AS IDENTITY,
    connection_id       NUMBER(19)          NOT NULL,
    project_key         VARCHAR2(200 BYTE)  NOT NULL,
    slug                VARCHAR2(200 BYTE)  NOT NULL,
    clone_url           VARCHAR2(1000 BYTE) NOT NULL,
    default_branch      VARCHAR2(200 BYTE),
    last_indexed_commit VARCHAR2(64 BYTE),
    last_status         VARCHAR2(30 BYTE),
    last_indexed_at     TIMESTAMP,
    active              NUMBER(1) DEFAULT 1 NOT NULL,
    CONSTRAINT pk_scm_repository PRIMARY KEY (id),
    CONSTRAINT fk_scm_repository_connection FOREIGN KEY (connection_id) REFERENCES scm_connection (id),
    CONSTRAINT uq_scm_repository UNIQUE (connection_id, project_key, slug),
    CONSTRAINT ck_scm_repository_active CHECK (active IN (0, 1))
);

CREATE TABLE maven_module (
    id             NUMBER(19) GENERATED BY DEFAULT AS IDENTITY,
    repo_id        NUMBER(19)          NOT NULL,
    path           VARCHAR2(1000 BYTE) NOT NULL,
    group_id       VARCHAR2(300 BYTE),
    artifact_id    VARCHAR2(300 BYTE),
    version        VARCHAR2(100 BYTE),
    classpath_mode VARCHAR2(10 BYTE)   NOT NULL,
    CONSTRAINT pk_maven_module PRIMARY KEY (id),
    CONSTRAINT fk_maven_module_repo FOREIGN KEY (repo_id) REFERENCES scm_repository (id),
    CONSTRAINT uq_maven_module UNIQUE (repo_id, path),
    CONSTRAINT ck_maven_module_cp CHECK (classpath_mode IN ('FULL', 'PARTIAL', 'NONE'))
);

CREATE TABLE symbol (
    id                NUMBER(19) GENERATED BY DEFAULT AS IDENTITY,
    symbol_key        VARCHAR2(4000 BYTE) NOT NULL,
    kind              VARCHAR2(20 BYTE)   NOT NULL,
    class_fqn         VARCHAR2(2000 BYTE) NOT NULL,
    member_name       VARCHAR2(1000 BYTE),
    display_signature VARCHAR2(4000 BYTE) NOT NULL,
    parent_id         NUMBER(19),
    origin            VARCHAR2(10 BYTE)   NOT NULL,
    name_only         NUMBER(1)           NOT NULL,
    artifact          VARCHAR2(500 BYTE),
    CONSTRAINT pk_symbol PRIMARY KEY (id),
    CONSTRAINT uq_symbol_key UNIQUE (symbol_key),
    CONSTRAINT fk_symbol_parent FOREIGN KEY (parent_id) REFERENCES symbol (id) ON DELETE SET NULL,
    CONSTRAINT ck_symbol_kind CHECK (kind IN ('CLASS', 'INTERFACE', 'ENUM', 'RECORD', 'ANNOTATION_TYPE',
                                              'METHOD', 'CONSTRUCTOR', 'FIELD')),
    CONSTRAINT ck_symbol_origin CHECK (origin IN ('SOURCE', 'BINARY')),
    CONSTRAINT ck_symbol_name_only CHECK (name_only IN (0, 1))
);
CREATE INDEX ix_symbol_class_member ON symbol (class_fqn, member_name);
CREATE INDEX ix_symbol_parent ON symbol (parent_id);

CREATE TABLE symbol_declaration (
    id        NUMBER(19) GENERATED BY DEFAULT AS IDENTITY,
    symbol_id NUMBER(19)          NOT NULL,
    module_id NUMBER(19)          NOT NULL,
    file_path VARCHAR2(1000 BYTE) NOT NULL,
    line_no   NUMBER(10)          NOT NULL,
    CONSTRAINT pk_symbol_declaration PRIMARY KEY (id),
    CONSTRAINT fk_symbol_declaration_symbol FOREIGN KEY (symbol_id) REFERENCES symbol (id),
    CONSTRAINT fk_symbol_declaration_module FOREIGN KEY (module_id) REFERENCES maven_module (id)
);
CREATE INDEX ix_symbol_declaration_symbol ON symbol_declaration (symbol_id);
CREATE INDEX ix_symbol_declaration_module ON symbol_declaration (module_id);

CREATE TABLE usage (
    id             NUMBER(19) GENERATED BY DEFAULT AS IDENTITY,
    from_symbol_id NUMBER(19)          NOT NULL,
    to_symbol_id   NUMBER(19)          NOT NULL,
    module_id      NUMBER(19)          NOT NULL,
    kind           VARCHAR2(20 BYTE)   NOT NULL,
    confidence     VARCHAR2(10 BYTE)   NOT NULL,
    file_path      VARCHAR2(1000 BYTE) NOT NULL,
    line_no        NUMBER(10)          NOT NULL,
    column_no      NUMBER(10)          NOT NULL,
    snippet        VARCHAR2(4000 BYTE),
    CONSTRAINT pk_usage PRIMARY KEY (id),
    CONSTRAINT fk_usage_from FOREIGN KEY (from_symbol_id) REFERENCES symbol (id),
    CONSTRAINT fk_usage_to FOREIGN KEY (to_symbol_id) REFERENCES symbol (id),
    CONSTRAINT fk_usage_module FOREIGN KEY (module_id) REFERENCES maven_module (id),
    CONSTRAINT ck_usage_kind CHECK (kind IN ('CALL', 'INSTANTIATION', 'METHOD_REF', 'TYPE_REF', 'EXTENDS',
                                             'IMPLEMENTS', 'OVERRIDES', 'FIELD_READ', 'FIELD_WRITE', 'ANNOTATION')),
    CONSTRAINT ck_usage_confidence CHECK (confidence IN ('EXACT', 'RECOVERED', 'NAME_ONLY'))
);
CREATE INDEX ix_usage_to ON usage (to_symbol_id);
CREATE INDEX ix_usage_from ON usage (from_symbol_id);
CREATE INDEX ix_usage_module ON usage (module_id);
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./mvnw test -Dtest='SchemaMigrationTest,GraphifyApplicationTests'`
Expected: 4 tests pass. The first run starts the container, which takes about 15–30 s.

- [ ] **Step 8: Document local running in `README.md`**

Replace the `## Running` section with:

````markdown
## Running

The application needs an Oracle database and these environment variables (no connection values live in code):

| Variable | Meaning |
|---|---|
| `DB_URL` | JDBC URL, e.g. `jdbc:oracle:thin:@//db-host:1521/FREEPDB1` |
| `DB_USER`, `DB_PASSWORD` | Schema owner credentials |
| `APP_MASTER_KEY` | Base64 of 32 random bytes; encrypts stored tokens (`openssl rand -base64 32`) |
| `SPRING_PROFILES_ACTIVE` | `dev` (default) or `prod` |

Schema changes are Flyway migrations in `src/main/resources/db/migration` and run at startup.

For local development without an Oracle server, run against a throwaway container (Docker required):

```bash
./mvnw spring-boot:test-run
```
````

Replace the `## Tests` section with:

````markdown
## Tests

```bash
./mvnw test
```

Integration tests start an Oracle Free container through Testcontainers, so Docker must be running.
````

- [ ] **Step 9: Run the whole suite and commit**

Run: `./mvnw test`
Expected: all tests pass.

```bash
git add pom.xml README.md src/main/resources src/test/java/com/graphify
git commit -m "feat(store): add Oracle, Flyway and the core schema" -m "<your harness Co-Authored-By trailer>"
```

---

### Task 3: DB-backed settings and the audit log

**Files:**
- Create: `src/main/resources/db/migration/V2__seed_settings.sql`
- Create: `src/main/java/com/graphify/common/util/Utf8.java`
- Create: `src/main/java/com/graphify/audit/AuditLog.java`
- Create: `src/main/java/com/graphify/settings/SettingType.java`
- Create: `src/main/java/com/graphify/settings/Setting.java`
- Create: `src/main/java/com/graphify/settings/SettingKeys.java`
- Create: `src/main/java/com/graphify/settings/SettingsRepository.java`
- Create: `src/main/java/com/graphify/settings/AppSettings.java`
- Create: `src/main/java/com/graphify/settings/SettingChangedEvent.java`
- Create: `src/main/java/com/graphify/settings/SettingNotFoundException.java`
- Create: `src/main/java/com/graphify/settings/InvalidSettingValueException.java`
- Test: `src/test/java/com/graphify/common/util/Utf8Test.java`
- Test: `src/test/java/com/graphify/settings/SettingTypeTest.java`
- Test: `src/test/java/com/graphify/settings/AppSettingsTest.java`

**Interfaces:**
- Consumes: `OracleIntegrationTest` (Task 2).
- Produces:
  - `public final class Utf8` with `static int byteLength(String)` and `static String truncateToBytes(String text, int maxBytes)`.
  - `public class AuditLog` (a `@Component`) with `void record(String actor, String action, String target, String details)`.
  - `public enum SettingType { INT, STRING, BOOL, CRON, LIST, DURATION }` with a package-private `Object parse(String raw)` that throws `IllegalArgumentException` on invalid input.
  - `public record Setting(String key, String value, SettingType type, String description, Long minValue, Long maxValue, String updatedBy, Instant updatedAt)`.
  - `public final class SettingKeys` with one `public static final String` per seeded key.
  - `public class AppSettings` (a `@Service`) with `int getInt(String)`, `boolean getBoolean(String)`, `String getString(String)`, `String getCron(String)`, `List<String> getList(String)`, `Duration getDuration(String)`, `List<Setting> all()` (sorted by key) and `Setting update(String key, String rawValue, String actor)`.
  - `public record SettingChangedEvent(String key)`.
  - `SettingNotFoundException(String key)` and `InvalidSettingValueException(String key, String reason)`. Both extend `RuntimeException`, and both expose `key()`. `InvalidSettingValueException` also exposes `reason()`.

- [ ] **Step 1: Write the failing unit tests**

`src/test/java/com/graphify/common/util/Utf8Test.java`:

```java
package com.graphify.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Utf8Test {

    @Test
    void countsEncodedBytesNotChars() {
        assertThat(Utf8.byteLength("abc")).isEqualTo(3);
        assertThat(Utf8.byteLength("şğü")).isEqualTo(6);
        assertThat(Utf8.byteLength("😀")).isEqualTo(4);
    }

    @Test
    void truncatesToWholeCodePointsWithinTheByteLimit() {
        assertThat(Utf8.truncateToBytes("abc", 10)).isEqualTo("abc");
        assertThat(Utf8.truncateToBytes("aşb", 2)).isEqualTo("a");
        assertThat(Utf8.truncateToBytes("aşb", 3)).isEqualTo("aş");
        assertThat(Utf8.truncateToBytes("a😀", 4)).isEqualTo("a");
        assertThat(Utf8.truncateToBytes("a😀", 5)).isEqualTo("a😀");
        assertThat(Utf8.byteLength(Utf8.truncateToBytes("ş".repeat(3000), 4000))).isLessThanOrEqualTo(4000);
    }
}
```

`src/test/java/com/graphify/settings/SettingTypeTest.java`:

```java
package com.graphify.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class SettingTypeTest {

    @Test
    void parsesEachTypesValidForm() {
        assertThat(SettingType.INT.parse(" 42 ")).isEqualTo(42);
        assertThat(SettingType.STRING.parse("/data/repos")).isEqualTo("/data/repos");
        assertThat(SettingType.BOOL.parse("TRUE")).isEqualTo(true);
        assertThat(SettingType.CRON.parse("0 0 2 * * *")).isEqualTo("0 0 2 * * *");
        assertThat(SettingType.LIST.parse(" a, b ,,c ")).isEqualTo(List.of("a", "b", "c"));
        assertThat(SettingType.LIST.parse("")).isEqualTo(List.of());
        assertThat(SettingType.DURATION.parse("PT10M")).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void rejectsInvalidValues() {
        assertThatIllegalArgumentException().isThrownBy(() -> SettingType.INT.parse("four"));
        assertThatIllegalArgumentException().isThrownBy(() -> SettingType.STRING.parse("  "));
        assertThatIllegalArgumentException().isThrownBy(() -> SettingType.BOOL.parse("yes"));
        assertThatIllegalArgumentException().isThrownBy(() -> SettingType.CRON.parse("0 2 * * *"));
        assertThatIllegalArgumentException().isThrownBy(() -> SettingType.DURATION.parse("10 minutes"));
        assertThatIllegalArgumentException().isThrownBy(() -> SettingType.DURATION.parse("PT0S"));
        assertThatIllegalArgumentException().isThrownBy(() -> SettingType.DURATION.parse("-PT1M"));
    }
}
```

- [ ] **Step 2: Write the failing integration test**

`src/test/java/com/graphify/settings/AppSettingsTest.java`:

```java
package com.graphify.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.graphify.OracleIntegrationTest;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

@RecordApplicationEvents
class AppSettingsTest extends OracleIntegrationTest {

    private static final String ACTOR = "app-settings-test";

    @Autowired
    AppSettings settings;

    @Autowired
    ApplicationEvents events;

    private final Map<String, String> originals = new HashMap<>();

    @AfterEach
    void restoreChangedSettings() {
        originals.forEach((key, value) -> settings.update(key, value, ACTOR));
        originals.clear();
    }

    private void change(String key, String value) {
        originals.putIfAbsent(key, settings.all().stream().filter(s -> s.key().equals(key)).findFirst()
                .orElseThrow().value());
        settings.update(key, value, ACTOR);
    }

    @Test
    void everyDeclaredKeyIsSeeded() {
        String[] declared = Arrays.stream(SettingKeys.class.getFields())
                .filter(f -> Modifier.isStatic(f.getModifiers()) && f.getType() == String.class)
                .map(SettingsTestSupport::constantValue)
                .toArray(String[]::new);

        assertThat(settings.all()).extracting(Setting::key).contains(declared);
    }

    @Test
    void readsSeededValuesWithTheirTypes() {
        assertThat(settings.getInt(SettingKeys.INDEX_PARALLELISM)).isEqualTo(4);
        assertThat(settings.getInt(SettingKeys.STORE_JDBC_BATCH_SIZE)).isEqualTo(1000);
        assertThat(settings.getDuration(SettingKeys.INDEX_MAVEN_TIMEOUT)).isEqualTo(Duration.ofMinutes(10));
        assertThat(settings.getCron(SettingKeys.INDEX_CRON)).isEqualTo("0 0 2 * * *");
        assertThat(settings.getString(SettingKeys.INDEX_WORKSPACE_DIR)).isEqualTo("/data/impact-analyzer/repos");
    }

    @Test
    void rejectsReadingAKeyAsTheWrongTypeOrAnUnknownKey() {
        assertThatThrownBy(() -> settings.getDuration(SettingKeys.INDEX_PARALLELISM))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(SettingKeys.INDEX_PARALLELISM);
        assertThatThrownBy(() -> settings.getInt("no.such.key"))
                .isInstanceOf(SettingNotFoundException.class);
    }

    @Test
    void validUpdateIsStoredAuditedAndAnnounced() {
        change(SettingKeys.INDEX_PARALLELISM, " 8 ");

        assertThat(settings.getInt(SettingKeys.INDEX_PARALLELISM)).isEqualTo(8);
        assertThat(jdbc.queryForObject(
                "SELECT details FROM audit_log WHERE actor = ? AND target = ? AND action = 'SETTING_UPDATED' "
                        + "ORDER BY id DESC FETCH FIRST 1 ROWS ONLY",
                String.class, ACTOR, SettingKeys.INDEX_PARALLELISM)).isEqualTo("4 -> 8");
        assertThat(jdbc.queryForObject("SELECT updated_by FROM app_setting WHERE setting_key = ?", String.class,
                SettingKeys.INDEX_PARALLELISM)).isEqualTo(ACTOR);
        assertThat(events.stream(SettingChangedEvent.class))
                .contains(new SettingChangedEvent(SettingKeys.INDEX_PARALLELISM));
    }

    @Test
    void invalidUpdatesAreRejectedAndChangeNothing() {
        Integer auditRowsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class);

        assertThatThrownBy(() -> settings.update(SettingKeys.INDEX_PARALLELISM, "0", ACTOR))
                .isInstanceOf(InvalidSettingValueException.class).hasMessageContaining(">= 1");
        assertThatThrownBy(() -> settings.update(SettingKeys.INDEX_PARALLELISM, "33", ACTOR))
                .isInstanceOf(InvalidSettingValueException.class).hasMessageContaining("<= 32");
        assertThatThrownBy(() -> settings.update(SettingKeys.INDEX_PARALLELISM, "many", ACTOR))
                .isInstanceOf(InvalidSettingValueException.class);
        assertThatThrownBy(() -> settings.update(SettingKeys.INDEX_CRON, "every night", ACTOR))
                .isInstanceOf(InvalidSettingValueException.class);
        assertThatThrownBy(() -> settings.update(SettingKeys.INDEX_MAVEN_TIMEOUT, "PT0S", ACTOR))
                .isInstanceOf(InvalidSettingValueException.class);
        assertThatThrownBy(() -> settings.update(SettingKeys.INDEX_PARALLELISM, null, ACTOR))
                .isInstanceOf(InvalidSettingValueException.class);
        assertThatThrownBy(() -> settings.update("no.such.key", "1", ACTOR))
                .isInstanceOf(SettingNotFoundException.class);

        assertThat(settings.getInt(SettingKeys.INDEX_PARALLELISM)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class)).isEqualTo(auditRowsBefore);
    }
}

final class SettingsTestSupport {

    private SettingsTestSupport() {
    }

    static String constantValue(Field field) {
        try {
            return (String) field.get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./mvnw test -Dtest='Utf8Test,SettingTypeTest,AppSettingsTest'`
Expected: BUILD FAILURE at test compilation, with `cannot find symbol` for `Utf8`, `SettingType`, `AppSettings` and `SettingKeys`.

- [ ] **Step 4: Write `Utf8` and `AuditLog`**

`src/main/java/com/graphify/common/util/Utf8.java`:

```java
package com.graphify.common.util;

import java.nio.charset.StandardCharsets;

/** UTF-8 byte arithmetic for {@code VARCHAR2(n BYTE)} columns. */
public final class Utf8 {

    private Utf8() {
    }

    public static int byteLength(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    /** Longest prefix of {@code text} whose UTF-8 encoding fits in {@code maxBytes}; never splits a code point. */
    public static String truncateToBytes(String text, int maxBytes) {
        int bytes = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            int length = codePoint < 0x80 ? 1 : codePoint < 0x800 ? 2 : codePoint < 0x10000 ? 3 : 4;
            if (bytes + length > maxBytes) {
                return text.substring(0, i);
            }
            bytes += length;
            i += Character.charCount(codePoint);
        }
        return text;
    }
}
```

`src/main/java/com/graphify/audit/AuditLog.java`:

```java
package com.graphify.audit;

import com.graphify.common.util.Utf8;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Append-only record of who changed what (spec §6.4, §7.2). Never pass secret values as details. */
@Component
public class AuditLog {

    /** Width of {@code audit_log.details} in V1__core_schema.sql. */
    private static final int DETAILS_MAX_BYTES = 4000;

    private final JdbcTemplate jdbc;

    public AuditLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String actor, String action, String target, String details) {
        jdbc.update("INSERT INTO audit_log (actor, action, target, details) VALUES (?, ?, ?, ?)",
                actor, action, target, details == null ? null : Utf8.truncateToBytes(details, DETAILS_MAX_BYTES));
    }
}
```

- [ ] **Step 5: Write the settings types**

`src/main/java/com/graphify/settings/SettingType.java`:

```java
package com.graphify.settings;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import org.springframework.scheduling.support.CronExpression;

/** How an {@code app_setting} value is written and parsed. */
public enum SettingType {

    INT {
        @Override
        Object parse(String raw) {
            try {
                return Integer.valueOf(raw.strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("not an integer: " + raw);
            }
        }
    },
    STRING {
        @Override
        Object parse(String raw) {
            if (raw.isBlank()) {
                throw new IllegalArgumentException("must not be blank");
            }
            return raw;
        }
    },
    BOOL {
        @Override
        Object parse(String raw) {
            String value = raw.strip();
            if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                throw new IllegalArgumentException("must be true or false: " + raw);
            }
            return Boolean.valueOf(value);
        }
    },
    CRON {
        @Override
        Object parse(String raw) {
            String value = raw.strip();
            if (!CronExpression.isValidExpression(value)) {
                throw new IllegalArgumentException(
                        "not a cron expression with 6 fields (second minute hour day month weekday): " + raw);
            }
            return value;
        }
    },
    LIST {
        @Override
        Object parse(String raw) {
            return Arrays.stream(raw.split(",")).map(String::strip).filter(item -> !item.isEmpty()).toList();
        }
    },
    DURATION {
        @Override
        Object parse(String raw) {
            Duration duration;
            try {
                duration = Duration.parse(raw.strip());
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("not an ISO-8601 duration such as PT10M: " + raw);
            }
            if (duration.isNegative() || duration.isZero()) {
                throw new IllegalArgumentException("must be positive: " + raw);
            }
            return duration;
        }
    };

    abstract Object parse(String raw);
}
```

`src/main/java/com/graphify/settings/Setting.java`:

```java
package com.graphify.settings;

import java.time.Instant;

/** One row of {@code app_setting}. {@code minValue}/{@code maxValue} bound INT values; {@code null} = unbounded. */
public record Setting(
        String key,
        String value,
        SettingType type,
        String description,
        Long minValue,
        Long maxValue,
        String updatedBy,
        Instant updatedAt) {
}
```

`src/main/java/com/graphify/settings/SettingKeys.java`:

```java
package com.graphify.settings;

/** Keys of the rows seeded by V2__seed_settings.sql. Values live in the database, never here. */
public final class SettingKeys {

    public static final String INDEX_CRON = "index.cron";
    public static final String INDEX_PARALLELISM = "index.parallelism";
    public static final String INDEX_WORKSPACE_DIR = "index.workspace_dir";
    public static final String INDEX_PARSE_BATCH_SIZE = "index.parse_batch_size";
    public static final String INDEX_MAVEN_TIMEOUT = "index.maven_timeout";
    public static final String INDEX_MAVEN_OUTPUT_TAIL_LINES = "index.maven_output_tail_lines";
    public static final String SCM_RETRY_COUNT = "scm.retry_count";
    public static final String SCM_RETRY_BACKOFF = "scm.retry_backoff";
    public static final String SCM_PAGE_SIZE = "scm.page_size";
    public static final String IMPACT_DEFAULT_DEPTH = "impact.default_depth";
    public static final String IMPACT_MAX_DEPTH = "impact.max_depth";
    public static final String IMPACT_MAX_RESULTS = "impact.max_results";
    public static final String USAGE_SNIPPET_MAX_LENGTH = "usage.snippet_max_length";
    public static final String API_PAGE_DEFAULT_SIZE = "api.page_default_size";
    public static final String API_PAGE_MAX_SIZE = "api.page_max_size";
    public static final String GRAPH_MAX_NODES = "graph.max_nodes";
    public static final String GRAPH_COMMUNITY_SEED = "graph.community_seed";
    public static final String AUTH_SESSION_TIMEOUT = "auth.session_timeout";
    public static final String AUTH_MAX_FAILED_ATTEMPTS = "auth.max_failed_attempts";
    public static final String AUTH_LOCK_DURATION = "auth.lock_duration";
    public static final String CLEANUP_ORPHAN_SYMBOLS_CRON = "cleanup.orphan_symbols_cron";
    public static final String STORE_JDBC_BATCH_SIZE = "store.jdbc_batch_size";

    private SettingKeys() {
    }
}
```

`src/main/java/com/graphify/settings/SettingChangedEvent.java`:

```java
package com.graphify.settings;

/** Published after a setting is updated, so caches and schedules (plan 4) can react. */
public record SettingChangedEvent(String key) {
}
```

`src/main/java/com/graphify/settings/SettingNotFoundException.java`:

```java
package com.graphify.settings;

public class SettingNotFoundException extends RuntimeException {

    private final String key;

    public SettingNotFoundException(String key) {
        super("Unknown setting: " + key);
        this.key = key;
    }

    public String key() {
        return key;
    }
}
```

`src/main/java/com/graphify/settings/InvalidSettingValueException.java`:

```java
package com.graphify.settings;

public class InvalidSettingValueException extends RuntimeException {

    private final String key;
    private final String reason;

    public InvalidSettingValueException(String key, String reason) {
        super("Invalid value for " + key + ": " + reason);
        this.key = key;
        this.reason = reason;
    }

    public String key() {
        return key;
    }

    public String reason() {
        return reason;
    }
}
```

- [ ] **Step 6: Write the repository and the service**

`src/main/java/com/graphify/settings/SettingsRepository.java`:

```java
package com.graphify.settings;

import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class SettingsRepository {

    private final JdbcTemplate jdbc;

    SettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<Setting> findAll() {
        return jdbc.query("""
                SELECT setting_key, setting_value, value_type, description, min_value, max_value, updated_by, updated_at
                  FROM app_setting
                """, (rs, row) -> {
            Timestamp updatedAt = rs.getTimestamp("updated_at");
            return new Setting(
                    rs.getString("setting_key"),
                    rs.getString("setting_value"),
                    SettingType.valueOf(rs.getString("value_type")),
                    rs.getString("description"),
                    rs.getObject("min_value", Long.class),
                    rs.getObject("max_value", Long.class),
                    rs.getString("updated_by"),
                    updatedAt == null ? null : updatedAt.toInstant());
        });
    }

    void updateValue(String key, String value, String actor) {
        jdbc.update("UPDATE app_setting SET setting_value = ?, updated_by = ?, updated_at = SYSTIMESTAMP "
                + "WHERE setting_key = ?", value, actor, key);
    }
}
```

`src/main/java/com/graphify/settings/AppSettings.java`:

```java
package com.graphify.settings;

import com.graphify.audit.AuditLog;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Typed access to {@code app_setting}. Values are cached until the next update; defaults exist only as Flyway
 * seed rows (V2__seed_settings.sql), never in code.
 */
@Service
public class AppSettings {

    static final String UPDATED_ACTION = "SETTING_UPDATED";

    private final SettingsRepository repository;
    private final AuditLog auditLog;
    private final ApplicationEventPublisher events;
    private volatile Map<String, Setting> cache;

    public AppSettings(SettingsRepository repository, AuditLog auditLog, ApplicationEventPublisher events) {
        this.repository = repository;
        this.auditLog = auditLog;
        this.events = events;
    }

    public int getInt(String key) {
        return (Integer) typed(key, SettingType.INT);
    }

    public boolean getBoolean(String key) {
        return (Boolean) typed(key, SettingType.BOOL);
    }

    public String getString(String key) {
        return (String) typed(key, SettingType.STRING);
    }

    public String getCron(String key) {
        return (String) typed(key, SettingType.CRON);
    }

    @SuppressWarnings("unchecked")
    public List<String> getList(String key) {
        return (List<String>) typed(key, SettingType.LIST);
    }

    public Duration getDuration(String key) {
        return (Duration) typed(key, SettingType.DURATION);
    }

    public List<Setting> all() {
        return List.copyOf(settings().values());
    }

    @Transactional
    public Setting update(String key, String rawValue, String actor) {
        Setting current = find(key);
        String value = rawValue == null ? null : rawValue.strip();
        validate(current, value);
        repository.updateValue(key, value, actor);
        auditLog.record(actor, UPDATED_ACTION, key, current.value() + " -> " + value);
        cache = null;
        events.publishEvent(new SettingChangedEvent(key));
        return find(key);
    }

    private Object typed(String key, SettingType expected) {
        Setting setting = find(key);
        if (setting.type() != expected) {
            throw new IllegalStateException("Setting " + key + " is " + setting.type() + ", not " + expected);
        }
        return expected.parse(setting.value());
    }

    private Setting find(String key) {
        Setting setting = settings().get(key);
        if (setting == null) {
            throw new SettingNotFoundException(key);
        }
        return setting;
    }

    private Map<String, Setting> settings() {
        Map<String, Setting> current = cache;
        if (current == null) {
            Map<String, Setting> loaded = new TreeMap<>();
            repository.findAll().forEach(setting -> loaded.put(setting.key(), setting));
            current = Collections.unmodifiableMap(loaded);
            cache = current;
        }
        return current;
    }

    private static void validate(Setting setting, String value) {
        if (value == null) {
            throw new InvalidSettingValueException(setting.key(), "a value is required");
        }
        Object parsed;
        try {
            parsed = setting.type().parse(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidSettingValueException(setting.key(), e.getMessage());
        }
        if (parsed instanceof Integer number) {
            if (setting.minValue() != null && number < setting.minValue()) {
                throw new InvalidSettingValueException(setting.key(), "must be >= " + setting.minValue());
            }
            if (setting.maxValue() != null && number > setting.maxValue()) {
                throw new InvalidSettingValueException(setting.key(), "must be <= " + setting.maxValue());
            }
        }
    }
}
```

- [ ] **Step 7: Write the seed migration**

`src/main/resources/db/migration/V2__seed_settings.sql`:

```sql
-- Default values for spec §6.3 settings. These rows are the only place defaults live; change them in the UI.
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('index.cron', '0 0 2 * * *', 'CRON', 'Tam tarama zamanlaması (saniye dakika saat gün ay haftagünü)', NULL, NULL);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('index.parallelism', '4', 'INT', 'Aynı anda taranan repo sayısı', 1, 32);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('index.workspace_dir', '/data/impact-analyzer/repos', 'STRING', 'Repoların klonlandığı çalışma dizini', NULL, NULL);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('index.parse_batch_size', '500', 'INT', 'Tek seferde JDT ile parse edilen dosya sayısı', 1, 10000);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('index.maven_timeout', 'PT10M', 'DURATION', 'Maven classpath çözümleme zaman aşımı (ISO-8601)', NULL, NULL);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('index.maven_output_tail_lines', '50', 'INT', 'Hata kaydına yazılan Maven çıktısı satır sayısı', 1, 1000);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('scm.retry_count', '3', 'INT', 'SCM API hatalarında yeniden deneme sayısı', 0, 10);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('scm.retry_backoff', 'PT2S', 'DURATION', 'İlk yeniden deneme bekleme süresi (üstel artar)', NULL, NULL);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('scm.page_size', '100', 'INT', 'SCM API sayfa boyutu', 1, 1000);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('impact.default_depth', '3', 'INT', 'Etki analizi varsayılan derinliği', 1, 10);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('impact.max_depth', '10', 'INT', 'Etki analizi azami derinliği', 1, 50);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('impact.max_results', '5000', 'INT', 'Etki analizinde döndürülen azami sembol sayısı', 1, 100000);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('usage.snippet_max_length', '500', 'INT', 'Kullanım satırı önizlemesinin azami karakter sayısı', 1, 1000);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('api.page_default_size', '50', 'INT', 'API varsayılan sayfa boyutu', 1, 500);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('api.page_max_size', '500', 'INT', 'API azami sayfa boyutu', 1, 5000);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('graph.max_nodes', '500', 'INT', 'Repo grafında bir seviyede gösterilen azami düğüm', 10, 5000);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('graph.community_seed', '42', 'INT', 'Topluluk tespiti için sabit rastgelelik tohumu', NULL, NULL);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('auth.session_timeout', 'PT8H', 'DURATION', 'Oturum zaman aşımı', NULL, NULL);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('auth.max_failed_attempts', '5', 'INT', 'Hesap kilitlenmeden önce izin verilen hatalı giriş', 1, 100);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('auth.lock_duration', 'PT15M', 'DURATION', 'Hesap kilit süresi', NULL, NULL);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('cleanup.orphan_symbols_cron', '0 0 4 * * SUN', 'CRON', 'Kullanılmayan sembolleri temizleme zamanlaması', NULL, NULL);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('store.jdbc_batch_size', '1000', 'INT', 'Veritabanına tek seferde yazılan satır sayısı', 1, 10000);
```

The file must be saved as UTF-8 (Flyway's default encoding).

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./mvnw test -Dtest='Utf8Test,SettingTypeTest,AppSettingsTest'`
Expected: 9 tests pass.

Run: `./mvnw test`
Expected: all tests pass.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/graphify/common src/main/java/com/graphify/audit src/main/java/com/graphify/settings src/main/resources/db/migration/V2__seed_settings.sql src/test/java/com/graphify/common src/test/java/com/graphify/settings
git commit -m "feat(settings): add DB-backed typed settings with validation and audit" -m "<your harness Co-Authored-By trailer>"
```

---

### Task 4: Secret encryption with the master key

**Files:**
- Modify: `src/main/resources/application.yml`
- Create: `src/main/java/com/graphify/common/crypto/SecretCipher.java`
- Create: `src/main/java/com/graphify/common/crypto/SecretDecryptionException.java`
- Create: `src/test/resources/config/application.yml`
- Test: `src/test/java/com/graphify/common/crypto/SecretCipherTest.java`

**Interfaces:**
- Produces:
  - `public class SecretCipher` (a `@Component`). Its constructor is `SecretCipher(@Value("${app.master-key}") String base64Key)`. It also has `String encrypt(String plaintext)`, which returns a value prefixed `v1:`, and `String decrypt(String stored)`.
  - `public class SecretDecryptionException extends RuntimeException`.
  - Startup fails with an `IllegalStateException` naming `APP_MASTER_KEY` when the key is not Base64 or does not decode to 32 bytes.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/graphify/common/crypto/SecretCipherTest.java`:

```java
package com.graphify.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecretCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());
    private static final String OTHER_KEY = Base64.getEncoder().encodeToString("fedcba9876543210fedcba9876543210".getBytes());

    @Test
    void roundTripsAndRandomizesEachEncryption() {
        SecretCipher cipher = new SecretCipher(KEY);

        String first = cipher.encrypt("bitbucket-token-şğü");
        String second = cipher.encrypt("bitbucket-token-şğü");

        assertThat(first).startsWith("v1:").isNotEqualTo(second).doesNotContain("bitbucket");
        assertThat(cipher.decrypt(first)).isEqualTo("bitbucket-token-şğü");
        assertThat(cipher.decrypt(second)).isEqualTo("bitbucket-token-şğü");
    }

    @Test
    void detectsTamperingWrongKeysAndUnknownFormats() {
        SecretCipher cipher = new SecretCipher(KEY);
        String stored = cipher.encrypt("secret");
        byte[] raw = Base64.getDecoder().decode(stored.substring(3));
        raw[raw.length - 1] ^= 1;
        String tampered = "v1:" + Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(SecretDecryptionException.class);
        assertThatThrownBy(() -> new SecretCipher(OTHER_KEY).decrypt(stored))
                .isInstanceOf(SecretDecryptionException.class);
        assertThatThrownBy(() -> cipher.decrypt("plain-text")).isInstanceOf(SecretDecryptionException.class);
        assertThatThrownBy(() -> cipher.decrypt("v1:###")).isInstanceOf(SecretDecryptionException.class);
    }

    @Test
    void rejectsKeysThatAreNotBase64Of32Bytes() {
        assertThatThrownBy(() -> new SecretCipher("not base64 !"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("APP_MASTER_KEY");
        assertThatThrownBy(() -> new SecretCipher(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> new SecretCipher("   "))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("APP_MASTER_KEY");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=SecretCipherTest`
Expected: BUILD FAILURE, with `cannot find symbol ... class SecretCipher`.

- [ ] **Step 3: Write the implementation**

`src/main/java/com/graphify/common/crypto/SecretDecryptionException.java`:

```java
package com.graphify.common.crypto;

/** A stored secret could not be decrypted: wrong master key, tampering, or not produced by {@link SecretCipher}. */
public class SecretDecryptionException extends RuntimeException {

    public SecretDecryptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

`src/main/java/com/graphify/common/crypto/SecretCipher.java`:

```java
package com.graphify.common.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts stored credentials (tokens, LDAP bind password) with AES-256-GCM. The key comes from the
 * {@code APP_MASTER_KEY} environment variable and never from the database it protects (spec §6.1).
 * Stored form: {@code v1:} + Base64(12-byte IV ‖ ciphertext ‖ 16-byte tag).
 */
@Component
public class SecretCipher {

    private static final String FORMAT_PREFIX = "v1:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${app.master-key}") String base64Key) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("APP_MASTER_KEY (app.master-key) must be Base64-encoded", e);
        }
        if (raw.length != KEY_BYTES) {
            throw new IllegalStateException("APP_MASTER_KEY (app.master-key) must decode to " + KEY_BYTES
                    + " bytes but decoded to " + raw.length + "; generate one with: openssl rand -base64 32");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public String encrypt(String plaintext) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] stored = ByteBuffer.allocate(IV_BYTES + encrypted.length).put(iv).put(encrypted).array();
            return FORMAT_PREFIX + Base64.getEncoder().encodeToString(stored);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM encryption failed", e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(FORMAT_PREFIX)) {
            throw new SecretDecryptionException("Not an encrypted secret (missing " + FORMAT_PREFIX + " prefix)", null);
        }
        try {
            byte[] raw = Base64.getDecoder().decode(stored.substring(FORMAT_PREFIX.length()));
            if (raw.length <= IV_BYTES) {
                throw new SecretDecryptionException("Encrypted secret is truncated", null);
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(raw, IV_BYTES, raw.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new SecretDecryptionException("Secret could not be decrypted with the current APP_MASTER_KEY", e);
        }
    }
}
```

- [ ] **Step 4: Wire the key from the environment, and a test-only key**

Append to `src/main/resources/application.yml`:

```yaml
app:
  master-key: ${APP_MASTER_KEY}
```

`src/test/resources/config/application.yml` (test classpath only, overrides the placeholder):

```yaml
app:
  master-key: MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw test -Dtest='SecretCipherTest,GraphifyApplicationTests'`
Expected: 4 tests pass. The context starts because the test key is valid.

Run: `./mvnw test`
Expected: all tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/graphify/common/crypto src/main/resources/application.yml src/test/resources/config src/test/java/com/graphify/common/crypto
git commit -m "feat(crypto): encrypt stored secrets with AES-GCM and APP_MASTER_KEY" -m "<your harness Co-Authored-By trailer>"
```

---

### Task 5: Shared, rank-aware symbol writes

**Files:**
- Create: `src/main/java/com/graphify/store/Chunks.java`
- Create: `src/main/java/com/graphify/store/StoreLimits.java`
- Create: `src/main/java/com/graphify/store/SymbolWriter.java`
- Test: `src/test/java/com/graphify/store/StoreFixtures.java`
- Test: `src/test/java/com/graphify/store/SymbolWriterTest.java`

**Interfaces:**
- Consumes `Symbol` (Task 1, with `nameOnly`), `Utf8` (Task 3) and `OracleIntegrationTest` (Task 2).
- Produces:
  - `final class Chunks` with `static <T> List<List<T>> of(List<T> items, int size)`.
  - `final class StoreLimits` with byte-width constants and the methods `static boolean storable(Symbol)` and `static boolean fits(String text, int maxBytes)`.
  - `@Component class SymbolWriter` with `Result upsert(List<Symbol> symbols, int batchSize)`, where `record Result(Map<String, Long> ids, Set<String> skippedKeys)`.
  - Test helper `StoreFixtures` with `static void cleanIndexTables(JdbcTemplate)`, `static long newRepository(JdbcTemplate, String slug)` and `static Symbol symbol(String key, SymbolKind kind, SymbolOrigin origin, boolean nameOnly)`.

- [ ] **Step 1: Write the test fixtures helper**

`src/test/java/com/graphify/store/StoreFixtures.java`:

```java
package com.graphify.store;

import com.graphify.indexer.model.Symbol;
import com.graphify.indexer.model.SymbolKind;
import com.graphify.indexer.model.SymbolOrigin;
import org.springframework.jdbc.core.JdbcTemplate;

/** Test data for the index tables. Settings rows are never touched here. */
final class StoreFixtures {

    private StoreFixtures() {
    }

    static void cleanIndexTables(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM usage");
        jdbc.update("DELETE FROM symbol_declaration");
        jdbc.update("UPDATE symbol SET parent_id = NULL");
        jdbc.update("DELETE FROM symbol");
        jdbc.update("DELETE FROM maven_module");
        jdbc.update("DELETE FROM scm_repository");
        jdbc.update("DELETE FROM scm_connection");
    }

    /** Inserts a connection (if needed) and a repository with this slug; returns the repository id. */
    static long newRepository(JdbcTemplate jdbc, String slug) {
        jdbc.update("""
                MERGE INTO scm_connection c USING (SELECT 'test-connection' AS name FROM dual) n ON (c.name = n.name)
                WHEN NOT MATCHED THEN INSERT (name, type, base_url) VALUES (n.name, 'BITBUCKET_DC', 'https://scm.test')
                """);
        Long connectionId = jdbc.queryForObject("SELECT id FROM scm_connection WHERE name = 'test-connection'",
                Long.class);
        jdbc.update("INSERT INTO scm_repository (connection_id, project_key, slug, clone_url) VALUES (?, 'TEST', ?, ?)",
                connectionId, slug, "https://scm.test/scm/test/" + slug + ".git");
        return jdbc.queryForObject("SELECT id FROM scm_repository WHERE slug = ?", Long.class, slug);
    }

    static Symbol symbol(String key, SymbolKind kind, SymbolOrigin origin, boolean nameOnly) {
        String classFqn = key.contains("#") ? key.substring(0, key.indexOf('#')) : key;
        String member = key.contains("#") ? key.substring(key.indexOf('#') + 1) : null;
        String parent = key.contains("#") ? classFqn : null;
        return new Symbol(key, kind, classFqn, member, key, parent, origin, nameOnly);
    }
}
```

- [ ] **Step 2: Write the failing test**

`src/test/java/com/graphify/store/SymbolWriterTest.java`:

```java
package com.graphify.store;

import static com.graphify.store.StoreFixtures.symbol;
import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.indexer.model.Symbol;
import com.graphify.indexer.model.SymbolKind;
import com.graphify.indexer.model.SymbolOrigin;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class SymbolWriterTest extends OracleIntegrationTest {

    @Autowired
    SymbolWriter writer;

    @Autowired
    TransactionTemplate transactions;

    @BeforeEach
    void clean() {
        StoreFixtures.cleanIndexTables(jdbc);
    }

    private String kindAndOrigin(String key) {
        return jdbc.queryForObject("SELECT kind || ':' || origin || ':' || name_only FROM symbol WHERE symbol_key = ?",
                String.class, key);
    }

    @Test
    void insertsSymbolsAndReturnsTheirIds() {
        SymbolWriter.Result result = writer.upsert(List.of(
                symbol("p.A", SymbolKind.CLASS, SymbolOrigin.SOURCE, false),
                symbol("p.A#run()", SymbolKind.METHOD, SymbolOrigin.SOURCE, false)), 10);

        assertThat(result.ids()).containsOnlyKeys("p.A", "p.A#run()");
        assertThat(result.skippedKeys()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT p.symbol_key FROM symbol c JOIN symbol p ON c.parent_id = p.id "
                + "WHERE c.symbol_key = 'p.A#run()'", String.class)).isEqualTo("p.A");
    }

    @Test
    void higherRankReplacesLowerButNeverTheReverse() {
        writer.upsert(List.of(
                symbol("p.Guess", SymbolKind.CLASS, SymbolOrigin.BINARY, true),
                symbol("p.Lib", SymbolKind.CLASS, SymbolOrigin.BINARY, false),
                symbol("p.Own", SymbolKind.INTERFACE, SymbolOrigin.SOURCE, false)), 10);

        writer.upsert(List.of(
                symbol("p.Guess", SymbolKind.INTERFACE, SymbolOrigin.BINARY, false),
                symbol("p.Lib", SymbolKind.ENUM, SymbolOrigin.SOURCE, false),
                symbol("p.Own", SymbolKind.CLASS, SymbolOrigin.BINARY, false)), 10);
        writer.upsert(List.of(symbol("p.Lib", SymbolKind.CLASS, SymbolOrigin.BINARY, true)), 10);

        assertThat(kindAndOrigin("p.Guess")).isEqualTo("INTERFACE:BINARY:0");
        assertThat(kindAndOrigin("p.Lib")).isEqualTo("ENUM:SOURCE:0");
        assertThat(kindAndOrigin("p.Own")).isEqualTo("INTERFACE:SOURCE:0");
    }

    @Test
    void skipsSymbolsThatDoNotFitTheColumnsAndCountsThem() {
        String longKey = "p.A#m(" + "x".repeat(4100) + ")";

        SymbolWriter.Result result = writer.upsert(List.of(
                symbol("p.A", SymbolKind.CLASS, SymbolOrigin.SOURCE, false),
                symbol(longKey, SymbolKind.METHOD, SymbolOrigin.SOURCE, false)), 10);

        assertThat(result.skippedKeys()).containsExactly(longKey);
        assertThat(result.ids()).containsOnlyKeys("p.A");
    }

    @Test
    void handlesMoreSymbolsThanOneBatchOrOneInList() {
        List<Symbol> many = new ArrayList<>();
        for (int i = 0; i < 2500; i++) {
            many.add(symbol("p.C" + i, SymbolKind.CLASS, SymbolOrigin.BINARY, false));
        }

        SymbolWriter.Result result = writer.upsert(many, 700);

        assertThat(result.ids()).hasSize(2500);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM symbol", Integer.class)).isEqualTo(2500);
    }

    @Test
    void concurrentWritersOfTheSameNewSymbolsBothSucceed() throws Exception {
        List<Symbol> shared = new ArrayList<>();
        for (int i = 0; i < 1500; i++) {
            shared.add(symbol("java.lang.Shared" + i, SymbolKind.CLASS, SymbolOrigin.BINARY, false));
        }
        Callable<Integer> write = () -> transactions.execute(status -> writer.upsert(shared, 200).ids().size());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(write);
            Future<Integer> second = pool.submit(write);
            assertThat(first.get()).isEqualTo(1500);
            assertThat(second.get()).isEqualTo(1500);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM symbol", Integer.class)).isEqualTo(1500);
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./mvnw test -Dtest=SymbolWriterTest`
Expected: BUILD FAILURE, with `cannot find symbol ... class SymbolWriter`.

- [ ] **Step 4: Write `Chunks` and `StoreLimits`**

`src/main/java/com/graphify/store/Chunks.java`:

```java
package com.graphify.store;

import java.util.ArrayList;
import java.util.List;

final class Chunks {

    private Chunks() {
    }

    static <T> List<List<T>> of(List<T> items, int size) {
        if (size < 1) {
            throw new IllegalArgumentException("chunk size must be >= 1 but was " + size);
        }
        List<List<T>> chunks = new ArrayList<>();
        for (int from = 0; from < items.size(); from += size) {
            chunks.add(items.subList(from, Math.min(items.size(), from + size)));
        }
        return chunks;
    }
}
```

`src/main/java/com/graphify/store/StoreLimits.java`:

```java
package com.graphify.store;

import com.graphify.common.util.Utf8;
import com.graphify.indexer.model.Symbol;

/** Column byte widths from V1__core_schema.sql. Rows that cannot fit an identifying column are skipped. */
final class StoreLimits {

    static final int SYMBOL_KEY_BYTES = 4000;
    static final int CLASS_FQN_BYTES = 2000;
    static final int MEMBER_NAME_BYTES = 1000;
    static final int DISPLAY_SIGNATURE_BYTES = 4000;
    static final int FILE_PATH_BYTES = 1000;
    static final int SNIPPET_BYTES = 4000;

    private StoreLimits() {
    }

    static boolean storable(Symbol symbol) {
        return fits(symbol.key(), SYMBOL_KEY_BYTES)
                && fits(symbol.classFqn(), CLASS_FQN_BYTES)
                && fits(symbol.memberName(), MEMBER_NAME_BYTES);
    }

    static boolean fits(String text, int maxBytes) {
        return text == null || Utf8.byteLength(text) <= maxBytes;
    }
}
```

- [ ] **Step 5: Write `SymbolWriter`**

`src/main/java/com/graphify/store/SymbolWriter.java`:

```java
package com.graphify.store;

import com.graphify.common.util.Utf8;
import com.graphify.indexer.model.Symbol;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Writes the symbols one repository's index refers to. Symbols are shared by every repository, so a row is only
 * changed when the incoming symbol ranks higher: name-only (0) &lt; BINARY (1) &lt; SOURCE (2).
 */
@Component
class SymbolWriter {

    /** Oracle rejects IN lists longer than 1000 items (ORA-01795). */
    private static final int MAX_IN_LIST = 1000;

    /**
     * Two repositories committing the same new key race on the unique index; the loser gets ORA-00001 and the
     * retried MERGE then sees the committed row. Symbols are written in key order, so writers cannot deadlock.
     */
    private static final int MAX_CONCURRENT_INSERT_ATTEMPTS = 3;

    private static final String MERGE = """
            MERGE INTO symbol s
            USING (SELECT ? AS symbol_key, ? AS kind, ? AS class_fqn, ? AS member_name, ? AS display_signature,
                          ? AS origin, ? AS name_only FROM dual) n
            ON (s.symbol_key = n.symbol_key)
            WHEN MATCHED THEN UPDATE SET s.kind = n.kind, s.class_fqn = n.class_fqn, s.member_name = n.member_name,
                    s.display_signature = n.display_signature, s.origin = n.origin, s.name_only = n.name_only
                WHERE (CASE WHEN n.name_only = 1 THEN 0 WHEN n.origin = 'BINARY' THEN 1 ELSE 2 END)
                    > (CASE WHEN s.name_only = 1 THEN 0 WHEN s.origin = 'BINARY' THEN 1 ELSE 2 END)
            WHEN NOT MATCHED THEN INSERT (symbol_key, kind, class_fqn, member_name, display_signature, origin, name_only)
                VALUES (n.symbol_key, n.kind, n.class_fqn, n.member_name, n.display_signature, n.origin, n.name_only)
            """;

    private static final String LINK_PARENT = "UPDATE symbol SET parent_id = ? WHERE id = ? AND parent_id IS NULL";

    record Result(Map<String, Long> ids, Set<String> skippedKeys) {
    }

    private final JdbcTemplate jdbc;

    SymbolWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Result upsert(List<Symbol> symbols, int batchSize) {
        List<Symbol> storable = new ArrayList<>();
        Set<String> skipped = new LinkedHashSet<>();
        for (Symbol symbol : symbols) {
            if (StoreLimits.storable(symbol)) {
                storable.add(symbol);
            } else {
                skipped.add(symbol.key());
            }
        }
        storable.sort(Comparator.comparing(Symbol::key));
        for (List<Symbol> chunk : Chunks.of(storable, batchSize)) {
            mergeWithRetry(chunk);
        }
        Map<String, Long> ids = ids(storable.stream().map(Symbol::key).toList());
        linkParents(storable, ids, batchSize);
        return new Result(ids, skipped);
    }

    private void mergeWithRetry(List<Symbol> chunk) {
        List<Object[]> rows = chunk.stream().map(SymbolWriter::row).toList();
        for (int attempt = 1; ; attempt++) {
            try {
                jdbc.batchUpdate(MERGE, rows);
                return;
            } catch (DuplicateKeyException e) {
                if (attempt >= MAX_CONCURRENT_INSERT_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    private static Object[] row(Symbol symbol) {
        String display = symbol.displaySignature() == null || symbol.displaySignature().isBlank()
                ? symbol.key() : symbol.displaySignature();
        return new Object[] {
                symbol.key(),
                symbol.kind().name(),
                symbol.classFqn(),
                symbol.memberName(),
                Utf8.truncateToBytes(display, StoreLimits.DISPLAY_SIGNATURE_BYTES),
                symbol.origin().name(),
                symbol.nameOnly() ? 1 : 0};
    }

    private Map<String, Long> ids(List<String> keys) {
        Map<String, Long> ids = new HashMap<>(keys.size() * 2);
        for (List<String> chunk : Chunks.of(keys, MAX_IN_LIST)) {
            String placeholders = String.join(",", Collections.nCopies(chunk.size(), "?"));
            jdbc.query("SELECT id, symbol_key FROM symbol WHERE symbol_key IN (" + placeholders + ")",
                    rs -> {
                        ids.put(rs.getString("symbol_key"), rs.getLong("id"));
                    },
                    chunk.toArray());
        }
        return ids;
    }

    private void linkParents(List<Symbol> symbols, Map<String, Long> ids, int batchSize) {
        List<Object[]> links = new ArrayList<>();
        for (Symbol symbol : symbols) {
            Long parent = symbol.parentKey() == null ? null : ids.get(symbol.parentKey());
            Long self = ids.get(symbol.key());
            if (parent != null && self != null) {
                links.add(new Object[] {parent, self});
            }
        }
        for (List<Object[]> chunk : Chunks.of(links, batchSize)) {
            jdbc.batchUpdate(LINK_PARENT, chunk);
        }
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./mvnw test -Dtest=SymbolWriterTest`
Expected: 5 tests pass.

If `concurrentWritersOfTheSameNewSymbolsBothSucceed` fails, read the cause first. `DuplicateKeyException` after 3 attempts, or a deadlock, means the retry or ordering is wrong; fix the writer and do not weaken the test. If Spring translates ORA-00001 as `DataIntegrityViolationException` rather than `DuplicateKeyException`, catch the subclass Spring actually throws and note it in the report.

Run: `./mvnw test`
Expected: all tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/graphify/store src/test/java/com/graphify/store
git commit -m "feat(store): upsert shared symbols with rank-aware MERGE" -m "<your harness Co-Authored-By trailer>"
```

---

### Task 6: Transactional replace of one repository's index

**Files:**
- Create: `src/main/java/com/graphify/store/ClasspathMode.java`
- Create: `src/main/java/com/graphify/store/ModuleRecord.java`
- Create: `src/main/java/com/graphify/store/RepositoryIndex.java`
- Create: `src/main/java/com/graphify/store/WriteSummary.java`
- Create: `src/main/java/com/graphify/store/RepositoryNotFoundException.java`
- Create: `src/main/java/com/graphify/store/ModuleWriter.java`
- Create: `src/main/java/com/graphify/store/RepositoryIndexWriter.java`
- Test: `src/test/java/com/graphify/store/RepositoryIndexWriterTest.java`

**Interfaces:**
- Consumes:
  - `SymbolWriter.upsert(List<Symbol>, int)` from Task 5, which returns `Result(ids, skippedKeys)`.
  - `AppSettings.getInt(SettingKeys.STORE_JDBC_BATCH_SIZE)` from Task 3.
  - `StoreLimits`, `Chunks` and `Utf8`.
  - From plan 1: `IndexResult` (with `symbols()`, `declarations()` and `usages()`), `Declaration(symbolKey, modulePath, filePath, line)` and `Usage(fromKey, toKey, kind, confidence, modulePath, filePath, line, column, snippet)`.
- Produces:
  - `public enum ClasspathMode { FULL, PARTIAL, NONE }`.
  - `public record ModuleRecord(String path, String groupId, String artifactId, String version, ClasspathMode classpathMode)`.
  - `public record RepositoryIndex(long repositoryId, String commit, List<ModuleRecord> modules, IndexResult result)`.
  - `public record WriteSummary(int symbols, int declarations, int usages, int skippedSymbols, int skippedRows)`.
  - `public class RepositoryNotFoundException extends RuntimeException`.
  - `public class RepositoryIndexWriter` (a `@Service`) with `@Transactional public WriteSummary replace(RepositoryIndex index)`. It throws `IllegalArgumentException` for an inconsistent input before anything is written.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/graphify/store/RepositoryIndexWriterTest.java`:

```java
package com.graphify.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.graphify.OracleIntegrationTest;
import com.graphify.common.util.Utf8;
import com.graphify.indexer.IndexRequest;
import com.graphify.indexer.IndexerOptions;
import com.graphify.indexer.JavaRepositoryIndexer;
import com.graphify.indexer.ModuleSource;
import com.graphify.indexer.model.Confidence;
import com.graphify.indexer.model.IndexResult;
import com.graphify.indexer.model.Usage;
import com.graphify.indexer.model.UsageKind;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RepositoryIndexWriterTest extends OracleIntegrationTest {

    private static final Path CORP_REPO = fixture();
    private static final List<ModuleRecord> MODULES = List.of(
            new ModuleRecord("common-lib", "com.corp", "common-lib", "1.0.0", ClasspathMode.NONE),
            new ModuleRecord("order-service", "com.corp", "order-service", "1.0.0", ClasspathMode.NONE));

    @Autowired
    RepositoryIndexWriter writer;

    private long repoId;
    private IndexResult corp;

    @BeforeEach
    void setUp() {
        StoreFixtures.cleanIndexTables(jdbc);
        repoId = StoreFixtures.newRepository(jdbc, "corp-repo");
        corp = new JavaRepositoryIndexer().index(new IndexRequest(CORP_REPO,
                List.of(module("common-lib"), module("order-service")), new IndexerOptions(50, 300)));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    @Test
    void storesTheWholeIndexAndTheCommit() {
        WriteSummary summary = writer.replace(new RepositoryIndex(repoId, "abc123", MODULES, corp));

        assertThat(summary.usages()).isEqualTo(corp.usages().size()).isEqualTo(count("usage"));
        assertThat(summary.declarations()).isEqualTo(corp.declarations().size()).isEqualTo(count("symbol_declaration"));
        assertThat(summary.symbols()).isEqualTo(corp.symbols().size()).isEqualTo(count("symbol"));
        assertThat(summary.skippedSymbols()).isZero();
        assertThat(summary.skippedRows()).isZero();
        assertThat(jdbc.queryForObject("SELECT last_indexed_commit FROM scm_repository WHERE id = ?", String.class,
                repoId)).isEqualTo("abc123");
        Map<String, Object> call = jdbc.queryForMap("""
                SELECT f.symbol_key AS from_key, u.confidence, u.line_no, m.path AS module_path, u.snippet
                  FROM usage u
                  JOIN symbol t ON t.id = u.to_symbol_id
                  JOIN symbol f ON f.id = u.from_symbol_id
                  JOIN maven_module m ON m.id = u.module_id
                 WHERE t.symbol_key = 'com.corp.common.MoneyUtil#format(int)' AND u.kind = 'CALL' AND u.line_no = 18
                """);
        assertThat(call).containsEntry("FROM_KEY", "com.corp.order.OrderService#b()")
                .containsEntry("CONFIDENCE", Confidence.EXACT.name())
                .containsEntry("MODULE_PATH", "order-service")
                .containsEntry("SNIPPET", "public void b() { money.format(5); }");
    }

    @Test
    void rewritingReplacesInsteadOfDuplicating() {
        writer.replace(new RepositoryIndex(repoId, "abc123", MODULES, corp));
        int usages = count("usage");
        int declarations = count("symbol_declaration");

        writer.replace(new RepositoryIndex(repoId, "def456", MODULES, corp));

        assertThat(count("usage")).isEqualTo(usages);
        assertThat(count("symbol_declaration")).isEqualTo(declarations);
        assertThat(count("maven_module")).isEqualTo(2);
    }

    @Test
    void droppingAModuleRemovesItsRowsAndTheModule() {
        writer.replace(new RepositoryIndex(repoId, "abc123", MODULES, corp));
        IndexResult onlyCommon = new JavaRepositoryIndexer().index(new IndexRequest(CORP_REPO,
                List.of(module("common-lib")), new IndexerOptions(50, 300)));

        writer.replace(new RepositoryIndex(repoId, "def456", List.of(MODULES.getFirst()), onlyCommon));

        assertThat(jdbc.queryForList("SELECT path FROM maven_module", String.class)).containsExactly("common-lib");
        assertThat(count("usage")).isEqualTo(onlyCommon.usages().size());
    }

    @Test
    void failedWriteKeepsThePreviousIndex() {
        writer.replace(new RepositoryIndex(repoId, "abc123", MODULES, corp));
        int usages = count("usage");
        List<ModuleRecord> tooLongGroup = List.of(
                new ModuleRecord("common-lib", "g".repeat(400), "common-lib", "1", ClasspathMode.NONE),
                MODULES.get(1));

        assertThatThrownBy(() -> writer.replace(new RepositoryIndex(repoId, "def456", tooLongGroup, corp)))
                .isInstanceOf(RuntimeException.class);

        assertThat(count("usage")).isEqualTo(usages);
        assertThat(jdbc.queryForObject("SELECT last_indexed_commit FROM scm_repository WHERE id = ?", String.class,
                repoId)).isEqualTo("abc123");
    }

    @Test
    void oversizedMultibyteSnippetIsTruncatedToTheColumnWidth() {
        Usage original = corp.usages().getFirst();
        Usage huge = new Usage(original.fromKey(), original.toKey(), original.kind(), original.confidence(),
                original.modulePath(), original.filePath(), original.line(), original.column(), "ş".repeat(2100));
        List<Usage> usages = new ArrayList<>(corp.usages());
        usages.set(0, huge);
        IndexResult withHugeSnippet = new IndexResult(corp.symbols(), corp.declarations(), usages, corp.warnings());

        writer.replace(new RepositoryIndex(repoId, "abc123", MODULES, withHugeSnippet));

        String stored = jdbc.queryForObject("SELECT MAX(snippet) KEEP (DENSE_RANK FIRST ORDER BY LENGTHB(snippet) DESC) "
                + "FROM usage", String.class);
        assertThat(Utf8.byteLength(stored)).isLessThanOrEqualTo(4000).isGreaterThan(3990);
    }

    @Test
    void rejectsInconsistentInputBeforeWriting() {
        assertThatIllegalArgumentException().isThrownBy(() -> writer.replace(
                new RepositoryIndex(repoId, "abc123", List.of(MODULES.getFirst()), corp)))
                .withMessageContaining("order-service");
        assertThatIllegalArgumentException().isThrownBy(() -> writer.replace(new RepositoryIndex(repoId, "abc123",
                List.of(new ModuleRecord(" ", null, null, null, ClasspathMode.NONE)), corp)));
        assertThatIllegalArgumentException().isThrownBy(() -> writer.replace(
                new RepositoryIndex(repoId, " ", MODULES, corp)));
        Usage dangling = new Usage("p.Nope#x()", "p.Nope#y()", UsageKind.CALL, Confidence.EXACT, "common-lib",
                "f.java", 1, 1, "x");
        assertThatIllegalArgumentException().isThrownBy(() -> writer.replace(new RepositoryIndex(repoId, "abc123",
                MODULES, new IndexResult(corp.symbols(), corp.declarations(), List.of(dangling), List.of()))))
                .withMessageContaining("p.Nope#x()");
        assertThat(count("usage")).isZero();
    }

    @Test
    void unknownRepositoryIsReported() {
        assertThatThrownBy(() -> writer.replace(new RepositoryIndex(-1L, "abc123", MODULES, corp)))
                .isInstanceOf(RepositoryNotFoundException.class);
    }

    private static ModuleSource module(String name) {
        return new ModuleSource(name, List.of(CORP_REPO.resolve(name).resolve("src/main/java")), List.of());
    }

    private static Path fixture() {
        try {
            return Path.of(RepositoryIndexWriterTest.class.getResource("/fixtures/corp-repo").toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=RepositoryIndexWriterTest`
Expected: BUILD FAILURE, with `cannot find symbol` for `RepositoryIndexWriter`, `ModuleRecord` and `ClasspathMode`.

- [ ] **Step 3: Write the public write types**

`src/main/java/com/graphify/store/ClasspathMode.java`:

```java
package com.graphify.store;

/** How completely a module's Maven classpath was resolved (spec §3.2, §8). */
public enum ClasspathMode {
    FULL, PARTIAL, NONE
}
```

`src/main/java/com/graphify/store/ModuleRecord.java`:

```java
package com.graphify.store;

/**
 * One Maven module of a repository. {@code path} matches the indexer's {@code ModuleSource.modulePath} and must
 * not be blank (Oracle stores '' as NULL); the root module of a single-module repository is {@code "."}.
 */
public record ModuleRecord(String path, String groupId, String artifactId, String version, ClasspathMode classpathMode) {
}
```

`src/main/java/com/graphify/store/RepositoryIndex.java`:

```java
package com.graphify.store;

import com.graphify.indexer.model.IndexResult;
import java.util.List;

/** Everything one indexing pass produced for one repository at one commit. */
public record RepositoryIndex(long repositoryId, String commit, List<ModuleRecord> modules, IndexResult result) {

    public RepositoryIndex {
        modules = List.copyOf(modules);
    }
}
```

`src/main/java/com/graphify/store/WriteSummary.java`:

```java
package com.graphify.store;

/**
 * What was written. {@code skippedSymbols}: symbols too long for their columns; {@code skippedRows}: declarations
 * and usages dropped because they reference a skipped symbol or their file path is too long.
 */
public record WriteSummary(int symbols, int declarations, int usages, int skippedSymbols, int skippedRows) {
}
```

`src/main/java/com/graphify/store/RepositoryNotFoundException.java`:

```java
package com.graphify.store;

public class RepositoryNotFoundException extends RuntimeException {

    public RepositoryNotFoundException(long repositoryId) {
        super("No scm_repository row with id " + repositoryId);
    }
}
```

- [ ] **Step 4: Write `ModuleWriter`**

`src/main/java/com/graphify/store/ModuleWriter.java`:

```java
package com.graphify.store;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Makes a repository's {@code maven_module} rows match the given modules. Caller removes their usages first. */
@Component
class ModuleWriter {

    private static final String MERGE = """
            MERGE INTO maven_module m
            USING (SELECT ? AS repo_id, ? AS path, ? AS group_id, ? AS artifact_id, ? AS version,
                          ? AS classpath_mode FROM dual) n
            ON (m.repo_id = n.repo_id AND m.path = n.path)
            WHEN MATCHED THEN UPDATE SET m.group_id = n.group_id, m.artifact_id = n.artifact_id,
                    m.version = n.version, m.classpath_mode = n.classpath_mode
            WHEN NOT MATCHED THEN INSERT (repo_id, path, group_id, artifact_id, version, classpath_mode)
                VALUES (n.repo_id, n.path, n.group_id, n.artifact_id, n.version, n.classpath_mode)
            """;

    private final JdbcTemplate jdbc;

    ModuleWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns module path → id for the given modules after removing the repository's other modules. */
    Map<String, Long> replace(long repositoryId, List<ModuleRecord> modules) {
        Set<String> wanted = modules.stream().map(ModuleRecord::path).collect(Collectors.toSet());
        List<Object[]> stale = jdbc.query("SELECT id, path FROM maven_module WHERE repo_id = ?",
                        (rs, row) -> new Object[] {rs.getLong("id"), rs.getString("path")}, repositoryId)
                .stream()
                .filter(row -> !wanted.contains((String) row[1]))
                .map(row -> new Object[] {row[0]})
                .toList();
        jdbc.batchUpdate("DELETE FROM maven_module WHERE id = ?", stale);
        jdbc.batchUpdate(MERGE, modules.stream().map(module -> new Object[] {
                repositoryId, module.path(), module.groupId(), module.artifactId(), module.version(),
                module.classpathMode().name()}).toList());
        Map<String, Long> ids = new HashMap<>();
        jdbc.query("SELECT id, path FROM maven_module WHERE repo_id = ?",
                rs -> {
                    ids.put(rs.getString("path"), rs.getLong("id"));
                },
                repositoryId);
        return ids;
    }
}
```

- [ ] **Step 5: Write `RepositoryIndexWriter`**

`src/main/java/com/graphify/store/RepositoryIndexWriter.java`:

```java
package com.graphify.store;

import com.graphify.common.util.Utf8;
import com.graphify.indexer.model.Declaration;
import com.graphify.indexer.model.IndexResult;
import com.graphify.indexer.model.Symbol;
import com.graphify.indexer.model.Usage;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Replaces one repository's modules, declarations and usages with a new index result in a single transaction.
 * If anything fails, the previous index stays as it was (spec §8).
 */
@Service
public class RepositoryIndexWriter {

    private static final String INSERT_DECLARATION =
            "INSERT INTO symbol_declaration (symbol_id, module_id, file_path, line_no) VALUES (?, ?, ?, ?)";

    private static final String INSERT_USAGE = """
            INSERT INTO usage (from_symbol_id, to_symbol_id, module_id, kind, confidence, file_path, line_no,
                               column_no, snippet)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;
    private final SymbolWriter symbols;
    private final ModuleWriter modules;
    private final AppSettings settings;

    public RepositoryIndexWriter(JdbcTemplate jdbc, SymbolWriter symbols, ModuleWriter modules, AppSettings settings) {
        this.jdbc = jdbc;
        this.symbols = symbols;
        this.modules = modules;
        this.settings = settings;
    }

    @Transactional
    public WriteSummary replace(RepositoryIndex index) {
        validate(index);
        long repositoryId = index.repositoryId();
        lock(repositoryId);
        jdbc.update("DELETE FROM usage WHERE module_id IN (SELECT id FROM maven_module WHERE repo_id = ?)",
                repositoryId);
        jdbc.update("DELETE FROM symbol_declaration WHERE module_id IN "
                + "(SELECT id FROM maven_module WHERE repo_id = ?)", repositoryId);
        Map<String, Long> moduleIds = modules.replace(repositoryId, index.modules());

        int batchSize = settings.getInt(SettingKeys.STORE_JDBC_BATCH_SIZE);
        IndexResult result = index.result();
        SymbolWriter.Result stored = symbols.upsert(result.symbols(), batchSize);
        Map<String, Long> ids = stored.ids();

        int skippedRows = 0;
        List<Object[]> declarations = new ArrayList<>();
        for (Declaration declaration : result.declarations()) {
            Long symbolId = ids.get(declaration.symbolKey());
            if (symbolId == null || !StoreLimits.fits(declaration.filePath(), StoreLimits.FILE_PATH_BYTES)) {
                skippedRows++;
                continue;
            }
            declarations.add(new Object[] {symbolId, moduleIds.get(declaration.modulePath()),
                    declaration.filePath(), declaration.line()});
        }
        List<Object[]> usages = new ArrayList<>();
        for (Usage usage : result.usages()) {
            Long fromId = ids.get(usage.fromKey());
            Long toId = ids.get(usage.toKey());
            if (fromId == null || toId == null || !StoreLimits.fits(usage.filePath(), StoreLimits.FILE_PATH_BYTES)) {
                skippedRows++;
                continue;
            }
            usages.add(new Object[] {fromId, toId, moduleIds.get(usage.modulePath()), usage.kind().name(),
                    usage.confidence().name(), usage.filePath(), usage.line(), usage.column(),
                    usage.snippet() == null ? null : Utf8.truncateToBytes(usage.snippet(), StoreLimits.SNIPPET_BYTES)});
        }
        for (List<Object[]> chunk : Chunks.of(declarations, batchSize)) {
            jdbc.batchUpdate(INSERT_DECLARATION, chunk);
        }
        for (List<Object[]> chunk : Chunks.of(usages, batchSize)) {
            jdbc.batchUpdate(INSERT_USAGE, chunk);
        }
        jdbc.update("UPDATE scm_repository SET last_indexed_commit = ?, last_indexed_at = SYSTIMESTAMP WHERE id = ?",
                index.commit(), repositoryId);
        return new WriteSummary(ids.size(), declarations.size(), usages.size(), stored.skippedKeys().size(),
                skippedRows);
    }

    private void lock(long repositoryId) {
        List<Long> found = jdbc.queryForList("SELECT id FROM scm_repository WHERE id = ? FOR UPDATE", Long.class,
                repositoryId);
        if (found.isEmpty()) {
            throw new RepositoryNotFoundException(repositoryId);
        }
    }

    private static void validate(RepositoryIndex index) {
        if (index.commit() == null || index.commit().isBlank()) {
            throw new IllegalArgumentException("commit must not be blank");
        }
        Set<String> modulePaths = new HashSet<>();
        for (ModuleRecord module : index.modules()) {
            if (module.path() == null || module.path().isBlank()) {
                throw new IllegalArgumentException("module path must not be blank; use \".\" for the root module");
            }
            if (module.classpathMode() == null) {
                throw new IllegalArgumentException("module " + module.path() + " has no classpath mode");
            }
            if (!modulePaths.add(module.path())) {
                throw new IllegalArgumentException("duplicate module path " + module.path());
            }
        }
        Set<String> symbolKeys = new HashSet<>();
        for (Symbol symbol : index.result().symbols()) {
            symbolKeys.add(symbol.key());
        }
        for (Declaration declaration : index.result().declarations()) {
            requireModule(modulePaths, declaration.modulePath());
            requireSymbol(symbolKeys, declaration.symbolKey());
        }
        for (Usage usage : index.result().usages()) {
            requireModule(modulePaths, usage.modulePath());
            requireSymbol(symbolKeys, usage.fromKey());
            requireSymbol(symbolKeys, usage.toKey());
        }
    }

    private static void requireModule(Set<String> modulePaths, String modulePath) {
        if (!modulePaths.contains(modulePath)) {
            throw new IllegalArgumentException("index result references module " + modulePath
                    + " which is not in the module list " + modulePaths);
        }
    }

    private static void requireSymbol(Set<String> symbolKeys, String key) {
        if (!symbolKeys.contains(key)) {
            throw new IllegalArgumentException("index result references unknown symbol " + key);
        }
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./mvnw test -Dtest=RepositoryIndexWriterTest`
Expected: 7 tests pass. If `storesTheWholeIndexAndTheCommit` fails on the snippet text, compare it with the fixture's line 18 (`public void b() { money.format(5); }`). Do not edit the fixture.

Run: `./mvnw test`
Expected: all tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/graphify/store src/test/java/com/graphify/store
git commit -m "feat(store): replace a repository's index in one transaction" -m "<your harness Co-Authored-By trailer>"
```

---

### Task 7: Orphan cleanup and the cross-repository acceptance query

**Files:**
- Create: `src/main/java/com/graphify/store/SymbolCleanup.java`
- Modify: `src/test/java/com/graphify/indexer/TestJars.java` (make the class and `jar` method `public`)
- Test: `src/test/java/com/graphify/store/SymbolCleanupTest.java`
- Test: `src/test/java/com/graphify/store/CrossRepositoryUsageTest.java`

**Interfaces:**
- Consumes `RepositoryIndexWriter.replace`, `StoreFixtures` and plan 1's `TestJars.jar(Path, String, Map<String,String>, Set<String>)`.
- Produces `public class SymbolCleanup` (a `@Component`) with `@Transactional public int deleteOrphans()`, which returns the number of deleted symbols. The caller must not run it concurrently with index writes. Plan 4 runs it under the indexing lock.

- [ ] **Step 1: Make `TestJars` usable from the store tests**

In `src/test/java/com/graphify/indexer/TestJars.java`, change `final class TestJars {` to `public final class TestJars {`, and change `static Path jar(` to `public static Path jar(`.

- [ ] **Step 2: Write the failing tests**

`src/test/java/com/graphify/store/SymbolCleanupTest.java`:

```java
package com.graphify.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.indexer.IndexRequest;
import com.graphify.indexer.IndexerOptions;
import com.graphify.indexer.JavaRepositoryIndexer;
import com.graphify.indexer.ModuleSource;
import com.graphify.indexer.model.IndexResult;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SymbolCleanupTest extends OracleIntegrationTest {

    @Autowired
    RepositoryIndexWriter writer;

    @Autowired
    SymbolCleanup cleanup;

    @BeforeEach
    void clean() {
        StoreFixtures.cleanIndexTables(jdbc);
    }

    @Test
    void deletesOnlySymbolsNothingReferences() throws URISyntaxException {
        Path repo = Path.of(getClass().getResource("/fixtures/corp-repo").toURI());
        ModuleSource common = new ModuleSource("common-lib", List.of(repo.resolve("common-lib/src/main/java")), List.of());
        ModuleRecord commonRecord = new ModuleRecord("common-lib", "com.corp", "common-lib", "1", ClasspathMode.NONE);
        long repoId = StoreFixtures.newRepository(jdbc, "common-lib");
        IndexResult result = new JavaRepositoryIndexer().index(
                new IndexRequest(repo, List.of(common), new IndexerOptions(50, 300)));
        writer.replace(new RepositoryIndex(repoId, "c1", List.of(commonRecord), result));
        cleanup.deleteOrphans(); // owner types the index registered but nothing references; not under test here
        jdbc.update("""
                INSERT INTO symbol (symbol_key, kind, class_fqn, display_signature, origin, name_only)
                VALUES ('zz.Orphan', 'CLASS', 'zz.Orphan', 'Orphan', 'BINARY', 0)
                """);
        int referenced = jdbc.queryForObject("SELECT COUNT(*) FROM symbol WHERE symbol_key <> 'zz.Orphan'",
                Integer.class);

        assertThat(cleanup.deleteOrphans()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM symbol", Integer.class)).isEqualTo(referenced);

        writer.replace(new RepositoryIndex(repoId, "c2", List.of(commonRecord),
                new IndexResult(List.of(), List.of(), List.of(), List.of())));

        assertThat(cleanup.deleteOrphans()).isEqualTo(referenced);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM symbol", Integer.class)).isZero();
    }
}
```

`src/test/java/com/graphify/store/CrossRepositoryUsageTest.java`:

```java
package com.graphify.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.indexer.IndexRequest;
import com.graphify.indexer.IndexerOptions;
import com.graphify.indexer.JavaRepositoryIndexer;
import com.graphify.indexer.ModuleSource;
import com.graphify.indexer.TestJars;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The plan-2 acceptance: common-lib and order-service are separate repositories, order-service sees common-lib
 * only as a jar, and SQL still answers "which projects use MoneyUtil#format(int)".
 */
class CrossRepositoryUsageTest extends OracleIntegrationTest {

    private static final String FORMAT_INT = "com.corp.common.MoneyUtil#format(int)";

    @Autowired
    RepositoryIndexWriter writer;

    @TempDir
    Path work;

    @BeforeEach
    void clean() {
        StoreFixtures.cleanIndexTables(jdbc);
    }

    @Test
    void usageInOneRepositoryJoinsTheDeclarationInAnother() throws Exception {
        Path repo = Path.of(getClass().getResource("/fixtures/corp-repo").toURI());
        Path commonSources = repo.resolve("common-lib/src/main/java");
        Path commonJar = TestJars.jar(work, "common-lib", sources(commonSources), Set.of());
        long orderRepo = StoreFixtures.newRepository(jdbc, "order-service");
        long commonRepo = StoreFixtures.newRepository(jdbc, "common-lib");

        // order-service first: it only knows MoneyUtil as BINARY; common-lib's SOURCE row must win afterwards.
        writer.replace(new RepositoryIndex(orderRepo, "o1",
                List.of(new ModuleRecord("order-service", "com.corp", "order-service", "1", ClasspathMode.FULL)),
                new JavaRepositoryIndexer().index(new IndexRequest(repo, List.of(new ModuleSource("order-service",
                        List.of(repo.resolve("order-service/src/main/java")), List.of(commonJar))),
                        new IndexerOptions(50, 300)))));
        writer.replace(new RepositoryIndex(commonRepo, "c1",
                List.of(new ModuleRecord("common-lib", "com.corp", "common-lib", "1", ClasspathMode.FULL)),
                new JavaRepositoryIndexer().index(new IndexRequest(repo,
                        List.of(new ModuleSource("common-lib", List.of(commonSources), List.of())),
                        new IndexerOptions(50, 300)))));

        assertThat(jdbc.queryForList("""
                SELECT DISTINCT r.slug
                  FROM usage u
                  JOIN symbol s ON s.id = u.to_symbol_id
                  JOIN maven_module m ON m.id = u.module_id
                  JOIN scm_repository r ON r.id = m.repo_id
                 WHERE s.symbol_key = ?
                """, String.class, FORMAT_INT)).containsExactly("order-service");
        assertThat(jdbc.queryForList("""
                SELECT r.slug
                  FROM symbol_declaration d
                  JOIN symbol s ON s.id = d.symbol_id
                  JOIN maven_module m ON m.id = d.module_id
                  JOIN scm_repository r ON r.id = m.repo_id
                 WHERE s.symbol_key = ?
                """, String.class, FORMAT_INT)).containsExactly("common-lib");
        assertThat(jdbc.queryForObject("SELECT origin FROM symbol WHERE symbol_key = ?", String.class, FORMAT_INT))
                .isEqualTo("SOURCE");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM usage u JOIN symbol s ON s.id = u.to_symbol_id
                 WHERE s.symbol_key = ? AND u.confidence = 'EXACT'
                """, Integer.class, FORMAT_INT)).isEqualTo(2);
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

The fixture `OrderService` calls `format(int)` twice, on lines 18 and 19. That is why the EXACT count is 2.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./mvnw test -Dtest='SymbolCleanupTest,CrossRepositoryUsageTest'`
Expected: BUILD FAILURE, with `cannot find symbol ... class SymbolCleanup`. `CrossRepositoryUsageTest` compiles once `TestJars` is public.

- [ ] **Step 4: Write `SymbolCleanup`**

`src/main/java/com/graphify/store/SymbolCleanup.java`:

```java
package com.graphify.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes symbols that no usage or declaration references any more (spec §4.4). Must not run while an index write
 * is in progress: a write may have merged a symbol it has not yet referenced. Plan 4 runs it under the index lock.
 */
@Component
public class SymbolCleanup {

    private final JdbcTemplate jdbc;

    public SymbolCleanup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public int deleteOrphans() {
        return jdbc.update("""
                DELETE FROM symbol s
                 WHERE NOT EXISTS (SELECT 1 FROM usage u WHERE u.to_symbol_id = s.id)
                   AND NOT EXISTS (SELECT 1 FROM usage u WHERE u.from_symbol_id = s.id)
                   AND NOT EXISTS (SELECT 1 FROM symbol_declaration d WHERE d.symbol_id = s.id)
                """);
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw test -Dtest='SymbolCleanupTest,CrossRepositoryUsageTest'`
Expected: 2 tests pass.

If `CrossRepositoryUsageTest` finds a confidence other than EXACT, or a different repository list, read the stored rows for `FORMAT_INT` before changing anything. A NAME_ONLY key such as `MoneyUtil#format/1` means the jar was not on the order-service classpath. Fix the test setup, not the expected values.

Run: `./mvnw test`
Expected: all tests pass, BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/graphify/store/SymbolCleanup.java src/test/java/com/graphify/indexer/TestJars.java src/test/java/com/graphify/store
git commit -m "feat(store): add orphan symbol cleanup and cross-repository acceptance test" -m "<your harness Co-Authored-By trailer>"
```
