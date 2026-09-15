#!/bin/sh
# Release identity helpers (Refs #456 R2-R4; #458 exact-resolver convergence).
#
# Single implementation for candidate/manifest/report identity checks shared
# by run-product-acceptance.sh, browser-first-mile-smoke.sh,
# check-release-readiness.sh, clean-install-smoke.sh and
# candidate-backup-restore-smoke.sh, so the fail-closed contract cannot
# drift between scripts.
#
# This file is a library: it sets no options, changes no directory, and
# performs no network or build side effects. Every function prints its
# result on stdout and diagnostics on stderr, returning 0 on match and 1
# on any missing / malformed / mismatch input (never guesses).
# Callers run under `set -eu` and fail closed on a nonzero status.
#
# Usage from a script in scripts/:
#   . "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/release-identity.sh"

# Print the SHA-256 of a regular file (sha256sum preferred, shasum fallback).
release_identity_sha256() {
  if [ ! -f "$1" ]; then
    echo "[identity] FAIL: not a regular file: $1" >&2
    return 1
  fi
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

# Canonicalize a JAR/input path to an absolute path.
#
# MUST be called before the caller changes directory: relative inputs are
# resolved against the caller's current working directory at call time, so
# the result never depends on $OLDPWD or a later mktemp cd (Refs #456 R3).
# Absolute inputs are normalized without touching $OLDPWD. Fails when the
# parent directory does not exist.
release_identity_canonicalize() {
  if [ -z "${1:-}" ]; then
    echo "[identity] FAIL: empty path cannot be canonicalized" >&2
    return 1
  fi
  case "$1" in
    /*) _abs="$1" ;;
    *) _abs="$(pwd)/$1" ;;
  esac
  _dir="$(dirname -- "$_abs")"
  _base="$(basename -- "$_abs")"
  if [ ! -d "$_dir" ]; then
    echo "[identity] FAIL: parent directory missing for: $1" >&2
    return 1
  fi
  _canon="$(cd -- "$_dir" 2>/dev/null && pwd -P 2>/dev/null)" || _canon=""
  if [ -z "$_canon" ]; then
    _canon="$(cd -- "$_dir" 2>/dev/null && pwd)" || {
      echo "[identity] FAIL: cannot resolve parent directory for: $1" >&2
      return 1
    }
  fi
  printf '%s/%s\n' "$_canon" "$_base"
}

# Exact candidate basename from the Maven authority (never mtime, Refs #456 R2).
release_identity_expected_basename() {
  if [ -z "${1:-}" ] || [ -z "${2:-}" ]; then
    echo "[identity] FAIL: artifactId and version are required" >&2
    return 1
  fi
  printf '%s-%s\n' "$1" "$2"
}

# Require a path to be an existing regular file (no guessing, no fallback pick).
release_identity_assert_regular_file() {
  if [ -z "${1:-}" ] || [ ! -f "$1" ]; then
    echo "[identity] FAIL: ${2:-candidate} missing: ${1:-<empty>}" >&2
    return 1
  fi
}

# Require the file's basename to equal the exact expected filename, so a
# stale or wrong-version candidate fails closed instead of being tested
# as if it were the release (Refs #456 R2, challenge cases 2/5/7).
release_identity_assert_exact_filename() {
  _actual="$(basename -- "$1")"
  if [ "$_actual" != "$2" ]; then
    echo "[identity] FAIL: expected exact candidate $2 but got $_actual" >&2
    return 1
  fi
}

# Implementation-Version from the JAR manifest (Spring Boot derives it from
# pom.xml project.version at package time).
release_identity_jar_manifest_version() {
  command -v unzip >/dev/null 2>&1 || {
    echo "[identity] FAIL: unzip is required to inspect $1" >&2
    return 1
  }
  _manifest="$(unzip -p "$1" META-INF/MANIFEST.MF 2>/dev/null || true)"
  if [ -z "$_manifest" ]; then
    echo "[identity] FAIL: no manifest in $1" >&2
    return 1
  fi
  _version="$(printf '%s' "$_manifest" | sed -n 's/^Implementation-Version:[[:space:]]*//p' | tr -d '\r ' | head -1)"
  if [ -z "$_version" ]; then
    echo "[identity] FAIL: manifest lacks Implementation-Version in $1" >&2
    return 1
  fi
  printf '%s\n' "$_version"
}

# app.version from the JAR-bundled generated properties (Maven-filtered
# version.properties, the runtime single authority, Refs #456 R1).
release_identity_jar_app_version() {
  command -v unzip >/dev/null 2>&1 || {
    echo "[identity] FAIL: unzip is required to inspect $1" >&2
    return 1
  }
  _props="$(unzip -p "$1" BOOT-INF/classes/version.properties 2>/dev/null || true)"
  if [ -z "$_props" ]; then
    _props="$(unzip -p "$1" version.properties 2>/dev/null || true)"
  fi
  if [ -z "$_props" ]; then
    echo "[identity] FAIL: no version.properties in $1" >&2
    return 1
  fi
  _version="$(printf '%s' "$_props" | sed -n 's/^app\.version[[:space:]]*=[[:space:]]*//p' | tr -d '\r ' | head -1)"
  if [ -z "$_version" ]; then
    echo "[identity] FAIL: version.properties lacks app.version in $1" >&2
    return 1
  fi
  case "$_version" in
    *@*) echo "[identity] FAIL: version.properties is unfiltered in $1" >&2; return 1 ;;
  esac
  printf '%s\n' "$_version"
}

# Both JAR-internal identities must equal the Maven version (challenge case 1:
# a stale JAR keeps failing even when its filename looks right).
release_identity_verify_jar_internal_version() {
  _manifest_version="$(release_identity_jar_manifest_version "$1")" || return 1
  _app_version="$(release_identity_jar_app_version "$1")" || return 1
  if [ "$_manifest_version" != "$2" ]; then
    echo "[identity] FAIL: JAR manifest version $_manifest_version != Maven $2 ($1)" >&2
    return 1
  fi
  if [ "$_app_version" != "$2" ]; then
    echo "[identity] FAIL: JAR app.version $_app_version != Maven $2 ($1)" >&2
    return 1
  fi
}

# Sidecar manifest must describe THIS exact JAR (challenge case 5: a swapped
# JAR with a stale sidecar fails closed).
# Args: manifestJson expectedVersion expectedFilename actualJar
release_identity_verify_sidecar() {
  _sidecar_version="$(release_identity_manifest_field "$1" version)" || return 1
  _sidecar_file="$(release_identity_manifest_field "$1" artifactFilename)" || return 1
  _sidecar_sha="$(release_identity_manifest_field "$1" artifactSha256)" || return 1
  _sidecar_commit="$(release_identity_manifest_field "$1" sourceCommit)" || return 1
  if [ "$_sidecar_version" != "$2" ]; then
    echo "[identity] FAIL: manifest version $_sidecar_version != Maven $2 ($1)" >&2
    return 1
  fi
  if [ "$_sidecar_file" != "$3" ]; then
    echo "[identity] FAIL: manifest artifactFilename $_sidecar_file != $3 ($1)" >&2
    return 1
  fi
  case "$_sidecar_commit" in
    *[!0-9a-f]* | "") echo "[identity] FAIL: manifest sourceCommit malformed ($1)" >&2; return 1 ;;
  esac
  if [ "${#_sidecar_commit}" -ne 40 ]; then
    echo "[identity] FAIL: manifest sourceCommit malformed ($1)" >&2
    return 1
  fi
  _actual_sha="$(release_identity_sha256 "$4")" || return 1
  if [ "$_actual_sha" != "$_sidecar_sha" ]; then
    echo "[identity] FAIL: JAR SHA $_actual_sha != manifest $_sidecar_sha ($4)" >&2
    return 1
  fi
}

# Extract one required top-level field from a manifest JSON (missing/blank fails).
release_identity_manifest_field() {
  _value="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get(sys.argv[2],""))' \
    "$1" "$2" 2>/dev/null || true)"
  if [ -z "$_value" ]; then
    echo "[identity] FAIL: manifest $1 lacks field $2" >&2
    return 1
  fi
  printf '%s\n' "$_value"
}

# Report/manifest source-commit cross-check (Refs #456 R4, challenge cases 4/6):
# the acceptance report must prove it tested THIS candidate's source.
# Args: reportJson manifestSourceCommit
release_identity_cross_check_source_commit() {
  _report_commit="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("sourceCommit",""))' \
    "$1" 2>/dev/null || true)"
  if [ -z "$_report_commit" ]; then
    echo "[identity] FAIL: report $1 lacks sourceCommit" >&2
    return 1
  fi
  case "$_report_commit" in
    *[!0-9a-f]* | "") echo "[identity] FAIL: report $1 has malformed sourceCommit" >&2; return 1 ;;
  esac
  if [ "${#_report_commit}" -ne 40 ]; then
    echo "[identity] FAIL: report $1 has malformed sourceCommit" >&2
    return 1
  fi
  if [ "$_report_commit" != "$2" ]; then
    echo "[identity] FAIL: report sourceCommit $_report_commit != manifest $2 ($1)" >&2
    return 1
  fi
}

# Resolve the exact release-candidate JAR deterministically (Refs #458).
#
# Args: explicitJarOrEmpty expectedFilename [candidateDir]
# Prints the canonical absolute path on stdout. Fails closed when:
#   - the explicit input is missing / not a regular file / wrong filename;
#   - the default exact file candidateDir/expectedFilename is missing.
# Never falls back to mtime globbing (`ls -t | head -1`): a stale or
# wrong-version file with a newer mtime is never selected, and multiple
# coexisting candidates do not resolve by recency. Callers keep Maven as
# the version truth: expectedFilename is "<artifactId>-<version>.jar"
# derived via release_identity_expected_basename. MUST be called before
# the caller changes directory so relative inputs anchor to the caller's
# cwd (same contract as release_identity_canonicalize).
release_identity_resolve_candidate_jar() {
  _explicit="${1:-}"
  _expected="${2:-}"
  _dir="${3:-target/release-candidate}"
  if [ -z "$_expected" ]; then
    echo "[identity] FAIL: expected filename is required" >&2
    return 1
  fi
  if [ -n "$_explicit" ]; then
    _canon="$(release_identity_canonicalize "$_explicit")" || return 1
    release_identity_assert_regular_file "$_canon" "explicit --jar candidate" || return 1
    release_identity_assert_exact_filename "$_canon" "$_expected" || return 1
    printf '%s\n' "$_canon"
    return 0
  fi
  _exact="$_dir/$_expected"
  if [ ! -f "$_exact" ]; then
    echo "[identity] FAIL: exact candidate $_exact missing; refusing to guess." >&2
    echo "[identity] present files (if any):" >&2
    ls "$_dir"/llm-wiki-km-*.jar "$_dir"/*.jar 2>/dev/null >&2 || true
    echo "[identity] build the candidate first (or pass --jar with the exact file)." >&2
    return 1
  fi
  _canon="$(release_identity_canonicalize "$_exact")" || return 1
  release_identity_assert_exact_filename "$_canon" "$_expected" || return 1
  printf '%s\n' "$_canon"
}

# Verify the sidecar manifest for the exact candidate when present
# (Refs #458, challenge cases 3/4).
#
# Args: candidateDir expectedBasename expectedFilename expectedVersion candidateJar
# Returns 0 when the sidecar is absent (warn-only plain-package flow: JAR
# selection stays exact but provenance is unverified, and
# check-release-readiness.sh still refuses READY without it) or when the
# sidecar describes this exact JAR; returns 1 on any mismatch so the
# caller never enters the smoke body with a swapped/stale artifact.
release_identity_verify_candidate_sidecar_if_present() {
  _dir="${1:-}"
  _basename="${2:-}"
  _filename="${3:-}"
  _version="${4:-}"
  _jar="${5:-}"
  if [ -z "$_dir" ] || [ -z "$_basename" ] || [ -z "$_filename" ] || [ -z "$_version" ] || [ -z "$_jar" ]; then
    echo "[identity] FAIL: candidate dir/basename/filename/version/jar are required" >&2
    return 1
  fi
  if [ ! -f "$_dir/$_basename-manifest.json" ]; then
    echo "[identity] WARN: no release sidecar manifest at $_dir/$_basename-manifest.json; provenance sidecar not verified here (readiness still refuses READY without it)." >&2
    return 0
  fi
  _manifest="$(release_identity_canonicalize "$_dir/$_basename-manifest.json")" || return 1
  release_identity_verify_sidecar "$_manifest" "$_version" "$_filename" "$_jar" || return 1
}

# Require exactly one file matching dir/pattern (no head-1 guessing between
# stale and current candidates, Refs #456 R4).
release_identity_require_single_file() {
  _matches="$(find "$1" -maxdepth 1 -name "$2" 2>/dev/null | sort)"
  _count="$(printf '%s' "$_matches" | grep -c . || true)"
  if [ "$_count" -ne 1 ]; then
    echo "[identity] FAIL: expected exactly one $1/$2 but found $_count" >&2
    if [ -n "$_matches" ]; then
      printf '%s\n' "$_matches" >&2
    fi
    return 1
  fi
  printf '%s\n' "$_matches"
}
