#!/usr/bin/env bash
# Deploys a WAR to a local WildFly, waits for the deployment and checks that the application answers
# (web UI spec §3.3, §8). Nothing here is application configuration: all values come from the caller.
#
# Usage: scripts/wildfly-smoke.sh path/to/app.war [context]
# Needs: WILDFLY_HOME, and the application's variables (DB_URL, DB_USER, DB_PASSWORD, APP_MASTER_KEY, ...).
# Optional: WILDFLY_HTTP_PORT (8080), WILDFLY_DEPLOY_TIMEOUT seconds (300), SMOKE_USER/SMOKE_PASSWORD to try a login,
#           SMOKE_FORCE=1 to replace an existing deployment of the same name.
# To run beside another WildFly use JAVA_OPTS=-Djboss.socket.binding.port-offset=N and a matching WILDFLY_HTTP_PORT.
# The script refuses to start when something already listens on the port or this WILDFLY_HOME is already running.
set -euo pipefail

war="${1:?usage: wildfly-smoke.sh app.war [context]}"
context="${2:-graphify}"
: "${WILDFLY_HOME:?set WILDFLY_HOME to the WildFly installation}"
: "${DB_URL:?}" "${DB_USER:?}" "${DB_PASSWORD:?}" "${APP_MASTER_KEY:?}"
port="${WILDFLY_HTTP_PORT:-8080}"
timeout="${WILDFLY_DEPLOY_TIMEOUT:-300}"
deployments="$WILDFLY_HOME/standalone/deployments"
base="http://localhost:$port/$context"

fail() { echo "$*" >&2; exit 1; }

[[ "$context" =~ ^[A-Za-z0-9_-]+$ ]] || fail "invalid context '$context' (letters, digits, '_' and '-' only)"
[[ -f "$war" ]] || fail "WAR not found: $war"
if curl -s -o /dev/null --max-time 2 "http://localhost:$port/"; then
    fail "something already answers on port $port; refusing to touch it (use another WILDFLY_HTTP_PORT and a port offset)"
fi
if pgrep -f -- "$WILDFLY_HOME/jboss-modules.jar" >/dev/null; then
    fail "a WildFly from $WILDFLY_HOME is already running; refusing to touch it"
fi
if [[ -e "$deployments/$context.war" && "${SMOKE_FORCE:-}" != "1" ]]; then
    fail "$deployments/$context.war already exists; set SMOKE_FORCE=1 to replace it"
fi

log="$(mktemp -t wildfly-smoke.XXXXXX)"
jar=""
server=""

cleanup() {
    set +e
    if [[ -n "$server" ]]; then
        # standalone.sh starts the JVM as a child process: stop that too, or it keeps the ports.
        pkill -P "$server" 2>/dev/null
        kill "$server" 2>/dev/null
        for _ in $(seq 30); do
            pgrep -f -- "$WILDFLY_HOME/jboss-modules.jar" >/dev/null || break
            sleep 1
        done
        wait "$server" 2>/dev/null
    fi
    rm -f "$deployments/$context.war"*
    [[ -n "$jar" ]] && rm -f "$jar"
    echo "server log: $log"
}
trap cleanup EXIT

rm -f "$deployments/$context.war"*
cp "$war" "$deployments/$context.war"
"$WILDFLY_HOME/bin/standalone.sh" >"$log" 2>&1 &
server=$!

for _ in $(seq "$timeout"); do
    [[ -e "$deployments/$context.war.deployed" ]] && break
    if [[ -e "$deployments/$context.war.failed" ]]; then
        echo "Deployment failed" >&2
        grep -E "ERROR|WFLY" "$log" | tail -n 60 >&2
        exit 1
    fi
    kill -0 "$server" 2>/dev/null || { echo "WildFly exited before the deployment finished" >&2; tail -n 60 "$log" >&2; exit 1; }
    sleep 1
done
[[ -e "$deployments/$context.war.deployed" ]] || fail "Not deployed after ${timeout}s"

csrf="$(curl -fsS "$base/api/v1/auth/csrf")" || fail "csrf endpoint did not answer"
grep -q '"headerName"' <<<"$csrf" || fail "csrf endpoint answered without headerName"
echo "OK  $base/api/v1/auth/csrf"

listing="$(unzip -l "$war")"
if grep -q 'WEB-INF/classes/static/index.html' <<<"$listing"; then
    index="$(curl -fsS "$base/")" || fail "index.html not served"
    grep -q "<base href=\"/$context/\"" <<<"$index" || fail "index.html without the context base"
    asset="$(grep -oE 'assets/[^"]+\.js' <<<"$index" | head -n 1)"
    [[ -n "$asset" ]] || fail "index.html references no script asset"
    curl -fsS -o /dev/null "$base/$asset" || fail "asset $asset not served"
    deep="$(curl -fsS -D - "$base/repositories/1/graph")" || fail "deep link not served"
    grep -q "<base href=\"/$context/\"" <<<"$deep" || fail "deep link did not serve the app"
    grep -qi '^Content-Security-Policy:' <<<"$deep" || fail "deep link without the Content-Security-Policy header"
    echo "OK  $base/ (index, asset, deep link)"
fi

if [[ -n "${SMOKE_USER:-}" && -n "${SMOKE_PASSWORD:-}" ]]; then
    jar="$(mktemp -t wildfly-cookies.XXXXXX)"
    session="$(curl -fsS -c "$jar" -b "$jar" "$base/api/v1/auth/csrf")" || fail "csrf endpoint did not answer"
    token="$(sed -nE 's/.*"token":"([^"]+)".*/\1/p' <<<"$session")"
    [[ -n "$token" ]] || fail "no CSRF token in the csrf response"
    if command -v jq >/dev/null; then
        payload="$(jq -n --arg u "$SMOKE_USER" --arg p "$SMOKE_PASSWORD" '{username:$u,password:$p}')"
    else
        esc() { local v="${1//\\/\\\\}"; printf '%s' "${v//\"/\\\"}"; }
        payload="{\"username\":\"$(esc "$SMOKE_USER")\",\"password\":\"$(esc "$SMOKE_PASSWORD")\"}"
    fi
    # The body goes through stdin so the password never appears on a command line.
    printf '%s' "$payload" | curl -fsS -c "$jar" -b "$jar" -H "X-XSRF-TOKEN: $token" -H 'Content-Type: application/json' \
        --data-binary @- "$base/api/v1/auth/login" >/dev/null || fail "login failed"
    echo "OK  login as $SMOKE_USER"
fi
echo "WildFly smoke test passed"
