# Plan 9: Web UI Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the application as one WAR that runs on WildFly 41.0.0.Final and serves a React single-page app from the same origin. Concretely:
- the context path is taken from the request (never hardcoded);
- a safe SPA fallback and a CSP;
- a USER-visible UI settings endpoint;
- an OpenAPI-generated client;
- sign-in, sign-out, password change, and the main layout with a role-aware menu.

**Architecture:**
- **Packaging.** The backend becomes a WAR: Tomcat is `provided`, a `SpringBootServletInitializer` is added, and `jboss-deployment-structure.xml` keeps WildFly's JAX-RS/JPA/CDI/logging/Jackson out of the deployment.
- **SPA serving (`com.graphify.web` package):**
  - A controller serves `index.html` with `<base href>` rewritten to the request's context path.
  - A request filter forwards non-API, extension-less GETs to it.
  - `SecurityConfiguration` opens those GETs and adds a CSP.
- **Frontend (`frontend/`).** A Vite + React + TypeScript + Mantine project. It talks to `/api/v1` only, through an `openapi-fetch` client generated from a committed OpenAPI snapshot. A backend test keeps that snapshot in sync.
- **Build.** Maven builds the frontend in `prepare-package` with `frontend-maven-plugin`, so `./mvnw test` stays backend-only. Node and npm come from mirrors given as Maven properties.

**Tech Stack:** Java 25, Spring Boot 4.1.1 (WAR), Spring Security 7, WildFly 41.0.0.Final, Node 24 / npm 11, React (current stable), TypeScript, Vite, Mantine, React Router, TanStack Query, openapi-typescript + openapi-fetch, Vitest + Testing Library + MSW, ESLint.

**Spec:** `docs/superpowers/specs/2026-10-06-web-ui-design.md` (§2, §3, §4 items 1–2 and the layout, §6, §7 rows 1–2 and 8, §8, §9 Plan 9). The backend spec `docs/superpowers/specs/2026-10-05-impact-analyzer-design.md` §7 and §10.2 applies too.

**Rulings taken while planning:**
- **`ui.poll_interval` has no min/max.** It is a DURATION, and DURATION settings are validated as positive ISO-8601 durations; `min_value`/`max_value` apply to INT only. Spec §3.6's "min/max ile" is met by that validation.
- **The OpenAPI snapshot `frontend/openapi.json` is committed.**
  - `OpenApiSnapshotTest` fails when the live document differs. Run it with `-Dopenapi.update=true` to rewrite the snapshot, then run `npm run generate:api`.
  - The `servers` entry is removed before comparing and writing (it is host-specific).
  - This replaces spec §3.2's "generated from the running backend" with a reproducible equivalent.
- **The Plan 9 menu has only "Ana sayfa".** Later plans add their screens. The home page is a short welcome until Plan 10 replaces it with search.
- **The Vite dev server proxies `/api` to `GRAPHIFY_API`.** The default is `http://localhost:8080`. This is a developer-tool default and is never part of the built application.

## Plan series

| Plan | Scope | Status |
|---|---|---|
| 1–8 | Backend: indexer, persistence, search/impact, acquisition, orchestration, authentication, admin APIs, repo graph | merged |
| **9** | **Web foundation: WAR on WildFly 41, SPA serving, ui-config, frontend scaffold, auth screens, layout** (this plan) | — |
| 10 | User screens: search, symbol detail, impact, repositories, runs | next |
| 11 | Repo graph screen | — |
| 12 | Admin screens | — |

## Global Constraints

- **Toolchain.**
  - JDK 25: every Maven command runs with `export JAVA_HOME=/opt/homebrew/opt/openjdk@25`; use `./mvnw`.
  - Docker must be running.
  - Node 24 and npm 11 are installed locally (`/opt/homebrew/bin/node` v24.5.0, npm 11.6.0); frontend commands run inside `frontend/`.
- **"Kodda sabit değer yok" (no hardcoded values).**
  - No operational value lives in code: no context path, URL, page size, limit or poll interval.
  - The UI reads its limits from `GET /api/v1/ui-config`.
  - Build-time mirror addresses are Maven properties, never committed values.
  - Protocol, format and security facts are named constants with a comment: CSP directives, header names, the `<base>` marker.
- **UI language.**
  - All user-visible text is in `frontend/src/i18n/tr.ts`, in Turkish. Components contain no literal UI text.
  - Backend problem `detail` messages are shown as they come.
- **Secrets.** Passwords are never put in localStorage, sessionStorage, the URL, logs or error reports.
- **Same origin.**
  - API calls use `credentials: 'same-origin'`.
  - Unsafe methods send the CSRF token from `GET /api/v1/auth/csrf` in the header the server names.
  - There is no CORS.
- **Backend tests.**
  - Oracle-backed tests extend `OracleIntegrationTest` (`mvc`, `as(...)`, `anonymous()`).
  - **Never use spring-security-test's `csrf()`.**
  - Migrations V1–V8 are never edited; new schema or seeds go in `V9__web_ui.sql`.
- **The user's uncommitted `.gitignore` change (`graphify-8/`) must never be committed.**
  - Frontend ignores go in `frontend/.gitignore`.
  - Stage files explicitly; never use `git add -A` or `git add .` at the root.
- **Commits** end with:
  ```
  Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01XpYgYKeBYFNw4tb6EwJ44V
  ```

## Review Focus

1. **The WAR on WildFly 41 under a non-root context path:**
   - `/{ctx}/` serves the UI with `<base href="/{ctx}/">`;
   - assets load;
   - `/{ctx}/api/v1/auth/csrf` answers.

   Tests: Task 1 and Task 5 run `scripts/wildfly-smoke.sh`; Task 2 has `SpaServingTest.writesTheContextPathIntoTheBaseHref`.
2. **Open redirect.** A `next=//evil.example` (or `/\evil`) after sign-in lands on the home page, never off-site. Test: Task 6 `auth.test.tsx` "ignores an off-site next".
3. **A refreshed deep link** (`/repositories/12/graph`) still loads the app. An unknown `/api/...` path is still a 404 problem, not the SPA. Test: Task 2 `SpaServingTest`.
4. **A stale CSRF token after sign-in or password change.** The next unsafe request still succeeds: the client refetches the token once on a `CSRF` 403. Test: Task 6 `client.test.ts`.
5. **A session that expires while the app is open.** The next API 401 returns the user to sign-in, and they come back to where they were. Test: Task 6 `auth.test.tsx` "a later 401 returns to sign-in".

---

## File Structure

| File | Responsibility |
|---|---|
| `pom.xml` (modify) | WAR packaging, provided Tomcat, frontend build in `prepare-package` |
| `src/main/java/com/graphify/ServletInitializer.java` | WAR bootstrap for WildFly |
| `src/main/webapp/WEB-INF/jboss-deployment-structure.xml` | Keep WildFly subsystems/modules out |
| `scripts/wildfly-smoke.sh` | Deploy a WAR to a local WildFly and check it answers |
| `src/main/java/com/graphify/web/SpaController.java`, `SpaForwardFilter.java`, `WebConfiguration.java` | `index.html` with `<base href>`, deep-link forwarding |
| `src/main/java/com/graphify/auth/SecurityConfiguration.java` (modify) | Open SPA GETs, CSP |
| `src/main/java/com/graphify/web/UiConfig.java`, `UiConfigController.java`; `SettingKeys.java` (modify); `db/migration/V9__web_ui.sql` | `GET /api/v1/ui-config`, `ui.poll_interval` |
| `src/test/resources/static/index.html` | Test stand-in for the built UI |
| `src/test/java/com/graphify/web/OpenApiSnapshotTest.java`, `frontend/openapi.json` | API contract snapshot |
| `frontend/**` | Vite project: config, API client, auth, layout, pages, tests |
| `README.md` (modify) | Build properties, frontend development, WildFly deployment guide |

---

### Task 1: WAR packaging and the WildFly 41 gate

**Files:**
- Modify: `pom.xml`, `README.md`
- Create: `src/main/java/com/graphify/ServletInitializer.java`, `src/main/webapp/WEB-INF/jboss-deployment-structure.xml`, `scripts/wildfly-smoke.sh`

**Interfaces:**
- **Consumes:**
  - `GraphifyApplication`, which excludes `UserDetailsServiceAutoConfiguration`;
  - the environment variables `DB_URL`, `DB_USER`, `DB_PASSWORD`, `APP_MASTER_KEY`, `APP_BOOTSTRAP_ADMIN_PASSWORD` and `SPRING_PROFILES_ACTIVE`, read by `application.yml`.
- **Produces:**
  - `./mvnw package` produces `target/graphify-0.0.1-SNAPSHOT.war`, which deploys on WildFly 41.0.0.Final under the context taken from the deployed file name, and still runs with `java -jar`.
  - `scripts/wildfly-smoke.sh WAR [CONTEXT]`, which later tasks re-run.
- **Gate.** This task decides whether WildFly 41 is usable. If the WAR cannot be deployed and answer after reasonable configuration, stop and report **BLOCKED** with the server log excerpt. The controller asks the user.

- [ ] **Step 1: Switch to WAR packaging**

In `pom.xml`:
- add `<packaging>war</packaging>` after `<version>`;
- add, next to the other starters:

```xml
		<!-- WildFly provides the servlet container; the embedded Tomcat stays only for java -jar -->
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-tomcat</artifactId>
			<scope>provided</scope>
		</dependency>
```

If `spring-boot-starter-webmvc` pulls Tomcat in transitively at compile scope, the explicit `provided` declaration above wins for the WAR's `WEB-INF/lib`. Verify in Step 4 that no `tomcat-embed-*` jar is in `WEB-INF/lib` (they belong in `WEB-INF/lib-provided`).

`src/main/java/com/graphify/ServletInitializer.java`:

```java
package com.graphify;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

/** Starts the application when the WAR is deployed to an external servlet container (WildFly). */
public class ServletInitializer extends SpringBootServletInitializer {

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder application) {
        return application.sources(GraphifyApplication.class);
    }
}
```

If the `SpringBootServletInitializer` package differs in Boot 4.1, use the class from Boot's web-server/servlet module. Find it with `jar tf` on the Boot jars in `~/.m2`.

- [ ] **Step 2: Keep WildFly's own stacks out of the deployment**

`src/main/webapp/WEB-INF/jboss-deployment-structure.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- Spring Boot brings its own web, JSON, logging and data stack; WildFly's must not be added to this deployment. -->
<jboss-deployment-structure xmlns="urn:jboss:deployment-structure:1.2">
    <deployment>
        <exclude-subsystems>
            <subsystem name="jaxrs"/>
            <subsystem name="jpa"/>
            <subsystem name="weld"/>
            <subsystem name="logging"/>
        </exclude-subsystems>
        <exclusions>
            <module name="org.slf4j"/>
            <module name="org.slf4j.impl"/>
            <module name="org.apache.logging.log4j.api"/>
            <module name="org.apache.commons.logging"/>
            <module name="com.fasterxml.jackson.core.jackson-core"/>
            <module name="com.fasterxml.jackson.core.jackson-databind"/>
            <module name="com.fasterxml.jackson.core.jackson-annotations"/>
        </exclusions>
    </deployment>
</jboss-deployment-structure>
```

This is a starting point. Step 5 proves it against a real WildFly 41. Add or remove entries only as the server log requires, and record each change and its reason in the report.

- [ ] **Step 3: Write the smoke script**

`scripts/wildfly-smoke.sh` (make it executable):

```bash
#!/usr/bin/env bash
# Deploys a WAR to a local WildFly, waits for the deployment and checks that the application answers
# (web UI spec §3.3, §8). Nothing here is application configuration: all values come from the caller.
#
# Usage: scripts/wildfly-smoke.sh path/to/app.war [context]
# Needs: WILDFLY_HOME, and the application's variables (DB_URL, DB_USER, DB_PASSWORD, APP_MASTER_KEY, ...).
# Optional: WILDFLY_HTTP_PORT (8080), WILDFLY_DEPLOY_TIMEOUT seconds (300), SMOKE_USER/SMOKE_PASSWORD to try a login.
set -euo pipefail

war="${1:?usage: wildfly-smoke.sh app.war [context]}"
context="${2:-graphify}"
: "${WILDFLY_HOME:?set WILDFLY_HOME to the WildFly installation}"
: "${DB_URL:?}" "${DB_USER:?}" "${DB_PASSWORD:?}" "${APP_MASTER_KEY:?}"
port="${WILDFLY_HTTP_PORT:-8080}"
timeout="${WILDFLY_DEPLOY_TIMEOUT:-300}"
deployments="$WILDFLY_HOME/standalone/deployments"
base="http://localhost:$port/$context"
log="$(mktemp -t wildfly-smoke.XXXXXX)"

rm -f "$deployments/$context.war"*
cp "$war" "$deployments/$context.war"
"$WILDFLY_HOME/bin/standalone.sh" >"$log" 2>&1 &
server=$!
trap 'kill "$server" 2>/dev/null || true; wait "$server" 2>/dev/null || true' EXIT

for _ in $(seq "$timeout"); do
    [[ -e "$deployments/$context.war.deployed" ]] && break
    if [[ -e "$deployments/$context.war.failed" ]]; then
        echo "Deployment failed; server log: $log" >&2
        grep -E "ERROR|WFLY" "$log" | tail -n 60 >&2
        exit 1
    fi
    sleep 1
done
[[ -e "$deployments/$context.war.deployed" ]] || { echo "Not deployed after ${timeout}s; log: $log" >&2; exit 1; }

curl -fsS "$base/api/v1/auth/csrf" | grep -q '"headerName"' || { echo "csrf endpoint did not answer" >&2; exit 1; }
echo "OK  $base/api/v1/auth/csrf"

if unzip -l "$war" | grep -q 'WEB-INF/classes/static/index.html'; then
    curl -fsS "$base/" | grep -q "<base href=\"/$context/\"" || { echo "index.html without the context base" >&2; exit 1; }
    asset="$(curl -fsS "$base/" | grep -oE 'assets/[^"]+\.js' | head -n 1)"
    curl -fsS -o /dev/null "$base/$asset" || { echo "asset $asset not served" >&2; exit 1; }
    curl -fsS "$base/repositories/1/graph" | grep -q "<base href=\"/$context/\"" \
        || { echo "deep link did not serve the app" >&2; exit 1; }
    echo "OK  $base/ (index, asset, deep link)"
fi

if [[ -n "${SMOKE_USER:-}" && -n "${SMOKE_PASSWORD:-}" ]]; then
    jar="$(mktemp -t wildfly-cookies.XXXXXX)"
    token="$(curl -fsS -c "$jar" -b "$jar" "$base/api/v1/auth/csrf" | sed -E 's/.*"token":"([^"]+)".*/\1/')"
    curl -fsS -c "$jar" -b "$jar" -H "X-XSRF-TOKEN: $token" -H 'Content-Type: application/json' \
        -d "{\"username\":\"$SMOKE_USER\",\"password\":\"$SMOKE_PASSWORD\"}" "$base/api/v1/auth/login" >/dev/null \
        || { echo "login failed" >&2; exit 1; }
    echo "OK  login as $SMOKE_USER"
fi
echo "WildFly smoke test passed; server log: $log"
```

- [ ] **Step 4: Build and inspect the WAR**

Run: `./mvnw -q package -DskipTests && unzip -l target/graphify-0.0.1-SNAPSHOT.war | grep -E 'jboss-deployment-structure|ServletInitializer|tomcat-embed' | head`

Expected:
- `WEB-INF/jboss-deployment-structure.xml` and `ServletInitializer.class` are present;
- any `tomcat-embed-*` jar is only under `WEB-INF/lib-provided/`.

Run: `./mvnw test`
Expected: all tests pass. The test context does not need the servlet container.

- [ ] **Step 5: Prove the gate on a real WildFly 41.0.0.Final**

Work outside the repository, in a scratch directory such as `/tmp/wildfly-gate`.

1. **Get WildFly.** Download `wildfly-41.0.0.Final.zip`; the release asset is published at `https://github.com/wildfly/wildfly/releases/download/41.0.0.Final/wildfly-41.0.0.Final.zip`. Unzip it. If this version cannot be downloaded, report BLOCKED with the exact error.
2. **Start an Oracle Free container:**

   ```bash
   docker run -d --name graphify-wildfly-db -p 1521:1521 -e ORACLE_PASSWORD=sys_pw \
     -e APP_USER=graphify -e APP_USER_PASSWORD=graphify_pw gvenzl/oracle-free:23-slim-faststart
   ```

   Wait for "DATABASE IS READY TO USE!" in `docker logs`.
3. **Run the smoke test against WildFly** with JDK 25:

   ```bash
   export JAVA_HOME=/opt/homebrew/opt/openjdk@25 WILDFLY_HOME=/tmp/wildfly-gate/wildfly-41.0.0.Final \
     DB_URL=jdbc:oracle:thin:@//localhost:1521/FREEPDB1 DB_USER=graphify DB_PASSWORD=graphify_pw \
     APP_MASTER_KEY="$(openssl rand -base64 32)" APP_BOOTSTRAP_ADMIN_PASSWORD='Smoke-Admin-1' \
     SMOKE_USER=admin SMOKE_PASSWORD='Smoke-Admin-1'
   scripts/wildfly-smoke.sh target/graphify-0.0.1-SNAPSHOT.war graphify
   ```

   Check the bootstrap admin's username in `BootstrapAdmin` and use it for `SMOKE_USER`.

   If the deploy fails, read the server log. Fix it only through `jboss-deployment-structure.xml`, the initializer, or packaging scope, and repeat. Never weaken application code to suit the server.
4. **Clean up:** `docker rm -f graphify-wildfly-db`. Keep the downloaded WildFly in `/tmp` for Tasks 5 and 6, and say where it is in the report.

Expected: `WildFly smoke test passed`, with the csrf and login checks OK. There is no UI check yet; the WAR has no `static/index.html`.

- [ ] **Step 6: Document the deployment**

Add a `## Deploying to WildFly` section to `README.md`. Cover:
- `./mvnw package` produces a WAR. The context path is the file name you deploy it as (`graphify.war` → `/graphify`); nothing in the app assumes a path.
- The variables from `## Running` are set as environment variables of the WildFly process (`bin/standalone.conf`) or as system properties in `standalone.xml` `<system-properties>`.
- JDK 25 is required for WildFly too.
- `scripts/wildfly-smoke.sh` and its variables.
- Copying the WAR into `standalone/deployments`, or using `jboss-cli.sh --command="deploy … --name=graphify.war"`.

- [ ] **Step 7: Commit**

```bash
git add pom.xml README.md src/main/java/com/graphify/ServletInitializer.java src/main/webapp scripts/wildfly-smoke.sh
git commit -m "build: package as a WAR for WildFly 41 with a deployment smoke test" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01XpYgYKeBYFNw4tb6EwJ44V"
```

---

### Task 2: Serve the SPA: base href, deep links, security and CSP

**Files:**
- Create: `src/main/java/com/graphify/web/SpaController.java`, `SpaForwardFilter.java`, `WebConfiguration.java`; `src/test/resources/static/index.html`
- Modify: `src/main/java/com/graphify/auth/SecurityConfiguration.java`, `README.md`
- Test: `src/test/java/com/graphify/web/SpaServingTest.java`

**Interfaces:**
- **Consumes:**
  - `SecurityConfiguration.apiSecurity`. Today `anyRequest().denyAll()`; `ProblemResponses` answers 401/403.
  - `spring.mvc.problemdetails.enabled: true`, so unknown paths are 404 problems.
- **Produces:**
  - `GET {ctx}/index.html` reads `classpath:static/index.html`. It replaces the first `<base href="…">` element with `<base href="{html-escaped contextPath}/">` and answers `text/html;charset=UTF-8` with `Cache-Control: no-store`.
    - When the file is absent, it answers 404 with detail "The web UI is not part of this build".
    - When the file has no `<base>` element, it is an `IllegalStateException` (a build defect).
  - `SpaForwardFilter`, registered for the REQUEST dispatcher, forwards a request to `/index.html` when all of these hold:
    - the method is GET;
    - the path inside the context is not `/api` and does not start with `/api/`;
    - the path is not `/index.html`;
    - the last path segment contains no `.`.

    The path `/` is forwarded.
  - `SecurityConfiguration`:
    - permits every GET whose path inside the context does not start with `/api/` (static files, `index.html`, SPA routes; no data);
    - keeps the API rules unchanged;
    - keeps `anyRequest().denyAll()` for everything else;
    - adds `Content-Security-Policy` (the value below) to every response.

- [ ] **Step 1: Write the failing test**

`src/test/resources/static/index.html`:

```html
<!doctype html>
<html lang="tr">
  <head>
    <meta charset="UTF-8" />
    <base href="./" />
    <title>Graphify test UI</title>
  </head>
  <body><div id="root">test-ui</div></body>
</html>
```

`src/test/java/com/graphify/web/SpaServingTest.java`:

```java
package com.graphify.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.auth.Role;
import org.junit.jupiter.api.Test;

class SpaServingTest extends OracleIntegrationTest {

    @Test
    void writesTheContextPathIntoTheBaseHref() {
        assertThat(anonymous().get().uri("/graphify/index.html").contextPath("/graphify")).hasStatusOk()
                .hasContentTypeCompatibleWith("text/html").bodyText()
                .contains("<base href=\"/graphify/\" />").contains("test-ui").doesNotContain("href=\"./\"");
        assertThat(anonymous().get().uri("/index.html")).hasStatusOk().bodyText().contains("<base href=\"/\" />");
        assertThat(anonymous().get().uri("/index.html")).headers().hasValue("Cache-Control", "no-store");
    }

    @Test
    void deepLinksAndTheRootForwardToTheApp() {
        assertThat(anonymous().get().uri("/repositories/12/graph")).hasForwardedUrl("/index.html");
        assertThat(anonymous().get().uri("/")).hasForwardedUrl("/index.html");
        assertThat(anonymous().get().uri("/graphify/admin/settings").contextPath("/graphify"))
                .hasForwardedUrl("/index.html");
    }

    @Test
    void apiPathsAndFilesAreNeverTheApp() {
        assertThat(as("viewer", Role.USER).get().uri("/api/v1/no-such-endpoint")).hasStatus(404)
                .hasContentTypeCompatibleWith("application/problem+json");
        assertThat(anonymous().get().uri("/api/v1/repositories")).hasStatus(401);
        assertThat(anonymous().get().uri("/assets/missing.js")).doesNotHaveForwardedUrl("/index.html").hasStatus(404);
        // a non-GET outside /api is never the app: the CSRF check (403 problem) or denyAll rejects it
        assertThat(anonymous().post().uri("/repositories/12")).hasStatus(403);
    }

    @Test
    void everyResponseCarriesTheContentSecurityPolicy() {
        assertThat(anonymous().get().uri("/index.html")).headers().containsKey("Content-Security-Policy");
        assertThat(anonymous().get().uri("/api/v1/auth/csrf")).headers().hasValue("Content-Security-Policy",
                WebConfiguration.CONTENT_SECURITY_POLICY);
    }
}
```

These use the AssertJ MockMvc API (`hasForwardedUrl`, `doesNotHaveForwardedUrl`, `headers()`). If a method is named differently in this Spring version, use the closest equivalent and keep the assertion's meaning. MockMvc does not execute forwards, so a forward is asserted with `hasForwardedUrl`.

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw test -Dtest=SpaServingTest`
Expected: failure. `index.html` is denied or 404, there are no forwards, and `WebConfiguration` is missing.

- [ ] **Step 3: Implement**

`src/main/java/com/graphify/web/WebConfiguration.java`:

```java
package com.graphify.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import jakarta.servlet.DispatcherType;
import java.util.EnumSet;

/** Serving the single-page web UI from the same origin as the API (web UI spec §3.4–3.5). */
@Configuration(proxyBeanMethods = false)
public class WebConfiguration {

    /**
     * Same-origin scripts only; Mantine injects style elements at runtime, hence 'unsafe-inline' for styles alone.
     * A security policy, not an operational setting.
     */
    public static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; "
            + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; "
            + "base-uri 'self'";

    @Bean
    FilterRegistrationBean<SpaForwardFilter> spaForwardFilter() {
        FilterRegistrationBean<SpaForwardFilter> registration = new FilterRegistrationBean<>(new SpaForwardFilter());
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST));
        return registration;
    }
}
```

If `FilterRegistrationBean` lives in another package in Boot 4.1, import it from there.

`src/main/java/com/graphify/web/SpaForwardFilter.java`:

```java
package com.graphify.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A reload or shared link to a UI route (e.g. /repositories/12/graph) must load the app: such GETs are forwarded to
 * index.html. API paths and file requests (a dot in the last segment) are never forwarded.
 */
public class SpaForwardFilter extends OncePerRequestFilter {

    /** The UI's entry document, served by {@link SpaController}. */
    static final String INDEX = "/index.html";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isUiRoute(request)) {
            request.getRequestDispatcher(INDEX).forward(request, response);
            return;
        }
        chain.doFilter(request, response);
    }

    static boolean isUiRoute(HttpServletRequest request) {
        if (!"GET".equals(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.isEmpty()) {
            path = "/";
        }
        if (path.equals("/api") || path.startsWith("/api/") || path.equals(INDEX)) {
            return false;
        }
        String last = path.substring(path.lastIndexOf('/') + 1);
        return !last.contains(".");
    }
}
```

`src/main/java/com/graphify/web/SpaController.java`:

```java
package com.graphify.web;

import com.graphify.common.exception.NotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/** Serves the UI's index.html with its base href set to this deployment's context path (web UI spec §3.4). */
@RestController
public class SpaController {

    /** Where the frontend build puts the entry document inside the WAR. */
    private static final String INDEX_RESOURCE = "static/index.html";

    /** The build writes a placeholder base element; the first one is replaced. */
    private static final Pattern BASE = Pattern.compile("<base href=\"[^\"]*\"\\s*/?>");

    private volatile String template;

    @GetMapping(value = SpaForwardFilter.INDEX, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> index(HttpServletRequest request) {
        String html = template();
        Matcher base = BASE.matcher(html);
        if (!base.find()) {
            throw new IllegalStateException(INDEX_RESOURCE + " has no <base href> element");
        }
        String href = HtmlUtils.htmlEscape(request.getContextPath() + "/");
        String body = html.substring(0, base.start()) + "<base href=\"" + href + "\" />" + html.substring(base.end());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8)).body(body);
    }

    private String template() {
        String loaded = template;
        if (loaded == null) {
            ClassPathResource resource = new ClassPathResource(INDEX_RESOURCE);
            if (!resource.exists()) {
                throw new NotFoundException("The web UI is not part of this build");
            }
            try (InputStream in = resource.getInputStream()) {
                loaded = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read " + INDEX_RESOURCE, e);
            }
            template = loaded;
        }
        return loaded;
    }
}
```

In `SecurityConfiguration.apiSecurity`:
- add `import org.springframework.security.web.util.matcher.RequestMatcher;` and `import com.graphify.web.WebConfiguration;`;
- define, before `http.csrf(...)`:

```java
        // the UI's files and routes: GETs outside /api carry no data, every API rule below still applies
        RequestMatcher uiGets = request -> "GET".equals(request.getMethod())
                && !request.getRequestURI().substring(request.getContextPath().length()).startsWith("/api/");
```

- insert `.requestMatchers(uiGets).permitAll()` right after the `DispatcherType.ERROR` line;
- add to the chain, before `.exceptionHandling(...)`:

```java
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp
                        .policyDirectives(WebConfiguration.CONTENT_SECURITY_POLICY)))
```

The forward to `/index.html` is a GET outside `/api/`, so it is permitted on the FORWARD dispatch as well.

In `README.md`'s Authentication section, add one line: everything outside `/api/` is the web UI (`index.html`, its assets and its routes) and carries no data; it is served without sign-in.

- [ ] **Step 4: Run the tests**

Run: `./mvnw test -Dtest='SpaServingTest,SecurityApiTest,AuthApiTest'`
If a test class name differs, run the security/auth API test classes that exist under `src/test/java/com/graphify/auth`.
Expected: all pass.

Run: `./mvnw test`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphify/web src/main/java/com/graphify/auth/SecurityConfiguration.java \
  src/test/resources/static/index.html src/test/java/com/graphify/web/SpaServingTest.java README.md
git commit -m "feat(web): serve the UI with the context path as base href, forward deep links, add a CSP" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01XpYgYKeBYFNw4tb6EwJ44V"
```

---

### Task 3: UI settings endpoint and the OpenAPI snapshot

**Files:**
- Create: `src/main/resources/db/migration/V9__web_ui.sql`, `src/main/java/com/graphify/web/UiConfig.java`, `UiConfigController.java`, `src/test/java/com/graphify/web/UiConfigApiTest.java`, `src/test/java/com/graphify/web/OpenApiSnapshotTest.java`, `frontend/openapi.json` (generated)
- Modify: `src/main/java/com/graphify/settings/SettingKeys.java`, `README.md`

**Interfaces:**
- **Consumes:**
  - `AppSettings.getInt` and `getDuration`;
  - the keys `API_PAGE_DEFAULT_SIZE`, `API_PAGE_MAX_SIZE`, `GRAPH_MAX_NODES`, `IMPACT_DEFAULT_DEPTH`, `IMPACT_MAX_DEPTH`;
  - the springdoc document at `GET /api/v1/openapi.json`, which is anonymous.
- **Produces:**
  - Setting `ui.poll_interval` (DURATION, `PT5S`) and `SettingKeys.UI_POLL_INTERVAL`.
  - `public record UiConfig(int pageDefaultSize, int pageMaxSize, int graphMaxNodes, int impactDefaultDepth, int impactMaxDepth, long pollIntervalMillis)`.
  - `GET /api/v1/ui-config`. USER-only through the existing `/api/**` rule; anonymous is 401.
  - `frontend/openapi.json`: the springdoc document without its `servers` entry, pretty-printed.
    - `OpenApiSnapshotTest` fails when it differs from the live document.
    - With `-Dopenapi.update=true` the test rewrites the file instead.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/graphify/web/UiConfigApiTest.java`:

```java
package com.graphify.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.auth.Role;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import com.graphify.testsupport.SettingsOverride;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class UiConfigApiTest extends OracleIntegrationTest {

    @Autowired
    AppSettings settings;

    @Test
    void givesSignedInUsersTheUiLimitsFromSettings() {
        SettingsOverride overrides = new SettingsOverride(settings);
        overrides.set(SettingKeys.UI_POLL_INTERVAL, "PT7S");
        try {
            assertThat(as("viewer", Role.USER).get().uri("/api/v1/ui-config")).hasStatusOk().bodyJson()
                    .satisfies(json -> {
                        assertThat(json).extractingPath("$.pollIntervalMillis").isEqualTo(7000);
                        assertThat(json).extractingPath("$.pageDefaultSize")
                                .isEqualTo(settings.getInt(SettingKeys.API_PAGE_DEFAULT_SIZE));
                        assertThat(json).extractingPath("$.graphMaxNodes")
                                .isEqualTo(settings.getInt(SettingKeys.GRAPH_MAX_NODES));
                        assertThat(json).extractingPath("$.impactMaxDepth")
                                .isEqualTo(settings.getInt(SettingKeys.IMPACT_MAX_DEPTH));
                    });
        } finally {
            overrides.restore();
        }
        assertThat(anonymous().get().uri("/api/v1/ui-config")).hasStatus(401);
        assertThat(jdbc.queryForObject("SELECT setting_value FROM app_setting WHERE setting_key = 'ui.poll_interval'",
                String.class)).isEqualTo("PT5S");
    }
}
```

`src/test/java/com/graphify/web/OpenApiSnapshotTest.java`:

```java
package com.graphify.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The frontend's API types are generated from frontend/openapi.json; this keeps that snapshot equal to the live
 * document. To refresh it: ./mvnw test -Dtest=OpenApiSnapshotTest -Dopenapi.update=true, then npm run generate:api.
 */
class OpenApiSnapshotTest extends OracleIntegrationTest {

    /** The snapshot, relative to the Maven project directory the tests run in. */
    private static final Path SNAPSHOT = Path.of("frontend", "openapi.json");

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void theCommittedSnapshotMatchesTheLiveDocument() throws Exception {
        String live = mvc.get().uri("/api/v1/openapi.json").exchange().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        ObjectNode document = (ObjectNode) json.readTree(live);
        document.remove("servers"); // host-specific
        if (Boolean.getBoolean("openapi.update")) {
            Files.createDirectories(SNAPSHOT.getParent());
            Files.writeString(SNAPSHOT, json.writerWithDefaultPrettyPrinter().writeValueAsString(document) + "\n");
        }
        assertThat(Files.exists(SNAPSHOT)).as("frontend/openapi.json exists").isTrue();
        JsonNode committed = json.readTree(Files.readString(SNAPSHOT));
        assertThat(committed).as("frontend/openapi.json is stale: run ./mvnw test -Dtest=OpenApiSnapshotTest "
                + "-Dopenapi.update=true, then npm run generate:api in frontend/").isEqualTo(document);
    }
}
```

Spring Boot 4 uses Jackson 3 (`tools.jackson.*`). Check with `ls ~/.m2/repository/tools/jackson/core`. If the API names differ (`writerWithDefaultPrettyPrinter`, `remove`), use the Jackson 3 equivalents with the same behaviour.

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -Dtest='UiConfigApiTest,OpenApiSnapshotTest'`
Expected: `UiConfigApiTest` fails (404 and the key is missing). `OpenApiSnapshotTest` fails because the snapshot does not exist.

- [ ] **Step 3: Implement**

`src/main/resources/db/migration/V9__web_ui.sql` (UTF-8):

```sql
-- Plan 9: how often the web UI refreshes a running index run (web UI spec §3.6).

INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('ui.poll_interval', 'PT5S', 'DURATION', 'Arayüzün çalışan taramayı yenileme aralığı', NULL, NULL);
```

Add to `SettingKeys.java`:

```java
    public static final String UI_POLL_INTERVAL = "ui.poll_interval";
```

`src/main/java/com/graphify/web/UiConfig.java`:

```java
package com.graphify.web;

/** The settings the web UI needs to behave, readable by every signed-in user (web UI spec §3.6). */
public record UiConfig(int pageDefaultSize, int pageMaxSize, int graphMaxNodes, int impactDefaultDepth,
        int impactMaxDepth, long pollIntervalMillis) {
}
```

`src/main/java/com/graphify/web/UiConfigController.java`:

```java
package com.graphify.web;

import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/v1/ui-config: no secret and no admin-only setting is ever added here. */
@RestController
public class UiConfigController {

    private final AppSettings settings;

    public UiConfigController(AppSettings settings) {
        this.settings = settings;
    }

    @GetMapping("/api/v1/ui-config")
    public UiConfig uiConfig() {
        return new UiConfig(settings.getInt(SettingKeys.API_PAGE_DEFAULT_SIZE),
                settings.getInt(SettingKeys.API_PAGE_MAX_SIZE), settings.getInt(SettingKeys.GRAPH_MAX_NODES),
                settings.getInt(SettingKeys.IMPACT_DEFAULT_DEPTH), settings.getInt(SettingKeys.IMPACT_MAX_DEPTH),
                settings.getDuration(SettingKeys.UI_POLL_INTERVAL).toMillis());
    }
}
```

Create the snapshot: `./mvnw test -Dtest=OpenApiSnapshotTest -Dopenapi.update=true`. It writes `frontend/openapi.json`.

In `README.md`, add an API table row for `GET /ui-config` (USER): page sizes, graph node limit, impact depths and the UI poll interval, all from settings. Also add a short "API contract" note describing the snapshot test and the refresh command.

- [ ] **Step 4: Run the tests**

Run: `./mvnw test -Dtest='UiConfigApiTest,OpenApiSnapshotTest,SchemaMigrationTest,AppSettingsTest'`
Expected: all pass.

Run: `./mvnw test`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V9__web_ui.sql src/main/java/com/graphify/settings/SettingKeys.java \
  src/main/java/com/graphify/web/UiConfig.java src/main/java/com/graphify/web/UiConfigController.java \
  src/test/java/com/graphify/web/UiConfigApiTest.java src/test/java/com/graphify/web/OpenApiSnapshotTest.java \
  frontend/openapi.json README.md
git commit -m "feat(web): expose the UI settings and keep an OpenAPI snapshot for the frontend" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01XpYgYKeBYFNw4tb6EwJ44V"
```

---

### Task 4: Frontend scaffold, generated API types and test harness

**Files (all under `frontend/`):**
- Create: `package.json`, `package-lock.json`, `.gitignore`, `index.html`, `vite.config.ts`, `tsconfig.json`, `eslint.config.js`, `src/main.tsx`, `src/App.tsx`, `src/i18n/tr.ts`, `src/config/basePath.ts`, `src/api/schema.d.ts` (generated), `src/test/setup.ts`, `src/test/server.ts`, `src/config/basePath.test.ts`, `src/App.test.tsx`

**Interfaces:**
- **Consumes:** `frontend/openapi.json` (Task 3).
- **Produces:**
  - **npm scripts:**
    - `dev`
    - `typecheck` (`tsc -p tsconfig.json --noEmit`)
    - `lint`
    - `test` (typecheck + lint + `vitest run`)
    - `build` (typecheck + `vite build` into `dist/`)
    - `generate:api` (`openapi-typescript openapi.json -o src/api/schema.d.ts`)
  - **Base path:**
    - `appRoot(): string` is the absolute application root without a trailing slash, from `document.baseURI`;
    - `routerBasename(): string | undefined` is its path, or `undefined` at the server root.
  - **Text:** `tr` is the Turkish text object.
  - **Test harness:**
    - jsdom at `http://localhost/graphify/`, so tests run under a context path;
    - an MSW `server` that fails on unhandled requests;
    - `apiUrl(path)` builds handler URLs.

- [ ] **Step 1: Create the project files**

`frontend/.gitignore`:

```
node_modules/
dist/
coverage/
```

`frontend/index.html`:

```html
<!doctype html>
<html lang="tr">
  <head>
    <meta charset="UTF-8" />
    <!-- the backend rewrites this to the deployment's context path; the dev server sets "/" -->
    <base href="./" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>Graphify</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

`frontend/package.json`. Dependency versions are added by the install commands in Step 2:

```json
{
  "name": "graphify-web",
  "private": true,
  "type": "module",
  "engines": { "node": ">=24", "npm": ">=11" },
  "scripts": {
    "dev": "vite",
    "typecheck": "tsc -p tsconfig.json --noEmit",
    "lint": "eslint .",
    "test": "npm run typecheck && npm run lint && vitest run",
    "build": "npm run typecheck && vite build",
    "generate:api": "openapi-typescript openapi.json -o src/api/schema.d.ts"
  }
}
```

`frontend/vite.config.ts`:

```ts
import { defineConfig } from 'vitest/config';
import type { Plugin } from 'vite';
import react from '@vitejs/plugin-react';

/** The dev server serves the app at "/"; in the WAR the backend writes the real context path into <base>. */
function devBaseHref(): Plugin {
  return {
    name: 'graphify-dev-base-href',
    apply: 'serve',
    transformIndexHtml: (html) => html.replace(/<base href="[^"]*"\s*\/?>/, '<base href="/" />'),
  };
}

export default defineConfig({
  // relative asset URLs, resolved against <base href>, so the build works under any context path
  base: './',
  plugins: [react(), devBaseHref()],
  server: {
    // developer convenience only: where `npm run dev` forwards API calls (GRAPHIFY_API overrides it)
    proxy: { '/api': process.env.GRAPHIFY_API ?? 'http://localhost:8080' },
  },
  test: {
    environment: 'jsdom',
    environmentOptions: { jsdom: { url: 'http://localhost/graphify/' } },
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
});
```

`frontend/tsconfig.json`:

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "lib": ["ES2023", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "moduleResolution": "bundler",
    "jsx": "react-jsx",
    "strict": true,
    "noEmit": true,
    "skipLibCheck": true,
    "isolatedModules": true,
    "verbatimModuleSyntax": true,
    "noUnusedLocals": true,
    "noUnusedParameters": true,
    "types": ["node", "vite/client", "@testing-library/jest-dom"]
  },
  "include": ["src", "vite.config.ts", "eslint.config.js"]
}
```

`frontend/eslint.config.js`:

```js
import js from '@eslint/js';
import tseslint from 'typescript-eslint';
import reactHooks from 'eslint-plugin-react-hooks';

export default tseslint.config(
  { ignores: ['dist', 'node_modules', 'src/api/schema.d.ts'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  { plugins: { 'react-hooks': reactHooks }, rules: { ...reactHooks.configs.recommended.rules } },
);
```

If the installed `eslint-plugin-react-hooks` exposes its flat preset under another key (e.g. `configs['recommended-latest']` or `configs.flat.recommended`), use that one. Keep the rules of hooks enforced.

`frontend/src/i18n/tr.ts`:

```ts
/** Every text the user sees (web UI spec §2: Turkish only, no literal text in components). */
export const tr = {
  app: { name: 'Graphify' },
  nav: { home: 'Ana sayfa' },
  header: {
    changePassword: 'Şifre değiştir',
    logout: 'Çıkış',
    roles: { ADMIN: 'Yönetici', USER: 'Kullanıcı' },
  },
  login: { title: 'Giriş', username: 'Kullanıcı adı', password: 'Şifre', submit: 'Giriş yap' },
  changePassword: {
    title: 'Şifre değiştir',
    current: 'Mevcut şifre',
    next: 'Yeni şifre',
    confirm: 'Yeni şifre (tekrar)',
    mismatch: 'Yeni şifreler aynı değil.',
    mustChange: 'Devam etmeden önce şifrenizi değiştirmeniz gerekiyor.',
    submit: 'Değiştir',
    done: 'Şifreniz değiştirildi.',
  },
  home: {
    title: 'Hoş geldiniz',
    intro: 'Sembol arama, etki analizi ve repo grafı ekranları sonraki sürümlerde bu menüye eklenecek.',
  },
  errors: {
    forbidden: 'Bu işlem için yetkiniz yok.',
    unreachable: 'Sunucuya ulaşılamıyor.',
    notFound: 'Sayfa bulunamadı.',
    required: 'Bu alan zorunlu.',
    retry: 'Tekrar dene',
  },
  common: { loading: 'Yükleniyor…' },
} as const;
```

`frontend/src/config/basePath.ts`:

```ts
/** The application root as an absolute URL without a trailing slash, from the <base href> the backend writes. */
export function appRoot(): string {
  return new URL('.', document.baseURI).href.replace(/\/$/, '');
}

/** The router basename: the root's path ("/graphify"), or undefined when the app is at the server root. */
export function routerBasename(): string | undefined {
  const path = new URL(`${appRoot()}/`).pathname.replace(/\/$/, '');
  return path === '' ? undefined : path;
}
```

`frontend/src/App.tsx` (Task 6 replaces this placeholder):

```tsx
import { MantineProvider, Title } from '@mantine/core';
import { tr } from './i18n/tr';

export function App() {
  return (
    <MantineProvider>
      <Title order={1}>{tr.app.name}</Title>
    </MantineProvider>
  );
}
```

`frontend/src/main.tsx`:

```tsx
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import '@mantine/core/styles.css';
import '@mantine/notifications/styles.css';
import { App } from './App';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
```

`frontend/src/test/server.ts`:

```ts
import { setupServer } from 'msw/node';
import { appRoot } from '../config/basePath';

/** The mock backend; tests add handlers per case. Unhandled requests fail the test (see setup.ts). */
export const server = setupServer();

/** An absolute API URL under the test page's context path (http://localhost/graphify). */
export function apiUrl(path: string): string {
  return `${appRoot()}${path}`;
}
```

`frontend/src/test/setup.ts`:

```ts
import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { server } from './server';

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
afterAll(() => server.close());

// jsdom lacks the browser APIs Mantine reads
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
  }),
});
class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}
window.ResizeObserver = ResizeObserverStub as unknown as typeof ResizeObserver;
```

- [ ] **Step 2: Install the dependencies**

Run inside `frontend/`. These use the npm registry configured on this machine. The user's build uses its internal registry through Maven, in Task 5.

```bash
npm install react react-dom @mantine/core @mantine/hooks @mantine/form @mantine/notifications \
  react-router @tanstack/react-query openapi-fetch
npm install -D typescript vite @vitejs/plugin-react vitest jsdom @testing-library/react \
  @testing-library/user-event @testing-library/jest-dom msw openapi-typescript eslint @eslint/js \
  typescript-eslint eslint-plugin-react-hooks @types/react @types/react-dom @types/node
```

Version rules:
- React must be ≥ 19.
- `react-router` must be v7 or newer: the `react-router` package, not `react-router-dom`.
- `@tanstack/react-query` must be v5 or newer, and `msw` v2 or newer.

Commit `package-lock.json`. Then run `npm run generate:api` to create `src/api/schema.d.ts` from the snapshot.

- [ ] **Step 3: Write the failing tests**

`frontend/src/config/basePath.test.ts`:

```ts
import { afterEach, describe, expect, it } from 'vitest';
import { appRoot, routerBasename } from './basePath';

describe('base path', () => {
  afterEach(() => document.head.querySelectorAll('base').forEach((b) => b.remove()));

  it('follows the page under a context path', () => {
    expect(appRoot()).toBe('http://localhost/graphify');
    expect(routerBasename()).toBe('/graphify');
  });

  it('follows the base element the backend writes', () => {
    const base = document.createElement('base');
    base.href = '/';
    document.head.appendChild(base);
    expect(appRoot()).toBe('http://localhost');
    expect(routerBasename()).toBeUndefined();
  });
});
```

`frontend/src/App.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react';
import { expect, it } from 'vitest';
import { App } from './App';
import { tr } from './i18n/tr';

it('renders the application name', () => {
  render(<App />);
  expect(screen.getByRole('heading', { name: tr.app.name })).toBeInTheDocument();
});
```

Run: `npm test`
Expected: the typecheck, lint and both tests pass once all files exist. Before that, the tests fail on missing modules. Record the RED run you had while creating them.

- [ ] **Step 4: Build**

Run: `npm run build && ls dist && grep -o '<base href="[^"]*"' dist/index.html && grep -o 'src="[^"]*assets/[^"]*"' dist/index.html`
Expected:
- `dist/index.html` keeps `<base href="./"`;
- its script tag points at `./assets/…` (relative);
- `dist/assets/` exists.

- [ ] **Step 5: Commit**

```bash
git add frontend/.gitignore frontend/package.json frontend/package-lock.json frontend/index.html frontend/vite.config.ts \
  frontend/tsconfig.json frontend/eslint.config.js frontend/src
git commit -m "feat(frontend): scaffold the React app with generated API types and a test harness" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01XpYgYKeBYFNw4tb6EwJ44V"
```

---

### Task 5: Build the frontend into the WAR

**Files:**
- Modify: `pom.xml`, `README.md`

**Interfaces:**
- **Consumes:** Task 4's npm scripts (`ci`, `test`, `build` → `frontend/dist`); Task 1's packaging and smoke script.
- **Produces (Maven properties):**
  - Required, never committed: `frontend.node.downloadRoot` (a Node distribution mirror, e.g. an internal Nexus raw proxy of `https://nodejs.org/dist/`) and `frontend.npm.registry` (the npm registry URL).
  - Pinned build facts in `pom.xml`: `frontend.node.version` (v24 line), `frontend.npm.version` (11 line) and `frontend-maven-plugin.version`.
  - `frontend.skip` (default `false`) builds a WAR without the UI.
- **Produces (build behaviour):**
  - In `prepare-package`: enforcer `requireProperty` for both mirrors (skipped with `frontend.skip`), then `install-node-and-npm`, `npm ci`, `npm test` and `npm run build`, then copy `frontend/dist/**` to `${project.build.outputDirectory}/static`.
  - `./mvnw test` runs none of this.

- [ ] **Step 1: Add the build**

In `pom.xml` `<properties>`:

```xml
		<!-- build toolchain for the web UI; the mirrors are given per machine (settings.xml or -D), never here -->
		<frontend.node.version>v24.5.0</frontend.node.version>
		<frontend.npm.version>11.6.0</frontend.npm.version>
		<frontend.skip>false</frontend.skip>
		<frontend-maven-plugin.version>CHECK</frontend-maven-plugin.version>
```

Replace `CHECK` with the latest released `com.github.eirslett:frontend-maven-plugin` from Maven Central. Find it with `./mvnw help:evaluate` or the Central search, and record it in the report. Pin the Node and npm versions to the newest 24.x and 11.x releases available from the configured mirror. The values above are what is installed locally.

Add these plugins to `<build><plugins>`, after `maven-enforcer-plugin`, in this order:

```xml
			<plugin>
				<groupId>org.apache.maven.plugins</groupId>
				<artifactId>maven-enforcer-plugin</artifactId>
				<executions>
					<execution>
						<id>require-frontend-mirrors</id>
						<phase>prepare-package</phase>
						<goals>
							<goal>enforce</goal>
						</goals>
						<configuration>
							<skip>${frontend.skip}</skip>
							<rules>
								<requireProperty>
									<property>frontend.node.downloadRoot</property>
									<message>Set -Dfrontend.node.downloadRoot (a Node.js distribution mirror) or build with -Dfrontend.skip=true</message>
								</requireProperty>
								<requireProperty>
									<property>frontend.npm.registry</property>
									<message>Set -Dfrontend.npm.registry (the npm registry) or build with -Dfrontend.skip=true</message>
								</requireProperty>
							</rules>
						</configuration>
					</execution>
				</executions>
			</plugin>
			<plugin>
				<groupId>com.github.eirslett</groupId>
				<artifactId>frontend-maven-plugin</artifactId>
				<version>${frontend-maven-plugin.version}</version>
				<configuration>
					<workingDirectory>frontend</workingDirectory>
					<installDirectory>${project.build.directory}/node</installDirectory>
					<nodeVersion>${frontend.node.version}</nodeVersion>
					<npmVersion>${frontend.npm.version}</npmVersion>
					<nodeDownloadRoot>${frontend.node.downloadRoot}</nodeDownloadRoot>
					<npmDownloadRoot>${frontend.npm.registry}/npm/-/</npmDownloadRoot>
					<npmRegistryURL>${frontend.npm.registry}</npmRegistryURL>
					<skip>${frontend.skip}</skip>
				</configuration>
				<executions>
					<execution>
						<id>install-node-and-npm</id>
						<phase>prepare-package</phase>
						<goals><goal>install-node-and-npm</goal></goals>
					</execution>
					<execution>
						<id>npm-ci</id>
						<phase>prepare-package</phase>
						<goals><goal>npm</goal></goals>
						<configuration><arguments>ci</arguments></configuration>
					</execution>
					<execution>
						<id>npm-test</id>
						<phase>prepare-package</phase>
						<goals><goal>npm</goal></goals>
						<configuration><arguments>test</arguments></configuration>
					</execution>
					<execution>
						<id>npm-build</id>
						<phase>prepare-package</phase>
						<goals><goal>npm</goal></goals>
						<configuration><arguments>run build</arguments></configuration>
					</execution>
				</executions>
			</plugin>
			<plugin>
				<groupId>org.apache.maven.plugins</groupId>
				<artifactId>maven-resources-plugin</artifactId>
				<executions>
					<execution>
						<id>copy-web-ui</id>
						<phase>prepare-package</phase>
						<goals><goal>copy-resources</goal></goals>
						<configuration>
							<skip>${frontend.skip}</skip>
							<outputDirectory>${project.build.outputDirectory}/static</outputDirectory>
							<resources>
								<resource><directory>frontend/dist</directory></resource>
							</resources>
						</configuration>
					</execution>
				</executions>
			</plugin>
```

- Merge the enforcer execution into the existing `maven-enforcer-plugin` declaration rather than declaring the plugin twice. Executions of different plugins in one phase run in POM declaration order, so keep enforcer, then frontend, then resources.
- If the plugin's `npmDownloadRoot` layout differs for the chosen version, follow its documentation. The npm tarball must come from the configured registry.
- If the plugin version uses `skip.npm`/`skip.installnodenpm` user properties instead of `<skip>`, wire `frontend.skip` to them.

- [ ] **Step 2: Build the WAR with the UI and inspect it**

Run (this machine can reach the public mirrors; on the user's build they are internal):

```bash
./mvnw -q package -DskipTests -Dfrontend.node.downloadRoot=https://nodejs.org/dist/ -Dfrontend.npm.registry=https://registry.npmjs.org
unzip -l target/graphify-0.0.1-SNAPSHOT.war | grep -E 'static/(index.html|assets/)' | head
```

Expected: `WEB-INF/classes/static/index.html` and `WEB-INF/classes/static/assets/*.js` are present.

Run: `./mvnw -q package -DskipTests -Dfrontend.skip=true` → the WAR builds without the UI, with no mirrors needed.
Run: `./mvnw -q package -DskipTests` (no mirror properties) → it fails with the enforcer message.
Run: `./mvnw test` → backend tests only, all pass, no npm.

- [ ] **Step 3: Smoke on WildFly with the UI**

Re-run Task 1 Step 5's procedure with the new WAR: the Oracle container plus the WildFly 41 kept in `/tmp`. The script now also checks:
- `/{ctx}/` with `<base href="/graphify/"`;
- a served asset;
- a deep link.

Expected: `WildFly smoke test passed`.

- [ ] **Step 4: Document the build**

In `README.md`, add a `## Web UI` section:
- **Layout:** `frontend/` is a Vite + React app built into the WAR.
- **Mirror properties:** the two properties, set in `~/.m2/settings.xml`:

  ```xml
  <profile><id>graphify-mirrors</id><activation><activeByDefault>true</activeByDefault></activation>
    <properties><frontend.node.downloadRoot>…</frontend.node.downloadRoot>
    <frontend.npm.registry>…</frontend.npm.registry></properties></profile>
  ```

- **Skipping the UI:** `-Dfrontend.skip=true`.
- **Development:**
  - `cd frontend && npm ci && npm run dev`, with the backend on `GRAPHIFY_API` (default `http://localhost:8080`);
  - `npm test`;
  - after an API change, refresh the snapshot and run `npm run generate:api`.

- [ ] **Step 5: Commit**

```bash
git add pom.xml README.md
git commit -m "build: build the web UI into the WAR from configurable Node and npm mirrors" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01XpYgYKeBYFNw4tb6EwJ44V"
```

---

### Task 6: API client, sign-in, password change and the main layout

**Files (under `frontend/src/`):**
- Create: `api/client.ts`, `api/queryClient.ts`, `auth/session.ts`, `auth/RequireAuth.tsx`, `auth/safeNext.ts`, `config/UiConfigContext.tsx`, `components/ErrorView.tsx`, `layout/AppLayout.tsx`, `layout/navigation.ts`, `pages/LoginPage.tsx`, `pages/ChangePasswordPage.tsx`, `pages/HomePage.tsx`, `pages/NotFoundPage.tsx`, `routes.tsx`
- Modify: `App.tsx`, `main.tsx`, `i18n/tr.ts`
- Test: `api/client.test.ts`, `auth/auth.test.tsx`, `auth/safeNext.test.ts`, `layout/navigation.test.ts`, `test/renderApp.tsx`
- Delete: `App.test.tsx` (superseded by `auth.test.tsx`)

**Interfaces:**
- **Consumes:**
  - backend `GET /api/v1/auth/csrf` → `{headerName, token}`;
  - `POST /auth/login` → `Me`;
  - `GET /auth/me` → `Me` (401 when anonymous);
  - `POST /auth/logout` → 204;
  - `POST /auth/change-password` → `Me`;
  - `GET /ui-config` → `UiConfig`;
  - 403 problems with `code: "CSRF"`.

  Schema names come from the generated `schema.d.ts`; use the names it contains (`Me`, `Credentials`, `PasswordChange`, `UiConfig`, `CsrfView`).
- **Produces:**
  - **`api/client.ts`:**
    - `api`, an `openapi-fetch` client on `appRoot()`;
    - `call(promise)`, which returns `data` or throws `ApiError(status, problem)`;
    - `ApiError`;
    - `resetCsrf()`;
    - `csrfFetch`.
  - **`auth/session.ts`:**
    - `useMe()`, where `null` means signed out;
    - `useLogin()`, `useLogout()` and `useChangePassword()`. Each resets the CSRF token after it succeeds, because the backend renews the token on sign-in and password change.
  - **`auth/RequireAuth`:** redirects to `/login?next=…` when signed out, and to `/change-password` when `mustChangePassword`.
  - **`safeNext(next)`:** returns an in-app path, or `/`.
  - **`layout/navigation.ts`:** `NAV_ITEMS` and `visibleItems(items, role)`.
  - **`config/UiConfigContext`:** `useUiConfig()`.
  - **`routes.tsx`:** `routes`, the route objects shared by `App` and the tests.

- [ ] **Step 1: Write the failing tests**

`frontend/src/test/renderApp.tsx`:

```tsx
import { MantineProvider } from '@mantine/core';
import { Notifications } from '@mantine/notifications';
import { QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { createQueryClient } from '../api/queryClient';
import { resetCsrf } from '../api/client';
import { routes } from '../routes';

/** Renders the whole app at an in-app path with a fresh cache and CSRF token. */
export function renderApp(path: string) {
  resetCsrf();
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  render(
    <MantineProvider>
      <Notifications />
      <QueryClientProvider client={createQueryClient()}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </MantineProvider>,
  );
  return router;
}
```

`frontend/src/api/client.test.ts`:

```ts
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import { server, apiUrl } from '../test/server';
import { api, call, resetCsrf, ApiError } from './client';

describe('api client', () => {
  beforeEach(() => resetCsrf());

  it('sends the CSRF token the server names on unsafe requests only', async () => {
    const seen: (string | null)[] = [];
    let csrfCalls = 0;
    server.use(
      http.get(apiUrl('/api/v1/auth/csrf'), () => {
        csrfCalls++;
        return HttpResponse.json({ headerName: 'X-XSRF-TOKEN', token: 't1' });
      }),
      http.post(apiUrl('/api/v1/auth/logout'), ({ request }) => {
        seen.push(request.headers.get('X-XSRF-TOKEN'));
        return new HttpResponse(null, { status: 204 });
      }),
      http.get(apiUrl('/api/v1/auth/me'), ({ request }) => {
        seen.push(request.headers.get('X-XSRF-TOKEN'));
        return HttpResponse.json({ username: 'u', role: 'USER', mustChangePassword: false });
      }),
    );

    await call(api.POST('/api/v1/auth/logout'));
    await call(api.GET('/api/v1/auth/me'));
    await call(api.POST('/api/v1/auth/logout'));

    expect(seen).toEqual(['t1', null, 't1']);
    expect(csrfCalls).toBe(1);
  });

  it('refetches a rejected CSRF token once and retries', async () => {
    const tokens = ['old', 'new'];
    const seen: (string | null)[] = [];
    server.use(
      http.get(apiUrl('/api/v1/auth/csrf'), () =>
        HttpResponse.json({ headerName: 'X-XSRF-TOKEN', token: tokens.shift() })),
      http.post(apiUrl('/api/v1/auth/logout'), ({ request }) => {
        const token = request.headers.get('X-XSRF-TOKEN');
        seen.push(token);
        return token === 'new'
          ? new HttpResponse(null, { status: 204 })
          : HttpResponse.json({ status: 403, detail: 'Missing or invalid CSRF token', code: 'CSRF' }, { status: 403 });
      }),
    );

    await call(api.POST('/api/v1/auth/logout'));

    expect(seen).toEqual(['old', 'new']);
  });

  it('does not retry other 403s and reports the problem detail', async () => {
    let posts = 0;
    server.use(
      http.get(apiUrl('/api/v1/auth/csrf'), () => HttpResponse.json({ headerName: 'X-XSRF-TOKEN', token: 't' })),
      http.post(apiUrl('/api/v1/auth/logout'), () => {
        posts++;
        return HttpResponse.json({ status: 403, detail: 'Not allowed' }, { status: 403 });
      }),
    );

    const error = await call(api.POST('/api/v1/auth/logout')).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(403);
    expect((error as ApiError).message).toBe('Not allowed');
    expect(posts).toBe(1);
  });
});
```

`frontend/src/auth/safeNext.test.ts`:

```ts
import { expect, it } from 'vitest';
import { safeNext } from './safeNext';

it('keeps in-app paths and drops anything that could leave the app', () => {
  expect(safeNext('/repositories/3?tab=runs')).toBe('/repositories/3?tab=runs');
  expect(safeNext(null)).toBe('/');
  expect(safeNext('//evil.example/x')).toBe('/');
  expect(safeNext('/\\evil.example')).toBe('/');
  expect(safeNext('https://evil.example')).toBe('/');
  expect(safeNext('relative')).toBe('/');
});
```

`frontend/src/layout/navigation.test.ts`:

```ts
import { expect, it } from 'vitest';
import { visibleItems, type NavItem } from './navigation';

it('shows admin items to admins only', () => {
  const items: NavItem[] = [
    { path: '/', label: 'a' },
    { path: '/admin/x', label: 'b', role: 'ADMIN' },
  ];
  expect(visibleItems(items, 'USER').map((i) => i.path)).toEqual(['/']);
  expect(visibleItems(items, 'ADMIN').map((i) => i.path)).toEqual(['/', '/admin/x']);
});
```

`frontend/src/auth/auth.test.tsx`:

```tsx
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { tr } from '../i18n/tr';
import { apiUrl, server } from '../test/server';
import { renderApp } from '../test/renderApp';

const user = { username: 'ayse', displayName: 'Ayşe', role: 'USER', source: 'LOCAL', mustChangePassword: false };
const uiConfig = {
  pageDefaultSize: 50, pageMaxSize: 500, graphMaxNodes: 500, impactDefaultDepth: 3, impactMaxDepth: 10,
  pollIntervalMillis: 5000,
};

function backend(options: { signedIn: boolean; me?: typeof user; loginError?: string }) {
  let signedIn = options.signedIn;
  let me = options.me ?? user;
  server.use(
    http.get(apiUrl('/api/v1/auth/csrf'), () => HttpResponse.json({ headerName: 'X-XSRF-TOKEN', token: 't' })),
    http.get(apiUrl('/api/v1/auth/me'), () =>
      signedIn ? HttpResponse.json(me) : HttpResponse.json({ status: 401, detail: 'Authentication is required' }, { status: 401 })),
    http.post(apiUrl('/api/v1/auth/login'), () => {
      if (options.loginError) {
        return HttpResponse.json({ status: 401, detail: options.loginError }, { status: 401 });
      }
      signedIn = true;
      return HttpResponse.json(me);
    }),
    http.post(apiUrl('/api/v1/auth/change-password'), () => {
      me = { ...me, mustChangePassword: false };
      return HttpResponse.json(me);
    }),
    http.get(apiUrl('/api/v1/ui-config'), () =>
      signedIn ? HttpResponse.json(uiConfig) : HttpResponse.json({ status: 401 }, { status: 401 })),
  );
  return { expire: () => { signedIn = false; } };
}

async function signIn() {
  await userEvent.type(await screen.findByLabelText(tr.login.username), 'ayse');
  await userEvent.type(screen.getByLabelText(tr.login.password), 'secret-pw');
  await userEvent.click(screen.getByRole('button', { name: tr.login.submit }));
}

describe('sign-in', () => {
  it('sends a signed-out visitor to sign-in and back to the page they asked for', async () => {
    backend({ signedIn: false });
    const router = renderApp('/somewhere?x=1');

    await signIn();

    await waitFor(() => expect(router.state.location.pathname).toBe('/somewhere'));
    expect(router.state.location.search).toBe('?x=1');
    expect(await screen.findByText(tr.errors.notFound)).toBeInTheDocument();
  });

  it('ignores an off-site next', async () => {
    backend({ signedIn: false });
    const router = renderApp('/login?next=%2F%2Fevil.example%2Fx');

    await signIn();

    await waitFor(() => expect(router.state.location.pathname).toBe('/'));
    expect(await screen.findByText(tr.home.title)).toBeInTheDocument();
  });

  it('shows the backend message when sign-in fails', async () => {
    backend({ signedIn: false, loginError: 'Invalid username or password' });
    renderApp('/');

    await signIn();

    expect(await screen.findByText('Invalid username or password')).toBeInTheDocument();
  });

  it('makes a user who must change the password do so first', async () => {
    backend({ signedIn: true, me: { ...user, mustChangePassword: true } });
    const router = renderApp('/');

    expect(await screen.findByText(tr.changePassword.mustChange)).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText(tr.changePassword.current), 'old-pw');
    await userEvent.type(screen.getByLabelText(tr.changePassword.next), 'New-Password-1');
    await userEvent.type(screen.getByLabelText(tr.changePassword.confirm), 'New-Password-1');
    await userEvent.click(screen.getByRole('button', { name: tr.changePassword.submit }));

    await waitFor(() => expect(router.state.location.pathname).toBe('/'));
    expect(await screen.findByText(tr.home.title)).toBeInTheDocument();
  });

  it('a later 401 returns to sign-in', async () => {
    const session = backend({ signedIn: true });
    const router = renderApp('/');
    expect(await screen.findByText(tr.home.title)).toBeInTheDocument();

    session.expire();
    router.navigate('/elsewhere');

    expect(await screen.findByLabelText(tr.login.username)).toBeInTheDocument();
    expect(router.state.location.search).toContain(encodeURIComponent('/elsewhere'));
  });

  it('shows the signed-in user and no admin menu to a USER', async () => {
    backend({ signedIn: true });
    renderApp('/');

    expect(await screen.findByText('Ayşe')).toBeInTheDocument();
    expect(screen.getByText(tr.header.roles.USER)).toBeInTheDocument();
    expect(screen.queryByText(tr.nav.admin)).not.toBeInTheDocument();
  });
});
```

How "a later 401" works: navigating to `/elsewhere` mounts `NotFoundPage` inside the layout. The layout keeps `ui-config` cached, so to exercise an API 401 on navigation, `NotFoundPage` does nothing. Instead `AppLayout` re-validates `me` on navigation: `useMe()` uses `refetchOnMount: 'always'` inside `RequireAuth`, and the route change remounts it. If your router setup keeps `RequireAuth` mounted across child navigations, trigger the re-check via `queryClient.invalidateQueries({ queryKey: ['me'] })` from a `useEffect` on `location.pathname` in `RequireAuth`. Keep the test as written.

Add `nav.admin: 'Yönetim'` to `tr.ts`. The menu shows the "Yönetim" group heading only when an admin item is visible.

- [ ] **Step 2: Run them to verify they fail**

Run: `npm test` (in `frontend/`)
Expected: type errors and failures; the modules don't exist yet.

- [ ] **Step 3: Implement**

`frontend/src/api/client.ts`:

```ts
import createClient from 'openapi-fetch';
import { appRoot } from '../config/basePath';
import type { paths } from './schema';

/** An RFC 7807 problem as the backend sends it. */
export interface Problem {
  title?: string;
  status?: number;
  detail?: string;
  code?: string;
}

/** A failed API call; the message is the backend's problem detail, shown to the user as is. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly problem: Problem,
  ) {
    super(problem.detail ?? problem.title ?? `HTTP ${status}`);
  }
}

/** HTTP methods that change state and therefore carry the CSRF token. */
const UNSAFE_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

/** The problem code the backend uses for a missing or stale CSRF token. */
const CSRF_PROBLEM = 'CSRF';

interface CsrfToken {
  headerName: string;
  token: string;
}

let csrf: CsrfToken | null = null;

/** Forget the token; the backend renews it at sign-in and password change, so it is fetched again when needed. */
export function resetCsrf(): void {
  csrf = null;
}

async function problemOf(response: Response): Promise<Problem> {
  try {
    return (await response.json()) as Problem;
  } catch {
    return { status: response.status };
  }
}

async function loadCsrf(): Promise<CsrfToken> {
  const response = await fetch(`${appRoot()}/api/v1/auth/csrf`, { credentials: 'same-origin' });
  if (!response.ok) {
    throw new ApiError(response.status, await problemOf(response));
  }
  csrf = (await response.json()) as CsrfToken;
  return csrf;
}

function withToken(request: Request, token: CsrfToken): Request {
  const headers = new Headers(request.headers);
  headers.set(token.headerName, token.token);
  return new Request(request, { headers });
}

/** fetch for the API client: adds the CSRF token to unsafe requests and retries once on a stale token. */
export async function csrfFetch(request: Request): Promise<Response> {
  if (!UNSAFE_METHODS.has(request.method)) {
    return fetch(request);
  }
  const retry = request.clone();
  const first = await fetch(withToken(request, csrf ?? (await loadCsrf())));
  if (first.status !== 403 || (await problemOf(first.clone())).code !== CSRF_PROBLEM) {
    return first;
  }
  return fetch(withToken(retry, await loadCsrf()));
}

export const api = createClient<paths>({ baseUrl: appRoot(), fetch: csrfFetch, credentials: 'same-origin' });

/** The data of a successful call; otherwise throws ApiError with the backend's problem. */
export async function call<T>(promise: Promise<{ data?: T; error?: unknown; response: Response }>): Promise<T> {
  const { data, error, response } = await promise;
  if (!response.ok) {
    throw new ApiError(response.status, (error ?? { status: response.status }) as Problem);
  }
  return data as T;
}
```

If the `openapi-fetch` result type does not fit `call`'s parameter, widen the parameter so the generic still infers the success type. Keep the throwing behaviour.

`frontend/src/api/queryClient.ts`:

```ts
import { MutationCache, QueryCache, QueryClient } from '@tanstack/react-query';
import { ApiError } from './client';

/** The query key of the signed-in user (null when signed out). */
export const ME_KEY = ['me'] as const;

/** A 401 from any call means the session ended: forget the user, which sends RequireAuth to sign-in. */
export function createQueryClient(): QueryClient {
  const client: QueryClient = new QueryClient({
    queryCache: new QueryCache({ onError: (error) => signedOutOn(error) }),
    mutationCache: new MutationCache({ onError: (error) => signedOutOn(error) }),
    defaultOptions: { queries: { retry: false } },
  });
  function signedOutOn(error: unknown) {
    if (error instanceof ApiError && error.status === 401) {
      client.setQueryData(ME_KEY, null);
    }
  }
  return client;
}
```

`frontend/src/auth/session.ts`:

```ts
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError, call, resetCsrf } from '../api/client';
import { ME_KEY } from '../api/queryClient';
import type { components } from '../api/schema';

export type Me = components['schemas']['Me'];
export type Role = NonNullable<Me['role']>;

/** The signed-in user, or null when signed out. */
export function useMe() {
  return useQuery({
    queryKey: ME_KEY,
    queryFn: async (): Promise<Me | null> => {
      try {
        return await call(api.GET('/api/v1/auth/me'));
      } catch (error) {
        if (error instanceof ApiError && error.status === 401) {
          return null;
        }
        throw error;
      }
    },
  });
}

export function useLogin() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (credentials: { username: string; password: string }) =>
      call(api.POST('/api/v1/auth/login', { body: credentials })),
    onSuccess: (me) => {
      resetCsrf();
      client.setQueryData(ME_KEY, me);
    },
  });
}

export function useLogout() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => call(api.POST('/api/v1/auth/logout')),
    onSettled: () => {
      resetCsrf();
      client.clear();
      client.setQueryData(ME_KEY, null);
    },
  });
}

export function useChangePassword() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (change: { currentPassword: string; newPassword: string }) =>
      call(api.POST('/api/v1/auth/change-password', { body: change })),
    onSuccess: (me) => {
      resetCsrf();
      client.setQueryData(ME_KEY, me);
    },
  });
}
```

`frontend/src/auth/safeNext.ts`:

```ts
/** Where to go after sign-in: only a path inside this app, never another site (no open redirect). */
export function safeNext(next: string | null): string {
  if (!next || !next.startsWith('/') || next.startsWith('//') || next.startsWith('/\\')) {
    return '/';
  }
  return next;
}
```

`frontend/src/auth/RequireAuth.tsx`:

```tsx
import { Center, Loader } from '@mantine/core';
import { useQueryClient } from '@tanstack/react-query';
import { useEffect, type ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router';
import { ME_KEY } from '../api/queryClient';
import { ErrorView } from '../components/ErrorView';
import { useMe } from './session';

/** Pages behind sign-in; re-checks the session on every navigation so an expired one returns to sign-in. */
export function RequireAuth({ children }: { children: ReactNode }) {
  const me = useMe();
  const location = useLocation();
  const client = useQueryClient();

  useEffect(() => {
    void client.invalidateQueries({ queryKey: ME_KEY });
  }, [client, location.pathname]);

  if (me.isPending) {
    return (
      <Center h="100vh">
        <Loader />
      </Center>
    );
  }
  if (me.isError) {
    return <ErrorView error={me.error} onRetry={() => void me.refetch()} />;
  }
  if (!me.data) {
    const next = encodeURIComponent(location.pathname + location.search);
    return <Navigate to={`/login?next=${next}`} replace />;
  }
  if (me.data.mustChangePassword && location.pathname !== '/change-password') {
    return <Navigate to="/change-password" replace />;
  }
  return <>{children}</>;
}
```

`frontend/src/components/ErrorView.tsx`:

```tsx
import { Alert, Button, Stack } from '@mantine/core';
import { ApiError } from '../api/client';
import { tr } from '../i18n/tr';

/** A failed load: the backend's message, a forbidden notice or "unreachable", with an optional retry. */
export function ErrorView({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const message =
    error instanceof ApiError
      ? error.status === 403
        ? tr.errors.forbidden
        : error.message
      : tr.errors.unreachable;
  return (
    <Stack p="md">
      <Alert color="red">{message}</Alert>
      {onRetry && (
        <Button variant="light" onClick={onRetry}>
          {tr.errors.retry}
        </Button>
      )}
    </Stack>
  );
}
```

`frontend/src/config/UiConfigContext.tsx`:

```tsx
import { useQuery } from '@tanstack/react-query';
import { createContext, useContext, type ReactNode } from 'react';
import { api, call } from '../api/client';
import type { components } from '../api/schema';
import { ErrorView } from '../components/ErrorView';
import { Center, Loader } from '@mantine/core';

export type UiConfig = components['schemas']['UiConfig'];

const UiConfigContext = createContext<UiConfig | null>(null);

/** Loads the UI settings (GET /ui-config) once per session and provides them to every page. */
export function UiConfigProvider({ children }: { children: ReactNode }) {
  const config = useQuery({ queryKey: ['ui-config'], queryFn: () => call(api.GET('/api/v1/ui-config')) });
  if (config.isPending) {
    return (
      <Center p="xl">
        <Loader />
      </Center>
    );
  }
  if (config.isError) {
    return <ErrorView error={config.error} onRetry={() => void config.refetch()} />;
  }
  return <UiConfigContext.Provider value={config.data}>{children}</UiConfigContext.Provider>;
}

export function useUiConfig(): UiConfig {
  const config = useContext(UiConfigContext);
  if (!config) {
    throw new Error('useUiConfig outside UiConfigProvider');
  }
  return config;
}
```

`frontend/src/layout/navigation.ts`:

```ts
import { tr } from '../i18n/tr';
import type { Role } from '../auth/session';

export interface NavItem {
  path: string;
  label: string;
  /** Only users with this role see the item; the backend enforces the same rule. */
  role?: Role;
}

/** The menu; later plans add their screens here. */
export const NAV_ITEMS: NavItem[] = [{ path: '/', label: tr.nav.home }];

export function visibleItems(items: NavItem[], role: Role | undefined): NavItem[] {
  return items.filter((item) => !item.role || item.role === role);
}
```

`frontend/src/layout/AppLayout.tsx`:

```tsx
import { AppShell, Badge, Button, Group, NavLink, Stack, Text, Title } from '@mantine/core';
import { Link, Outlet, useLocation, useNavigate } from 'react-router';
import { useLogout, useMe } from '../auth/session';
import { UiConfigProvider } from '../config/UiConfigContext';
import { tr } from '../i18n/tr';
import { NAV_ITEMS, visibleItems } from './navigation';

/** The signed-in frame: header with the user, the role-aware menu and the page. */
export function AppLayout() {
  const me = useMe().data;
  const logout = useLogout();
  const navigate = useNavigate();
  const location = useLocation();
  const items = visibleItems(NAV_ITEMS, me?.role);
  const userItems = items.filter((item) => !item.role);
  const adminItems = items.filter((item) => item.role === 'ADMIN');

  return (
    <AppShell header={{ height: 56 }} navbar={{ width: 240, breakpoint: 'sm' }} padding="md">
      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between">
          <Title order={3}>{tr.app.name}</Title>
          <Group>
            <Text>{me?.displayName ?? me?.username}</Text>
            {me?.role && <Badge variant="light">{tr.header.roles[me.role]}</Badge>}
            {me?.source === 'LOCAL' && (
              <Button variant="subtle" component={Link} to="/change-password">
                {tr.header.changePassword}
              </Button>
            )}
            <Button variant="default" onClick={() => logout.mutate(undefined, { onSettled: () => navigate('/login') })}>
              {tr.header.logout}
            </Button>
          </Group>
        </Group>
      </AppShell.Header>
      <AppShell.Navbar p="xs">
        <Stack gap={0}>
          {userItems.map((item) => (
            <NavLink key={item.path} component={Link} to={item.path} label={item.label}
              active={location.pathname === item.path} />
          ))}
          {adminItems.length > 0 && (
            <NavLink label={tr.nav.admin} defaultOpened>
              {adminItems.map((item) => (
                <NavLink key={item.path} component={Link} to={item.path} label={item.label}
                  active={location.pathname === item.path} />
              ))}
            </NavLink>
          )}
        </Stack>
      </AppShell.Navbar>
      <AppShell.Main>
        <UiConfigProvider>
          <Outlet />
        </UiConfigProvider>
      </AppShell.Main>
    </AppShell>
  );
}
```

The header's height and navbar width are layout styling, not operational settings.

`frontend/src/pages/LoginPage.tsx`:

```tsx
import { Alert, Button, Center, Paper, PasswordInput, Stack, TextInput, Title } from '@mantine/core';
import { useForm } from '@mantine/form';
import { Navigate, useNavigate, useSearchParams } from 'react-router';
import { safeNext } from '../auth/safeNext';
import { useLogin, useMe } from '../auth/session';
import { tr } from '../i18n/tr';

export function LoginPage() {
  const me = useMe();
  const login = useLogin();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const next = safeNext(params.get('next'));
  const form = useForm({
    initialValues: { username: '', password: '' },
    validate: {
      username: (value) => (value.trim() ? null : tr.errors.required),
      password: (value) => (value ? null : tr.errors.required),
    },
  });

  if (me.data) {
    return <Navigate to={next} replace />;
  }
  return (
    <Center h="100vh">
      <Paper withBorder p="xl" w={360}>
        <form onSubmit={form.onSubmit((values) => login.mutate(values, { onSuccess: () => navigate(next, { replace: true }) }))}>
          <Stack>
            <Title order={2}>{tr.login.title}</Title>
            {login.error && <Alert color="red">{login.error.message}</Alert>}
            <TextInput label={tr.login.username} autoComplete="username" {...form.getInputProps('username')} />
            <PasswordInput label={tr.login.password} autoComplete="current-password" {...form.getInputProps('password')} />
            <Button type="submit" loading={login.isPending}>
              {tr.login.submit}
            </Button>
          </Stack>
        </form>
      </Paper>
    </Center>
  );
}
```

`frontend/src/pages/ChangePasswordPage.tsx`:

```tsx
import { Alert, Button, Paper, PasswordInput, Stack, Title } from '@mantine/core';
import { useForm } from '@mantine/form';
import { notifications } from '@mantine/notifications';
import { useNavigate } from 'react-router';
import { useChangePassword, useMe } from '../auth/session';
import { tr } from '../i18n/tr';

export function ChangePasswordPage() {
  const me = useMe().data;
  const change = useChangePassword();
  const navigate = useNavigate();
  const form = useForm({
    initialValues: { currentPassword: '', newPassword: '', confirm: '' },
    validate: {
      currentPassword: (value) => (value ? null : tr.errors.required),
      newPassword: (value) => (value ? null : tr.errors.required),
      confirm: (value, values) => (value === values.newPassword ? null : tr.changePassword.mismatch),
    },
  });

  return (
    <Paper withBorder p="xl" maw={420}>
      <form
        onSubmit={form.onSubmit(({ currentPassword, newPassword }) =>
          change.mutate(
            { currentPassword, newPassword },
            {
              onSuccess: () => {
                notifications.show({ message: tr.changePassword.done });
                navigate('/', { replace: true });
              },
            },
          ),
        )}
      >
        <Stack>
          <Title order={2}>{tr.changePassword.title}</Title>
          {me?.mustChangePassword && <Alert color="yellow">{tr.changePassword.mustChange}</Alert>}
          {change.error && <Alert color="red">{change.error.message}</Alert>}
          <PasswordInput label={tr.changePassword.current} autoComplete="current-password"
            {...form.getInputProps('currentPassword')} />
          <PasswordInput label={tr.changePassword.next} autoComplete="new-password"
            {...form.getInputProps('newPassword')} />
          <PasswordInput label={tr.changePassword.confirm} autoComplete="new-password"
            {...form.getInputProps('confirm')} />
          <Button type="submit" loading={change.isPending}>
            {tr.changePassword.submit}
          </Button>
        </Stack>
      </form>
    </Paper>
  );
}
```

`frontend/src/pages/HomePage.tsx`:

```tsx
import { Stack, Text, Title } from '@mantine/core';
import { tr } from '../i18n/tr';

/** A short welcome until Plan 10 makes symbol search the home page. */
export function HomePage() {
  return (
    <Stack>
      <Title order={2}>{tr.home.title}</Title>
      <Text>{tr.home.intro}</Text>
    </Stack>
  );
}
```

`frontend/src/pages/NotFoundPage.tsx`:

```tsx
import { Text } from '@mantine/core';
import { tr } from '../i18n/tr';

export function NotFoundPage() {
  return <Text>{tr.errors.notFound}</Text>;
}
```

`frontend/src/routes.tsx`:

```tsx
import type { RouteObject } from 'react-router';
import { RequireAuth } from './auth/RequireAuth';
import { AppLayout } from './layout/AppLayout';
import { ChangePasswordPage } from './pages/ChangePasswordPage';
import { HomePage } from './pages/HomePage';
import { LoginPage } from './pages/LoginPage';
import { NotFoundPage } from './pages/NotFoundPage';

/** Every route; App uses a browser router with the context path as basename, tests a memory router. */
export const routes: RouteObject[] = [
  { path: '/login', element: <LoginPage /> },
  {
    path: '/',
    element: (
      <RequireAuth>
        <AppLayout />
      </RequireAuth>
    ),
    children: [
      { index: true, element: <HomePage /> },
      { path: 'change-password', element: <ChangePasswordPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
];
```

`frontend/src/App.tsx`:

```tsx
import { MantineProvider } from '@mantine/core';
import { Notifications } from '@mantine/notifications';
import { QueryClientProvider } from '@tanstack/react-query';
import { useState } from 'react';
import { createBrowserRouter, RouterProvider } from 'react-router';
import { createQueryClient } from './api/queryClient';
import { routerBasename } from './config/basePath';
import { routes } from './routes';

const router = createBrowserRouter(routes, { basename: routerBasename() });

export function App() {
  const [queryClient] = useState(createQueryClient);
  return (
    <MantineProvider defaultColorScheme="auto">
      <Notifications />
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </MantineProvider>
  );
}
```

Delete `frontend/src/App.test.tsx`. Its check is covered by the auth tests, which render the real app.

If a generated schema name differs (e.g. `Me` is `AuthController.Me`), use the generated name. If `role`/`source` are not string unions in the schema, type them from it as they are. Never redefine API types by hand.

- [ ] **Step 4: Run the tests and build**

Run: `npm test` (in `frontend/`)
Expected: the typecheck, lint and all tests pass.

Run: `npm run build`
Expected: success.

Run from the repository root:

```bash
./mvnw -q package -DskipTests -Dfrontend.node.downloadRoot=https://nodejs.org/dist/ -Dfrontend.npm.registry=https://registry.npmjs.org
```

Then re-run the WildFly smoke test (Task 1 Step 5) with `SMOKE_USER`/`SMOKE_PASSWORD`.

Expected: `WildFly smoke test passed`. If a browser automation tool is available to you, also open `http://localhost:8080/graphify/` while WildFly runs, sign in, and confirm:
- the home page shows;
- a reload at `/graphify/change-password` keeps the page;
- the console has no CSP violations.

Report what you checked.

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git rm frontend/src/App.test.tsx
git commit -m "feat(frontend): sign-in, password change, CSRF-aware API client and the main layout" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01XpYgYKeBYFNw4tb6EwJ44V"
```
