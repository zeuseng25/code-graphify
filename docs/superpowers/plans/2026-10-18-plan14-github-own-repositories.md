# Plan 14: GitHub — the Token Owner's Own Repositories Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A GitHub repo connection can also scan the repositories that the token's owner owns (a personal account such as `zeuseng25`), private ones included. The admin turns this on with a "Kendi repolarımı da tara" switch. Organizations become optional when the switch is on.

**Architecture:**
- **Schema:** V11 adds `scm_connection.include_own_repositories NUMBER(1) DEFAULT 0 NOT NULL` with a 0/1 check.
- **Model:** `ScmConnection`, `ScmConnectionUpdate` and `ScmConnectionView` carry the new field.
  - `ScmConnectionAdministration.validate` accepts a GitHub connection with organizations, the flag, or both.
  - For Bitbucket and Git, the flag must be false.
- **Listing:** `GitHubClient` pages `GET {baseUrl}/user/repos?affiliation=owner&visibility=all` next to the organization listings.
  - The paging, retry, rate-limit, clone-URL, archived/disabled and exclude rules are the same ones the organization listing uses.
  - Results are merged and de-duplicated by `owner/name`, ignoring case.
- **Connection test:** `test()` without organizations calls `GET {baseUrl}/user` once.
- **UI:** a `Switch` on the GitHub form. The organizations field is required only while the switch is off.

**Tech Stack:**
- Backend: Spring Boot 4.1.1, `RestClient` on the JDK `HttpClient`, Flyway, JdbcTemplate. Tests use JUnit 5 and Testcontainers Oracle, plus `FakeGitHub` (`com.sun.net.httpserver`).
- Frontend: React 19, Mantine 9 `Switch`, Vitest + MSW.
- No new dependency.

**Spec:**
- `docs/superpowers/specs/2026-10-07-repo-connection-types-design.md` §6. That section was added on 2026-10-07 in commit 09ef5b9.
- Background: §1–§5 of the same file. Plan 13 is `docs/superpowers/plans/2026-10-17-plan13-repo-connection-types.md`; its follow-ups are in `2026-10-17-plan13-followups.md`.

**Why (from the first real run):** the user's GitHub connection named `Zeus` as its organization. GitHub answered 404 for `/orgs/Zeus/repos`, because `Zeus` is not an organization; the user's repositories are under the personal account `zeuseng25`. `/orgs/{name}/repos` only lists organizations.

**Code facts the plan relies on (read from main at 09ef5b9):**
- **`ScmConnection`:**
  - The canonical record is `(id, name, type, baseUrl, username, secret, includeProjects, excludeRepos, repositoryUrls)`. An 8-argument constructor without `repositoryUrls` exists for tests.
  - It is built in `ScmConnections.map` and in tests: `RepositoryFilterTest` (3×), `ScmConnectionTest`, `BitbucketDataCenterClientTest`, `GitConnectionClientTest`, `GitHubClientTest`, `RepositorySyncTest`.
- **`ScmConnectionUpdate`:** `(name, type, baseUrl, username, secret, includeProjects, excludeRepos, repositoryUrls, Boolean enabled)`. It is built directly only in `ScmConnectionValidationTest.update(...)`; the admin API tests post JSON.
- **`ScmConnectionView`:** built only in `ScmConnections.view(ResultSet)`.
- **`ScmConnections.insert/update`:** take `(…, includeProjects, excludeRepos, repositoryUrls, boolean enabled)`. The callers are `ScmConnectionAdministration` (create, applyUpdate) and `ScmConnectionsTest` (lines ~70, 76, 88, 94).
- **`ScmConnectionAdministration.validate`:**
  - `GITHUB` requires `includeProjects` to be non-empty, with the message "includeProjects must name at least one GitHub organization". `ScmConnectionAdminApiTest.aGitHubConnectionNeedsATokenAndAnOrganization` asserts it contains "at least one GitHub organization".
  - `changed()` lists the changed fields for the audit log.
- **`RepositoryFilter.accepts`:** when `includeProjects` is non-empty, it drops any repository whose `projectKey` is not in it. This is why own repositories must not go through it (see Rulings).
- **`GitHubClient`:**
  - `listRepositories` throws when `includeProjects` is empty.
  - `listOrganization` pages with `fetched` loop protection, uses `checkedNext` (origin plus userinfo), skips archived/disabled/invalid entries, applies `acceptableCloneUrl` (masked WARN) and then `RepositoryFilter.accepts`.
  - `withRetry(connection, org, call, retries)` turns a 404 into "GitHub organization '<org>' was not found…".
  - `fetch` is typed `List<Repo>`.
  - `test()` sends one request for the first organization.
- **`FakeGitHub`:**
  - It serves only the `/orgs/` context. Repos are `(org, name, cloneUrl, archived, disabled)`.
  - It records the requested orgs, pages, per_page values, authorizations, Accept headers and API versions.
  - `nextLinkOverride` applies to page 1, and `redirectTo`, `failNext` and `failureHeader` exist.
- **API contract:** in `frontend/src/api/schema.d.ts`, every `ScmConnectionUpdate`/`ScmConnectionView` field is optional (`?`), so adding a field causes no type fallout. To refresh: `./mvnw test -Dtest=OpenApiSnapshotTest -Dopenapi.update=true`, then `cd frontend && npm run generate:api`. `npm test` runs `check:api`.
- **UI:** `ScmConnectionPage.tsx`:
  - It exports `FormValues` and `connectionBody(values, secret)`; `connectionBody.test.ts` builds a `base: FormValues`.
  - The type `Select`'s `onChange` clears the unused fields and calls `form.clearErrors()`.
  - Validation: `includeProjects` is required for GITHUB.

**Rulings taken while planning:**
- **`includeOwnRepositories` is a nullable `Boolean` in `ScmConnectionUpdate`; null means false.** Older clients and every existing JSON test body omit it and keep working. The view and the stored row use a plain `boolean`.
- **Own repositories bypass the `includeProjects` filter; excludes still apply.** Their `projectKey` is the user's login, which is not an organization in `includeProjects`. `RepositoryFilter` gains `excluded(connection, repository)`, which `accepts` reuses. The own listing calls `!excluded(...)`.
- **The listing order is organizations first, then own repositories.** De-duplication keeps the first occurrence by `owner/name` (lower-cased with `Locale.ROOT`).
- **`affiliation=owner&visibility=all` and `/user` are GitHub protocol facts** (like `/orgs/{org}/repos`), not operational values. They are named constants. `per_page` still comes from `scm.page_size`.
- **Error subjects:** `withRetry` takes a subject string instead of an org name:
  - for organizations, `"organization '<org>'"`; the existing 404 text "GitHub organization '<org>' was not found or is not visible to connection '<name>'" stays the same.
  - for own repositories, `"user of this token"`.
- **`test()`:** with organizations, the unchanged single organization request is sent. Without organizations but with the flag, one `GET {baseUrl}/user` is sent with no retry and the same 401/403/rate-limit mapping. With neither (only reachable from a hand-edited row), it throws `ScmException`.
- **No migration of existing rows.** The default is 0, so existing connections behave exactly as before.
- **The UI sends `includeOwnRepositories: false` for every type except GITHUB.** A type switch on a new connection clears the flag along with the other fields.
- **An own login typed as an organization still fails with 404.** This keeps the Plan 13 rule ("a missing organization fails the listing"). The hint text tells the admin to use the switch for their own account.

## Plan series

| Plan | Scope | Status |
|---|---|---|
| 1–8 | Backend | merged |
| 9–12 | Web foundation, user screens, repository graph screen, admin screens | merged |
| 13 | Repo connection types (Bitbucket, GitHub, Git) | merged |
| **14** | **GitHub: the token owner's own repositories** (this plan) | — |
| later | LLM purpose/misuse layer (separate spec) | — |

## Global Constraints

- **Environment:**
  - Backend: `export JAVA_HOME=/opt/homebrew/opt/openjdk@25`; `./mvnw -q test -Dfrontend.skip=true` (or a `-Dtest=` subset while iterating). Oracle comes from Testcontainers.
  - Frontend: commands run in `frontend/` (Node 24, npm 11). `npm test` runs `check:api`, the typecheck, lint and Vitest; `npm run build` must pass.
  - Both suites are green at the end of every task.
  - No new dependency.
- **"Kodda sabit değer yok" (no hardcoded values):**
  - Page size, retries, backoff and timeouts come from `scm.*` settings. URLs come from the connection.
  - Allowed: GitHub protocol facts (`/user`, `/user/repos`, `affiliation=owner`, `visibility=all`, media type, API version), column widths with their migration named in a comment, and API enum names.
- **Secrets:**
  - Never in logs, exception messages, responses, audit rows, the URL, browser storage or the query cache.
  - Exceptions mask URLs with `UrlMasking.mask`.
  - Tests assert that a failing call's message does not contain the token.
  - The Bearer token only goes to the `baseUrl` origin. Next links are checked with `checkedNext`, and redirects are never followed.
- **Text:**
  - Every user-visible text lives in `frontend/src/i18n/tr.ts`, in Turkish sentences.
  - Software terms stay English (repo, token, organization names are data).
  - `frontend/src/i18n/terms.test.ts` must keep passing.
  - Backend messages are English.
- **Patterns:**
  - **Frontend (Plans 9–13):** data-first rendering; `gcTime: 0` plus `reset()` for the secret-carrying save; assertions through `tr`.
  - **Backend:** `InvalidRequestException` → 400; audit through `AuditLog.record` without secrets.
- **Running test environment:** the user is using a test WildFly on port 9080 (`/tmp/wildfly-gate/wildfly-41.0.0.Final`) with Oracle container `graphify-wildfly-db` on 1522.
  - Never stop, restart or redeploy them. The controller redeploys after the merge, with the user's consent.
  - Never touch the user's WildFly on 8080 or container `fw-batch-oracle`.
- **Staging:** stage paths explicitly; never `git add -A` / `git add .` at the root.
- **Commits** end with:
  ```
  Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01XpYgYKeBYFNw4tb6EwJ44V
  ```

## Review Focus

1. **Own repositories are kept when organizations are also listed.** The include filter would otherwise drop them, because the user's login is not an organization. Test: Task 2 `GitHubClientTest` "lists own repositories next to the organizations".
2. **The token stays on the API origin while paging `/user/repos`.** A next link to another origin fails, and the other server receives nothing. Test: Task 2 `GitHubClientTest` "refuses an own-repository next link on another host".
3. **Neither organizations nor the flag is a 400 for GitHub; the flag on Bitbucket or Git is a 400.** Test: Task 1 `ScmConnectionValidationTest` and the admin API test.
4. **The own-only connection test makes exactly one `/user` request.** A rejected token is AUTH_FAILED, and the message has no token. Test: Task 2 `GitHubClientTest` "tests an own-only connection with one user request", plus the admin API test.
5. **The form sends the flag only for GitHub.** Switching the type clears it, and with the switch on, the organizations field may stay empty. Test: Task 3 `connectionBody.test.ts` and `scmConnections.test.tsx`.

---

## File Structure

| File | Responsibility |
|---|---|
| `src/main/resources/db/migration/V11__github_own_repositories.sql` (new) | `include_own_repositories` column |
| `src/main/java/com/graphify/scm/ScmConnection.java`, `ScmConnectionUpdate.java`, `ScmConnectionView.java` (modify) | The new field |
| `scm/ScmConnections.java` (modify) | Read and write the column |
| `scm/ScmConnectionAdministration.java` (modify) | Validation and audit of the flag |
| `scm/RepositoryFilter.java` (modify) | `excluded(...)` |
| `scm/GitHubClient.java` (modify) | Own-repository listing, merge, `/user` test |
| `src/test/java/com/graphify/testsupport/FakeGitHub.java` (modify) | `/user` and `/user/repos` |
| `src/test/java/com/graphify/scm/…Test.java`, `store/SchemaMigrationTest.java` (modify) | Tests |
| `frontend/openapi.json`, `frontend/src/api/schema.d.ts` (regenerated) | API contract |
| `frontend/src/i18n/tr.ts`, `frontend/src/features/admin/scm/ScmConnectionPage.tsx`, `connectionBody.test.ts`, `scmConnections.test.tsx` (modify) | Switch and body |
| `README.md` (modify) | Document the flag |

---

### Task 1: Schema, field and validation

**Files:**
- Create: `src/main/resources/db/migration/V11__github_own_repositories.sql`
- Modify: `src/main/java/com/graphify/scm/ScmConnection.java`, `ScmConnectionUpdate.java`, `ScmConnectionView.java`, `ScmConnections.java`, `ScmConnectionAdministration.java`
- Modify (tests): `src/test/java/com/graphify/scm/ScmConnectionValidationTest.java`, `ScmConnectionsTest.java`, `ScmConnectionAdminApiTest.java`, `src/test/java/com/graphify/store/SchemaMigrationTest.java`
- Regenerate: `frontend/openapi.json`, `frontend/src/api/schema.d.ts`

**Interfaces:**
- Produces:
  - `ScmConnection` canonical record: `(long id, String name, ScmType type, String baseUrl, String username, String secret, List<String> includeProjects, List<String> excludeRepos, List<String> repositoryUrls, boolean includeOwnRepositories)`. The 8- and 9-argument constructors stay and pass `false`.
  - `ScmConnectionUpdate`: `(name, type, baseUrl, username, secret, includeProjects, excludeRepos, repositoryUrls, Boolean includeOwnRepositories, Boolean enabled)`.
  - `ScmConnectionView`: `boolean includeOwnRepositories` goes right after `repositoryUrls`.
  - `ScmConnections.insert/update(…, List<String> repositoryUrls, boolean includeOwnRepositories, boolean enabled)`.
- Task 2 reads `connection.includeOwnRepositories()`. Task 3 reads `view.includeOwnRepositories` and sends `includeOwnRepositories`.

- [ ] **Step 1: Write the failing tests**

`ScmConnectionValidationTest`: change the helper to pass the flag, then add tests.

```java
    private static ScmConnectionUpdate update(ScmType type, List<String> include, List<String> exclude,
            List<String> urls) {
        return update(type, include, exclude, urls, null);
    }

    private static ScmConnectionUpdate update(ScmType type, List<String> include, List<String> exclude,
            List<String> urls, Boolean own) {
        return new ScmConnectionUpdate("c", type, BASE, null, null, include, exclude, urls, own, true);
    }

    @Test
    void aGitHubConnectionMayListOnlyTheTokenOwnersRepositories() {
        ScmConnectionAdministration.Valid valid = ScmConnectionAdministration.validate(
                update(ScmType.GITHUB, List.of(), List.of("zeuseng25/old-*"), List.of(), true));

        assertThat(valid.includeProjects()).isEmpty();
        assertThat(valid.includeOwnRepositories()).isTrue();
    }

    @Test
    void aGitHubConnectionNeedsAnOrganizationOrItsOwnRepositories() {
        rejects(update(ScmType.GITHUB, List.of(), List.of(), List.of(), false), "at least one GitHub organization");
        rejects(update(ScmType.GITHUB, List.of(), List.of(), List.of(), null), "includeOwnRepositories");
    }

    @Test
    void onlyGitHubListsTheTokenOwnersRepositories() {
        rejects(update(ScmType.BITBUCKET_DC, List.of(), List.of(), List.of(), true), "includeOwnRepositories");
        rejects(update(ScmType.GIT, List.of(), List.of(), List.of(BASE + "/a/b.git"), true),
                "includeOwnRepositories");
        assertThat(ScmConnectionAdministration.validate(update(ScmType.GITHUB, List.of("acme"), List.of(), List.of(),
                null)).includeOwnRepositories()).isFalse();
    }
```

The existing `aGitHubConnectionNeedsAnOrganization` keeps passing, because a null flag means false.

`ScmConnectionsTest`: in the four `insert`/`update` calls (lines ~70, 76, 88, 94), insert `false` before the `enabled` argument. Add a round-trip test:

```java
    @Test
    void storesWhetherTheTokenOwnersRepositoriesAreListed() {
        long id = connections.insert("own", ScmType.GITHUB, "https://api.github.test", null, "tok", List.of(),
                List.of(), List.of(), true, true);
        try {
            assertThat(connections.find(id).orElseThrow().includeOwnRepositories()).isTrue();
            assertThat(connections.view(id).orElseThrow().includeOwnRepositories()).isTrue();

            connections.update(id, "own", ScmType.GITHUB, "https://api.github.test", null, "tok", List.of("acme"),
                    List.of(), List.of(), false, true);
            assertThat(connections.find(id).orElseThrow().includeOwnRepositories()).isFalse();
        } finally {
            jdbc.update("DELETE FROM scm_connection WHERE id = ?", id);
        }
    }
```

If `ScmConnectionsTest` has no `jdbc` field, it inherits one from `OracleIntegrationTest` (as `ScmConnectionAdminApiTest` uses it). If its cleanup already deletes all `scm_connection` rows in `@AfterEach`, drop the `try/finally`.

`ScmConnectionAdminApiTest`: add a body helper and a test.

```java
    private String ownGitHubBody(String type, String orgs, boolean own) {
        return """
                {"name":"own-%s","type":"%s","baseUrl":"https://github.corp/api/v3","username":null,"secret":"ghp_x",
                 "includeProjects":%s,"excludeRepos":[],"repositoryUrls":[],"includeOwnRepositories":%s,"enabled":true}
                """.formatted(type.toLowerCase(java.util.Locale.ROOT), type, orgs, own);
    }

    @Test
    void aGitHubConnectionCanListOnlyTheTokenOwnersRepositories() throws Exception {
        MvcTestResult created = mvc.post().uri("/api/v1/admin/scm-connections").contentType(MediaType.APPLICATION_JSON)
                .content(ownGitHubBody("GITHUB", "[]", true)).exchange();
        assertThat(created).hasStatus(201).bodyJson().extractingPath("$.includeOwnRepositories").isEqualTo(true);

        assertThat(mvc.post().uri("/api/v1/admin/scm-connections").contentType(MediaType.APPLICATION_JSON)
                .content(ownGitHubBody("BITBUCKET_DC", "[]", true))).hasStatus(400).bodyText()
                .contains("includeOwnRepositories");
    }
```

`SchemaMigrationTest`: add next to `connectionTypesCoverBitbucketGitHubAndGit`.

```java
    @Test
    void ownRepositoriesAreOffByDefault() {
        try {
            jdbc.update("INSERT INTO scm_connection (name, type, base_url) VALUES ('v11-gh', 'GITHUB', 'https://gh')");
            assertThat(jdbc.queryForObject("SELECT include_own_repositories FROM scm_connection WHERE name = 'v11-gh'",
                    Integer.class)).isZero();
            assertThatThrownBy(() -> jdbc.update("INSERT INTO scm_connection (name, type, base_url, "
                    + "include_own_repositories) VALUES ('v11-bad', 'GITHUB', 'https://gh', 2)"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.update("DELETE FROM scm_connection WHERE name LIKE 'v11-%'");
        }
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw -q test -Dfrontend.skip=true -Dtest='ScmConnectionValidationTest,ScmConnectionsTest,ScmConnectionAdminApiTest,SchemaMigrationTest'`
Expected: compilation fails (no 10-argument `ScmConnectionUpdate`, no `includeOwnRepositories()`).

- [ ] **Step 3: Migration**

`src/main/resources/db/migration/V11__github_own_repositories.sql`:

```sql
-- V11: GitHub connections may also list the repositories the token's owner owns (spec 2026-10-07 §6)
ALTER TABLE scm_connection ADD (include_own_repositories NUMBER(1) DEFAULT 0 NOT NULL
    CONSTRAINT ck_scm_connection_own_repos CHECK (include_own_repositories IN (0, 1)));
```

- [ ] **Step 4: Records**

`ScmConnection.java`: the canonical components gain `boolean includeOwnRepositories` at the end. The compact constructor is unchanged. Replace the 8-argument constructor and add a 9-argument one.

```java
public record ScmConnection(long id, String name, ScmType type, String baseUrl, String username, String secret,
        List<String> includeProjects, List<String> excludeRepos, List<String> repositoryUrls,
        boolean includeOwnRepositories) {

    // compact constructor unchanged

    public ScmConnection(long id, String name, ScmType type, String baseUrl, String username, String secret,
            List<String> includeProjects, List<String> excludeRepos, List<String> repositoryUrls) {
        this(id, name, type, baseUrl, username, secret, includeProjects, excludeRepos, repositoryUrls, false);
    }

    public ScmConnection(long id, String name, ScmType type, String baseUrl, String username, String secret,
            List<String> includeProjects, List<String> excludeRepos) {
        this(id, name, type, baseUrl, username, secret, includeProjects, excludeRepos, List.of(), false);
    }
```

`toString()` stays as is.

`ScmConnectionUpdate.java`: add `Boolean includeOwnRepositories,` between `List<String> repositoryUrls,` and `Boolean enabled`, with this Javadoc line on the record: `{@code includeOwnRepositories} null means false.`

`ScmConnectionView.java`: add `boolean includeOwnRepositories,` right after `List<String> repositoryUrls,`.

- [ ] **Step 5: Storage**

`ScmConnections.java`:
- Add `include_own_repositories` to `SELECT` (after `repository_urls`) and to `VIEW` (after `c.repository_urls`, as `c.include_own_repositories`).
- In `map`, pass `rs.getInt("include_own_repositories") == 1` as the last argument.
- In `view`, pass `rs.getInt("include_own_repositories") == 1` right after the `splitLines(rs.getString("repository_urls"))` argument.

```java
    /** Stores a validated connection; {@code secret} is plaintext (encrypted here) or null. */
    public long insert(String name, ScmType type, String baseUrl, String username, String secret,
            List<String> includeProjects, List<String> excludeRepos, List<String> repositoryUrls,
            boolean includeOwnRepositories, boolean enabled) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO scm_connection (name, type, base_url, username, secret_enc, include_projects,
                                                exclude_repos, repository_urls, include_own_repositories, enabled)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[] {"id"});
            statement.setString(1, name);
            statement.setString(2, type.name());
            statement.setString(3, baseUrl);
            statement.setString(4, username);
            statement.setString(5, secret == null ? null : cipher.encrypt(secret));
            statement.setString(6, join(includeProjects));
            statement.setString(7, join(excludeRepos));
            statement.setString(8, joinLines(repositoryUrls));
            statement.setInt(9, includeOwnRepositories ? 1 : 0);
            statement.setInt(10, enabled ? 1 : 0);
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    public void update(long id, String name, ScmType type, String baseUrl, String username, String secret,
            List<String> includeProjects, List<String> excludeRepos, List<String> repositoryUrls,
            boolean includeOwnRepositories, boolean enabled) {
        jdbc.update("""
                UPDATE scm_connection SET name = ?, type = ?, base_url = ?, username = ?, secret_enc = ?,
                       include_projects = ?, exclude_repos = ?, repository_urls = ?, include_own_repositories = ?,
                       enabled = ?
                 WHERE id = ?
                """, name, type.name(), baseUrl, username, secret == null ? null : cipher.encrypt(secret),
                join(includeProjects), join(excludeRepos), joinLines(repositoryUrls), includeOwnRepositories ? 1 : 0,
                enabled ? 1 : 0, id);
    }
```

- [ ] **Step 6: Validation and audit**

`ScmConnectionAdministration.java`:
- The `Valid` record gains `boolean includeOwnRepositories` before `boolean enabled`.
- `create` and `applyUpdate` pass `valid.includeOwnRepositories()` to `connections.insert`/`update` before `valid.enabled()`.
- In `validate`, replace the per-type switch and the `return`:

```java
        boolean own = Boolean.TRUE.equals(update.includeOwnRepositories());
        switch (update.type()) {
            case BITBUCKET_DC -> {
                requireEmpty("repositoryUrls", urls);
                requireNoOwnRepositories(own);
            }
            case GITHUB -> {
                requireEmpty("repositoryUrls", urls);
                if (include.isEmpty() && !own) {
                    throw new InvalidRequestException("includeProjects must name at least one GitHub organization, "
                            + "or includeOwnRepositories must be true");
                }
            }
            case GIT -> {
                requireEmpty("includeProjects", include);
                requireEmpty("excludeRepos", exclude);
                requireNoOwnRepositories(own);
                if (urls.isEmpty()) {
                    throw new InvalidRequestException("repositoryUrls must list at least one clone URL");
                }
                urls = GitRepositoryUrls.check(baseUrl, urls).stream().map(GitRepositoryUrls.Named::url).toList();
            }
        }
        return new Valid(name, update.type(), baseUrl, username, include, exclude, urls, own, update.enabled());
```

Add next to `requireEmpty`:

```java
    private static void requireNoOwnRepositories(boolean own) {
        if (own) {
            throw new InvalidRequestException("includeOwnRepositories is only used by GitHub connections");
        }
    }
```

In `changed(...)`, after the `repositoryUrls` check:

```java
        if (before.includeOwnRepositories() != after.includeOwnRepositories()) {
            fields.add("includeOwnRepositories");
        }
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./mvnw -q test -Dfrontend.skip=true -Dtest='ScmConnectionValidationTest,ScmConnectionsTest,ScmConnectionAdminApiTest,SchemaMigrationTest'`
Expected: PASS.

- [ ] **Step 8: Refresh the API contract**

Run: `./mvnw -q test -Dfrontend.skip=true -Dtest=OpenApiSnapshotTest -Dopenapi.update=true`, then `cd frontend && npm run generate:api && npm test`.
Expected: `openapi.json` and `schema.d.ts` gain `includeOwnRepositories` on `ScmConnectionUpdate` and `ScmConnectionView`, and `npm test` passes. The new fields are optional in the generated types, so no frontend code changes. If the typecheck asks for a change anyway, make the smallest fix and list it in the report.

- [ ] **Step 9: Full suites, then commit**

Run: `./mvnw -q test -Dfrontend.skip=true` and `cd frontend && npm test && npm run build`. Expected: both green.

```bash
git add src/main/resources/db/migration/V11__github_own_repositories.sql \
  src/main/java/com/graphify/scm/ScmConnection.java src/main/java/com/graphify/scm/ScmConnectionUpdate.java \
  src/main/java/com/graphify/scm/ScmConnectionView.java src/main/java/com/graphify/scm/ScmConnections.java \
  src/main/java/com/graphify/scm/ScmConnectionAdministration.java \
  src/test/java/com/graphify/scm/ScmConnectionValidationTest.java src/test/java/com/graphify/scm/ScmConnectionsTest.java \
  src/test/java/com/graphify/scm/ScmConnectionAdminApiTest.java src/test/java/com/graphify/store/SchemaMigrationTest.java \
  frontend/openapi.json frontend/src/api/schema.d.ts
git commit -m "feat(scm): GitHub connections can include the token owner's own repositories" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01XpYgYKeBYFNw4tb6EwJ44V"
```

---

### Task 2: GitHub client — own-repository listing and the `/user` test

**Files:**
- Modify: `src/main/java/com/graphify/scm/GitHubClient.java`, `src/main/java/com/graphify/scm/RepositoryFilter.java`
- Modify (tests): `src/test/java/com/graphify/testsupport/FakeGitHub.java`, `src/test/java/com/graphify/scm/GitHubClientTest.java`, `src/test/java/com/graphify/scm/RepositoryFilterTest.java`, `src/test/java/com/graphify/scm/ScmConnectionAdminApiTest.java`

**Interfaces:**
- Consumes: `ScmConnection.includeOwnRepositories()` (Task 1).
- Produces: `RepositoryFilter.excluded(ScmConnection, RemoteRepository)` (package-private static). No API change.

- [ ] **Step 1: Extend FakeGitHub**

In `FakeGitHub.java`:
- Add an `own` flag to the internal record: `private record Repo(String org, String name, String cloneUrl, boolean archived, boolean disabled, boolean own)`. Update every existing `new Repo(...)` to pass `false` last.
- Add the members below.
- Register the context in `start()`: `server.createContext("/user", this::handle);` (this prefix also covers `/user/repos`).
- Replace the body of `handle` from the `String[] path = …` line up to the `respond(exchange, 200, …)` call with the version below.

```java
    private volatile String ownerLogin = "octo";
    private final List<String> requestedPaths = new ArrayList<>();
    private final List<String> requestedQueries = new ArrayList<>();

    /** The login GET /user answers and the owner of the repositories added with {@link #addOwnRepository}. */
    public FakeGitHub ownerLogin(String login) {
        ownerLogin = login;
        return this;
    }

    public synchronized FakeGitHub addOwnRepository(String name, String cloneUrl) {
        repos.add(new Repo(null, name, cloneUrl, false, false, true));
        return this;
    }

    public synchronized FakeGitHub addOwnArchived(String name, String cloneUrl) {
        repos.add(new Repo(null, name, cloneUrl, true, false, true));
        return this;
    }

    public synchronized List<String> requestedPaths() {
        return List.copyOf(requestedPaths);
    }

    public synchronized List<String> requestedQueries() {
        return List.copyOf(requestedQueries);
    }
```

```java
    private void handle(HttpExchange exchange) throws IOException {
        String requestPath = exchange.getRequestURI().getPath();
        boolean user = requestPath.equals("/user");
        boolean ownRepos = requestPath.equals("/user/repos");
        String[] path = requestPath.split("/");
        // ["", "orgs", org, "repos"] or ["", "user", "repos"] or ["", "user"]
        String org = ownRepos || user ? ownerLogin : path.length > 2 ? path[2] : "";
        int perPage = intParam(exchange.getRequestURI(), "per_page", 30);
        int page = intParam(exchange.getRequestURI(), "page", 1);
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        synchronized (this) {
            requestedPaths.add(requestPath);
            requestedQueries.add(exchange.getRequestURI().getRawQuery());
            requestedOrgs.add(org);
            requestedPages.add(Integer.toString(page));
            requestedPerPage.add(Integer.toString(perPage));
            authorizations.add(authorization);
            accepts.add(exchange.getRequestHeaders().getFirst("Accept"));
            apiVersions.add(exchange.getRequestHeaders().getFirst("X-GitHub-Api-Version"));
        }
        if (failuresLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            if (failureHeaderName != null) {
                exchange.getResponseHeaders().add(failureHeaderName, failureHeaderValue);
            }
            respond(exchange, failureStatus, "{\"message\":\"injected\"}", null);
            return;
        }
        if (redirectUrl != null) {
            exchange.getResponseHeaders().add("Location", redirectUrl);
            respond(exchange, 301, "", null);
            return;
        }
        if (requiredAuthorization != null && !requiredAuthorization.equals(authorization)) {
            respond(exchange, 401, "{\"message\":\"Bad credentials\"}", null);
            return;
        }
        if (user) {
            respond(exchange, 200, "{\"login\":\"" + ownerLogin + "\",\"type\":\"User\"}", null);
            return;
        }
        List<Repo> listed;
        synchronized (this) {
            listed = repos.stream().filter(r -> ownRepos ? r.own() : !r.own() && r.org().equals(org)).toList();
        }
        if (!ownRepos && listed.isEmpty()) {
            respond(exchange, 404, "{\"message\":\"Not Found\"}", null);
            return;
        }
        int from = Math.min(listed.size(), (page - 1) * perPage);
        int to = Math.min(listed.size(), from + perPage);
        String ownerType = ownRepos ? "User" : "Organization";
        StringBuilder items = new StringBuilder();
        for (int i = from; i < to; i++) {
            Repo repo = listed.get(i);
            if (items.length() > 0) {
                items.append(',');
            }
            items.append("{\"id\":").append(i + 1).append(",\"name\":\"").append(repo.name())
                    .append("\",\"owner\":{\"login\":\"").append(org).append("\",\"type\":\"").append(ownerType)
                    .append("\"},\"clone_url\":\"").append(repo.cloneUrl()).append("\",\"archived\":")
                    .append(repo.archived()).append(",\"disabled\":").append(repo.disabled())
                    .append(",\"fork\":false,\"stargazers_count\":3}");
        }
        String link = null;
        if (to < listed.size()) {
            String pageUrl = ownRepos
                    ? baseUrl() + "/user/repos?affiliation=owner&visibility=all&per_page=" + perPage + "&page="
                    : baseUrl() + "/orgs/" + org + "/repos?per_page=" + perPage + "&page=";
            String next = page == 1 && nextLinkOverride != null ? nextLinkOverride : pageUrl + (page + 1);
            int lastPage = (listed.size() + perPage - 1) / perPage;
            link = "<" + next + ">; rel=\"next\", <" + pageUrl + lastPage + ">; rel=\"last\"";
        }
        respond(exchange, 200, "[" + items + "]", link);
    }
```

Update the class Javadoc to: `A minimal GitHub serving {@code GET /orgs/{org}/repos}, {@code GET /user/repos} and {@code GET /user} with Link-header paging, auth checks and injected failures.` An empty own list answers `[]` (GitHub does the same), never 404.

- [ ] **Step 2: Write the failing tests**

`GitHubClientTest`: add a connection helper with the flag, then the tests.

```java
    private ScmConnection own(List<String> orgs, List<String> exclude) {
        return new ScmConnection(1, "hub", ScmType.GITHUB, github.baseUrl(), null, "tok", orgs, exclude, List.of(),
                true);
    }

    @Test
    void listsEveryPageOfTheTokenOwnersRepositories() {
        github.ownerLogin("zeuseng25");
        for (String name : List.of("a", "b", "c")) {
            github.addOwnRepository(name, "https://github.test/zeuseng25/" + name + ".git");
        }
        github.addOwnArchived("old", "https://github.test/zeuseng25/old.git");

        List<RemoteRepository> repositories = client.listRepositories(own(List.of(), List.of()));

        assertThat(repositories).extracting(RemoteRepository::projectKey, RemoteRepository::slug)
                .containsExactly(tuple("zeuseng25", "a"), tuple("zeuseng25", "b"), tuple("zeuseng25", "c"));
        assertThat(github.requestedPaths()).containsOnly("/user/repos");
        assertThat(github.requestedQueries()).allMatch(q -> q.contains("affiliation=owner")
                && q.contains("visibility=all") && q.contains("per_page=2"));
        assertThat(github.authorizations()).containsOnly("Bearer tok");
    }

    @Test
    void listsOwnRepositoriesNextToTheOrganizations() {
        github.ownerLogin("zeuseng25").addOwnRepository("zeus-fw", "https://github.test/zeuseng25/zeus-fw.git")
                .addOwnRepository("backend-old", "https://github.test/zeuseng25/backend-old.git")
                .addRepository("shop", "api", "https://github.test/shop/api.git");

        List<RemoteRepository> repositories = client.listRepositories(
                own(List.of("shop"), List.of("zeuseng25/*-old")));

        assertThat(repositories).extracting(RemoteRepository::projectKey, RemoteRepository::slug)
                .containsExactly(tuple("shop", "api"), tuple("zeuseng25", "zeus-fw"));
    }

    @Test
    void aRepositoryListedTwiceIsKeptOnce() {
        // the fake answers the same owner/name (differing only in case) from both listings; the first one is kept
        github.ownerLogin("Shop").addOwnRepository("api", "https://github.test/shop/api.git")
                .addRepository("shop", "api", "https://github.test/shop/api.git");

        assertThat(client.listRepositories(own(List.of("shop"), List.of())))
                .extracting(RemoteRepository::projectKey, RemoteRepository::slug).containsExactly(tuple("shop", "api"));
    }

    @Test
    void refusesAnOwnRepositoryNextLinkOnAnotherHost() throws Exception {
        try (FakeGitHub other = new FakeGitHub().start()) {
            github.addOwnRepository("a", "https://github.test/o/a.git").addOwnRepository("b", "https://github.test/o/b.git")
                    .addOwnRepository("c", "https://github.test/o/c.git")
                    .nextLinkOverride(other.baseUrl() + "/user/repos?affiliation=owner&visibility=all&page=2");

            assertThatThrownBy(() -> client.listRepositories(own(List.of(), List.of())))
                    .isInstanceOf(ScmException.class).hasMessageContaining("outside the base URL");
            assertThat(other.requestedPages()).isEmpty();
            assertThat(other.authorizations()).isEmpty();
        }
    }

    @Test
    void testsAnOwnOnlyConnectionWithOneUserRequest() {
        client.test(own(List.of(), List.of()));

        assertThat(github.requestedPaths()).containsExactly("/user");
    }

    @Test
    void anOwnOnlyTestWithARejectedTokenIsAnAuthenticationFailure() {
        github.requireAuthorization("Bearer other");

        assertThatThrownBy(() -> client.test(new ScmConnection(1, "hub", ScmType.GITHUB, github.baseUrl(), null,
                "tok-secret", List.of(), List.of(), List.of(), true)))
                .isInstanceOf(ScmAuthenticationException.class).message().doesNotContain("tok-secret");
        assertThat(github.requestedPaths()).containsExactly("/user");
    }

    @Test
    void anOwnOnlyTestIsNotRetried() {
        overrides.set(SettingKeys.SCM_RETRY_COUNT, "3");
        github.failNext(502, 1);

        assertThatThrownBy(() -> client.test(own(List.of(), List.of()))).isInstanceOf(ScmException.class);
        assertThat(github.requestedPaths()).containsExactly("/user");
    }

    @Test
    void anOwnRepositoriesRateLimitIsNotAnAuthenticationFailure() {
        github.failNext(403, 1).failureHeader("X-RateLimit-Remaining", "0");

        assertThatThrownBy(() -> client.listRepositories(own(List.of(), List.of())))
                .isInstanceOf(ScmException.class).isNotInstanceOf(ScmAuthenticationException.class)
                .hasMessageContaining("rate limit");
    }

    @Test
    void neitherOrganizationsNorOwnRepositoriesFails() {
        assertThatThrownBy(() -> client.listRepositories(connection(null, "tok", List.of(), List.of())))
                .isInstanceOf(ScmException.class);
        assertThatThrownBy(() -> client.test(connection(null, "tok", List.of(), List.of())))
                .isInstanceOf(ScmException.class);
        assertThat(github.requestedPaths()).isEmpty();
    }
```

If a test named `anEmptyOrganizationListFails` already exists, keep it; `neitherOrganizationsNorOwnRepositoriesFails` adds the `test()` side.

`RepositoryFilterTest`:

```java
    @Test
    void excludedIgnoresTheIncludeList() {
        ScmConnection filtered = connection(List.of("shop"), List.of("zeuseng25/*-old"));

        assertThat(RepositoryFilter.excluded(filtered, repo("zeuseng25", "zeus-fw"))).isFalse();
        assertThat(RepositoryFilter.excluded(filtered, repo("zeuseng25", "backend-old"))).isTrue();
        assertThat(RepositoryFilter.accepts(filtered, repo("zeuseng25", "zeus-fw"))).isFalse();
    }
```

`repo(project, slug)` is the existing helper in that test. If it has a different name, use it.

`ScmConnectionAdminApiTest`, next to `testsAGitHubConnection`:

```java
    @Test
    void testsAGitHubConnectionThatListsOnlyItsOwnRepositories() throws Exception {
        try (FakeGitHub github = new FakeGitHub().start().requireAuthorization("Bearer " + TOKEN)) {
            String json = """
                    {"name":"own","type":"GITHUB","baseUrl":"%s","username":null,"secret":"%s",
                     "includeProjects":[],"excludeRepos":[],"repositoryUrls":[],"includeOwnRepositories":true,
                     "enabled":true}
                    """.formatted(github.baseUrl(), TOKEN);
            MvcTestResult created = mvc.post().uri("/api/v1/admin/scm-connections")
                    .contentType(MediaType.APPLICATION_JSON).content(json).exchange();
            assertThat(created).hasStatus(201);
            long id = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();

            assertThat(mvc.post().uri("/api/v1/admin/scm-connections/" + id + "/test")).hasStatusOk().bodyJson()
                    .extractingPath("$.ok").isEqualTo(true);
            assertThat(github.requestedPaths()).containsExactly("/user");
        }
    }
```

- [ ] **Step 3: Run them to verify they fail**

Run: `./mvnw -q test -Dfrontend.skip=true -Dtest='GitHubClientTest,RepositoryFilterTest,ScmConnectionAdminApiTest'`
Expected: the own-repository tests fail (`/user/repos` is never requested, the listing throws "names no organization"), and `RepositoryFilter.excluded` does not compile.

- [ ] **Step 4: RepositoryFilter**

```java
    static boolean accepts(ScmConnection connection, RemoteRepository repository) {
        if (!connection.includeProjects().isEmpty() && connection.includeProjects().stream()
                .noneMatch(project -> project.equalsIgnoreCase(repository.projectKey()))) {
            return false;
        }
        return !excluded(connection, repository);
    }

    /** Whether an exclude_repos glob matches {@code PROJECT/slug}; the include list is not consulted. */
    static boolean excluded(ScmConnection connection, RemoteRepository repository) {
        String fullName = repository.projectKey() + "/" + repository.slug();
        return connection.excludeRepos().stream().anyMatch(pattern -> glob(pattern).matcher(fullName).matches());
    }
```

Update the class Javadoc to: `Applies a connection's include_projects (project keys) and exclude_repos ({@code PROJECT/slug} globs); {@link #excluded} applies only the excludes.`

- [ ] **Step 5: GitHubClient**

Constants, next to `ORG_REPOS_PATH`:

```java
    /** GitHub REST resource listing the repositories the token's owner owns, private ones included. */
    private static final String OWN_REPOS_PATH = "/user/repos?affiliation=owner&visibility=all&per_page={perPage}&page=1";
    /** GitHub REST resource naming the token's owner: the own-only connection test. */
    private static final String USER_PATH = "/user";
    /** Subject of error messages about the own-repository listing. */
    private static final String OWN_SUBJECT = "user of this token";
```

Add a JSON record next to `Repo`:

```java
    @JsonIgnoreProperties(ignoreUnknown = true)
    record User(String login) {
    }
```

Update the class Javadoc to: `Lists the repositories of GitHub organizations ({@code GET /orgs/{org}/repos}) and, when asked, the token owner's own ({@code GET /user/repos}), paged by the Link header.`

Replace `listRepositories` and `test`:

```java
    @Override
    public List<RemoteRepository> listRepositories(ScmConnection connection) {
        if (connection.includeProjects().isEmpty() && !connection.includeOwnRepositories()) {
            throw new ScmException("GitHub connection '" + connection.name()
                    + "' names no organization and does not include its own repositories");
        }
        try (HttpClient http = httpClient()) {
            RestClient client = client(connection, http);
            int pageSize = settings.getInt(SettingKeys.SCM_PAGE_SIZE);
            int retries = settings.getInt(SettingKeys.SCM_RETRY_COUNT);
            URI base = baseUri(connection);
            Map<String, RemoteRepository> repositories = new LinkedHashMap<>();
            for (String org : connection.includeProjects()) {
                listPages(connection, client, base, orgPage(connection, org, pageSize), organization(org), retries,
                        remote -> RepositoryFilter.accepts(connection, remote), repositories);
            }
            if (connection.includeOwnRepositories()) {
                // the owner's login is not an organization in includeProjects: only the excludes apply
                listPages(connection, client, base, ownPage(connection, pageSize), OWN_SUBJECT, retries,
                        remote -> !RepositoryFilter.excluded(connection, remote), repositories);
            }
            return List.copyOf(repositories.values());
        }
    }

    @Override
    public void test(ScmConnection connection) {
        if (connection.includeProjects().isEmpty() && !connection.includeOwnRepositories()) {
            throw new ScmException("GitHub connection '" + connection.name()
                    + "' names no organization and does not include its own repositories");
        }
        try (HttpClient http = httpClient()) {
            RestClient client = client(connection, http);
            if (!connection.includeProjects().isEmpty()) {
                String org = connection.includeProjects().getFirst();
                URI first = orgPage(connection, org, TEST_PAGE_SIZE);
                withRetry(connection, organization(org), () -> fetch(client, first, connection), 0);
                return;
            }
            URI user = UriComponentsBuilder.fromUriString(connection.baseUrl() + USER_PATH).build().encode().toUri();
            withRetry(connection, OWN_SUBJECT, () -> fetchUser(client, user, connection), 0);
        }
    }

    private static String organization(String org) {
        return "organization '" + org + "'";
    }
```

Rename `listOrganization` to `listPages` with this signature and body. The loop is the existing one; only the subject, the filter and the de-duplicating sink are new.

```java
    private void listPages(ScmConnection connection, RestClient client, URI base, URI first, String subject,
            int retries, Predicate<RemoteRepository> filter, Map<String, RemoteRepository> out) {
        Set<URI> fetched = new HashSet<>();
        fetched.add(first);
        URI current = first;
        int pages = 0;
        while (true) {
            URI target = current;
            ResponseEntity<List<Repo>> response = withRetry(connection, subject,
                    () -> fetch(client, target, connection), retries);
            if (response.getBody() == null) {
                // a listing we cannot read must fail the sync: an empty result would deactivate every repository
                throw new ScmException("GitHub returned no repository page for " + subject + " of connection '"
                        + connection.name() + "' (page " + (pages + 1) + ")");
            }
            pages++;
            for (Repo repo : response.getBody()) {
                if (Boolean.TRUE.equals(repo.archived()) || Boolean.TRUE.equals(repo.disabled())
                        || repo.cloneUrl() == null || repo.owner() == null || repo.owner().login() == null
                        || repo.name() == null) {
                    continue;
                }
                if (!acceptableCloneUrl(base, repo.cloneUrl())) {
                    log.warn("Skipping GitHub repository {}/{} of connection '{}': unacceptable clone_url {}",
                            repo.owner().login(), repo.name(), connection.name(), UrlMasking.mask(repo.cloneUrl()));
                    continue;
                }
                RemoteRepository remote = new RemoteRepository(repo.owner().login(), repo.name(), repo.name(),
                        repo.cloneUrl());
                if (filter.test(remote)) {
                    out.putIfAbsent((remote.projectKey() + "/" + remote.slug()).toLowerCase(Locale.ROOT), remote);
                }
            }
            String link = response.getHeaders().getFirst("Link");
            Optional<String> nextRef = nextLink(link);
            if (nextRef.isEmpty()) {
                return;
            }
            URI uri = checkedNext(connection, base, nextRef.get(), fetched);
            fetched.add(uri);
            current = uri;
        }
    }
```

Rename `firstPage` to `orgPage` (same body) and add `ownPage`:

```java
    private static URI orgPage(ScmConnection connection, String org, int perPage) {
        return UriComponentsBuilder.fromUriString(connection.baseUrl() + ORG_REPOS_PATH).buildAndExpand(org, perPage)
                .encode().toUri();
    }

    private static URI ownPage(ScmConnection connection, int perPage) {
        return UriComponentsBuilder.fromUriString(connection.baseUrl() + OWN_REPOS_PATH).buildAndExpand(perPage)
                .encode().toUri();
    }
```

Add `fetchUser` next to `fetch`:

```java
    private static ResponseEntity<User> fetchUser(RestClient client, URI uri, ScmConnection connection) {
        ResponseEntity<User> response = client.get().uri(uri).retrieve().toEntity(User.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new ScmException("GitHub answered connection '" + connection.name() + "' with HTTP "
                    + response.getStatusCode().value() + " instead of the token's user");
        }
        return response;
    }
```

In `withRetry`, rename the parameter `org` to `subject` and change only the 404 message:

```java
                if (status.value() == 404) {
                    throw new ScmException("GitHub " + subject + " was not found or is not visible to connection '"
                            + connection.name() + "'");
                }
```

For organizations this gives the same text as before ("GitHub organization 'ghost' was not found or is not visible to connection 'hub'").

Imports to add: `java.util.LinkedHashMap`, `java.util.Locale`, `java.util.Map`, `java.util.function.Predicate`. `ArrayList` may become unused; remove it if so.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw -q test -Dfrontend.skip=true -Dtest='GitHubClientTest,RepositoryFilterTest,ScmConnectionAdminApiTest'`
Expected: PASS, including every existing GitHub test (organization listing, rate limits, redirects, link loops, clone URL hygiene).

- [ ] **Step 7: Full backend suite, then commit**

Run: `./mvnw -q test -Dfrontend.skip=true`. Expected: green.

```bash
git add src/main/java/com/graphify/scm/GitHubClient.java src/main/java/com/graphify/scm/RepositoryFilter.java \
  src/test/java/com/graphify/testsupport/FakeGitHub.java src/test/java/com/graphify/scm/GitHubClientTest.java \
  src/test/java/com/graphify/scm/RepositoryFilterTest.java src/test/java/com/graphify/scm/ScmConnectionAdminApiTest.java
git commit -m "feat(scm): list the GitHub token owner's own repositories" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01XpYgYKeBYFNw4tb6EwJ44V"
```

---

### Task 3: Frontend switch, request body, tests and README

**Files:**
- Modify: `frontend/src/i18n/tr.ts`, `frontend/src/features/admin/scm/ScmConnectionPage.tsx`, `frontend/src/features/admin/scm/connectionBody.test.ts`, `frontend/src/features/admin/scm/scmConnections.test.tsx`, `README.md`

**Interfaces:**
- Consumes: `ScmConnectionUpdate.includeOwnRepositories?: boolean` and `ScmConnectionView.includeOwnRepositories?: boolean` (Task 1 regenerated types).
- Produces: `FormValues.includeOwnRepositories: boolean`. `connectionBody` sends `includeOwnRepositories` (true only for GITHUB with the switch on).

- [ ] **Step 1: Texts**

In `tr.ts` under `admin.scm`:
- `fields`: add `includeOwnRepositories: 'Kendi repolarımı da tara',`.
- `hints`: replace `organizations` and add `includeOwnRepositories`:

```ts
        organizations: 'Organizasyon adları (Enter ile ekleyin). Kendi hesabınızın repoları için aşağıdaki seçeneği açın.',
        includeOwnRepositories: "Token sahibinin kendi repoları taranır, gizli olanlar dahil (token'ın repo okuma izni olmalı).",
```

- Add a new key next to `typeFixed`: `githubScope: 'En az bir organizasyon girin ya da kendi repolarınızı dahil edin.',`.

- [ ] **Step 2: Write the failing tests**

`connectionBody.test.ts`: add `includeOwnRepositories: false` to `base`, then add:

```ts
  it('sends the own-repositories switch only for GitHub', () => {
    expect(connectionBody({ ...base, type: 'GITHUB', includeOwnRepositories: true }, 'tok').includeOwnRepositories)
      .toBe(true);
    expect(connectionBody({ ...base, type: 'BITBUCKET_DC', includeOwnRepositories: true }, 'tok').includeOwnRepositories)
      .toBe(false);
    expect(connectionBody({ ...base, type: 'GIT', includeOwnRepositories: true, repositoryUrls: 'https://git.corp/a.git' },
      undefined).includeOwnRepositories).toBe(false);
  });
```

`scmConnections.test.tsx`: add the tests below, reusing the file's existing helpers. In "sends only the fields of the chosen type", the MSW POST handler pushes the JSON body into `posts`, and `pick(type)` and `renderApp` exist. Build the handler the same way that test does: copy its `posts` array and its POST handler; the created connection can be the same `created` object.

```tsx
  it('saves a GitHub connection that lists only the token owner\'s repositories', async () => {
    // same backend setup as "sends only the fields of the chosen type": posts collects POST bodies
    renderApp('/admin/scm-connections/new');
    await userEvent.type(await screen.findByLabelText(tr.admin.scm.fields.name), 'own');
    await userEvent.type(screen.getByLabelText(tr.admin.scm.fields.baseUrl), 'https://api.github.com');
    await userEvent.type(screen.getByLabelText(tr.admin.scm.fields.secret), 'tok');
    await pick(tr.enums.scmType.GITHUB);

    await userEvent.click(screen.getByRole('button', { name: tr.admin.common.save }));
    expect(await screen.findByText(tr.admin.scm.githubScope)).toBeInTheDocument();
    expect(posts).toHaveLength(0);

    await userEvent.click(screen.getByRole('switch', { name: tr.admin.scm.fields.includeOwnRepositories }));
    await userEvent.click(screen.getByRole('button', { name: tr.admin.common.save }));
    await waitFor(() => expect(posts).toHaveLength(1));
    expect(posts[0]).toMatchObject({ type: 'GITHUB', includeProjects: [], includeOwnRepositories: true });
  });

  it('shows the own-repositories switch only for GitHub and clears it on a type change', async () => {
    // same backend setup as above
    renderApp('/admin/scm-connections/new');
    await screen.findByLabelText(tr.admin.scm.fields.name);
    expect(screen.queryByRole('switch', { name: tr.admin.scm.fields.includeOwnRepositories })).not.toBeInTheDocument();

    await pick(tr.enums.scmType.GITHUB);
    await userEvent.click(screen.getByRole('switch', { name: tr.admin.scm.fields.includeOwnRepositories }));
    await pick(tr.enums.scmType.GIT);
    expect(screen.queryByRole('switch', { name: tr.admin.scm.fields.includeOwnRepositories })).not.toBeInTheDocument();
    await pick(tr.enums.scmType.GITHUB);
    expect(screen.getByRole('switch', { name: tr.admin.scm.fields.includeOwnRepositories })).not.toBeChecked();
  });
```

Mantine's `Switch` renders `role="switch"`. If the installed version renders a checkbox, query `getByRole('checkbox', …)` instead and note it in the report.

- [ ] **Step 3: Run them to verify they fail**

Run: `cd frontend && npx vitest run src/features/admin/scm`
Expected: FAIL (no `includeOwnRepositories` in `FormValues`, no switch).

- [ ] **Step 4: The form**

In `ScmConnectionPage.tsx`:

`FormValues` gains:

```ts
  /** GitHub only: also list the repositories the token's owner owns. */
  includeOwnRepositories: boolean;
```

In `connectionBody`, add the field to the returned object:

```ts
    includeOwnRepositories: values.type === 'GITHUB' && values.includeOwnRepositories,
```

In `valuesOf`, add:

```ts
    includeOwnRepositories: view?.includeOwnRepositories ?? false,
```

In `useForm` `validate`, replace the `includeProjects` rule:

```ts
      includeProjects: (value, all) => (all.type === 'GITHUB' && value.length === 0 && !all.includeOwnRepositories
        ? tr.admin.scm.githubScope : null),
```

In the type `Select`'s `onChange`, after `form.setFieldValue('repositoryUrls', '');`, add:

```ts
              form.setFieldValue('includeOwnRepositories', false);
```

Replace the GitHub organizations block with:

```tsx
        {values.type === 'GITHUB' && (
          <>
            <TagsInput label={fields.organizations} description={hints.organizations}
              {...form.getInputProps('includeProjects')} />
            <Switch label={fields.includeOwnRepositories} description={hints.includeOwnRepositories}
              {...form.getInputProps('includeOwnRepositories', { type: 'checkbox' })} />
          </>
        )}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd frontend && npx vitest run src/features/admin/scm`
Expected: PASS.

- [ ] **Step 6: README**

In the "Repo connection types" table in `README.md`:
- Change the `includeProjects` GITHUB cell to `organizations (at least 1 unless \`includeOwnRepositories\` is true)`.
- Add a row after `repositoryUrls`:

```markdown
| `includeOwnRepositories` | not used (must be false) | also list the repositories the token's owner owns, private ones included (`GET /user/repos?affiliation=owner&visibility=all`); the token needs read access to them | not used (must be false) |
```

Append these sentences to the **GitHub:** bullet:

```markdown
With `includeOwnRepositories` the token owner's own repositories (a personal account is not an organization, so `/orgs/{name}/repos` answers 404 for it) are listed as well; they are filtered by `excludeRepos` only, their project key is the owner's login, and a repository listed twice is kept once. A connection test without organizations calls `GET {baseUrl}/user` once.
```

Change the GitHub column of the `/admin/scm-connections` API row (README line ~169) from "organizations in `includeProjects` for GITHUB" to "organizations in `includeProjects` and/or `includeOwnRepositories` for GITHUB".

- [ ] **Step 7: Full suites, then commit**

Run: `cd frontend && npm test && npm run build` and `./mvnw -q test -Dfrontend.skip=true`. Expected: both green. Skip any WildFly or browser step.

```bash
git add frontend/src/i18n/tr.ts frontend/src/features/admin/scm/ScmConnectionPage.tsx \
  frontend/src/features/admin/scm/connectionBody.test.ts frontend/src/features/admin/scm/scmConnections.test.tsx README.md
git commit -m "feat(frontend): scan the GitHub token owner's own repositories" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01XpYgYKeBYFNw4tb6EwJ44V"
```
