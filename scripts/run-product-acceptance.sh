#!/bin/sh
# Product-journey acceptance runner (Refs #429, #454 §B; #456 R2).
#
# Produces the versioned golden-workspace gate:
#   clean built JAR -> temp knowledge root -> loopback HTTP -> public /api/v1
#   -> governed Ask/Proposal/Draft/Publish -> quality -> restart/currentness
#   -> bounded reports under target/release-evidence/ (git-ignored).
#
# Usage:
#   scripts/run-product-acceptance.sh [--skip-build] [--with-vector-native]
#       [--jar <path>] [--manifest <path>]
#
# Candidate identity (Refs #456 R2): the tested JAR is NEVER chosen by mtime.
# The exact filename is derived from the Maven authority
# (project.artifactId + project.version); an explicit --jar must name that
# same file. Before any suite runs, the script verifies the artifact exists,
# its filename, its JAR-internal version (manifest Implementation-Version and
# bundled app.version), and — when the release sidecar manifest exists — that
# the sidecar describes this exact JAR (version, filename, SHA-256,
# sourceCommit). A missing sidecar only warns here (plain `mvn package`
# produces none); check-release-readiness.sh still refuses READY without a
# consistent manifest. Stale / wrong-version / multiple-candidate states fail
# closed instead of auto-picking one JAR.
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

. "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/release-identity.sh"

fail() { echo "[acceptance] FAIL: $1" >&2; exit 1; }

SKIP_BUILD=0
WITH_VECTOR_NATIVE=0
EXPLICIT_JAR=""
EXPLICIT_MANIFEST=""
while [ $# -gt 0 ]; do
  case "$1" in
    --skip-build) SKIP_BUILD=1; shift ;;
    --with-vector-native) WITH_VECTOR_NATIVE=1; shift ;;
    --jar) EXPLICIT_JAR="${2:-}"; [ -n "$EXPLICIT_JAR" ] || fail "--jar requires a path"; shift 2 ;;
    --manifest) EXPLICIT_MANIFEST="${2:-}"; [ -n "$EXPLICIT_MANIFEST" ] || fail "--manifest requires a path"; shift 2 ;;
    *) echo "[acceptance] unknown argument: $1" >&2; exit 2 ;;
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
  echo "[acceptance] skipping build; requiring existing candidate JAR"
fi

# Version truth is Maven only (no second hardcoded version, no mtime pick):
# the exact candidate filename is derived, then verified before use.
ARTIFACT_ID="$(mvn --batch-mode -q help:evaluate -Dexpression=project.artifactId -DforceStdout)"
PROJECT_VERSION="$(mvn --batch-mode -q help:evaluate -Dexpression=project.version -DforceStdout)"
[ -n "${ARTIFACT_ID:-}" ] || fail "cannot derive Maven artifactId"
[ -n "${PROJECT_VERSION:-}" ] || fail "cannot derive Maven project version"
EXPECTED_BASENAME="$(release_identity_expected_basename "$ARTIFACT_ID" "$PROJECT_VERSION")" \
  || fail "cannot derive expected candidate name"
EXPECTED_FILENAME="${EXPECTED_BASENAME}.jar"
if [ -n "$EXPLICIT_JAR" ]; then
  CANDIDATE_JAR="$(release_identity_canonicalize "$EXPLICIT_JAR")" \
    || fail "cannot resolve --jar $EXPLICIT_JAR"
  release_identity_assert_regular_file "$CANDIDATE_JAR" "explicit --jar candidate" \
    || fail "explicit --jar candidate unusable"
  release_identity_assert_exact_filename "$CANDIDATE_JAR" "$EXPECTED_FILENAME" \
    || fail "explicit --jar is not the Maven $PROJECT_VERSION candidate"
elif [ -f "target/${EXPECTED_FILENAME}" ]; then
  CANDIDATE_JAR="$(release_identity_canonicalize "target/${EXPECTED_FILENAME}")" \
    || fail "cannot resolve target/${EXPECTED_FILENAME}"
elif [ -f "target/release-candidate/${EXPECTED_FILENAME}" ]; then
  CANDIDATE_JAR="$(release_identity_canonicalize "target/release-candidate/${EXPECTED_FILENAME}")" \
    || fail "cannot resolve target/release-candidate/${EXPECTED_FILENAME}"
else
  echo "[acceptance] FAIL: exact candidate ${EXPECTED_FILENAME} missing; refusing to guess." >&2
  echo "[acceptance] present files (if any):" >&2
  ls target/llm-wiki-km-*.jar target/release-candidate/*.jar 2>/dev/null >&2 || true
  echo "[acceptance] build the candidate first (or pass --jar with the exact file)." >&2
  exit 1
fi
echo "[acceptance] candidate JAR: $CANDIDATE_JAR"
release_identity_verify_jar_internal_version "$CANDIDATE_JAR" "$PROJECT_VERSION" \
  || fail "candidate JAR identity does not match Maven $PROJECT_VERSION"

if [ -n "$EXPLICIT_MANIFEST" ]; then
  MANIFEST_JSON="$(release_identity_canonicalize "$EXPLICIT_MANIFEST")" \
    || fail "cannot resolve --manifest $EXPLICIT_MANIFEST"
  release_identity_assert_regular_file "$MANIFEST_JSON" "explicit --manifest" \
    || fail "explicit --manifest unusable"
elif [ -f "target/release-candidate/${EXPECTED_BASENAME}-manifest.json" ]; then
  MANIFEST_JSON="$(release_identity_canonicalize \
    "target/release-candidate/${EXPECTED_BASENAME}-manifest.json")"
else
  MANIFEST_JSON=""
fi
if [ -n "$MANIFEST_JSON" ]; then
  release_identity_verify_sidecar "$MANIFEST_JSON" "$PROJECT_VERSION" \
    "$EXPECTED_FILENAME" "$CANDIDATE_JAR" \
    || fail "sidecar manifest does not describe this exact candidate"
  echo "[acceptance] sidecar verified: $MANIFEST_JSON"
else
  echo "[acceptance] WARN: no release sidecar manifest; provenance sidecar not verified here (readiness still refuses READY without it)."
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
