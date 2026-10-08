# Plan 15: Checking Directory and Maven Settings on Save — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When an admin saves a setting on Yönetim → Ayarlar, the server checks the setting's real effect before saving it.
- `index.workspace_dir` and `index.maven_local_repository` must be absolute paths to a directory the server can create and write.
- `index.maven_executable` must answer `-v` within a timeout.

A refused value is not saved. The admin sees the reason under the field instead of a scan failing later with `Read-only file system`.

**Architecture:**
- **Validator hook:** a `SettingValidator` hook keyed by setting key. Validators are Spring beans, so a later key needs only a new bean.
- **`SettingsAdministration` (new service), behind `PUT /api/v1/admin/settings/{key}`:**
  1. runs the existing type and bounds check, through the new `AppSettings.checkValue`;
  2. runs the key's validator, outside any database transaction;
  3. calls `AppSettings.update`.
- **`AppSettings.update` itself does not run validators.** Tests and internal callers keep saving any type-valid value, for example restoring the seeded `/data/...` defaults on a developer machine.
- **New setting:** V12 seeds `index.maven_check_timeout`.

**Tech Stack:** Spring Boot 4.1.1, `java.nio.file`, `ProcessBuilder`, Flyway, JUnit 5 with Testcontainers Oracle, React 19 + Vitest + MSW. No new dependency.

**Spec:**
- The request was approved in chat on 2026-10-08. It follows the first real scan on the test WildFly, which failed with `GitException: clone … failed: /data: Read-only file system`.
- The user asked whether the server's write directories can be set from the UI. They can (Yönetim → Ayarlar), but nothing checked them. The approved design is the feature list in **Goal**.
- Background:
  - `docs/superpowers/specs/2026-10-05-impact-analyzer-design.md` §10.6 "Ayarlar": settings are runtime values from the database, never constants.
  - `docs/superpowers/specs/2026-10-06-web-ui-design.md` §4.2: the settings screen.

**Code facts the plan relies on (read from main at 8cf65a1):**
- **`AppSettings` (`settings/AppSettings.java`):**
  - `update(key, rawValue, actor)` is `@Transactional`. It checks the actor, calls `find(key)` (unknown key → `SettingNotFoundException` → 404), strips the value, then runs `validate(current, value)`.
  - `validate` checks: not null, at most `VALUE_BYTES`, `SettingType.parse`, and the INT `minValue`/`maxValue`.
  - Then it writes the row, audits `SETTING_UPDATED`, and after commit clears the cache and publishes `SettingChangedEvent`.
  - `validate` is `private static`.
- **Errors:**
  - `InvalidSettingValueException(key, reason)` has the message `"Invalid value for " + key + ": " + reason`. `ApiExceptionHandler.invalidSetting` turns it into a 400 `ProblemDetail` whose `detail` is that message.
  - `SettingNotFoundException` → 404.
- **`SettingType`:** DURATION parses ISO-8601 and rejects zero or negative values. STRING rejects blank values. `min_value`/`max_value` exist only for INT.
- **`SettingsAdminController.update`:** a null body or value → `InvalidRequestException` (400); otherwise `settings.update(key, change.value(), authentication.getName())`.
- **Seed data:** `V2__seed_settings.sql` seeds `index.workspace_dir` = `/data/impact-analyzer/repos` (STRING) and `index.maven_timeout` = `PT10M` (DURATION). The `index.maven_executable` (`mvn`) and `index.maven_local_repository` (`/data/impact-analyzer/m2`) seeds come from the acquisition migration (see `SchemaMigrationTest.createsTheAcquisitionTablesAndSeedsTheirSettings`). The latest migration is `V11__github_own_repositories.sql`.
- **Consumers:**
  - `GitWorkspace.directoryFor` resolves under `Path.of(settings.getString(INDEX_WORKSPACE_DIR))`.
  - `ClasspathResolver.run` runs `settings.getString(INDEX_MAVEN_EXECUTABLE)` plus flags, with `-Dmaven.repo.local=` + `INDEX_MAVEN_LOCAL_REPOSITORY`. It uses `ProcessBuilder` with an argument list, and clears the environment down to `childEnvironment(System.getenv())`. `INHERITED_ENVIRONMENT` is PATH, HOME, JAVA_HOME, LANG, LC_ALL, TMPDIR; the service keeps APP_MASTER_KEY and DB_PASSWORD in its own environment.
  - On timeout it kills the process and its descendants, then waits `KILL_WAIT` (5 s, `private static final`).
  - `privateTempFile` (owner-only temp file) and `readLenient` are static helpers in the same class.
- **Tests that change these settings call `AppSettings.update` directly** (through `SettingsOverride.set/restore`, or directly), never the HTTP API. They then restore the original value, `/data/...`:
  - `GitWorkspaceTest` (setUp/tearDown);
  - `IndexRunExecutorTest`, `IndexRunServiceTest`, `RepositoryIndexerTest` (workspace and local repository);
  - `ClasspathResolverTest`, which sets the executable to a missing file and to fake scripts on purpose.
- **Frontend:**
  - `SettingsPage.tsx` `SettingRow` shows `formatHint ?? errorMessage(save.error)` as the `TextInput` error, keeps the draft, and shows `loading` on the Save button while the PUT runs.
  - `settings.test.tsx` already covers a 400 on `index.cron`.

## Rulings

1. **Validators run only on the admin API path (`SettingsAdministration`), not inside `AppSettings.update`.**
   - Every test that restores `/data/...` through `AppSettings.update` would fail on a developer Mac. `ClasspathResolverTest` deliberately saves a missing executable.
   - The Maven check can take seconds, and must not hold the `@Transactional` database connection.
   - Cost: a value written by code, not by an admin, is not checked. Nothing writes these keys except tests.
2. **Defaults are not re-checked at startup.** The seeded `/data/...` defaults stay as they are. Only an admin save is checked. A bad value already stored keeps failing at scan time, as today.
3. **Directory check:**
   - The path must be absolute. A relative path would resolve against WildFly's working directory, which is surprising.
   - It is normalised (`..` resolved).
   - It is created with `Files.createDirectories` if missing. An existing non-directory is refused.
   - A probe file is created with `Files.createTempFile(dir, ".graphify-write-check", ".tmp")` and deleted in `finally`.
   - Symlinks are followed: a symlink to a writable directory is fine. The check judges the directory as the indexer will use it.
   - A directory created by a check that later fails stays. It is empty and harmless.
   - The reason quotes the path and the file system's own reason, e.g. `directory is not usable: /data/impact-analyzer/repos (Read-only file system)`. Paths are not secrets.
4. **Maven check:**
   - Command: `ProcessBuilder(List.of(value, "-v"))`. No shell, so a value with spaces or `;` is one program path.
   - Environment: the same reduced environment as indexing (`ClasspathResolver.childEnvironment`), so the check is faithful and never sees APP_MASTER_KEY or DB_PASSWORD.
   - stdin is closed. stdout and stderr go to an owner-only temp file, never logged, deleted in `finally`.
   - **Timeout:** a new DURATION setting, `index.maven_check_timeout`, seeded at `PT30S` by V12.
     - Reusing `index.maven_timeout` (PT10M) would let one Save block an HTTP request for ten minutes.
     - DURATION has no min/max columns. The type already refuses zero and negative values, and an admin choosing a long check timeout only delays their own Save.
   - On timeout the process and its descendants are killed (`KILL_WAIT` reused, now package-private) and the reason says `did not answer -v within PT30S`.
   - A non-zero exit gives `exited with N: <last non-blank output line>`, masked with `UrlMasking`. One line keeps the field error short; this is a display choice, not an operational value.
   - A start failure gives `could not be started: <reason>`.
5. **Trust:** an admin can already make the indexer run any `index.maven_executable`. The check runs the same program with `-v`, so it adds no new power.
6. **Duplicate validator keys** fail at startup (`IllegalStateException`), so two beans cannot silently fight over a key.
7. **The UI needs no change.** `SettingRow` already shows the backend reason under the field and keeps the draft. Task 2 adds a test for a path setting's 400 and documents the checks in the README.

## Plan series

| Plan | Scope | Status |
|---|---|---|
| 1–8 | Backend | merged |
| 9–12 | Web foundation, user screens, repository graph screen, admin screens | merged |
| 13–14 | Repo connection types; GitHub own repositories | merged |
| **15** | **Checking directory and Maven settings on save** (this plan) | — |

## Global Constraints

- **Environment:**
  - Backend: `export JAVA_HOME=/opt/homebrew/opt/openjdk@25`; `./mvnw -q test -Dfrontend.skip=true` (or a `-Dtest=` subset while iterating). Oracle comes from Testcontainers.
  - Frontend: commands run in `frontend/` (Node 24, npm 11). `npm test` runs `check:api`, the typecheck, lint and Vitest; `npm run build` must pass.
  - Both suites are green at the end of every task.
  - No new dependency.
- **"Kodda sabit değer yok" (no hardcoded values):**
  - Timeouts come from settings: the new `index.maven_check_timeout`.
  - Allowed: the `-v` argument, the probe file prefix, the existing `KILL_WAIT`, and column widths with their migration named in a comment.
- **Secrets:**
  - The Maven check never inherits more than `ClasspathResolver.INHERITED_ENVIRONMENT`.
  - Never log the environment or the command output.
  - Mask output with `UrlMasking.mask` before it reaches a message.
- **Text:**
  - Every user-visible text lives in `frontend/src/i18n/tr.ts`, in Turkish sentences. Software terms stay English.
  - `frontend/src/i18n/terms.test.ts` must keep passing.
  - Backend messages are English, following the existing `InvalidSettingValueException` convention.
  - Setting descriptions in migrations are Turkish, like V2.
- **Running test environment:** the user is using these; never stop, restart or redeploy them (the controller redeploys after the merge, with the user's consent):
  - a test WildFly on port 9080 (`/tmp/wildfly-gate/wildfly-41.0.0.Final`);
  - Oracle container `graphify-wildfly-db` on 1522;
  - LDAP container `graphify-ldap` on 1389.

  Never touch the user's WildFly on 8080 or container `fw-batch-oracle`.
- **Staging:**
  - The working tree may hold the user's own files (`notes/` is git-ignored).
  - Commit only your paths, with `git commit <path> <path> … -m …`, and check `git show --stat HEAD`.
  - Never `git add -A` / `git add .` / `git stash` / `git reset`.
- **Commits** end with:
  ```
  Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01XpYgYKeBYFNw4tb6EwJ44V
  ```

## Review Focus

1. **A refused value is not saved and the admin sees why.** Test: Task 1 `SettingsAdminApiTest`:
   - a read-only directory → 400 whose detail names the key and the path; `settings.getString` is unchanged;
   - a missing Maven → 400; the value is unchanged.
2. **A hung Maven does not hang the request.** The check returns soon after `index.maven_check_timeout` and the process is killed. Test: Task 1 `MavenExecutableValidatorTest` "a hung program is killed at the timeout".
3. **No shell, and no secrets in the child.** A program path with a space works. The child sees `-v` as its argument and nothing like `APP_MASTER_KEY`. Test: Task 1 `MavenExecutableValidatorTest` "runs the path directly with -v and the reduced environment".
4. **Internal callers are not checked.** `AppSettings.update` still saves a relative or unwritable path, so test restores and the seeded defaults keep working. Test: Task 1 `AppSettingsTest` "update does not run the server-side checks".
5. **The probe leaves nothing behind.** After a successful check the directory is empty. A path that is a file is refused. Test: Task 1 `WritableDirectoryValidatorTest`.

---

## File Structure

| File | Responsibility |
|---|---|
| `src/main/resources/db/migration/V12__maven_check_timeout.sql` (new) | Seeds `index.maven_check_timeout` |
| `src/main/java/com/graphify/settings/SettingKeys.java` (modify) | `INDEX_MAVEN_CHECK_TIMEOUT` |
| `settings/SettingValidator.java` (new) | The hook |
| `settings/SettingsAdministration.java` (new) | Type check → validator → save |
| `settings/AppSettings.java` (modify) | `checkValue` extracted from `update` |
| `settings/SettingsAdminController.java` (modify) | Calls `SettingsAdministration` |
| `settings/WritableDirectoryValidator.java` (new) | `index.workspace_dir`, `index.maven_local_repository` |
| `maven/MavenExecutableValidator.java` (new) | `index.maven_executable` |
| `maven/ClasspathResolver.java` (modify) | `KILL_WAIT`, `privateTempFile`, `deleteQuietly` package-private for reuse |
| `src/test/java/com/graphify/settings/WritableDirectoryValidatorTest.java`, `SettingsAdministrationTest.java` (new); `SettingsAdminApiTest.java`, `AppSettingsTest.java` (modify) | Tests |
| `src/test/java/com/graphify/maven/MavenExecutableValidatorTest.java` (new) | Tests |
| `src/test/java/com/graphify/store/SchemaMigrationTest.java` (modify) | V12 seed |
| `frontend/src/features/admin/settings/settings.test.tsx` (modify) | 400 on a path setting |
| `README.md` (modify) | The checks |

---

### Task 1: Server-side checks on save

**Files:**
- Create: `src/main/resources/db/migration/V12__maven_check_timeout.sql`
- Create: `src/main/java/com/graphify/settings/SettingValidator.java`
- Create: `src/main/java/com/graphify/settings/SettingsAdministration.java`
- Create: `src/main/java/com/graphify/settings/WritableDirectoryValidator.java`
- Create: `src/main/java/com/graphify/maven/MavenExecutableValidator.java`
- Modify: `src/main/java/com/graphify/settings/SettingKeys.java`
- Modify: `src/main/java/com/graphify/settings/AppSettings.java`
- Modify: `src/main/java/com/graphify/settings/SettingsAdminController.java`
- Modify: `src/main/java/com/graphify/maven/ClasspathResolver.java`
- Test (new): `src/test/java/com/graphify/settings/WritableDirectoryValidatorTest.java`, `src/test/java/com/graphify/settings/SettingsAdministrationTest.java`, `src/test/java/com/graphify/maven/MavenExecutableValidatorTest.java`
- Test (modify): `src/test/java/com/graphify/settings/SettingsAdminApiTest.java`, `src/test/java/com/graphify/settings/AppSettingsTest.java`, `src/test/java/com/graphify/store/SchemaMigrationTest.java`

**Interfaces:**
- **Produces:**
  - `public interface SettingValidator { Set<String> keys(); Optional<String> problem(String key, String value); }`
  - `public String AppSettings.checkValue(String key, String rawValue)`
  - `public Setting SettingsAdministration.update(String key, String rawValue, String actor)`
  - `SettingKeys.INDEX_MAVEN_CHECK_TIMEOUT = "index.maven_check_timeout"`
- **Consumes:** `ClasspathResolver.childEnvironment(Map)`, `ClasspathResolver.readLenient(Path)`, `UrlMasking.mask(String)`, `InvalidSettingValueException(key, reason)`.
- The API shape does not change: same path, same body, a 400 `ProblemDetail`. `frontend/openapi.json` stays as it is. Run `OpenApiSnapshotTest` to confirm.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/graphify/settings/WritableDirectoryValidatorTest.java`:

```java
package com.graphify.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WritableDirectoryValidatorTest {

    private static final String KEY = SettingKeys.INDEX_WORKSPACE_DIR;

    private final WritableDirectoryValidator validator = new WritableDirectoryValidator();

    @TempDir
    Path dir;

    @Test
    void checksBothDirectorySettings() {
        assertThat(validator.keys()).containsExactlyInAnyOrder(SettingKeys.INDEX_WORKSPACE_DIR,
                SettingKeys.INDEX_MAVEN_LOCAL_REPOSITORY);
    }

    @Test
    void anExistingWritableDirectoryPassesAndKeepsNoProbe() throws Exception {
        assertThat(validator.problem(KEY, dir.toString())).isEmpty();
        try (var entries = Files.list(dir)) {
            assertThat(entries).isEmpty();
        }
    }

    @Test
    void aMissingDirectoryIsCreated() {
        Path missing = dir.resolve("a/b/repos");

        assertThat(validator.problem(KEY, missing.toString())).isEmpty();
        assertThat(missing).isDirectory();
    }

    @Test
    void aRelativePathIsRefused() {
        assertThat(validator.problem(KEY, "data/repos")).hasValueSatisfying(reason ->
                assertThat(reason).contains("absolute path").contains("data/repos"));
    }

    @Test
    void aFileIsRefused() throws Exception {
        Path file = Files.writeString(dir.resolve("file.txt"), "x");

        assertThat(validator.problem(KEY, file.toString())).hasValueSatisfying(reason ->
                assertThat(reason).contains("not a directory").contains(file.toString()));
    }

    @Test
    void aReadOnlyDirectoryIsRefusedWithThePathAndTheReason() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        assumeTrue(!"root".equals(System.getProperty("user.name")), "root can write anywhere");
        Path readOnly = Files.createDirectory(dir.resolve("ro"));
        Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assertThat(validator.problem(KEY, readOnly.toString())).hasValueSatisfying(reason ->
                    assertThat(reason).startsWith("directory is not usable: " + readOnly).contains("("));
            assertThat(validator.problem(KEY, readOnly.resolve("child").toString())).isPresent();
        } finally {
            Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void aSymlinkToAWritableDirectoryPasses() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path target = Files.createDirectory(dir.resolve("target"));
        Path link = Files.createSymbolicLink(dir.resolve("link"), target);

        assertThat(validator.problem(KEY, link.toString())).isEmpty();
    }
}
```

`src/test/java/com/graphify/maven/MavenExecutableValidatorTest.java`:

```java
package com.graphify.maven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.graphify.settings.SettingKeys;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenExecutableValidatorTest {

    private static final String KEY = SettingKeys.INDEX_MAVEN_EXECUTABLE;

    @TempDir
    Path dir;

    @BeforeEach
    void posixOnly() {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
    }

    private Path script(String name, String body) throws Exception {
        Path file = dir.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
        return file;
    }

    private MavenExecutableValidator validator(Duration timeout) {
        return new MavenExecutableValidator(() -> timeout);
    }

    @Test
    void checksTheMavenExecutable() {
        assertThat(validator(Duration.ofSeconds(5)).keys()).containsExactly(KEY);
    }

    @Test
    void aProgramThatAnswersVersionPasses() throws Exception {
        Path mvn = script("mvn", "echo 'Apache Maven 3.9.9'");

        assertThat(validator(Duration.ofSeconds(10)).problem(KEY, mvn.toString())).isEmpty();
    }

    @Test
    void runsThePathDirectlyWithVersionFlagAndTheReducedEnvironment() throws Exception {
        // a directory with a space: through a shell this would be two words
        Path mvn = script("with space/mvn", "echo \"arg=$1 key=${APP_MASTER_KEY:-none}\"; exit 3");

        assertThat(validator(Duration.ofSeconds(10)).problem(KEY, mvn.toString())).hasValueSatisfying(reason ->
                assertThat(reason).isEqualTo("exited with 3: arg=-v key=none"));
    }

    @Test
    void reportsTheLastOutputLineOfAFailure() throws Exception {
        Path mvn = script("mvn", "echo first; echo 'The JAVA_HOME environment variable is not defined correctly'; echo; exit 1");

        assertThat(validator(Duration.ofSeconds(10)).problem(KEY, mvn.toString())).hasValue(
                "exited with 1: The JAVA_HOME environment variable is not defined correctly");
    }

    @Test
    void aMissingProgramCannotBeStarted() {
        assertThat(validator(Duration.ofSeconds(5)).problem(KEY, dir.resolve("no-such-mvn").toString()))
                .hasValueSatisfying(reason -> assertThat(reason).startsWith("could not be started: "));
    }

    @Test
    void aHungProgramIsKilledAtTheTimeout() throws Exception {
        Path mvn = script("mvn", "sleep 30");

        long start = System.nanoTime();
        var problem = validator(Duration.ofMillis(300)).problem(KEY, mvn.toString());
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(problem).hasValue("did not answer -v within PT0.3S");
        assertThat(took).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void credentialsInTheOutputAreMasked() throws Exception {
        Path mvn = script("mvn", "echo 'Could not reach https://bob:s3cret@repo.test/maven'; exit 1");

        assertThat(validator(Duration.ofSeconds(10)).problem(KEY, mvn.toString())).hasValueSatisfying(reason ->
                assertThat(reason).doesNotContain("s3cret"));
    }
}
```

`src/test/java/com/graphify/settings/SettingsAdministrationTest.java`:

```java
package com.graphify.settings;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SettingsAdministrationTest {

    private record Fixed(String key) implements SettingValidator {
        @Override
        public Set<String> keys() {
            return Set.of(key);
        }

        @Override
        public Optional<String> problem(String key, String value) {
            return Optional.empty();
        }
    }

    @Test
    void twoValidatorsForOneKeyFailAtStartup() {
        assertThatThrownBy(() -> new SettingsAdministration(null,
                List.of(new Fixed("index.workspace_dir"), new Fixed("index.workspace_dir"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("index.workspace_dir");
    }
}
```

Append to `SettingsAdminApiTest` (keep its existing `overrides` setUp/tearDown; add the imports `java.nio.file.Files`, `java.nio.file.Path`, `java.nio.file.FileSystems`, `java.nio.file.attribute.PosixFilePermissions`, `org.junit.jupiter.api.io.TempDir`, and `static org.junit.jupiter.api.Assumptions.assumeTrue`):

```java
    @TempDir
    Path dir;

    private void remember(String key) {
        overrides.set(key, settings.all().stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow().value());
    }

    @Test
    void savesAWritableDirectoryAndCreatesIt() {
        remember(SettingKeys.INDEX_WORKSPACE_DIR);
        Path target = dir.resolve("repos");

        assertThat(mvc.put().uri("/api/v1/admin/settings/index.workspace_dir").contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"" + target + "\"}")).hasStatusOk();
        assertThat(settings.getString(SettingKeys.INDEX_WORKSPACE_DIR)).isEqualTo(target.toString());
        assertThat(target).isDirectory();
    }

    @Test
    void refusesAnUnusableDirectoryAndKeepsTheOldValue() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        assumeTrue(!"root".equals(System.getProperty("user.name")), "root can write anywhere");
        remember(SettingKeys.INDEX_MAVEN_LOCAL_REPOSITORY);
        String before = settings.getString(SettingKeys.INDEX_MAVEN_LOCAL_REPOSITORY);
        Path readOnly = Files.createDirectory(dir.resolve("ro"));
        Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assertThat(mvc.put().uri("/api/v1/admin/settings/index.maven_local_repository")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"" + readOnly.resolve("m2") + "\"}"))
                    .hasStatus(400).bodyJson().extractingPath("$.detail").asString()
                    .contains("index.maven_local_repository").contains("directory is not usable").contains(readOnly.toString());
        } finally {
            Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        assertThat(settings.getString(SettingKeys.INDEX_MAVEN_LOCAL_REPOSITORY)).isEqualTo(before);
    }

    @Test
    void refusesARelativeDirectory() {
        assertThat(mvc.put().uri("/api/v1/admin/settings/index.workspace_dir").contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"data/repos\"}")).hasStatus(400).bodyJson().extractingPath("$.detail").asString()
                .contains("absolute path");
    }

    @Test
    void refusesAMavenThatCannotRunAndKeepsTheOldValue() {
        String before = settings.getString(SettingKeys.INDEX_MAVEN_EXECUTABLE);

        assertThat(mvc.put().uri("/api/v1/admin/settings/index.maven_executable").contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"" + dir.resolve("no-such-mvn") + "\"}")).hasStatus(400).bodyJson()
                .extractingPath("$.detail").asString().contains("index.maven_executable").contains("could not be started");
        assertThat(settings.getString(SettingKeys.INDEX_MAVEN_EXECUTABLE)).isEqualTo(before);
    }

    @Test
    void savesAMavenThatAnswersVersion() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        remember(SettingKeys.INDEX_MAVEN_EXECUTABLE);
        Path mvn = dir.resolve("mvn");
        Files.writeString(mvn, "#!/bin/sh\necho 'Apache Maven 3.9.9'\n");
        Files.setPosixFilePermissions(mvn, PosixFilePermissions.fromString("rwx------"));

        assertThat(mvc.put().uri("/api/v1/admin/settings/index.maven_executable").contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"" + mvn + "\"}")).hasStatusOk();
        assertThat(settings.getString(SettingKeys.INDEX_MAVEN_EXECUTABLE)).isEqualTo(mvn.toString());
    }

    @Test
    void anUnknownKeyIsStillNotFoundAndATypeErrorComesFirst() {
        assertThat(mvc.put().uri("/api/v1/admin/settings/no.such.key").contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"/tmp\"}")).hasStatus(404);
        assertThat(mvc.put().uri("/api/v1/admin/settings/index.workspace_dir").contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"   \"}")).hasStatus(400).bodyJson().extractingPath("$.detail").asString()
                .contains("must not be blank");
    }
```

Append to `AppSettingsTest` (it already restores changed settings in `@AfterEach` through `change(...)`):

```java
    @Test
    void updateDoesNotRunTheServerSideChecks() {
        // tests and internal callers restore the seeded /data/... defaults; only the admin API checks the server side
        change(SettingKeys.INDEX_WORKSPACE_DIR, "relative/not/checked");

        assertThat(settings.getString(SettingKeys.INDEX_WORKSPACE_DIR)).isEqualTo("relative/not/checked");
    }

    @Test
    void checkValueValidatesWithoutSaving() {
        int before = settings.getInt(SettingKeys.INDEX_PARALLELISM);

        assertThat(settings.checkValue(SettingKeys.INDEX_PARALLELISM, " 8 ")).isEqualTo("8");
        assertThatThrownBy(() -> settings.checkValue(SettingKeys.INDEX_PARALLELISM, "0"))
                .isInstanceOf(InvalidSettingValueException.class);
        assertThatThrownBy(() -> settings.checkValue("no.such.key", "1")).isInstanceOf(SettingNotFoundException.class);
        assertThat(settings.getInt(SettingKeys.INDEX_PARALLELISM)).isEqualTo(before);
    }
```

Append to `SchemaMigrationTest`:

```java
    @Test
    void seedsTheMavenCheckTimeout() {
        assertThat(jdbc.queryForObject(
                "SELECT value_type || ' ' || setting_value FROM app_setting WHERE setting_key = 'index.maven_check_timeout'",
                String.class)).isEqualTo("DURATION PT30S");
    }
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `./mvnw -q test -Dfrontend.skip=true -Dtest='WritableDirectoryValidatorTest,MavenExecutableValidatorTest,SettingsAdministrationTest,SettingsAdminApiTest,AppSettingsTest,SchemaMigrationTest'`
Expected: compilation fails (`WritableDirectoryValidator`, `MavenExecutableValidator`, `SettingValidator`, `SettingsAdministration`, `checkValue` and `INDEX_MAVEN_CHECK_TIMEOUT` do not exist). Record the output.

- [ ] **Step 3: Implement**

`src/main/resources/db/migration/V12__maven_check_timeout.sql`:

```sql
-- V12: how long saving index.maven_executable waits for "<value> -v" (Plan 15)
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('index.maven_check_timeout', 'PT30S', 'DURATION',
     'Maven komutu kaydedilirken "-v" denemesinin zaman aşımı (ISO-8601)', NULL, NULL);
```

`SettingKeys.java`: add after `INDEX_SOURCE_ROOTS`:

```java
    public static final String INDEX_MAVEN_CHECK_TIMEOUT = "index.maven_check_timeout";
```

`SettingValidator.java`:

```java
package com.graphify.settings;

import java.util.Optional;
import java.util.Set;

/**
 * Checks what a setting does on the server (a directory it writes, a program it runs) when an admin saves it through
 * the API. Values written through {@link AppSettings#update} directly (tests, internal callers) are not checked, and
 * nothing is re-checked at startup.
 */
public interface SettingValidator {

    /** The setting keys this validator checks. */
    Set<String> keys();

    /** Why {@code value} (already type-checked and stripped) cannot be used for {@code key}; empty if it can. */
    Optional<String> problem(String key, String value);
}
```

`SettingsAdministration.java`:

```java
package com.graphify.settings;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** An admin's change to a setting: the type and bounds, then the key's server-side check, then the save. */
@Service
public class SettingsAdministration {

    private final AppSettings settings;
    private final Map<String, SettingValidator> validators;

    @Autowired
    public SettingsAdministration(AppSettings settings, ObjectProvider<SettingValidator> validators) {
        this(settings, validators.orderedStream().toList());
    }

    SettingsAdministration(AppSettings settings, List<SettingValidator> validators) {
        this.settings = settings;
        Map<String, SettingValidator> byKey = new HashMap<>();
        for (SettingValidator validator : validators) {
            for (String key : validator.keys()) {
                if (byKey.putIfAbsent(key, validator) != null) {
                    throw new IllegalStateException("Two setting validators check " + key);
                }
            }
        }
        this.validators = Map.copyOf(byKey);
    }

    /**
     * Saves the value if its type, bounds and server-side check allow it. The check runs outside any database
     * transaction because it may run a program; a refused value is not saved.
     */
    public Setting update(String key, String rawValue, String actor) {
        String value = settings.checkValue(key, rawValue);
        SettingValidator validator = validators.get(key);
        if (validator != null) {
            validator.problem(key, value).ifPresent(reason -> {
                throw new InvalidSettingValueException(key, reason);
            });
        }
        return settings.update(key, rawValue, actor);
    }
}
```

`AppSettings.java`: extract the check from `update`:

```java
    /**
     * Checks a value against the setting's type and bounds without saving it, and returns it as it would be stored.
     * An unknown key throws {@link SettingNotFoundException}.
     */
    public String checkValue(String key, String rawValue) {
        Setting current = find(key);
        String value = rawValue == null ? null : rawValue.strip();
        validate(current, value);
        return value;
    }
```

and in `update` replace

```java
        Setting current = find(key);
        String value = rawValue == null ? null : rawValue.strip();
        validate(current, value);
```

with

```java
        Setting current = find(key);
        String value = checkValue(key, rawValue);
```

`SettingsAdminController.java`: keep `AppSettings` for `list()`. Inject `SettingsAdministration` as well, and save through it. Also correct the class Javadoc:

```java
/**
 * Settings for admins (spec §10.6 "Ayarlar"); type checks and audit live in AppSettings, server-side checks in
 * SettingsAdministration.
 */
@RestController
@RequestMapping("/api/v1/admin/settings")
public class SettingsAdminController {

    public record ValueChange(String value) {
    }

    private final AppSettings settings;
    private final SettingsAdministration administration;

    public SettingsAdminController(AppSettings settings, SettingsAdministration administration) {
        this.settings = settings;
        this.administration = administration;
    }
    // list() unchanged

    @PutMapping("/{key}")
    public Setting update(@PathVariable String key, @RequestBody(required = false) ValueChange change,
            Authentication authentication) {
        if (change == null || change.value() == null) {
            throw new InvalidRequestException("A body {value} is required");
        }
        return administration.update(key, change.value(), authentication.getName());
    }
}
```

`WritableDirectoryValidator.java`:

```java
package com.graphify.settings;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The directories the indexer writes (checkouts, the Maven local repository) must be absolute, creatable and
 * writable on this server. Symlinks are followed: the directory is judged as the indexer will use it.
 */
@Component
public class WritableDirectoryValidator implements SettingValidator {

    /** Prefix of the file written and deleted to prove the directory is writable. */
    static final String PROBE_PREFIX = ".graphify-write-check";

    @Override
    public Set<String> keys() {
        return Set.of(SettingKeys.INDEX_WORKSPACE_DIR, SettingKeys.INDEX_MAVEN_LOCAL_REPOSITORY);
    }

    @Override
    public Optional<String> problem(String key, String value) {
        Path path;
        try {
            path = Path.of(value);
        } catch (InvalidPathException e) {
            return Optional.of("not a path: " + value);
        }
        if (!path.isAbsolute()) {
            return Optional.of("must be an absolute path: " + value);
        }
        path = path.normalize();
        if (Files.exists(path) && !Files.isDirectory(path)) {
            return Optional.of("not a directory: " + path);
        }
        try {
            Files.createDirectories(path);
            Path probe = Files.createTempFile(path, PROBE_PREFIX, ".tmp");
            Files.delete(probe);
            return Optional.empty();
        } catch (IOException | SecurityException e) {
            return Optional.of("directory is not usable: " + path + " (" + reason(e) + ")");
        }
    }

    private static String reason(Exception e) {
        if (e instanceof FileSystemException fileSystem && fileSystem.getReason() != null) {
            return fileSystem.getReason();
        }
        return e.getClass().getSimpleName();
    }
}
```

> Notes for the implementer:
> - If `Files.delete(probe)` throws, the directory is writable but the probe stays behind. That counts as a problem: the catch reports it, which is correct.
> - `AccessDeniedException` has no reason, so the class name is shown (`AccessDeniedException`). On macOS, `/data` fails with `Read-only file system`.

`ClasspathResolver.java`: make `KILL_WAIT`, `privateTempFile` and `deleteQuietly` package-private (drop `private`), with no other change, so the validator reuses them.

`MavenExecutableValidator.java`:

```java
package com.graphify.maven;

import com.graphify.scm.UrlMasking;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import com.graphify.settings.SettingValidator;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The Maven command must start and answer {@code -v} within {@code index.maven_check_timeout}. It runs without a
 * shell and with the same reduced environment as indexing (ClasspathResolver.INHERITED_ENVIRONMENT), so it never sees
 * the service's own secrets; its output goes to an owner-only temp file and is never logged.
 */
@Component
public class MavenExecutableValidator implements SettingValidator {

    static final String VERSION_FLAG = "-v";

    private final Supplier<Duration> timeout;

    @Autowired
    public MavenExecutableValidator(AppSettings settings) {
        this(() -> settings.getDuration(SettingKeys.INDEX_MAVEN_CHECK_TIMEOUT));
    }

    MavenExecutableValidator(Supplier<Duration> timeout) {
        this.timeout = timeout;
    }

    @Override
    public Set<String> keys() {
        return Set.of(SettingKeys.INDEX_MAVEN_EXECUTABLE);
    }

    @Override
    public Optional<String> problem(String key, String value) {
        Path output = null;
        try {
            output = ClasspathResolver.privateTempFile("graphify-mvn-check", ".log");
            ProcessBuilder builder = new ProcessBuilder(List.of(value, VERSION_FLAG)).redirectErrorStream(true)
                    .redirectOutput(output.toFile());
            builder.environment().clear();
            builder.environment().putAll(ClasspathResolver.childEnvironment(System.getenv()));
            Process process;
            try {
                process = builder.start();
            } catch (IOException e) {
                return Optional.of(UrlMasking.mask("could not be started: " + e.getMessage()));
            }
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {
                // nothing reads stdin; closing it only stops a prompt from waiting
            }
            Duration limit = timeout.get();
            try {
                if (!process.waitFor(limit.toMillis(), TimeUnit.MILLISECONDS)) {
                    kill(process);
                    return Optional.of("did not answer " + VERSION_FLAG + " within " + limit);
                }
            } catch (InterruptedException e) {
                kill(process);
                Thread.currentThread().interrupt();
                return Optional.of("the check was interrupted");
            }
            if (process.exitValue() == 0) {
                return Optional.empty();
            }
            return Optional.of(UrlMasking.mask("exited with " + process.exitValue() + ": "
                    + lastLine(ClasspathResolver.readLenient(output))));
        } catch (IOException e) {
            return Optional.of(UrlMasking.mask("could not be checked: " + e.getMessage()));
        } finally {
            ClasspathResolver.deleteQuietly(output);
        }
    }

    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(ClasspathResolver.KILL_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String lastLine(String text) {
        List<String> lines = text.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
        return lines.isEmpty() ? "(no output)" : lines.getLast();
    }
}
```

> Notes for the implementer:
> - The working directory is inherited from the server process, the same as an operator running `mvn -v`. `-v` reads no project.
> - `UrlMasking.mask` takes the whole message, so a URL with userinfo in the last line is masked (the test `credentialsInTheOutputAreMasked`).

- [ ] **Step 4: Run the tests to see them pass**

Run: `./mvnw -q test -Dfrontend.skip=true -Dtest='WritableDirectoryValidatorTest,MavenExecutableValidatorTest,SettingsAdministrationTest,SettingsAdminApiTest,AppSettingsTest,SchemaMigrationTest,OpenApiSnapshotTest'`
Expected: PASS. `OpenApiSnapshotTest` is unchanged, because the API shape is the same.

Then run the full backend suite, `./mvnw -q test -Dfrontend.skip=true`, and the frontend `npm test`; both are green. `GitWorkspaceTest`, `ClasspathResolverTest`, `IndexRunExecutorTest`, `IndexRunServiceTest` and `RepositoryIndexerTest` still pass unchanged (Ruling 1).

- [ ] **Step 5: Commit**

```bash
git commit src/main/resources/db/migration/V12__maven_check_timeout.sql \
  src/main/java/com/graphify/settings/SettingKeys.java src/main/java/com/graphify/settings/SettingValidator.java \
  src/main/java/com/graphify/settings/SettingsAdministration.java src/main/java/com/graphify/settings/AppSettings.java \
  src/main/java/com/graphify/settings/SettingsAdminController.java \
  src/main/java/com/graphify/settings/WritableDirectoryValidator.java \
  src/main/java/com/graphify/maven/MavenExecutableValidator.java src/main/java/com/graphify/maven/ClasspathResolver.java \
  src/test/java/com/graphify/settings/WritableDirectoryValidatorTest.java \
  src/test/java/com/graphify/settings/SettingsAdministrationTest.java \
  src/test/java/com/graphify/maven/MavenExecutableValidatorTest.java \
  src/test/java/com/graphify/settings/SettingsAdminApiTest.java src/test/java/com/graphify/settings/AppSettingsTest.java \
  src/test/java/com/graphify/store/SchemaMigrationTest.java \
  -m "feat(settings): check directory and Maven settings on the server before saving" \
  -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01XpYgYKeBYFNw4tb6EwJ44V"
```

New files must be added first, path by path (`git add <each new path>`). The pathspec commit then takes only the listed paths. Check `git show --stat HEAD`.

---

### Task 2: Frontend test and README

**Files:**
- Modify: `frontend/src/features/admin/settings/settings.test.tsx`
- Modify: `README.md`

**Interfaces:**
- Consumes: the Task 1 400 detail, `Invalid value for <key>: <reason>`. The UI shows `errorMessage(save.error)` under the field (`SettingsPage.tsx` `SettingRow`).
- Produces: nothing new.

- [ ] **Step 1: Write the test**

Add to `settings.test.tsx` inside `describe('settings', …)`, using the file's own `settings` fixture style:

```tsx
  it('shows why the server cannot use a directory and keeps the typed path', async () => {
    signedIn({ user: { role: 'ADMIN' } });
    const detail = 'Invalid value for index.workspace_dir: directory is not usable: /data/impact-analyzer/repos (Read-only file system)';
    server.use(
      http.get(apiUrl('/api/v1/admin/settings'), () => HttpResponse.json([
        { key: 'index.workspace_dir', value: '/tmp/graphify/repos', type: 'STRING', description: 'Repoların klonlandığı çalışma dizini' },
      ])),
      http.put(apiUrl('/api/v1/admin/settings/index.workspace_dir'), () =>
        HttpResponse.json({ status: 400, detail }, { status: 400 })),
    );
    renderApp('/admin/settings');

    const row = (await screen.findByText('index.workspace_dir')).closest('tr')!;
    const input = within(row).getByRole('textbox');
    await userEvent.clear(input);
    await userEvent.type(input, '/data/impact-analyzer/repos');
    await userEvent.click(within(row).getByRole('button', { name: tr.admin.common.save }));

    expect(await within(row).findByText(detail)).toBeInTheDocument();
    expect(input).toHaveValue('/data/impact-analyzer/repos');
  });
```

- [ ] **Step 2: Run it**

Run: `cd frontend && npx vitest run src/features/admin/settings`
Expected: PASS without a UI change. This proves Ruling 7. If it fails, the UI is not showing the backend reason: fix `SettingRow` minimally and say so in the report.

- [ ] **Step 3: README**

In `README.md`, under the admin screens list, replace the line `- Settings: runtime settings grouped by area.` with:

```markdown
- Settings: runtime settings grouped by area. Saving checks what a setting does on the server:
  `index.workspace_dir` and `index.maven_local_repository` must be absolute paths to a directory the server can
  create and write (a probe file is written and deleted; symlinks are followed), and `index.maven_executable` must
  answer `-v` within `index.maven_check_timeout`, run without a shell and with the indexer's reduced environment.
  A refused value is not saved and the reason appears under the field. Seeded defaults are not checked at startup.
```

In the API table, replace the `PUT | /admin/settings/{key}` row with:

```markdown
| PUT | `/admin/settings/{key}` | Set a setting from `{value}`; 400 with the reason (type, bounds, or the server-side check for the directory and Maven settings), 404 for an unknown key; changes apply without a restart (admin) |
```

- [ ] **Step 4: Run the suites**

Run: `cd frontend && npm test && npm run build`, then `./mvnw -q test -Dfrontend.skip=true` from the repo root.
Expected: all green.

- [ ] **Step 5: Commit**

```bash
git commit frontend/src/features/admin/settings/settings.test.tsx README.md \
  -m "docs(settings): document the server-side checks; test the refusal under the field" \
  -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01XpYgYKeBYFNw4tb6EwJ44V"
```

Check `git show --stat HEAD`: only the two files.
