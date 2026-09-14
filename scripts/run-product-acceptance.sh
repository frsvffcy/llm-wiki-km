#!/bin/sh
# v0.1.0 product-journey acceptance runner (Refs #429).
#
# Produces the versioned golden-workspace gate:
#   clean built JAR -> temp knowledge root -> loopback HTTP -> public /api/v1
#   -> governed Ask/Proposal/Draft/Publish -> quality -> restart/currentness
#   -> bounded reports under target/release-evidence/ (git-ignored).
#
# Usage:
#   scripts/run-product-acceptance.sh [--skip-build]
#
# Without --skip-build the script builds the clean JAR first so the
# JAR-subprocess suite (ProductAcceptanceJarProcessIntegrationTest) genuinely
# executes instead of prerequisite-skipping. Reports never contain secrets,
# raw provider payloads, or full canonical dumps.
set -eu

SKIP_BUILD=0
if [ "${1:-}" = "--skip-build" ]; then
  SKIP_BUILD=1
fi

if [ "$SKIP_BUILD" -eq 0 ]; then
  echo "[acceptance] building clean application JAR..."
  mvn --batch-mode clean package -DskipTests
else
  echo "[acceptance] skipping build; requiring existing target/llm-wiki-km-0.1.0.jar"
fi

if [ ! -f target/llm-wiki-km-0.1.0.jar ]; then
  echo "[acceptance] FAIL: target/llm-wiki-km-0.1.0.jar missing; cannot prove JAR boundary." >&2
  exit 1
fi

echo "[acceptance] running versioned product-journey suites..."
mvn --batch-mode test -Pintegration \
  -Dtest='ProductAcceptanceCorpusContractTest,ProductAcceptanceBaselineJourneyIntegrationTest,ProductAcceptanceFullCapabilityJourneyIntegrationTest,ProductAcceptanceJarProcessIntegrationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false

echo "[acceptance] reports (git-ignored evidence, not runtime authority):"
ls -R target/release-evidence/ 2>/dev/null || echo "(no reports produced)"
