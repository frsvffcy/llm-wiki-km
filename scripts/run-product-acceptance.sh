#!/bin/sh
# v0.1.0 product-journey acceptance runner (Refs #429).
#
# Produces the versioned golden-workspace gate:
#   clean built JAR -> temp knowledge root -> loopback HTTP -> public /api/v1
#   -> governed Ask/Proposal/Draft/Publish -> quality -> restart/currentness
#   -> bounded reports under target/release-evidence/ (git-ignored).
#
# Usage:
#   scripts/run-product-acceptance.sh [--skip-build] [--with-vector-native]
#
# Without --skip-build the script builds the clean JAR first so the
# JAR-subprocess suite (ProductAcceptanceJarProcessIntegrationTest) genuinely
# executes instead of prerequisite-skipping. Reports never contain secrets,
# raw provider payloads, or full canonical dumps.
#
# Vector native (Refs #435): by default the suites run provider-free for the
# vector prerequisite (typed SKIP, blocks FULL-GO, never fake-green). When
# --with-vector-native is given, or when the caller already exports a readable
# VECTOR_EXTENSION_PATH (release workflow prepares it explicitly), the pinned
# sqlite-vec v0.1.9 native is verified (checksum + JDBC smoke) and exported as
# VECTOR_CAPABILITY_ENABLED=true + VECTOR_EXTENSION_PATH so full-capability /
# JAR journeys can reach FULL-GO. Never downloads floating `latest`.
set -eu

SKIP_BUILD=0
WITH_VECTOR_NATIVE=0
for arg in "$@"; do
  case "$arg" in
    --skip-build) SKIP_BUILD=1 ;;
    --with-vector-native) WITH_VECTOR_NATIVE=1 ;;
    *) echo "[acceptance] unknown argument: $arg" >&2; exit 2 ;;
  esac
done

provision_pinned_vector_native() {
  # Pinned acquisition only (see docs/release/native-capability-matrix.md).
  OS="$(uname -s)"
  ARCH="$(uname -m)"
  if [ "$OS" = "Linux" ] && [ "$ARCH" = "x86_64" ]; then
    ARTIFACT="sqlite-vec-0.1.9-loadable-linux-x86_64.tar.gz"
    EXPECTED_SHA="b959baa1d8dc88861b1edb337b8587178cdcb12d60b4998f9d10b6a82052d5d7"
    LIB_NAME="vec0.so"
  elif [ "$OS" = "Darwin" ] && [ "$ARCH" = "arm64" ]; then
    ARTIFACT="sqlite-vec-0.1.9-loadable-macos-aarch64.tar.gz"
    EXPECTED_SHA="8282126333399ddfe98bbbcc7a1936e7252625aac49df056a98be602e46bfd29"
    LIB_NAME="vec0.dylib"
  else
    echo "[acceptance] FAIL: --with-vector-native unsupported on $OS/$ARCH (no pinned evidence)" >&2
    exit 1
  fi
  DEST_DIR="target/sqlite-vec-acceptance"
  mkdir -p "$DEST_DIR"
  if [ ! -f "$DEST_DIR/$ARTIFACT" ]; then
    echo "[acceptance] downloading pinned $ARTIFACT..."
    curl --fail --location --silent --show-error \
      --output "$DEST_DIR/$ARTIFACT" \
      "https://github.com/asg017/sqlite-vec/releases/download/v0.1.9/$ARTIFACT"
  fi
  echo "[acceptance] verifying checksum..."
  if command -v sha256sum >/dev/null 2>&1; then
    echo "$EXPECTED_SHA  $DEST_DIR/$ARTIFACT" | sha256sum --check -
  else
    echo "$EXPECTED_SHA  $DEST_DIR/$ARTIFACT" | shasum -a 256 --check -
  fi
  tar --extract --gzip --file "$DEST_DIR/$ARTIFACT" --directory "$DEST_DIR"
  VECTOR_LIB="$DEST_DIR/$LIB_NAME"
  [ -f "$VECTOR_LIB" ] || { echo "[acceptance] FAIL: $LIB_NAME missing after extract" >&2; exit 1; }
  echo "[acceptance] running JDBC smoke on $VECTOR_LIB..."
  scripts/sqlite-vec-jdbc-smoke.sh "$VECTOR_LIB"
  # Absolute path: the JAR subprocess must resolve it independent of cwd.
  case "$VECTOR_LIB" in
    /*) VECTOR_EXTENSION_PATH="$VECTOR_LIB" ;;
    *) VECTOR_EXTENSION_PATH="$(pwd)/$VECTOR_LIB" ;;
  esac
  export VECTOR_EXTENSION_PATH
  export VECTOR_CAPABILITY_ENABLED="true"
  echo "[acceptance] vector native provisioned: $VECTOR_EXTENSION_PATH"
}

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

# Vector native provisioning happens AFTER the clean build on purpose:
# `mvn clean` wipes target/, so any native staged under target/ before the
# build would be deleted (and a workflow-provisioned path would be lost).
if [ -n "${VECTOR_EXTENSION_PATH:-}" ] && [ -f "${VECTOR_EXTENSION_PATH:-}" ]; then
  echo "[acceptance] reusing provisioned vector native: $VECTOR_EXTENSION_PATH"
  case "$VECTOR_EXTENSION_PATH" in
    /*) export VECTOR_EXTENSION_PATH ;;
    *) export VECTOR_EXTENSION_PATH="$(pwd)/$VECTOR_EXTENSION_PATH" ;;
  esac
  export VECTOR_CAPABILITY_ENABLED="true"
  scripts/sqlite-vec-jdbc-smoke.sh "$VECTOR_EXTENSION_PATH"
elif [ "$WITH_VECTOR_NATIVE" -eq 1 ]; then
  provision_pinned_vector_native
else
  echo "[acceptance] vector native absent; vector-prerequisite stays typed SKIP (blocks FULL-GO)."
fi

echo "[acceptance] running versioned product-journey suites..."
mvn --batch-mode test -Pintegration \
  -Dtest='ProductAcceptanceCorpusContractTest,ProductAcceptanceBaselineJourneyIntegrationTest,ProductAcceptanceFullCapabilityJourneyIntegrationTest,ProductAcceptanceJarProcessIntegrationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false

echo "[acceptance] reports (git-ignored evidence, not runtime authority):"
ls -R target/release-evidence/ 2>/dev/null || echo "(no reports produced)"
