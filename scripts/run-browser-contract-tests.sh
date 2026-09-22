#!/bin/sh
# Browser Node contract suite runner (Refs #561).
#
# Single classification authority for every src/test/js/*.test.mjs file, so
# PR CI and Full Regression Canary share one deterministic Browser suite and
# a new test file can never be silently ungated:
#
#   required    deterministic offline Browser contracts (Node built-in runner;
#               no live server, no external SDK, no network). PR Fast job and
#               Full Regression Canary must both succeed on this set.
#   governance  PR metadata / delivery-governance ownership (PR Metadata job).
#   live        explicit non-required live/manual interop (needs a live server
#               plus a pinned SDK install; self-skips without env and must
#               never be reported as required evidence).
#   check       completeness guard: every src/test/js/*.test.mjs file must be
#               classified in exactly one list, every listed file must exist,
#               and no list may repeat a file. Fails closed otherwise.
#
# Only `node --test` with the Node built-in runner is used; no npm frontend
# framework is introduced. Must run from the repository root.
#
# Usage:
#   sh scripts/run-browser-contract-tests.sh required|governance|check
set -eu

TEST_DIR="src/test/js"

# Required deterministic offline Browser contracts (AC-01: the 7 previously
# ungated suites are listed here alongside the 9 already gated ones).
# Entries are repo-root-relative paths, the exact argv given to `node --test`.
REQUIRED="src/test/js/analysis-ui.test.mjs
src/test/js/ask-ui.test.mjs
src/test/js/browser-suite-manifest.test.mjs
src/test/js/cross-surface-states.test.mjs
src/test/js/design-system-foundation.test.mjs
src/test/js/graph-operations-ui.test.mjs
src/test/js/hidden-visibility.test.mjs
src/test/js/inbox-ui.test.mjs
src/test/js/navigation-ia.test.mjs
src/test/js/navigation-ui.test.mjs
src/test/js/organize-ui.test.mjs
src/test/js/owner-auth-ui.test.mjs
src/test/js/quality-ui.test.mjs
src/test/js/retrieval-inspector-ui.test.mjs
src/test/js/review-ui.test.mjs
src/test/js/source-chunk-inspector-ui.test.mjs
src/test/js/wiki-ui.test.mjs
src/test/js/workspace-ui.test.mjs"

# Governance ownership stays with the PR Metadata job (AC-05).
GOVERNANCE="src/test/js/language-governance.test.mjs
src/test/js/merge-commit-guard.test.mjs
src/test/js/merge-settings.test.mjs
src/test/js/pr-metadata.test.mjs
src/test/js/repo-public-hygiene.test.mjs"

# Explicit non-required live/manual interop (AC-06): classified so `check`
# stays exhaustive, never executed as required evidence.
LIVE="src/test/js/mcp-modern-interop.test.mjs
src/test/js/mcp-sdk-interop.test.mjs"

fail() { echo "[browser-contracts] FAIL: $1" >&2; exit 1; }

in_list() {
  # $1 = repo-root-relative path, $2 = list: 0 when present, 1 when absent.
  _want="$1"
  for _entry in $2; do
    [ "$_entry" = "$_want" ] && return 0
  done
  return 1
}

run_list() {
  # $1 = label, $2 = list: exactly one `node --test` invocation, no repeats.
  [ -n "${2:-}" ] || fail "empty $1 list"
  # Unquoted on purpose: entries are repo-relative paths without spaces.
  node --test $2
}

check_duplicates() {
  # $1 = label, $2 = list.
  _seen=""
  for _entry in $2; do
    case " $_seen " in
      *" $_entry "*) fail "duplicate $_entry in $1 list" ;;
    esac
    _seen="$_seen $_entry"
  done
}

classify() {
  # Echo the single owning list name for a repo-root-relative path, or fail.
  _count=0
  _owner=""
  for _pair in "required:$REQUIRED" "governance:$GOVERNANCE" "live:$LIVE"; do
    _name="${_pair%%:*}"
    _list="${_pair#*:}"
    if in_list "$1" "$_list"; then
      _count=$((_count + 1))
      _owner="$_name"
    fi
  done
  [ "$_count" -eq 1 ] || fail "unclassified or multi-listed test file: $1"
  printf '%s\n' "$_owner"
}

cmd_check() {
  [ -d "$TEST_DIR" ] || fail "must run from the repository root ($TEST_DIR missing)"
  check_duplicates "required" "$REQUIRED"
  check_duplicates "governance" "$GOVERNANCE"
  check_duplicates "live" "$LIVE"
  for _list in "$REQUIRED" "$GOVERNANCE" "$LIVE"; do
    for _entry in $_list; do
      [ -f "$_entry" ] || fail "listed test file missing: $_entry"
    done
  done
  for _path in "$TEST_DIR"/*.test.mjs; do
    [ -f "$_path" ] || fail "no Browser contract tests found in $TEST_DIR"
    _owner="$(classify "$_path")"
    echo "[browser-contracts] classified: $_path -> $_owner"
  done
  echo "[browser-contracts] PASS: every Browser contract test is classified exactly once"
}

case "${1:-}" in
  required) run_list "required" "$REQUIRED" ;;
  governance) run_list "governance" "$GOVERNANCE" ;;
  check) cmd_check ;;
  *) echo "usage: $0 required|governance|check" >&2; exit 2 ;;
esac
