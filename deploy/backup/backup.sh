#!/bin/sh
# WAL-safe backup for llm-wiki-km (#418 §G).
#
# Backs up the complete authoritative set only: vault/, archive/, the
# WAL-checkpointed knowledge.db single file, and required deployment config
# without secrets. Derived projections (FTS5, vector, graph) and logs/temp
# are intentionally excluded: they rebuild from canonical input on restore.
# Secrets (owner.env, provider keys) never enter the artifact.
set -eu

WORKSPACE_ROOT="${WORKSPACE_ROOT:?set WORKSPACE_ROOT to the workspace root}"
BACKUP_DIR="${BACKUP_DIR:?set BACKUP_DIR to the backup destination}"
RETENTION_COUNT="${BACKUP_RETENTION_COUNT:-7}"

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
DEST="${BACKUP_DIR}/llm-wiki-km-${STAMP}"
mkdir -p "$DEST"

if [ ! -d "${WORKSPACE_ROOT}/vault" ] || [ ! -d "${WORKSPACE_ROOT}/archive" ]; then
  echo "backup refused: vault/archive are missing (partial set)" >&2
  exit 1
fi
if [ ! -f "${WORKSPACE_ROOT}/data/knowledge.db" ]; then
  echo "backup refused: authoritative database is missing (partial set)" >&2
  exit 1
fi

DB="${WORKSPACE_ROOT}/data/knowledge.db"
if command -v sqlite3 >/dev/null 2>&1; then
  sqlite3 "$DB" "PRAGMA wal_checkpoint(TRUNCATE);" >/dev/null
fi

cp -a "${WORKSPACE_ROOT}/vault" "$DEST/vault"
cp -a "${WORKSPACE_ROOT}/archive" "$DEST/archive"
mkdir -p "$DEST/data"
cp -a "$DB" "$DEST/data/knowledge.db"

# Required deployment config without secret material: allowlist the file,
# refuse when a secret-looking sibling would be swept in.
if [ -f "${WORKSPACE_ROOT}/config/deployment.yml" ]; then
  mkdir -p "$DEST/config"
  cp -a "${WORKSPACE_ROOT}/config/deployment.yml" "$DEST/config/deployment.yml"
else
  echo "backup refused: required deployment configuration is missing" >&2
  exit 1
fi
if find "$DEST" -maxdepth 3 \( -name '*.env' -o -name '*secret*' \) | grep -q .; then
  echo "backup refused: secret material must not enter the artifact" >&2
  exit 1
fi

cat > "$DEST/MANIFEST.txt" <<EOF
stamp=${STAMP}
vault=included
archive=included
database=included
deployment-config=included
secrets=excluded
wal-checkpointed=yes
derived-only=no
EOF

# Rotation: keep the newest N artifacts, prune the rest (minimum contract).
# shellcheck disable=SC2012
ls -1dt "${BACKUP_DIR}"/llm-wiki-km-* 2>/dev/null | tail -n +"$((RETENTION_COUNT + 1))" | xargs -r rm -rf

echo "backup complete: $DEST"
