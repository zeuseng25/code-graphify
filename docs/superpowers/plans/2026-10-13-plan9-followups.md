# Plan 9 follow-ups: rulings and deferred findings

## Rulings made during execution

- **WildFly 41 gate (Task 1).**
  - The WAR deploys on WildFly 41.0.0.Final.
  - `jboss-deployment-structure.xml` excludes the `jaxrs`, `jpa`, `weld`, `jsf` and `logging` subsystems; `jsf` had to be added because Faces needs CDI.
  - The explicit module exclusions were dropped: they did not exist and only produced warnings.
  - **Smoke script safety:**
    - it refuses a port that is already listening, a running install, an invalid context, or an existing same-named deployment (unless `SMOKE_FORCE=1`);
    - it removes what it deployed;
    - it sends the password on stdin.
- **Authentication bypass through an encoded `/api` (Task 2).**
  - The UI permit (`GET`/`HEAD /**`) comes after every `/api` rule, and the forward filter decides on the decoded path.
  - Regression tests run with and without the filter.
- **OpenAPI snapshot (Task 3).**
  - `frontend/openapi.json` is committed.
  - `OpenApiSnapshotTest` keeps it equal to the live document, and the frontend's `check:api` keeps `schema.d.ts` equal to the snapshot.
  - `Me` and `UiConfig` mark their always-present fields as required.
- **Frontend toolchain (Tasks 4–5).**
  - **Pins:** TypeScript is pinned to `~5.9.3`, because 7.x conflicts with the peer ranges of `openapi-typescript` and `typescript-eslint`. Node v24.21.0 and npm 11.21.0 (the `next-11` tag) are pinned in `pom.xml`.
  - **Mirror properties:** the mirrors are Maven properties whose format is validated (the Node root ends with `/`; the registry does not). Credentials live in `~/.npmrc` or in `settings.xml` via `serverId`.
  - **Clean WAR:** the previous UI is pruned in `prepare-package`, so no stale assets reach the WAR.
- **Auth screens (Task 6, final review).**
  - **Must-change-password users:** `/ui-config` answers 403 for a user who must change the password, so the layout skips it until the change; tested.
  - **Errors in Turkish:** messages go through `errorMessage()`. Network failures and non-problem errors show Turkish text.
  - **`safeNext`:** rejects control characters and whitespace and requires the same origin.
  - **CSRF token:** the token load is shared while in flight, and its shape is validated.
  - **Sign-in hygiene:** signing in clears the previous user's cache, and password mutations use `gcTime: 0`.
  - **Assets:** hashed assets are served `immutable` for a year; `index.html` is `no-store`.
  - **Mobile:** the menu has a burger on narrow screens.

## Carry into later plans

- **Plans 10–11, dotted route segments:** a UI route whose last path segment contains `.` returns 404 on reload, because of the forward filter's file rule. Put FQNs and file names in the query string, or encode them.
- **Plan 10:** the backend's 400/404/409/502 problem notifications (spec §6.2).
- **Plan 12:** a `RequireRole` component for admin routes.
- **Auth edge cases:**
  - a mid-session `PASSWORD_CHANGE_REQUIRED` 403 shows "forbidden" until the next navigation's session check;
  - a failed logout POST silently signs the user back in;
  - a cached signed-out state plus a failed refetch shows the error view instead of the sign-in page.
- **Build hygiene:**
  - `check-api.mjs` exits without a message if `npx` cannot be spawned;
  - `@types/node` is ^26 while the runtime is Node 24;
  - no lint rule forbids JSX text literals (enforced in review).
- **Backend:**
  - the snapshot test's path depends on the working directory;
  - snapshot arrays compare in reflection order;
  - `UiConfigApiTest` asserts 4 of the 6 fields.
- **WildFly:**
  - the bean-validation subsystem overlaps the bundled Hibernate Validator; deploy works;
  - the CSP check in `wildfly-smoke.sh` was added after the last WildFly run.
