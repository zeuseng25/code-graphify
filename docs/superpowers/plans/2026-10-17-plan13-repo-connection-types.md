# Plan 13: Repo Connection Types (Bitbucket, GitHub, Git) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let admins connect three kinds of repository source, and rename the screen from "SCM bağlantıları" to "Repo bağlantıları":
- **Bitbucket** (`BITBUCKET_DC`): the existing type, unchanged.
- **GitHub** (`GITHUB`): github.com and GitHub Enterprise. A connection lists the repositories of one or more organizations.
- **Git** (`GIT`): any Git server over HTTP(S). The admin types the clone URLs.

The form changes its fields per type. A saved connection keeps its type.

**Architecture:**
- **Schema:** V10 widens the `scm_connection.type` check and adds `repository_urls CLOB`, a newline-separated list.
- **Model:** `ScmType` gains `GITHUB` and `GIT`. `ScmConnection`, `ScmConnectionUpdate` and `ScmConnectionView` carry `repositoryUrls`. `ScmConnectionAdministration.validate` checks the fields per type (spec §2) and rejects a type change on update.
- **Listing:** one `ScmClient` per type, found by `type()` as today:
  - `GitHubClient` pages `GET {baseUrl}/orgs/{org}/repos` by the `Link: rel="next"` header.
  - `GitConnectionClient` turns the stored URLs into repositories without a network call. Its `test()` runs `ls-remote` on the first URL.
- **Clone credentials:** `ScmConnection.gitAuthorization()` builds the HTTP `Authorization` header for git per type. `GitWorkspace` takes that header instead of a username/secret pair.
- **UI:** the existing admin pages with per-type fields, labels and hints. The type is a `Select` on create and read-only text on edit.

**Tech Stack:**
- Backend: Spring Boot 4.1.1, `RestClient` on the JDK `HttpClient`, JGit, Flyway, JdbcTemplate, Jackson 3. Tests use JUnit 5 and Testcontainers Oracle, plus a fake GitHub on `com.sun.net.httpserver`.
- Frontend: React 19, Mantine 9 (`Select`, `TagsInput`, `Textarea`), TanStack Query 5, Vitest + MSW.
- No new dependency.

**Spec:**
- `docs/superpowers/specs/2026-10-07-repo-connection-types-design.md` (all sections).
- Background: `2026-10-05-impact-analyzer-design.md` §3.2 step 1, §6.2, §8; `2026-10-06-web-ui-design.md` §4.2 row 10, §4.3.

**Code facts the plan relies on (read from the code):**
- **Listing and sync:** `RepositorySync.sync` picks the client whose `type()` matches the connection, calls `listRepositories`, and upserts `scm_repository` rows by `(connection_id, project_key, slug)`.
  - `project_key` and `slug` are `VARCHAR2(200 BYTE)`. `clone_url` is 1000 bytes; a longer URL leaves its row unchanged.
  - Unlisted repositories are deactivated under `scm.max_deactivation_percent`.
- **Filtering:** `RepositoryFilter.accepts` matches `includeProjects` case-insensitively against `projectKey`, and the `excludeRepos` globs against `projectKey/slug`. GitHub reuses it unchanged, with `projectKey` = the organization.
- **Bitbucket client patterns (copy them for GitHub):**
  - A `JdkClientHttpRequestFactory` with `scm.connect_timeout` and `scm.read_timeout`.
  - `withRetry`: 401/403 throw `ScmAuthenticationException`; any other 4xx throws `ScmException`; 5xx and I/O errors retry `scm.retry_count` times with exponential `scm.retry_backoff`; unreadable bodies throw `ScmException`.
  - A missing page body throws; it never becomes an empty listing, which would deactivate everything.
  - `test()` sends one request with no retry.
- **Credentials:**
  - `AuthorizationHeader.of(username, secret)` gives `Bearer secret` when the username is blank and `Basic base64(user:secret)` otherwise. Bitbucket, Maven (`ArtifactRepositoryProbe`) and git (`GitWorkspace.configure`) use it.
  - `GitWorkspace` sets the header through `TransportHttp.setAdditionalHeaders`; it registers no `CredentialsProvider`.
  - `RepositoryIndexer` passes `connection.username()` and `connection.secret()` to `remoteHead` and `checkout`.
- **Secrets on update:** `SecretUpdate.resolve(submitted, stored, sameTarget, …)`: null keeps the stored secret only for the same `baseUrl` + `username`; `""` clears it. A `baseUrl` change takes the index lock and deactivates the connection's repositories (Plan 7).
- **Storage:** lists are stored comma-joined (`ScmConnections.join`/`split`), and items may not contain a comma.
- **Construction sites:** `ScmConnection` is built in `ScmConnections.map` and in three tests (`RepositoryFilterTest`, `RepositorySyncTest`, `BitbucketDataCenterClientTest`). Other tests insert `scm_connection` rows by SQL; those keep working because the new column is nullable.
- **API contract:** the OpenAPI snapshot is `frontend/openapi.json`. `OpenApiSnapshotTest` checks it; regenerate with `-Dopenapi.update=true`. `npm run check:api` regenerates `src/api/schema.ts` and fails on drift.

**Rulings taken while planning:**
- **GitHub git credentials.** GitHub's REST API takes `Authorization: Bearer <token>`. Git over HTTPS takes only Basic, where any non-empty username is accepted with a token. So for `GITHUB`:
  - **REST:** always `Bearer <secret>`.
  - **Git:** `Basic base64(<username or "x-access-token">:<secret>)`.

  `x-access-token` is the username GitHub documents for token authentication. It is a protocol fact like the `Bearer` and `Basic` keywords, not an operational setting, so it is a named constant. `BITBUCKET_DC` and `GIT` keep `AuthorizationHeader.of(username, secret)`.
- **GitHub REST headers.** `Accept: application/vnd.github+json` and `X-GitHub-Api-Version: 2022-11-28` are protocol constants.
- **Pagination stays on the API host.** A `Link: rel="next"` URL whose origin (scheme, host, port) differs from `baseUrl` throws `ScmException`, so the token is never sent to another host. The same applies to a next link that repeats a page already fetched.
- **Archived GitHub repositories are skipped**, as in Bitbucket. Forks are listed.
- **A missing organization answers 404.** The listing then fails with `ScmException` ("organization … not found or not visible"); it is not treated as an empty listing.
- **GIT URL rules** (spec §2), applied after stripping whitespace and trailing slashes:
  - **Same server:** the scheme and host match `baseUrl`, ignoring case; the port matches, with the default port applied.
  - **Same path prefix:** the path starts with `baseUrl`'s path followed by `/`, and at least one segment follows.
  - **Clean URL:** no userinfo, query or fragment.
  - **Names:** `slug` is the last segment minus a trailing `.git`, and must be non-empty. `projectKey` is the segments between the base path and the slug, joined by `/`, or the slug when none remain. Both must be at most 200 bytes.
  - **Width:** each URL is at most 1000 bytes (the `clone_url` width).
  - **No duplicates:** two URLs giving the same `projectKey/slug` answer 400.
- **Empty means empty.** For `GIT`, `includeProjects` and `excludeRepos` must be empty. For `BITBUCKET_DC` and `GITHUB`, `repositoryUrls` must be empty. A non-empty unused field answers 400. The UI sends `[]` for every field the type does not use.
- **GitHub needs a token.** On create and update, the resolved secret of a `GITHUB` connection must be non-null; clearing it answers 400. `includeProjects` (organizations) needs at least one item.
- **The type is fixed.** A PUT with a different `type` answers 400 "type cannot be changed; create a new connection". The UI shows the type read-only on edit.
- **No URL-count cap.** `repository_urls` is a CLOB, so the list has no byte cap beyond each URL's own width. A cap would be an operational value, and the settings table has none for it.
- **Repointing through URLs.** Changing only `repositoryUrls` does not deactivate anything up front, because every URL must stay under the same `baseUrl`, so credentials keep going to the same server. A removed URL is deactivated by the next sync's usual unlisted rule. A `baseUrl` change keeps the Plan 7 lock-and-deactivate path for all types.
- **List page.** The list has no project column today, so the spec §4 note ("adres sayısı yerine") needs no column change. The type column shows the new labels, and the existing repository count column covers Git.
- **`ScmConnection` keeps an 8-argument constructor** that passes `List.of()` for `repositoryUrls`, so the existing test call sites need no change.
- **The texts rename "SCM" to "repo"** in Turkish UI sentences. Type names stay English: "Bitbucket", "GitHub", "Git". "Organizasyonlar" is a Turkish word, not a software term. "Repo" is already used across the UI.

## Plan series

| Plan | Scope | Status |
|---|---|---|
| 1–8 | Backend | merged |
| 9–12 | Web foundation, user screens, repository graph screen, admin screens | merged |
| **13** | **Repo connection types** (this plan) | — |
| later | LLM purpose/misuse layer (separate spec) | — |

## Global Constraints

- **Environment:**
  - Backend: `./mvnw -q test` (or a `-Dtest=` subset while iterating). Oracle comes from Testcontainers.
  - Frontend: commands run in `frontend/` (Node 24, npm 11). `npm test` runs `check:api`, the typecheck, lint and Vitest; `npm run build` must pass.
  - The full `./mvnw -q verify` must pass at the end of each task that touches the backend.
  - No new dependency.
- **"Kodda sabit değer yok" (no hardcoded values):**
  - Page size, retries, backoff and timeouts come from `scm.*` settings. URLs come from the connection; no default GitHub URL appears in code or in the form's initial values.
  - Allowed: protocol facts (`Bearer`, `Basic`, `x-access-token`, GitHub media type and API version header, `Link` syntax, `.git`), column widths with their migration named in a comment, and API enum names.
  - Example URLs may appear only in hint texts in `tr.ts`.
- **Secrets:**
  - Never in logs, exception messages, responses, audit rows, the URL, browser storage or the query cache.
  - Exceptions mask URLs with `UrlMasking.mask`.
  - `ScmConnection.toString()` keeps omitting the secret; it must not print `repositoryUrls` userinfo either. Userinfo is rejected anyway, but mask the URLs if they are printed.
  - Tests assert that a failing call's message does not contain the token.
- **Text:**
  - Every user-visible text lives in `frontend/src/i18n/tr.ts`, in Turkish sentences.
  - Software terms stay English (repository, project, URL, base URL, token, clone…).
  - `frontend/src/i18n/terms.test.ts` must keep passing.
  - Backend messages are English, as today.
- **Patterns:**
  - **Frontend (Plans 9–12):** data-first rendering, `notifyError` on mutations, `gcTime: 0` plus `reset()` for the secret-carrying save, `SecretField`, `ConfirmButton`, assertions through `tr`.
  - **Backend (Plans 4–7):** `InvalidRequestException` → 400, `ConflictException` → 409, `ExternalSystemException` → 502, audit through `AuditLog.record` without secrets.
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

1. **The GitHub token never leaves the API host or the clone host GitHub named.** A `Link` next URL on another origin fails the listing, and the token is not sent there. Test: Task 2 `GitHubClientTest` "refuses a next link on another host".
2. **A Git URL outside `baseUrl` is rejected**, including tricks:
   - `https://git.corp.evil/…` against `https://git.corp`;
   - `https://git.corp/scmx/r.git` against `https://git.corp/scm`;
   - userinfo, query and fragment;
   - another port.

   Test: Task 1 `ScmConnectionValidationTest`.
3. **Changing the type is refused**, and nothing is stored. Test: Task 1 admin API test "the type of a saved connection is fixed".
4. **Clone credentials per type.** GitHub with a blank username sends `Basic x-access-token:<token>`; Bitbucket and Git behave as before. Test: Task 3 `ScmConnectionTest` and `GitWorkspaceTest`.
5. **The form sends only the fields the type uses.** Switching the type on a new connection does not leak a GitHub organization into a Git request, or Git URLs into a GitHub one. Test: Task 4 `scmConnections.test.tsx` "sends only the fields of the chosen type".

---

## File Structure

| File | Responsibility |
|---|---|
| `src/main/resources/db/migration/V10__repo_connection_types.sql` (new) | Type check, `repository_urls` |
| `src/main/java/com/graphify/scm/ScmType.java` (modify) | `GITHUB`, `GIT` |
| `scm/ScmConnection.java`, `ScmConnectionUpdate.java`, `ScmConnectionView.java` (modify) | `repositoryUrls`; `gitAuthorization()` (Task 3) |
| `scm/ScmConnections.java` (modify) | Read and write `repository_urls` |
| `scm/ScmConnectionAdministration.java` (modify) | Per-type validation, fixed type, GitHub token rule |
| `scm/GitRepositoryUrls.java` (new) | Parse and check Git clone URLs against `baseUrl`; derive `projectKey`/`slug` |
| `scm/GitHubClient.java` (new) | GitHub organization listing and test |
| `scm/GitConnectionClient.java` (new) | Git listing from URLs, `ls-remote` test |
| `workspace/GitWorkspace.java`, `workspace/GitAuthenticationException.java` (new), `indexing/RepositoryIndexer.java` (modify) | Git credentials as a header; auth failures recognisable |
| `src/test/java/com/graphify/testsupport/FakeGitHub.java` (new) | Fake GitHub organization listing |
| `src/test/java/com/graphify/scm/…Test.java` (new and modify) | Tests per task |
| `frontend/openapi.json`, `frontend/src/api/schema.ts` (regenerated) | API contract |
| `frontend/src/i18n/tr.ts`, `frontend/src/features/admin/scm/*`, `frontend/src/components/secret.ts` (modify) | Rename, per-type form |
| `README.md` (modify) | Connection types, rename |

---

### Task 1: Schema, types and per-type validation

**Files:**
- Create: `src/main/resources/db/migration/V10__repo_connection_types.sql`, `src/main/java/com/graphify/scm/GitRepositoryUrls.java`, `src/test/java/com/graphify/scm/GitRepositoryUrlsTest.java`, `src/test/java/com/graphify/scm/ScmConnectionValidationTest.java`
- Modify: `ScmType`, `ScmConnection`, `ScmConnectionUpdate`, `ScmConnectionView`, `ScmConnections`, `ScmConnectionAdministration`, `src/test/java/com/graphify/store/SchemaMigrationTest.java`, `src/test/java/com/graphify/scm/ScmConnectionAdminApiTest.java`, `src/test/java/com/graphify/scm/ScmConnectionsTest.java`

No client exists yet for `GITHUB`/`GIT`; `test()` and sync of such a connection still fail with "No SCM client", which Tasks 2–3 fix. Do not register placeholder clients.

- [ ] **Step 1: Migration**

```sql
-- V10: repository connection types beyond Bitbucket (spec 2026-10-07 §3)
ALTER TABLE scm_connection DROP CONSTRAINT ck_scm_connection_type;
ALTER TABLE scm_connection ADD CONSTRAINT ck_scm_connection_type CHECK (type IN ('BITBUCKET_DC', 'GITHUB', 'GIT'));
-- GIT connections: the clone URLs, one per line
ALTER TABLE scm_connection ADD (repository_urls CLOB);
```

`SchemaMigrationTest`: a new test inserts one row per type, including `repository_urls` for GIT; assert that the type `'GITLAB'` is rejected (`DataIntegrityViolationException`); clean up the rows.

- [ ] **Step 2: Model**

- `ScmType`: `BITBUCKET_DC, GITHUB, GIT`, each with a one-line Javadoc.
- `ScmConnection`: add `List<String> repositoryUrls` as the last component, null → `List.of()`, copied. Add the 8-argument convenience constructor that passes `List.of()`. `toString()` is unchanged; it still prints no secret and no URL list.
- `ScmConnectionUpdate`: add `List<String> repositoryUrls` before `enabled`. JSON is by name, so the order is free. `toString()` is unchanged.
- `ScmConnectionView`: add `List<String> repositoryUrls` after `excludeRepos`.

- [ ] **Step 3: Storage**

`ScmConnections`:
- `SELECT` and `VIEW` read `repository_urls`.
- `insert`/`update` take `List<String> repositoryUrls` and write `joinLines(urls)` (null when empty) with `setString`.
- `splitLines` strips each line and drops blanks.

`ScmConnectionsTest`: a round trip of a GIT connection with three URLs. Add a second round trip whose joined text is larger than 32 767 bytes (for example 400 URLs of about 90 characters), proving the CLOB binding is not capped. If `setString` fails there, bind with `new SqlParameterValue(Types.CLOB, text)` in `update` and `statement.setClob(i, new StringReader(text))` in `insert`, and say so in the report.

- [ ] **Step 4: `GitRepositoryUrls`**

```java
/** Checks GIT clone URLs against the connection's baseUrl and names them (spec 2026-10-07 §2). */
final class GitRepositoryUrls {

    /** A clone URL with the repository name it is stored under. */
    record Named(String url, String projectKey, String slug) {
    }

    /** @throws InvalidRequestException naming the offending URL (masked) and the rule it breaks */
    static List<Named> check(String baseUrl, List<String> urls) { … }

    /** Names one already-validated URL; used by GitConnectionClient. */
    static Named name(String baseUrl, String url) { … }
}
```

Rules (planning ruling "GIT URL rules"):
- Parse with `java.net.URI`.
- Compare `scheme` and `host` with `equalsIgnoreCase`. Compare ports after mapping `-1` to the scheme default: 80 for http, 443 for https.
- Path segments: `baseUrl`'s raw path minus trailing `/` is the prefix, and the URL's raw path must start with `prefix + "/"`.
- Widths: name 200 bytes (`scm_repository.project_key`/`slug`, V1), URL 1000 bytes (`clone_url`, V1). Name the constants with their source column in a comment, as `ScmConnectionAdministration` does.

`GitRepositoryUrlsTest` cases:
- **Names:**
  - `https://git.corp/scm` + `https://git.corp/scm/team/api.git` → `team`/`api`.
  - `…/scm/a/b/c.git` → `a/b`/`c`.
  - `…/scm/solo.git` → `solo`/`solo`.
  - No `.git` suffix → the slug is the last segment.
  - `HTTPS://GIT.CORP:443/scm/x/y` is accepted against `https://git.corp/scm`.
- **Rejected:**
  - `https://git.corp.evil/scm/x.git`;
  - `https://git.corp/scmx/x.git`;
  - `https://git.corp/scm` and `https://git.corp/scm/` (no segment after the base);
  - `https://u:p@git.corp/scm/x.git`;
  - `…?a=1` and `…#f`;
  - `https://git.corp:8443/scm/x.git`;
  - `http://` against an `https` base;
  - `https://git.corp/scm/.git` (empty slug);
  - two URLs that both name `team/api`.
- **No leak:** the rejection message for the userinfo case does not contain `p@`.

- [ ] **Step 5: Validation in `ScmConnectionAdministration`**

`Valid` gains `repositoryUrls`. After the common checks, `validate` branches on the type:

```java
switch (update.type()) {
    case BITBUCKET_DC -> requireEmpty("repositoryUrls", urls);
    case GITHUB -> {
        requireEmpty("repositoryUrls", urls);
        if (include.isEmpty()) {
            throw new InvalidRequestException("includeProjects must name at least one GitHub organization");
        }
    }
    case GIT -> {
        requireEmpty("includeProjects", include);
        requireEmpty("excludeRepos", exclude);
        if (urls.isEmpty()) {
            throw new InvalidRequestException("repositoryUrls must list at least one clone URL");
        }
        urls = GitRepositoryUrls.check(baseUrl, urls).stream().map(GitRepositoryUrls.Named::url).toList();
    }
}
```

- `repositoryUrls` items: non-blank, no line break, stripped, trailing `/` removed. A comma is allowed here, since this list is newline-stored.
- `create`: after `SecretUpdate.resolve`, a `GITHUB` connection with a null secret answers 400 "A GitHub connection needs a token".
- `update`:
  - Before taking any lock, `valid.type() != current.type()` answers 400 "type cannot be changed; create a new connection".
  - In `applyUpdate`, the same GitHub token rule applies to the resolved secret.
- `changed(...)` adds `repositoryUrls` when the list differs. Remove the now-impossible `type` entry.
- Update the class Javadoc: "Repo connections (Bitbucket, GitHub, Git) for admins".

- [ ] **Step 6: Tests**

`ScmConnectionValidationTest` is a plain unit test. Reach `validate` through the public `create` with mocked collaborators, or make `validate` package-private static; prefer package-private static with a comment.

Cases:
- GITHUB without an organization → 400.
- GITHUB with `repositoryUrls` → 400.
- GIT with `includeProjects` → 400.
- GIT without URLs → 400.
- GIT with a URL off the base → 400.
- BITBUCKET_DC with `repositoryUrls` → 400.
- A valid document of each type passes.

`ScmConnectionAdminApiTest` gains:
- `createsAGitConnectionWithItsUrls`: POST GIT with two URLs → 201. The view lists them, `secretSet` is false, and `includeProjects` is `[]`.
- `aGitHubConnectionNeedsATokenAndAnOrganization`: no secret → 400; no organization → 400; both present → 201. A PUT with `"secret":""` → 400.
- `theTypeOfASavedConnectionIsFixed`: create BITBUCKET_DC, PUT with `"type":"GIT"` and valid Git fields → 400. GET still shows BITBUCKET_DC. No `SCM_CONNECTION_UPDATED` audit row.
- `body(...)` keeps producing BITBUCKET_DC. Add a `"repositoryUrls":[]` key to it so the existing tests cover an explicit empty list.

- [ ] **Step 7: Run and commit**

```bash
./mvnw -q verify
git add src/main/resources/db/migration/V10__repo_connection_types.sql src/main/java/com/graphify/scm/ src/test/java/com/graphify/scm/ src/test/java/com/graphify/store/SchemaMigrationTest.java
git commit -m "feat(scm): GitHub and Git connection types with per-type validation"
```

The OpenAPI snapshot changes here because of the new fields. Regenerate it now (`./mvnw -q test -Dtest=OpenApiSnapshotTest -Dopenapi.update=true`) and stage `frontend/openapi.json`; otherwise `verify` fails. `frontend/src/api/schema.ts` is regenerated in Task 4.

---

### Task 2: GitHub client

**Files:**
- Create: `src/main/java/com/graphify/scm/GitHubClient.java`, `src/test/java/com/graphify/testsupport/FakeGitHub.java`, `src/test/java/com/graphify/scm/GitHubClientTest.java`
- Modify: `src/test/java/com/graphify/scm/ScmConnectionAdminApiTest.java` (one GitHub test-connection case)

- [ ] **Step 1: `FakeGitHub`**

Model it on `FakeBitbucket`: `HttpServer` on `127.0.0.1:0`, and one context `/orgs/` serving `GET /orgs/{org}/repos?per_page=N&page=P`.
- **Data:** `addRepository(org, name, cloneUrl)` and `addArchived(org, name, cloneUrl)`.
- **Paging:** the reply has `per_page` items. While more remain, it sends `Link: <{baseUrl}/orgs/{org}/repos?per_page=N&page=P+1>; rel="next", <…>; rel="last"`.
- **Auth:** with `requireAuthorization(header)` set, a request with a different header gets 401 `{"message":"Bad credentials"}`.
- **Failures:**
  - `failNext(status, times)`;
  - an unknown org gets 404 `{"message":"Not Found"}`;
  - `nextLinkOverride(String url)` makes the first page's next link point at `url`, for the off-host test.
- **Recording:** requested pages, `per_page` values, `Authorization`, `Accept` and `X-GitHub-Api-Version` headers.
- **Item JSON:** `{"name":…, "owner":{"login":org}, "clone_url":…, "archived":bool, "fork":false}`, plus an unknown field to prove `ignoreUnknown`.

- [ ] **Step 2: Failing tests (`GitHubClientTest extends OracleIntegrationTest`)**

Use `SettingsOverride`: `SCM_PAGE_SIZE=2`, `SCM_RETRY_BACKOFF=PT0.01S`. Cases:
1. `listsEveryPageOfEachOrganization`: 5 repos in `shop` and 1 in `pay`. Organizations `["shop","pay"]` → 6 repositories with projectKey = owner login, slug = name, cloneUrl = clone_url. Pages 1, 2, 3 requested for `shop`; `per_page=2` every time.
2. `sendsTheTokenAsBearerWithTheGitHubHeaders`: with username `"bob"` set, the REST header is still `Bearer tok`, and `Accept`/`X-GitHub-Api-Version` are present.
3. `skipsArchivedAndAppliesExcludes`: `excludeRepos ["shop/legacy-*"]` drops `shop/legacy-a`. An archived repo is absent.
4. `aRejectedTokenIsAnAuthenticationFailure`: 401 → `ScmAuthenticationException`, and the message does not contain the token.
5. `retriesServerErrors`: `failNext(502, 2)` with `SCM_RETRY_COUNT=2` → success. `failNext(502, 5)` → `ScmException`.
6. `anUnknownOrganizationFailsTheListing`: 404 → `ScmException` mentioning the organization, never an empty list.
7. `refusesANextLinkOnAnotherHost`: `nextLinkOverride("http://127.0.0.2:1/orgs/shop/repos?page=2")` → `ScmException`. The fake records exactly one request (it never sees page 2), and nothing is sent to the other host.
8. `testSendsOneRequestWithoutRetry`: `test()` with `failNext(502, 1)` → `ScmException`, after one request with `per_page=1` for the first organization.

- [ ] **Step 3: Implementation**

```java
/** Lists the repositories of GitHub organizations ({@code GET /orgs/{org}/repos}, paged by the Link header). */
@Component
public class GitHubClient implements ScmClient {

    /** GitHub REST resource listing an organization's repositories. */
    private static final String ORG_REPOS_PATH = "/orgs/{org}/repos?per_page={perPage}&page=1";
    /** Media type and API version GitHub documents for its REST API. */
    private static final String MEDIA_TYPE = "application/vnd.github+json";
    private static final String API_VERSION_HEADER = "X-GitHub-Api-Version";
    private static final String API_VERSION = "2022-11-28";
    /** Page size of the connection test: one repository is enough to prove the URL and token work. */
    private static final int TEST_PAGE_SIZE = 1;
    …
}
```

- **Records:** `Owner(String login)` and `Repo(String name, Owner owner, @JsonProperty("clone_url") String cloneUrl, Boolean archived)`, with `@JsonIgnoreProperties(ignoreUnknown = true)`, using the same Jackson annotations as `BitbucketDataCenterClient`.
- **Paging:**
  - Read `ResponseEntity<List<Repo>>` via `.retrieve().toEntity(new ParameterizedTypeReference<>() {})`.
  - A null body throws `ScmException`.
  - Parse `Link` for `rel="next"` with a small private parser: split on `,`, match `<url>` and `rel="next"`.
  - The next URI must have the same scheme, host and port as `baseUrl`, using the default-port mapping from Task 1 (reuse a package-private helper, for example `UrlOrigins.sameOrigin(a, b)`, extracted from `GitRepositoryUrls`). It must not be a URL already fetched (a `Set<URI>`). Either failure throws `ScmException`.
  - Follow it with `client.get().uri(URI)`. An absolute URI bypasses `baseUrl`; the origin check above is what keeps the token on the host.
- **Errors:** `withRetry` is copied from Bitbucket with the messages saying "GitHub". Extract a shared package-private `ScmRetry` helper only if the copy would exceed about 40 identical lines; otherwise duplicate it and note that in the report. A 404 maps to `ScmException("GitHub organization '" + org + "' was not found or is not visible to connection '" + name + "'")`.
- **Headers:** `Authorization: Bearer <secret>` (never Basic for REST), `Accept: MEDIA_TYPE` and `X-GitHub-Api-Version: API_VERSION` as default headers.
- **Results:** skip `archived` items and items with a null `cloneUrl` or `owner`. Build `RemoteRepository(owner.login, name, name, cloneUrl)` and filter it with `RepositoryFilter.accepts`.
- **`test()`:** one request for `includeProjects().getFirst()` with `per_page=1` and 0 retries.

- [ ] **Step 4: Admin API case**

In `ScmConnectionAdminApiTest`, `testsAGitHubConnection`: create GITHUB against `FakeGitHub` with token `TOKEN` and organization `shop` → `POST …/test` → 200, and `lastTestStatus` is `SUCCESS`. With a wrong token → 502 and `AUTH_FAILED`.

- [ ] **Step 5: Run and commit**

```bash
./mvnw -q verify
git add src/main/java/com/graphify/scm/ src/test/java/com/graphify/scm/ src/test/java/com/graphify/testsupport/FakeGitHub.java
git commit -m "feat(scm): list GitHub organization repositories"
```

---

### Task 3: Git client and clone credentials per type

**Files:**
- Create: `src/main/java/com/graphify/scm/GitConnectionClient.java`, `src/main/java/com/graphify/workspace/GitAuthenticationException.java`, `src/test/java/com/graphify/scm/GitConnectionClientTest.java`, `src/test/java/com/graphify/scm/ScmConnectionTest.java`
- Modify: `ScmConnection` (`gitAuthorization()`), `workspace/GitWorkspace.java`, `indexing/RepositoryIndexer.java`, `src/test/java/com/graphify/workspace/GitWorkspaceTest.java`

- [ ] **Step 1: `ScmConnection.gitAuthorization()`**

```java
/** Username GitHub documents for token authentication over git HTTPS (any non-empty name is accepted). */
static final String GITHUB_TOKEN_USER = "x-access-token";

/** The Authorization header git sends for this connection's clones; GitHub git takes Basic only. */
public Optional<String> gitAuthorization() {
    if (type == ScmType.GITHUB && secret != null) {
        return AuthorizationHeader.of(username == null || username.isBlank() ? GITHUB_TOKEN_USER : username, secret);
    }
    return AuthorizationHeader.of(username, secret);
}
```

`ScmConnectionTest` cases:
- GITHUB with a blank username → `Basic base64("x-access-token:tok")`.
- GITHUB with username `bob` → `Basic base64("bob:tok")`.
- GITHUB without a secret → empty.
- BITBUCKET_DC and GIT with a blank username → `Bearer tok`.
- With a username → Basic.

- [ ] **Step 2: `GitWorkspace` takes the header**

- Signatures become `remoteHead(String cloneUrl, Optional<String> authorization)` and `checkout(Path, String cloneUrl, String branch, Optional<String> authorization)`. `configure` and the private `cloneFresh`/`update` follow.
- Drop the `AuthorizationHeader` import from `workspace`.
- `RepositoryIndexer` passes `connection.gitAuthorization()`.
- `GitWorkspaceTest`: replace `null, null` with `Optional.empty()`, and `"bob", "tok-secret-1"` with `AuthorizationHeader.of("bob", "tok-secret-1")`.

- [ ] **Step 3: Recognise authentication failures**

- `GitAuthenticationException extends GitException`, with Javadoc "the remote rejected or demanded credentials".
- In `remoteHead`'s catch, walk the cause chain. When a `org.eclipse.jgit.errors.TransportException` message contains `JGitText.get().notAuthorized` or `JGitText.get().noCredentialsProvider`, throw `GitAuthenticationException` with the same masked message.
- Find the exact JGit texts by running the test below first; use the `JGitText` fields, never copied English strings.
- Add a `GitWorkspaceTest` case `aRemoteDemandingCredentialsIsAnAuthenticationFailure`: a `com.sun.net.httpserver.HttpServer` answers every request with `401` and `WWW-Authenticate: Basic realm="git"`. `remoteHead("http://127.0.0.1:{port}/scm/x.git", AuthorizationHeader.of("bob","tok-secret-1"))` throws `GitAuthenticationException`, and the message does not contain `tok-secret-1`.

- [ ] **Step 4: `GitConnectionClient`**

```java
/** GIT connections: the admin lists the clone URLs; the test reads the first one's HEAD (ls-remote). */
@Component
public class GitConnectionClient implements ScmClient {

    private final GitWorkspace workspace;
    …
    @Override
    public ScmType type() {
        return ScmType.GIT;
    }

    @Override
    public List<RemoteRepository> listRepositories(ScmConnection connection) {
        // names come from the URLs, already checked against baseUrl when saved
        return connection.repositoryUrls().stream().map(url -> GitRepositoryUrls.name(connection.baseUrl(), url))
                .map(named -> new RemoteRepository(named.projectKey(), named.slug(), named.slug(), named.url()))
                .toList();
    }

    @Override
    public void test(ScmConnection connection) {
        if (connection.repositoryUrls().isEmpty()) {
            throw new ScmException("Connection '" + connection.name() + "' lists no repository URL");
        }
        try {
            workspace.remoteHead(connection.repositoryUrls().getFirst(), connection.gitAuthorization());
        } catch (GitAuthenticationException e) {
            throw new ScmAuthenticationException("The Git server rejected the credentials of connection '"
                    + connection.name() + "'");
        } catch (GitException e) {
            throw new ScmException("Connection '" + connection.name() + "' could not read "
                    + UrlMasking.mask(connection.repositoryUrls().getFirst()) + ": " + e.getMessage());
        }
    }
}
```

An empty URL list in `listRepositories` cannot happen after validation. If it does (a row edited in the database), throw `ScmException` rather than return `[]`, so a sync never deactivates everything. Add that guard and its test.

`GitConnectionClientTest`:
- **Listing:** two `GitFixtures.bareRepository` repos under a temp dir. A `file:` URL cannot pass the http(s)-only `baseUrl` rule, so build the `ScmConnection` directly with `baseUrl` = the parent directory's `file:` URI and call `listRepositories`. If `GitRepositoryUrls.name` rejects `file:` because of the scheme check, keep the scheme check in `check` (admin input) only and let `name` work on any hierarchical URI; say so in its Javadoc.
- **Test, success:** `test()` on a `file:` bare repo succeeds.
- **Test, auth failure:** use the 401 `HttpServer` from Step 3 → `ScmAuthenticationException`.
- **Test, unreachable:** an unreachable `http://127.0.0.1:1/...` → `ScmException`.
- **Sync:** a `RepositorySync.sync` of a GIT connection inserts the two rows with the derived `project_key`/`slug`. Reuse the `RepositorySyncTest` setup style.

- [ ] **Step 5: Run and commit**

```bash
./mvnw -q verify
git add src/main/java/com/graphify/scm/ src/main/java/com/graphify/workspace/ src/main/java/com/graphify/indexing/RepositoryIndexer.java src/test/java/com/graphify/scm/ src/test/java/com/graphify/workspace/GitWorkspaceTest.java
git commit -m "feat(scm): Git connections from clone URLs and per-type clone credentials"
```

---

### Task 4: Frontend — rename and per-type form

**Files:**
- Modify: `frontend/src/api/schema.ts` (regenerated), `frontend/src/i18n/tr.ts`, `frontend/src/features/admin/scm/ScmConnectionPage.tsx`, `ScmConnectionsPage.tsx` (Javadoc only), `frontend/src/components/secret.ts` (comment only), `frontend/src/features/admin/scm/scmConnections.test.tsx`

- [ ] **Step 1: Contract**

In `frontend/`, run `npm run check:api`, or the project's generate script, to regenerate `src/api/schema.ts` from the Task 1 snapshot. `ScmType` becomes `'BITBUCKET_DC' | 'GITHUB' | 'GIT'`; `repositoryUrls` appears on the view and the update.

- [ ] **Step 2: Texts (`tr.ts`)**

- `admin.nav.scmConnections`: `'Repo bağlantıları'`.
- `admin.scm.title`: `'Repo bağlantıları'`; `newTitle`: `'Yeni repo bağlantısı'`.
- `admin.scm.fields`:
  - add `organizations: 'Organizasyonlar'`;
  - add `repositoryUrls: 'Repo adresleri'`;
  - `includeProjects` stays "Dahil edilen project'ler".
- `admin.scm.hints`:
  - `baseUrl` per type:
    ```ts
    baseUrl: {
      BITBUCKET_DC: 'Bitbucket Data Center adresi, örn. https://bitbucket.example.com',
      GITHUB: 'API URL: github.com için https://api.github.com, Enterprise için https://<host>/api/v3',
      GIT: 'Git sunucusunun adresi; repo adresleri bununla başlamalı, örn. https://git.example.com/scm',
    },
    ```
  - `organizations`: `'En az bir organizasyon (Enter ile ekleyin).'`
  - `excludeRepos` per type: Bitbucket `'Örnek: SHOP/legacy-* (Enter ile ekleyin)'`, GitHub `'Örnek: my-org/legacy-* (Enter ile ekleyin)'`.
  - `repositoryUrls`: `'Her satıra bir clone URL; hepsi base URL ile başlamalı.'`
  - `secret` per type: Bitbucket `'Token ya da şifre'`, GitHub `'Token (zorunlu)'`, Git `'Şifre ya da token (gerekmiyorsa boş bırakın)'`.
  - `username` for GitHub: `"Boş bırakılabilir; clone için token kullanılır."`
- `admin.scm.typeFixed`: `'Tür kayıttan sonra değiştirilemez.'`
- `enums.scmType`: `{ BITBUCKET_DC: 'Bitbucket', GITHUB: 'GitHub', GIT: 'Git' }`.
- Search `tr.ts` for any other "SCM" in Turkish text and replace it with "repo"; keep the identifier names (`scm`, `scmConnections`).
- `terms.test.ts` must still pass.

- [ ] **Step 3: Form (`ScmConnectionPage.tsx`)**

- **`FormValues`:** gains `repositoryUrls: string`, the textarea text, one URL per line. `valuesOf` joins `view.repositoryUrls` with `'\n'`.
- **Type field:**
  - New connection: the `Select` as today, with labels from `tr.enums.scmType`.
  - Saved connection: a read-only `TextInput` with the label and `description={tr.admin.scm.typeFixed}`, so the type cannot change.
- **Per type** (`values.type`):

  | | BITBUCKET_DC | GITHUB | GIT |
  |---|---|---|---|
  | base URL hint | `hints.baseUrl.BITBUCKET_DC` | `hints.baseUrl.GITHUB` | `hints.baseUrl.GIT` |
  | include field | `TagsInput` "Dahil edilen project'ler" + hint | `TagsInput` "Organizasyonlar" + hint | hidden |
  | exclude field | `TagsInput` + Bitbucket hint | `TagsInput` + GitHub hint | hidden |
  | repository URLs | hidden | hidden | `Textarea autosize` "Repo adresleri" + hint |
  | secret description | Bitbucket | GitHub | Git |

  Pass the secret hint as a `description` prop if `SecretField` accepts one. If it does not, add an optional `description?: string` prop to `SecretField`, passed to the input, and leave its other behaviour alone.
- **Client-side validation** (the backend stays the authority):
  - GitHub needs at least one organization;
  - Git needs at least one non-blank line;
  - both use `tr.errors.required`.
- **The request body** is built by a pure exported function, so the test can check it:

```ts
/** The request for the chosen type: fields the type does not use are sent empty (the backend rejects them). */
export function connectionBody(values: FormValues, secret: string | undefined): ScmConnectionUpdate {
  const urls = values.repositoryUrls.split('\n').map((line) => line.trim()).filter(Boolean);
  const git = values.type === 'GIT';
  return {
    name: values.name, type: values.type, baseUrl: values.baseUrl, username: values.username || undefined, secret,
    enabled: values.enabled,
    includeProjects: git ? [] : values.includeProjects,
    excludeRepos: git ? [] : values.excludeRepos,
    repositoryUrls: git ? urls : [],
  };
}
```

`submit` uses it. Everything else on the page (secret re-entry, test, scan, delete, `save.reset()`) is unchanged. `scmTargetChanged` still compares base URL + username for every type.

- [ ] **Step 4: Tests (`scmConnections.test.tsx`)**

Keep the existing tests. In the `stored` fixture, add `repositoryUrls: []` and keep `type: 'BITBUCKET_DC'`. Expectations that use the old title or "SCM" now read from `tr`. New tests:
1. **`'is called Repo bağlantıları in the menu and the title'`:** the nav link and the heading show `tr.admin.nav.scmConnections` / `tr.admin.scm.title`. The literal `'Repo bağlantıları'` appears once in the test, as a rename guard.
2. **`'sends only the fields of the chosen type'`:**
   - On `/admin/scm-connections/new`, fill the name, base URL and token, and choose GitHub. Add organization `shop`, then switch the type to Git and type two URLs. Save.
   - The POST body has `type: 'GIT'`, `includeProjects: []`, `excludeRepos: []` and the two URLs.
   - Then a second render chooses GitHub with organization `shop`. Its body has `repositoryUrls: []` and `includeProjects: ['shop']`.
   - Mock `POST /api/v1/admin/scm-connections` to return a GIT view with `id: 9`, plus `GET …/9`.
3. **`'shows the type read-only when editing'`:** on `/admin/scm-connections/4`, the type input has the value `tr.enums.scmType.BITBUCKET_DC` and is read-only. `tr.admin.scm.typeFixed` is shown. No `combobox` role exists for the type.
4. **`'labels the GitHub fields'`:** a stored GITHUB connection shows `tr.admin.scm.fields.organizations` and no `includeProjects` label. A stored GIT connection shows the URL textarea, filled with its URLs one per line, and neither tag field.

Then run in `frontend/`:

```bash
npm test && npm run build
```

- [ ] **Step 5: Commit**

```bash
git add frontend/openapi.json frontend/src/api/schema.ts frontend/src/i18n/tr.ts frontend/src/features/admin/scm/ frontend/src/components/
git commit -m "feat(frontend): repo connections with Bitbucket, GitHub and Git forms"
```

---

### Task 5: README and the final check

**Files:**
- Modify: `README.md`

- [ ] **Step 1: README**

- **Admin screens list:** "SCM connections" becomes "Repo connections (Bitbucket, GitHub, Git)".
- **New subsection "Repo connection types",** with the spec §2 field table in English, and:
  - the GitHub API URL forms;
  - the organization scope;
  - that Git URLs must start with the base URL;
  - how projectKey/slug are derived;
  - that the type is fixed after creation;
  - the clone credential rule (GitHub without a username uses `x-access-token`).
- **API table:** the `/admin/scm-connections` rows mention the `type`-specific fields and the 400 on a type change.
- **Secrets paragraph:** unchanged. The target is still base URL + username for all three types.

- [ ] **Step 2: Whole-suite check**

```bash
./mvnw -q verify
cd frontend && npm test && npm run build
```

Both must pass. Report the backend and frontend test counts; before this plan they were 462 and 145.

- [ ] **Step 3: Optional manual check (no deploy)**

Do not deploy to the user's test WildFly; the controller does that after the merge, with consent. If a local check is wanted, run the Vite dev server against an MSW or Testcontainers backend, or simply rely on the tests.

- [ ] **Step 4: Commit**

```bash
git add README.md
git commit -m "docs: document repo connection types"
```

---

## Self-review against the spec

| Spec section | Covered by |
|---|---|
| §1 rename, types, fixed type | Task 1 Step 5 (400 on type change), Task 4 Steps 2–3 |
| §2 field table and rules | Task 1 Steps 4–6, Task 4 Step 3 |
| §2 GIT URL prefix, userinfo/query/fragment | Task 1 Step 4 |
| §2 secret re-entry and repoint deactivation for all types | Unchanged code path; Task 1 keeps it type-agnostic |
| §2 naming (GitHub owner/name, GIT path) | Task 2 Step 3, Task 1 Step 4 |
| §3 V10 | Task 1 Step 1 |
| §3 `GitHubClient` (paging, Link, Bearer, 401/403, retries, timeouts, excludes, test) | Task 2 |
| §3 `GitConnectionClient` (no network listing, ls-remote test, auth vs other errors) | Task 3 Steps 3–4 |
| §3 JGit username for GitHub | Task 3 Step 1 (ruling "GitHub git credentials") |
| §4 UI per type, read-only type, list labels | Task 4 |
| §5 tests | Tasks 1–4 test steps |
