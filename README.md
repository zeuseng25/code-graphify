# Graphify

Spring Boot application.

> **Türkçe kurulum rehberi:** projeyi indirip WildFly'a deploy etmeyi ve kullanmaya başlamayı adım adım anlatan [KURULUM.md](KURULUM.md).

## Requirements

- JDK 25 (macOS/Homebrew: `export JAVA_HOME=/opt/homebrew/opt/openjdk@25`; the build fails fast on older JDKs)
- Maven (wrapper included: `./mvnw`)
- Oracle database character set must be AL32UTF8.

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

## Deploying to WildFly

`./mvnw package` produces `target/graphify-0.0.1-SNAPSHOT.war`, which also still runs with `java -jar`. On WildFly 41 the context path is the file name the WAR is deployed as (`graphify.war` is served under `/graphify`); nothing in the application assumes a path.

- WildFly needs JDK 25 as well.
- The variables from [Running](#running) are set as environment variables of the WildFly process (`bin/standalone.conf`) or as system properties in the `<system-properties>` element of `standalone.xml`.
- Deploy by copying the WAR into `standalone/deployments`, or with `jboss-cli.sh --connect --command="deploy target/graphify-0.0.1-SNAPSHOT.war --name=graphify.war"`.
- `WEB-INF/jboss-deployment-structure.xml` keeps WildFly's JAX-RS, JPA, CDI, Faces and logging stacks out of the deployment; Spring Boot brings its own.

`scripts/wildfly-smoke.sh WAR [CONTEXT]` deploys a WAR to a local WildFly, starts it, and checks that the application answers (CSRF endpoint, the UI when the WAR contains one, and a login when `SMOKE_USER` and `SMOKE_PASSWORD` are set). It needs `WILDFLY_HOME` and `DB_URL`, `DB_USER`, `DB_PASSWORD`, `APP_MASTER_KEY` (plus `APP_BOOTSTRAP_ADMIN_PASSWORD` for the login check). Optional: `WILDFLY_HTTP_PORT` (default 8080), `WILDFLY_DEPLOY_TIMEOUT` seconds (default 300). The logging subsystem is excluded, so the application logs through logback to the server's stdout (which `server.log` captures). The script refuses to touch a running server or an existing same-named deployment unless `SMOKE_FORCE=1`. To run beside another WildFly, pass `JAVA_OPTS=-Djboss.socket.binding.port-offset=1000` and set `WILDFLY_HTTP_PORT` to match.

## Web UI

`frontend/` is a Vite + React app. `./mvnw package` builds it into the WAR (`WEB-INF/classes/static`): it installs Node and npm under `target/node`, then runs `npm ci`, `npm test` and `npm run build` in the `prepare-package` phase. `./mvnw test` does not touch it.

The Node distribution mirror and the npm registry are per-machine settings, never committed. Set them in `~/.m2/settings.xml`:

```xml
<profile><id>graphify-mirrors</id><activation><activeByDefault>true</activeByDefault></activation>
  <properties><frontend.node.downloadRoot>…</frontend.node.downloadRoot>
  <frontend.npm.registry>…</frontend.npm.registry></properties></profile>
```

`frontend.node.downloadRoot` is a mirror of `https://nodejs.org/dist/` and must end with a slash; `frontend.npm.registry` is the npm registry URL, which must not end with a slash (the build appends `/npm/-/` to find the npm tarball). Both can also be passed with `-D`. Without them the build stops with a message naming the missing property. `-Dfrontend.skip=true` builds a WAR without the UI and needs neither; each build replaces the UI in `target`, so a skipped build never ships an older one.

An `activeByDefault` profile is switched off as soon as another profile is activated with `-P`; in that case add `-Pgraphify-mirrors` as well.

Credentials for an internal mirror or registry never go in this repository:

- npm registry: a user-level `~/.npmrc`, with the token in the environment, e.g. `//registry.example.com/repository/npm/:_authToken=${NPM_TOKEN}`.
- Node mirror: a `<server>` in `~/.m2/settings.xml` and `<frontend.node.serverId>` set to its id in the profile above (the plugin uses it for the Node and npm downloads only; `npm ci` reads `~/.npmrc`).

Screens:

- Search: find a class, method or field; every overload is its own row.
- Symbol detail: declarations, hierarchy, usage summary and the filtered list of usages.
- Impact analysis: entry points, warnings and graph for a change, with a shareable link and CSV export.
- Repositories: indexed repositories with their modules and index run history.
- Repository graph: module, package, class and method levels with drill-down by tapping a node, optional external nodes, a rollup notice for large graphs, a report (counts, critical classes, communities, package cycles, entry points) and GraphML/JSON export; links look like `/repositories/{id}/graph?level=&focus=&includeExternal=`.
- Runs: index runs and their per-repository results; a running run refreshes every `ui.poll_interval`.

Admin (role ADMIN):

- Runs: start a run for all repositories, one enabled repo connection or one repository, and cancel a running one. A repository page has a "Bu repoyu tara" button (forced re-scan) and an enabled repo connection page a "Bu bağlantıyı tara" button; both link to the running run when another run holds the lock.
- Repo connections (Bitbucket, GitHub, Git) and Maven repositories: create, edit, delete and test the saved configuration. See "Repo connection types" below.
- Users: roles and active state; users are added from the LDAP directory.
- LDAP: connection and attribute settings, with a connection test.
- Settings: runtime settings grouped by area. Saving checks what a setting does on the server:
  `index.workspace_dir` and `index.maven_local_repository` must be absolute paths to a directory the server can
  create and write (a probe file is written and deleted; symlinks are followed; the normalised path is stored), and `index.maven_executable` must
  answer `-v` within `index.maven_check_timeout`, run without a shell and with the indexer's reduced environment.
  A refused value is not saved and the reason appears under the field. Seeded defaults are not checked at startup.
- Entry-point annotations: which annotations mark an entry point; enable, edit, add and delete.
- Impact rules: per usage kind, whether the impact propagates and whether it shows at level 1.
- Audit log: administrative changes, filtered by user, action and time range.

Secrets (tokens, passwords) are never shown or pre-filled; leaving the field empty keeps the stored value, "Kayıtlı değeri sil" clears it, and a changed target requires entering the secret again (the target is base URL + username for repo connections, URL + username for Maven repositories, URL + bind DN for LDAP).

### Repo connection types

The admin screen is called "Repo bağlantıları"; its API path stays `/api/v1/admin/scm-connections`. A connection has a type: `BITBUCKET_DC` (shown as Bitbucket), `GITHUB` or `GIT`. The type is chosen when the connection is created and cannot be changed afterwards (a `PUT` with another type returns 400; create a new connection instead).

| Field | BITBUCKET_DC | GITHUB | GIT |
|---|---|---|---|
| `baseUrl` | Bitbucket address | API URL: `https://api.github.com` or `https://<host>/api/v3` (no default in code; the form only shows examples) | Git server address (scheme + host + optional path prefix) |
| `username` | optional | optional (clone user name; when blank the token is sent as `x-access-token`) | optional |
| `secret` | token | token (required) | password or token (optional); with a blank `username` the secret is sent as a Bearer token, a password needs a `username` |
| `includeProjects` | included projects | organizations (at least 1 unless `includeOwnRepositories` is true) | not used (must be empty) |
| `excludeRepos` | `PROJECT/repo` glob | `org/repo` glob | not used (must be empty) |
| `repositoryUrls` | not used | not used | clone URL list (at least 1): a JSON array in the API, one URL per line in the form; no limit on the number of URLs, each URL at most 1000 bytes |
| `includeOwnRepositories` | not used (must be false) | also list the repositories the token's owner owns, private ones included (`GET /user/repos?affiliation=owner&visibility=all`); the token needs read access to them (classic token: `repo` scope; fine-grained token: Contents read and Metadata read on the repositories) | not used (must be false) |

- **GitHub:** each organization is listed with `GET {baseUrl}/orgs/{org}/repos`, following the `Link: rel="next"` header. The project key is the owner login and the slug the repository name. Archived and disabled repositories are skipped. A `clone_url` must be https (http only when the base URL is http) and carry no userinfo, otherwise the repository is skipped with a warning. An organization that returns 404 fails the listing. A rate limit (429, or 403 with `X-RateLimit-Remaining: 0` or `Retry-After`) is reported as a rate limit, not as an authentication failure. The connection test lists one repository of the first organization. With `includeOwnRepositories` the token owner's own repositories (a personal account is not an organization, so `/orgs/{name}/repos` answers 404 for it) are listed as well; they are filtered by `excludeRepos` (as `owner/name`) only, `includeProjects` is not applied to them, their project key is the owner's login, and a repository listed twice is kept once. A connection test without organizations calls `GET {baseUrl}/user` once. To read private repositories the token needs a classic token with the `repo` scope, or a fine-grained token with Contents (read) and Metadata (read) on the repositories.
- **Git:** there is no listing call; each URL in `repositoryUrls` is one repository. Every URL must start with the base URL (origin and path prefix are compared together, so the stored credentials only go to that server) and must not contain userinfo, query, fragment, encoded dot segments or encoded slashes. The slug is the last path segment without `.git`; the project key is the remaining path segments after the base URL path, joined with `/`, or the slug itself when none remain. Removing a URL deactivates that repository (and deletes its index) at the next sync; removing more than `scm.max_deactivation_percent` of the active repositories at once gives `DEACTIVATION_SKIPPED` and changes nothing. The connection test runs `ls-remote` on the first URL.
- **All types:** git clone, fetch and ls-remote drop the credentials when redirected to another origin, and API paging never leaves the base origin. Cloning uses the connection's `username` and secret; for GitHub without a username the user name `x-access-token` is used.

### How a scan resolves Maven projects

A scan runs in three phases:

1. **Prepare:** every repository in the run is checked out and its Maven projects are read, in parallel (`index.parallelism`).
2. **Install providers:** provider relationships are per project root, so a repository can install one of its own roots for another of its roots. A project root is a *provider* when another scanned root uses one of its modules as its parent, an imported BOM or a dependency (exact `groupId:artifactId:version` match).
   - Providers are installed into the local Maven repository (`index.maven_local_repository`) layer by layer, in dependency order. Cycles are found as strongly connected components and each one is installed as its own layer; a cycle member whose install fails gets `CYCLE_FAILED` ("Döngüsel dependency"); the other members are `INSTALLED` or `UP_TO_DATE` with a note about the cycle.
   - A repository is installed with `mvn -B -q -fae -Dmaven.test.skip=true install` per project root, roots in dependency order, stopping at the first failing root. The run uses `index.maven_timeout`, the same reduced environment as classpath resolution, and the output tail (`index.maven_output_tail_lines`) is masked before it is stored.
   - A provider whose commit equals `last_installed_commit` and whose built artifacts (every module of the installed roots) are all present (presence is checked by packaging) is `UP_TO_DATE` ("Güncel"). `last_installed_commit` is advanced only after the index phase, when every in-run consumer finished non-FAILED and the run was not cancelled.
   - Providers outside the run come from the active repositories of enabled connections. They are checked out at the default-branch head, installed, and get a run row (`SKIPPED_UNCHANGED` plus the install result, or "Checkout failed: ..." when the checkout fails).
   - An in-run provider that fails to install is still indexed, and its consumers may stay partial. A provider outside the run only gets a run row.
3. **Index:** as before. An unchanged consumer is indexed again when any repository in its transitive provider closure was `INSTALLED` in the run.

Only repositories that provide something to another scanned repository are built; their build plugins run (tests are skipped).

A repository without a root `pom.xml` is searched for independent Maven projects up to `index.pom_search_depth` folders deep. `.git`, `target`, `node_modules`, `build` and hidden folders are skipped and symlinks are not followed. Each project found gets its own classpath, and a module of a found project is not a project of its own. A warning counts the `.java` files outside the discovered projects.

Known limitations:

- A consumer outside the run of a `REPOSITORY`-scope scan is not re-indexed when that run installs a new provider; scan it (or run a full scan) afterwards.
- A scanned consumer whose preparation fails does not hold back its provider's `last_installed_commit`.

Development:

- `cd frontend && npm ci && npm run dev` serves the UI with the API proxied to the backend at `GRAPHIFY_API` (default `http://localhost:8080`). The proxy needs the backend at the root context (`./mvnw spring-boot:test-run`, or `java -jar` on `GRAPHIFY_API`), because the session cookie's path follows the context path; a WAR under `/graphify` will not keep the session behind the proxy.
- `npm test` runs the type check, lint and unit tests.
- After an API change, refresh the OpenAPI snapshot (`frontend/openapi.json`) and run `npm run generate:api`.

## Authentication

Everything outside `/api/` is the web UI (`index.html`, its assets and its routes) and carries no data; it is served without sign-in.

Every `/api/v1` endpoint except `GET /auth/csrf`, `POST /auth/login` and `GET /openapi.json` needs a signed-in session.

1. `GET /api/v1/auth/csrf` → `{headerName: "X-XSRF-TOKEN", token}` (starts a session).
2. `POST /api/v1/auth/login` with `{username, password}` and the `X-XSRF-TOKEN` header → the session cookie now
   carries the user; every later `POST`/`PUT`/`DELETE` sends the `X-XSRF-TOKEN` header with the current token (it is
   renewed at sign-in and at password change).
3. `POST /api/v1/auth/logout`; `GET /api/v1/auth/me`; `POST /api/v1/auth/change-password`.

- The CSRF token is renewed at sign-in and at password change: call `GET /api/v1/auth/csrf` again after logging in and
  after changing the password. The token is accepted only in the `X-XSRF-TOKEN` header, not as a form or body parameter.
- A local account is checked first; otherwise the user signs in through LDAP (configured at `/api/v1/admin/ldap`) and
  is created with role `USER` on first login. Roles live in the application, never in directory groups. A directory
  outage during login answers 502 "The directory is not available".
- First start: the local `admin` is created with `APP_BOOTSTRAP_ADMIN_PASSWORD`, or a generated password printed once
  in the log; it must be changed at first login (until then only `/auth/me`, `/auth/change-password` and `/auth/logout`
  answer; other calls get 403 with `code: PASSWORD_CHANGE_REQUIRED`).
- `ADMIN`: `/api/v1/admin/**`, starting and cancelling index runs; `USER`: everything else. At least one active admin
  always remains; the local admin can be deactivated only while an LDAP admin is active.
- LDAP settings: changing the LDAP url or bind DN while a bind password is stored requires entering the bind password
  again. LDAP can be disabled only while an active local admin exists; while it is disabled, open directory sessions
  end and only local admins count toward the last active admin.
- Local accounts lock after `auth.max_failed_attempts` failures for `auth.lock_duration`; sessions last
  `auth.session_timeout`. Every login failure answers the same 401.

## API

All endpoints are under `/api/v1`. The OpenAPI document is served at `/api/v1/openapi.json`. Errors are RFC 7807
problem details: 400 for invalid input, 404 for an unknown id.

| Method | Path | Purpose |
|---|---|---|
| GET | `/symbols/search?q=RestTemplate.exchange&kind=&repo={repositoryId}` | Find classes/methods/fields (case-insensitive); overloads are separate hits |
| GET | `/symbols/{id}` | Declarations, members, overrides, super/subtypes |
| GET | `/symbols/{id}/usages?confidence=&kind=&repo={repositoryId}` | Every usage site, paged |
| GET | `/symbols/{id}/usages/summary` | Usages grouped by repository → module → class |
| POST | `/impact` | Impact analysis (`{"symbolIds":[..],"changeType":"BEHAVIOR","depth":3}`): nodes with confidence, edges, entry points, staleness |
| POST | `/impact/export?format=csv` | The same analysis as CSV, one row per edge (formula-like cells prefixed with `'`) |
| GET | `/repositories?q=&status=`, `/repositories/{id}` | Indexed repositories (`status` filters by last index status, case-insensitive), modules and freshness |
| POST | `/index/runs` | Start a run (body `{scope: ALL\|CONNECTION\|REPOSITORY, id, force}`); 202 + `Location`; 409 with `runId` while a run or the cleanup holds the lock |
| GET | `/index/runs` | Runs, newest first |
| GET | `/index/runs/{runId}` | One run (`repositoriesByStatus`, `repositories[]`, `inProgress[]`); each repository carries `artifactInstall` and `artifactInstallError` (null when it provided nothing) |
| POST | `/index/runs/{runId}/cancel` | Cancel (repositories already being indexed finish; no new one starts) |
| GET | `/repositories/{id}/runs` | A repository's run history |
| GET | `/repositories/{id}/graph?level=&focus=&includeExternal=` | MODULE / PACKAGE (default) / CLASS / METHOD graph; `focus` is a package prefix, or at METHOD level (required there) the class's binary name as shown in the CLASS view (e.g. `a.b.Outer$Inner`), whose METHOD edges carry every usage kind with per-kind counts in `kinds`; `includeExternal` groups other repositories and libraries; over `graph.max_nodes` the graph is shown one level up with `truncated` and a `suggestion` (USER) |
| GET | `/repositories/{id}/graph/report` | Counts, the most depended-on classes, communities, package cycles and entry-point classes (lists capped by `graph.report_top_n`, except cycles); `stale` when the last analysis is not for the indexed commit (USER) |
| GET | `/repositories/{id}/graph/export?format=graphml\|json&level=&focus=&includeExternal=` | The same graph as an attachment, capped by `graph.export_max_nodes` (USER) |
| GET | `/ui-config` | Page sizes, graph node limit, impact depths and the UI poll interval, all from settings (USER) |
| GET | `/auth/csrf` | CSRF token and header name; starts a session (no sign-in needed) |
| POST | `/auth/login` | Sign in (`{username, password}`, `X-XSRF-TOKEN` header); sets the session cookie |
| POST | `/auth/logout` | End the session |
| GET | `/auth/me` | The signed-in user, role and whether a password change is required |
| POST | `/auth/change-password` | Change the signed-in user's password |
| GET, POST | `/admin/users` | List users / register a directory user from `{username, role}` (admin) |
| PUT | `/admin/users/{id}/role` | Set a user's role (admin) |
| PUT | `/admin/users/{id}/active` | Activate or deactivate a user (admin) |
| GET | `/admin/ldap/users?q=` | Search the directory for users to add (admin) |
| GET, PUT | `/admin/ldap` | Read / update the LDAP connection; the bind password is never returned (admin) |
| POST | `/admin/ldap/test` | Test an LDAP connection (admin) |
| GET, POST | `/admin/scm-connections` | List / create repo connections (`type` `BITBUCKET_DC`, `GITHUB` or `GIT`; `repositoryUrls` for GIT, organizations in `includeProjects` and/or `includeOwnRepositories` for GITHUB); responses carry `secretSet`, never the token (admin) |
| GET, PUT, DELETE | `/admin/scm-connections/{id}` | Read / replace / delete one; 400 when `type` differs from the stored type; changing `baseUrl` deactivates the connection's repositories until the next sync, which reactivates the repositories the new host lists and deletes the index of the others (same `scm.max_deactivation_percent` guard: when too many of the repointed connection's repositories are unlisted their index is kept and the run and connection show `DEACTIVATION_SKIPPED`; raising `scm.max_deactivation_percent` releases the purge on the next sync); 409 while the connection has repositories — disable it instead (admin) |
| POST | `/admin/scm-connections/{id}/test` | Test the connection with one repository request, no retries; 502 with the masked reason on failure (admin) |
| GET, POST | `/admin/artifact-repositories` | List / create Maven repositories (ordered by sort order); responses carry `secretSet`, never the password (admin) |
| GET, PUT, DELETE | `/admin/artifact-repositories/{id}` | Read / replace / delete one (admin) |
| POST | `/admin/artifact-repositories/{id}/test` | Test with one request (`http(s)`) or a directory check (`file:`), records `SUCCESS`/`AUTH_FAILED`/`FAILED` (HTTP 401/403 is `AUTH_FAILED`; 404 and 5xx are `FAILED`); 502 with the masked reason on failure (admin) |
| GET, POST | `/admin/entry-point-annotations` | List / create entry-point annotations (FQN, label, enabled); 409 for a duplicate FQN; changes apply to the next analysis (admin) |
| PUT, DELETE | `/admin/entry-point-annotations/{id}` | Replace / delete one (admin) |
| GET | `/admin/impact-rules` | List the impact relation rules by usage kind (admin) |
| PUT | `/admin/impact-rules/{kind}` | Set `{propagates, shownAtLevel1}` for a kind; 400 for an unknown kind; changes apply to the next analysis (admin) |
| GET | `/admin/settings` | List all settings with type, bounds and last editor, ordered by key (admin) |
| PUT | `/admin/settings/{key}` | Set a setting from `{value}`; 400 with the reason (type, bounds, or the server-side check for the directory and Maven settings), 404 for an unknown key; changes apply without a restart (admin) |
| GET | `/admin/audit?actor=&action=&from=&to=` | Audit log, newest first, paged; `from` inclusive, `to` exclusive, ISO-8601 instants (admin) |

API contract: `frontend/openapi.json` is a snapshot of the OpenAPI document (without `servers`) that the frontend's
types are generated from. `OpenApiSnapshotTest` fails when it differs from the live document; refresh it with
`./mvnw test -Dtest=OpenApiSnapshotTest -Dopenapi.update=true`, then `npm run generate:api` in `frontend/`.

Repository graph analyses are recomputed after every index, and communities are deterministic for `graph.community_seed`.

`file:`, `http:` and `https:` repository URLs are accepted (no credentials in the URL; use `username` and `secret`). A `mirrorOf` value turns the repository into a mirror of the named repositories in the generated `settings.xml`.

Secrets (repo connection token, Maven repository password, LDAP bind password) are never returned, only `secretSet`. For repo connections (`baseUrl` + `username`), artifact repositories (`url` + `username`) and LDAP (`url` + `bindDn`) a null secret keeps the stored one only while that target is unchanged; `""` clears it.

Index runs: one run or orphan cleanup runs at a time. The nightly run follows `index.cron` and the cleanup follows
`cleanup.orphan_symbols_cron`; both are rescheduled when the setting changes. Every index run also deletes the orphan symbols it leaves behind before it ends, so the weekly cleanup only catches what a failed run left. `scm.max_deactivation_percent` keeps a
shrunken SCM listing from deactivating repositories in bulk. `app.scheduling.enabled=false` turns scheduling off. Run
one application instance per schema.

Impact results (`POST /impact`):

- `nodes[]`: `role` is `SEED` (what you changed), `AFFECTED`, `DISPATCH` (an overridden method whose callers were followed) or `TWIN` (the name-only key `Class#name/argCount` standing for calls the indexer could not bind); `confidence` is the weakest edge on the path that reached the node; `nameOnly` marks twins.
- `edges[]`: every `fromSymbolId`/`toSymbolId` is a node; `repository` is `{id, projectKey, slug}`.
- `entryPoints[]`: `http` is true for request mappings; `httpPath` is null when the path is not a plain literal (never guessed).
- `summary`: counts cover `AFFECTED` nodes; `usagesByConfidence` and `nodesByConfidence` always list all three confidences; `usagesByLevel` replaces the spec's `levels[]`.
- `staleness[]` lists each affected module's last indexed commit.
- `versionWarnings[]`: affected modules whose `MODULE_DEPENDENCY` names the changed artifact at a different version than the indexed one.
- An impact analysis reads one consistent snapshot even while an index run commits.
- A lone search word (`charge`) matches type names and member names; with `kind=` it matches either within that kind.
- Field reads/writes and type references are shown at level 1 but do not propagate (configurable in `impact_relation_rule`).

CSV export: one row per edge; `repository` is `projectKey/slug`; cells starting with `= + - @` are prefixed with `'`.

Search matching is case-insensitive, qualified names included. A lone word (`q=PriceFormatter`) matches a type by its
simple name or any member by its name; with `kind=` it also matches members of a type with that simple name, so
`q=PriceFormatter&kind=CONSTRUCTOR` lists that class's constructors.

`repo=` is a repository id (from `/repositories`): a slug is unique only within its Bitbucket project. Every
response names a repository as `{"id", "projectKey", "slug"}`, ordered by project key, slug, then id.

Paging uses `?page=0&size=N`. The defaults and limits come from the `api.page_*` settings; impact depth and result
limits come from the `impact.*` settings.

## Tests

```bash
./mvnw test
```

Integration tests start an Oracle Free container through Testcontainers, so Docker must be running.

## Project structure

Code is organized by feature: each feature lives in its own package
(controller, service, repository, entity, `dto/` together).

```
com.graphify
├── GraphifyApplication.java
├── config/              # Spring configuration
├── common/
│   ├── exception/       # shared exceptions, global error handling
│   └── util/            # shared helpers
└── <feature>/           # e.g. graph/
    ├── GraphController.java
    ├── GraphService.java
    ├── GraphRepository.java
    ├── Graph.java
    └── dto/
```
