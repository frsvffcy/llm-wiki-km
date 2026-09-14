#!/bin/sh
# Restore smoke for llm-wiki-km (#418 §H).
#
# Restores one authoritative backup artifact to an empty target root, fails
# closed on partial/corrupt sets, then leaves Flyway migration, currentness,
# derived-projection rebuild, and the readiness gate to application startup:
# start the service, watch it validate migrations, rebuild FTS/vector/graph,
# and report readiness. Stale derived state must never read as ready; a
# corrupt or partial restore must never serve.
set -eu

ARTIFACT="${BACKUP_ARTIFACT:?set BACKUP_ARTIFACT to one llm-wiki-km-<stamp> directory}"
TARGET_ROOT="${TARGET_ROOT:?set TARGET_ROOT to an empty restore directory}"

fail() { echo "restore refused: $1" >&2; exit 1; }

[ -f "$ARTIFACT/MANIFEST.txt" ] || fail "manifest is missing"
grep -q '^vault=included$' "$ARTIFACT/MANIFEST.txt" || fail "vault is missing"
grep -q '^archive=included$' "$ARTIFACT/MANIFEST.txt" || fail "archive is missing"
grep -q '^database=included$' "$ARTIFACT/MANIFEST.txt" || fail "database is missing"
grep -q '^deployment-config=included$' "$ARTIFACT/MANIFEST.txt" || fail "deployment config is missing"
grep -q '^secrets=excluded$' "$ARTIFACT/MANIFEST.txt" || fail "secret boundary is unclear"
grep -q '^derived-only=no$' "$ARTIFACT/MANIFEST.txt" || fail "derived-only set is never complete"

[ -s "$ARTIFACT/data/knowledge.db" ] || fail "database file is missing or empty"
[ -d "$ARTIFACT/vault" ] || fail "vault content is missing"
[ -d "$ARTIFACT/archive" ] || fail "archive content is missing"

if [ -n "$(ls -A "$TARGET_ROOT" 2>/dev/null)" ]; then
  fail "target directory is not empty"
fi

mkdir -p "$TARGET_ROOT"
cp -a "$ARTIFACT/vault" "$TARGET_ROOT/vault"
cp -a "$ARTIFACT/archive" "$TARGET_ROOT/archive"
mkdir -p "$TARGET_ROOT/data"
cp -a "$ARTIFACT/data/knowledge.db" "$TARGET_ROOT/data/knowledge.db"
mkdir -p "$TARGET_ROOT/config"
cp -a "$ARTIFACT/config/deployment.yml" "$TARGET_ROOT/config/deployment.yml"
for dir in logs temp inbox; do mkdir -p "$TARGET_ROOT/$dir"; done

cat <<EOF
restore staged at $TARGET_ROOT.
Next, exactly in this order:
  1. start the service and let Flyway validate/migrate the database;
  2. let currentness checks run and rebuild FTS/vector/graph projections;
  3. read GET /api/v1/system/deployment and the projection readiness surfaces;
  4. serve only when every readiness surface agrees it is ready.
EOF
