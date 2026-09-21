#!/bin/sh
# Hermetic executable matrix for the browser-first-mile packaged gate (Refs #560).
#
# Proves with real executions (not string presence) that the shared
# implementation in scripts/browser-first-mile-content.sh — the same code
# sourced by scripts/browser-first-mile-smoke.sh — accepts current-like
# packaged Browser resources and rejects stale #517 / #567 / missing /
# guard-less resources fail-closed.
#
# Hermetic: temp dirs only, no Maven, no network, no JAR build, no repository
# state (aside from the read-only production-source dogfood case). Fixtures
# are generated deterministically in this file.
#
# Run: sh scripts/tests/test-browser-first-mile-smoke.sh
# Also executed from the fast tier via BrowserFirstMileSmokeShellContractTest,
# from PR CI, and from Full Regression Canary.
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
. "$SCRIPT_DIR/../browser-first-mile-content.sh"

PASS=0
FAIL=0
ok() { PASS=$((PASS + 1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL + 1)); echo "  NOT OK: $1" >&2; }

WORK="$(mktemp -d "${TMPDIR:-/tmp}/browser-smoke-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT INT TERM

# --- fixture writers ---------------------------------------------------------
# Minimal packaged-shaped resources carrying exactly the tokens the gate
# asserts. Two-space controller indentation mirrors createInboxController so
# the packaged-source block extractor behaves identically to production.

write_css() {
  cat > "$1" <<'CSS'
[hidden]{display:none!important}
.empty-state{display:grid}
CSS
}

write_html() {
  cat > "$1" <<'HTML'
<section id="view-inbox"><p id="inbox-empty" class="empty-state" hidden>empty</p></section>
<section id="view-wiki"><p id="wiki-empty" class="empty-state" hidden>empty</p></section>
HTML
}

# $1 = out path, $2 = list helper name, $3 = refresh guard line (or empty),
# $4 = mutation follow-up line, $5 = extra mutation line (or empty).
write_js() {
  _out="$1"
  _helper="$2"
  _guard="$3"
  _followup="$4"
  _extra="${5:-}"
  {
    printf '%s\n' "'use strict';"
    printf '%s\n' "// data-status data-parse-status"
    printf '%s\n' "// 文件狀態 抽取狀態"
    printf '%s\n' "const LIFECYCLE_FILTER_STATUSES = ['PENDING'];"
    printf '%s\n' "const PARSE_STATUSES = ['PROCESSED'];"
    printf '%s\n' "const labels = ['重新抽取', '執行抽取'].join('|');"
    printf '%s\n' "export function createInboxController() {"
    printf '%s\n' "  let inFlight = false;"
    printf '%s\n' "  async function ${_helper}() {"
    printf '%s\n' "    await load();"
    printf '%s\n' "  }"
    printf '%s\n' "  async function refresh() {"
    if [ -n "$_guard" ]; then
      printf '%s\n' "    $_guard"
    fi
    printf '%s\n' "    await ${_helper}();"
    printf '%s\n' "  }"
    for _m in uploadSingle uploadBatch rescan extract remove; do
      printf '%s\n' "  async function ${_m}() {"
      printf '%s\n' "    inFlight = true;"
      printf '%s\n' "    try {"
      printf '%s\n' "      await post();"
      printf '%s\n' "      ${_followup}"
      if [ -n "$_extra" ]; then
        printf '%s\n' "      ${_extra}"
      fi
      printf '%s\n' "    } finally {"
      printf '%s\n' "      inFlight = false;"
      printf '%s\n' "    }"
      printf '%s\n' "  }"
    done
    printf '%s\n' "}"
  } > "$_out"
}

make_fixture() {
  # $1 = dir, $2 = helper, $3 = guard, $4 = followup, $5 = extra followup
  mkdir -p "$1"
  write_css "$1/styles.css"
  write_html "$1/index.html"
  write_js "$1/inbox-ui.js" "$2" "$3" "$4" "${5:-}"
}

GUARD="if (inFlight) return;"
FETCH="await fetchList();"
REFRESH="await refresh();"

make_fixture "$WORK/current" "fetchList" "$GUARD" "$FETCH"
make_fixture "$WORK/stale-no-fetchlist" "fetchLegacyList" "$GUARD" "$FETCH"
make_fixture "$WORK/stale-refresh-call" "fetchList" "$GUARD" "$REFRESH"
make_fixture "$WORK/stale-both-call" "fetchList" "$GUARD" "$FETCH" "$REFRESH"
make_fixture "$WORK/no-guard" "fetchList" "" "$FETCH"
mkdir -p "$WORK/missing-css"
write_html "$WORK/missing-css/index.html"
write_js "$WORK/missing-css/inbox-ui.js" "fetchList" "$GUARD" "$FETCH"
mkdir -p "$WORK/missing-js"
write_css "$WORK/missing-js/styles.css"
write_html "$WORK/missing-js/index.html"

cp -R "$WORK/current" "$WORK/stale-manual-upload"
sed 's/autoProcess=true/autoProcess=false/' "$WORK/stale-manual-upload/inbox-ui.js" \
  > "$WORK/stale-manual-upload/inbox-ui.js.tmp"
mv "$WORK/stale-manual-upload/inbox-ui.js.tmp" "$WORK/stale-manual-upload/inbox-ui.js"

# --- assertion helpers ---------------------------------------------------------

expect_check_pass() {
  # $1 = label, $2/$3/$4 = css/js/html
  _out="$WORK/out.txt"
  if browser_content_check "$2" "$3" "$4" >"$_out" 2>&1; then
    ok "$1 passes"
  else
    bad "$1 (expected exit 0)"; cat "$_out" >&2
  fi
}

expect_check_fail() {
  # $1 = label, $2 = required message fragment, $3/$4/$5 = css/js/html
  _out="$WORK/out.txt"
  if browser_content_check "$3" "$4" "$5" >"$_out" 2>&1; then
    bad "$1 (expected fail-closed, got exit 0)"
  elif grep -Fq "$2" "$_out"; then
    ok "$1 fails closed ($2)"
  else
    bad "$1 (wrong failure message; want [$2])"; cat "$_out" >&2
  fi
}

# --- matrix --------------------------------------------------------------------

echo "[test] AC-01 current-like packaged resources PASS"
expect_check_pass "current fixture" \
  "$WORK/current/styles.css" "$WORK/current/inbox-ui.js" "$WORK/current/index.html"

echo "[test] AC-02 stale #517 fixtures FAIL with a #517 contract message"
expect_check_fail "stale helper (no fetchList)" "exactly one async function fetchList() (#517" \
  "$WORK/stale-no-fetchlist/styles.css" \
  "$WORK/stale-no-fetchlist/inbox-ui.js" \
  "$WORK/stale-no-fetchlist/index.html"
expect_check_fail "stale mutation (await refresh() only)" "via fetchList() (#517)" \
  "$WORK/stale-refresh-call/styles.css" \
  "$WORK/stale-refresh-call/inbox-ui.js" \
  "$WORK/stale-refresh-call/index.html"
expect_check_fail "stale mutation (fetchList + await refresh())" "#517 stale-state regression" \
  "$WORK/stale-both-call/styles.css" \
  "$WORK/stale-both-call/inbox-ui.js" \
  "$WORK/stale-both-call/index.html"

echo "[test] #567 regression: manual-only Browser upload FAILS"
expect_check_fail "manual-only upload" "backend automatic processing (#567)" \
  "$WORK/stale-manual-upload/styles.css" \
  "$WORK/stale-manual-upload/inbox-ui.js" \
  "$WORK/stale-manual-upload/index.html"

echo "[test] AC-03 missing static resources fail closed"
expect_check_fail "missing styles.css" "styles.css missing" \
  "$WORK/missing-css/styles.css" \
  "$WORK/missing-css/inbox-ui.js" \
  "$WORK/missing-css/index.html"
expect_check_fail "missing inbox-ui.js" "inbox-ui.js missing" \
  "$WORK/missing-js/styles.css" \
  "$WORK/missing-js/inbox-ui.js" \
  "$WORK/missing-js/index.html"

echo "[test] AC-04 refresh() without the inFlight guard fails closed"
expect_check_fail "guard-less refresh" "inFlight guard" \
  "$WORK/no-guard/styles.css" \
  "$WORK/no-guard/inbox-ui.js" \
  "$WORK/no-guard/index.html"

echo "[test] shared implementation also accepts the live production sources"
REPO_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)"
expect_check_pass "production sources" \
  "$REPO_ROOT/src/main/resources/static/styles.css" \
  "$REPO_ROOT/src/main/resources/static/inbox-ui.js" \
  "$REPO_ROOT/src/main/resources/static/index.html"

echo "[test] result: pass=$PASS fail=$FAIL"
[ "$FAIL" -eq 0 ]
