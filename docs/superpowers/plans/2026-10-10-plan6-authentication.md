# Plan 6: Authentication, Users and Roles Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Protect the API behind sign-in. Users sign in with an application-local account first and with LDAP/Active Directory otherwise. Roles (`ADMIN`/`USER`) come from the application's own `APP_USER` table, never from directory groups. A bootstrap local `admin` exists from the first start, and admins can grant `ADMIN` to any number of directory users. The LDAP connection, users and the audit log are managed through admin APIs.

**Architecture:**
- **Login (`com.graphify.auth`).** `LoginService` checks `LOCAL_ACCOUNT` first, using bcrypt with a lockout from `auth.max_failed_attempts`/`auth.lock_duration`. Only when no local account exists does it bind against LDAP through `LdapDirectory`.
  - `LdapDirectory` builds a Spring LDAP context from the `LDAP_CONFIG` row on every call, so a configuration change applies at once. The bind password is encrypted with `SecretCipher`, and timeouts come from settings.
  - A first LDAP login provisions an `APP_USER` with role `USER`.
- **Sessions.** Spring Security keeps a server-side session (HttpOnly cookie) whose length comes from `auth.session_timeout`. CSRF uses a session-stored token: the client reads it from `GET /auth/csrf` and sends it in `X-XSRF-TOKEN`.
- **Fresh rights.** `CurrentUserRefreshFilter` reloads the signed-in user on every request. A demotion, promotion or deactivation therefore takes effect on that user's next request.
- **Authorization.** Paths are protected by role (spec §7.5). A user who must change their password can only reach `/auth/me`, `/auth/change-password` and `/auth/logout`. 401/403 answers are RFC 7807 problems.
- **Admin APIs.** They cover users (register a directory user, change role, activate or deactivate, with the last-admin rule), LDAP configuration (view, update, test, search the directory) and the audit log.

**Tech Stack:**
- Java 25, Spring Boot 4.1.1, Spring Security 7 (web, config, ldap)
- Spring LDAP, bcrypt (`spring-security-crypto`), Oracle + Flyway + JdbcTemplate
- Testcontainers, JUnit 5, AssertJ, `spring-security-test`, UnboundID in-memory LDAP server (`com.unboundid:unboundid-ldapsdk`, version from the Boot BOM)

**Verified in a spike (Boot 4.1.1):**
- **JSON login:** `SecurityContextRepository.saveContext` after `request.changeSessionId()`, called only when a session already exists.
- **Session timeout:** `session.setMaxInactiveInterval(...)` applies.
- **CSRF:** `HttpSessionCsrfTokenRepository` with header `X-XSRF-TOKEN` plus `CsrfTokenRequestAttributeHandler`. `GET /auth/csrf` returns the token, login and later POSTs send it, and the token survives `changeSessionId`.
- **LDAP:** `BindAuthenticator` + `FilterBasedLdapUserSearch` against `InMemoryDirectoryServer`.
- **401 entry point:** `HttpStatusEntryPoint`-style entry point.
- **Authorization:** role checks in `authorizeHttpRequests`.
- **Tests:** `MockMvcTester.from(context, b -> b.apply(springSecurity()).defaultRequest(get("/").with(user(..).roles(..)).with(csrf())).build())`.
- **Rejected alternative:** the cookie-based `csrf.spa()` did not issue its cookie reliably for plain GETs, so it is not used.

**Spec:** `docs/superpowers/specs/2026-10-05-impact-analyzer-design.md`, covering:
- §6.1 `APP_BOOTSTRAP_ADMIN_PASSWORD`; §6.4 secrets never returned, null keeps the stored secret, audit without secrets
- §7.1–7.5 (whole section)
- §8 API errors (401/403/409/502)
- §10.2 session endpoints; §10.6 rows "LDAP", "Kullanıcılar", "Audit"
- §11 rows "LDAP / auth" and "Güvenlik"

Plan-5 carry-over (`docs/superpowers/plans/2026-10-09-plan5-followups.md`): ADMIN role on starting and cancelling runs, and `started_by` is the signed-in user.

## Plan series (renumbered)

| Plan | Scope | Status |
|---|---|---|
| 1–5 | Indexer, persistence, search/impact, acquisition, orchestration | merged |
| **6** | **Authentication, users, roles, LDAP config, audit API** (this plan) | — |
| 7 | Remaining admin APIs: SCM connections (with `last_sync_*`), artifact repositories, settings, entry-point annotations, impact rules; plan-5 lock hardening follow-ups | next |
| 8 | Repo graph view | later |

## Global Constraints

- **Environment.**
  - JDK 25: every command runs with `export JAVA_HOME=/opt/homebrew/opt/openjdk@25`; use `./mvnw`.
  - Docker must be running.
- **Dependencies.**
  - New dependencies, all versioned by the Boot 4.1.1 BOM: `spring-security-crypto` (Task 2), `spring-security-ldap` (Task 3), `spring-boot-starter-security` (Task 5).
  - Test-only: `com.unboundid:unboundid-ldapsdk` (Task 3) and `spring-security-test` (Task 5).
  - `spring-boot-starter-security` is added only in Task 5. Its auto-configuration would lock every endpoint before the filter chain exists.
- **"Kodda sabit değer yok" (no hardcoded values in code).**
  - These settings come from `AppSettings`: `auth.session_timeout`, `auth.max_failed_attempts`, `auth.lock_duration`, `auth.password_min_length`, `auth.ldap_connect_timeout`, `auth.ldap_read_timeout`, `auth.ldap_search_max_results`, `api.page_*`.
  - The LDAP connection lives only in the `LDAP_CONFIG` row, managed through the admin API. Its seed defaults are in V6.
  - Named constants with a comment are allowed for protocol facts:
    - the bootstrap username `admin` (spec §7.3)
    - the CSRF header name
    - the JNDI timeout property names
    - the `{0}` filter placeholder
    - the bcrypt encoder
  - `APP_BOOTSTRAP_ADMIN_PASSWORD` is read through `app.bootstrap-admin-password` (spec §6.1).
- **Secrets.**
  - A password, password hash or LDAP bind password never appears in an API response, an audit row, an exception message or a log line. The bind password is stored encrypted (`SecretCipher`), and the API reports only `bindPasswordSet`.
  - **The one spec-mandated exception:** a generated bootstrap password is logged once, at WARN (spec §7.3).
- **Usernames.** They are stored lower-cased and stripped (`AppUsers.normalize`) and are unique across sources. Login is case-insensitive.
- **Login failures.** Every failure answers `401` with the same detail, "Invalid username or password", so a caller cannot tell an unknown user from a wrong password, a locked account or an inactive user. The reason goes only to the audit log (`LOGIN_FAILED`).
- **Lockout (spec §7.4).**
  - It applies to local accounts. After `auth.max_failed_attempts` consecutive failures the account is locked for `auth.lock_duration`.
  - While locked, even the correct password fails, and a success resets the counter.
  - Directory accounts are subject to the directory's own lockout policy.
- **Authorization (spec §7.5):**
  - Open to anyone: `GET /api/v1/auth/csrf`, `POST /api/v1/auth/login`, `GET /api/v1/openapi.json`.
  - Any signed-in user, including one who must change their password: `/api/v1/auth/me`, `/api/v1/auth/logout`, `/api/v1/auth/change-password`.
  - `ADMIN`: `/api/v1/admin/**`, `POST /api/v1/index/runs` and `POST /api/v1/index/runs/*/cancel`.
  - `USER` (which every active user has; an admin has both roles): everything else under `/api/**`.
  - Anything else is denied.
  - Authorities: `ROLE_ADMIN`, `ROLE_USER`, and `PASSWORD_CHANGE_REQUIRED`. A user who must change their password has only `PASSWORD_CHANGE_REQUIRED`.
- **Last admin (spec §7.4).**
  - At least one active `ADMIN` always remains. Demoting or deactivating the last one is `409`.
  - The local admin can only be deactivated while another active LDAP admin exists (`409` otherwise).
  - These checks lock the active admin rows (`SELECT ... FOR UPDATE`), so two concurrent changes cannot both pass.
- **Errors.**
  - 401/403 from the security layer are `application/problem+json` with `title`, `status`, `detail` and an optional `code`: `PASSWORD_CHANGE_REQUIRED` or `CSRF`.
  - A directory that cannot be reached is `502` (`ExternalSystemException`), with a message free of secrets.
- **Schema.** New schema goes only in `V6__authentication.sql`; V1–V5 are never edited. Text columns are `VARCHAR2(n BYTE)` and timestamps are `TIMESTAMP WITH TIME ZONE`.
- **Feature packages.**
  - `com.graphify.auth`: users, accounts, LDAP, login, security, admin endpoints for users and LDAP.
  - `com.graphify.audit`: audit queries and endpoint.
  - `com.graphify.common.exception`: `ExternalSystemException`, `LoginFailedException`.
- **Tests.**
  - Oracle-backed tests extend `OracleIntegrationTest`. From Task 5 its `mvc` is an authenticated ADMIN+USER named `tester` with a CSRF token. `anonymous()` and `as(name, roles)` give other testers.
  - Tests that create users delete them in `@AfterEach` and never touch the bootstrap `admin` unless they restore it.
  - LDAP tests use `testsupport.TestLdap`, an in-memory directory started once per JVM, and reset `ldap_config` afterwards.
- **Commits** end with the Co-Authored-By trailer the committing agent's harness provides.

## Review Focus

1. **Password guessing against a local account.** After the configured number of failures the account is locked, even the right password fails until the lock expires, and every response looks the same. Test: Task 4 `LoginServiceTest.repeatedFailuresLockTheAccountAndEveryFailureLooksTheSame`.
2. **Two admins demote each other at the same moment.** One admin always remains. Test: Task 6 `UserAdministrationTest.concurrentDemotionsNeverRemoveTheLastAdmin`.
3. **A demoted or deactivated user still has an open session.** Rights change on that user's next request, with no re-login needed. Test: Task 5 `SecurityApiTest.roleAndActiveChangesApplyToOpenSessions`.
4. **LDAP filter injection through the username or the directory search box.** Input such as `*`, `*)(uid=*` or `ayse)(|(uid=*` matches nobody else and authenticates nobody. Test: Task 3 `LdapDirectoryTest.filterMetacharactersNeverWiden`.
5. **The LDAP bind password and user passwords never leak** through API responses, audit rows or error messages. Tests: Task 4 `LoginServiceTest.auditNeverContainsPasswords`; Task 7 `LdapAdminApiTest.theBindPasswordIsNeverReturnedOrAudited`.

---

## File Structure

| File | Responsibility |
|---|---|
| `db/migration/V6__authentication.sql` | `app_user`, `local_account`, `ldap_config` (single row), 4 auth settings |
| `common/exception/ExternalSystemException.java`, `LoginFailedException.java`, `ApiExceptionHandler.java` (modify) | 502 and 401 problems |
| `auth/Role.java`, `UserSource.java`, `AppUser.java`, `AppUsers.java`, `LocalAccount.java`, `LocalAccounts.java` | User and local account persistence |
| `auth/PasswordConfiguration.java`, `PasswordPolicy.java`, `BootstrapAdmin.java` | bcrypt, minimum length, first-start admin |
| `auth/LdapSettings.java`, `DirectoryUser.java`, `LdapConfigRepository.java`, `LdapDirectory.java` | LDAP config row and directory access |
| `auth/Authorities.java`, `AppPrincipal.java`, `LoginService.java` | Login, lockout, provisioning, password change |
| `auth/SecurityConfiguration.java`, `ProblemResponses.java`, `CurrentUserRefreshFilter.java`, `AuthController.java` | Web security and session endpoints |
| `auth/UserAdministration.java`, `UserAdminController.java` | Users admin API |
| `auth/LdapConfigView.java`, `LdapConfigUpdate.java`, `LdapAdministration.java`, `LdapAdminController.java` | LDAP admin API |
| `audit/AuditEntry.java`, `AuditQueries.java`, `AuditController.java` | Audit API |
| test `testsupport/TestLdap.java`, `testsupport/AuthFixtures.java`, `testsupport/LoginFlow.java` | In-memory directory, user helpers, real login |

Main paths are under `src/main/java/com/graphify/` unless they start with `db/`, which is `src/main/resources/db/migration/`.

---

### Task 1: Schema V6 and user/account persistence

**Files:**
- Create: `src/main/resources/db/migration/V6__authentication.sql`
- Create: `src/main/java/com/graphify/auth/Role.java`, `UserSource.java`, `AppUser.java`, `AppUsers.java`, `LocalAccount.java`, `LocalAccounts.java`
- Create: `src/main/java/com/graphify/common/exception/ExternalSystemException.java`, `LoginFailedException.java`
- Modify: `src/main/java/com/graphify/common/exception/ApiExceptionHandler.java`, `src/main/java/com/graphify/settings/SettingKeys.java`
- Create (test): `src/test/java/com/graphify/testsupport/AuthFixtures.java`
- Test: `src/test/java/com/graphify/auth/AppUsersTest.java`, `LocalAccountsTest.java`; `src/test/java/com/graphify/store/SchemaMigrationTest.java` (add a test)

**Interfaces:**
- **Produces (types):**
  - `public enum Role { ADMIN, USER }` and `public enum UserSource { LOCAL, LDAP }`.
  - `public record AppUser(long id, String username, UserSource source, String displayName, String email, Role role, String roleGrantedBy, Instant roleGrantedAt, Instant lastLoginAt, boolean active)`.
  - `public record LocalAccount(String username, String passwordHash, boolean mustChangePassword, boolean enabled, int failedAttempts, Instant lockedUntil, boolean locked)`. `locked` is computed in SQL against `SYSTIMESTAMP`.
- **Produces (`AppUsers`, a `@Repository`):**
  - `static String normalize(String)` strips and lower-cases with `Locale.ROOT`; null stays null.
  - Lookup: `Optional<AppUser> find(long)`, `Optional<AppUser> findByUsername(String)`.
  - Changes: `AppUser create(String username, UserSource, String displayName, String email, Role, String grantedBy)`, `void recordLogin(long id, String displayName, String email)` (null attributes are kept), `void setRole(long id, Role, String grantedBy)`, `void setActive(long id, boolean)`.
  - Queries: `Page<AppUser> list(String query, Paging)` and `List<AppUser> lockActiveAdmins()`. The latter runs `SELECT ... FOR UPDATE` ordered by id and must be called inside a transaction.
- **Produces (`LocalAccounts`, a `@Repository`):**
  - `boolean any()` and `Optional<LocalAccount> find(String username)`.
  - `void create(String username, String passwordHash, boolean mustChangePassword)`.
  - `boolean recordFailure(String username, int maxAttempts, Duration lockDuration)` returns true when this failure locked the account.
  - `void recordSuccess(String username)`.
  - `void setPassword(String username, String passwordHash, boolean mustChangePassword)` and `void setEnabled(String username, boolean)`.
- **Produces (exceptions):** `ExternalSystemException(String)` maps to 502 with title `External system error`. `LoginFailedException()` maps to 401 with title `Unauthorized` and detail `Invalid username or password`.
- **Produces (settings):** `SettingKeys.AUTH_PASSWORD_MIN_LENGTH`, `AUTH_LDAP_CONNECT_TIMEOUT`, `AUTH_LDAP_READ_TIMEOUT`, `AUTH_LDAP_SEARCH_MAX_RESULTS`.
- **Produces (`AuthFixtures`, test only):**
  - `static void deleteUsersExcept(JdbcTemplate, String... keep)` deletes `local_account` and then `app_user` rows not in `keep`.
  - `static AppUser createLocal(AppUsers, LocalAccounts, String passwordHash, String username, Role, boolean mustChange)`.

- [ ] **Step 1: Write the failing tests**

Append to `src/test/java/com/graphify/store/SchemaMigrationTest.java`, inside the class:

```java
    @Test
    void createsTheAuthenticationSchema() {
        assertThat(jdbc.queryForList("SELECT LOWER(table_name) FROM user_tables", String.class))
                .contains("app_user", "local_account", "ldap_config");
        assertThat(jdbc.queryForObject("SELECT enabled || ':' || user_search_filter || ':' || username_attr "
                + "FROM ldap_config WHERE id = 1", String.class)).isEqualTo("0:(uid={0}):uid");
        assertThat(jdbc.queryForList("SELECT setting_key FROM app_setting", String.class)).contains(
                "auth.password_min_length", "auth.ldap_connect_timeout", "auth.ldap_read_timeout",
                "auth.ldap_search_max_results");
    }
```

`src/test/java/com/graphify/testsupport/AuthFixtures.java`:

```java
package com.graphify.testsupport;

import com.graphify.auth.AppUser;
import com.graphify.auth.AppUsers;
import com.graphify.auth.LocalAccounts;
import com.graphify.auth.Role;
import com.graphify.auth.UserSource;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Users for authentication tests; the bootstrap 'admin' is normally kept. */
public final class AuthFixtures {

    private AuthFixtures() {
    }

    public static void deleteUsersExcept(JdbcTemplate jdbc, String... keep) {
        List<String> kept = List.of(keep);
        jdbc.queryForList("SELECT username FROM app_user", String.class).stream()
                .filter(username -> !kept.contains(username))
                .forEach(username -> {
                    jdbc.update("DELETE FROM local_account WHERE username = ?", username);
                    jdbc.update("DELETE FROM app_user WHERE username = ?", username);
                });
    }

    public static AppUser createLocal(AppUsers users, LocalAccounts accounts, String passwordHash, String username,
            Role role, boolean mustChange) {
        AppUser user = users.create(username, UserSource.LOCAL, username, null, role, "test");
        accounts.create(user.username(), passwordHash, mustChange);
        return user;
    }
}
```

`src/test/java/com/graphify/auth/AppUsersTest.java`:

```java
package com.graphify.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.graphify.OracleIntegrationTest;
import com.graphify.api.Paging;
import com.graphify.testsupport.AuthFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

class AppUsersTest extends OracleIntegrationTest {

    @Autowired
    AppUsers users;

    @BeforeEach
    @AfterEach
    void clean() {
        AuthFixtures.deleteUsersExcept(jdbc, "admin");
    }

    @Test
    void storesNormalizedUniqueUsernames() {
        AppUser created = users.create("  Ayse.Yilmaz ", UserSource.LDAP, "Ayşe Yılmaz", "ayse@corp.com", Role.USER,
                "ldap-login");

        assertThat(created.username()).isEqualTo("ayse.yilmaz");
        assertThat(created.displayName()).isEqualTo("Ayşe Yılmaz");
        assertThat(created.active()).isTrue();
        assertThat(created.roleGrantedAt()).isNotNull();
        assertThat(users.findByUsername("AYSE.YILMAZ")).contains(created);
        assertThatThrownBy(() -> users.create("ayse.yilmaz", UserSource.LOCAL, null, null, Role.USER, "t"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(AppUsers.normalize(null)).isNull();
    }

    @Test
    void changesRoleActivityAndLoginAttributes() {
        AppUser user = users.create("mehmet", UserSource.LDAP, "M", null, Role.USER, "ldap-login");

        users.setRole(user.id(), Role.ADMIN, "boss");
        users.setActive(user.id(), false);
        users.recordLogin(user.id(), "Mehmet Kaya", null);

        AppUser changed = users.find(user.id()).orElseThrow();
        assertThat(changed.role()).isEqualTo(Role.ADMIN);
        assertThat(changed.roleGrantedBy()).isEqualTo("boss");
        assertThat(changed.active()).isFalse();
        assertThat(changed.displayName()).isEqualTo("Mehmet Kaya");
        assertThat(changed.email()).isNull();
        assertThat(changed.lastLoginAt()).isNotNull();
    }

    @Test
    void listsUsersFilteredByNameOrDisplayName() {
        users.create("ayse", UserSource.LDAP, "Ayşe Yılmaz", null, Role.USER, "t");
        users.create("mehmet", UserSource.LDAP, "Mehmet Kaya", null, Role.USER, "t");

        assertThat(users.list("YIL", new Paging(0, 10)).items()).extracting(AppUser::username).containsExactly("ayse");
        assertThat(users.list(null, new Paging(0, 10)).items()).extracting(AppUser::username)
                .containsExactly("ayse", "mehmet");
    }
}
```

`src/test/java/com/graphify/auth/LocalAccountsTest.java`:

```java
package com.graphify.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.testsupport.AuthFixtures;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class LocalAccountsTest extends OracleIntegrationTest {

    @Autowired
    AppUsers users;

    @Autowired
    LocalAccounts accounts;

    @BeforeEach
    void setUp() {
        AuthFixtures.deleteUsersExcept(jdbc, "admin");
        AuthFixtures.createLocal(users, accounts, "$2a$10$hash", "ops", Role.ADMIN, true);
    }

    @AfterEach
    void clean() {
        AuthFixtures.deleteUsersExcept(jdbc, "admin");
    }

    @Test
    void failuresLockTheAccountAtTheLimitAndSuccessResets() {
        assertThat(accounts.recordFailure("ops", 3, Duration.ofMinutes(15))).isFalse();
        accounts.recordSuccess("ops");
        assertThat(accounts.find("ops").orElseThrow().failedAttempts()).isZero();

        assertThat(accounts.recordFailure("ops", 2, Duration.ofMinutes(15))).isFalse();
        assertThat(accounts.recordFailure("ops", 2, Duration.ofMinutes(15))).isTrue();

        LocalAccount locked = accounts.find("ops").orElseThrow();
        assertThat(locked.locked()).isTrue();
        assertThat(locked.lockedUntil()).isNotNull();

        accounts.recordSuccess("ops");
        assertThat(accounts.find("ops").orElseThrow().locked()).isFalse();
    }

    @Test
    void anExpiredLockIsNoLongerLocked() {
        accounts.recordFailure("ops", 1, Duration.ofMillis(1));
        jdbc.update("UPDATE local_account SET locked_until = SYSTIMESTAMP - INTERVAL '1' SECOND WHERE username = 'ops'");

        assertThat(accounts.find("ops").orElseThrow().locked()).isFalse();
    }

    @Test
    void passwordsAndEnablementChange() {
        accounts.setPassword("ops", "$2a$10$other", false);
        accounts.setEnabled("ops", false);

        LocalAccount account = accounts.find("ops").orElseThrow();
        assertThat(account.passwordHash()).isEqualTo("$2a$10$other");
        assertThat(account.mustChangePassword()).isFalse();
        assertThat(account.enabled()).isFalse();
        assertThat(accounts.any()).isTrue();
        assertThat(accounts.find("nobody")).isEmpty();
    }
}
```

No `admin` row exists until Task 2 adds the bootstrap admin, so this task's list assertion expects only `ayse` and `mehmet`; Task 2 adds `"admin"` to it.

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -Dtest='SchemaMigrationTest,AppUsersTest,LocalAccountsTest'`
Expected: BUILD FAILURE: `cannot find symbol` for `AppUsers`, `LocalAccounts` and `Role`.

- [ ] **Step 3: Write the V6 migration**

`src/main/resources/db/migration/V6__authentication.sql` (UTF-8):

```sql
-- Plan 6: users and roles (spec §7.2), local accounts, the single LDAP configuration row and authentication settings.

CREATE TABLE app_user (
    id              NUMBER(19) GENERATED BY DEFAULT AS IDENTITY,
    username        VARCHAR2(200 BYTE) NOT NULL,
    source          VARCHAR2(10 BYTE)  NOT NULL,
    display_name    VARCHAR2(200 BYTE),
    email           VARCHAR2(320 BYTE),
    role            VARCHAR2(10 BYTE)  NOT NULL,
    role_granted_by VARCHAR2(200 BYTE),
    role_granted_at TIMESTAMP WITH TIME ZONE,
    last_login_at   TIMESTAMP WITH TIME ZONE,
    active          NUMBER(1) DEFAULT 1 NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_app_user PRIMARY KEY (id),
    CONSTRAINT uq_app_user_username UNIQUE (username),
    CONSTRAINT ck_app_user_source CHECK (source IN ('LOCAL', 'LDAP')),
    CONSTRAINT ck_app_user_role CHECK (role IN ('ADMIN', 'USER')),
    CONSTRAINT ck_app_user_active CHECK (active IN (0, 1)),
    CONSTRAINT ck_app_user_lower CHECK (username = LOWER(username))
);
CREATE INDEX ix_app_user_role ON app_user (role, active);

CREATE TABLE local_account (
    username             VARCHAR2(200 BYTE) NOT NULL,
    password_hash        VARCHAR2(100 BYTE) NOT NULL,
    must_change_password NUMBER(1) DEFAULT 1 NOT NULL,
    enabled              NUMBER(1) DEFAULT 1 NOT NULL,
    failed_attempts      NUMBER(5) DEFAULT 0 NOT NULL,
    locked_until         TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_local_account PRIMARY KEY (username),
    CONSTRAINT fk_local_account_user FOREIGN KEY (username) REFERENCES app_user (username),
    CONSTRAINT ck_local_account_change CHECK (must_change_password IN (0, 1)),
    CONSTRAINT ck_local_account_enabled CHECK (enabled IN (0, 1))
);

CREATE TABLE ldap_config (
    id                 NUMBER(1) DEFAULT 1 NOT NULL,
    enabled            NUMBER(1) DEFAULT 0 NOT NULL,
    url                VARCHAR2(1000 BYTE),
    base_dn            VARCHAR2(1000 BYTE),
    user_search_base   VARCHAR2(1000 BYTE),
    user_search_filter VARCHAR2(1000 BYTE) DEFAULT '(uid={0})' NOT NULL,
    user_query_filter  VARCHAR2(1000 BYTE) DEFAULT '(|(uid={0}*)(cn={0}*)(mail={0}*))' NOT NULL,
    username_attr      VARCHAR2(100 BYTE)  DEFAULT 'uid' NOT NULL,
    display_name_attr  VARCHAR2(100 BYTE)  DEFAULT 'cn' NOT NULL,
    email_attr         VARCHAR2(100 BYTE)  DEFAULT 'mail' NOT NULL,
    bind_dn            VARCHAR2(1000 BYTE),
    bind_password_enc  VARCHAR2(4000 BYTE),
    updated_by         VARCHAR2(200 BYTE),
    updated_at         TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_ldap_config PRIMARY KEY (id),
    CONSTRAINT ck_ldap_config_single CHECK (id = 1),
    CONSTRAINT ck_ldap_config_enabled CHECK (enabled IN (0, 1))
);
INSERT INTO ldap_config (id) VALUES (1);

INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('auth.password_min_length', '12', 'INT', 'Yerel hesap şifresinin en az uzunluğu', 8, 128);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('auth.ldap_connect_timeout', 'PT5S', 'DURATION', 'LDAP bağlantı zaman aşımı', NULL, NULL);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('auth.ldap_read_timeout', 'PT10S', 'DURATION', 'LDAP okuma/arama zaman aşımı', NULL, NULL);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('auth.ldap_search_max_results', '50', 'INT', 'Dizin kullanıcı aramasında dönen en fazla kayıt', 1, 1000);
```

- [ ] **Step 4: Write the types, repositories and exceptions**

Add to `SettingKeys.java`:

```java
    public static final String AUTH_PASSWORD_MIN_LENGTH = "auth.password_min_length";
    public static final String AUTH_LDAP_CONNECT_TIMEOUT = "auth.ldap_connect_timeout";
    public static final String AUTH_LDAP_READ_TIMEOUT = "auth.ldap_read_timeout";
    public static final String AUTH_LDAP_SEARCH_MAX_RESULTS = "auth.ldap_search_max_results";
```

`src/main/java/com/graphify/auth/Role.java`:

```java
package com.graphify.auth;

/** Application roles (spec §7.5); kept in APP_USER, never taken from directory groups. */
public enum Role {
    ADMIN, USER
}
```

`src/main/java/com/graphify/auth/UserSource.java`:

```java
package com.graphify.auth;

/** Where a user authenticates: an application-local account or the LDAP directory. */
public enum UserSource {
    LOCAL, LDAP
}
```

`src/main/java/com/graphify/auth/AppUser.java`:

```java
package com.graphify.auth;

import java.time.Instant;

/** A row of app_user; holds no credentials. */
public record AppUser(
        long id,
        String username,
        UserSource source,
        String displayName,
        String email,
        Role role,
        String roleGrantedBy,
        Instant roleGrantedAt,
        Instant lastLoginAt,
        boolean active) {
}
```

`src/main/java/com/graphify/auth/LocalAccount.java`:

```java
package com.graphify.auth;

import java.time.Instant;

/** A row of local_account; {@code locked} is true while locked_until lies in the future (database time). */
public record LocalAccount(
        String username,
        String passwordHash,
        boolean mustChangePassword,
        boolean enabled,
        int failedAttempts,
        Instant lockedUntil,
        boolean locked) {

    @Override
    public String toString() {
        return "LocalAccount[username=" + username + ", enabled=" + enabled + ", locked=" + locked + "]";
    }
}
```

`src/main/java/com/graphify/auth/AppUsers.java`:

```java
package com.graphify.auth;

import com.graphify.api.Page;
import com.graphify.api.Paging;
import com.graphify.common.util.Utf8;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** app_user rows (spec §7.2). Usernames are stored normalized: stripped and lower-cased. */
@Repository
public class AppUsers {

    /** Widths of app_user.display_name and app_user.email in V6__authentication.sql. */
    private static final int DISPLAY_NAME_BYTES = 200;
    private static final int EMAIL_BYTES = 320;

    private static final String SELECT = """
            SELECT id, username, source, display_name, email, role, role_granted_by, role_granted_at, last_login_at,
                   active
              FROM app_user
            """;

    private final JdbcTemplate jdbc;

    public AppUsers(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static String normalize(String username) {
        return username == null ? null : username.strip().toLowerCase(Locale.ROOT);
    }

    public Optional<AppUser> find(long id) {
        return jdbc.query(SELECT + " WHERE id = ?", (rs, row) -> map(rs), id).stream().findFirst();
    }

    public Optional<AppUser> findByUsername(String username) {
        return jdbc.query(SELECT + " WHERE username = ?", (rs, row) -> map(rs), normalize(username)).stream()
                .findFirst();
    }

    public AppUser create(String username, UserSource source, String displayName, String email, Role role,
            String grantedBy) {
        String normalized = normalize(username);
        jdbc.update("""
                INSERT INTO app_user (username, source, display_name, email, role, role_granted_by, role_granted_at)
                VALUES (?, ?, ?, ?, ?, ?, SYSTIMESTAMP)
                """, normalized, source.name(), cut(displayName, DISPLAY_NAME_BYTES), cut(email, EMAIL_BYTES),
                role.name(), grantedBy);
        return findByUsername(normalized).orElseThrow();
    }

    /** Stamps a successful login; non-null directory attributes replace the stored ones. */
    public void recordLogin(long id, String displayName, String email) {
        jdbc.update("""
                UPDATE app_user SET last_login_at = SYSTIMESTAMP, display_name = NVL(?, display_name),
                                    email = NVL(?, email)
                 WHERE id = ?
                """, cut(displayName, DISPLAY_NAME_BYTES), cut(email, EMAIL_BYTES), id);
    }

    public void setRole(long id, Role role, String grantedBy) {
        jdbc.update("UPDATE app_user SET role = ?, role_granted_by = ?, role_granted_at = SYSTIMESTAMP WHERE id = ?",
                role.name(), grantedBy, id);
    }

    public void setActive(long id, boolean active) {
        jdbc.update("UPDATE app_user SET active = ? WHERE id = ?", active ? 1 : 0, id);
    }

    /** Users whose username or display name contains {@code query} (case-insensitive), ordered by username. */
    public Page<AppUser> list(String query, Paging paging) {
        String pattern = query == null || query.isBlank() ? "%"
                : "%" + query.strip().toUpperCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%")
                        .replace("_", "\\_") + "%";
        String where = " WHERE UPPER(username) LIKE ? ESCAPE '\\' OR UPPER(display_name) LIKE ? ESCAPE '\\'";
        List<AppUser> items = jdbc.query(SELECT + where + " ORDER BY username OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                (rs, row) -> map(rs), pattern, pattern, paging.offset(), paging.size());
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM app_user" + where, Long.class, pattern, pattern);
        return new Page<>(items, paging.page(), paging.size(), total == null ? 0 : total);
    }

    /** Locks every active admin row (spec §7.4 last-admin rule); call inside a transaction. */
    public List<AppUser> lockActiveAdmins() {
        return jdbc.query(SELECT + " WHERE role = 'ADMIN' AND active = 1 ORDER BY id FOR UPDATE", (rs, row) -> map(rs));
    }

    private static String cut(String text, int maxBytes) {
        return text == null ? null : Utf8.truncateToBytes(text, maxBytes);
    }

    private static AppUser map(ResultSet rs) throws SQLException {
        return new AppUser(rs.getLong("id"), rs.getString("username"), UserSource.valueOf(rs.getString("source")),
                rs.getString("display_name"), rs.getString("email"), Role.valueOf(rs.getString("role")),
                rs.getString("role_granted_by"), instant(rs, "role_granted_at"), instant(rs, "last_login_at"),
                rs.getInt("active") == 1);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
```

`src/main/java/com/graphify/auth/LocalAccounts.java`:

```java
package com.graphify.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** local_account rows: bcrypt hashes and the lockout counter (spec §7.2, §7.4). All time checks use database time. */
@Repository
public class LocalAccounts {

    private final JdbcTemplate jdbc;

    public LocalAccounts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean any() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM local_account", Integer.class);
        return count != null && count > 0;
    }

    public Optional<LocalAccount> find(String username) {
        return jdbc.query("""
                SELECT username, password_hash, must_change_password, enabled, failed_attempts, locked_until,
                       CASE WHEN locked_until > SYSTIMESTAMP THEN 1 ELSE 0 END AS locked
                  FROM local_account WHERE username = ?
                """, (rs, row) -> map(rs), AppUsers.normalize(username)).stream().findFirst();
    }

    public void create(String username, String passwordHash, boolean mustChangePassword) {
        jdbc.update("INSERT INTO local_account (username, password_hash, must_change_password) VALUES (?, ?, ?)",
                AppUsers.normalize(username), passwordHash, mustChangePassword ? 1 : 0);
    }

    /** Counts a failed password; at {@code maxAttempts} consecutive failures the account locks for {@code lockDuration}. */
    public boolean recordFailure(String username, int maxAttempts, Duration lockDuration) {
        String normalized = AppUsers.normalize(username);
        jdbc.update("""
                UPDATE local_account
                   SET failed_attempts = failed_attempts + 1,
                       locked_until = CASE WHEN failed_attempts + 1 >= ?
                                           THEN SYSTIMESTAMP + NUMTODSINTERVAL(?, 'SECOND') ELSE locked_until END
                 WHERE username = ?
                """, maxAttempts, lockDuration.toMillis() / 1000.0, normalized);
        return find(normalized).map(LocalAccount::locked).orElse(false);
    }

    public void recordSuccess(String username) {
        jdbc.update("UPDATE local_account SET failed_attempts = 0, locked_until = NULL WHERE username = ?",
                AppUsers.normalize(username));
    }

    public void setPassword(String username, String passwordHash, boolean mustChangePassword) {
        jdbc.update("UPDATE local_account SET password_hash = ?, must_change_password = ? WHERE username = ?",
                passwordHash, mustChangePassword ? 1 : 0, AppUsers.normalize(username));
    }

    public void setEnabled(String username, boolean enabled) {
        jdbc.update("UPDATE local_account SET enabled = ? WHERE username = ?", enabled ? 1 : 0,
                AppUsers.normalize(username));
    }

    private static LocalAccount map(ResultSet rs) throws SQLException {
        OffsetDateTime lockedUntil = rs.getObject("locked_until", OffsetDateTime.class);
        return new LocalAccount(rs.getString("username"), rs.getString("password_hash"),
                rs.getInt("must_change_password") == 1, rs.getInt("enabled") == 1, rs.getInt("failed_attempts"),
                lockedUntil == null ? null : lockedUntil.toInstant(), rs.getInt("locked") == 1);
    }
}
```

`recordFailure` locks when `failed_attempts + 1 >= maxAttempts` is evaluated with the pre-update value (Oracle evaluates the `SET` expressions against the old row), so the Nth failure locks.

`src/main/java/com/graphify/common/exception/ExternalSystemException.java`:

```java
package com.graphify.common.exception;

/** A system we depend on (LDAP, SCM) failed or could not be reached (spec §8: 502); the message holds no secrets. */
public class ExternalSystemException extends RuntimeException {

    public ExternalSystemException(String message) {
        super(message);
    }
}
```

`src/main/java/com/graphify/common/exception/LoginFailedException.java`:

```java
package com.graphify.common.exception;

/**
 * A login was refused. The message is the same for every reason (unknown user, wrong password, locked, inactive)
 * so the response never tells which; the reason is audited instead.
 */
public class LoginFailedException extends RuntimeException {

    public static final String MESSAGE = "Invalid username or password";

    public LoginFailedException() {
        super(MESSAGE);
    }
}
```

Add to `ApiExceptionHandler.java`:

```java
    @ExceptionHandler(LoginFailedException.class)
    ProblemDetail loginFailed(LoginFailedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
        problem.setTitle("Unauthorized");
        return problem;
    }

    @ExceptionHandler(ExternalSystemException.class)
    ProblemDetail externalSystem(ExternalSystemException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
        problem.setTitle("External system error");
        return problem;
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw test -Dtest='SchemaMigrationTest,AppUsersTest,LocalAccountsTest,AppSettingsTest'`
Expected: all pass.

Run: `./mvnw test`
Expected: all tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main src/test
git commit -m "feat(auth): add users, local accounts and the LDAP configuration schema" -m "<your harness Co-Authored-By trailer>"
```

---

### Task 2: Passwords and the bootstrap admin

**Files:**
- Modify: `pom.xml` (add `spring-security-crypto`)
- Modify: `src/main/resources/application.yml`, `src/test/resources/config/application.yml`
- Create: `src/main/java/com/graphify/auth/PasswordConfiguration.java`, `PasswordPolicy.java`, `BootstrapAdmin.java`
- Modify: `src/test/java/com/graphify/auth/AppUsersTest.java` (the list assertion now includes `admin`)
- Test: `src/test/java/com/graphify/auth/BootstrapAdminTest.java`, `PasswordPolicyTest.java`

**Interfaces:**
- **Consumes:** `AppUsers`, `LocalAccounts`, `Role`, `UserSource` (Task 1), `AuditLog`, `AppSettings`, `InvalidRequestException`.
- **Produces:**
  - A `PasswordEncoder` bean (bcrypt) from `PasswordConfiguration`.
  - `PasswordPolicy` (a `@Component`) with `void check(String newPassword)`. It raises `InvalidRequestException` when the password is null or shorter than `auth.password_min_length`.
  - `BootstrapAdmin` (a `@Component` that implements `SmartInitializingSingleton`):
    - `static final String USERNAME = "admin"` and `static final String ACTOR = "bootstrap"`.
    - `Optional<String> ensureAdmin()` returns the generated password when it generated one, and empty otherwise.
    - `afterSingletonsInstantiated()` calls `ensureAdmin()`.
  - Bootstrap rules:
    - When `local_account` has no row and no `app_user` named `admin` exists, it creates `app_user` `admin` (`LOCAL`, `ADMIN`, granted by `bootstrap`) and a `local_account` with `must_change_password = 1`.
    - The password is `app.bootstrap-admin-password` when set, which comes from `${APP_BOOTSTRAP_ADMIN_PASSWORD:}`. Otherwise it is 24 random URL-safe characters from `SecureRandom`, logged once at WARN.
    - It audits `BOOTSTRAP_ADMIN_CREATED` without the password.
  - Tests set `app.bootstrap-admin-password: Bootstrap-Test-Password-1`.

- [ ] **Step 1: Add the dependency and properties**

`pom.xml`:

```xml
		<dependency>
			<groupId>org.springframework.security</groupId>
			<artifactId>spring-security-crypto</artifactId>
		</dependency>
```

In `src/main/resources/application.yml`, under `app:`, add:

```yaml
  bootstrap-admin-password: ${APP_BOOTSTRAP_ADMIN_PASSWORD:}
```

In `src/test/resources/config/application.yml`, under `app:`, add:

```yaml
  bootstrap-admin-password: Bootstrap-Test-Password-1
```

- [ ] **Step 2: Write the failing tests**

`src/test/java/com/graphify/auth/BootstrapAdminTest.java`:

```java
package com.graphify.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.audit.AuditLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(OutputCaptureExtension.class)
class BootstrapAdminTest extends OracleIntegrationTest {

    private static final String CONFIGURED = "Bootstrap-Test-Password-1";

    @Autowired
    BootstrapAdmin bootstrap;

    @Autowired
    AppUsers users;

    @Autowired
    LocalAccounts accounts;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    AuditLog auditLog;

    @AfterEach
    void restoreConfiguredAdmin() {
        removeAdmin();
        bootstrap.ensureAdmin();
    }

    /** Removes every local account (and its user), so ensureAdmin sees a first start. */
    private void removeAdmin() {
        jdbc.update("DELETE FROM local_account");
        jdbc.update("DELETE FROM app_user WHERE source = 'LOCAL'");
    }

    @Test
    void theFirstStartCreatesALocalAdminThatMustChangeItsPassword() {
        AppUser admin = users.findByUsername(BootstrapAdmin.USERNAME).orElseThrow();
        LocalAccount account = accounts.find(BootstrapAdmin.USERNAME).orElseThrow();

        assertThat(admin.role()).isEqualTo(Role.ADMIN);
        assertThat(admin.source()).isEqualTo(UserSource.LOCAL);
        assertThat(admin.roleGrantedBy()).isEqualTo(BootstrapAdmin.ACTOR);
        assertThat(account.mustChangePassword()).isTrue();
        assertThat(encoder.matches(CONFIGURED, account.passwordHash())).isTrue();
        assertThat(bootstrap.ensureAdmin()).isEmpty();
    }

    @Test
    void withoutAConfiguredPasswordOneIsGeneratedAndLoggedOnce(CapturedOutput output) {
        removeAdmin();
        BootstrapAdmin unconfigured = new BootstrapAdmin("", users, accounts, encoder, auditLog);

        String generated = unconfigured.ensureAdmin().orElseThrow();

        assertThat(generated).hasSize(24);
        assertThat(encoder.matches(generated, accounts.find("admin").orElseThrow().passwordHash())).isTrue();
        assertThat(output.getOut() + output.getErr()).containsOnlyOnce(generated);
        assertThat(unconfigured.ensureAdmin()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'BOOTSTRAP_ADMIN_CREATED' "
                + "AND (details LIKE ? OR target LIKE ?)", Integer.class, "%" + generated + "%", "%" + generated + "%"))
                .isZero();
    }
}
```

`src/test/java/com/graphify/auth/PasswordPolicyTest.java`:

```java
package com.graphify.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.graphify.OracleIntegrationTest;
import com.graphify.common.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PasswordPolicyTest extends OracleIntegrationTest {

    @Autowired
    PasswordPolicy policy;

    @Test
    void enforcesTheConfiguredMinimumLength() {
        assertThatThrownBy(() -> policy.check("short")).isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("12");
        assertThatThrownBy(() -> policy.check(null)).isInstanceOf(InvalidRequestException.class);
        assertThatCode(() -> policy.check("long-enough-password")).doesNotThrowAnyException();
    }
}
```

In `AppUsersTest.listsUsersFilteredByNameOrDisplayName`, change the second assertion to `.containsExactly("admin", "ayse", "mehmet")`.

- [ ] **Step 3: Run them to verify they fail**

Run: `./mvnw test -Dtest='BootstrapAdminTest,PasswordPolicyTest,AppUsersTest'`
Expected: BUILD FAILURE: `cannot find symbol` for `BootstrapAdmin` and `PasswordPolicy`.

- [ ] **Step 4: Implement**

`src/main/java/com/graphify/auth/PasswordConfiguration.java`:

```java
package com.graphify.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
class PasswordConfiguration {

    /** bcrypt with its default cost (spec §7.2: password_hash is bcrypt). */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

`src/main/java/com/graphify/auth/PasswordPolicy.java`:

```java
package com.graphify.auth;

import com.graphify.common.exception.InvalidRequestException;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import org.springframework.stereotype.Component;

/** Rules a new local-account password must meet; the minimum length comes from auth.password_min_length. */
@Component
public class PasswordPolicy {

    private final AppSettings settings;

    public PasswordPolicy(AppSettings settings) {
        this.settings = settings;
    }

    public void check(String newPassword) {
        int minimum = settings.getInt(SettingKeys.AUTH_PASSWORD_MIN_LENGTH);
        if (newPassword == null || newPassword.length() < minimum) {
            throw new InvalidRequestException("The new password must be at least " + minimum + " characters long");
        }
    }
}
```

`src/main/java/com/graphify/auth/BootstrapAdmin.java`:

```java
package com.graphify.auth;

import com.graphify.audit.AuditLog;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spec §7.3 first start: when no local account exists, creates the local {@code admin} with the ADMIN role and a
 * password that must be changed at first login. The password comes from APP_BOOTSTRAP_ADMIN_PASSWORD, or is generated
 * and written to the log exactly once (the spec's one sanctioned secret in a log).
 */
@Component
public class BootstrapAdmin implements SmartInitializingSingleton {

    /** Username of the emergency local admin (spec §7.3). */
    public static final String USERNAME = "admin";

    /** role_granted_by / audit actor of the bootstrap step. */
    public static final String ACTOR = "bootstrap";

    /** Random bytes behind a generated password; 18 bytes are 24 URL-safe Base64 characters. */
    private static final int GENERATED_BYTES = 18;

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);

    private final String configuredPassword;
    private final AppUsers users;
    private final LocalAccounts accounts;
    private final PasswordEncoder encoder;
    private final AuditLog auditLog;

    @Autowired
    public BootstrapAdmin(@Value("${app.bootstrap-admin-password:}") String configuredPassword, AppUsers users,
            LocalAccounts accounts, PasswordEncoder encoder, AuditLog auditLog) {
        this.configuredPassword = configuredPassword;
        this.users = users;
        this.accounts = accounts;
        this.encoder = encoder;
        this.auditLog = auditLog;
    }

    @Override
    public void afterSingletonsInstantiated() {
        ensureAdmin();
    }

    /** Creates the admin when needed; returns the password only when it was generated here. */
    @Transactional
    public Optional<String> ensureAdmin() {
        if (accounts.any()) {
            return Optional.empty();
        }
        if (users.findByUsername(USERNAME).isPresent()) {
            log.warn("No local account exists, but a user named '{}' does; no bootstrap admin was created", USERNAME);
            return Optional.empty();
        }
        boolean generate = configuredPassword == null || configuredPassword.isBlank();
        String password = generate ? generatePassword() : configuredPassword;
        users.create(USERNAME, UserSource.LOCAL, "Administrator", null, Role.ADMIN, ACTOR);
        accounts.create(USERNAME, encoder.encode(password), true);
        auditLog.record(ACTOR, "BOOTSTRAP_ADMIN_CREATED", USERNAME, generate ? "password generated" : "password from "
                + "APP_BOOTSTRAP_ADMIN_PASSWORD");
        if (generate) {
            log.warn("Created the local admin account '{}' with the one-time password: {} (it must be changed at the "
                    + "first login)", USERNAME, password);
            return Optional.of(password);
        }
        log.info("Created the local admin account '{}' with the password from APP_BOOTSTRAP_ADMIN_PASSWORD", USERNAME);
        return Optional.empty();
    }

    private static String generatePassword() {
        byte[] bytes = new byte[GENERATED_BYTES];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
```

`BootstrapAdminTest` constructs `BootstrapAdmin` directly, so `@Transactional` does not apply there. Both inserts run in autocommit, which is fine for a test. Through the bean the two inserts are atomic.

`SmartInitializingSingleton` runs after every singleton exists, and therefore after Flyway has migrated, but before the web server accepts requests.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw test -Dtest='BootstrapAdminTest,PasswordPolicyTest,AppUsersTest,LocalAccountsTest'`
Expected: all pass.

Run: `./mvnw test`
Expected: all tests pass.

- [ ] **Step 6: Commit**

```bash
git add pom.xml src/main src/test
git commit -m "feat(auth): create the bootstrap admin and enforce the password policy" -m "<your harness Co-Authored-By trailer>"
```

---

### Task 3: LDAP directory access

**Files:**
- Modify: `pom.xml` (add `spring-security-ldap`; test `com.unboundid:unboundid-ldapsdk`)
- Create: `src/main/java/com/graphify/auth/LdapSettings.java`, `DirectoryUser.java`, `LdapConfigRepository.java`, `LdapDirectory.java`
- Create (test): `src/test/java/com/graphify/testsupport/TestLdap.java`
- Test: `src/test/java/com/graphify/auth/LdapDirectoryTest.java`

**Interfaces:**
- **Consumes:** `SecretCipher`, `AppSettings` (`AUTH_LDAP_*`), `AppUsers.normalize`, `ExternalSystemException`, `ConflictException`, `UrlMasking`.
- **Produces (types):**
  - `public record LdapSettings(boolean enabled, String url, String baseDn, String userSearchBase, String userSearchFilter, String userQueryFilter, String usernameAttr, String displayNameAttr, String emailAttr, String bindDn, String bindPassword)`. Its `toString()` leaves out `bindPassword`.
  - `public record DirectoryUser(String username, String displayName, String email)`, with `username` normalized.
  - `LdapConfigRepository` (a `@Repository`) with `LdapSettings load()`, which decrypts the bind password.
- **Produces (`LdapDirectory`, a `@Component`), one `LdapContextSource` per call built from `load()`:**
  - `boolean enabled()`.
  - `Optional<DirectoryUser> authenticate(String username, String password)`: empty means rejected. It raises `ExternalSystemException` when the directory cannot be used and `ConflictException` when LDAP is disabled.
  - `Optional<DirectoryUser> find(String username)`: looks the user up with `user_search_filter`.
  - `List<DirectoryUser> search(String query)`: uses `user_query_filter`, at most `auth.ldap_search_max_results` results, sorted by username. Blank input returns an empty list.
  - `void test(LdapSettings settings)`: binds with the given settings and raises `ExternalSystemException` on failure.
  - User input is always LDAP-filter-encoded (`LdapEncoder.filterEncode`) before it replaces `{0}`. The username used to authenticate goes through `FilterBasedLdapUserSearch`, which passes it as an encoded JNDI filter argument.
  - The connect and read timeouts come from `auth.ldap_connect_timeout` and `auth.ldap_read_timeout`.
- **Produces (`TestLdap`, test only):**
  - A JVM-wide in-memory directory with base `dc=corp,dc=com`, people under `ou=people`, and bind account `cn=reader,dc=corp,dc=com` / `reader-pw`.
  - Users:
    - `ayse` (`Ayşe Yılmaz`, `ayse@corp.com`, `ayse-secret`)
    - `mehmet` (`Mehmet Kaya`, `mehmet@corp.com`, `mehmet-secret`)
    - `admin` (`Directory Admin`, `ldap-admin-secret`)
  - Methods: `static String url()`, `static LdapSettings settings()`, `static void configure(JdbcTemplate, SecretCipher)` (enables `ldap_config` against it) and `static void disable(JdbcTemplate)` (restores the V6 defaults).

- [ ] **Step 1: Add the dependencies**

`pom.xml`:

```xml
		<dependency>
			<groupId>org.springframework.security</groupId>
			<artifactId>spring-security-ldap</artifactId>
		</dependency>
		<dependency>
			<groupId>com.unboundid</groupId>
			<artifactId>unboundid-ldapsdk</artifactId>
			<scope>test</scope>
		</dependency>
```

`spring-security-ldap` brings `spring-security-core` and `spring-ldap-core`, but not the Boot security auto-configuration. That arrives with `spring-boot-starter-security` in Task 5, so endpoints stay open until then.

- [ ] **Step 2: Write the test directory and the failing test**

`src/test/java/com/graphify/testsupport/TestLdap.java`:

```java
package com.graphify.testsupport;

import com.graphify.auth.LdapSettings;
import com.graphify.common.crypto.SecretCipher;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.sdk.LDAPException;
import org.springframework.jdbc.core.JdbcTemplate;

/** One in-memory LDAP directory per test JVM, started on first use on a free port. */
public final class TestLdap {

    public static final String BASE_DN = "dc=corp,dc=com";
    public static final String BIND_DN = "cn=reader,dc=corp,dc=com";
    public static final String BIND_PASSWORD = "reader-pw";

    private static InMemoryDirectoryServer server;

    private TestLdap() {
    }

    public static synchronized String url() {
        if (server == null) {
            try {
                InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE_DN);
                config.addAdditionalBindCredentials(BIND_DN, BIND_PASSWORD);
                config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("default", 0));
                InMemoryDirectoryServer started = new InMemoryDirectoryServer(config);
                started.add("dn: dc=corp,dc=com", "objectClass: top", "objectClass: domain", "dc: corp");
                started.add("dn: ou=people,dc=corp,dc=com", "objectClass: organizationalUnit", "ou: people");
                person(started, "ayse", "Ayşe Yılmaz", "Yılmaz", "ayse@corp.com", "ayse-secret");
                person(started, "mehmet", "Mehmet Kaya", "Kaya", "mehmet@corp.com", "mehmet-secret");
                person(started, "admin", "Directory Admin", "Admin", "dir-admin@corp.com", "ldap-admin-secret");
                started.startListening();
                server = started;
            } catch (LDAPException e) {
                throw new IllegalStateException(e);
            }
        }
        return "ldap://localhost:" + server.getListenPort();
    }

    private static void person(InMemoryDirectoryServer target, String uid, String cn, String sn, String mail,
            String password) throws LDAPException {
        target.add("dn: uid=" + uid + ",ou=people,dc=corp,dc=com", "objectClass: inetOrgPerson", "uid: " + uid,
                "cn: " + cn, "sn: " + sn, "mail: " + mail, "userPassword: " + password);
    }

    public static LdapSettings settings() {
        return new LdapSettings(true, url(), BASE_DN, "ou=people", "(uid={0})", "(|(uid={0}*)(cn={0}*)(mail={0}*))",
                "uid", "cn", "mail", BIND_DN, BIND_PASSWORD);
    }

    public static void configure(JdbcTemplate jdbc, SecretCipher cipher) {
        jdbc.update("""
                UPDATE ldap_config SET enabled = 1, url = ?, base_dn = ?, user_search_base = 'ou=people',
                       bind_dn = ?, bind_password_enc = ? WHERE id = 1
                """, url(), BASE_DN, BIND_DN, cipher.encrypt(BIND_PASSWORD));
    }

    public static void disable(JdbcTemplate jdbc) {
        jdbc.update("""
                UPDATE ldap_config SET enabled = 0, url = NULL, base_dn = NULL, user_search_base = NULL,
                       user_search_filter = '(uid={0})', user_query_filter = '(|(uid={0}*)(cn={0}*)(mail={0}*))',
                       username_attr = 'uid', display_name_attr = 'cn', email_attr = 'mail', bind_dn = NULL,
                       bind_password_enc = NULL, updated_by = NULL, updated_at = NULL
                 WHERE id = 1
                """);
    }
}
```

`src/test/java/com/graphify/auth/LdapDirectoryTest.java`:

```java
package com.graphify.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.graphify.OracleIntegrationTest;
import com.graphify.common.crypto.SecretCipher;
import com.graphify.common.exception.ConflictException;
import com.graphify.common.exception.ExternalSystemException;
import com.graphify.testsupport.TestLdap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class LdapDirectoryTest extends OracleIntegrationTest {

    @Autowired
    LdapDirectory directory;

    @Autowired
    LdapConfigRepository config;

    @Autowired
    SecretCipher cipher;

    @BeforeEach
    void setUp() {
        TestLdap.configure(jdbc, cipher);
    }

    @AfterEach
    void tearDown() {
        TestLdap.disable(jdbc);
    }

    @Test
    void loadsTheConfigurationWithTheDecryptedBindPassword() {
        LdapSettings loaded = config.load();

        assertThat(loaded.enabled()).isTrue();
        assertThat(loaded.bindPassword()).isEqualTo(TestLdap.BIND_PASSWORD);
        assertThat(loaded.toString()).doesNotContain(TestLdap.BIND_PASSWORD);
    }

    @Test
    void authenticatesWithTheDirectoryPassword() {
        assertThat(directory.authenticate("Ayse", "ayse-secret"))
                .contains(new DirectoryUser("ayse", "Ayşe Yılmaz", "ayse@corp.com"));
        assertThat(directory.authenticate("ayse", "wrong")).isEmpty();
        assertThat(directory.authenticate("nobody", "x")).isEmpty();
        assertThat(directory.authenticate("ayse", "")).isEmpty();
    }

    @Test
    void searchesAndFindsUsers() {
        assertThat(directory.search("me")).extracting(DirectoryUser::username).containsExactly("mehmet");
        assertThat(directory.search("Ay")).extracting(DirectoryUser::displayName).containsExactly("Ayşe Yılmaz");
        assertThat(directory.search(" ")).isEmpty();
        assertThat(directory.find("MEHMET")).contains(new DirectoryUser("mehmet", "Mehmet Kaya", "mehmet@corp.com"));
        assertThat(directory.find("ghost")).isEmpty();
    }

    @Test
    void filterMetacharactersNeverWiden() {
        assertThat(directory.search("*")).isEmpty();
        assertThat(directory.search("*)(uid=*")).isEmpty();
        assertThat(directory.find("*")).isEmpty();
        assertThat(directory.authenticate("*", "ayse-secret")).isEmpty();
        assertThat(directory.authenticate("ayse)(|(uid=*", "ayse-secret")).isEmpty();
    }

    @Test
    void anUnreachableDirectoryIsAnExternalFailureWithoutSecrets() {
        jdbc.update("UPDATE ldap_config SET url = 'ldap://localhost:1' WHERE id = 1");

        assertThatThrownBy(() -> directory.authenticate("ayse", "ayse-secret"))
                .isInstanceOf(ExternalSystemException.class)
                .hasMessageNotContaining(TestLdap.BIND_PASSWORD)
                .hasMessageNotContaining("ayse-secret");
        assertThatThrownBy(() -> directory.test(new LdapSettings(true, "ldap://localhost:1", TestLdap.BASE_DN,
                "ou=people", "(uid={0})", "(uid={0}*)", "uid", "cn", "mail", TestLdap.BIND_DN, "x")))
                .isInstanceOf(ExternalSystemException.class);
    }

    @Test
    void testingChecksTheBindAccount() {
        directory.test(TestLdap.settings());

        LdapSettings wrongPassword = new LdapSettings(true, TestLdap.url(), TestLdap.BASE_DN, "ou=people",
                "(uid={0})", "(uid={0}*)", "uid", "cn", "mail", TestLdap.BIND_DN, "nope");
        assertThatThrownBy(() -> directory.test(wrongPassword)).isInstanceOf(ExternalSystemException.class)
                .hasMessageNotContaining("nope");
    }

    @Test
    void aDisabledDirectoryCannotBeUsed() {
        TestLdap.disable(jdbc);

        assertThat(directory.enabled()).isFalse();
        assertThatThrownBy(() -> directory.search("ay")).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> directory.authenticate("ayse", "ayse-secret")).isInstanceOf(ConflictException.class);
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `./mvnw test -Dtest=LdapDirectoryTest`
Expected: BUILD FAILURE: `cannot find symbol` for `LdapDirectory`, `LdapSettings`, `DirectoryUser` and `LdapConfigRepository`.

- [ ] **Step 4: Implement**

`src/main/java/com/graphify/auth/LdapSettings.java`:

```java
package com.graphify.auth;

/**
 * The LDAP connection (ldap_config). {@code userSearchBase} is relative to {@code baseDn}; the filters contain the
 * placeholder {0}. toString never shows the bind password.
 */
public record LdapSettings(
        boolean enabled,
        String url,
        String baseDn,
        String userSearchBase,
        String userSearchFilter,
        String userQueryFilter,
        String usernameAttr,
        String displayNameAttr,
        String emailAttr,
        String bindDn,
        String bindPassword) {

    @Override
    public String toString() {
        return "LdapSettings[enabled=" + enabled + ", url=" + url + ", baseDn=" + baseDn + ", bindDn=" + bindDn + "]";
    }
}
```

`src/main/java/com/graphify/auth/DirectoryUser.java`:

```java
package com.graphify.auth;

/** A person as the directory describes them; {@code username} is normalized. */
public record DirectoryUser(String username, String displayName, String email) {
}
```

`src/main/java/com/graphify/auth/LdapConfigRepository.java`:

```java
package com.graphify.auth;

import com.graphify.common.crypto.SecretCipher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The single ldap_config row (spec §7.2); the bind password is stored encrypted. */
@Repository
public class LdapConfigRepository {

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;

    public LdapConfigRepository(JdbcTemplate jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    public LdapSettings load() {
        return jdbc.queryForObject("""
                SELECT enabled, url, base_dn, user_search_base, user_search_filter, user_query_filter, username_attr,
                       display_name_attr, email_attr, bind_dn, bind_password_enc
                  FROM ldap_config WHERE id = 1
                """, (rs, row) -> {
                    String secret = rs.getString("bind_password_enc");
                    return new LdapSettings(rs.getInt("enabled") == 1, rs.getString("url"), rs.getString("base_dn"),
                            rs.getString("user_search_base"), rs.getString("user_search_filter"),
                            rs.getString("user_query_filter"), rs.getString("username_attr"),
                            rs.getString("display_name_attr"), rs.getString("email_attr"), rs.getString("bind_dn"),
                            secret == null ? null : cipher.decrypt(secret));
                });
    }
}
```

`src/main/java/com/graphify/auth/LdapDirectory.java`:

```java
package com.graphify.auth;

import com.graphify.common.exception.ConflictException;
import com.graphify.common.exception.ExternalSystemException;
import com.graphify.scm.UrlMasking;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchControls;
import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.ldap.support.LdapEncoder;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.ldap.authentication.BindAuthenticator;
import org.springframework.security.ldap.search.FilterBasedLdapUserSearch;
import org.springframework.stereotype.Component;

/**
 * Talks to the LDAP/Active Directory configured in ldap_config (spec §7.1). Every call builds its context from the
 * current row, so a configuration change applies at once. User input is always filter-encoded.
 */
@Component
public class LdapDirectory {

    /** JNDI LDAP provider properties for the connect and read timeouts, in milliseconds. */
    private static final String CONNECT_TIMEOUT = "com.sun.jndi.ldap.connect.timeout";
    private static final String READ_TIMEOUT = "com.sun.jndi.ldap.read.timeout";

    /** Placeholder for the (encoded) user input in the configured filters. */
    private static final String PLACEHOLDER = "{0}";

    private final LdapConfigRepository config;
    private final AppSettings settings;

    public LdapDirectory(LdapConfigRepository config, AppSettings settings) {
        this.config = config;
        this.settings = settings;
    }

    public boolean enabled() {
        return config.load().enabled();
    }

    public Optional<DirectoryUser> authenticate(String username, String password) {
        LdapSettings ldap = requireEnabled();
        if (password == null || password.isEmpty()) {
            return Optional.empty(); // an empty password would be an anonymous bind
        }
        LdapContextSource source = contextSource(ldap);
        BindAuthenticator authenticator = new BindAuthenticator(source);
        authenticator.setUserSearch(new FilterBasedLdapUserSearch(searchBase(ldap), ldap.userSearchFilter(), source));
        try {
            DirContextOperations entry = authenticator.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(username, password));
            String found = entry.getStringAttribute(ldap.usernameAttr());
            return Optional.of(new DirectoryUser(AppUsers.normalize(found == null ? username : found),
                    entry.getStringAttribute(ldap.displayNameAttr()), entry.getStringAttribute(ldap.emailAttr())));
        } catch (BadCredentialsException | UsernameNotFoundException e) {
            return Optional.empty();
        } catch (RuntimeException e) {
            throw unusable(e);
        }
    }

    public Optional<DirectoryUser> find(String username) {
        LdapSettings ldap = requireEnabled();
        String filter = ldap.userSearchFilter().replace(PLACEHOLDER, LdapEncoder.filterEncode(AppUsers.normalize(username)));
        return query(ldap, filter, 1).stream().findFirst();
    }

    public List<DirectoryUser> search(String query) {
        LdapSettings ldap = requireEnabled();
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String filter = ldap.userQueryFilter().replace(PLACEHOLDER, LdapEncoder.filterEncode(query.strip()));
        return query(ldap, filter, settings.getInt(SettingKeys.AUTH_LDAP_SEARCH_MAX_RESULTS));
    }

    /** Binds with the given settings (the bind account, or anonymously); throws when the directory is unusable. */
    public void test(LdapSettings ldap) {
        try {
            contextSource(ldap).getReadOnlyContext().close();
        } catch (javax.naming.NamingException | RuntimeException e) {
            throw unusable(e);
        }
    }

    private List<DirectoryUser> query(LdapSettings ldap, String filter, int limit) {
        LdapTemplate template = new LdapTemplate(contextSource(ldap));
        template.setIgnoreSizeLimitExceededException(true);
        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        controls.setCountLimit(limit);
        controls.setTimeLimit((int) settings.getDuration(SettingKeys.AUTH_LDAP_READ_TIMEOUT).toMillis());
        controls.setReturningAttributes(new String[] {ldap.usernameAttr(), ldap.displayNameAttr(), ldap.emailAttr()});
        try {
            return template.search(searchBase(ldap), filter, controls, (AttributesMapper<DirectoryUser>) attributes ->
                            new DirectoryUser(AppUsers.normalize(text(attributes, ldap.usernameAttr())),
                                    text(attributes, ldap.displayNameAttr()), text(attributes, ldap.emailAttr())))
                    .stream().filter(user -> user.username() != null)
                    .sorted(Comparator.comparing(DirectoryUser::username)).toList();
        } catch (RuntimeException e) {
            throw unusable(e);
        }
    }

    private LdapSettings requireEnabled() {
        LdapSettings ldap = config.load();
        if (!ldap.enabled()) {
            throw new ConflictException("LDAP is not configured");
        }
        return ldap;
    }

    private LdapContextSource contextSource(LdapSettings ldap) {
        LdapContextSource source = new LdapContextSource();
        source.setUrl(ldap.url());
        source.setBase(ldap.baseDn());
        if (ldap.bindDn() == null || ldap.bindDn().isBlank()) {
            source.setAnonymousReadOnly(true);
        } else {
            source.setUserDn(ldap.bindDn());
            source.setPassword(ldap.bindPassword() == null ? "" : ldap.bindPassword());
        }
        source.setPooled(false);
        source.setBaseEnvironmentProperties(Map.of(
                CONNECT_TIMEOUT, Long.toString(settings.getDuration(SettingKeys.AUTH_LDAP_CONNECT_TIMEOUT).toMillis()),
                READ_TIMEOUT, Long.toString(settings.getDuration(SettingKeys.AUTH_LDAP_READ_TIMEOUT).toMillis())));
        source.afterPropertiesSet();
        return source;
    }

    private static String searchBase(LdapSettings ldap) {
        return ldap.userSearchBase() == null ? "" : ldap.userSearchBase();
    }

    private static String text(Attributes attributes, String name) throws javax.naming.NamingException {
        Attribute attribute = attributes.get(name);
        return attribute == null || attribute.get() == null ? null : attribute.get().toString();
    }

    /** Never includes credentials: the message is the exception type plus its masked message. */
    private static ExternalSystemException unusable(Exception e) {
        return new ExternalSystemException("The directory could not be used: " + e.getClass().getSimpleName()
                + (e.getMessage() == null ? "" : ": " + UrlMasking.mask(e.getMessage())));
    }
}
```

**Before running, check one behaviour.** If the bind account's wrong password (`testingChecksTheBindAccount`) surfaces as a different exception type, keep the mapping to `ExternalSystemException`. JNDI error messages contain DNs and LDAP result codes but never the password. If one ever did, replace the message with the exception type only.

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw test -Dtest=LdapDirectoryTest`
Expected: 7 tests pass.

Run: `./mvnw test`
Expected: all tests pass.

- [ ] **Step 6: Commit**

```bash
git add pom.xml src/main src/test
git commit -m "feat(auth): authenticate and search users in the configured LDAP directory" -m "<your harness Co-Authored-By trailer>"
```

---

### Task 4: Login, lockout, provisioning and password change

**Files:**
- Create: `src/main/java/com/graphify/auth/Authorities.java`, `AppPrincipal.java`, `LoginService.java`
- Test: `src/test/java/com/graphify/auth/LoginServiceTest.java`

**Interfaces:**
- **Consumes:**
  - From Tasks 1–3: `AppUsers`, `LocalAccounts`, `LdapDirectory`, `PasswordEncoder`, `PasswordPolicy`.
  - `AuditLog`, `AppSettings` (`AUTH_MAX_FAILED_ATTEMPTS`, `AUTH_LOCK_DURATION`).
  - `LoginFailedException`, `ConflictException`, `InvalidRequestException`; `TestLdap`, `AuthFixtures`.
- **Produces (types):**
  - `public final class Authorities` with the constants `ROLE_ADMIN = "ROLE_ADMIN"`, `ROLE_USER = "ROLE_USER"` and `PASSWORD_CHANGE_REQUIRED = "PASSWORD_CHANGE_REQUIRED"`.
  - `public record AppPrincipal(long userId, String username, String displayName, String email, Role role, UserSource source, boolean mustChangePassword)`.
    - It implements `org.springframework.security.core.AuthenticatedPrincipal` (`getName()` returns `username`) and `java.io.Serializable`.
    - `List<GrantedAuthority> authorities()` returns `[PASSWORD_CHANGE_REQUIRED]` when the password must change, `[ROLE_ADMIN, ROLE_USER]` for ADMIN, and `[ROLE_USER]` for USER.
- **Produces (`LoginService`, a `@Service`):**
  - **`AppPrincipal login(String username, String password)`:**
    - The username is normalized first. A blank username or empty password fails.
    - **Local account first.** If a local account exists for the username, only it is checked; LDAP is never tried.
      - A disabled or inactive account fails. So does a locked one, without counting.
      - A wrong password counts toward the lockout and fails, and audits `ACCOUNT_LOCKED` when it locks the account.
      - Success resets the counter and records the login.
    - **Otherwise, LDAP if enabled.** A rejected bind fails.
      - On the first success it provisions `APP_USER` as LDAP/USER, granted by `ldap-login`. A user that already exists keeps its role.
      - An inactive user fails. Success records the login and updates the display name and email.
      - If LDAP is disabled, an unknown user fails.
    - Every failure audits `LOGIN_FAILED` with a reason and throws `LoginFailedException`. A success audits `LOGIN_SUCCEEDED`.
    - `ExternalSystemException` from LDAP propagates (502).
  - **`AppPrincipal changePassword(AppPrincipal who, String currentPassword, String newPassword)`:**
    - Local accounts only; otherwise `ConflictException`.
    - A wrong current password is `InvalidRequestException`. The new password is checked by the policy and must differ from the current one.
    - It stores the new hash with `must_change_password = 0`, audits `PASSWORD_CHANGED`, and returns the refreshed principal.
  - **`Optional<AppPrincipal> reload(long userId)`:** empty when the user is missing or inactive, or when a local account is disabled. Otherwise the current role and the must-change flag.
  - **`static Authentication authentication(AppPrincipal principal)`:** an authenticated `UsernamePasswordAuthenticationToken` carrying `principal.authorities()`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/graphify/auth/LoginServiceTest.java`:

```java
package com.graphify.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.graphify.OracleIntegrationTest;
import com.graphify.common.crypto.SecretCipher;
import com.graphify.common.exception.ConflictException;
import com.graphify.common.exception.ExternalSystemException;
import com.graphify.common.exception.InvalidRequestException;
import com.graphify.common.exception.LoginFailedException;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import com.graphify.testsupport.AuthFixtures;
import com.graphify.testsupport.SettingsOverride;
import com.graphify.testsupport.TestLdap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;

class LoginServiceTest extends OracleIntegrationTest {

    private static final String OPS_PASSWORD = "ops-password-123";

    @Autowired
    LoginService logins;

    @Autowired
    AppUsers users;

    @Autowired
    LocalAccounts accounts;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    SecretCipher cipher;

    @Autowired
    AppSettings settings;

    private SettingsOverride overrides;

    @BeforeEach
    void setUp() {
        AuthFixtures.deleteUsersExcept(jdbc, "admin");
        AuthFixtures.createLocal(users, accounts, encoder.encode(OPS_PASSWORD), "ops", Role.ADMIN, false);
        overrides = new SettingsOverride(settings);
        TestLdap.configure(jdbc, cipher);
    }

    @AfterEach
    void tearDown() {
        overrides.restore();
        TestLdap.disable(jdbc);
        AuthFixtures.deleteUsersExcept(jdbc, "admin");
        accounts.recordSuccess("admin"); // the shadowing test counts a failure against the bootstrap admin
        jdbc.update("DELETE FROM audit_log WHERE action LIKE 'LOGIN%' OR action IN ('ACCOUNT_LOCKED', "
                + "'PASSWORD_CHANGED')");
    }

    @Test
    void aLocalAccountSignsInCaseInsensitively() {
        AppPrincipal principal = logins.login(" OPS ", OPS_PASSWORD);

        assertThat(principal.username()).isEqualTo("ops");
        assertThat(principal.role()).isEqualTo(Role.ADMIN);
        assertThat(principal.authorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly(Authorities.ROLE_ADMIN, Authorities.ROLE_USER);
        assertThat(users.findByUsername("ops").orElseThrow().lastLoginAt()).isNotNull();
    }

    @Test
    void repeatedFailuresLockTheAccountAndEveryFailureLooksTheSame() {
        overrides.set(SettingKeys.AUTH_MAX_FAILED_ATTEMPTS, "2");

        assertThatThrownBy(() -> logins.login("ops", "wrong-1")).isInstanceOf(LoginFailedException.class)
                .hasMessage(LoginFailedException.MESSAGE);
        assertThatThrownBy(() -> logins.login("ops", "wrong-2")).hasMessage(LoginFailedException.MESSAGE);
        assertThatThrownBy(() -> logins.login("ops", OPS_PASSWORD)).hasMessage(LoginFailedException.MESSAGE);
        assertThatThrownBy(() -> logins.login("nobody-at-all", "x")).hasMessage(LoginFailedException.MESSAGE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'ACCOUNT_LOCKED' "
                + "AND target = 'ops'", Integer.class)).isEqualTo(1);

        jdbc.update("UPDATE local_account SET locked_until = SYSTIMESTAMP - INTERVAL '1' SECOND WHERE username = 'ops'");

        assertThat(logins.login("ops", OPS_PASSWORD).username()).isEqualTo("ops");
        assertThat(accounts.find("ops").orElseThrow().failedAttempts()).isZero();
    }

    @Test
    void theFirstDirectoryLoginProvisionsAUserAndLaterLoginsKeepTheRole() {
        AppPrincipal first = logins.login("Ayse", "ayse-secret");

        assertThat(first.role()).isEqualTo(Role.USER);
        assertThat(first.source()).isEqualTo(UserSource.LDAP);
        assertThat(first.displayName()).isEqualTo("Ayşe Yılmaz");
        AppUser stored = users.findByUsername("ayse").orElseThrow();
        assertThat(stored.roleGrantedBy()).isEqualTo("ldap-login");

        users.setRole(stored.id(), Role.ADMIN, "ops");

        assertThat(logins.login("ayse", "ayse-secret").role()).isEqualTo(Role.ADMIN);
        assertThatThrownBy(() -> logins.login("ayse", "wrong")).isInstanceOf(LoginFailedException.class);
    }

    @Test
    void aLocalAccountShadowsTheDirectoryAccountOfTheSameName() {
        assertThatThrownBy(() -> logins.login("admin", "ldap-admin-secret")).isInstanceOf(LoginFailedException.class);
    }

    @Test
    void inactiveUsersCannotSignIn() {
        logins.login("mehmet", "mehmet-secret");
        users.setActive(users.findByUsername("mehmet").orElseThrow().id(), false);
        users.setActive(users.findByUsername("ops").orElseThrow().id(), false);

        assertThatThrownBy(() -> logins.login("mehmet", "mehmet-secret")).isInstanceOf(LoginFailedException.class);
        assertThatThrownBy(() -> logins.login("ops", OPS_PASSWORD)).isInstanceOf(LoginFailedException.class);
        assertThat(logins.reload(users.findByUsername("ops").orElseThrow().id())).isEmpty();
    }

    @Test
    void withoutLdapOnlyLocalAccountsSignIn() {
        TestLdap.disable(jdbc);

        assertThatThrownBy(() -> logins.login("ayse", "ayse-secret")).isInstanceOf(LoginFailedException.class);
        assertThat(logins.login("ops", OPS_PASSWORD).username()).isEqualTo("ops");
    }

    @Test
    void anUnreachableDirectoryIsNotALoginFailure() {
        jdbc.update("UPDATE ldap_config SET url = 'ldap://localhost:1' WHERE id = 1");

        assertThatThrownBy(() -> logins.login("ayse", "ayse-secret")).isInstanceOf(ExternalSystemException.class);
    }

    @Test
    void aPasswordChangeClearsTheMustChangeFlag() {
        AuthFixtures.createLocal(users, accounts, encoder.encode("initial-password-1"), "fresh", Role.USER, true);
        AppPrincipal principal = logins.login("fresh", "initial-password-1");
        assertThat(principal.authorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly(Authorities.PASSWORD_CHANGE_REQUIRED);

        assertThatThrownBy(() -> logins.changePassword(principal, "wrong", "brand-new-password-1"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> logins.changePassword(principal, "initial-password-1", "short"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> logins.changePassword(principal, "initial-password-1", "initial-password-1"))
                .isInstanceOf(InvalidRequestException.class);

        AppPrincipal changed = logins.changePassword(principal, "initial-password-1", "brand-new-password-1");

        assertThat(changed.mustChangePassword()).isFalse();
        assertThat(changed.authorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly(Authorities.ROLE_USER);
        assertThat(logins.login("fresh", "brand-new-password-1").username()).isEqualTo("fresh");

        AppPrincipal directory = logins.login("ayse", "ayse-secret");
        assertThatThrownBy(() -> logins.changePassword(directory, "ayse-secret", "brand-new-password-2"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void auditNeverContainsPasswords() {
        logins.login("ops", OPS_PASSWORD);
        assertThatThrownBy(() -> logins.login("ops", "Wrong-Secret-42"));
        logins.login("ayse", "ayse-secret");

        assertThat(jdbc.queryForList("SELECT actor || ' ' || target || ' ' || NVL(details, '') FROM audit_log "
                + "WHERE action LIKE 'LOGIN%'", String.class))
                .isNotEmpty()
                .noneMatch(row -> row.contains(OPS_PASSWORD) || row.contains("Wrong-Secret-42")
                        || row.contains("ayse-secret"));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw test -Dtest=LoginServiceTest`
Expected: BUILD FAILURE: `cannot find symbol` for `LoginService`, `AppPrincipal` and `Authorities`.

- [ ] **Step 3: Implement**

`src/main/java/com/graphify/auth/Authorities.java`:

```java
package com.graphify.auth;

/** Granted authority names (spec §7.5). A user who must change their password holds only PASSWORD_CHANGE_REQUIRED. */
public final class Authorities {

    public static final String ROLE_ADMIN = "ROLE_ADMIN";
    public static final String ROLE_USER = "ROLE_USER";
    public static final String PASSWORD_CHANGE_REQUIRED = "PASSWORD_CHANGE_REQUIRED";

    private Authorities() {
    }
}
```

`src/main/java/com/graphify/auth/AppPrincipal.java`:

```java
package com.graphify.auth;

import java.io.Serializable;
import java.util.List;
import org.springframework.security.core.AuthenticatedPrincipal;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** The signed-in user kept in the session; carries no credentials. */
public record AppPrincipal(
        long userId,
        String username,
        String displayName,
        String email,
        Role role,
        UserSource source,
        boolean mustChangePassword) implements AuthenticatedPrincipal, Serializable {

    @Override
    public String getName() {
        return username;
    }

    public List<GrantedAuthority> authorities() {
        if (mustChangePassword) {
            return List.of(new SimpleGrantedAuthority(Authorities.PASSWORD_CHANGE_REQUIRED));
        }
        if (role == Role.ADMIN) {
            return List.of(new SimpleGrantedAuthority(Authorities.ROLE_ADMIN),
                    new SimpleGrantedAuthority(Authorities.ROLE_USER));
        }
        return List.of(new SimpleGrantedAuthority(Authorities.ROLE_USER));
    }
}
```

`src/main/java/com/graphify/auth/LoginService.java`:

```java
package com.graphify.auth;

import com.graphify.audit.AuditLog;
import com.graphify.common.exception.ConflictException;
import com.graphify.common.exception.InvalidRequestException;
import com.graphify.common.exception.LoginFailedException;
import com.graphify.common.util.Utf8;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Spec §7.1 login: an application-local account first; only when none exists, an LDAP bind. A first LDAP login
 * provisions the user with role USER. Every refusal looks the same to the caller; its reason is audited.
 */
@Service
public class LoginService {

    /** role_granted_by of users provisioned by their first directory login. */
    static final String PROVISIONING_ACTOR = "ldap-login";

    /** Width of audit_log.actor and audit_log.target (V1). */
    private static final int AUDIT_NAME_BYTES = 200;

    private final AppUsers users;
    private final LocalAccounts accounts;
    private final LdapDirectory directory;
    private final PasswordEncoder encoder;
    private final PasswordPolicy policy;
    private final AuditLog auditLog;
    private final AppSettings settings;

    public LoginService(AppUsers users, LocalAccounts accounts, LdapDirectory directory, PasswordEncoder encoder,
            PasswordPolicy policy, AuditLog auditLog, AppSettings settings) {
        this.users = users;
        this.accounts = accounts;
        this.directory = directory;
        this.encoder = encoder;
        this.policy = policy;
        this.auditLog = auditLog;
        this.settings = settings;
    }

    public static Authentication authentication(AppPrincipal principal) {
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.authorities());
    }

    public AppPrincipal login(String username, String password) {
        String name = AppUsers.normalize(username);
        if (name == null || name.isEmpty() || password == null || password.isEmpty()) {
            throw failed(name == null || name.isEmpty() ? "-" : name, "blank username or password");
        }
        Optional<LocalAccount> local = accounts.find(name);
        if (local.isPresent()) {
            return loginLocal(local.get(), password);
        }
        if (!directory.enabled()) {
            throw failed(name, "unknown user");
        }
        DirectoryUser entry = directory.authenticate(name, password)
                .orElseThrow(() -> failed(name, "rejected by the directory"));
        AppUser user = users.findByUsername(entry.username()).orElseGet(() -> users.create(entry.username(),
                UserSource.LDAP, entry.displayName(), entry.email(), Role.USER, PROVISIONING_ACTOR));
        if (user.source() != UserSource.LDAP) {
            throw failed(name, "a local user of this name has no local account");
        }
        if (!user.active()) {
            throw failed(name, "inactive user");
        }
        users.recordLogin(user.id(), entry.displayName(), entry.email());
        return succeeded(principal(users.find(user.id()).orElseThrow(), false));
    }

    private AppPrincipal loginLocal(LocalAccount account, String password) {
        String name = account.username();
        if (!account.enabled()) {
            throw failed(name, "disabled account");
        }
        if (account.locked()) {
            throw failed(name, "locked account");
        }
        if (!encoder.matches(password, account.passwordHash())) {
            boolean lockedNow = accounts.recordFailure(name, settings.getInt(SettingKeys.AUTH_MAX_FAILED_ATTEMPTS),
                    settings.getDuration(SettingKeys.AUTH_LOCK_DURATION));
            if (lockedNow) {
                auditLog.record(audited(name), "ACCOUNT_LOCKED", audited(name),
                        "locked for " + settings.getDuration(SettingKeys.AUTH_LOCK_DURATION));
            }
            throw failed(name, "wrong password");
        }
        AppUser user = users.findByUsername(name).orElseThrow(() -> failed(name, "account without user"));
        if (!user.active()) {
            throw failed(name, "inactive user");
        }
        accounts.recordSuccess(name);
        users.recordLogin(user.id(), null, null);
        return succeeded(principal(users.find(user.id()).orElseThrow(), account.mustChangePassword()));
    }

    public AppPrincipal changePassword(AppPrincipal who, String currentPassword, String newPassword) {
        if (who.source() != UserSource.LOCAL) {
            throw new ConflictException("The password of a directory user is managed in the directory");
        }
        LocalAccount account = accounts.find(who.username())
                .orElseThrow(() -> new ConflictException("No local account for " + who.username()));
        if (currentPassword == null || !encoder.matches(currentPassword, account.passwordHash())) {
            throw new InvalidRequestException("The current password is not correct");
        }
        policy.check(newPassword);
        if (newPassword.equals(currentPassword)) {
            throw new InvalidRequestException("The new password must differ from the current one");
        }
        accounts.setPassword(who.username(), encoder.encode(newPassword), false);
        auditLog.record(audited(who.username()), "PASSWORD_CHANGED", audited(who.username()), null);
        return reload(who.userId()).orElseThrow(() -> new ConflictException("The user is no longer active"));
    }

    /** The user as now stored; empty when missing, inactive or a disabled local account. */
    public Optional<AppPrincipal> reload(long userId) {
        Optional<AppUser> user = users.find(userId).filter(AppUser::active);
        if (user.isEmpty()) {
            return Optional.empty();
        }
        if (user.get().source() == UserSource.LOCAL) {
            Optional<LocalAccount> account = accounts.find(user.get().username());
            if (account.isEmpty() || !account.get().enabled()) {
                return Optional.empty();
            }
            return Optional.of(principal(user.get(), account.get().mustChangePassword()));
        }
        return Optional.of(principal(user.get(), false));
    }

    private static AppPrincipal principal(AppUser user, boolean mustChangePassword) {
        return new AppPrincipal(user.id(), user.username(), user.displayName(), user.email(), user.role(),
                user.source(), mustChangePassword);
    }

    private AppPrincipal succeeded(AppPrincipal principal) {
        auditLog.record(audited(principal.username()), "LOGIN_SUCCEEDED", audited(principal.username()),
                principal.source().name());
        return principal;
    }

    private LoginFailedException failed(String name, String reason) {
        auditLog.record(audited(name), "LOGIN_FAILED", audited(name), reason);
        return new LoginFailedException();
    }

    private static String audited(String name) {
        return Utf8.truncateToBytes(name, AUDIT_NAME_BYTES);
    }
}
```

`failed(...)` and `succeeded(...)` record the audit row before throwing or returning. `login` is not `@Transactional`, so the `LOGIN_FAILED` row survives the exception.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw test -Dtest=LoginServiceTest`
Expected: 9 tests pass.

Run: `./mvnw test`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main src/test
git commit -m "feat(auth): sign in local accounts first and directory users second, with lockout" -m "<your harness Co-Authored-By trailer>"
```

---

### Task 5: Web security, session endpoints and role checks

**Files:**
- Modify: `pom.xml` (add `spring-boot-starter-security`; test `spring-security-test`)
- Create: `src/main/java/com/graphify/auth/SecurityConfiguration.java`, `ProblemResponses.java`, `CurrentUserRefreshFilter.java`, `AuthController.java`
- Modify: `src/main/java/com/graphify/indexing/IndexRunController.java` (actor from the signed-in user)
- Modify: `src/test/java/com/graphify/OracleIntegrationTest.java`, `src/test/java/com/graphify/indexing/IndexRunApiTest.java` (`startedBy` is now `tester`)
- Create (test): `src/test/java/com/graphify/testsupport/LoginFlow.java`
- Test: `src/test/java/com/graphify/auth/SecurityApiTest.java`

**Interfaces:**
- **Consumes:** `LoginService`, `AppPrincipal`, `Authorities`, `Role` (Task 4), `AppSettings` (`AUTH_SESSION_TIMEOUT`), `AuditLog`, `InvalidRequestException`, `ConflictException`, `TestLdap`, `AuthFixtures`.
- **Produces (security configuration):**
  - `SecurityConfiguration` provides the beans `SecurityContextRepository` (`HttpSessionSecurityContextRepository`), `CsrfTokenRepository` (`HttpSessionCsrfTokenRepository` with header `SecurityConfiguration.CSRF_HEADER = "X-XSRF-TOKEN"`) and the `SecurityFilterChain`.
  - The filter chain implements the authorization rules in the Global Constraints. It adds `CurrentUserRefreshFilter` after `SecurityContextHolderFilter` and disables form login, basic auth, the built-in logout and the request cache.
  - `ProblemResponses` (package-private) is both the `AuthenticationEntryPoint` and the `AccessDeniedHandler`. It writes `application/problem+json`:
    - 401 "Authentication is required";
    - 403 with `code: PASSWORD_CHANGE_REQUIRED` for a must-change user;
    - 403 with `code: CSRF` for a missing or invalid token;
    - 403 "Not allowed" otherwise.
  - `CurrentUserRefreshFilter` (package-private, not a bean) works on an `AppPrincipal` session:
    - missing or inactive user: clear the context and invalidate the session (the request then gets 401);
    - changed principal: replace the authentication and save it in the session.
- **Produces (`AuthController`, under `/api/v1/auth`):**
  - `GET /csrf` returns `{headerName, token}`.
  - `POST /login` takes `{username, password}` and returns `Me`. It changes the session id if a session exists, saves the context, and sets `maxInactiveInterval` from `auth.session_timeout`.
  - `POST /logout` returns 204. It audits `LOGOUT` and invalidates the session.
  - `GET /me` returns `Me`.
  - `POST /change-password` takes `{currentPassword, newPassword}` and returns `Me` with the refreshed authentication saved.
  - `public record Me(String username, String displayName, String email, Role role, UserSource source, boolean mustChangePassword)`. For non-`AppPrincipal` authentications (test users), `role` is derived from the authorities and the other fields are null or false.
- **Produces (test base and helpers):**
  - `OracleIntegrationTest.mvc` is a tester signed in as `tester` with roles ADMIN and USER and a valid CSRF token on every request.
  - `OracleIntegrationTest.anonymous()` and `OracleIntegrationTest.as(String username, Role... roles)` return other testers.
  - `LoginFlow.login(MockMvcTester anonymous, String username, String password)` returns `LoginFlow.Session(MockHttpSession session, String csrfToken)`. It performs the real flow: `GET /auth/csrf`, then `POST /auth/login` with the header.
- **Produces (index runs):** `IndexRunController` records `started_by` as `Authentication.getName()`, and `ANONYMOUS_ACTOR` is removed.

- [ ] **Step 1: Add the dependencies**

`pom.xml`:

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.security</groupId>
			<artifactId>spring-security-test</artifactId>
			<scope>test</scope>
		</dependency>
```

- [ ] **Step 2: Switch the test base to authenticated requests**

Replace `src/test/java/com/graphify/OracleIntegrationTest.java` with:

```java
package com.graphify;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.graphify.auth.Role;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

/** Base for tests against the real Oracle schema and MVC layer. All subclasses share one context and container. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
public abstract class OracleIntegrationTest {

    /** The user {@link #mvc} requests run as. */
    protected static final String TEST_ACTOR = "tester";

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected WebApplicationContext context;

    /** Requests as {@value #TEST_ACTOR} with the ADMIN and USER roles and a valid CSRF token. */
    protected MockMvcTester mvc;

    @BeforeEach
    void signedInTester() {
        mvc = as(TEST_ACTOR, Role.ADMIN, Role.USER);
    }

    protected MockMvcTester as(String username, Role... roles) {
        String[] names = Arrays.stream(roles).map(Role::name).toArray(String[]::new);
        return MockMvcTester.from(context, builder -> builder.apply(springSecurity())
                .defaultRequest(get("/").with(user(username).roles(names)).with(csrf())).build());
    }

    protected MockMvcTester anonymous() {
        return MockMvcTester.from(context, builder -> builder.apply(springSecurity()).build());
    }
}
```

`src/test/java/com/graphify/testsupport/LoginFlow.java`:

```java
package com.graphify.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.auth.SecurityConfiguration;
import com.jayway.jsonpath.JsonPath;
import java.io.UnsupportedEncodingException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** The real browser flow: fetch a CSRF token, then sign in with it; returns the session and token to reuse. */
public final class LoginFlow {

    public record Session(MockHttpSession session, String csrfToken) {
    }

    private LoginFlow() {
    }

    public static Session login(MockMvcTester anonymous, String username, String password) {
        MockHttpSession session = new MockHttpSession();
        MvcTestResult csrf = anonymous.get().uri("/api/v1/auth/csrf").session(session).exchange();
        assertThat(csrf).hasStatusOk();
        String token = JsonPath.read(body(csrf), "$.token");
        MvcTestResult login = anonymous.post().uri("/api/v1/auth/login").session(session)
                .header(SecurityConfiguration.CSRF_HEADER, token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}").exchange();
        assertThat(login).hasStatusOk();
        return new Session((MockHttpSession) login.getRequest().getSession(false), token);
    }

    private static String body(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

- [ ] **Step 3: Write the failing test**

`src/test/java/com/graphify/auth/SecurityApiTest.java`:

```java
package com.graphify.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.common.crypto.SecretCipher;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import com.graphify.testsupport.AuthFixtures;
import com.graphify.testsupport.LoginFlow;
import com.graphify.testsupport.TestLdap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

class SecurityApiTest extends OracleIntegrationTest {

    @Autowired
    AppUsers users;

    @Autowired
    LocalAccounts accounts;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    SecretCipher cipher;

    @Autowired
    AppSettings settings;

    private MockMvcTester anonymous;

    @BeforeEach
    void setUp() {
        AuthFixtures.deleteUsersExcept(jdbc, "admin");
        TestLdap.configure(jdbc, cipher);
        anonymous = anonymous();
    }

    @AfterEach
    void tearDown() {
        TestLdap.disable(jdbc);
        AuthFixtures.deleteUsersExcept(jdbc, "admin");
    }

    @Test
    void anonymousCallersGetAProblemAndOnlyOpenEndpointsAnswer() {
        assertThat(anonymous.get().uri("/api/v1/symbols/search?q=Price")).hasStatus(401).bodyJson()
                .extractingPath("$.title").isEqualTo("Unauthorized");
        assertThat(anonymous.get().uri("/api/v1/openapi.json")).hasStatusOk();
        assertThat(anonymous.get().uri("/api/v1/auth/csrf")).hasStatusOk().bodyJson()
                .extractingPath("$.headerName").isEqualTo(SecurityConfiguration.CSRF_HEADER);
        assertThat(anonymous.post().uri("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"ops\",\"password\":\"x\"}")).hasStatus(403).bodyJson()
                .extractingPath("$.code").isEqualTo("CSRF");
        assertThat(anonymous.get().uri("/not-an-api")).hasStatus(401);
    }

    @Test
    void aDirectoryUserSignsInGetsUserRightsAndSignsOut() {
        LoginFlow.Session session = LoginFlow.login(anonymous, "ayse", "ayse-secret");

        assertThat(anonymous.get().uri("/api/v1/auth/me").session(session.session())).hasStatusOk().bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.username").isEqualTo("ayse");
                    assertThat(json).extractingPath("$.role").isEqualTo("USER");
                    assertThat(json).extractingPath("$.displayName").isEqualTo("Ayşe Yılmaz");
                });
        assertThat(session.session().getMaxInactiveInterval())
                .isEqualTo((int) settings.getDuration(SettingKeys.AUTH_SESSION_TIMEOUT).toSeconds());
        assertThat(anonymous.get().uri("/api/v1/repositories").session(session.session())).hasStatusOk();
        assertThat(anonymous.post().uri("/api/v1/index/runs").session(session.session())
                .header(SecurityConfiguration.CSRF_HEADER, session.csrfToken()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"ALL\"}")).hasStatus(403);
        assertThat(anonymous.post().uri("/api/v1/impact").session(session.session())
                .contentType(MediaType.APPLICATION_JSON).content("{\"symbolIds\":[1]}")).hasStatus(403);

        assertThat(anonymous.post().uri("/api/v1/auth/logout").session(session.session())
                .header(SecurityConfiguration.CSRF_HEADER, session.csrfToken())).hasStatus(204);
        assertThat(anonymous.get().uri("/api/v1/auth/me").session(session.session())).hasStatus(401);
    }

    @Test
    void wrongCredentialsAreA401Problem() {
        MockMvcTester fresh = anonymous();
        org.springframework.mock.web.MockHttpSession session = new org.springframework.mock.web.MockHttpSession();
        String token = com.jayway.jsonpath.JsonPath.read(new String(fresh.get().uri("/api/v1/auth/csrf")
                .session(session).exchange().getResponse().getContentAsByteArray(),
                java.nio.charset.StandardCharsets.UTF_8), "$.token");

        assertThat(fresh.post().uri("/api/v1/auth/login").session(session)
                .header(SecurityConfiguration.CSRF_HEADER, token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"ayse\",\"password\":\"wrong\"}")).hasStatus(401).bodyJson()
                .extractingPath("$.detail").isEqualTo("Invalid username or password");
    }

    @Test
    void aMustChangePasswordUserCanOnlyChangeThePassword() {
        AuthFixtures.createLocal(users, accounts, encoder.encode("initial-password-1"), "fresh", Role.ADMIN, true);
        LoginFlow.Session session = LoginFlow.login(anonymous, "fresh", "initial-password-1");

        assertThat(anonymous.get().uri("/api/v1/repositories").session(session.session())).hasStatus(403)
                .bodyJson().extractingPath("$.code").isEqualTo("PASSWORD_CHANGE_REQUIRED");
        assertThat(anonymous.get().uri("/api/v1/auth/me").session(session.session())).hasStatusOk().bodyJson()
                .extractingPath("$.mustChangePassword").isEqualTo(true);

        assertThat(anonymous.post().uri("/api/v1/auth/change-password").session(session.session())
                .header(SecurityConfiguration.CSRF_HEADER, session.csrfToken()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"initial-password-1\",\"newPassword\":\"brand-new-password-1\"}"))
                .hasStatusOk().bodyJson().extractingPath("$.mustChangePassword").isEqualTo(false);

        assertThat(anonymous.get().uri("/api/v1/repositories").session(session.session())).hasStatusOk();
    }

    @Test
    void roleAndActiveChangesApplyToOpenSessions() {
        LoginFlow.Session session = LoginFlow.login(anonymous, "mehmet", "mehmet-secret");
        long id = users.findByUsername("mehmet").orElseThrow().id();
        assertThat(anonymous.get().uri("/api/v1/index/runs").session(session.session())).hasStatusOk();
        assertThat(anonymous.post().uri("/api/v1/index/runs/-1/cancel").session(session.session())
                .header(SecurityConfiguration.CSRF_HEADER, session.csrfToken())).hasStatus(403);

        users.setRole(id, Role.ADMIN, "test");

        assertThat(anonymous.post().uri("/api/v1/index/runs/-1/cancel").session(session.session())
                .header(SecurityConfiguration.CSRF_HEADER, session.csrfToken())).hasStatus(404);

        users.setActive(id, false);

        assertThat(anonymous.get().uri("/api/v1/index/runs").session(session.session())).hasStatus(401);
    }

    @Test
    void theTestBaseRequestsRunAsAnAdmin() {
        assertThat(mvc.get().uri("/api/v1/auth/me")).hasStatusOk().bodyJson().satisfies(json -> {
            assertThat(json).extractingPath("$.username").isEqualTo(TEST_ACTOR);
            assertThat(json).extractingPath("$.role").isEqualTo("ADMIN");
        });
        assertThat(as("viewer", Role.USER).post().uri("/api/v1/index/runs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"ALL\"}")).hasStatus(403);
    }
}
```

`wrongCredentialsAreA401Problem` uses checked IO through `getContentAsByteArray()`, which does not throw; adjust imports to real imports rather than fully qualified names. Real imports are preferred everywhere.

In `IndexRunApiTest.startsARunAndPointsToIt`, change the expected `startedBy` from `"anonymous"` to `"tester"`.

- [ ] **Step 4: Run it to verify it fails**

Run: `./mvnw test -Dtest=SecurityApiTest`
Expected: compile failure first (`SecurityConfiguration`). Once the classes exist, the security behaviour assertions fail until Step 5 is complete.

- [ ] **Step 5: Implement the web security**

`src/main/java/com/graphify/auth/SecurityConfiguration.java`:

```java
package com.graphify.auth;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

/**
 * Spec §7.4–7.5: server-side sessions (HttpOnly cookie), a session-stored CSRF token sent back in a header, and the
 * role matrix. 401/403 answers are RFC 7807 problems.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    /** Header that carries the CSRF token; the client reads the token from GET /api/v1/auth/csrf. */
    public static final String CSRF_HEADER = "X-XSRF-TOKEN";

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        HttpSessionCsrfTokenRepository repository = new HttpSessionCsrfTokenRepository();
        repository.setHeaderName(CSRF_HEADER);
        return repository;
    }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, SecurityContextRepository contexts,
            CsrfTokenRepository csrfTokens, LoginService logins) throws Exception {
        ProblemResponses problems = new ProblemResponses();
        String admin = Role.ADMIN.name();
        http.csrf(csrf -> csrf.csrfTokenRepository(csrfTokens)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .securityContext(context -> context.securityContextRepository(contexts))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .addFilterAfter(new CurrentUserRefreshFilter(logins, contexts), SecurityContextHolderFilter.class)
                .authorizeHttpRequests(requests -> requests
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf", "/api/v1/openapi.json").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers("/api/v1/auth/me", "/api/v1/auth/logout", "/api/v1/auth/change-password")
                        .authenticated()
                        .requestMatchers("/api/v1/admin/**").hasRole(admin)
                        .requestMatchers(HttpMethod.POST, "/api/v1/index/runs", "/api/v1/index/runs/*/cancel")
                        .hasRole(admin)
                        .requestMatchers("/api/**").hasRole(Role.USER.name())
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable());
        return http.build();
    }
}
```

`src/main/java/com/graphify/auth/ProblemResponses.java`:

```java
package com.graphify.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;

/**
 * RFC 7807 bodies for the 401/403 the security filters produce (spec §8). Every text here is a constant, so the JSON
 * is written directly without escaping.
 */
final class ProblemResponses implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final String PROBLEM_JSON = "application/problem+json";

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException {
        write(response, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized", "Authentication is required", null);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
            throws IOException {
        if (e instanceof CsrfException) {
            write(response, HttpServletResponse.SC_FORBIDDEN, "Forbidden", "Missing or invalid CSRF token", "CSRF");
            return;
        }
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        boolean mustChange = current != null && current.getAuthorities().stream()
                .anyMatch(authority -> Authorities.PASSWORD_CHANGE_REQUIRED.equals(authority.getAuthority()));
        if (mustChange) {
            write(response, HttpServletResponse.SC_FORBIDDEN, "Forbidden", "The password must be changed first",
                    Authorities.PASSWORD_CHANGE_REQUIRED);
            return;
        }
        write(response, HttpServletResponse.SC_FORBIDDEN, "Forbidden", "Not allowed", null);
    }

    private static void write(HttpServletResponse response, int status, String title, String detail, String code)
            throws IOException {
        response.setStatus(status);
        response.setContentType(PROBLEM_JSON);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":" + status
                + ",\"detail\":\"" + detail + "\"" + (code == null ? "" : ",\"code\":\"" + code + "\"") + "}");
    }
}
```

`src/main/java/com/graphify/auth/CurrentUserRefreshFilter.java`:

```java
package com.graphify.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Re-reads the signed-in user on every request (one primary-key lookup), so a role change or deactivation applies to
 * open sessions at once (spec §7.4) instead of at the next login. Not a bean: it is registered only in the security
 * filter chain, never as a plain servlet filter.
 */
final class CurrentUserRefreshFilter extends OncePerRequestFilter {

    private final LoginService logins;
    private final SecurityContextRepository contexts;

    CurrentUserRefreshFilter(LoginService logins, SecurityContextRepository contexts) {
        this.logins = logins;
        this.contexts = contexts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        if (current != null && current.getPrincipal() instanceof AppPrincipal principal) {
            Optional<AppPrincipal> fresh = logins.reload(principal.userId());
            if (fresh.isEmpty()) {
                SecurityContextHolder.clearContext();
                HttpSession session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
            } else if (!fresh.get().equals(principal)) {
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(LoginService.authentication(fresh.get()));
                SecurityContextHolder.setContext(context);
                contexts.saveContext(context, request, response);
            }
        }
        chain.doFilter(request, response);
    }
}
```

`src/main/java/com/graphify/auth/AuthController.java`:

```java
package com.graphify.auth;

import com.graphify.audit.AuditLog;
import com.graphify.common.exception.ConflictException;
import com.graphify.common.exception.InvalidRequestException;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Session endpoints (spec §10.2). */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    public record Credentials(String username, String password) {
    }

    public record PasswordChange(String currentPassword, String newPassword) {
    }

    public record CsrfView(String headerName, String token) {
    }

    public record Me(String username, String displayName, String email, Role role, UserSource source,
            boolean mustChangePassword) {

        static Me of(AppPrincipal principal) {
            return new Me(principal.username(), principal.displayName(), principal.email(), principal.role(),
                    principal.source(), principal.mustChangePassword());
        }
    }

    private final LoginService logins;
    private final SecurityContextRepository contexts;
    private final AppSettings settings;
    private final AuditLog auditLog;

    public AuthController(LoginService logins, SecurityContextRepository contexts, AppSettings settings,
            AuditLog auditLog) {
        this.logins = logins;
        this.contexts = contexts;
        this.settings = settings;
        this.auditLog = auditLog;
    }

    @GetMapping("/csrf")
    public CsrfView csrf(CsrfToken token) {
        return new CsrfView(token.getHeaderName(), token.getToken());
    }

    @PostMapping("/login")
    public Me login(@RequestBody(required = false) Credentials credentials, HttpServletRequest request,
            HttpServletResponse response) {
        if (credentials == null) {
            throw new InvalidRequestException("A body {username, password} is required");
        }
        AppPrincipal principal = logins.login(credentials.username(), credentials.password());
        if (request.getSession(false) != null) {
            request.changeSessionId(); // a new session id at every sign-in prevents session fixation
        }
        establish(principal, request, response);
        request.getSession().setMaxInactiveInterval(
                (int) settings.getDuration(SettingKeys.AUTH_SESSION_TIMEOUT).toSeconds());
        return Me.of(principal);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(Authentication authentication, HttpServletRequest request) {
        auditLog.record(authentication.getName(), "LOGOUT", authentication.getName(), null);
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public Me me(Authentication authentication) {
        if (authentication.getPrincipal() instanceof AppPrincipal principal) {
            return Me.of(principal);
        }
        boolean admin = authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .anyMatch(Authorities.ROLE_ADMIN::equals);
        return new Me(authentication.getName(), null, null, admin ? Role.ADMIN : Role.USER, null, false);
    }

    @PostMapping("/change-password")
    public Me changePassword(@RequestBody(required = false) PasswordChange change, Authentication authentication,
            HttpServletRequest request, HttpServletResponse response) {
        if (change == null) {
            throw new InvalidRequestException("A body {currentPassword, newPassword} is required");
        }
        if (!(authentication.getPrincipal() instanceof AppPrincipal principal)) {
            throw new ConflictException("Only signed-in application users can change a password");
        }
        AppPrincipal changed = logins.changePassword(principal, change.currentPassword(), change.newPassword());
        establish(changed, request, response);
        return Me.of(changed);
    }

    private void establish(AppPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(LoginService.authentication(principal));
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
    }
}
```

In `IndexRunController`:
- Remove `ANONYMOUS_ACTOR`.
- Add an `Authentication authentication` parameter to `start`.
- Pass `authentication.getName()` as the actor.
- Update the class Javadoc: the role checks live in `SecurityConfiguration`.

**Boot's default user.** If the startup log shows "Using generated security password", Boot's `UserDetailsServiceAutoConfiguration` is still creating its default in-memory user. Exclude that auto-configuration in `GraphifyApplication` (`@SpringBootApplication(exclude = ...)`, using its Boot 4 class name) so no default credentials exist. Say what you did in your report.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw test -Dtest='SecurityApiTest,IndexRunApiTest,RepositoryApiTest,ImpactApiTest'`
Expected: all pass.

Run: `./mvnw test`
Expected: all tests pass. Every existing API test now runs as the signed-in `tester` through the new base class.

- [ ] **Step 7: Commit**

```bash
git add pom.xml src/main src/test
git commit -m "feat(auth): require sign-in, enforce the role matrix and keep open sessions in sync" -m "<your harness Co-Authored-By trailer>"
```

---

### Task 6: Users admin API and directory search

**Files:**
- Create: `src/main/java/com/graphify/auth/UserAdministration.java`, `UserAdminController.java`
- Test: `src/test/java/com/graphify/auth/UserAdministrationTest.java`, `UserAdminApiTest.java`

**Interfaces:**
- **Consumes:** `AppUsers` (including `lockActiveAdmins`), `LocalAccounts`, `LdapDirectory`, `AuditLog`, `PagingResolver`, `ConflictException`, `NotFoundException`, `InvalidRequestException`, `TestLdap`, `AuthFixtures`.
- **Produces (`UserAdministration`, a `@Service`):**
  - `AppUser register(String username, Role role, String actor)` (`@Transactional`):
    - registers a directory user who has not signed in yet;
    - a missing role is 400; an existing app user of that name is 409; a name the directory does not have is 404;
    - audits `USER_REGISTERED`.
  - `AppUser changeRole(long userId, Role role, String actor)` (`@Transactional`):
    - an unknown user is 404; a missing role is 400; the same role is a no-op;
    - demoting an active admin needs another active admin (409 otherwise);
    - audits `USER_ROLE_CHANGED` with `OLD -> NEW`.
  - `AppUser changeActive(long userId, boolean active, String actor)` (`@Transactional`):
    - deactivating an active admin needs another active admin;
    - deactivating a LOCAL admin needs another active admin whose source is LDAP (spec §7.4);
    - for LOCAL users it keeps `local_account.enabled` in sync, then audits `USER_ACTIVE_CHANGED`.
  - Last-admin checks always go through `users.lockActiveAdmins()`.
- **Produces (`UserAdminController`, under `/api/v1/admin`):**
  - `GET /users?q=&page=&size=` returns `Page<AppUser>`.
  - `POST /users` takes `{username, role}` and answers 201 with `Location: /api/v1/admin/users/{id}` and the `AppUser`.
  - `PUT /users/{id}/role` takes `{role}` and returns `AppUser`.
  - `PUT /users/{id}/active` takes `{active}` and returns `AppUser`.
  - `GET /ldap/users?q=` returns `List<DirectoryUserView(String username, String displayName, String email, Long appUserId)>`; `appUserId` is null when the person is not registered.
  - The actor is `Authentication.getName()`.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/graphify/auth/UserAdministrationTest.java`:

```java
package com.graphify.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.graphify.OracleIntegrationTest;
import com.graphify.common.crypto.SecretCipher;
import com.graphify.common.exception.ConflictException;
import com.graphify.common.exception.InvalidRequestException;
import com.graphify.common.exception.NotFoundException;
import com.graphify.testsupport.AuthFixtures;
import com.graphify.testsupport.TestLdap;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class UserAdministrationTest extends OracleIntegrationTest {

    @Autowired
    UserAdministration administration;

    @Autowired
    AppUsers users;

    @Autowired
    LocalAccounts accounts;

    @Autowired
    SecretCipher cipher;

    private long bootstrapId;

    @BeforeEach
    void setUp() {
        AuthFixtures.deleteUsersExcept(jdbc, "admin");
        TestLdap.configure(jdbc, cipher);
        bootstrapId = users.findByUsername("admin").orElseThrow().id();
    }

    @AfterEach
    void tearDown() {
        users.setRole(bootstrapId, Role.ADMIN, "test");
        users.setActive(bootstrapId, true);
        accounts.setEnabled("admin", true);
        TestLdap.disable(jdbc);
        AuthFixtures.deleteUsersExcept(jdbc, "admin");
    }

    @Test
    void registersADirectoryUserWithARole() {
        AppUser ayse = administration.register("Ayse", Role.ADMIN, "admin");

        assertThat(ayse.username()).isEqualTo("ayse");
        assertThat(ayse.source()).isEqualTo(UserSource.LDAP);
        assertThat(ayse.displayName()).isEqualTo("Ayşe Yılmaz");
        assertThat(ayse.role()).isEqualTo(Role.ADMIN);
        assertThat(ayse.roleGrantedBy()).isEqualTo("admin");
        assertThatThrownBy(() -> administration.register("ayse", Role.USER, "admin"))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> administration.register("ghost", Role.USER, "admin"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> administration.register("mehmet", null, "admin"))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void theLastActiveAdminCannotBeDemotedOrDeactivated() {
        assertThatThrownBy(() -> administration.changeRole(bootstrapId, Role.USER, "admin"))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> administration.changeActive(bootstrapId, false, "admin"))
                .isInstanceOf(ConflictException.class);

        AppUser ayse = administration.register("ayse", Role.ADMIN, "admin");

        assertThat(administration.changeRole(bootstrapId, Role.USER, "ayse").role()).isEqualTo(Role.USER);
        assertThatThrownBy(() -> administration.changeRole(ayse.id(), Role.USER, "ayse"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void theLocalAdminNeedsAnActiveDirectoryAdminToBeDeactivated() {
        long localOps = AuthFixtures.createLocal(users, accounts, "$2a$10$x", "ops", Role.ADMIN, false).id();

        assertThatThrownBy(() -> administration.changeActive(bootstrapId, false, "ops"))
                .isInstanceOf(ConflictException.class).hasMessageContaining("LDAP");

        administration.register("mehmet", Role.ADMIN, "admin");
        AppUser deactivated = administration.changeActive(bootstrapId, false, "mehmet");

        assertThat(deactivated.active()).isFalse();
        assertThat(accounts.find("admin").orElseThrow().enabled()).isFalse();
        assertThat(administration.changeActive(localOps, false, "mehmet").active()).isFalse();
    }

    @Test
    void concurrentDemotionsNeverRemoveTheLastAdmin() throws Exception {
        long ayse = administration.register("ayse", Role.ADMIN, "admin").id();
        users.setRole(bootstrapId, Role.USER, "test");
        long mehmet = administration.register("mehmet", Role.ADMIN, "admin").id();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (long id : List.of(ayse, mehmet)) {
                Callable<Boolean> demote = () -> {
                    start.await();
                    try {
                        administration.changeRole(id, Role.USER, "race");
                        return true;
                    } catch (ConflictException e) {
                        return false;
                    }
                };
                results.add(pool.submit(demote));
            }
            start.countDown();
            int demoted = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    demoted++;
                }
            }
            assertThat(demoted).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE role = 'ADMIN' AND active = 1",
                    Integer.class)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
```

`src/test/java/com/graphify/auth/UserAdminApiTest.java`:

```java
package com.graphify.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.common.crypto.SecretCipher;
import com.graphify.testsupport.AuthFixtures;
import com.graphify.testsupport.TestLdap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

class UserAdminApiTest extends OracleIntegrationTest {

    @Autowired
    AppUsers users;

    @Autowired
    SecretCipher cipher;

    @BeforeEach
    void setUp() {
        AuthFixtures.deleteUsersExcept(jdbc, "admin");
        TestLdap.configure(jdbc, cipher);
    }

    @AfterEach
    void tearDown() {
        TestLdap.disable(jdbc);
        AuthFixtures.deleteUsersExcept(jdbc, "admin");
    }

    @Test
    void searchesTheDirectoryAndRegistersAnAdmin() {
        assertThat(mvc.get().uri("/api/v1/admin/ldap/users?q=ay")).hasStatusOk().bodyJson().satisfies(json -> {
            assertThat(json).extractingPath("$[0].username").isEqualTo("ayse");
            assertThat(json).extractingPath("$[0].appUserId").isNull();
        });

        MvcTestResult created = mvc.post().uri("/api/v1/admin/users").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"ayse\",\"role\":\"ADMIN\"}").exchange();

        assertThat(created).hasStatus(201);
        long id = users.findByUsername("ayse").orElseThrow().id();
        assertThat(created.getResponse().getHeader("Location")).isEqualTo("/api/v1/admin/users/" + id);
        assertThat(mvc.get().uri("/api/v1/admin/ldap/users?q=ay")).hasStatusOk().bodyJson()
                .extractingPath("$[0].appUserId").isEqualTo((int) id);
        assertThat(jdbc.queryForObject("SELECT role_granted_by FROM app_user WHERE id = ?", String.class, id))
                .isEqualTo(TEST_ACTOR);
    }

    @Test
    void changesRolesAndActivityAndKeepsTheLastAdmin() {
        long admin = users.findByUsername("admin").orElseThrow().id();

        assertThat(mvc.put().uri("/api/v1/admin/users/" + admin + "/role").contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"USER\"}")).hasStatus(409);
        assertThat(mvc.put().uri("/api/v1/admin/users/" + admin + "/active").contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}")).hasStatus(409);
        assertThat(mvc.put().uri("/api/v1/admin/users/-1/role").contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"USER\"}")).hasStatus(404);
        assertThat(mvc.put().uri("/api/v1/admin/users/" + admin + "/role").contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"BOSS\"}")).hasStatus(400);
        assertThat(mvc.get().uri("/api/v1/admin/users?q=adm")).hasStatusOk().bodyJson()
                .extractingPath("$.items[0].username").isEqualTo("admin");
    }

    @Test
    void usersCannotAdministerUsers() {
        assertThat(as("viewer", Role.USER).get().uri("/api/v1/admin/users")).hasStatus(403);
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -Dtest='UserAdministrationTest,UserAdminApiTest'`
Expected: BUILD FAILURE: `cannot find symbol` for `UserAdministration`.

- [ ] **Step 3: Implement**

`src/main/java/com/graphify/auth/UserAdministration.java`:

```java
package com.graphify.auth;

import com.graphify.audit.AuditLog;
import com.graphify.common.exception.ConflictException;
import com.graphify.common.exception.InvalidRequestException;
import com.graphify.common.exception.NotFoundException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin changes to users (spec §7.3–7.4). At least one active ADMIN always remains, and the local admin is only
 * deactivated while an active LDAP admin exists; both checks lock the active admin rows, so concurrent changes are
 * serialized.
 */
@Service
public class UserAdministration {

    private final AppUsers users;
    private final LocalAccounts accounts;
    private final LdapDirectory directory;
    private final AuditLog auditLog;

    public UserAdministration(AppUsers users, LocalAccounts accounts, LdapDirectory directory, AuditLog auditLog) {
        this.users = users;
        this.accounts = accounts;
        this.directory = directory;
        this.auditLog = auditLog;
    }

    @Transactional
    public AppUser register(String username, Role role, String actor) {
        if (role == null) {
            throw new InvalidRequestException("role is required (ADMIN or USER)");
        }
        String name = AppUsers.normalize(username);
        if (name == null || name.isEmpty()) {
            throw new InvalidRequestException("username is required");
        }
        if (users.findByUsername(name).isPresent()) {
            throw new ConflictException("User " + name + " is already registered");
        }
        DirectoryUser entry = directory.find(name)
                .orElseThrow(() -> new NotFoundException("No directory user named " + name));
        AppUser created = users.create(entry.username(), UserSource.LDAP, entry.displayName(), entry.email(), role,
                actor);
        auditLog.record(actor, "USER_REGISTERED", created.username(), role.name());
        return created;
    }

    @Transactional
    public AppUser changeRole(long userId, Role role, String actor) {
        if (role == null) {
            throw new InvalidRequestException("role is required (ADMIN or USER)");
        }
        AppUser user = find(userId);
        if (user.role() == role) {
            return user;
        }
        if (user.role() == Role.ADMIN && user.active()) {
            requireAnotherActiveAdmin(user, false);
        }
        users.setRole(userId, role, actor);
        auditLog.record(actor, "USER_ROLE_CHANGED", user.username(), user.role() + " -> " + role);
        return find(userId);
    }

    @Transactional
    public AppUser changeActive(long userId, boolean active, String actor) {
        AppUser user = find(userId);
        if (user.active() == active) {
            return user;
        }
        if (!active && user.role() == Role.ADMIN) {
            requireAnotherActiveAdmin(user, user.source() == UserSource.LOCAL);
        }
        users.setActive(userId, active);
        if (user.source() == UserSource.LOCAL) {
            accounts.setEnabled(user.username(), active);
        }
        auditLog.record(actor, "USER_ACTIVE_CHANGED", user.username(), active ? "activated" : "deactivated");
        return find(userId);
    }

    private void requireAnotherActiveAdmin(AppUser leaving, boolean directoryAdminRequired) {
        List<AppUser> admins = users.lockActiveAdmins();
        boolean another = admins.stream().anyMatch(admin -> admin.id() != leaving.id()
                && (!directoryAdminRequired || admin.source() == UserSource.LDAP));
        if (!another) {
            throw new ConflictException(directoryAdminRequired
                    ? "The local admin can only be deactivated while another active LDAP admin exists"
                    : "At least one active admin must remain");
        }
    }

    private AppUser find(long userId) {
        return users.find(userId).orElseThrow(() -> new NotFoundException("No user with id " + userId));
    }
}
```

`src/main/java/com/graphify/auth/UserAdminController.java`:

```java
package com.graphify.auth;

import com.graphify.api.Page;
import com.graphify.api.PagingResolver;
import com.graphify.common.exception.InvalidRequestException;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Users and directory lookup for admins (spec §10.6 "Kullanıcılar", "LDAP" users search). */
@RestController
@RequestMapping("/api/v1/admin")
public class UserAdminController {

    public record Registration(String username, Role role) {
    }

    public record RoleChange(Role role) {
    }

    public record ActiveChange(Boolean active) {
    }

    public record DirectoryUserView(String username, String displayName, String email, Long appUserId) {
    }

    private final UserAdministration administration;
    private final AppUsers users;
    private final LdapDirectory directory;
    private final PagingResolver paging;

    public UserAdminController(UserAdministration administration, AppUsers users, LdapDirectory directory,
            PagingResolver paging) {
        this.administration = administration;
        this.users = users;
        this.directory = directory;
        this.paging = paging;
    }

    @GetMapping("/users")
    public Page<AppUser> list(@RequestParam(required = false) String q, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return users.list(q, paging.resolve(page, size));
    }

    @PostMapping("/users")
    public ResponseEntity<AppUser> register(@RequestBody(required = false) Registration registration,
            Authentication authentication) {
        if (registration == null) {
            throw new InvalidRequestException("A body {username, role} is required");
        }
        AppUser created = administration.register(registration.username(), registration.role(),
                authentication.getName());
        return ResponseEntity.created(URI.create("/api/v1/admin/users/" + created.id())).body(created);
    }

    @PutMapping("/users/{id}/role")
    public AppUser changeRole(@PathVariable long id, @RequestBody(required = false) RoleChange change,
            Authentication authentication) {
        return administration.changeRole(id, change == null ? null : change.role(), authentication.getName());
    }

    @PutMapping("/users/{id}/active")
    public AppUser changeActive(@PathVariable long id, @RequestBody(required = false) ActiveChange change,
            Authentication authentication) {
        if (change == null || change.active() == null) {
            throw new InvalidRequestException("A body {active: true|false} is required");
        }
        return administration.changeActive(id, change.active(), authentication.getName());
    }

    @GetMapping("/ldap/users")
    public List<DirectoryUserView> searchDirectory(@RequestParam(required = false) String q) {
        return directory.search(q).stream().map(user -> new DirectoryUserView(user.username(), user.displayName(),
                user.email(), users.findByUsername(user.username()).map(AppUser::id).orElse(null))).toList();
    }
}
```

An unknown enum value in the body (`{"role":"BOSS"}`) fails JSON reading, which Spring answers with a 400 problem.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw test -Dtest='UserAdministrationTest,UserAdminApiTest'`
Expected: all pass.

Run: `./mvnw test`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main src/test
git commit -m "feat(auth): let admins register directory users and change roles while keeping an admin" -m "<your harness Co-Authored-By trailer>"
```

---

### Task 7: LDAP configuration admin API

**Files:**
- Create: `src/main/java/com/graphify/auth/LdapConfigView.java`, `LdapConfigUpdate.java`, `LdapAdministration.java`, `LdapAdminController.java`
- Modify: `src/main/java/com/graphify/auth/LdapConfigRepository.java` (add `view()` and `save(...)`)
- Test: `src/test/java/com/graphify/auth/LdapAdminApiTest.java`

**Interfaces:**
- **Consumes:** `LdapConfigRepository.load()`, `LdapDirectory.test(LdapSettings)`, `SecretCipher`, `AuditLog`, `Utf8`, `InvalidRequestException`, `ExternalSystemException`, `TestLdap`.
- **Produces (types):**
  - `public record LdapConfigView(boolean enabled, String url, String baseDn, String userSearchBase, String userSearchFilter, String userQueryFilter, String usernameAttr, String displayNameAttr, String emailAttr, String bindDn, boolean bindPasswordSet, String updatedBy, Instant updatedAt)`.
  - `public record LdapConfigUpdate(Boolean enabled, String url, String baseDn, String userSearchBase, String userSearchFilter, String userQueryFilter, String usernameAttr, String displayNameAttr, String emailAttr, String bindDn, String bindPassword)`.
- **Produces (`LdapConfigRepository` additions):** `LdapConfigView view()` and `void save(LdapSettings settings, String actor)`. `save` encrypts the bind password and stores null for an empty one.
- **Produces (`LdapAdministration`, a `@Service`):**
  - `LdapConfigView get()`.
  - `LdapConfigView update(LdapConfigUpdate update, String actor)`, which validates, saves and audits `LDAP_CONFIG_UPDATED`. The details name the changed fields and never their secret value.
  - `void test(LdapConfigUpdate candidate)`: a null candidate tests the stored configuration.
  - **Update semantics.** `PUT` is a full document:
    - Optional fields `userSearchBase` and `bindDn` become null when absent.
    - `bindPassword` null keeps the stored secret (spec §6.4), and `""` clears it.
    - `enabled` is required.
  - **Validation** (400):
    - Filters and attributes must be non-blank.
    - Both filters must contain `{0}`.
    - When `enabled` is true, `url` must be an `ldap://` or `ldaps://` URI and `baseDn` must be non-blank.
    - Text must fit the V6 column widths in bytes.
- **Produces (`LdapAdminController`):**
  - `GET /api/v1/admin/ldap` returns `LdapConfigView`.
  - `PUT /api/v1/admin/ldap` returns `LdapConfigView`.
  - `POST /api/v1/admin/ldap/test` takes an optional `LdapConfigUpdate` body and returns `{ok: true}`, or a 502 problem.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/graphify/auth/LdapAdminApiTest.java`:

```java
package com.graphify.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.common.crypto.SecretCipher;
import com.graphify.testsupport.TestLdap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class LdapAdminApiTest extends OracleIntegrationTest {

    @Autowired
    SecretCipher cipher;

    @Autowired
    LdapConfigRepository config;

    @AfterEach
    void tearDown() {
        TestLdap.disable(jdbc);
        jdbc.update("DELETE FROM audit_log WHERE action = 'LDAP_CONFIG_UPDATED'");
    }

    private String body(boolean enabled, String url, String bindPassword) {
        return """
                {"enabled":%s,"url":"%s","baseDn":"dc=corp,dc=com","userSearchBase":"ou=people",
                 "userSearchFilter":"(uid={0})","userQueryFilter":"(|(uid={0}*)(cn={0}*))","usernameAttr":"uid",
                 "displayNameAttr":"cn","emailAttr":"mail","bindDn":"cn=reader,dc=corp,dc=com"%s}
                """.formatted(enabled, url, bindPassword == null ? "" : ",\"bindPassword\":\"" + bindPassword + "\"");
    }

    @Test
    void savesAndShowsTheConfigurationWithoutTheBindPassword() {
        assertThat(mvc.put().uri("/api/v1/admin/ldap").contentType(MediaType.APPLICATION_JSON)
                .content(body(true, TestLdap.url(), TestLdap.BIND_PASSWORD))).hasStatusOk().bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.enabled").isEqualTo(true);
                    assertThat(json).extractingPath("$.bindPasswordSet").isEqualTo(true);
                    assertThat(json).extractingPath("$.updatedBy").isEqualTo(TEST_ACTOR);
                });

        assertThat(config.load().bindPassword()).isEqualTo(TestLdap.BIND_PASSWORD);
        assertThat(mvc.put().uri("/api/v1/admin/ldap").contentType(MediaType.APPLICATION_JSON)
                .content(body(true, TestLdap.url(), null))).hasStatusOk();
        assertThat(config.load().bindPassword()).isEqualTo(TestLdap.BIND_PASSWORD);
        assertThat(mvc.post().uri("/api/v1/admin/ldap/test")).hasStatusOk().bodyJson()
                .extractingPath("$.ok").isEqualTo(true);
    }

    @Test
    void theBindPasswordIsNeverReturnedOrAudited() {
        mvc.put().uri("/api/v1/admin/ldap").contentType(MediaType.APPLICATION_JSON)
                .content(body(true, TestLdap.url(), TestLdap.BIND_PASSWORD)).exchange();

        assertThat(mvc.get().uri("/api/v1/admin/ldap")).hasStatusOk().bodyText()
                .doesNotContain(TestLdap.BIND_PASSWORD).doesNotContain(cipher.encrypt(TestLdap.BIND_PASSWORD));
        assertThat(jdbc.queryForList("SELECT NVL(details, '-') FROM audit_log WHERE action = 'LDAP_CONFIG_UPDATED'",
                String.class)).isNotEmpty().noneMatch(details -> details.contains(TestLdap.BIND_PASSWORD))
                .anyMatch(details -> details.contains("bindPassword"));
        assertThat(mvc.post().uri("/api/v1/admin/ldap/test").contentType(MediaType.APPLICATION_JSON)
                .content(body(true, "ldap://localhost:1", "candidate-secret"))).hasStatus(502).bodyText()
                .doesNotContain("candidate-secret");
    }

    @Test
    void rejectsAnUnusableConfiguration() {
        assertThat(mvc.put().uri("/api/v1/admin/ldap").contentType(MediaType.APPLICATION_JSON)
                .content(body(true, "http://not-ldap", "x"))).hasStatus(400);
        assertThat(mvc.put().uri("/api/v1/admin/ldap").contentType(MediaType.APPLICATION_JSON)
                .content(body(true, TestLdap.url(), "x").replace("(uid={0})", "(uid=fixed)"))).hasStatus(400);
        assertThat(mvc.put().uri("/api/v1/admin/ldap").contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"ldap://x\"}")).hasStatus(400);
        assertThat(mvc.put().uri("/api/v1/admin/ldap").contentType(MediaType.APPLICATION_JSON)
                .content(body(false, "", null))).hasStatusOk();
    }

    @Test
    void onlyAdminsManageTheDirectory() {
        assertThat(as("viewer", Role.USER).get().uri("/api/v1/admin/ldap")).hasStatus(403);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw test -Dtest=LdapAdminApiTest`
Expected: 404 for `/api/v1/admin/ldap`, or a compile failure for the new types.

- [ ] **Step 3: Implement**

`src/main/java/com/graphify/auth/LdapConfigView.java`:

```java
package com.graphify.auth;

import java.time.Instant;

/** The LDAP configuration as the API shows it: never the bind password, only whether one is set (spec §6.4). */
public record LdapConfigView(
        boolean enabled,
        String url,
        String baseDn,
        String userSearchBase,
        String userSearchFilter,
        String userQueryFilter,
        String usernameAttr,
        String displayNameAttr,
        String emailAttr,
        String bindDn,
        boolean bindPasswordSet,
        String updatedBy,
        Instant updatedAt) {
}
```

`src/main/java/com/graphify/auth/LdapConfigUpdate.java`:

```java
package com.graphify.auth;

/** A full PUT document; {@code bindPassword} null keeps the stored secret and "" clears it (spec §6.4). */
public record LdapConfigUpdate(
        Boolean enabled,
        String url,
        String baseDn,
        String userSearchBase,
        String userSearchFilter,
        String userQueryFilter,
        String usernameAttr,
        String displayNameAttr,
        String emailAttr,
        String bindDn,
        String bindPassword) {

    @Override
    public String toString() {
        return "LdapConfigUpdate[enabled=" + enabled + ", url=" + url + ", baseDn=" + baseDn + "]";
    }
}
```

Add to `src/main/java/com/graphify/auth/LdapConfigRepository.java` (with imports `java.time.OffsetDateTime`):

```java
    public LdapConfigView view() {
        return jdbc.queryForObject("""
                SELECT enabled, url, base_dn, user_search_base, user_search_filter, user_query_filter, username_attr,
                       display_name_attr, email_attr, bind_dn, bind_password_enc, updated_by, updated_at
                  FROM ldap_config WHERE id = 1
                """, (rs, row) -> {
                    OffsetDateTime updatedAt = rs.getObject("updated_at", OffsetDateTime.class);
                    return new LdapConfigView(rs.getInt("enabled") == 1, rs.getString("url"), rs.getString("base_dn"),
                            rs.getString("user_search_base"), rs.getString("user_search_filter"),
                            rs.getString("user_query_filter"), rs.getString("username_attr"),
                            rs.getString("display_name_attr"), rs.getString("email_attr"), rs.getString("bind_dn"),
                            rs.getString("bind_password_enc") != null, rs.getString("updated_by"),
                            updatedAt == null ? null : updatedAt.toInstant());
                });
    }

    public void save(LdapSettings settings, String actor) {
        String password = settings.bindPassword();
        jdbc.update("""
                UPDATE ldap_config SET enabled = ?, url = ?, base_dn = ?, user_search_base = ?, user_search_filter = ?,
                       user_query_filter = ?, username_attr = ?, display_name_attr = ?, email_attr = ?, bind_dn = ?,
                       bind_password_enc = ?, updated_by = ?, updated_at = SYSTIMESTAMP
                 WHERE id = 1
                """, settings.enabled() ? 1 : 0, settings.url(), settings.baseDn(), settings.userSearchBase(),
                settings.userSearchFilter(), settings.userQueryFilter(), settings.usernameAttr(),
                settings.displayNameAttr(), settings.emailAttr(), settings.bindDn(),
                password == null || password.isEmpty() ? null : cipher.encrypt(password), actor);
    }
```

`src/main/java/com/graphify/auth/LdapAdministration.java`:

```java
package com.graphify.auth;

import com.graphify.audit.AuditLog;
import com.graphify.common.exception.InvalidRequestException;
import com.graphify.common.util.Utf8;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin management of the LDAP connection (spec §10.6 "LDAP"); secrets are never returned or audited. */
@Service
public class LdapAdministration {

    /** Widths of the ldap_config text columns in V6__authentication.sql. */
    private static final int TEXT_BYTES = 1000;
    private static final int ATTR_BYTES = 100;

    /** Placeholder every configured filter must contain for the (encoded) user input. */
    private static final String PLACEHOLDER = "{0}";

    private final LdapConfigRepository config;
    private final LdapDirectory directory;
    private final AuditLog auditLog;

    public LdapAdministration(LdapConfigRepository config, LdapDirectory directory, AuditLog auditLog) {
        this.config = config;
        this.directory = directory;
        this.auditLog = auditLog;
    }

    public LdapConfigView get() {
        return config.view();
    }

    @Transactional
    public LdapConfigView update(LdapConfigUpdate update, String actor) {
        LdapSettings current = config.load();
        LdapSettings next = merge(update, current);
        config.save(next, actor);
        auditLog.record(actor, "LDAP_CONFIG_UPDATED", "ldap_config", "changed: " + String.join(", ",
                changedFields(current, next)));
        return config.view();
    }

    public void test(LdapConfigUpdate candidate) {
        directory.test(candidate == null ? config.load() : merge(candidate, config.load()));
    }

    private static LdapSettings merge(LdapConfigUpdate update, LdapSettings current) {
        if (update == null || update.enabled() == null) {
            throw new InvalidRequestException("enabled is required");
        }
        boolean enabled = update.enabled();
        String url = blankToNull(update.url());
        String baseDn = blankToNull(update.baseDn());
        if (enabled) {
            requireLdapUrl(url);
            if (baseDn == null) {
                throw new InvalidRequestException("baseDn is required when LDAP is enabled");
            }
        }
        String userSearchFilter = filter("userSearchFilter", update.userSearchFilter());
        String userQueryFilter = filter("userQueryFilter", update.userQueryFilter());
        String password = update.bindPassword() == null ? current.bindPassword() : update.bindPassword();
        LdapSettings next = new LdapSettings(enabled, url, baseDn, blankToNull(update.userSearchBase()),
                userSearchFilter, userQueryFilter, attribute("usernameAttr", update.usernameAttr()),
                attribute("displayNameAttr", update.displayNameAttr()), attribute("emailAttr", update.emailAttr()),
                blankToNull(update.bindDn()), password);
        for (String text : new String[] {next.url(), next.baseDn(), next.userSearchBase(), next.bindDn()}) {
            fits(text, TEXT_BYTES);
        }
        return next;
    }

    private static void requireLdapUrl(String url) {
        if (url == null) {
            throw new InvalidRequestException("url is required when LDAP is enabled");
        }
        try {
            String scheme = new URI(url).getScheme();
            if (!"ldap".equalsIgnoreCase(scheme) && !"ldaps".equalsIgnoreCase(scheme)) {
                throw new InvalidRequestException("url must start with ldap:// or ldaps://");
            }
        } catch (URISyntaxException e) {
            throw new InvalidRequestException("url is not a valid URI");
        }
    }

    private static String filter(String field, String value) {
        if (value == null || value.isBlank() || !value.contains(PLACEHOLDER)) {
            throw new InvalidRequestException(field + " must contain " + PLACEHOLDER);
        }
        return fits(value.strip(), TEXT_BYTES);
    }

    private static String attribute(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidRequestException(field + " is required");
        }
        return fits(value.strip(), ATTR_BYTES);
    }

    private static String fits(String value, int maxBytes) {
        if (value != null && Utf8.byteLength(value) > maxBytes) {
            throw new InvalidRequestException("A value is longer than " + maxBytes + " bytes");
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** Names of the changed fields; the bind password is named, its value never shown. */
    private static List<String> changedFields(LdapSettings before, LdapSettings after) {
        List<String> changed = new ArrayList<>();
        if (before.enabled() != after.enabled()) {
            changed.add("enabled");
        }
        String[][] pairs = {
            {"url", before.url(), after.url()}, {"baseDn", before.baseDn(), after.baseDn()},
            {"userSearchBase", before.userSearchBase(), after.userSearchBase()},
            {"userSearchFilter", before.userSearchFilter(), after.userSearchFilter()},
            {"userQueryFilter", before.userQueryFilter(), after.userQueryFilter()},
            {"usernameAttr", before.usernameAttr(), after.usernameAttr()},
            {"displayNameAttr", before.displayNameAttr(), after.displayNameAttr()},
            {"emailAttr", before.emailAttr(), after.emailAttr()}, {"bindDn", before.bindDn(), after.bindDn()},
            {"bindPassword", before.bindPassword(), after.bindPassword()}};
        for (String[] pair : pairs) {
            if (!Objects.equals(pair[1], pair[2])) {
                changed.add(pair[0]);
            }
        }
        return changed.isEmpty() ? List.of("nothing") : changed;
    }
}
```

`src/main/java/com/graphify/auth/LdapAdminController.java`:

```java
package com.graphify.auth;

import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** LDAP connection management (spec §10.6 "LDAP"). */
@RestController
@RequestMapping("/api/v1/admin/ldap")
public class LdapAdminController {

    private final LdapAdministration administration;

    public LdapAdminController(LdapAdministration administration) {
        this.administration = administration;
    }

    @GetMapping
    public LdapConfigView get() {
        return administration.get();
    }

    @PutMapping
    public LdapConfigView update(@RequestBody(required = false) LdapConfigUpdate update,
            Authentication authentication) {
        return administration.update(update, authentication.getName());
    }

    /** Tests the submitted configuration (stored bind password when none is given), or the stored one without a body. */
    @PostMapping("/test")
    public Map<String, Boolean> test(@RequestBody(required = false) LdapConfigUpdate candidate) {
        administration.test(candidate);
        return Map.of("ok", true);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw test -Dtest=LdapAdminApiTest`
Expected: 4 tests pass.

Run: `./mvnw test`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main src/test
git commit -m "feat(auth): manage and test the LDAP connection without exposing its secret" -m "<your harness Co-Authored-By trailer>"
```

---

### Task 8: Audit log API and documentation

**Files:**
- Create: `src/main/java/com/graphify/audit/AuditEntry.java`, `AuditQueries.java`, `AuditController.java`
- Modify: `README.md`
- Test: `src/test/java/com/graphify/audit/AuditApiTest.java`

**Interfaces:**
- **Consumes:** `AuditLog.record`, `PagingResolver`, `Page`, `InvalidRequestException`.
- **Produces:**
  - `public record AuditEntry(long id, String actor, String action, String target, String details, Instant at)`.
  - `AuditQueries` (a `@Repository`) with `Page<AuditEntry> list(String actor, String action, Instant from, Instant to, Paging)`.
    - `actor` and `action` match exactly, case-insensitively.
    - `from` is inclusive and `to` exclusive.
    - Results are newest first (`at DESC, id DESC`).
  - `GET /api/v1/admin/audit?actor=&action=&from=&to=&page=&size=` returns `Page<AuditEntry>`. `from` and `to` are ISO-8601 instants; anything else is 400.
- **README:** an "Authentication" section covering:
  - the sign-in flow: `GET /api/v1/auth/csrf`, then `POST /api/v1/auth/login` with `X-XSRF-TOKEN`, then the session cookie;
  - the bootstrap admin and `APP_BOOTSTRAP_ADMIN_PASSWORD`;
  - the roles and the must-change rule;
  - LDAP configuration through `/api/v1/admin/ldap`;
  - the lockout settings.

  It also adds the new endpoints to the API table.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/graphify/audit/AuditApiTest.java`:

```java
package com.graphify.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.auth.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AuditApiTest extends OracleIntegrationTest {

    @Autowired
    AuditLog auditLog;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM audit_log WHERE action LIKE 'AUDIT_TEST%'");
        auditLog.record("alice", "AUDIT_TEST_ONE", "x", "first");
        auditLog.record("bob", "AUDIT_TEST_TWO", "y", "second");
        jdbc.update("UPDATE audit_log SET at = TIMESTAMP '2026-01-01 10:00:00 +00:00' WHERE action = 'AUDIT_TEST_ONE'");
        jdbc.update("UPDATE audit_log SET at = TIMESTAMP '2026-01-02 10:00:00 +00:00' WHERE action = 'AUDIT_TEST_TWO'");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM audit_log WHERE action LIKE 'AUDIT_TEST%'");
    }

    @Test
    void filtersByActorActionAndTimeNewestFirst() {
        assertThat(mvc.get().uri("/api/v1/admin/audit?actor=ALICE")).hasStatusOk().bodyJson().satisfies(json -> {
            assertThat(json).extractingPath("$.items[0].action").isEqualTo("AUDIT_TEST_ONE");
            assertThat(json).extractingPath("$.total").isEqualTo(1);
        });
        assertThat(mvc.get().uri("/api/v1/admin/audit?action=audit_test_two")).hasStatusOk().bodyJson()
                .extractingPath("$.items[0].actor").isEqualTo("bob");
        assertThat(mvc.get().uri("/api/v1/admin/audit?from=2026-01-01T00:00:00Z&to=2026-01-03T00:00:00Z"))
                .hasStatusOk().bodyJson().satisfies(json -> {
                    assertThat(json).extractingPath("$.items[0].action").isEqualTo("AUDIT_TEST_TWO");
                    assertThat(json).extractingPath("$.items[1].action").isEqualTo("AUDIT_TEST_ONE");
                });
        assertThat(mvc.get().uri("/api/v1/admin/audit?from=2026-01-02T00:00:00Z&to=2026-01-02T10:00:00Z"))
                .hasStatusOk().bodyJson().extractingPath("$.total").isEqualTo(0);
    }

    @Test
    void rejectsBadTimesAndNonAdmins() {
        assertThat(mvc.get().uri("/api/v1/admin/audit?from=yesterday")).hasStatus(400);
        assertThat(as("viewer", Role.USER).get().uri("/api/v1/admin/audit")).hasStatus(403);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw test -Dtest=AuditApiTest`
Expected: 404 or a compile failure for the missing classes.

- [ ] **Step 3: Implement**

`src/main/java/com/graphify/audit/AuditEntry.java`:

```java
package com.graphify.audit;

import java.time.Instant;

public record AuditEntry(long id, String actor, String action, String target, String details, Instant at) {
}
```

`src/main/java/com/graphify/audit/AuditQueries.java`:

```java
package com.graphify.audit;

import com.graphify.api.Page;
import com.graphify.api.Paging;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read side of audit_log (spec §10.6 "Audit"). */
@Repository
public class AuditQueries {

    private final JdbcTemplate jdbc;

    public AuditQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Page<AuditEntry> list(String actor, String action, Instant from, Instant to, Paging paging) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (actor != null && !actor.isBlank()) {
            where.append(" AND UPPER(actor) = ?");
            args.add(actor.strip().toUpperCase(Locale.ROOT));
        }
        if (action != null && !action.isBlank()) {
            where.append(" AND UPPER(action) = ?");
            args.add(action.strip().toUpperCase(Locale.ROOT));
        }
        if (from != null) {
            where.append(" AND at >= ?");
            args.add(from.atOffset(ZoneOffset.UTC));
        }
        if (to != null) {
            where.append(" AND at < ?");
            args.add(to.atOffset(ZoneOffset.UTC));
        }
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(paging.offset());
        pageArgs.add(paging.size());
        List<AuditEntry> items = jdbc.query("SELECT id, actor, action, target, details, at FROM audit_log" + where
                + " ORDER BY at DESC, id DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY", (rs, row) -> {
                    OffsetDateTime at = rs.getObject("at", OffsetDateTime.class);
                    return new AuditEntry(rs.getLong("id"), rs.getString("actor"), rs.getString("action"),
                            rs.getString("target"), rs.getString("details"), at == null ? null : at.toInstant());
                }, pageArgs.toArray());
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log" + where, Long.class, args.toArray());
        return new Page<>(items, paging.page(), paging.size(), total == null ? 0 : total);
    }
}
```

`src/main/java/com/graphify/audit/AuditController.java`:

```java
package com.graphify.audit;

import com.graphify.api.Page;
import com.graphify.api.PagingResolver;
import com.graphify.common.exception.InvalidRequestException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The audit log for admins (spec §10.6 "Audit"); role checks live in SecurityConfiguration. */
@RestController
@RequestMapping("/api/v1/admin/audit")
public class AuditController {

    private final AuditQueries queries;
    private final PagingResolver paging;

    public AuditController(AuditQueries queries, PagingResolver paging) {
        this.queries = queries;
        this.paging = paging;
    }

    @GetMapping
    public Page<AuditEntry> list(@RequestParam(required = false) String actor,
            @RequestParam(required = false) String action, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return queries.list(actor, action, instant("from", from), instant("to", to), paging.resolve(page, size));
    }

    private static Instant instant(String name, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw new InvalidRequestException(name + " must be an ISO-8601 instant such as 2026-01-31T00:00:00Z");
        }
    }
}
```

- [ ] **Step 4: Document authentication in `README.md`**

Add an `## Authentication` section before `## API`:

```markdown
## Authentication

Every `/api/v1` endpoint except `GET /auth/csrf`, `POST /auth/login` and `GET /openapi.json` needs a signed-in session.

1. `GET /api/v1/auth/csrf` → `{headerName: "X-XSRF-TOKEN", token}` (starts a session).
2. `POST /api/v1/auth/login` with `{username, password}` and the `X-XSRF-TOKEN` header → the session cookie now
   carries the user; every later `POST`/`PUT`/`DELETE` sends the same header.
3. `POST /api/v1/auth/logout`; `GET /api/v1/auth/me`; `POST /api/v1/auth/change-password`.

- A local account is checked first; otherwise the user signs in through LDAP (configured at `/api/v1/admin/ldap`) and
  is created with role `USER` on first login. Roles live in the application, never in directory groups.
- First start: the local `admin` is created with `APP_BOOTSTRAP_ADMIN_PASSWORD`, or a generated password printed once
  in the log; it must be changed at first login (until then only `/auth/me`, `/auth/change-password` and `/auth/logout`
  answer; other calls get 403 with `code: PASSWORD_CHANGE_REQUIRED`).
- `ADMIN`: `/api/v1/admin/**`, starting and cancelling index runs; `USER`: everything else. At least one active admin
  always remains; the local admin can be deactivated only while an LDAP admin is active.
- Local accounts lock after `auth.max_failed_attempts` failures for `auth.lock_duration`; sessions last
  `auth.session_timeout`. Every login failure answers the same 401.
```

In the API table, add rows for:
- the session endpoints;
- `GET/POST /admin/users`, `PUT /admin/users/{id}/role`, `PUT /admin/users/{id}/active`, `GET /admin/ldap/users?q=`;
- `GET/PUT /admin/ldap`, `POST /admin/ldap/test`;
- `GET /admin/audit?actor=&action=&from=&to=`.

Use the table's existing relative path style.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=AuditApiTest`
Expected: 2 tests pass.

Run: `./mvnw test`
Expected: all tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main src/test README.md
git commit -m "feat(audit): expose the audit log to admins and document authentication" -m "<your harness Co-Authored-By trailer>"
```
