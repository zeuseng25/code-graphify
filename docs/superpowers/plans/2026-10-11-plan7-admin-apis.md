# Plan 7: Remaining Admin APIs and Lock Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the spec §10.6 admin surface so that every operational value is managed through the API ("kodda sabit değer yok"), and harden the index lock as plan 5's follow-ups asked. The admin surface covers:
- SCM connections, with their last test and sync results
- Maven artifact repositories, with a connection test
- settings
- entry-point annotations
- impact relation rules

**Architecture:**
- **Shared secret rule.** `common.secret.SecretUpdate` holds the single rule for secrets in full-document updates (spec §6.4). A null secret keeps the stored one, but only for the same target (URL and username), so a stored credential is never sent to a host it was not set for. An empty string clears the secret. The LDAP administration from plan 6 switches to it.
- **One service and one controller per resource.** The services validate, persist through the existing repositories, extended here, and audit every change without its secret.
- **Connection tests.**
  - The SCM test asks Bitbucket for one repository and does not retry.
  - The artifact repository test is one HTTP request, or a directory check for `file:` URLs.
  - Both store `last_test_status` and `last_test_at`. A failure answers 502 with a masked message.
- **Lock and run hardening.**
  - `IndexLock` fences a takeover on `acquired_at`.
  - A taken-over run gets its own error text.
  - `index_run_repo` becomes unique per `(run_id, repo_id)`.
  - `RepositoryIndexer` rethrows interruptions, so a shutdown is recorded as `INTERRUPTED` and not `FAILED`.

**Tech Stack:** Java 25, Spring Boot 4.1.1 (Spring MVC, Spring Security 7), Oracle with Flyway, JdbcTemplate, JDK `HttpClient`, Testcontainers, JUnit 5, AssertJ, `com.sun.net.httpserver` for fake servers.

**Spec:** `docs/superpowers/specs/2026-10-05-impact-analyzer-design.md`
- §6.2 (SCM connections, artifact repositories, entry-point annotations, impact rules)
- §6.4 (secrets never returned; null keeps; validation; audit without secrets)
- §8 (409 business rules, 502 external systems)
- §10.6 rows "SCM bağlantıları", "Maven depoları", "Ayarlar", "Giriş noktası annotation'ları", "Etki kuralları"

**Carried items:**
- `docs/superpowers/plans/2026-10-09-plan5-followups.md`: lock-takeover fencing, unique `(run_id, repo_id)`, interrupt rethrow in `RepositoryIndexer`, takeover error text, `last_sync_*` in the connection API.
- `docs/superpowers/plans/2026-10-10-plan6-followups.md`: review the `ExternalSystemException` detail for the test endpoints. This is done here: messages are masked and contain no secrets, and the endpoints are admin-only.

**Decided not to do:**
- **AppPrincipal-backed test base** (plan-6 follow-up). Every `as(...)` user would need an `app_user` row and a local account, which counts toward the last-admin rule. That would change the meaning of the existing admin tests for little gain. `CurrentUserRefreshFilter` stays covered by the real-login `LoginFlow` tests.
- **Requiring a passing `/admin/ldap/test` before saving.** An admin can already test before saving, and the lockout cases are guarded.

## Plan series

| Plan | Scope | Status |
|---|---|---|
| 1–6 | Indexer, persistence, search/impact, acquisition, orchestration, authentication | merged |
| **7** | **Remaining admin APIs + lock hardening** (this plan) | — |
| 8 | Repo graph view | next |
| later | React UI; LLM purpose/misuse layer | — |

## Global Constraints

- **Environment.**
  - JDK 25: every command runs with `export JAVA_HOME=/opt/homebrew/opt/openjdk@25`; use `./mvnw`.
  - Docker must be running.
- **No new dependencies.**
- **"Kodda sabit değer yok" (no hardcoded values in code).**
  - New operational values come from settings, and the new key `artifact.test_timeout` is seeded only in `V7__admin_and_lock_hardening.sql`.
  - Protocol facts are named constants with a comment: the Bitbucket path and page size of the test call, and the accepted URL schemes.
- **Secrets (spec §6.4).**
  - A secret (SCM token or password, artifact repository password, LDAP bind password) never appears in an API response, an audit row, a log line or an exception message. Responses carry only `secretSet`.
  - In a full PUT, a null secret keeps the stored one only when the target (URL and username, or bind DN) is unchanged. If a stored secret exists and the target changed, the answer is 400 "Re-enter the … when changing …".
  - `""` clears the secret.
  - Secrets are encrypted with `SecretCipher`, and their plaintext length is capped so the ciphertext fits its column.
- **Errors.**
  - Validation errors are 400. An unknown id or key is 404.
  - A uniqueness clash or a business rule (deleting a connection that still has repositories) is 409.
  - A failed connection test is 502. Its message goes through `UrlMasking.mask` and never contains a secret.
- **Audit.** Every create, update and delete writes one `audit_log` row: actor = signed-in user, target = resource name or key, details = changed field names or a short summary, never secret values.
- **Authorization.** All endpoints sit under `/api/v1/admin/**` (ADMIN, already enforced by `SecurityConfiguration`). The actor is `Authentication.getName()`.
- **Schema.** New schema only in `V7__admin_and_lock_hardening.sql`; V1–V6 are never edited.
- **Tests.**
  - Oracle-backed tests extend `OracleIntegrationTest` and use `mvc` (ADMIN+USER `tester` with a valid CSRF header) or `as(name, roles…)`.
  - **Never use spring-security-test's `csrf()`**: it replaces the shared CsrfFilter's repository for the rest of the test context.
  - Tests restore every row and setting they change, in `@AfterEach`.
- **Commits** end with the Co-Authored-By trailer the committing agent's harness provides.

## Review Focus

1. **An admin repoints a connection or repository at another host without retyping its secret.** The answer is 400, and the stored secret is never sent anywhere. Tests: Task 2 `SecretUpdateTest`; Task 3 `ScmConnectionAdminApiTest.aChangedTargetNeedsTheSecretAgain`; Task 4 `ArtifactRepositoryAdminApiTest.aChangedTargetNeedsTheSecretAgain`.
2. **Deleting a connection that has repositories.** This is 409, nothing is deleted, and run history survives. Test: Task 3 `ScmConnectionAdminApiTest.aConnectionWithRepositoriesCannotBeDeleted`.
3. **A setting change through the API.** A bad value is 400 with the reason, an unknown key is 404, and a good value takes effect at once, including the cron reschedule event. Test: Task 2 `SettingsAdminApiTest`.
4. **Disabling an entry-point annotation or an impact rule.** The next impact analysis reflects it without a restart. Test: Task 5 `ImpactRulesAdminApiTest.changesApplyToTheNextAnalysis`.
5. **A live cleanup re-takes the lock between a stale-holder read and the takeover.** The takeover must not displace it. Test: Task 1 `IndexLockTest.aTakeoverIsFencedOnTheAcquisitionTime`.

---

## File Structure

| File | Responsibility |
|---|---|
| `db/migration/V7__admin_and_lock_hardening.sql` | unique `(run_id, repo_id)`; `artifact_repository.last_test_*`; `artifact.test_timeout` |
| `indexing/IndexLock.java`, `IndexRunRecorder.java`, `RepositoryIndexer.java` (modify) | Fenced takeover, takeover error text, interrupt rethrow |
| `common/secret/SecretUpdate.java`; `auth/LdapAdministration.java` (modify) | Shared secret rule |
| `settings/SettingsAdminController.java`; `common/exception/ApiExceptionHandler.java` (modify) | Settings API; 400/404 for settings errors |
| `scm/ScmConnectionView.java`, `ScmConnectionUpdate.java`, `ScmConnectionAdministration.java`, `ScmConnectionAdminController.java`; `scm/ScmClient.java`, `BitbucketDataCenterClient.java`, `ScmConnections.java` (modify) | SCM connections API and test |
| `maven/ArtifactRepositoryView.java`, `ArtifactRepositoryUpdate.java`, `ArtifactRepositoryProbe.java`, `ArtifactRepositoryAdministration.java`, `ArtifactRepositoryAdminController.java`; `maven/ArtifactRepositories.java` (modify) | Artifact repositories API and test |
| `impact/EntryPointAnnotation.java`, `ImpactRuleAdministration.java`, `ImpactRulesAdminController.java` | Entry-point annotations and impact rules API |
| `README.md` (modify) | New endpoints |

Main paths are under `src/main/java/com/graphify/` unless they start with `db/`, which is `src/main/resources/db/migration/`.

---

### Task 1: Lock and run hardening (plan-5 carry-over)

**Files:**
- Create: `src/main/resources/db/migration/V7__admin_and_lock_hardening.sql`
- Modify: `src/main/java/com/graphify/indexing/IndexLock.java`, `IndexRunRecorder.java`, `RepositoryIndexer.java`; `src/main/java/com/graphify/settings/SettingKeys.java`
- Test: `src/test/java/com/graphify/indexing/IndexLockTest.java`, `IndexRunRecorderTest.java`, `RepositoryIndexerInterruptionTest.java` (new); `src/test/java/com/graphify/store/SchemaMigrationTest.java`

**Interfaces:**
- **Consumes:** `IndexLock` (in-process `held` set, `takeOverStale`), `IndexRunRecorder` (`recover(Long)`, `recoverRun`, `recoverInterrupted`, `INTERRUPTED_RUN`), `IndexRunExecutor.isInterruption(Throwable, boolean)` (package-private static), `RepositoryIndexer.run` (three `catch (RuntimeException e)` blocks).
- **Produces (schema, V7):**
  - Duplicate `index_run_repo` rows per `(run_id, repo_id)` are removed (keeping the lowest id), then `uq_index_run_repo UNIQUE (run_id, repo_id)` is added.
  - `artifact_repository.last_test_status VARCHAR2(30 BYTE)` and `last_test_at TIMESTAMP WITH TIME ZONE`.
  - Setting `artifact.test_timeout` (DURATION, `PT10S`) and `SettingKeys.ARTIFACT_TEST_TIMEOUT`.
- **Produces (`IndexLock`):**
  - The takeover reads holder and `acquired_at` together.
  - It updates only `WHERE holder = :stale AND acquired_at = :staleAcquiredAt`, so a holder that re-took the lock in the meantime is never displaced.
  - A package-private `boolean takeOver(String holder, String staleHolder, OffsetDateTime staleAcquiredAt)` exists for the fencing test.
- **Produces (`IndexRunRecorder`):**
  - `recoverRun(long runId)` finishes the run with `TAKEN_OVER_RUN` = "The process running this run no longer held the index lock; the lock was taken over".
  - The run's in-flight repositories get `TAKEN_OVER_REPOSITORY` = "The run that was indexing this repository lost the index lock".
  - Startup recovery keeps its existing texts.
- **Produces (`RepositoryIndexer`):** each `catch (RuntimeException e)` first calls a package-private static `rethrowIfInterrupted(RuntimeException e)`. That method rethrows when `IndexRunExecutor.isInterruption(e, Thread.currentThread().isInterrupted())`. The executor then leaves the repository to recovery (INTERRUPTED) instead of recording CLONE_FAILED or FAILED.

- [ ] **Step 1: Write the failing tests**

Append to `src/test/java/com/graphify/store/SchemaMigrationTest.java`, inside the class (add `import static org.assertj.core.api.Assertions.assertThatThrownBy;` and `org.springframework.dao.DataIntegrityViolationException` if missing):

```java
    @Test
    void anIndexRunRecordsOneOutcomePerRepositoryAndArtifactRepositoriesRememberTheirTest() {
        StoreFixtures.cleanIndexTables(jdbc);
        long repo = StoreFixtures.newRepository(jdbc, "uniq");
        jdbc.update("INSERT INTO index_run (trigger_type, scope, status, started_by) VALUES ('MANUAL', 'ALL', 'RUNNING', 't')");
        long run = jdbc.queryForObject("SELECT MAX(id) FROM index_run", Long.class);
        jdbc.update("INSERT INTO index_run_repo (run_id, repo_id, status) VALUES (?, ?, 'SUCCESS')", run, repo);

        assertThatThrownBy(() -> jdbc.update("INSERT INTO index_run_repo (run_id, repo_id, status) VALUES (?, ?, 'FAILED')",
                run, repo)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForList("SELECT LOWER(column_name) FROM user_tab_columns WHERE table_name = "
                + "'ARTIFACT_REPOSITORY'", String.class)).contains("last_test_status", "last_test_at");
        assertThat(jdbc.queryForList("SELECT setting_key FROM app_setting", String.class))
                .contains("artifact.test_timeout");
        StoreFixtures.cleanIndexTables(jdbc);
    }
```

Append to `src/test/java/com/graphify/indexing/IndexLockTest.java`, inside the class (add `import java.time.OffsetDateTime;`):

```java
    @Test
    void aTakeoverIsFencedOnTheAcquisitionTime() {
        jdbc.update("UPDATE index_lock SET holder = 'cleanup', acquired_at = SYSTIMESTAMP - INTERVAL '1' HOUR "
                + "WHERE lock_name = 'INDEX'");
        OffsetDateTime seen = jdbc.queryForObject("SELECT acquired_at FROM index_lock WHERE lock_name = 'INDEX'",
                OffsetDateTime.class);
        // a live cleanup re-took the lock after the stale read: same holder name, newer acquisition time
        jdbc.update("UPDATE index_lock SET acquired_at = SYSTIMESTAMP WHERE lock_name = 'INDEX'");

        assertThat(lock.takeOver("run:9", IndexLock.CLEANUP_HOLDER, seen)).isFalse();
        assertThat(lock.holder()).contains(IndexLock.CLEANUP_HOLDER);

        OffsetDateTime current = jdbc.queryForObject("SELECT acquired_at FROM index_lock WHERE lock_name = 'INDEX'",
                OffsetDateTime.class);
        assertThat(lock.takeOver("run:9", IndexLock.CLEANUP_HOLDER, current)).isTrue();
        assertThat(lock.holder()).contains("run:9");
        lock.release("run:9");
    }
```

Append to `src/test/java/com/graphify/indexing/IndexRunRecorderTest.java`, inside the class:

```java
    @Test
    void aTakenOverRunIsLabelledAsSuch() {
        long run = start();
        runs.markStarted(run, repoId);

        assertThat(runs.recoverRun(run)).isTrue();

        assertThat(jdbc.queryForObject("SELECT error FROM index_run WHERE id = ?", String.class, run))
                .isEqualTo(IndexRunRecorder.TAKEN_OVER_RUN);
        assertThat(jdbc.queryForObject("SELECT error FROM index_run_repo WHERE run_id = ?", String.class, run))
                .isEqualTo(IndexRunRecorder.TAKEN_OVER_REPOSITORY);
    }
```

`src/test/java/com/graphify/indexing/RepositoryIndexerInterruptionTest.java`:

```java
package com.graphify.indexing;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InterruptedIOException;
import java.nio.channels.ClosedByInterruptException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RepositoryIndexerInterruptionTest {

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void interruptionsAreRethrownSoTheRepositoryIsLeftToRecovery() {
        RuntimeException wrapped = new IllegalStateException("git", new ClosedByInterruptException());

        assertThatThrownBy(() -> RepositoryIndexer.rethrowIfInterrupted(wrapped)).isSameAs(wrapped);
        assertThatThrownBy(() -> RepositoryIndexer.rethrowIfInterrupted(
                new IllegalStateException(new InterruptedIOException("maven")))).isInstanceOf(IllegalStateException.class);

        Thread.currentThread().interrupt();
        RuntimeException plain = new IllegalStateException("anything");
        assertThatThrownBy(() -> RepositoryIndexer.rethrowIfInterrupted(plain)).isSameAs(plain);
    }

    @Test
    void ordinaryFailuresAreNotRethrown() {
        assertThatCode(() -> RepositoryIndexer.rethrowIfInterrupted(new IllegalStateException("clone failed")))
                .doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -Dtest='SchemaMigrationTest,IndexLockTest,IndexRunRecorderTest,RepositoryIndexerInterruptionTest'`
Expected: compile errors (`takeOver`, `TAKEN_OVER_RUN`, `rethrowIfInterrupted`). Once they compile, the schema test fails until V7 exists.

- [ ] **Step 3: Write V7**

`src/main/resources/db/migration/V7__admin_and_lock_hardening.sql` (UTF-8):

```sql
-- Plan 7: one outcome per repository and run, artifact repository test results and the artifact test timeout.

DELETE FROM index_run_repo a
 WHERE EXISTS (SELECT 1 FROM index_run_repo b WHERE b.run_id = a.run_id AND b.repo_id = a.repo_id AND b.id < a.id);
ALTER TABLE index_run_repo ADD CONSTRAINT uq_index_run_repo UNIQUE (run_id, repo_id);

ALTER TABLE artifact_repository ADD (
    last_test_status VARCHAR2(30 BYTE),
    last_test_at     TIMESTAMP WITH TIME ZONE
);

INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('artifact.test_timeout', 'PT10S', 'DURATION', 'Maven deposu bağlantı testi zaman aşımı', NULL, NULL);
```

Add to `SettingKeys.java`:

```java
    public static final String ARTIFACT_TEST_TIMEOUT = "artifact.test_timeout";
```

- [ ] **Step 4: Fence the takeover**

In `IndexLock.java`, replace `takeOverStale` with the following, and add the imports `java.time.OffsetDateTime` and `java.util.Optional` if missing:

```java
    /** The holder row as one read: its name and when it was taken. */
    private record Holding(String holder, OffsetDateTime acquiredAt) {
    }

    private Optional<Holding> holding() {
        return jdbc.query("SELECT holder, acquired_at FROM index_lock WHERE lock_name = ? AND holder IS NOT NULL",
                (rs, row) -> new Holding(rs.getString("holder"), rs.getObject("acquired_at", OffsetDateTime.class)),
                LOCK_NAME).stream().findFirst();
    }

    private boolean takeOverStale(String holder) {
        Optional<Holding> current = holding();
        if (current.isEmpty()) { // released in the meantime
            return take(holder);
        }
        Holding stale = current.get();
        if (held.contains(stale.holder())) {
            return false;
        }
        log.warn("The index lock holder '{}' is not running in this process; '{}' takes the lock over", stale.holder(),
                holder);
        runIdOf(stale.holder()).ifPresent(runs::recoverRun);
        return takeOver(holder, stale.holder(), stale.acquiredAt()) || take(holder);
    }

    /**
     * Replaces exactly the holding that was judged stale: fenced on the acquisition time, so a holder that re-took the
     * lock under the same name in the meantime (a new cleanup) is never displaced.
     */
    boolean takeOver(String holder, String staleHolder, OffsetDateTime staleAcquiredAt) {
        return jdbc.update("UPDATE index_lock SET holder = ?, acquired_at = SYSTIMESTAMP "
                + "WHERE lock_name = ? AND holder = ? AND acquired_at = ?", holder, LOCK_NAME, staleHolder,
                staleAcquiredAt) == 1;
    }
```

- [ ] **Step 5: Label taken-over runs**

In `IndexRunRecorder.java`:
- Add these constants:

  ```java
      static final String TAKEN_OVER_RUN =
              "The process running this run no longer held the index lock; the lock was taken over";
      static final String TAKEN_OVER_REPOSITORY = "The run that was indexing this repository lost the index lock";
  ```

- Change `recover(Long runId)` to `recover(Long runId, String runError, String repositoryError)`, and use those parameters where it currently uses `INTERRUPTED_RUN` and `INTERRUPTED_REPOSITORY`.
- `recoverInterrupted()` calls `recover(null, INTERRUPTED_RUN, INTERRUPTED_REPOSITORY)`.
- `recoverRun(runId)` calls `recover(runId, TAKEN_OVER_RUN, TAKEN_OVER_REPOSITORY)`.
- Keep the method Javadocs accurate.

- [ ] **Step 6: Rethrow interruptions in the indexer**

In `RepositoryIndexer.java`, add:

```java
    /**
     * An interrupt surfacing through git, Maven or the database is not this repository's failure: it is rethrown, so
     * the executor leaves the repository to recovery (INTERRUPTED, previous index kept) instead of recording it FAILED.
     */
    static void rethrowIfInterrupted(RuntimeException e) {
        if (IndexRunExecutor.isInterruption(e, Thread.currentThread().isInterrupted())) {
            throw e;
        }
    }
```

Then make `rethrowIfInterrupted(e);` the first statement of each of the three `catch (RuntimeException e)` blocks in `run(...)`.

- [ ] **Step 7: Run the tests**

Run: `./mvnw test -Dtest='SchemaMigrationTest,IndexLockTest,IndexRunRecorderTest,RepositoryIndexerInterruptionTest,IndexRunExecutorTest,IndexRunExecutorInterruptionTest,IndexRunServiceTest,AppSettingsTest'`
Expected: all pass.

Run: `./mvnw test`
Expected: all tests pass.

- [ ] **Step 8: Commit**

```bash
git add src/main src/test
git commit -m "fix(indexing): fence lock takeovers, label taken-over runs and leave interrupted repositories to recovery" -m "<your harness Co-Authored-By trailer>"
```

---

### Task 2: Shared secret rule and the settings admin API

**Files:**
- Create: `src/main/java/com/graphify/common/secret/SecretUpdate.java`, `src/main/java/com/graphify/settings/SettingsAdminController.java`
- Modify: `src/main/java/com/graphify/auth/LdapAdministration.java`, `src/main/java/com/graphify/common/exception/ApiExceptionHandler.java`, `README.md`
- Test: `src/test/java/com/graphify/common/secret/SecretUpdateTest.java`, `src/test/java/com/graphify/settings/SettingsAdminApiTest.java`

**Interfaces:**
- **Consumes:**
  - `AppSettings.all()` and `update(key, raw, actor)`; the `Setting` record (key, value, type, description, minValue, maxValue, updatedBy, updatedAt).
  - `SettingNotFoundException` and `InvalidSettingValueException` (key, reason).
  - `LdapAdministration.merge`.
- **Produces:**
  - `public final class SecretUpdate` with `static String resolve(String submitted, String stored, boolean sameTarget, int maxBytes, String secretName, String targetName)`:
    - `submitted == null`: return `stored`. When `stored != null && !sameTarget`, throw `InvalidRequestException("Re-enter the " + secretName + " when changing " + targetName)` instead.
    - `""`: return null.
    - otherwise: 400 `"The " + secretName + " is longer than " + maxBytes + " bytes"` when too long; else `submitted`.
  - `LdapAdministration` uses `SecretUpdate.resolve(update.bindPassword(), current.bindPassword(), sameUrlAndBindDn, PASSWORD_BYTES, "bind password", "url or bindDn")`. Behaviour and the existing LDAP tests are unchanged.
  - `ApiExceptionHandler` maps `SettingNotFoundException` to 404 (title "Not found") and `InvalidSettingValueException` to 400 (title "Invalid request", detail is the exception message).
  - `SettingsAdminController` under `/api/v1/admin/settings`:
    - `GET` returns `List<Setting>` ordered by key.
    - `PUT /{key}` takes `{value}` and returns the updated `Setting`. A missing body or value is 400.
    - The actor is the signed-in user, and auditing is already done by `AppSettings.update`.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/graphify/common/secret/SecretUpdateTest.java`:

```java
package com.graphify.common.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.graphify.common.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;

class SecretUpdateTest {

    private static String resolve(String submitted, String stored, boolean sameTarget) {
        return SecretUpdate.resolve(submitted, stored, sameTarget, 10, "token", "the URL");
    }

    @Test
    void nullKeepsTheStoredSecretOnlyForTheSameTarget() {
        assertThat(resolve(null, "s3cret", true)).isEqualTo("s3cret");
        assertThat(resolve(null, null, false)).isNull();
        assertThatThrownBy(() -> resolve(null, "s3cret", false)).isInstanceOf(InvalidRequestException.class)
                .hasMessage("Re-enter the token when changing the URL").hasMessageNotContaining("s3cret");
    }

    @Test
    void emptyClearsAndANewValueReplacesWithinTheLimit() {
        assertThat(resolve("", "s3cret", false)).isNull();
        assertThat(resolve("fresh", "s3cret", false)).isEqualTo("fresh");
        assertThatThrownBy(() -> resolve("x".repeat(11), null, true)).isInstanceOf(InvalidRequestException.class)
                .hasMessage("The token is longer than 10 bytes");
    }
}
```

`src/test/java/com/graphify/settings/SettingsAdminApiTest.java`:

```java
package com.graphify.settings;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.auth.Role;
import com.graphify.testsupport.SettingsOverride;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class SettingsAdminApiTest extends OracleIntegrationTest {

    @Autowired
    AppSettings settings;

    private SettingsOverride overrides;

    @BeforeEach
    void setUp() {
        overrides = new SettingsOverride(settings);
        overrides.set(SettingKeys.API_PAGE_DEFAULT_SIZE, settings.all().stream()
                .filter(s -> s.key().equals(SettingKeys.API_PAGE_DEFAULT_SIZE)).findFirst().orElseThrow().value());
    }

    @AfterEach
    void tearDown() {
        overrides.restore();
    }

    @Test
    void listsEverySettingWithItsTypeAndBounds() {
        assertThat(mvc.get().uri("/api/v1/admin/settings")).hasStatusOk().bodyJson().satisfies(json -> {
            assertThat(json).extractingPath("$[?(@.key == 'index.parallelism')].type").asArray().containsExactly("INT");
            assertThat(json).extractingPath("$[?(@.key == 'index.parallelism')].minValue").asArray().containsExactly(1);
        });
    }

    @Test
    void updatesAValueAndRecordsWhoChangedIt() {
        assertThat(mvc.put().uri("/api/v1/admin/settings/api.page_default_size").contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"40\"}")).hasStatusOk().bodyJson().satisfies(json -> {
                    assertThat(json).extractingPath("$.value").isEqualTo("40");
                    assertThat(json).extractingPath("$.updatedBy").isEqualTo(TEST_ACTOR);
                });
        assertThat(settings.getInt(SettingKeys.API_PAGE_DEFAULT_SIZE)).isEqualTo(40);
    }

    @Test
    void rejectsBadValuesUnknownKeysAndNonAdmins() {
        assertThat(mvc.put().uri("/api/v1/admin/settings/index.parallelism").contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"0\"}")).hasStatus(400).bodyJson().extractingPath("$.detail").asString()
                .contains("index.parallelism");
        assertThat(mvc.put().uri("/api/v1/admin/settings/index.cron").contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"not a cron\"}")).hasStatus(400);
        assertThat(mvc.put().uri("/api/v1/admin/settings/no.such.key").contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"1\"}")).hasStatus(404);
        assertThat(mvc.put().uri("/api/v1/admin/settings/index.parallelism").contentType(MediaType.APPLICATION_JSON)
                .content("{}")).hasStatus(400);
        assertThat(as("viewer", Role.USER).get().uri("/api/v1/admin/settings")).hasStatus(403);
    }
}
```

The cron-reschedule effect of a change is covered by plan 5's `IndexSchedulerTest`. This test proves the API goes through `AppSettings.update`, which publishes that event.

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -Dtest='SecretUpdateTest,SettingsAdminApiTest'`
Expected: compile failure for `SecretUpdate`, then 404s for `/api/v1/admin/settings`.

- [ ] **Step 3: Implement**

`src/main/java/com/graphify/common/secret/SecretUpdate.java`:

```java
package com.graphify.common.secret;

import com.graphify.common.exception.InvalidRequestException;
import com.graphify.common.util.Utf8;

/**
 * The secret field of a full-document update (spec §6.4): null keeps the stored secret, but only for the same target,
 * so a stored credential is never sent to a server or account it was not set for; "" clears it.
 */
public final class SecretUpdate {

    private SecretUpdate() {
    }

    public static String resolve(String submitted, String stored, boolean sameTarget, int maxBytes, String secretName,
            String targetName) {
        if (submitted == null) {
            if (stored != null && !sameTarget) {
                throw new InvalidRequestException("Re-enter the " + secretName + " when changing " + targetName);
            }
            return stored;
        }
        if (submitted.isEmpty()) {
            return null;
        }
        if (Utf8.byteLength(submitted) > maxBytes) {
            throw new InvalidRequestException("The " + secretName + " is longer than " + maxBytes + " bytes");
        }
        return submitted;
    }
}
```

In `LdapAdministration.merge`, replace the inline password block (`String password = update.bindPassword(); if (password == null) { … } else if (…) { … }`) with:

```java
        String password = SecretUpdate.resolve(update.bindPassword(), current.bindPassword(),
                Objects.equals(url, current.url()) && Objects.equals(bindDn, current.bindDn()), PASSWORD_BYTES,
                "bind password", "url or bindDn");
```

If an existing LDAP test asserts the exact old length message ("bindPassword is longer than…"), update that one assertion to the new wording and list it in your report.

Add to `ApiExceptionHandler.java`:

```java
    @ExceptionHandler(SettingNotFoundException.class)
    ProblemDetail settingNotFound(SettingNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Not found");
        return problem;
    }

    @ExceptionHandler(InvalidSettingValueException.class)
    ProblemDetail invalidSetting(InvalidSettingValueException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid request");
        return problem;
    }
```

If either exception's `getMessage()` does not name the key, build the detail from its accessors (`key()`, `reason()`), so the detail reads "`<key>`: `<reason>`".

`src/main/java/com/graphify/settings/SettingsAdminController.java`:

```java
package com.graphify.settings;

import com.graphify.common.exception.InvalidRequestException;
import java.util.Comparator;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Settings for admins (spec §10.6 "Ayarlar"); validation and audit live in AppSettings. */
@RestController
@RequestMapping("/api/v1/admin/settings")
public class SettingsAdminController {

    public record ValueChange(String value) {
    }

    private final AppSettings settings;

    public SettingsAdminController(AppSettings settings) {
        this.settings = settings;
    }

    @GetMapping
    public List<Setting> list() {
        return settings.all().stream().sorted(Comparator.comparing(Setting::key)).toList();
    }

    @PutMapping("/{key}")
    public Setting update(@PathVariable String key, @RequestBody(required = false) ValueChange change,
            Authentication authentication) {
        if (change == null || change.value() == null) {
            throw new InvalidRequestException("A body {value} is required");
        }
        return settings.update(key, change.value(), authentication.getName());
    }
}
```

In `README.md`, add rows to the API table: `GET /admin/settings` and `PUT /admin/settings/{key}` (`{value}`; 400 with the reason, 404 for an unknown key; changes apply without a restart).

- [ ] **Step 4: Run the tests**

Run: `./mvnw test -Dtest='SecretUpdateTest,SettingsAdminApiTest,LdapAdminApiTest,AppSettingsTest'`
Expected: all pass.

Run: `./mvnw test`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main src/test README.md
git commit -m "feat(admin): manage settings through the API and share the stored-secret rule" -m "<your harness Co-Authored-By trailer>"
```

---

### Task 3: SCM connections admin API

**Files:**
- Create: `src/main/java/com/graphify/scm/ScmConnectionView.java`, `ScmConnectionUpdate.java`, `ScmConnectionAdministration.java`, `ScmConnectionAdminController.java`
- Modify: `src/main/java/com/graphify/scm/ScmClient.java`, `BitbucketDataCenterClient.java`, `ScmConnections.java`, `README.md`
- Test: `src/test/java/com/graphify/scm/ScmConnectionAdminApiTest.java`

**Interfaces:**
- **Consumes:**
  - `ScmConnections` (`find`, `split`, `recordSync`), `ScmConnection`, `ScmType`, `ScmClient`, `ScmException`, `ScmAuthenticationException`, `ConnectionSyncStatus` (SUCCESS, AUTH_FAILED, FAILED).
  - `SecretCipher`, `SecretUpdate` (Task 2), `AuditLog`, `UrlMasking`.
  - `ExternalSystemException`, `ConflictException`, `NotFoundException`, `InvalidRequestException`; `FakeBitbucket`, `StoreFixtures`.
- **Produces (types):**
  - `public record ScmConnectionView(long id, String name, ScmType type, String baseUrl, String username, boolean secretSet, List<String> includeProjects, List<String> excludeRepos, boolean enabled, String lastTestStatus, Instant lastTestAt, String lastSyncStatus, Instant lastSyncAt, String lastSyncError, int repositoryCount)`. `repositoryCount` counts every `scm_repository` row of the connection.
  - `public record ScmConnectionUpdate(String name, ScmType type, String baseUrl, String username, String secret, List<String> includeProjects, List<String> excludeRepos, Boolean enabled)`. Its `toString` omits the secret.
- **Produces (`ScmClient` and the Bitbucket client):**
  - `ScmClient` gains `void test(ScmConnection connection)`.
  - `BitbucketDataCenterClient.test` requests the repository listing once, with `start=0` and `limit=1` and no retries. It throws `ScmAuthenticationException` on 401/403 and `ScmException` otherwise.
  - The existing retry helper takes the retry count as a parameter. `listRepositories` keeps `scm.retry_count`; `test` passes 0.
- **Produces (`ScmConnections` additions):**
  - `List<ScmConnectionView> views()` (ordered by name) and `Optional<ScmConnectionView> view(long id)`.
  - `long insert(...)`, `void update(long id, ...)` and `void delete(long id)`.
  - `int repositoryCount(long id)` and `void recordTest(long id, ConnectionSyncStatus status)`. `recordTest` sets `last_test_status` and `last_test_at = SYSTIMESTAMP`.
- **Produces (`ScmConnectionAdministration`, a `@Service`):** `list()`, `get(id)`, `create(update, actor)`, `update(id, update, actor)`, `delete(id, actor)` and `test(id)`.
  - **Validation (400):**
    - `name` is required and at most 200 bytes.
    - `type` is required.
    - `baseUrl` is required, `http`/`https` only, at most 1000 bytes, and stored without a trailing `/`.
    - `username` is optional and at most 200 bytes.
    - Project and repository list items are stripped, non-blank and comma-free, and each joined list is at most 4000 bytes.
    - `enabled` is required.
  - **Secret:** `SecretUpdate.resolve(update.secret(), stored, sameBaseUrlAndUsername, 2000, "token", "baseUrl or username")`. A create uses `stored = null`.
  - **Uniqueness:** a duplicate name (unique constraint) is 409.
  - **Delete:** a connection that still has `scm_repository` rows is 409 "disable it instead".
  - **Test:**
    - Success records SUCCESS and returns `{ok: true}`.
    - `ScmAuthenticationException` records AUTH_FAILED and throws `ExternalSystemException` (502) with the masked message.
    - `ScmException` records FAILED and throws the same way.
  - **Audit:** `SCM_CONNECTION_CREATED`, `SCM_CONNECTION_UPDATED` (details: changed field names; the secret is named "secret" only) and `SCM_CONNECTION_DELETED`. The target is the connection name.
- **Produces (`ScmConnectionAdminController`, under `/api/v1/admin/scm-connections`):**
  - `GET /`, `POST /` (201 with `Location` and the view), `GET /{id}`, `PUT /{id}`, `DELETE /{id}` (204), `POST /{id}/test` (200 `{ok:true}` or 502).

- [ ] **Step 1: Write the failing test**

`src/test/java/com/graphify/scm/ScmConnectionAdminApiTest.java`:

```java
package com.graphify.scm;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.auth.Role;
import com.graphify.common.crypto.SecretCipher;
import com.graphify.store.StoreFixtures;
import com.graphify.testsupport.FakeBitbucket;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

class ScmConnectionAdminApiTest extends OracleIntegrationTest {

    private static final String TOKEN = "scm-token-7";

    @Autowired
    ScmConnections connections;

    @Autowired
    SecretCipher cipher;

    private FakeBitbucket bitbucket;

    @BeforeEach
    void setUp() throws Exception {
        StoreFixtures.cleanIndexTables(jdbc);
        jdbc.update("DELETE FROM audit_log WHERE action LIKE 'SCM_CONNECTION%'");
        bitbucket = new FakeBitbucket().start().requireAuthorization("Bearer " + TOKEN)
                .addRepository("SHOP", "api", "https://scm/a.git");
    }

    @AfterEach
    void tearDown() {
        bitbucket.close();
        StoreFixtures.cleanIndexTables(jdbc);
        jdbc.update("DELETE FROM audit_log WHERE action LIKE 'SCM_CONNECTION%'");
    }

    private String body(String name, String baseUrl, String secret) {
        return """
                {"name":"%s","type":"BITBUCKET_DC","baseUrl":"%s","username":null,%s
                 "includeProjects":[" SHOP ","PAY"],"excludeRepos":["SHOP/old-*"],"enabled":true}
                """.formatted(name, baseUrl, secret == null ? "" : "\"secret\":\"" + secret + "\",");
    }

    private long create(String name, String secret) throws Exception {
        MvcTestResult created = mvc.post().uri("/api/v1/admin/scm-connections").contentType(MediaType.APPLICATION_JSON)
                .content(body(name, bitbucket.baseUrl() + "/", secret)).exchange();
        assertThat(created).hasStatus(201);
        return ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();
    }

    @Test
    void createsShowsAndTestsAConnectionWithoutRevealingItsSecret() throws Exception {
        long id = create("corp", TOKEN);

        assertThat(mvc.get().uri("/api/v1/admin/scm-connections/" + id)).hasStatusOk().bodyJson().satisfies(json -> {
            assertThat(json).extractingPath("$.baseUrl").isEqualTo(bitbucket.baseUrl());
            assertThat(json).extractingPath("$.secretSet").isEqualTo(true);
            assertThat(json).extractingPath("$.includeProjects").asArray().containsExactly("SHOP", "PAY");
            assertThat(json).extractingPath("$.repositoryCount").isEqualTo(0);
        });
        assertThat(mvc.get().uri("/api/v1/admin/scm-connections")).hasStatusOk().bodyText().doesNotContain(TOKEN);
        assertThat(connections.find(id).orElseThrow().secret()).isEqualTo(TOKEN);

        assertThat(mvc.post().uri("/api/v1/admin/scm-connections/" + id + "/test")).hasStatusOk().bodyJson()
                .extractingPath("$.ok").isEqualTo(true);
        assertThat(bitbucket.requestedStarts()).containsExactly("0");
        assertThat(mvc.get().uri("/api/v1/admin/scm-connections/" + id)).hasStatusOk().bodyJson()
                .extractingPath("$.lastTestStatus").isEqualTo("SUCCESS");
        assertThat(jdbc.queryForList("SELECT NVL(details, '-') FROM audit_log WHERE action LIKE 'SCM_CONNECTION%'",
                String.class)).isNotEmpty().noneMatch(details -> details.contains(TOKEN));
    }

    @Test
    void aRejectedTokenIsRecordedAsAuthFailedAndAnswers502() throws Exception {
        long id = create("corp", "wrong-token");

        assertThat(mvc.post().uri("/api/v1/admin/scm-connections/" + id + "/test")).hasStatus(502).bodyText()
                .doesNotContain("wrong-token");
        assertThat(jdbc.queryForObject("SELECT last_test_status FROM scm_connection WHERE id = ?", String.class, id))
                .isEqualTo("AUTH_FAILED");
    }

    @Test
    void aChangedTargetNeedsTheSecretAgain() throws Exception {
        long id = create("corp", TOKEN);

        assertThat(mvc.put().uri("/api/v1/admin/scm-connections/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(body("corp", "https://elsewhere.example", null))).hasStatus(400);
        assertThat(connections.find(id).orElseThrow().baseUrl()).isEqualTo(bitbucket.baseUrl());

        assertThat(mvc.put().uri("/api/v1/admin/scm-connections/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(body("corp-renamed", bitbucket.baseUrl(), null))).hasStatusOk().bodyJson()
                .extractingPath("$.secretSet").isEqualTo(true);
        assertThat(connections.find(id).orElseThrow().secret()).isEqualTo(TOKEN);
    }

    @Test
    void aConnectionWithRepositoriesCannotBeDeleted() throws Exception {
        long id = create("corp", TOKEN);
        jdbc.update("INSERT INTO scm_repository (connection_id, project_key, slug, clone_url) VALUES (?, 'SHOP', 'api', "
                + "'https://scm/a.git')", id);

        assertThat(mvc.delete().uri("/api/v1/admin/scm-connections/" + id)).hasStatus(409);
        assertThat(connections.find(id)).isPresent();

        jdbc.update("DELETE FROM scm_repository WHERE connection_id = ?", id);
        assertThat(mvc.delete().uri("/api/v1/admin/scm-connections/" + id)).hasStatus(204);
        assertThat(connections.find(id)).isEmpty();
        assertThat(mvc.delete().uri("/api/v1/admin/scm-connections/" + id)).hasStatus(404);
    }

    @Test
    void validatesInputAndUniqueness() throws Exception {
        create("corp", TOKEN);

        assertThat(mvc.post().uri("/api/v1/admin/scm-connections").contentType(MediaType.APPLICATION_JSON)
                .content(body("corp", bitbucket.baseUrl(), TOKEN))).hasStatus(409);
        assertThat(mvc.post().uri("/api/v1/admin/scm-connections").contentType(MediaType.APPLICATION_JSON)
                .content(body("other", "ftp://scm", TOKEN))).hasStatus(400);
        assertThat(mvc.post().uri("/api/v1/admin/scm-connections").contentType(MediaType.APPLICATION_JSON)
                .content(body("", bitbucket.baseUrl(), TOKEN))).hasStatus(400);
        assertThat(mvc.post().uri("/api/v1/admin/scm-connections").contentType(MediaType.APPLICATION_JSON)
                .content(body("third", bitbucket.baseUrl(), TOKEN).replace("\"PAY\"", "\"A,B\""))).hasStatus(400);
        assertThat(as("viewer", Role.USER).get().uri("/api/v1/admin/scm-connections")).hasStatus(403);
    }
}
```

`StoreFixtures.cleanIndexTables` already deletes every `scm_connection` row.

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw test -Dtest=ScmConnectionAdminApiTest`
Expected: compile failure or 404s.

- [ ] **Step 3: Implement**

`src/main/java/com/graphify/scm/ScmConnectionView.java`:

```java
package com.graphify.scm;

import java.time.Instant;
import java.util.List;

/** An SCM connection as the admin API shows it: never the secret, only whether one is set (spec §6.4). */
public record ScmConnectionView(
        long id,
        String name,
        ScmType type,
        String baseUrl,
        String username,
        boolean secretSet,
        List<String> includeProjects,
        List<String> excludeRepos,
        boolean enabled,
        String lastTestStatus,
        Instant lastTestAt,
        String lastSyncStatus,
        Instant lastSyncAt,
        String lastSyncError,
        int repositoryCount) {
}
```

`src/main/java/com/graphify/scm/ScmConnectionUpdate.java`:

```java
package com.graphify.scm;

import java.util.List;

/** A full create/PUT document; {@code secret} null keeps the stored one for the same baseUrl and username, "" clears it. */
public record ScmConnectionUpdate(
        String name,
        ScmType type,
        String baseUrl,
        String username,
        String secret,
        List<String> includeProjects,
        List<String> excludeRepos,
        Boolean enabled) {

    @Override
    public String toString() {
        return "ScmConnectionUpdate[name=" + name + ", type=" + type + ", baseUrl=" + baseUrl + "]";
    }
}
```

Add to `ScmClient.java`:

```java
    /** One cheap authenticated call; throws ScmAuthenticationException when the credentials are rejected. */
    void test(ScmConnection connection);
```

In `BitbucketDataCenterClient.java`:
1. Change `withRetry(connection, call)` to `withRetry(connection, call, retries)`. Callers pass the retry count, and the method no longer reads `scm.retry_count` itself.
2. `listRepositories` passes `settings.getInt(SettingKeys.SCM_RETRY_COUNT)`.
3. Add:

```java
    /** Page size of the connection test: one repository is enough to prove the URL and credentials work. */
    private static final int TEST_PAGE_SIZE = 1;

    @Override
    public void test(ScmConnection connection) {
        try (HttpClient http = HttpClient.newBuilder()
                .connectTimeout(settings.getDuration(SettingKeys.SCM_CONNECT_TIMEOUT)).build()) {
            RestClient client = client(connection, http);
            withRetry(connection, () -> client.get().uri(REPOS_PATH, 0, TEST_PAGE_SIZE).retrieve().body(Page.class), 0);
        }
    }
```

Add to `ScmConnections.java` (imports: `java.time.OffsetDateTime`, `java.time.Instant`, `org.springframework.jdbc.support.GeneratedKeyHolder`, `java.sql.PreparedStatement`):

```java
    private static final String VIEW = """
            SELECT c.id, c.name, c.type, c.base_url, c.username, c.secret_enc, c.include_projects, c.exclude_repos,
                   c.enabled, c.last_test_status, c.last_test_at, c.last_sync_status, c.last_sync_at, c.last_sync_error,
                   (SELECT COUNT(*) FROM scm_repository r WHERE r.connection_id = c.id) AS repository_count
              FROM scm_connection c
            """;

    public List<ScmConnectionView> views() {
        return jdbc.query(VIEW + " ORDER BY c.name", (rs, row) -> view(rs));
    }

    public Optional<ScmConnectionView> view(long id) {
        return jdbc.query(VIEW + " WHERE c.id = ?", (rs, row) -> view(rs), id).stream().findFirst();
    }

    /** Stores a validated connection; {@code secret} is plaintext (encrypted here) or null. */
    public long insert(String name, ScmType type, String baseUrl, String username, String secret,
            List<String> includeProjects, List<String> excludeRepos, boolean enabled) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO scm_connection (name, type, base_url, username, secret_enc, include_projects,
                                                exclude_repos, enabled)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[] {"id"});
            statement.setString(1, name);
            statement.setString(2, type.name());
            statement.setString(3, baseUrl);
            statement.setString(4, username);
            statement.setString(5, secret == null ? null : cipher.encrypt(secret));
            statement.setString(6, join(includeProjects));
            statement.setString(7, join(excludeRepos));
            statement.setInt(8, enabled ? 1 : 0);
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    public void update(long id, String name, ScmType type, String baseUrl, String username, String secret,
            List<String> includeProjects, List<String> excludeRepos, boolean enabled) {
        jdbc.update("""
                UPDATE scm_connection SET name = ?, type = ?, base_url = ?, username = ?, secret_enc = ?,
                       include_projects = ?, exclude_repos = ?, enabled = ?
                 WHERE id = ?
                """, name, type.name(), baseUrl, username, secret == null ? null : cipher.encrypt(secret),
                join(includeProjects), join(excludeRepos), enabled ? 1 : 0, id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM scm_connection WHERE id = ?", id);
    }

    public int repositoryCount(long id) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM scm_repository WHERE connection_id = ?",
                Integer.class, id);
        return count == null ? 0 : count;
    }

    public void recordTest(long id, ConnectionSyncStatus status) {
        jdbc.update("UPDATE scm_connection SET last_test_status = ?, last_test_at = SYSTIMESTAMP WHERE id = ?",
                status.name(), id);
    }

    static String join(List<String> items) {
        return items == null || items.isEmpty() ? null : String.join(",", items);
    }

    private static ScmConnectionView view(ResultSet rs) throws SQLException {
        return new ScmConnectionView(rs.getLong("id"), rs.getString("name"), ScmType.valueOf(rs.getString("type")),
                rs.getString("base_url"), rs.getString("username"), rs.getString("secret_enc") != null,
                split(rs.getString("include_projects")), split(rs.getString("exclude_repos")),
                rs.getInt("enabled") == 1, rs.getString("last_test_status"), instant(rs, "last_test_at"),
                rs.getString("last_sync_status"), instant(rs, "last_sync_at"), rs.getString("last_sync_error"),
                rs.getInt("repository_count"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
```

`src/main/java/com/graphify/scm/ScmConnectionAdministration.java`:

```java
package com.graphify.scm;

import com.graphify.audit.AuditLog;
import com.graphify.common.exception.ConflictException;
import com.graphify.common.exception.ExternalSystemException;
import com.graphify.common.exception.InvalidRequestException;
import com.graphify.common.exception.NotFoundException;
import com.graphify.common.secret.SecretUpdate;
import com.graphify.common.util.Utf8;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** SCM connections for admins (spec §10.6 "SCM bağlantıları"); secrets are never returned or audited. */
@Service
public class ScmConnectionAdministration {

    /** Widths of the scm_connection columns in V1__core_schema.sql. */
    private static final int NAME_BYTES = 200;
    private static final int URL_BYTES = 1000;
    private static final int USERNAME_BYTES = 200;
    private static final int LIST_BYTES = 4000;

    /** Plaintext cap so the AES-GCM ciphertext fits scm_connection.secret_enc (VARCHAR2(4000 BYTE)). */
    private static final int SECRET_BYTES = 2000;

    /** Schemes an SCM REST base URL may use. */
    private static final List<String> SCHEMES = List.of("http", "https");

    private final ScmConnections connections;
    private final List<ScmClient> clients;
    private final AuditLog auditLog;

    public ScmConnectionAdministration(ScmConnections connections, List<ScmClient> clients, AuditLog auditLog) {
        this.connections = connections;
        this.clients = clients;
        this.auditLog = auditLog;
    }

    public List<ScmConnectionView> list() {
        return connections.views();
    }

    public ScmConnectionView get(long id) {
        return connections.view(id).orElseThrow(() -> new NotFoundException("No SCM connection with id " + id));
    }

    @Transactional
    public ScmConnectionView create(ScmConnectionUpdate update, String actor) {
        Valid valid = validate(update);
        String secret = SecretUpdate.resolve(update.secret(), null, true, SECRET_BYTES, "token", "baseUrl or username");
        long id;
        try {
            id = connections.insert(valid.name(), valid.type(), valid.baseUrl(), valid.username(), secret,
                    valid.includeProjects(), valid.excludeRepos(), valid.enabled());
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("An SCM connection named " + valid.name() + " already exists");
        }
        auditLog.record(actor, "SCM_CONNECTION_CREATED", valid.name(), valid.type().name());
        return get(id);
    }

    @Transactional
    public ScmConnectionView update(long id, ScmConnectionUpdate update, String actor) {
        ScmConnection current = connections.find(id)
                .orElseThrow(() -> new NotFoundException("No SCM connection with id " + id));
        ScmConnectionView before = get(id);
        Valid valid = validate(update);
        String secret = SecretUpdate.resolve(update.secret(), current.secret(),
                Objects.equals(valid.baseUrl(), current.baseUrl()) && Objects.equals(valid.username(), current.username()),
                SECRET_BYTES, "token", "baseUrl or username");
        try {
            connections.update(id, valid.name(), valid.type(), valid.baseUrl(), valid.username(), secret,
                    valid.includeProjects(), valid.excludeRepos(), valid.enabled());
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("An SCM connection named " + valid.name() + " already exists");
        }
        auditLog.record(actor, "SCM_CONNECTION_UPDATED", valid.name(),
                "changed: " + changed(before, valid, !Objects.equals(secret, current.secret())));
        return get(id);
    }

    @Transactional
    public void delete(long id, String actor) {
        ScmConnectionView current = get(id);
        if (connections.repositoryCount(id) > 0) {
            throw new ConflictException("SCM connection " + current.name()
                    + " still has repositories and run history; disable it instead");
        }
        connections.delete(id);
        auditLog.record(actor, "SCM_CONNECTION_DELETED", current.name(), null);
    }

    /** Not transactional: the test result is stored even when the test fails. */
    public void test(long id) {
        ScmConnection connection = connections.find(id)
                .orElseThrow(() -> new NotFoundException("No SCM connection with id " + id));
        ScmClient client = clients.stream().filter(c -> c.type() == connection.type()).findFirst()
                .orElseThrow(() -> new IllegalStateException("No SCM client for " + connection.type()));
        try {
            client.test(connection);
            connections.recordTest(id, ConnectionSyncStatus.SUCCESS);
        } catch (ScmAuthenticationException e) {
            connections.recordTest(id, ConnectionSyncStatus.AUTH_FAILED);
            throw new ExternalSystemException(UrlMasking.mask(e.getMessage()));
        } catch (ScmException e) {
            connections.recordTest(id, ConnectionSyncStatus.FAILED);
            throw new ExternalSystemException(UrlMasking.mask(e.getMessage()));
        }
    }

    private record Valid(String name, ScmType type, String baseUrl, String username, List<String> includeProjects,
            List<String> excludeRepos, boolean enabled) {
    }

    private static Valid validate(ScmConnectionUpdate update) {
        if (update == null) {
            throw new InvalidRequestException("A connection document is required");
        }
        String name = required("name", update.name(), NAME_BYTES);
        if (update.type() == null) {
            throw new InvalidRequestException("type is required");
        }
        String baseUrl = required("baseUrl", update.baseUrl(), URL_BYTES);
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        requireScheme(baseUrl);
        String username = update.username() == null || update.username().isBlank() ? null : update.username().strip();
        if (username != null && Utf8.byteLength(username) > USERNAME_BYTES) {
            throw new InvalidRequestException("username is longer than " + USERNAME_BYTES + " bytes");
        }
        if (update.enabled() == null) {
            throw new InvalidRequestException("enabled is required");
        }
        return new Valid(name, update.type(), baseUrl, username, items("includeProjects", update.includeProjects()),
                items("excludeRepos", update.excludeRepos()), update.enabled());
    }

    private static String required(String field, String value, int maxBytes) {
        if (value == null || value.isBlank()) {
            throw new InvalidRequestException(field + " is required");
        }
        String stripped = value.strip();
        if (Utf8.byteLength(stripped) > maxBytes) {
            throw new InvalidRequestException(field + " is longer than " + maxBytes + " bytes");
        }
        return stripped;
    }

    private static void requireScheme(String url) {
        try {
            String scheme = new URI(url).getScheme();
            if (scheme == null || !SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
                throw new InvalidRequestException("baseUrl must start with http:// or https://");
            }
        } catch (URISyntaxException e) {
            throw new InvalidRequestException("baseUrl is not a valid URI");
        }
    }

    private static List<String> items(String field, List<String> values) {
        List<String> items = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                if (value == null || value.isBlank() || value.contains(",")) {
                    throw new InvalidRequestException(field + " items must be non-blank and contain no comma");
                }
                items.add(value.strip());
            }
        }
        if (Utf8.byteLength(String.join(",", items)) > LIST_BYTES) {
            throw new InvalidRequestException(field + " is longer than " + LIST_BYTES + " bytes");
        }
        return List.copyOf(items);
    }

    private static String changed(ScmConnectionView before, Valid after, boolean secretChanged) {
        List<String> fields = new ArrayList<>();
        if (!before.name().equals(after.name())) {
            fields.add("name");
        }
        if (before.type() != after.type()) {
            fields.add("type");
        }
        if (!before.baseUrl().equals(after.baseUrl())) {
            fields.add("baseUrl");
        }
        if (!Objects.equals(before.username(), after.username())) {
            fields.add("username");
        }
        if (secretChanged) {
            fields.add("secret");
        }
        if (!before.includeProjects().equals(after.includeProjects())) {
            fields.add("includeProjects");
        }
        if (!before.excludeRepos().equals(after.excludeRepos())) {
            fields.add("excludeRepos");
        }
        if (before.enabled() != after.enabled()) {
            fields.add("enabled");
        }
        return fields.isEmpty() ? "nothing" : String.join(", ", fields);
    }
}
```

`src/main/java/com/graphify/scm/ScmConnectionAdminController.java`:

```java
package com.graphify.scm;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** SCM connections for admins (spec §10.6 "SCM bağlantıları"). */
@RestController
@RequestMapping("/api/v1/admin/scm-connections")
public class ScmConnectionAdminController {

    private final ScmConnectionAdministration administration;

    public ScmConnectionAdminController(ScmConnectionAdministration administration) {
        this.administration = administration;
    }

    @GetMapping
    public List<ScmConnectionView> list() {
        return administration.list();
    }

    @PostMapping
    public ResponseEntity<ScmConnectionView> create(@RequestBody(required = false) ScmConnectionUpdate update,
            Authentication authentication) {
        ScmConnectionView created = administration.create(update, authentication.getName());
        return ResponseEntity.created(URI.create("/api/v1/admin/scm-connections/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    public ScmConnectionView get(@PathVariable long id) {
        return administration.get(id);
    }

    @PutMapping("/{id}")
    public ScmConnectionView update(@PathVariable long id, @RequestBody(required = false) ScmConnectionUpdate update,
            Authentication authentication) {
        return administration.update(id, update, authentication.getName());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id, Authentication authentication) {
        administration.delete(id, authentication.getName());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test")
    public Map<String, Boolean> test(@PathVariable long id) {
        administration.test(id);
        return Map.of("ok", true);
    }
}
```

In `README.md`, add the `/admin/scm-connections` rows to the API table:
- `GET`/`POST`
- `GET`/`PUT`/`DELETE /{id}`. Note "409 while the connection has repositories — disable it instead".
- `POST /{id}/test`

Also add one note on secrets: `secretSet` only; a null secret keeps the stored one only for the same `baseUrl` and `username`; `""` clears it.

- [ ] **Step 4: Run the tests**

Run: `./mvnw test -Dtest='ScmConnectionAdminApiTest,BitbucketDataCenterClientTest,RepositorySyncTest,ScmConnectionsTest'`
Expected: all pass.

Run: `./mvnw test`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main src/test README.md
git commit -m "feat(admin): manage and test SCM connections through the API" -m "<your harness Co-Authored-By trailer>"
```

---

### Task 4: Artifact repositories admin API

**Files:**
- Create: `src/main/java/com/graphify/maven/ArtifactRepositoryView.java`, `ArtifactRepositoryUpdate.java`, `ArtifactRepositoryProbe.java`, `ArtifactRepositoryAdministration.java`, `ArtifactRepositoryAdminController.java`
- Modify: `src/main/java/com/graphify/maven/ArtifactRepositories.java`, `README.md`
- Test: `src/test/java/com/graphify/maven/ArtifactRepositoryAdminApiTest.java`

**Interfaces:**
- **Consumes:** `ArtifactRepositories.enabled()`, `ArtifactRepository` (id, name, url, username, secret, mirrorOf), `SecretCipher`, `SecretUpdate`, `AuditLog`, `AuthorizationHeader.of(username, secret)`, `UrlMasking`, `AppSettings` (`ARTIFACT_TEST_TIMEOUT`, Task 1), the exceptions.
- **Produces (types):**
  - `public record ArtifactRepositoryView(long id, String name, String url, String username, boolean secretSet, String mirrorOf, int sortOrder, boolean enabled, String lastTestStatus, Instant lastTestAt)`.
  - `public record ArtifactRepositoryUpdate(String name, String url, String username, String secret, String mirrorOf, Integer sortOrder, Boolean enabled)`. Its `toString` omits the secret.
- **Produces (`ArtifactRepositories` additions):** `views()` (ordered by sort order then id), `view(id)`, `find(id)` (an `Optional<ArtifactRepository>` with the decrypted secret), `insert(...)`, `update(...)`, `delete(id)` and `recordTest(id, String status)`.
- **Produces (`ArtifactRepositoryProbe`, a `@Component`):** `void test(ArtifactRepository repository)`.
  - **`file:` URL:** the directory must exist, otherwise `ExternalSystemException` with "not a readable directory".
  - **`http(s)` URL:** one GET with timeout `artifact.test_timeout` and the header from `AuthorizationHeader.of(username, secret)`.
    - 401/403 throws `ArtifactAuthenticationException` (a nested static class, so the caller can record AUTH_FAILED).
    - 5xx or an I/O failure throws `ExternalSystemException`.
    - Any other status means the server answered, and the test passes.
  - All messages are masked and secret-free.
- **Produces (`ArtifactRepositoryAdministration`, a `@Service`):** list, get, create, update, delete, test.
  - **Validation (400):**
    - `name` is required and at most 200 bytes.
    - `url` is required, `http`/`https`/`file`, and at most 1000 bytes.
    - `username` is optional and at most 200 bytes.
    - `mirrorOf` is optional and at most 200 bytes.
    - `sortOrder` is required and `>= 0`.
    - `enabled` is required.
  - **Secret:** `SecretUpdate.resolve(update.secret(), stored, sameUrlAndUsername, 2000, "password", "url or username")`.
  - **Uniqueness:** a duplicate name is 409.
  - **Test:** records `SUCCESS`, `AUTH_FAILED` or `FAILED` in `last_test_status`/`last_test_at` and answers 502 on failure.
  - **Audit:** `ARTIFACT_REPOSITORY_CREATED`, `ARTIFACT_REPOSITORY_UPDATED` (changed field names) and `ARTIFACT_REPOSITORY_DELETED`.
- **Produces (`ArtifactRepositoryAdminController`, under `/api/v1/admin/artifact-repositories`):** the same shape as the SCM controller.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/graphify/maven/ArtifactRepositoryAdminApiTest.java`:

```java
package com.graphify.maven;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.auth.Role;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

class ArtifactRepositoryAdminApiTest extends OracleIntegrationTest {

    private static final String PASSWORD = "nexus-pw-5";

    @Autowired
    ArtifactRepositories repositories;

    @TempDir
    Path dir;

    private HttpServer nexus;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM artifact_repository");
        jdbc.update("DELETE FROM audit_log WHERE action LIKE 'ARTIFACT_REPOSITORY%'");
        String expected = "Basic " + Base64.getEncoder().encodeToString(("ci:" + PASSWORD)
                .getBytes(StandardCharsets.UTF_8));
        nexus = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        nexus.createContext("/repository/maven-public/", exchange -> {
            int status = expected.equals(exchange.getRequestHeaders().getFirst("Authorization")) ? 200 : 401;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        nexus.start();
    }

    @AfterEach
    void tearDown() {
        nexus.stop(0);
        jdbc.update("DELETE FROM artifact_repository");
        jdbc.update("DELETE FROM audit_log WHERE action LIKE 'ARTIFACT_REPOSITORY%'");
    }

    private String url() {
        return "http://127.0.0.1:" + nexus.getAddress().getPort() + "/repository/maven-public/";
    }

    private String body(String name, String url, String password) {
        return """
                {"name":"%s","url":"%s","username":"ci",%s"mirrorOf":"*","sortOrder":0,"enabled":true}
                """.formatted(name, url, password == null ? "" : "\"secret\":\"" + password + "\",");
    }

    private long create(String name, String url, String password) throws Exception {
        MvcTestResult created = mvc.post().uri("/api/v1/admin/artifact-repositories")
                .contentType(MediaType.APPLICATION_JSON).content(body(name, url, password)).exchange();
        assertThat(created).hasStatus(201);
        return ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();
    }

    @Test
    void createsTestsAndListsARepositoryWithoutItsSecret() throws Exception {
        long id = create("nexus", url(), PASSWORD);

        assertThat(mvc.post().uri("/api/v1/admin/artifact-repositories/" + id + "/test")).hasStatusOk();
        assertThat(mvc.get().uri("/api/v1/admin/artifact-repositories")).hasStatusOk().bodyJson().satisfies(json -> {
            assertThat(json).extractingPath("$[0].secretSet").isEqualTo(true);
            assertThat(json).extractingPath("$[0].lastTestStatus").isEqualTo("SUCCESS");
        });
        assertThat(mvc.get().uri("/api/v1/admin/artifact-repositories/" + id)).hasStatusOk().bodyText()
                .doesNotContain(PASSWORD);
        assertThat(repositories.enabled()).extracting(ArtifactRepository::secret).containsExactly(PASSWORD);
        assertThat(jdbc.queryForList("SELECT NVL(details, '-') FROM audit_log WHERE action LIKE 'ARTIFACT_REPOSITORY%'",
                String.class)).isNotEmpty().noneMatch(details -> details.contains(PASSWORD));
    }

    @Test
    void aRejectedPasswordIsAuthFailedAndAFileRepositoryMustExist() throws Exception {
        long wrong = create("nexus", url(), "wrong-pw");
        long folder = create("local", dir.toUri().toString(), null);
        long missing = create("missing", dir.resolve("nope").toUri().toString(), null);

        assertThat(mvc.post().uri("/api/v1/admin/artifact-repositories/" + wrong + "/test")).hasStatus(502)
                .bodyText().doesNotContain("wrong-pw");
        assertThat(jdbc.queryForObject("SELECT last_test_status FROM artifact_repository WHERE id = ?", String.class,
                wrong)).isEqualTo("AUTH_FAILED");
        assertThat(mvc.post().uri("/api/v1/admin/artifact-repositories/" + folder + "/test")).hasStatusOk();
        assertThat(mvc.post().uri("/api/v1/admin/artifact-repositories/" + missing + "/test")).hasStatus(502);
    }

    @Test
    void aChangedTargetNeedsTheSecretAgain() throws Exception {
        long id = create("nexus", url(), PASSWORD);

        assertThat(mvc.put().uri("/api/v1/admin/artifact-repositories/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(body("nexus", "https://elsewhere.example/repo/", null))).hasStatus(400);
        assertThat(mvc.put().uri("/api/v1/admin/artifact-repositories/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(body("nexus-2", url(), null))).hasStatusOk();
        assertThat(repositories.enabled()).extracting(ArtifactRepository::secret).containsExactly(PASSWORD);
    }

    @Test
    void validatesDeletesAndGuardsAccess() throws Exception {
        long id = create("nexus", url(), PASSWORD);

        assertThat(mvc.post().uri("/api/v1/admin/artifact-repositories").contentType(MediaType.APPLICATION_JSON)
                .content(body("nexus", url(), PASSWORD))).hasStatus(409);
        assertThat(mvc.post().uri("/api/v1/admin/artifact-repositories").contentType(MediaType.APPLICATION_JSON)
                .content(body("other", "ftp://repo", null))).hasStatus(400);
        assertThat(mvc.post().uri("/api/v1/admin/artifact-repositories").contentType(MediaType.APPLICATION_JSON)
                .content(body("neg", url(), null).replace("\"sortOrder\":0", "\"sortOrder\":-1"))).hasStatus(400);
        assertThat(as("viewer", Role.USER).get().uri("/api/v1/admin/artifact-repositories")).hasStatus(403);

        assertThat(mvc.delete().uri("/api/v1/admin/artifact-repositories/" + id)).hasStatus(204);
        assertThat(mvc.get().uri("/api/v1/admin/artifact-repositories/" + id)).hasStatus(404);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw test -Dtest=ArtifactRepositoryAdminApiTest`
Expected: compile failure or 404s.

- [ ] **Step 3: Implement**

Write these five classes following the SCM classes from Task 3 one to one, with these differences:
- **`ArtifactRepositoryView`, `ArtifactRepositoryUpdate`:** the fields are listed in this task's Interfaces block. `toString` hides the secret.
- **`ArtifactRepositories` additions:**
  - SQL against `artifact_repository` (id, name, url, username, secret_enc, mirror_of, sort_order, enabled, last_test_status, last_test_at).
  - `find(id)` returns `ArtifactRepository` with the decrypted secret.
  - `recordTest(id, status)` sets `last_test_status`/`last_test_at`.
- **`ArtifactRepositoryAdministration`:**
  - Allowed schemes are `List.of("http", "https", "file")`, as a named constant with a comment.
  - `sortOrder` is required and `>= 0`.
  - The secret name is "password" and the target is "url or username".
  - Delete has no repository check (nothing references artifact repositories).
  - Test catches `ArtifactRepositoryProbe.ArtifactAuthenticationException` and records AUTH_FAILED, catches `ExternalSystemException` and records FAILED, records SUCCESS otherwise, and rethrows failures as `ExternalSystemException`.
- **`ArtifactRepositoryAdminController`:** `/api/v1/admin/artifact-repositories` with the same endpoints as the SCM controller.

`src/main/java/com/graphify/maven/ArtifactRepositoryProbe.java`:

```java
package com.graphify.maven;

import com.graphify.common.exception.ExternalSystemException;
import com.graphify.scm.AuthorizationHeader;
import com.graphify.scm.UrlMasking;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Checks that a Maven repository answers with the stored credentials: one GET for http(s), a directory check for
 * file: URLs. Any answer below 500 other than 401/403 means the server and path are reachable.
 */
@Component
public class ArtifactRepositoryProbe {

    /** The credentials were rejected (HTTP 401/403). */
    public static class ArtifactAuthenticationException extends RuntimeException {

        ArtifactAuthenticationException(String message) {
            super(message);
        }
    }

    private final AppSettings settings;

    public ArtifactRepositoryProbe(AppSettings settings) {
        this.settings = settings;
    }

    public void test(ArtifactRepository repository) {
        URI uri = URI.create(repository.url());
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            if (!Files.isDirectory(Path.of(uri))) {
                throw new ExternalSystemException("Repository " + repository.name() + " is not a readable directory");
            }
            return;
        }
        Duration timeout = settings.getDuration(SettingKeys.ARTIFACT_TEST_TIMEOUT);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout).GET();
        AuthorizationHeader.of(repository.username(), repository.secret())
                .ifPresent(header -> request.header("Authorization", header));
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL).build()) {
            int status = http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status == 401 || status == 403) {
                throw new ArtifactAuthenticationException("Repository " + repository.name()
                        + " rejected the credentials (HTTP " + status + ")");
            }
            if (status >= 500) {
                throw new ExternalSystemException("Repository " + repository.name() + " answered HTTP " + status);
            }
        } catch (IOException e) {
            throw new ExternalSystemException("Repository " + repository.name() + " could not be reached: "
                    + UrlMasking.mask(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExternalSystemException("Repository test of " + repository.name() + " was interrupted");
        }
    }
}
```

`AuthorizationHeader.of("ci", password)` gives `Basic base64(ci:password)`, which is what Maven sends for a server with a username and password.

In `README.md`, add the `/admin/artifact-repositories` rows: `GET`/`POST`, `GET`/`PUT`/`DELETE /{id}`, `POST /{id}/test`. Add a note that `file:`, `http:` and `https:` URLs are accepted and that `mirrorOf` turns the repository into a mirror.

- [ ] **Step 4: Run the tests**

Run: `./mvnw test -Dtest='ArtifactRepositoryAdminApiTest,SettingsXmlWriterTest,ClasspathResolverTest'`
Expected: all pass.

Run: `./mvnw test`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main src/test README.md
git commit -m "feat(admin): manage and test Maven artifact repositories through the API" -m "<your harness Co-Authored-By trailer>"
```

---

### Task 5: Entry-point annotations and impact rules admin API

**Files:**
- Create: `src/main/java/com/graphify/impact/EntryPointAnnotation.java`, `ImpactRuleAdministration.java`, `ImpactRulesAdminController.java`
- Modify: `README.md`
- Test: `src/test/java/com/graphify/impact/ImpactRulesAdminApiTest.java`

**Interfaces:**
- **Consumes:** tables `entry_point_annotation` (id, annotation_fqn UNIQUE, label, enabled) and `impact_relation_rule` (usage_kind PK, propagates, shown_at_level1); `ImpactRule(UsageKind kind, boolean propagates, boolean shownAtLevel1)`; `ImpactRules` (read on every analysis, so changes apply immediately); `ImpactService`; `ShopFixture`; `AuditLog`.
- **Produces (types):**
  - `public record EntryPointAnnotation(long id, String annotationFqn, String label, boolean enabled)`.
  - `public record AnnotationChange(String annotationFqn, String label, Boolean enabled)`, nested in the controller or the service.
  - `public record RuleChange(Boolean propagates, Boolean shownAtLevel1)`.
- **Produces (`ImpactRuleAdministration`, a `@Service`):**
  - `List<EntryPointAnnotation> annotations()`, ordered by FQN.
  - `create(change, actor)` and `update(id, change, actor)`:
    - `annotationFqn` is required, at most 500 bytes, and must match a dotted Java name (`[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*`).
    - `label` is required and at most 200 bytes.
    - `enabled` is required.
    - A duplicate FQN is 409 and an unknown id is 404.
  - `delete(id, actor)`.
  - `List<ImpactRule> rules()`, ordered by kind.
  - `ImpactRule updateRule(UsageKind kind, RuleChange change, actor)`: both flags are required, and an unknown kind in the path is 400 through Spring's type conversion.
  - Audit actions: `ENTRY_POINT_ANNOTATION_CREATED`, `_UPDATED`, `_DELETED` (target = FQN) and `IMPACT_RULE_UPDATED` (target = kind, details = `propagates=…, shownAtLevel1=…`).
- **Produces (`ImpactRulesAdminController`, under `/api/v1/admin`):**
  - `GET/POST /entry-point-annotations` (POST answers 201 with `Location`).
  - `PUT/DELETE /entry-point-annotations/{id}`.
  - `GET /impact-rules` and `PUT /impact-rules/{kind}`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/graphify/impact/ImpactRulesAdminApiTest.java`:

```java
package com.graphify.impact;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.auth.Role;
import com.graphify.store.RepositoryIndexWriter;
import com.graphify.testsupport.ShopFixture;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class ImpactRulesAdminApiTest extends OracleIntegrationTest {

    @Autowired
    RepositoryIndexWriter writer;

    @Autowired
    ImpactService impact;

    @TempDir
    Path work;

    private List<java.util.Map<String, Object>> savedRules;
    private List<java.util.Map<String, Object>> savedAnnotations;

    @BeforeEach
    void setUp() throws Exception {
        ShopFixture.load(jdbc, writer, work);
        savedRules = jdbc.queryForList("SELECT usage_kind, propagates, shown_at_level1 FROM impact_relation_rule");
        savedAnnotations = jdbc.queryForList("SELECT annotation_fqn, label, enabled FROM entry_point_annotation");
    }

    @AfterEach
    void restore() {
        savedRules.forEach(row -> jdbc.update("UPDATE impact_relation_rule SET propagates = ?, shown_at_level1 = ? "
                + "WHERE usage_kind = ?", row.get("PROPAGATES"), row.get("SHOWN_AT_LEVEL1"), row.get("USAGE_KIND")));
        jdbc.update("DELETE FROM entry_point_annotation");
        savedAnnotations.forEach(row -> jdbc.update("INSERT INTO entry_point_annotation (annotation_fqn, label, enabled) "
                + "VALUES (?, ?, ?)", row.get("ANNOTATION_FQN"), row.get("LABEL"), row.get("ENABLED")));
        jdbc.update("DELETE FROM audit_log WHERE action LIKE 'ENTRY_POINT_ANNOTATION%' OR action = 'IMPACT_RULE_UPDATED'");
    }

    private ImpactResult analyzeFormat() {
        return impact.analyze(new ImpactRequest(
                List.of(ShopFixture.symbolId(jdbc, "com.shop.lib.PriceFormatter#format(int)")), null, 3, null, null));
    }

    @Test
    void changesApplyToTheNextAnalysis() {
        assertThat(analyzeFormat().entryPoints()).isNotEmpty();
        assertThat(analyzeFormat().nodes()).extracting(ImpactNode::level).contains(3);

        long postMapping = jdbc.queryForObject("SELECT id FROM entry_point_annotation WHERE annotation_fqn = "
                + "'org.springframework.web.bind.annotation.PostMapping'", Long.class);
        assertThat(mvc.put().uri("/api/v1/admin/entry-point-annotations/" + postMapping)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"annotationFqn":"org.springframework.web.bind.annotation.PostMapping","label":"HTTP POST",
                         "enabled":false}
                        """)).hasStatusOk();
        assertThat(mvc.put().uri("/api/v1/admin/impact-rules/CALL").contentType(MediaType.APPLICATION_JSON)
                .content("{\"propagates\":false,\"shownAtLevel1\":true}")).hasStatusOk().bodyJson()
                .extractingPath("$.propagates").isEqualTo(false);

        ImpactResult after = analyzeFormat();
        assertThat(after.nodes()).extracting(ImpactNode::level).doesNotContain(2, 3);
        assertThat(mvc.get().uri("/api/v1/admin/impact-rules")).hasStatusOk().bodyJson()
                .extractingPath("$[?(@.kind == 'CALL')].propagates").asArray().containsExactly(false);
    }

    @Test
    void managesEntryPointAnnotations() {
        assertThat(mvc.post().uri("/api/v1/admin/entry-point-annotations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"annotationFqn\":\"com.corp.Job\",\"label\":\"Corp job\",\"enabled\":true}"))
                .hasStatus(201);
        assertThat(mvc.post().uri("/api/v1/admin/entry-point-annotations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"annotationFqn\":\"com.corp.Job\",\"label\":\"again\",\"enabled\":true}")).hasStatus(409);
        assertThat(mvc.post().uri("/api/v1/admin/entry-point-annotations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"annotationFqn\":\"not a name!\",\"label\":\"x\",\"enabled\":true}")).hasStatus(400);
        long id = jdbc.queryForObject("SELECT id FROM entry_point_annotation WHERE annotation_fqn = 'com.corp.Job'",
                Long.class);

        assertThat(mvc.delete().uri("/api/v1/admin/entry-point-annotations/" + id)).hasStatus(204);
        assertThat(mvc.delete().uri("/api/v1/admin/entry-point-annotations/" + id)).hasStatus(404);
        assertThat(mvc.put().uri("/api/v1/admin/impact-rules/NOPE").contentType(MediaType.APPLICATION_JSON)
                .content("{\"propagates\":true,\"shownAtLevel1\":true}")).hasStatus(400);
        assertThat(mvc.put().uri("/api/v1/admin/impact-rules/CALL").contentType(MediaType.APPLICATION_JSON)
                .content("{\"propagates\":true}")).hasStatus(400);
        assertThat(as("viewer", Role.USER).get().uri("/api/v1/admin/impact-rules")).hasStatus(403);
    }
}
```

**Adapting `changesApplyToTheNextAnalysis` to the fixture.** The test relies on two facts about the shop fixture. Check both in `ImpactServiceTest` and adjust this test (not the code) if they differ:
- `OrderController#checkout()` (`@PostMapping`) is the level-3 entry point of `PriceFormatter#format(int)`.
- With `CALL` no longer propagating, only level-1 nodes remain.

The aim is to prove the next analysis reflects both changes without a restart.

**Entry-point label assertion.** Also assert that the entry point labelled from `@PostMapping` disappears once it is disabled. Use the `EntryPoint` record's label field from plan 3, and replace the bare `isNotEmpty()` with that concrete assertion.

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw test -Dtest=ImpactRulesAdminApiTest`
Expected: 404s for the new endpoints.

- [ ] **Step 3: Implement**

`src/main/java/com/graphify/impact/EntryPointAnnotation.java`:

```java
package com.graphify.impact;

/** A row of entry_point_annotation (spec §6.2): methods carrying it are reported as entry points. */
public record EntryPointAnnotation(long id, String annotationFqn, String label, boolean enabled) {
}
```

`src/main/java/com/graphify/impact/ImpactRuleAdministration.java`:

```java
package com.graphify.impact;

import com.graphify.audit.AuditLog;
import com.graphify.common.exception.ConflictException;
import com.graphify.common.exception.InvalidRequestException;
import com.graphify.common.exception.NotFoundException;
import com.graphify.common.util.Utf8;
import com.graphify.indexer.model.UsageKind;
import java.sql.PreparedStatement;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Entry-point annotations and impact relation rules for admins (spec §10.6); read afresh by every analysis. */
@Service
public class ImpactRuleAdministration {

    public record AnnotationChange(String annotationFqn, String label, Boolean enabled) {
    }

    public record RuleChange(Boolean propagates, Boolean shownAtLevel1) {
    }

    /** A dotted Java type name, the form annotation_fqn is matched against. */
    private static final Pattern JAVA_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

    /** Widths of entry_point_annotation.annotation_fqn and .label in V3__search_and_impact.sql. */
    private static final int FQN_BYTES = 500;
    private static final int LABEL_BYTES = 200;

    private final JdbcTemplate jdbc;
    private final ImpactRules rules;
    private final AuditLog auditLog;

    public ImpactRuleAdministration(JdbcTemplate jdbc, ImpactRules rules, AuditLog auditLog) {
        this.jdbc = jdbc;
        this.rules = rules;
        this.auditLog = auditLog;
    }

    public List<EntryPointAnnotation> annotations() {
        return jdbc.query("SELECT id, annotation_fqn, label, enabled FROM entry_point_annotation ORDER BY annotation_fqn",
                (rs, row) -> new EntryPointAnnotation(rs.getLong("id"), rs.getString("annotation_fqn"),
                        rs.getString("label"), rs.getInt("enabled") == 1));
    }

    public EntryPointAnnotation annotation(long id) {
        return annotations().stream().filter(a -> a.id() == id).findFirst()
                .orElseThrow(() -> new NotFoundException("No entry-point annotation with id " + id));
    }

    @Transactional
    public EntryPointAnnotation create(AnnotationChange change, String actor) {
        AnnotationChange valid = validate(change);
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("INSERT INTO entry_point_annotation "
                        + "(annotation_fqn, label, enabled) VALUES (?, ?, ?)", new String[] {"id"});
                statement.setString(1, valid.annotationFqn());
                statement.setString(2, valid.label());
                statement.setInt(3, valid.enabled() ? 1 : 0);
                return statement;
            }, keys);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("Annotation " + valid.annotationFqn() + " is already listed");
        }
        auditLog.record(actor, "ENTRY_POINT_ANNOTATION_CREATED", valid.annotationFqn(), valid.label());
        return annotation(keys.getKey().longValue());
    }

    @Transactional
    public EntryPointAnnotation update(long id, AnnotationChange change, String actor) {
        annotation(id);
        AnnotationChange valid = validate(change);
        try {
            jdbc.update("UPDATE entry_point_annotation SET annotation_fqn = ?, label = ?, enabled = ? WHERE id = ?",
                    valid.annotationFqn(), valid.label(), valid.enabled() ? 1 : 0, id);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("Annotation " + valid.annotationFqn() + " is already listed");
        }
        auditLog.record(actor, "ENTRY_POINT_ANNOTATION_UPDATED", valid.annotationFqn(),
                "label=" + valid.label() + ", enabled=" + valid.enabled());
        return annotation(id);
    }

    @Transactional
    public void delete(long id, String actor) {
        EntryPointAnnotation current = annotation(id);
        jdbc.update("DELETE FROM entry_point_annotation WHERE id = ?", id);
        auditLog.record(actor, "ENTRY_POINT_ANNOTATION_DELETED", current.annotationFqn(), null);
    }

    public List<ImpactRule> rules() {
        return rules.rules().values().stream().sorted(Comparator.comparing(ImpactRule::kind)).toList();
    }

    @Transactional
    public ImpactRule updateRule(UsageKind kind, RuleChange change, String actor) {
        if (change == null || change.propagates() == null || change.shownAtLevel1() == null) {
            throw new InvalidRequestException("A body {propagates, shownAtLevel1} is required");
        }
        int updated = jdbc.update("UPDATE impact_relation_rule SET propagates = ?, shown_at_level1 = ? "
                + "WHERE usage_kind = ?", change.propagates() ? 1 : 0, change.shownAtLevel1() ? 1 : 0, kind.name());
        if (updated == 0) {
            throw new NotFoundException("No impact rule for " + kind);
        }
        auditLog.record(actor, "IMPACT_RULE_UPDATED", kind.name(),
                "propagates=" + change.propagates() + ", shownAtLevel1=" + change.shownAtLevel1());
        return rules.rules().get(kind);
    }

    private static AnnotationChange validate(AnnotationChange change) {
        if (change == null || change.enabled() == null) {
            throw new InvalidRequestException("A body {annotationFqn, label, enabled} is required");
        }
        String fqn = change.annotationFqn() == null ? "" : change.annotationFqn().strip();
        if (!JAVA_NAME.matcher(fqn).matches() || Utf8.byteLength(fqn) > FQN_BYTES) {
            throw new InvalidRequestException("annotationFqn must be a fully qualified Java name");
        }
        String label = change.label() == null ? "" : change.label().strip();
        if (label.isEmpty() || Utf8.byteLength(label) > LABEL_BYTES) {
            throw new InvalidRequestException("label is required and at most " + LABEL_BYTES + " bytes");
        }
        return new AnnotationChange(fqn, label, change.enabled());
    }
}
```

`src/main/java/com/graphify/impact/ImpactRulesAdminController.java`:

```java
package com.graphify.impact;

import com.graphify.indexer.model.UsageKind;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Entry-point annotations and impact rules (spec §10.6 "Giriş noktası annotation'ları", "Etki kuralları"). */
@RestController
@RequestMapping("/api/v1/admin")
public class ImpactRulesAdminController {

    private final ImpactRuleAdministration administration;

    public ImpactRulesAdminController(ImpactRuleAdministration administration) {
        this.administration = administration;
    }

    @GetMapping("/entry-point-annotations")
    public List<EntryPointAnnotation> annotations() {
        return administration.annotations();
    }

    @PostMapping("/entry-point-annotations")
    public ResponseEntity<EntryPointAnnotation> create(
            @RequestBody(required = false) ImpactRuleAdministration.AnnotationChange change,
            Authentication authentication) {
        EntryPointAnnotation created = administration.create(change, authentication.getName());
        return ResponseEntity.created(URI.create("/api/v1/admin/entry-point-annotations/" + created.id())).body(created);
    }

    @PutMapping("/entry-point-annotations/{id}")
    public EntryPointAnnotation update(@PathVariable long id,
            @RequestBody(required = false) ImpactRuleAdministration.AnnotationChange change,
            Authentication authentication) {
        return administration.update(id, change, authentication.getName());
    }

    @DeleteMapping("/entry-point-annotations/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id, Authentication authentication) {
        administration.delete(id, authentication.getName());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/impact-rules")
    public List<ImpactRule> rules() {
        return administration.rules();
    }

    @PutMapping("/impact-rules/{kind}")
    public ImpactRule updateRule(@PathVariable UsageKind kind,
            @RequestBody(required = false) ImpactRuleAdministration.RuleChange change, Authentication authentication) {
        return administration.updateRule(kind, change, authentication.getName());
    }
}
```

`annotation(id)` filters the full list; the table holds a few dozen rows. If `ImpactRule`'s accessor is not `kind()`, use the record's actual component name.

In `README.md`, add the rows `GET/POST /admin/entry-point-annotations`, `PUT/DELETE /admin/entry-point-annotations/{id}`, `GET /admin/impact-rules` and `PUT /admin/impact-rules/{kind}` (`{propagates, shownAtLevel1}`), with the note "changes apply to the next analysis".

- [ ] **Step 4: Run the tests**

Run: `./mvnw test -Dtest='ImpactRulesAdminApiTest,ImpactServiceTest,ImpactApiTest'`
Expected: all pass.

Run: `./mvnw test`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main src/test README.md
git commit -m "feat(admin): manage entry-point annotations and impact rules through the API" -m "<your harness Co-Authored-By trailer>"
```
