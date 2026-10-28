#!/usr/bin/env bash
set -euo pipefail

required=(ANSWER_PROVIDER_MODEL OPENAI_API_KEY)
if [[ "${ANSWER_PROVIDER_ENABLED:-}" != "true" ]]; then
  echo "ERROR: ANSWER_PROVIDER_ENABLED 必須設為 true，才會執行真實 Provider 量測。" >&2
  exit 2
fi
for name in "${required[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    echo "ERROR: 缺少必要環境變數 $name。" >&2
    exit 2
  fi
done

: "${LLM_WIKI_LIVE_PROVIDER_REPETITIONS:=2}"
export LLM_WIKI_LIVE_PROVIDER_REPETITIONS

run_id="$(od -An -N16 -tx1 /dev/urandom | tr -d '[:space:]')"
source_revision="$(git rev-parse HEAD 2>/dev/null || true)"
tree_state="UNOBSERVED"
if tree_status="$(git status --porcelain 2>/dev/null)"; then
  if [[ -z "$tree_status" ]]; then tree_state="CLEAN"; else tree_state="DIRTY"; fi
fi
report="target/quality-reports/grounded-answer-live-provider-v1.md"
trap 'status=$?; if [[ -f "$report" ]] && grep -Fqx -- "- runId: $run_id" "$report"; then echo; cat "$report"; else echo "本次未產生可識別的新報告；既有報告不代表本次結果。" >&2; fi; exit $status' EXIT

mvn --batch-mode test \
  -Plive-provider \
  -Dmeasurement.runId="$run_id" \
  -Dmeasurement.sourceRevision="$source_revision" \
  -Dmeasurement.treeState="$tree_state" \
  -Dtest=GroundedAnswerLiveProviderMeasurementTest
