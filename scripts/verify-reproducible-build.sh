#!/bin/sh
# Reproducibility verification (Refs #430 §B).
#
# Builds the release artifact twice from a clean lifecycle with the documented
# toolchain and requires bit-identical SHA-256. Any difference fails closed
# with both hashes; it never claims "same workflow" as reproducibility.
#
# Usage:
#   scripts/verify-reproducible-build.sh [--allow-dirty]
set -eu

ALLOW_DIRTY=""
if [ "${1:-}" = "--allow-dirty" ]; then
  ALLOW_DIRTY="--allow-dirty"
fi

fail() { echo "[reproducible] FAIL: $1" >&2; exit 1; }

JAVA_SPEC="$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p')"
[ "$JAVA_SPEC" = "21" ] || fail "requires Java 21; found ${JAVA_SPEC:-unknown}"

echo "[reproducible] first clean build..."
sh scripts/build-release-candidate.sh $ALLOW_DIRTY || fail "first build failed"
FIRST="$(python3 -c 'import json,glob; print(json.load(open(glob.glob("target/release-candidate/*-manifest.json")[0]))["artifactSha256"])')"
[ -n "$FIRST" ] || fail "first hash missing"
cp target/release-candidate/*.jar "/tmp/reproducible-first-$(date -u +%s).jar"

echo "[reproducible] second clean build..."
sh scripts/build-release-candidate.sh $ALLOW_DIRTY || fail "second build failed"
SECOND="$(python3 -c 'import json,glob; print(json.load(open(glob.glob("target/release-candidate/*-manifest.json")[0]))["artifactSha256"])')"
[ -n "$SECOND" ] || fail "second hash missing"

echo "[reproducible] first : $FIRST"
echo "[reproducible] second: $SECOND"
if [ "$FIRST" = "$SECOND" ]; then
  echo "[reproducible] PASS: bit-identical SHA-256 across two clean builds"
else
  fail "hashes differ across identical source/toolchain (first=$FIRST second=$SECOND)"
fi
