#!/bin/sh
# Behavioral regression for scripts/release-identity.sh (Refs #456 R2-R4; #458).
#
# Hermetic: temp dirs only, no Maven, no network, no repository state.
# Fake JARs are assembled with python3 zipfile (deterministic bytes).
# Run: sh scripts/tests/test-release-identity.sh
# Also executed from the fast tier via ReleaseIdentityShellContractTest.
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
. "$SCRIPT_DIR/../release-identity.sh"

PASS=0
FAIL=0
ok() { PASS=$((PASS + 1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL + 1)); echo "  NOT OK: $1" >&2; }
expect_pass() {
  if "$@" >/dev/null 2>&1; then ok "$1"; else bad "$1 (expected pass)"; fi
}
expect_fail() {
  if "$@" >/dev/null 2>&1; then bad "$1 (expected fail-closed)"; else ok "$1 fails closed"; fi
}

WORK="$(mktemp -d "${TMPDIR:-/tmp}/release-identity-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT INT TERM

COMMIT_A="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
COMMIT_B="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

make_jar() {
  python3 - "$1" "$2" "$3" <<'PY'
import sys, zipfile
out, manifest_version, app_version = sys.argv[1], sys.argv[2], sys.argv[3]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as jar:
    jar.writestr("META-INF/MANIFEST.MF",
                 "Manifest-Version: 1.0\nImplementation-Version: %s\n" % manifest_version)
    jar.writestr("BOOT-INF/classes/version.properties",
                 "app.version=%s\n" % app_version)
PY
}

make_manifest() {
  python3 - "$1" "$2" "$3" "$4" "$5" <<'PY'
import json, sys
path, version, filename, sha, commit = sys.argv[1:6]
with open(path, "w", encoding="utf-8") as fh:
    json.dump({"artifactFilename": filename, "artifactSha256": sha,
               "sourceCommit": commit, "version": version}, fh, indent=2, sort_keys=True)
    fh.write("\n")
PY
}

make_report() {
  python3 - "$1" "$2" <<'PY'
import json, sys
path, commit = sys.argv[1], sys.argv[2]
doc = {"overall": "FULL-GO"}
if commit != "ABSENT":
    doc["sourceCommit"] = commit
with open(path, "w", encoding="utf-8") as fh:
    json.dump(doc, fh, indent=2)
    fh.write("\n")
PY
}

echo "[test] R3 canonicalize: relative and absolute inputs agree"
mkdir -p "$WORK/proj/target/release-candidate"
touch "$WORK/proj/target/release-candidate/candidate.jar"
CANON_REL="$(cd "$WORK/proj" && release_identity_canonicalize target/release-candidate/candidate.jar)"
CANON_ABS="$(cd / && release_identity_canonicalize "$WORK/proj/target/release-candidate/candidate.jar")"
[ "$CANON_REL" = "$CANON_ABS" ] && ok "relative == absolute ($CANON_REL)" || bad "relative/absolute disagree"
# Parent symlinks (e.g. macOS /var -> /private/var) resolve to physical paths.
CANON_WORK="$(cd "$WORK/proj" && pwd -P)"
case "$CANON_REL" in
  "$CANON_WORK"/target/release-candidate/candidate.jar) ok "canonical shape" ;;
  *) bad "canonical shape: $CANON_REL" ;;
esac
OLDPWD="/nonexistent-bogus" CANON_AGAIN="$(cd "$WORK/proj" && release_identity_canonicalize target/release-candidate/candidate.jar)"
[ "$CANON_AGAIN" = "$CANON_REL" ] && ok "independent of OLDPWD" || bad "OLDPWD leak"
expect_fail release_identity_canonicalize "$WORK/proj/no-such-dir/candidate.jar"
expect_fail release_identity_canonicalize ""

echo "[test] R2 exact filename derivation (no mtime authority)"
[ "$(release_identity_expected_basename llm-wiki-km 9.9.9)" = "llm-wiki-km-9.9.9" ] \
  && ok "basename derivation" || bad "basename derivation"
expect_fail release_identity_expected_basename "" "9.9.9"

echo "[test] challenge 2/7: stale and multiple candidates never auto-pick"
make_jar "$WORK/old.jar" "9.9.8" "9.9.8"
make_jar "$WORK/new.jar" "9.9.9" "9.9.9"
touch -t 202001010000 "$WORK/new.jar"
touch -t 203001010000 "$WORK/old.jar"
expect_pass release_identity_assert_exact_filename "$WORK/new.jar" "new.jar"
expect_fail release_identity_assert_exact_filename "$WORK/old.jar" "new.jar"
expect_fail release_identity_assert_regular_file "$WORK/missing.jar" "candidate"
# The exact path resolves deterministically even though the stale file is newer.
[ -f "$WORK/new.jar" ] && ok "exact path used regardless of mtime" || bad "exact path"

echo "[test] challenge 1 (artifact level): stale internal version fails"
make_jar "$WORK/stale.jar" "9.9.8" "9.9.8"
expect_fail release_identity_verify_jar_internal_version "$WORK/stale.jar" "9.9.9"
make_jar "$WORK/good.jar" "9.9.9" "9.9.9"
expect_pass release_identity_verify_jar_internal_version "$WORK/good.jar" "9.9.9"
make_jar "$WORK/unfiltered.jar" "9.9.9" "@project.version@"
expect_fail release_identity_verify_jar_internal_version "$WORK/unfiltered.jar" "9.9.9"

echo "[test] challenge 5: swapped JAR against a stale sidecar fails"
SHA_GOOD="$(release_identity_sha256 "$WORK/good.jar")"
make_manifest "$WORK/good-manifest.json" "9.9.9" "good.jar" "$SHA_GOOD" "$COMMIT_A"
expect_pass release_identity_verify_sidecar "$WORK/good-manifest.json" "9.9.9" "good.jar" "$WORK/good.jar"
make_jar "$WORK/good.jar" "9.9.9" "9.9.9-extra-bytes-marker"
# Same versions, different bytes: sidecar no longer describes this artifact.
expect_fail release_identity_verify_sidecar "$WORK/good-manifest.json" "9.9.9" "good.jar" "$WORK/good.jar"
make_manifest "$WORK/wrongver-manifest.json" "9.9.8" "good.jar" "$SHA_GOOD" "$COMMIT_A"
expect_fail release_identity_verify_sidecar "$WORK/wrongver-manifest.json" "9.9.9" "good.jar" "$WORK/good.jar"
python3 -c 'import json; json.dump({"version":"9.9.9"}, open("'"$WORK/incomplete-manifest.json"'","w"))'
expect_fail release_identity_verify_sidecar "$WORK/incomplete-manifest.json" "9.9.9" "good.jar" "$WORK/good.jar"
make_manifest "$WORK/badcommit-manifest.json" "9.9.9" "good.jar" "$SHA_GOOD" "not-a-sha"
expect_fail release_identity_verify_sidecar "$WORK/badcommit-manifest.json" "9.9.9" "good.jar" "$WORK/good.jar"

echo "[test] challenge 4/6: report/manifest sourceCommit cross-check"
make_jar "$WORK/rc.jar" "9.9.9" "9.9.9"
make_report "$WORK/report-a.json" "$COMMIT_A"
make_report "$WORK/report-b.json" "$COMMIT_B"
make_report "$WORK/report-absent.json" "ABSENT"
make_report "$WORK/report-malformed.json" "xyz"
expect_pass release_identity_cross_check_source_commit "$WORK/report-a.json" "$COMMIT_A"
expect_fail release_identity_cross_check_source_commit "$WORK/report-b.json" "$COMMIT_A"
expect_fail release_identity_cross_check_source_commit "$WORK/report-absent.json" "$COMMIT_A"
expect_fail release_identity_cross_check_source_commit "$WORK/report-malformed.json" "$COMMIT_A"

echo "[test] challenge 7: manifest/bundle ambiguity fails closed"
mkdir -p "$WORK/rc"
echo '{}' > "$WORK/rc/a-manifest.json"
expect_pass release_identity_require_single_file "$WORK/rc" "*-manifest.json"
echo '{}' > "$WORK/rc/b-manifest.json"
expect_fail release_identity_require_single_file "$WORK/rc" "*-manifest.json"
mkdir -p "$WORK/empty"
expect_fail release_identity_require_single_file "$WORK/empty" "*-manifest.json"

echo "[test] #458 exact resolver: default ignores mtime, explicit revalidates"
mkdir -p "$WORK/cands"
make_jar "$WORK/cands/llm-wiki-km-0.1.0.jar" "0.1.0" "0.1.0"
make_jar "$WORK/cands/llm-wiki-km-0.1.1.jar" "0.1.1" "0.1.1"
# Stale 0.1.0 is newer by mtime but the Maven 0.1.1 candidate must win.
touch -t 202001010000 "$WORK/cands/llm-wiki-km-0.1.1.jar"
touch -t 203001010000 "$WORK/cands/llm-wiki-km-0.1.0.jar"
RESOLVED="$(release_identity_resolve_candidate_jar "" "llm-wiki-km-0.1.1.jar" "$WORK/cands")" \
  && [ "$(basename -- "$RESOLVED")" = "llm-wiki-km-0.1.1.jar" ] \
  && ok "default picks Maven candidate despite stale mtime" \
  || bad "default picks Maven candidate despite stale mtime"
# Same-version duplicate content: exact name still resolves deterministically.
RESOLVED2="$(release_identity_resolve_candidate_jar "" "llm-wiki-km-0.1.1.jar" "$WORK/cands")" \
  && [ "$RESOLVED" = "$RESOLVED2" ] \
  && ok "exact resolution is deterministic" \
  || bad "exact resolution is deterministic"
# Wrong-version default is fail-closed even when a newer-mtime file exists.
expect_fail release_identity_resolve_candidate_jar "" "llm-wiki-km-9.9.9.jar" "$WORK/cands"
# Explicit inputs revalidate filename and existence.
expect_pass release_identity_resolve_candidate_jar "$WORK/cands/llm-wiki-km-0.1.1.jar" "llm-wiki-km-0.1.1.jar" "$WORK/cands"
expect_fail release_identity_resolve_candidate_jar "$WORK/cands/llm-wiki-km-0.1.0.jar" "llm-wiki-km-0.1.1.jar" "$WORK/cands"
expect_fail release_identity_resolve_candidate_jar "$WORK/cands/missing.jar" "missing.jar" "$WORK/cands"
expect_fail release_identity_resolve_candidate_jar "" "" "$WORK/cands"
# Relative and absolute explicit inputs agree and ignore OLDPWD.
CANON_REL_EXPLICIT="$(cd "$WORK/cands" && release_identity_resolve_candidate_jar llm-wiki-km-0.1.1.jar llm-wiki-km-0.1.1.jar "$WORK/cands")"
CANON_ABS_EXPLICIT="$(cd / && release_identity_resolve_candidate_jar "$WORK/cands/llm-wiki-km-0.1.1.jar" llm-wiki-km-0.1.1.jar "$WORK/cands")"
[ "$CANON_REL_EXPLICIT" = "$CANON_ABS_EXPLICIT" ] \
  && ok "explicit relative == absolute" || bad "explicit relative/absolute disagree"
OLDPWD="/nonexistent-bogus" CANON_AGAIN_EXPLICIT="$(cd "$WORK/cands" && release_identity_resolve_candidate_jar llm-wiki-km-0.1.1.jar llm-wiki-km-0.1.1.jar "$WORK/cands")"
[ "$CANON_AGAIN_EXPLICIT" = "$CANON_REL_EXPLICIT" ] \
  && ok "explicit resolution independent of OLDPWD" || bad "explicit OLDPWD leak"

echo "[test] #458 sidecar-if-present: absent warns, present must describe exact JAR"
mkdir -p "$WORK/sidecar"
make_jar "$WORK/sidecar/llm-wiki-km-0.1.1.jar" "0.1.1" "0.1.1"
SHA_SIDE="$(release_identity_sha256 "$WORK/sidecar/llm-wiki-km-0.1.1.jar")"
# Absent sidecar is warn-only (plain-package flow), never a hard fail here.
expect_pass release_identity_verify_candidate_sidecar_if_present \
  "$WORK/sidecar" "llm-wiki-km-0.1.1" "llm-wiki-km-0.1.1.jar" "0.1.1" \
  "$WORK/sidecar/llm-wiki-km-0.1.1.jar"
make_manifest "$WORK/sidecar/llm-wiki-km-0.1.1-manifest.json" \
  "0.1.1" "llm-wiki-km-0.1.1.jar" "$SHA_SIDE" "$COMMIT_A"
expect_pass release_identity_verify_candidate_sidecar_if_present \
  "$WORK/sidecar" "llm-wiki-km-0.1.1" "llm-wiki-km-0.1.1.jar" "0.1.1" \
  "$WORK/sidecar/llm-wiki-km-0.1.1.jar"
# Same bytes swapped: manifest points at A but the JAR changed -> fail before body.
make_jar "$WORK/sidecar/llm-wiki-km-0.1.1.jar" "0.1.1" "0.1.1-swapped"
expect_fail release_identity_verify_candidate_sidecar_if_present \
  "$WORK/sidecar" "llm-wiki-km-0.1.1" "llm-wiki-km-0.1.1.jar" "0.1.1" \
  "$WORK/sidecar/llm-wiki-km-0.1.1.jar"
expect_fail release_identity_verify_candidate_sidecar_if_present \
  "$WORK/sidecar" "llm-wiki-km-0.1.1" "llm-wiki-km-0.1.1.jar" "" \
  "$WORK/sidecar/llm-wiki-km-0.1.1.jar"

echo "[test] result: pass=$PASS fail=$FAIL"
[ "$FAIL" -eq 0 ]
