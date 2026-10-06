#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$ROOT/scripts/run-grounded-answer-live-provider-measurement.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

mkdir -p "$TMP/bin"
cat > "$TMP/bin/mvn" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" > "$FAKE_MVN_ARGS"
exit 0
EOF
chmod +x "$TMP/bin/mvn"

expect_fail() {
  local expected="$1"
  shift
  set +e
  output="$(env -i PATH="$TMP/bin:/usr/bin:/bin" HOME="$HOME" "$@" bash "$SCRIPT" 2>&1)"
  status=$?
  set -e
  [[ $status -eq 2 ]]
  [[ "$output" == *"$expected"* ]]
}

KEY_ENV_NAME="OPENAI_API""_KEY"
FAKE_KEY_VALUE="fixture""-key"

expect_fail "ANSWER_PROVIDER_ENABLED" \
  ANSWER_PROVIDER_MODEL="fixture-model" \
  "$KEY_ENV_NAME=$FAKE_KEY_VALUE"

expect_fail "ANSWER_PROVIDER_MODEL" \
  ANSWER_PROVIDER_ENABLED="true" \
  "$KEY_ENV_NAME=$FAKE_KEY_VALUE"

expect_fail "$KEY_ENV_NAME" \
  ANSWER_PROVIDER_ENABLED="true" \
  ANSWER_PROVIDER_MODEL="fixture-model"

env \
  FAKE_MVN_ARGS="$TMP/mvn-args.txt" \
  PATH="$TMP/bin:$PATH" \
  ANSWER_PROVIDER_ENABLED="true" \
  ANSWER_PROVIDER_MODEL="fixture-model" \
  "$KEY_ENV_NAME=$FAKE_KEY_VALUE" \
  LLM_WIKI_LIVE_PROVIDER_REPETITIONS="1" \
  bash "$SCRIPT"

grep -F -- "-Plive-provider" "$TMP/mvn-args.txt" >/dev/null
grep -F -- "-Dtest=GroundedAnswerLiveProviderMeasurementTest" "$TMP/mvn-args.txt" >/dev/null

echo "live-provider script env contract: PASS"
