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
# Candidate identity (Refs #456 R2-R3; #458): the exact filename is derived
# from the Maven authority (project.artifactId + project.version) and
# resolved via release_identity_resolve_candidate_jar — never by mtime
# recency. An explicit --jar must name that same file; its
# JAR-internal versions are verified and, when the release sidecar
# manifest exists, the sidecar must describe this exact JAR before the
# smoke body runs. Exits 0 on PASS, 1 on FAIL.
#
# Path handling (Refs #456 R3): the input is canonicalized to an absolute path
# BEFORE any directory change, so relative and absolute --jar inputs resolve
# identically and never depend on $OLDPWD or the caller's later cwd.
set -eu

. "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/release-identity.sh"

JAR=""
while [ $# -gt 0 ]; do
  case "$1" in
    --jar) JAR="${2:-}"; [ -n "$JAR" ] || { echo "[browser-smoke] FAIL: --jar requires a path" >&2; exit 2; }; shift 2 ;;
    *) echo "[browser-smoke] unknown argument: $1" >&2; exit 2 ;;
  esac
done

fail() { echo "[browser-smoke] FAIL: $1" >&2; exit 1; }
pass() { echo "[browser-smoke] PASS: $1"; }

# Version truth is Maven only (no second hardcoded version, no mtime pick).
ARTIFACT_ID="$(mvn --batch-mode -q help:evaluate -Dexpression=project.artifactId -DforceStdout 2>/dev/null || true)"
PROJECT_VERSION="$(mvn --batch-mode -q help:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null || true)"
[ -n "${ARTIFACT_ID:-}" ] || fail "cannot derive Maven artifactId"
[ -n "${PROJECT_VERSION:-}" ] || fail "cannot derive Maven project version"
EXPECTED_BASENAME="$(release_identity_expected_basename "$ARTIFACT_ID" "$PROJECT_VERSION")" \
  || fail "cannot derive expected candidate name"
EXPECTED_FILENAME="${EXPECTED_BASENAME}.jar"
CANDIDATE_DIR="target/release-candidate"
# Deterministic exact resolution before cd: relative --jar anchors to the
# caller's cwd now — $OLDPWD is never consulted (Refs #456 R3, #458).
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
