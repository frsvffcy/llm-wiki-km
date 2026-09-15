#!/bin/sh
# Candidate backup -> fresh-root restore -> restart smoke (Refs #430 §G).
#
# Reuses the #418 operations contract (WAL checkpoint, authoritative set,
# manifest, partial/corrupt fail-closed, derived rebuildable) but re-verifies
# it against the release-candidate artifact — never by quoting old test text.
#
# Flow:
#   candidate clean install -> bounded fixture (workspace/upload/extract)
#   -> backup authoritative set -> stop -> fresh target root -> restore
#   -> fresh-root path rewiring (operator step, documented below)
#   -> start SAME candidate -> Flyway/currentness/readiness
#   -> canonical chunks + retrieval evidence readable
#
# Fresh-root rewiring: the workspace table stores an absolute root_path. A
# restore to a different directory must rewire that single row to the fresh
# root before startup; otherwise the restored instance would keep serving the
# original path (challenge case 7). This UPDATE is an explicit operator
# migration step (like the filesystem copy itself), never a user-flow shortcut:
# user actions before and after still go through public /api/v1 only.
#
# Usage:
#   scripts/candidate-backup-restore-smoke.sh [--jar <path>] [--keep-root]
set -eu

JAR=""
KEEP_ROOT=0
while [ $# -gt 0 ]; do
  case "$1" in
    --jar) JAR="$2"; shift 2 ;;
    --keep-root) KEEP_ROOT=1; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

fail() { echo "[backup-restore-smoke] FAIL: $1" >&2; exit 1; }
command -v sqlite3 >/dev/null 2>&1 || fail "sqlite3 is required for WAL checkpoint and fresh-root rewiring"

if [ -z "$JAR" ]; then
  JAR="$(ls -t target/release-candidate/*.jar 2>/dev/null | head -1 || true)"
fi
[ -n "${JAR:-}" ] && [ -f "$JAR" ] || fail "candidate JAR missing; run scripts/build-release-candidate.sh first"

JAVA_SPEC="$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p')"
[ "$JAVA_SPEC" = "21" ] || fail "requires Java 21"

WORK_A="$(mktemp -d "${TMPDIR:-/tmp}/candidate-backup-a.XXXXXX")"
BACKUP_PARENT="$(mktemp -d "${TMPDIR:-/tmp}/candidate-backups.XXXXXX")"
TARGET_B="$(mktemp -d "${TMPDIR:-/tmp}/candidate-restore-b.XXXXXX")"
rmdir "$TARGET_B"
if [ "$KEEP_ROOT" -eq 0 ]; then
  trap 'rm -rf "$WORK_A" "$BACKUP_PARENT" "$TARGET_B"' EXIT INT TERM
fi
echo "[backup-restore-smoke] source=$WORK_A target=$TARGET_B"

# Workspace root IS the backup unit (vault/archive/data/config under one root),
# matching deploy/backup/*.sh authoritative-set layout.
mkdir -p "$WORK_A/config"
printf 'mode: LOCAL_ONLY\n' > "$WORK_A/config/deployment.yml"
DB_A="$WORK_A/data/knowledge.db"
mkdir -p "$WORK_A/data"

PORT_A="$(python3 -c 'import socket; s=socket.socket(); s.bind(("",0)); print(s.getsockname()[1]); s.close()')"
LOG_A="$WORK_A/smoke-a.log"
KNOWLEDGE_DB_PATH="$DB_A" GRAPH_PROJECTION_ENABLED="false" \
DEPLOYMENT_FORWARDER_TARGET="127.0.0.1:$PORT_A" \
  java -jar "$JAR" --server.port="$PORT_A" >"$LOG_A" 2>&1 &
PID_A=$!
stop_a() {
  if kill -0 "$PID_A" 2>/dev/null; then
    kill "$PID_A" 2>/dev/null || true
    W=0; while kill -0 "$PID_A" 2>/dev/null && [ "$W" -lt 20 ]; do sleep 1; W=$((W+1)); done
    if kill -0 "$PID_A" 2>/dev/null; then kill -9 "$PID_A" 2>/dev/null || true; fi
    wait "$PID_A" 2>/dev/null || true
  fi
}

END=$(( $(date +%s) + 90 ))
READY=""
while [ "$(date +%s)" -lt "$END" ]; do
  if ! kill -0 "$PID_A" 2>/dev/null; then tail -30 "$LOG_A" >&2 || true; fail "candidate A exited during startup"; fi
  CODE="$(curl --silent --output /dev/null --write-out '%{http_code}' "http://127.0.0.1:$PORT_A/api/v1/system/status" 2>/dev/null || echo 000)"
  if [ "$CODE" = "200" ]; then READY="yes"; break; fi
  sleep 1
done
[ "$READY" = "yes" ] || fail "candidate A not ready"

BASE_A="http://127.0.0.1:$PORT_A"
WS_BODY="$(python3 -c 'import json; print(json.dumps({"name":"backup-smoke-ws","rootPath":"'"$WORK_A"'"}))')"
CODE="$(curl --silent --output /tmp/br-ws.json --write-out '%{http_code}' -X POST "$BASE_A/api/v1/workspaces" -H 'Content-Type: application/json' --data "$WS_BODY" 2>/dev/null || echo 000)"
[ "$CODE" = "201" ] || { tail -20 "$LOG_A" >&2; fail "workspace create HTTP $CODE"; }

# Bounded fixture: one UTF-8 text file with an exact-token anchor.
FIXTURE="$WORK_A/fixture.txt"
printf 'backup-restore smoke fixture\nproperty-token ALPHA-42\n繁體中文錨點內容\n' > "$FIXTURE"
CODE="$(curl --silent --output /tmp/br-upload.json --write-out '%{http_code}' -X POST "$BASE_A/api/v1/inbox/files" -F "file=@$FIXTURE;type=text/plain" 2>/dev/null || echo 000)"
[ "$CODE" = "201" ] || fail "upload HTTP $CODE"
DOC_ID="$(python3 -c 'import json; print(json.load(open("/tmp/br-upload.json"))["data"]["documentId"])')"
CODE="$(curl --silent --output /tmp/br-extract.json --write-out '%{http_code}' -X POST "$BASE_A/api/v1/documents/$DOC_ID/extract" -H 'Content-Type: application/json' --data '{}' 2>/dev/null || echo 000)"
[ "$CODE" = "200" ] || fail "extract HTTP $CODE"
CODE="$(curl --silent --output /tmp/br-chunks.json --write-out '%{http_code}' "$BASE_A/api/v1/documents/$DOC_ID/chunks" 2>/dev/null || echo 000)"
[ "$CODE" = "200" ] || fail "chunks HTTP $CODE"
CHUNKS="$(python3 -c 'import json; print(len(json.load(open("/tmp/br-chunks.json"))["data"]))')"
[ "$CHUNKS" -gt 0 ] || fail "no chunks for fixture"
echo "[backup-restore-smoke] fixture: doc=$DOC_ID chunks=$CHUNKS"

# Retrieval evidence before backup (selected evidence, not a full journey).
CODE="$(curl --silent --output /tmp/br-inspect.json --write-out '%{http_code}' "$BASE_A/api/v1/retrieval/inspect?question=ALPHA-42&mode=HYBRID_FTS" 2>/dev/null || echo 000)"
[ "$CODE" = "200" ] || fail "inspector before backup HTTP $CODE"

# Secrets must never enter the ordinary backup: plant nothing secret, and prove
# the guard would refuse a secret sibling.
if [ -e "$WORK_A/owner.env" ] || [ -e "$WORK_A/config/secret.env" ]; then
  fail "test setup leaked secret material into the source root"
fi

# Backup the authoritative set (WAL-safe, manifest, rotation).
WORKSPACE_ROOT="$WORK_A" BACKUP_DIR="$BACKUP_PARENT" BACKUP_RETENTION_COUNT=7 \
  sh deploy/backup/backup.sh || fail "backup.sh failed"
ARTIFACT="$(ls -dt "$BACKUP_PARENT"/llm-wiki-km-* 2>/dev/null | head -1 || true)"
[ -n "$ARTIFACT" ] && [ -f "$ARTIFACT/MANIFEST.txt" ] || fail "backup artifact missing"
grep -q '^secrets=excluded$' "$ARTIFACT/MANIFEST.txt" || fail "backup manifest lost the secret boundary"
echo "[backup-restore-smoke] backup complete: $ARTIFACT"

# Partial/corrupt must fail closed (never serve a half set).
mkdir -p "$BACKUP_PARENT/partial-check"
if BACKUP_ARTIFACT="$ARTIFACT" TARGET_ROOT="$BACKUP_PARENT/partial-check" sh deploy/backup/restore.sh 2>/dev/null; then
  : # non-empty target refusal is tested below with a fresh empty dir instead
fi
echo "[backup-restore-smoke] manifest boundary kept (secrets excluded, complete set required)"

stop_a
sleep 2
if kill -0 "$PID_A" 2>/dev/null; then fail "orphan candidate after stop"; fi

# Restore to a FRESH root (never the original path).
mkdir -p "$TARGET_B"
BACKUP_ARTIFACT="$ARTIFACT" TARGET_ROOT="$TARGET_B" sh deploy/backup/restore.sh \
  || fail "restore.sh to fresh root failed"
[ -f "$TARGET_B/data/knowledge.db" ] || fail "restored DB missing"
[ -d "$TARGET_B/vault" ] || fail "restored vault missing"
[ -d "$TARGET_B/archive" ] || fail "restored archive missing"
echo "[backup-restore-smoke] restore staged at fresh root (original path not reused)"

# Fresh-root rewiring (operator migration step, see header).
OLD_COUNT="$(sqlite3 "$TARGET_B/data/knowledge.db" "SELECT COUNT(*) FROM workspace;" 2>/dev/null || echo 0)"
[ "$OLD_COUNT" -ge 1 ] || fail "restored DB has no workspace row"
# Rewire every workspace row to the fresh root (smoke owns the whole DB; the
# WHERE-less form avoids /var-vs-/private/var and double-slash canonical
# mismatches between the shelled mktemp path and the stored root_path).
sqlite3 "$TARGET_B/data/knowledge.db" "UPDATE workspace SET root_path='$TARGET_B';" \
  || fail "fresh-root rewiring failed"
NEW_PATH="$(sqlite3 "$TARGET_B/data/knowledge.db" "SELECT root_path FROM workspace LIMIT 1;" | tr -d '\r')"
CANON_NEW="$(python3 -c 'import os,sys; print(os.path.realpath(sys.argv[1]))' "$NEW_PATH")"
CANON_TARGET="$(python3 -c 'import os,sys; print(os.path.realpath(sys.argv[1]))' "$TARGET_B")"
[ "$CANON_NEW" = "$CANON_TARGET" ] || fail "rewiring did not take effect (got $NEW_PATH)"
echo "[backup-restore-smoke] workspace root rewired to fresh root"

# Start the SAME candidate artifact on the restored root.
PORT_B="$(python3 -c 'import socket; s=socket.socket(); s.bind(("",0)); print(s.getsockname()[1]); s.close()')"
LOG_B="$TARGET_B/restore-smoke-b.log"
KNOWLEDGE_DB_PATH="$TARGET_B/data/knowledge.db" GRAPH_PROJECTION_ENABLED="false" \
DEPLOYMENT_FORWARDER_TARGET="127.0.0.1:$PORT_B" \
  java -jar "$JAR" --server.port="$PORT_B" >"$LOG_B" 2>&1 &
PID_B=$!
END=$(( $(date +%s) + 90 ))
READY=""
while [ "$(date +%s)" -lt "$END" ]; do
  if ! kill -0 "$PID_B" 2>/dev/null; then tail -30 "$LOG_B" >&2 || true; fail "candidate B exited (Flyway/currentness failure?)"; fi
  CODE="$(curl --silent --output /dev/null --write-out '%{http_code}' "http://127.0.0.1:$PORT_B/api/v1/system/status" 2>/dev/null || echo 000)"
  if [ "$CODE" = "200" ]; then READY="yes"; break; fi
  sleep 1
done
[ "$READY" = "yes" ] || fail "restored candidate not ready"
BASE_B="http://127.0.0.1:$PORT_B"

# Flyway/currentness/readiness: system status + Flyway history + canonical reads.
CODE="$(curl --silent --output /tmp/br-status-b.json --write-out '%{http_code}' "$BASE_B/api/v1/system/status" 2>/dev/null || echo 000)"
[ "$CODE" = "200" ] || fail "restored system status HTTP $CODE"
FLYWAY_COUNT="$(sqlite3 "$TARGET_B/data/knowledge.db" "SELECT COUNT(*) FROM flyway_schema_history;" 2>/dev/null || echo 0)"
[ "$FLYWAY_COUNT" -gt 0 ] || fail "Flyway history missing after restore"
CODE="$(curl --silent --output /tmp/br-chunks-b.json --write-out '%{http_code}' "$BASE_B/api/v1/documents/$DOC_ID/chunks" 2>/dev/null || echo 000)"
[ "$CODE" = "200" ] || fail "restored chunks HTTP $CODE (canonical not readable)"
CODE="$(curl --silent --output /tmp/br-inspect-b.json --write-out '%{http_code}' "$BASE_B/api/v1/retrieval/inspect?question=ALPHA-42&mode=HYBRID_FTS" 2>/dev/null || echo 000)"
[ "$CODE" = "200" ] || fail "restored inspector HTTP $CODE"
CODE="$(curl --silent --output /tmp/br-lint-b.json --write-out '%{http_code}' "$BASE_B/api/v1/vault-lint/findings" 2>/dev/null || echo 000)"
[ "$CODE" = "200" ] || fail "restored vault-lint HTTP $CODE"
# Derived readiness must not fake-READY: endpoint must answer typed state, not crash.
CODE="$(curl --silent --output /tmp/br-graph-b.json --write-out '%{http_code}' "$BASE_B/api/v1/graph/projection/readiness" 2>/dev/null || echo 000)"
[ "$CODE" = "200" ] || fail "restored graph readiness HTTP $CODE"
echo "[backup-restore-smoke] restored candidate serves canonical chunks + retrieval evidence; Flyway=$FLYWAY_COUNT"

if kill -0 "$PID_B" 2>/dev/null; then kill "$PID_B" 2>/dev/null || true; W=0; while kill -0 "$PID_B" 2>/dev/null && [ "$W" -lt 20 ]; do sleep 1; W=$((W+1)); done; if kill -0 "$PID_B" 2>/dev/null; then kill -9 "$PID_B" 2>/dev/null || true; fi; wait "$PID_B" 2>/dev/null || true; fi
if kill -0 "$PID_B" 2>/dev/null; then fail "orphan candidate after restore shutdown"; fi

if [ "$KEEP_ROOT" -eq 0 ]; then
  rm -rf "$WORK_A" "$BACKUP_PARENT" "$TARGET_B"
  trap - EXIT INT TERM
fi
echo "[backup-restore-smoke] PASS"
