#!/bin/sh
# Release-candidate build (Refs #430 §A, §C, §D).
#
#   verified main SHA -> clean release build -> artifact identity/provenance
#   -> SHA-256 checksum -> dependency evidence -> bounded release bundle
#
# Usage:
#   scripts/build-release-candidate.sh [--allow-dirty] [--expected-version <v>]
#
# Default requires a clean checkout (dirty fails closed). CI always runs clean.
# --allow-dirty is a local-iteration escape hatch and is recorded in the manifest.
# --expected-version enables the version-mismatch fail-closed check.
#
# Never: reuses developer target/, existing generated-sources, untracked files,
# or live provider calls as a build prerequisite. Build failure produces no
# manifest/bundle and never reports candidate-ready.
set -eu

PROCEDURE_VERSION="release-candidate-procedure-v1"
ALLOW_DIRTY=0
EXPECTED_VERSION=""
while [ $# -gt 0 ]; do
  case "$1" in
    --allow-dirty) ALLOW_DIRTY=1; shift ;;
    --expected-version) EXPECTED_VERSION="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

fail() { echo "[release-build] FAIL: $1" >&2; exit 1; }

# --- 1. Toolchain authority -------------------------------------------------
JAVA_SPEC="$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p')"
[ "$JAVA_SPEC" = "21" ] || fail "release build requires Java 21; found ${JAVA_SPEC:-unknown}"
command -v mvn >/dev/null 2>&1 || fail "mvn is required"
MVN_VERSION="$(mvn -version 2>&1 | head -1 | tr -d '\r')"
case "$MVN_VERSION" in
  *3.9*|*3.10*|*4.*) ;;
  *) echo "[release-build] WARN: unexpected Maven version: $MVN_VERSION" ;;
esac
JAVA_VERSION="$(java -version 2>&1 | head -1 | tr -d '\r')"
command -v git >/dev/null 2>&1 || fail "git is required"

# --- 2. Source authority (clean checkout) -----------------------------------
SOURCE_COMMIT="$(git rev-parse HEAD 2>/dev/null)" || fail "cannot read source commit"
DIRTY_FILES="$(git status --porcelain 2>/dev/null || true)"
DIRTY="false"
if [ -n "$DIRTY_FILES" ]; then
  if [ "$ALLOW_DIRTY" -eq 1 ]; then
    DIRTY="true"
    echo "[release-build] WARN: dirty tree allowed for local iteration (recorded)."
  else
    echo "[release-build] dirty tree:" >&2
    echo "$DIRTY_FILES" >&2
    fail "release build requires a clean checkout (use --allow-dirty only for local iteration)"
  fi
fi

# --- 3. Version authority (Maven only; no second truth) ----------------------
ARTIFACT_ID="$(mvn --batch-mode -q help:evaluate -Dexpression=project.artifactId -DforceStdout)"
PROJECT_VERSION="$(mvn --batch-mode -q help:evaluate -Dexpression=project.version -DforceStdout)"
[ -n "${ARTIFACT_ID:-}" ] || fail "cannot derive Maven artifactId"
[ -n "${PROJECT_VERSION:-}" ] || fail "cannot derive Maven project version"
if [ -n "$EXPECTED_VERSION" ] && [ "$PROJECT_VERSION" != "$EXPECTED_VERSION" ]; then
  fail "pom.xml version ${PROJECT_VERSION} != expected ${EXPECTED_VERSION} (no second version truth)"
fi
BASENAME="${ARTIFACT_ID}-${PROJECT_VERSION}"
echo "[release-build] source=${SOURCE_COMMIT} version=${PROJECT_VERSION} procedure=${PROCEDURE_VERSION}"

# --- 4. Clean lifecycle (no developer target/ reuse) -------------------------
# NOTE: OUT_DIR lives under target/ so it must be (re)created AFTER
# `mvn clean`, which wipes the whole target/ tree (challenge: build failure
# must not leave a half-finished candidate behind claiming readiness).
OUT_DIR="target/release-candidate"

BUILD_COMMAND="mvn clean package -Dtest.execution.skip=true"
echo "[release-build] running clean lifecycle: $BUILD_COMMAND"
# shellcheck disable=SC2086
mvn --batch-mode clean package -Dtest.execution.skip=true || fail "clean release build failed; no half-finished candidate is published"

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

JAR="target/${BASENAME}.jar"
[ -f "$JAR" ] || fail "expected artifact $JAR missing after clean build"
cp "$JAR" "$OUT_DIR/${BASENAME}.jar"
ARTIFACT_JAR="$OUT_DIR/${BASENAME}.jar"

# --- 5. Artifact identity ------------------------------------------------------
if command -v sha256sum >/dev/null 2>&1; then
  ARTIFACT_SHA256="$(sha256sum "$ARTIFACT_JAR" | awk '{print $1}')"
else
  ARTIFACT_SHA256="$(shasum -a 256 "$ARTIFACT_JAR" | awk '{print $1}')"
fi
[ -n "$ARTIFACT_SHA256" ] || fail "cannot compute artifact SHA-256"
echo "$ARTIFACT_SHA256  ${BASENAME}.jar" > "$OUT_DIR/${BASENAME}.sha256"
echo "[release-build] artifact ${BASENAME}.jar sha256=${ARTIFACT_SHA256}"

# --- 6. Flyway highest migration (derived from chain, never hand-edited) -------
MIGRATION_DIR="src/main/resources/db/migration"
HIGHEST_MIGRATION="$(ls "$MIGRATION_DIR"/V*__*.sql 2>/dev/null | sort -V | tail -1 | xargs -n1 basename 2>/dev/null || true)"
[ -n "$HIGHEST_MIGRATION" ] || fail "cannot derive Flyway highest migration from $MIGRATION_DIR"
echo "[release-build] flyway highest: $HIGHEST_MIGRATION"

# --- 7. Dependency evidence -----------------------------------------------------
sh scripts/generate-dependency-inventory.sh --output-dir "$OUT_DIR" \
  || fail "dependency inventory failed"
DEPENDENCY_FINGERPRINT="$(cat "$OUT_DIR/${BASENAME}-dependencies.tsv.sha256" | tr -d ' \r\n')"

# --- 8. Manifest (machine + human readable; createdAt never enters JAR bytes) --
CREATED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
RUN_IDENTITY="${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-1}"
ACCEPTANCE_CORPUS="product-acceptance-corpus-v1"
export MANIFEST_JSON="$OUT_DIR/${BASENAME}-manifest.json"
export M_PROJECT="llm-wiki-km" M_VERSION="$PROJECT_VERSION" M_COMMIT="$SOURCE_COMMIT"
export M_FILE="${BASENAME}.jar" M_SHA="$ARTIFACT_SHA256" M_JAVA="$JAVA_VERSION"
export M_TOOL="$MVN_VERSION" M_CMD="$BUILD_COMMAND" M_PROC="$PROCEDURE_VERSION"
export M_CREATED="$CREATED_AT" M_FLYWAY="$HIGHEST_MIGRATION" M_DEP="$DEPENDENCY_FINGERPRINT"
export M_CORPUS="$ACCEPTANCE_CORPUS" M_RUN="$RUN_IDENTITY" M_DIRTY="$DIRTY"
python3 - <<'PY'
import json, os
manifest = {
    "project": os.environ["M_PROJECT"],
    "version": os.environ["M_VERSION"],
    "sourceCommit": os.environ["M_COMMIT"],
    "dirtyTree": os.environ["M_DIRTY"] == "true",
    "artifactFilename": os.environ["M_FILE"],
    "artifactSha256": os.environ["M_SHA"],
    "javaVersion": os.environ["M_JAVA"],
    "buildTool": os.environ["M_TOOL"],
    "buildCommand": os.environ["M_CMD"],
    "procedureVersion": os.environ["M_PROC"],
    "flywayHighestMigration": os.environ["M_FLYWAY"],
    "dependencyFingerprint": os.environ["M_DEP"],
    "acceptanceCorpusVersion": os.environ["M_CORPUS"],
    "createdAt": os.environ["M_CREATED"],
    "runIdentity": os.environ["M_RUN"],
}
with open(os.environ["MANIFEST_JSON"], "w", encoding="utf-8", newline="\n") as fh:
    json.dump(manifest, fh, indent=2, sort_keys=True)
    fh.write("\n")
print("[release-build] manifest written")
PY

python3 - "$MANIFEST_JSON" "$OUT_DIR/${BASENAME}-manifest.md" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as fh:
    m = json.load(fh)
lines = [
    "# Release candidate manifest",
    "",
    f"- project: {m['project']}",
    f"- version: {m['version']}",
    f"- sourceCommit: {m['sourceCommit']}",
    f"- dirtyTree: {str(m['dirtyTree']).lower()}",
    f"- artifactFilename: {m['artifactFilename']}",
    f"- artifactSha256: {m['artifactSha256']}",
    f"- javaVersion: {m['javaVersion']}",
    f"- buildTool: {m['buildTool']}",
    f"- buildCommand: {m['buildCommand']}",
    f"- procedureVersion: {m['procedureVersion']}",
    f"- flywayHighestMigration: {m['flywayHighestMigration']}",
    f"- dependencyFingerprint: {m['dependencyFingerprint']}",
    f"- acceptanceCorpusVersion: {m['acceptanceCorpusVersion']}",
    f"- createdAt: {m['createdAt']}",
    f"- runIdentity: {m['runIdentity']}",
    "",
    "Machine-readable authority is the sibling -manifest.json file.",
    "createdAt never participates in reproducible artifact bytes.",
    "",
]
with open(sys.argv[2], "w", encoding="utf-8", newline="\n") as out:
    out.write("\n".join(lines))
PY

# --- 9. Manifest hygiene (no secret / key / verifier / absolute path / data) ----
if grep -Ei 'api[_-]?key|password|verifier|secret|BEGIN .*PRIVATE|/Users/|/home/|/tmp/|knowledge\.db|vault/|archive/' "$MANIFEST_JSON" >/dev/null 2>&1; then
  rm -rf "$OUT_DIR"
  fail "manifest leaks secret/path/data material; candidate directory removed"
fi

# --- 10. Bounded release bundle (JAR + manifest + inventory + notes only) ---------
cp docs/release/v0.1.0-release-notes.md "$OUT_DIR/${BASENAME}-release-notes.md"
cp docs/release/native-capability-matrix.md "$OUT_DIR/${BASENAME}-native-matrix.md"
BUNDLE="$OUT_DIR/${BASENAME}-bundle.tar.gz"
tar --create --gzip --file "$BUNDLE" --directory "$OUT_DIR" \
  "${BASENAME}.jar" "${BASENAME}-manifest.json" "${BASENAME}-manifest.md" \
  "${BASENAME}-dependencies.tsv" "${BASENAME}-dependencies.tsv.sha256" \
  "${BASENAME}.sha256" "${BASENAME}-release-notes.md" "${BASENAME}-native-matrix.md" \
  || fail "bundle creation failed"
if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "$BUNDLE" | awk '{print $1}' > "${BUNDLE}.sha256"
else
  shasum -a 256 "$BUNDLE" | awk '{print $1}' > "${BUNDLE}.sha256"
fi

# --- 11. Bundle hygiene (challenge cases 5/6/10) ---------------------------------
sh scripts/check-release-bundle-hygiene.sh --bundle "$BUNDLE" --basename "$BASENAME" \
  || fail "bundle hygiene failed; candidate directory kept for inspection but never candidate-ready"

echo "[release-build] READY: $OUT_DIR (bundle $(cat "${BUNDLE}.sha256"))"
ls -l "$OUT_DIR"
