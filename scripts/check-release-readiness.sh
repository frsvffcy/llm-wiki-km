#!/bin/sh
# Release readiness gate (Refs #430 §I, §J; consumes #429 gate).
#
# Evaluates the release-candidate evidence and writes a bounded verdict:
#   target/release-candidate/READINESS.md
#
# Verdicts:
#   READY_TO_PUBLISH — all required evidence present, hygiene PASS, no #429
#                      FAIL, no #429 SKIP blocking FULL-GO, manifest/bundle
#                      consistent, and every comparable identity matches
#                      (manifest version == Maven, candidate SHA == manifest,
#                      each report sourceCommit == manifest sourceCommit).
#                      Publishing still requires A2 human auth.
#   CONDITIONAL      — evidence produced honestly but a bounded SKIP blocks
#                      FULL-GO (e.g. vector native absent). Never reported READY.
#   NO-GO            — missing evidence, hygiene failure, any FAIL, or any
#                      identity missing / malformed / mismatch (Refs #456 R4).
#
# Exit codes: 0 when evaluation completes (any verdict); 1 on evaluation
# failure (missing toolchain, unreadable evidence — never produces READY);
# --enforce-ready exits 2 when verdict is not READY_TO_PUBLISH (for publish
# gates; ordinary runs must not use it to fake-green a CONDITIONAL).
#
# Failure / cancelled / skipped CI jobs must never produce READY: this script
# only writes READY_TO_PUBLISH after all checks pass in the same execution.
# Same-runner / same-checkout coincidence is never trusted: report and
# manifest source identities are compared field-by-field every run.
set -eu

. "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/release-identity.sh"

ENFORCE=0
if [ "${1:-}" = "--enforce-ready" ]; then
  ENFORCE=1
fi

OUT_DIR="target/release-candidate"
READINESS="$OUT_DIR/READINESS.md"

fail_eval() { echo "[readiness] FAIL: $1" >&2; exit 1; }

[ -d "$OUT_DIR" ] || fail_eval "release-candidate directory missing; run scripts/build-release-candidate.sh first"
# Exactly one manifest and one bundle: never head-1 guess between a stale
# and a current candidate (Refs #456 R4, challenge case 7).
MANIFEST_JSON="$(release_identity_require_single_file "$OUT_DIR" "*-manifest.json")" \
  || fail_eval "candidate manifest missing or ambiguous"
BUNDLE="$(release_identity_require_single_file "$OUT_DIR" "*-bundle.tar.gz")" \
  || fail_eval "candidate bundle missing or ambiguous"

BASENAME="$(basename "$MANIFEST_JSON" "-manifest.json")"
sh scripts/check-release-bundle-hygiene.sh --bundle "$BUNDLE" --basename "$BASENAME" \
  || fail_eval "bundle hygiene failed"

# Release notes + native matrix must be present and honestly scoped.
NOTES="$OUT_DIR/${BASENAME}-release-notes.md"
MATRIX="$OUT_DIR/${BASENAME}-native-matrix.md"
[ -f "$NOTES" ] || fail_eval "release notes missing from bundle source"
[ -f "$MATRIX" ] || fail_eval "native matrix missing from bundle source"
for token in "SUPPORTED" "CANDIDATE" "NOT SUPPORTED"; do
  grep -q "$token" "$NOTES" || fail_eval "release notes missing section $token"
done
# Candidate-promotion scope is locked statically by ReleaseCandidateContractTest
# (notes must list Mode 2 / OCR / MCP-write under CANDIDATE / NOT SUPPORTED,
# never as SUPPORTED); the runtime gate here only requires the sections exist.

# #429 gate consumption: overall FULL-GO requires every journey PASS.
# Any FAIL -> NO-GO; any SKIP or missing reports -> CONDITIONAL (blocks READY).
EVIDENCE_DIR="target/release-evidence"
VERDICT="READY_TO_PUBLISH"
REASONS=""

# --- Refs #456 R4: every comparable identity must agree ----------------------
# manifest.version == Maven project.version (stale-candidate fail-closed),
# manifest artifactFilename == this candidate file, current artifact SHA ==
# manifest artifactSha256 (swapped-JAR fail-closed), and each acceptance
# report sourceCommit == manifest sourceCommit (cross-checkout fail-closed).
# Any missing / malformed / mismatch below forces NO-GO, never READY.
_downgrade() {
  VERDICT="NO-GO"
  REASONS="$REASONS $1;"
}
MANIFEST_VERSION="$(release_identity_manifest_field "$MANIFEST_JSON" version)" \
  || _downgrade "manifest lacks version"
MANIFEST_FILE="$(release_identity_manifest_field "$MANIFEST_JSON" artifactFilename)" \
  || _downgrade "manifest lacks artifactFilename"
MANIFEST_SHA="$(release_identity_manifest_field "$MANIFEST_JSON" artifactSha256)" \
  || _downgrade "manifest lacks artifactSha256"
MANIFEST_COMMIT="$(release_identity_manifest_field "$MANIFEST_JSON" sourceCommit)" \
  || _downgrade "manifest lacks sourceCommit"
if [ -n "${MANIFEST_COMMIT:-}" ]; then
  case "$MANIFEST_COMMIT" in
    *[!0-9a-f]* | "") _downgrade "manifest sourceCommit malformed" ;;
    *) [ "${#MANIFEST_COMMIT}" -eq 40 ] || _downgrade "manifest sourceCommit malformed" ;;
  esac
fi
PROJECT_VERSION="$(mvn --batch-mode -q help:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null || true)"
if [ -z "$PROJECT_VERSION" ]; then
  fail_eval "cannot derive Maven project version for identity comparison"
fi
if [ -n "${MANIFEST_VERSION:-}" ] && [ "$MANIFEST_VERSION" != "$PROJECT_VERSION" ]; then
  _downgrade "manifest version $MANIFEST_VERSION != Maven $PROJECT_VERSION (stale candidate)"
fi
if [ -n "${MANIFEST_FILE:-}" ] && [ "$MANIFEST_FILE" != "${BASENAME}.jar" ]; then
  _downgrade "manifest artifactFilename $MANIFEST_FILE != ${BASENAME}.jar"
fi
if [ -n "${MANIFEST_FILE:-}" ]; then
  if [ ! -f "$OUT_DIR/$MANIFEST_FILE" ]; then
    _downgrade "candidate artifact $MANIFEST_FILE missing from $OUT_DIR"
  elif [ -n "${MANIFEST_SHA:-}" ]; then
    ACTUAL_SHA="$(release_identity_sha256 "$OUT_DIR/$MANIFEST_FILE")" || _downgrade "cannot hash candidate artifact"
    if [ -n "${ACTUAL_SHA:-}" ] && [ "$ACTUAL_SHA" != "$MANIFEST_SHA" ]; then
      _downgrade "candidate SHA $ACTUAL_SHA != manifest $MANIFEST_SHA (artifact changed without manifest update)"
    fi
  fi
fi
if [ ! -d "$EVIDENCE_DIR" ]; then
  VERDICT="CONDITIONAL"
  REASONS="no #429 release-evidence reports (run scripts/run-product-acceptance.sh --skip-build after building the candidate)"
else
  # Version-agnostic (Refs #454 §A): accept v0.1.0 and v0.1.1+ report names;
  # the version truth lives in pom.xml + manifest, never in this gate constant.
  REPORTS="$(find "$EVIDENCE_DIR" -name '*-product-acceptance.json' 2>/dev/null || true)"
  if [ -z "$REPORTS" ]; then
    VERDICT="CONDITIONAL"
    REASONS="no #429 acceptance JSON reports under $EVIDENCE_DIR"
  else
    # Simpler: iterate files in shell, use python per file.
    for report in $REPORTS; do
      OVERALL="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("overall","unknown"))' "$report")"
      # Refs #456 R4: the report must prove it tested this candidate's
      # source. Missing / malformed / mismatched sourceCommit is NO-GO,
      # even when the journey verdict itself is FULL-GO.
      if [ -n "${MANIFEST_COMMIT:-}" ]; then
        if ! release_identity_cross_check_source_commit "$report" "$MANIFEST_COMMIT"; then
          VERDICT="NO-GO"
          REASONS="$REASONS $report source identity mismatch (see log);"
        fi
      fi
      case "$report" in
        *acceptance-baseline*)
          # Baseline is a provider-free subset by design: governed-mutation and
          # graph SKIP defer to the full-capability profile (see #429). It blocks
          # READY only on FAIL, never on its designed SKIP.
          case "$OVERALL" in
            FULL-GO|CONDITIONAL) ;;
            *)
              VERDICT="NO-GO"
              REASONS="$REASONS $report=$OVERALL;"
              ;;
          esac
          ;;
        *)
          case "$OVERALL" in
            FULL-GO) ;;
            CONDITIONAL)
              VERDICT="CONDITIONAL"
              REASONS="$REASONS $report=CONDITIONAL (a SKIP blocks FULL-GO; see report journeys);"
              ;;
            *)
              VERDICT="NO-GO"
              REASONS="$REASONS $report=$OVERALL;"
              ;;
          esac
          ;;
      esac
      # Secret hygiene on reports (assigned values / key blocks only; journey
      # ids like "ask-provider-disabled-typed" must never trip the scanner).
      if grep -Eq 'api[_-]?key[=:][^[:space:]]|BEGIN .*(PRIVATE KEY|RSA PRIVATE|OPENSSH PRIVATE)|gh[pousr]_[A-Za-z0-9]{8,}|(^|[^A-Za-z])sk-[A-Za-z0-9]{20,}|xox[bap]-[A-Za-z0-9]+' "$report"; then
        VERDICT="NO-GO"
        REASONS="$REASONS $report leaks credential material;"
      fi
    done
  fi
fi

# Manifest dirty tree blocks READY (not a clean checkout).
if python3 -c 'import json,sys; sys.exit(0 if json.load(open(sys.argv[1])).get("dirtyTree") is True else 1)' "$MANIFEST_JSON" 2>/dev/null; then
  if [ "$VERDICT" = "READY_TO_PUBLISH" ]; then
    VERDICT="CONDITIONAL"
  fi
  REASONS="$REASONS manifest records a dirty tree (local iteration only);"
fi

CREATED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
{
  echo "# Release readiness"
  echo ""
  echo "- verdict: **$VERDICT**"
  echo "- candidate: \`$BASENAME\`"
  echo "- manifest: \`$(basename "$MANIFEST_JSON")\`"
  echo "- bundle: \`$(basename "$BUNDLE")\`"
  echo "- evaluatedAt: \`$CREATED_AT\`"
  echo "- procedure: \`release-candidate-procedure-v1\`"
  echo ""
  if [ -n "$REASONS" ]; then
    echo "## Reasons"
    echo ""
    echo "$REASONS" | tr ';' '\n' | sed '/^$/d; s/^/- /'
    echo ""
  fi
  echo "READY_TO_PUBLISH never authorizes public tag / GitHub Release creation:"
  echo "publication stays A2 explicit human authorization (see issue #430 §I)."
  echo ""
} > "$READINESS"
echo "[readiness] verdict: $VERDICT (see $READINESS)"

if [ "$ENFORCE" -eq 1 ] && [ "$VERDICT" != "READY_TO_PUBLISH" ]; then
  echo "[readiness] refusing READY enforcement (verdict=$VERDICT)" >&2
  exit 2
fi
exit 0
