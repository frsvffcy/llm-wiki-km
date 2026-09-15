#!/bin/sh
# Deterministic dependency inventory (Refs #430 §D).
#
# Produces machine-readable coordinates/version evidence without adding a
# third-party SBOM plugin to the production classpath:
#   target/release-candidate/<artifact>-dependencies.tsv (+ .sha256)
#
# Tradeoff (bounded review): CycloneDX would give a standard SBOM envelope but
# adds a new pinned plugin supply-chain + maintenance surface for the first
# release. The TSV records exact resolved coordinates (group/artifact/type/
# version/scope, sorted, deduped) from Maven itself, contains no developer
# paths or credentials, and is documented as the weaker option. A future Story
# may adopt pinned CycloneDX without changing this file's hygiene contract.
#
# Usage:
#   scripts/generate-dependency-inventory.sh [--output-dir <dir>]
set -eu

OUTPUT_DIR="target/release-candidate"
while [ $# -gt 0 ]; do
  case "$1" in
    --output-dir) OUTPUT_DIR="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

ARTIFACT_ID="$(mvn --batch-mode -q help:evaluate -Dexpression=project.artifactId -DforceStdout)"
PROJECT_VERSION="$(mvn --batch-mode -q help:evaluate -Dexpression=project.version -DforceStdout)"
if [ -z "${ARTIFACT_ID:-}" ] || [ -z "${PROJECT_VERSION:-}" ]; then
  echo "[inventory] FAIL: cannot derive Maven coordinates." >&2
  exit 1
fi
BASENAME="${ARTIFACT_ID}-${PROJECT_VERSION}"
mkdir -p "$OUTPUT_DIR"

TMP_LIST="$(mktemp "${TMPDIR:-/tmp}/deps.XXXXXX")"
trap 'rm -f "$TMP_LIST"' EXIT INT TERM

echo "[inventory] resolving deterministic coordinates via Maven..."
mvn --batch-mode -q dependency:list -Dsort=true -DoutputFile="$TMP_LIST"

python3 - "$TMP_LIST" "$OUTPUT_DIR/${BASENAME}-dependencies.tsv" <<'PY'
import sys
src, dst = sys.argv[1], sys.argv[2]
rows = set()
with open(src, encoding="utf-8", errors="strict") as fh:
    for raw in fh:
        line = raw.strip()
        if not line or line.startswith("The following"):
            continue
        # Format: group:artifact:type:version:scope [-- module ...]
        coord = line.split()[0]
        parts = coord.split(":")
        if len(parts) != 5:
            continue
        rows.add(tuple(parts))
ordered = sorted(rows)
with open(dst, "w", encoding="utf-8", newline="\n") as out:
    out.write("group\tartifact\ttype\tversion\tscope\n")
    for r in ordered:
        out.write("\t".join(r) + "\n")
print(f"[inventory] wrote {len(ordered)} coordinates to {dst}")
PY

TSV="$OUTPUT_DIR/${BASENAME}-dependencies.tsv"
if [ ! -s "$TSV" ]; then
  echo "[inventory] FAIL: inventory is empty." >&2
  exit 1
fi

# Hygiene: no developer paths, no credentials, no raw tree dump.
if grep -Eq '/Users/|/home/|/tmp/|C:\\|BEGIN .*PRIVATE|api[_-]?key|password|verifier' "$TSV"; then
  echo "[inventory] FAIL: inventory leaks path or secret material." >&2
  exit 1
fi
# Not a pasted console log: must be the TSV contract with a header.
# POSIX grep does not define \t (GNU grep treats it as a literal "t"), so
# compare the exact header emitted above instead of relying on regex escapes.
EXPECTED_HEADER="$(printf 'group\tartifact\ttype\tversion\tscope')"
[ "$(sed -n '1p' "$TSV")" = "$EXPECTED_HEADER" ] || {
  echo "[inventory] FAIL: inventory is not the TSV contract." >&2
  exit 1
}

if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "$TSV" | awk '{print $1}' > "$TSV.sha256.tmp"
  mv "$TSV.sha256.tmp" "${TSV}.sha256"
else
  shasum -a 256 "$TSV" | awk '{print $1}' > "$TSV.sha256.tmp"
  mv "$TSV.sha256.tmp" "${TSV}.sha256"
fi
echo "[inventory] fingerprint: $(cat "${TSV}.sha256")  ${BASENAME}-dependencies.tsv"
