#!/bin/sh
# Clean-install smoke from the released artifact (Refs #430 §E).
#
# Proves the candidate JAR boots without the Maven reactor:
#   new temp install root + only candidate JAR
#   -> java -jar candidate.jar
#   -> clean Flyway migration
#   -> loopback readiness / system status
#   -> #429 LOCAL_ONLY baseline subset (workspace -> vault-lint read-only)
#
# Forbidden: project classpath launch, working-tree resources as runtime deps,
# developer DB/vault/archive/config, sleep-luck waits.
# Shutdown must leave no orphan process / locked DB (a second start proves it).
#
# Usage:
#   scripts/clean-install-smoke.sh [--jar <path>] [--keep-root]
#
# Candidate identity (Refs #456 R2; #458): the exact filename is derived
# from the Maven authority (project.artifactId + project.version) and
# resolved via release_identity_resolve_candidate_jar — never by mtime
# recency. An explicit --jar must name that same file; the
# JAR-internal versions are verified up front and, when the release
# sidecar manifest exists, it must describe this exact JAR before boot.
set -eu

. "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/release-identity.sh"

JAR=""
KEEP_ROOT=0
while [ $# -gt 0 ]; do
  case "$1" in
    --jar) JAR="${2:-}"; [ -n "$JAR" ] || { echo "[install-smoke] FAIL: --jar requires a path" >&2; exit 2; }; shift 2 ;;
    --keep-root) KEEP_ROOT=1; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

fail() { echo "[install-smoke] FAIL: $1" >&2; exit 1; }

# Version truth is Maven only (no second hardcoded version, no mtime pick).
ARTIFACT_ID="$(mvn --batch-mode -q help:evaluate -Dexpression=project.artifactId -DforceStdout 2>/dev/null || true)"
PROJECT_VERSION="$(mvn --batch-mode -q help:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null || true)"
[ -n "${ARTIFACT_ID:-}" ] || fail "cannot derive Maven artifactId"
[ -n "${PROJECT_VERSION:-}" ] || fail "cannot derive Maven project version"
EXPECTED_BASENAME="$(release_identity_expected_basename "$ARTIFACT_ID" "$PROJECT_VERSION")" \
  || fail "cannot derive expected candidate name"
EXPECTED_FILENAME="${EXPECTED_BASENAME}.jar"
CANDIDATE_DIR="target/release-candidate"
JAR="$(release_identity_resolve_candidate_jar "$JAR" "$EXPECTED_FILENAME" "$CANDIDATE_DIR")" \
  || fail "exact candidate ${EXPECTED_FILENAME} unusable; run scripts/build-release-candidate.sh first"
case "$JAR" in
  *target/release-candidate/*) ;;
  *) fail "must use the release-candidate JAR, not $JAR" ;;
esac
release_identity_verify_jar_internal_version "$JAR" "$PROJECT_VERSION" \
  || fail "candidate JAR identity does not match Maven $PROJECT_VERSION"
release_identity_verify_candidate_sidecar_if_present "$CANDIDATE_DIR" \
  "$EXPECTED_BASENAME" "$EXPECTED_FILENAME" "$PROJECT_VERSION" "$JAR" \
  || fail "sidecar manifest does not describe this exact candidate"
echo "[install-smoke] candidate: $JAR"

JAVA_SPEC="$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p')"
[ "$JAVA_SPEC" = "21" ] || fail "requires Java 21; found ${JAVA_SPEC:-unknown}"

INSTALL_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/candidate-install.XXXXXX")"
if [ "$KEEP_ROOT" -eq 0 ]; then
  trap 'rm -rf "$INSTALL_ROOT"' EXIT INT TERM
fi
echo "[install-smoke] install root: $INSTALL_ROOT"

DB_PATH="$INSTALL_ROOT/data/knowledge.db"
GRAPH_PATH="$INSTALL_ROOT/graph"
WORKSPACE_ROOT="$INSTALL_ROOT/ws"
mkdir -p "$INSTALL_ROOT/data" "$GRAPH_PATH" "$WORKSPACE_ROOT"

# Documented contract only: fresh files, never developer data/.
[ -e "$DB_PATH" ] && fail "install root is not fresh"
PORT="$(python3 -c 'import socket; s=socket.socket(); s.bind(("",0)); print(s.getsockname()[1]); s.close()')"
LOG="$INSTALL_ROOT/install-smoke.log"

echo "[install-smoke] starting candidate JAR on 127.0.0.1:$PORT ..."
KNOWLEDGE_DB_PATH="$DB_PATH" \
GRAPH_PROJECTION_ENABLED="false" \
DEPLOYMENT_FORWARDER_TARGET="127.0.0.1:$PORT" \
  java -jar "$JAR" --server.port="$PORT" >"$LOG" 2>&1 &
PID=$!
cleanup() {
  if kill -0 "$PID" 2>/dev/null; then
    kill "$PID" 2>/dev/null || true
    WAIT=0
    while kill -0 "$PID" 2>/dev/null && [ "$WAIT" -lt 20 ]; do
      sleep 1
      WAIT=$((WAIT + 1))
    done
    if kill -0 "$PID" 2>/dev/null; then
      kill -9 "$PID" 2>/dev/null || true
    fi
    wait "$PID" 2>/dev/null || true
  fi
}
trap 'cleanup; [ "$KEEP_ROOT" -eq 0 ] && rm -rf "$INSTALL_ROOT"' EXIT INT TERM

# Bounded readiness poll (no sleep-luck): Flyway must have migrated and the
# loopback listener must serve system status.
echo "[install-smoke] waiting for loopback readiness (deadline 90s)..."
READY=""
END=$(( $(date +%s) + 90 ))
while [ "$(date +%s)" -lt "$END" ]; do
  if ! kill -0 "$PID" 2>/dev/null; then
    echo "--- candidate log ---" >&2
    tail -50 "$LOG" >&2 || true
    fail "candidate exited during startup"
  fi
  CODE="$(curl --silent --output /tmp/install-smoke-status.json --write-out '%{http_code}' \
    "http://127.0.0.1:$PORT/api/v1/system/status" 2>/dev/null || echo 000)"
  if [ "$CODE" = "200" ]; then
    READY="yes"
    break
  fi
  sleep 1
done
[ "$READY" = "yes" ] || fail "candidate not ready within deadline (see $LOG)"

STATUS="$(python3 -c 'import json; print(json.load(open("/tmp/install-smoke-status.json"))["data"]["status"])')"
[ "$STATUS" = "NOT_INITIALIZED" ] || fail "clean install must start NOT_INITIALIZED, got $STATUS"
echo "[install-smoke] clean Flyway startup: system status NOT_INITIALIZED"

# #429 LOCAL_ONLY baseline subset over public /api/v1 only.
WS_BODY="$(python3 -c 'import json; print(json.dumps({"name":"install-smoke-ws","rootPath":"'"$WORKSPACE_ROOT"'"}))')"
CODE="$(curl --silent --output /tmp/install-smoke-ws.json --write-out '%{http_code}' \
  -X POST "http://127.0.0.1:$PORT/api/v1/workspaces" \
  -H 'Content-Type: application/json' --data "$WS_BODY" 2>/dev/null || echo 000)"
[ "$CODE" = "201" ] || fail "workspace create HTTP $CODE"
ACTIVE_ROOT="$(python3 -c 'import json,urllib.request; print(json.load(urllib.request.urlopen("http://127.0.0.1:'"$PORT"'/api/v1/workspaces/current"))["data"]["workspace"]["rootPath"])')"
# macOS /var is a symlink to /private/var: compare canonical paths, not raw strings.
CANON_ACTIVE="$(python3 -c 'import os,sys; print(os.path.realpath(sys.argv[1]))' "$ACTIVE_ROOT")"
CANON_EXPECTED="$(python3 -c 'import os,sys; print(os.path.realpath(sys.argv[1]))' "$WORKSPACE_ROOT")"
[ "$CANON_ACTIVE" = "$CANON_EXPECTED" ] || fail "active root is not the temp install root"

CODE="$(curl --silent --output /tmp/install-smoke-status2.json --write-out '%{http_code}' \
  "http://127.0.0.1:$PORT/api/v1/system/status" 2>/dev/null || echo 000)"
[ "$CODE" = "200" ] || fail "system status after workspace HTTP $CODE"
STATUS2="$(python3 -c 'import json; print(json.load(open("/tmp/install-smoke-status2.json"))["data"]["status"])')"
[ "$STATUS2" = "READY" ] || fail "expected READY after workspace create, got $STATUS2"

CODE="$(curl --silent --output /tmp/install-smoke-deploy.json --write-out '%{http_code}' \
  "http://127.0.0.1:$PORT/api/v1/system/deployment" 2>/dev/null || echo 000)"
[ "$CODE" = "200" ] || fail "deployment readiness HTTP $CODE"

CODE="$(curl --silent --output /tmp/install-smoke-lint.json --write-out '%{http_code}' \
  "http://127.0.0.1:$PORT/api/v1/vault-lint/findings" 2>/dev/null || echo 000)"
[ "$CODE" = "200" ] || fail "vault-lint findings HTTP $CODE"
if grep -Eq '/Users/|/home/|Exception' /tmp/install-smoke-lint.json; then
  fail "vault-lint leaks path or exception material"
fi
echo "[install-smoke] baseline subset PASS: workspace -> READY -> deployment -> vault-lint read-only"

# Refs #456 R1 (packaged proof): the booted runtime version must equal the
# Maven project version and both JAR-internal identities. A stale candidate
# that reports an old version fails closed here, not at publish time.
RUNTIME_VERSION="$(python3 -c 'import json; print(json.load(open("/tmp/install-smoke-status2.json"))["data"]["version"])')"
JAR_MANIFEST_VERSION="$(release_identity_jar_manifest_version "$JAR")" \
  || fail "cannot read manifest Implementation-Version from $JAR"
JAR_APP_VERSION="$(release_identity_jar_app_version "$JAR")" \
  || fail "cannot read bundled app.version from $JAR"
EXPECTED_VERSION="$(mvn --batch-mode -q help:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null || true)"
[ -n "${EXPECTED_VERSION:-}" ] || fail "cannot derive Maven project version"
[ -n "${RUNTIME_VERSION:-}" ] || fail "runtime status carries no version"
[ "$RUNTIME_VERSION" = "$EXPECTED_VERSION" ] \
  || fail "runtime version $RUNTIME_VERSION != Maven $EXPECTED_VERSION (stale candidate?)"
[ "$JAR_MANIFEST_VERSION" = "$EXPECTED_VERSION" ] \
  || fail "JAR manifest $JAR_MANIFEST_VERSION != Maven $EXPECTED_VERSION"
[ "$JAR_APP_VERSION" = "$EXPECTED_VERSION" ] \
  || fail "JAR app.version $JAR_APP_VERSION != Maven $EXPECTED_VERSION"
echo "[install-smoke] version identity PASS: runtime == manifest == app.version == Maven ($EXPECTED_VERSION)"

# Graceful shutdown must release the DB/Graph lock: stopping here and starting
# again on the same root proves no orphan process / locked resource.
cleanup
trap - EXIT INT TERM
sleep 2
if kill -0 "$PID" 2>/dev/null; then
  fail "orphan candidate process after shutdown"
fi
echo "[install-smoke] shutdown clean: no orphan process"

PORT2="$(python3 -c 'import socket; s=socket.socket(); s.bind(("",0)); print(s.getsockname()[1]); s.close()')"
LOG2="$INSTALL_ROOT/install-smoke-restart.log"
KNOWLEDGE_DB_PATH="$DB_PATH" \
GRAPH_PROJECTION_ENABLED="false" \
DEPLOYMENT_FORWARDER_TARGET="127.0.0.1:$PORT2" \
  java -jar "$JAR" --server.port="$PORT2" >"$LOG2" 2>&1 &
PID2=$!
END=$(( $(date +%s) + 90 ))
READY2=""
while [ "$(date +%s)" -lt "$END" ]; do
  if ! kill -0 "$PID2" 2>/dev/null; then
    tail -50 "$LOG2" >&2 || true
    fail "candidate restart exited (locked DB?)"
  fi
  CODE="$(curl --silent --output /dev/null --write-out '%{http_code}' \
    "http://127.0.0.1:$PORT2/api/v1/system/status" 2>/dev/null || echo 000)"
  if [ "$CODE" = "200" ]; then
    READY2="yes"
    break
  fi
  sleep 1
done
[ "$READY2" = "yes" ] || fail "candidate restart not ready (locked resource?)"
kill "$PID2" 2>/dev/null || true
WAIT=0
while kill -0 "$PID2" 2>/dev/null && [ "$WAIT" -lt 20 ]; do sleep 1; WAIT=$((WAIT+1)); done
if kill -0 "$PID2" 2>/dev/null; then kill -9 "$PID2" 2>/dev/null || true; fi
wait "$PID2" 2>/dev/null || true
echo "[install-smoke] restart PASS: same DB reopened, no locked resource"

if [ "$KEEP_ROOT" -eq 0 ]; then
  rm -rf "$INSTALL_ROOT"
  trap - EXIT INT TERM
fi
echo "[install-smoke] PASS"
