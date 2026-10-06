#!/usr/bin/env bash
set -euo pipefail

required=(APP_AI_ANSWER_MODEL APP_AI_ANSWER_API_KEY)
if [[ "${APP_AI_ANSWER_ENABLED:-}" != "true" ]]; then
  echo "ERROR: APP_AI_ANSWER_ENABLED 必須設為 true，才會執行真實 Provider 量測。" >&2
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

report="target/quality-reports/grounded-answer-live-provider-v1.md"
trap 'status=$?; if [[ -f "$report" ]]; then echo; cat "$report"; fi; exit $status' EXIT

mvn --batch-mode test \
  -Plive-provider \
  -Dtest=GroundedAnswerLiveProviderMeasurementTest
