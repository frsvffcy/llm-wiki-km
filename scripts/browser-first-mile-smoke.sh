#!/bin/sh
# Browser first-mile packaged-artifact gate (Refs #454 §C).
#
# Verifies the exact candidate JAR's embedded static resources contain the
# #450 hidden-visibility fix and the #451 lifecycle/parseStatus dual-state
# contract — i.e. the Browser smoke runs against fixed bits, not a stale JAR.
#
# Checks (all fail-closed, no DOM/DB forgery):
#   #450: BOOT-INF/classes/static/styles.css contains
#         [hidden]{display:none!important} AND .empty-state{display:grid}
#   #451: BOOT-INF/classes/static/inbox-ui.js contains dual badges
#         (data-status + data-parse-status, 文件狀態 + 抽取狀態,
#          LIFECYCLE_FILTER_STATUSES + PARSE_STATUSES, 重新抽取/執行抽取)
#   shell: BOOT-INF/classes/static/index.html contains at least 2 hidden
#         .empty-state panels (inbox + wiki minimum)
#
# Usage:
#   scripts/browser-first-mile-smoke.sh [--jar <path>]
#
# Default JAR is the newest target/release-candidate/*.jar (Maven version truth;
# no second hardcoded version). Exits 0 on PASS, 1 on FAIL.
#
# Path handling (Refs #456 R3): the input is canonicalized to an absolute path
# BEFORE any directory change, so relative and absolute --jar inputs resolve
# identically and never depend on $OLDPWD or the caller's later cwd.
set -eu

. "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/release-identity.sh"

JAR=""
while [ $# -gt 0 ]; do
  case "$1" in
    --jar) JAR="$2"; shift 2 ;;
    *) echo "[browser-smoke] unknown argument: $1" >&2; exit 2 ;;
  esac
done

fail() { echo "[browser-smoke] FAIL: $1" >&2; exit 1; }
pass() { echo "[browser-smoke] PASS: $1"; }

if [ -z "$JAR" ]; then
  JAR="$(ls -t target/release-candidate/*.jar 2>/dev/null | head -1 || true)"
fi
[ -n "${JAR:-}" ] && [ -f "$JAR" ] || fail "candidate JAR missing; run scripts/build-release-candidate.sh first"
# Canonicalize before cd: absolute inputs stay absolute, relative inputs anchor
# to the caller's cwd now — $OLDPWD is never consulted (Refs #456 R3).
JAR="$(release_identity_canonicalize "$JAR")" || fail "cannot resolve candidate JAR path: ${JAR:-<empty>}"
[ -f "$JAR" ] || fail "candidate JAR missing; run scripts/build-release-candidate.sh first"
case "$JAR" in
  *target/release-candidate/*) ;;
  *) fail "must use the release-candidate JAR, not $JAR" ;;
esac
echo "[browser-smoke] candidate: $JAR"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/browser-smoke.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT INT TERM
START_DIR="$(pwd)"
cd "$WORK"
unzip -q -o "$JAR" 'BOOT-INF/classes/static/styles.css' 'BOOT-INF/classes/static/inbox-ui.js' 'BOOT-INF/classes/static/index.html' \
  || fail "candidate JAR missing Browser static resources"

CSS="BOOT-INF/classes/static/styles.css"
JS="BOOT-INF/classes/static/inbox-ui.js"
HTML="BOOT-INF/classes/static/index.html"

# --- #450: hidden authority survives .empty-state grid -----------------------
grep -Eq '\[hidden\][[:space:]]*\{[[:space:]]*display[[:space:]]*:[[:space:]]*none[[:space:]]*!important' "$CSS" \
  || fail "styles.css lacks global [hidden]{display:none!important} (#450)"
grep -Eq '\.empty-state[[:space:]]*\{[[:space:]]*display[[:space:]]*:[[:space:]]*grid' "$CSS" \
  || fail "styles.css lacks .empty-state{display:grid} layout (#450 keeps layout, fixes authority)"
pass "#450 hidden authority present in packaged styles.css"

# --- #451: dual-state projection ---------------------------------------------
grep -q 'data-status' "$JS" || fail "inbox-ui.js lacks data-status lifecycle badge (#451)"
grep -q 'data-parse-status' "$JS" || fail "inbox-ui.js lacks data-parse-status extraction badge (#451)"
grep -q '文件狀態' "$JS" || fail "inbox-ui.js lacks 文件狀態 label (#451)"
grep -q '抽取狀態' "$JS" || fail "inbox-ui.js lacks 抽取狀態 label (#451)"
grep -q 'LIFECYCLE_FILTER_STATUSES' "$JS" || fail "inbox-ui.js lacks lifecycle filter contract (#451)"
grep -q 'PARSE_STATUSES' "$JS" || fail "inbox-ui.js lacks parseStatus filter contract (#451)"
grep -q '重新抽取' "$JS" || fail "inbox-ui.js lacks 重新抽取 semantics (#451)"
grep -q '執行抽取' "$JS" || fail "inbox-ui.js lacks 執行抽取 semantics (#451)"
pass "#451 lifecycle/parseStatus dual projection present in packaged inbox-ui.js"

# --- shell: hidden empty-state panels -----------------------------------------
COUNT="$(grep -o 'empty-state' "$HTML" | wc -l | tr -d ' ')"
HIDDEN_COUNT="$(grep -c 'hidden' "$HTML" || true)"
[ "$COUNT" -ge 2 ] || fail "index.html has fewer than 2 empty-state panels (found $COUNT)"
[ "$HIDDEN_COUNT" -ge 2 ] || fail "index.html has fewer than 2 hidden markers (found $HIDDEN_COUNT)"
pass "index.html carries hidden empty-state panels (empty-state=$COUNT, hidden-lines=$HIDDEN_COUNT)"

cd "$START_DIR"
echo "[browser-smoke] PASS: exact candidate artifact carries #450 + #451 Browser fixes"
