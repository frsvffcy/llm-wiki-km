#!/bin/sh
# Release readiness gate (Refs #430 §I, §J; consumes #429 gate).
#
# Evaluates the release-candidate evidence and writes a bounded verdict:
#   target/release-candidate/READINESS.md
#
# Verdicts:
#   READY_TO_PUBLISH — all required evidence present, hygiene PASS, no #429
#                      FAIL, no #429 SKIP blocking FULL-GO, manifest/bundle
#                      consistent. Publishing still requires A2 human auth.
#   CONDITIONAL      — evidence produced honestly but a bounded SKIP blocks
#                      FULL-GO (e.g. vector native absent). Never reported READY.
#   NO-GO            — missing evidence, hygiene failure, or any FAIL.
#
# Exit codes: 0 when evaluation completes (any verdict); 1 on evaluation
# failure (missing toolchain, unreadable evidence — never produces READY);
# --enforce-ready exits 2 when verdict is not READY_TO_PUBLISH (for publish
# gates; ordinary runs must not use it to fake-green a CONDITIONAL).
#
# Failure / cancelled / skipped CI jobs must never produce READY: this script
# only writes READY_TO_PUBLISH after all checks pass in the same execution.
set -eu

ENFORCE=0
if [ "${1:-}" = "--enforce-ready" ]; then
  ENFORCE=1
fi

OUT_DIR="target/release-candidate"
READINESS="$OUT_DIR/READINESS.md"

fail_eval() { echo "[readiness] FAIL: $1" >&2; exit 1; }

[ -d "$OUT_DIR" ] || fail_eval "release-candidate directory missing; run scripts/build-release-candidate.sh first"
MANIFEST_JSON="$(ls "$OUT_DIR"/*-manifest.json 2>/dev/null | head -1 || true)"
BUNDLE="$(ls "$OUT_DIR"/*-bundle.tar.gz 2>/dev/null | head -1 || true)"
[ -n "$MANIFEST_JSON" ] || fail_eval "manifest missing"
[ -n "$BUNDLE" ] || fail_eval "bundle missing"

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
if [ ! -d "$EVIDENCE_DIR" ]; then
  VERDICT="CONDITIONAL"
  REASONS="no #429 release-evidence reports (run scripts/run-product-acceptance.sh --skip-build after building the candidate)"
else
  REPORTS="$(find "$EVIDENCE_DIR" -name 'v0.1.0-product-acceptance.json' 2>/dev/null || true)"
  if [ -z "$REPORTS" ]; then
    VERDICT="CONDITIONAL"
    REASONS="no #429 acceptance JSON reports under $EVIDENCE_DIR"
  else
    # Simpler: iterate files in shell, use python per file.
    for report in $REPORTS; do
      OVERALL="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("overall","unknown"))' "$report")"
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
