#!/bin/sh
# Browser first-mile packaged-content assertions (Refs #560).
#
# Single implementation of the #450 / #451 / #517 / #567 / shell checks shared by
#   - scripts/browser-first-mile-smoke.sh (production packaged gate), and
#   - scripts/tests/test-browser-first-mile-smoke.sh (hermetic fixture matrix).
#
# This file is a library: it sets no options, changes no directory, and
# performs no network, Maven, or JAR side effects. Callers pass already
# extracted static resource paths (styles.css, inbox-ui.js, index.html).
# Every check prints its result ([browser-smoke] PASS/FAIL) and the entry
# point returns 0 on match and 1 on any missing / malformed / stale input
# (never guesses). Callers run under `set -eu` and fail closed on nonzero.
#
# Usage from a script in scripts/:
#   . "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/browser-first-mile-content.sh"
#   browser_content_check "$CSS" "$JS" "$HTML" || exit 1

browser_content_fail() { echo "[browser-smoke] FAIL: $1" >&2; return 1; }
browser_content_pass() { echo "[browser-smoke] PASS: $1"; }

browser_content_async_block() {
  # Extract one controller-level async function up to (but excluding) the
  # next controller-level async function. This is a packaged-source gate,
  # not a JavaScript parser; it deliberately keys on the repository's
  # stable two-space indentation used by createInboxController.
  # Args: jsFile functionName
  awk -v fn="$2" '
    $0 ~ "^  async function " fn "\\(" { capture = 1 }
    capture && $0 ~ "^  async function " && $0 !~ "^  async function " fn "\\(" { exit }
    capture { print }
  ' "$1"
}

# Entry point: verify extracted Browser static resources carry the
# #450 hidden-visibility fix, the #451 lifecycle/parseStatus filter separation,
# the #517 mutation follow-up fetch fix, and the #567 backend-owned usability /
# auto-processing contract, plus the hidden
# empty-state shell panels. Args: cssFile jsFile htmlFile.
browser_content_check() {
  _css="$1"
  _js="$2"
  _html="$3"
  [ -n "${_css:-}" ] && [ -f "$_css" ] \
    || { browser_content_fail "candidate static styles.css missing"; return 1; }
  [ -n "${_js:-}" ] && [ -f "$_js" ] \
    || { browser_content_fail "candidate static inbox-ui.js missing"; return 1; }
  [ -n "${_html:-}" ] && [ -f "$_html" ] \
    || { browser_content_fail "candidate static index.html missing"; return 1; }

  # --- #450: hidden authority survives .empty-state grid ---------------------
  grep -Eq '\[hidden\][[:space:]]*\{[[:space:]]*display[[:space:]]*:[[:space:]]*none[[:space:]]*!important' "$_css" \
    || { browser_content_fail "styles.css lacks global [hidden]{display:none!important} (#450)"; return 1; }
  grep -Eq '\.empty-state[[:space:]]*\{[[:space:]]*display[[:space:]]*:[[:space:]]*grid' "$_css" \
    || { browser_content_fail "styles.css lacks .empty-state{display:grid} layout (#450 keeps layout, fixes authority)"; return 1; }
  browser_content_pass "#450 hidden authority present in packaged styles.css"

  # --- #451: lifecycle / extraction filter separation ------------------------
  grep -q 'LIFECYCLE_FILTER_STATUSES' "$_js" \
    || { browser_content_fail "inbox-ui.js lacks lifecycle filter contract (#451)"; return 1; }
  grep -q 'PARSE_STATUSES' "$_js" \
    || { browser_content_fail "inbox-ui.js lacks parseStatus filter contract (#451)"; return 1; }
  grep -q 'params.set("status"' "$_js" \
    || { browser_content_fail "inbox-ui.js no longer maps lifecycle status to status= (#451)"; return 1; }
  grep -q 'params.set("parseStatus"' "$_js" \
    || { browser_content_fail "inbox-ui.js no longer maps extraction status to parseStatus= (#451)"; return 1; }
  browser_content_pass "#451 lifecycle/parseStatus filter separation present in packaged inbox-ui.js"

  # --- #567: backend-owned usability + bounded automatic processing -----------
  grep -q 'data-usability-status' "$_js" \
    || { browser_content_fail "inbox-ui.js lacks backend usability projection (#567)"; return 1; }
  grep -q 'READY_TO_USE' "$_js" \
    || { browser_content_fail "inbox-ui.js lacks READY_TO_USE semantics (#567)"; return 1; }
  grep -q 'START_USING' "$_js" \
    || { browser_content_fail "inbox-ui.js lacks START_USING next action (#567)"; return 1; }
  grep -q 'autoProcess=true' "$_js" \
    || { browser_content_fail "inbox-ui.js no longer opts into backend automatic processing (#567)"; return 1; }
  grep -q 'scheduleProcessingRefresh' "$_js" \
    || { browser_content_fail "inbox-ui.js lacks authoritative PROCESSING refresh (#567)"; return 1; }
  grep -q '開始提問' "$_js" \
    || { browser_content_fail "inbox-ui.js lacks READY_TO_USE task handoff (#567)"; return 1; }
  browser_content_pass "#567 usability/readiness and automatic-processing contract present in packaged inbox-ui.js"

  # --- #517: mutation follow-up fetch bypasses the held inFlight guard --------
  _fetch_list_count="$(grep -Ec '^[[:space:]]{2}async function fetchList\(\)' "$_js" || true)"
  [ "$_fetch_list_count" -eq 1 ] \
    || { browser_content_fail "inbox-ui.js must contain exactly one async function fetchList() (#517; found $_fetch_list_count)"; return 1; }

  _refresh_block="$(browser_content_async_block "$_js" refresh)"
  [ -n "$_refresh_block" ] \
    || { browser_content_fail "inbox-ui.js lacks refresh() controller function (#517)"; return 1; }
  printf '%s\n' "$_refresh_block" | grep -Fq 'if (inFlight) return;' \
    || { browser_content_fail "refresh() lost the inFlight guard (#517)"; return 1; }
  printf '%s\n' "$_refresh_block" | grep -Fq 'await fetchList();' \
    || { browser_content_fail "refresh() no longer delegates to fetchList() (#517)"; return 1; }

  for _mutation in uploadSingle uploadBatch rescan extract remove; do
    _mutation_block="$(browser_content_async_block "$_js" "$_mutation")"
    [ -n "$_mutation_block" ] \
      || { browser_content_fail "inbox-ui.js lacks $_mutation() mutation handler (#517)"; return 1; }
    printf '%s\n' "$_mutation_block" | grep -Fq 'await fetchList();' \
      || { browser_content_fail "$_mutation() does not refresh from backend authority via fetchList() (#517)"; return 1; }
    if printf '%s\n' "$_mutation_block" | grep -Fq 'await refresh();'; then
      browser_content_fail "$_mutation() still uses await refresh() while holding inFlight (#517 stale-state regression)"
      return 1
    fi
  done
  browser_content_pass "#517 mutation handlers use fetchList() while refresh keeps the inFlight guard"

  # --- shell: hidden empty-state panels ---------------------------------------
  _count="$(grep -o 'empty-state' "$_html" | wc -l | tr -d ' ')"
  _hidden_count="$(grep -c 'hidden' "$_html" || true)"
  [ "$_count" -ge 2 ] \
    || { browser_content_fail "index.html has fewer than 2 empty-state panels (found $_count)"; return 1; }
  [ "$_hidden_count" -ge 2 ] \
    || { browser_content_fail "index.html has fewer than 2 hidden markers (found $_hidden_count)"; return 1; }
  browser_content_pass "index.html carries hidden empty-state panels (empty-state=$_count, hidden-lines=$_hidden_count)"
}
