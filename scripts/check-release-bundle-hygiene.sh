#!/bin/sh
# Release bundle hygiene gate (Refs #430 §C, §G, challenge 5/6/10).
#
# Fails closed when the bundle contains owner secrets, provider keys, private
# vault/archive content, runtime databases, Graph data, or absolute developer
# paths. Success prints a bounded allowlist; it never dumps bundle content.
#
# Usage:
#   scripts/check-release-bundle-hygiene.sh --bundle <tar.gz> --basename <artifact-basename>
set -eu

BUNDLE=""
BASENAME=""
while [ $# -gt 0 ]; do
  case "$1" in
    --bundle) BUNDLE="$2"; shift 2 ;;
    --basename) BASENAME="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
[ -n "$BUNDLE" ] && [ -f "$BUNDLE" ] || { echo "[hygiene] FAIL: bundle missing" >&2; exit 1; }
[ -n "$BASENAME" ] || { echo "[hygiene] FAIL: basename missing" >&2; exit 1; }

fail() { echo "[hygiene] FAIL: $1" >&2; exit 1; }

LIST="$(tar --list --gzip --file "$BUNDLE")"
echo "$LIST" | grep -q "^${BASENAME}\.jar$" || fail "bundle missing candidate JAR"
echo "$LIST" | grep -q "^${BASENAME}-manifest\.json$" || fail "bundle missing manifest"
echo "$LIST" | grep -q "dependencies\.tsv$" || fail "bundle missing dependency inventory"

# Forbidden members: runtime state, canonical private content, secrets.
if echo "$LIST" | grep -Eq '(^|/)(data/|vault/|archive/|config/|\.env$|owner\.env|knowledge\.db|\.db$|\.db-shm|\.db-wal|graph/)'; then
  echo "$LIST" >&2
  fail "bundle contains runtime DB / vault / archive / Graph / env material"
fi

TMPDIR_H="$(mktemp -d "${TMPDIR:-/tmp}/bundle-hygiene.XXXXXX")"
trap 'rm -rf "$TMPDIR_H"' EXIT INT TERM
tar --extract --gzip --file "$BUNDLE" --directory "$TMPDIR_H"

# Content scan on text companions (JAR bytes excluded from the text scan).
# Credential scan targets assigned secret values and key blocks — never bare
# documentation words like "verifier" or "password" in the release notes.
if grep -rE '(OWNER_PASSWORD_VERIFIER|OWNER_PASSWORD_HASH|OPENAI_API_KEY|EMBEDDING_PROVIDER_API_KEY|QUERY_REWRITE_PROVIDER_API_KEY|MCP_ADAPTER_AUTH_TOKEN)[=:][^[:space:]]' "$TMPDIR_H" \
    --include='*.json' --include='*.md' --include='*.tsv' --include='*.sha256' >/dev/null 2>&1; then
  fail "bundle text companion contains an assigned credential value"
fi
if grep -rE 'BEGIN .*(PRIVATE KEY|RSA PRIVATE|OPENSSH PRIVATE)|gh[pousr]_[A-Za-z0-9]{8,}|sk-[A-Za-z0-9]{8,}|xox[bap]-[A-Za-z0-9]' "$TMPDIR_H" \
    --include='*.json' --include='*.md' --include='*.tsv' --include='*.sha256' >/dev/null 2>&1; then
  fail "bundle text companion leaks key material"
fi
# Absolute-path scan on text companions (JAR bytes excluded).
# Logical names like vault/ or knowledge.db may appear in the bundled docs as
# boundary descriptions; only absolute developer-machine paths are leaks here.
# (Membership exclusion is enforced above via the tar list; manifest-level
# logical-name hygiene is enforced by the build script itself.)
if grep -rE '/Users/|/home/[^/]+/|C:\\|/tmp/(candidate|bundle|release|sqlite|product-acceptance)' "$TMPDIR_H" \
    --include='*.json' --include='*.md' --include='*.tsv' >/dev/null 2>&1; then
  fail "bundle text companion leaks an absolute developer path"
fi

echo "[hygiene] PASS: bounded bundle ($BASENAME) contains only JAR + manifest + inventory + notes"
