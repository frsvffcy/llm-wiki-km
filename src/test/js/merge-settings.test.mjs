import assert from "node:assert/strict";
import test from "node:test";

import {
  MERGE_SETTING_ENUMS,
  RECORDED_BASELINE,
  auditMergeSettings,
  fetchMergeSettings,
  resolveGuardedSources,
} from "../../../scripts/audit-merge-settings.mjs";

const GUARDED_SURFACES = ["PR_TITLE", "PR_BODY", "COMMIT_MESSAGE", "KEYWORD_FREE_CONSTANT"];

test("current production merge settings pass the audit and map to guarded surfaces", () => {
  const { errors } = auditMergeSettings(RECORDED_BASELINE.values);

  assert.deepEqual(errors, []);
  assert.deepEqual(resolveGuardedSources("merge_commit_title", "MERGE_MESSAGE"), [
    "KEYWORD_FREE_CONSTANT",
  ]);
  assert.deepEqual(resolveGuardedSources("merge_commit_message", "PR_TITLE"), ["PR_TITLE"]);
  assert.deepEqual(resolveGuardedSources("squash_merge_commit_title", "COMMIT_OR_PR_TITLE"), [
    "PR_TITLE",
    "COMMIT_MESSAGE",
  ]);
  assert.deepEqual(resolveGuardedSources("squash_merge_commit_message", "COMMIT_MESSAGES"), [
    "COMMIT_MESSAGE",
  ]);
});

test("every official enum value maps to guarded surfaces or a keyword-free constant", () => {
  // Structural coverage proof (#357): any future settings change that stays inside
  // GitHub's official enums still feeds only guarded inputs into merge-generated text.
  for (const [field, rule] of Object.entries(MERGE_SETTING_ENUMS)) {
    if (rule.kind !== "enum") {
      continue;
    }
    for (const value of rule.values) {
      const sources = resolveGuardedSources(field, value);
      assert.ok(Array.isArray(sources), `${field} = ${value} 必須可建模`);
      for (const source of sources) {
        assert.ok(GUARDED_SURFACES.includes(source), `${field} = ${value} 映射到未知 surface ${source}`);
      }
    }
  }
});

test("a setting value outside the official enums fails closed", () => {
  const mutated = { ...RECORDED_BASELINE.values, merge_commit_message: "SOMETHING_NEW" };
  const { errors } = auditMergeSettings(mutated);

  assert.equal(errors.length, 1);
  assert.match(errors[0], /無法建模.*fail-closed/u);
});

test("a missing or non-boolean merge method flag fails closed", () => {
  const mutated = { ...RECORDED_BASELINE.values, allow_rebase_merge: undefined };
  const { errors } = auditMergeSettings(mutated);

  assert.match(errors.join("\n"), /allow_rebase_merge.*無法建模/u);
});

test("any deviation from the recorded baseline is governance-visible, never silent", () => {
  const mutated = {
    ...RECORDED_BASELINE.values,
    squash_merge_commit_message: "PR_BODY",
    merge_commit_title: "PR_TITLE",
  };
  const { errors } = auditMergeSettings(mutated);

  assert.equal(errors.length, 2);
  assert.match(errors.join("\n"), /不得 silent drift/u);
  assert.match(errors.join("\n"), /RECORDED_BASELINE/u);
});

test("empty settings fail closed instead of auditing nothing", () => {
  const { errors } = auditMergeSettings({});

  assert.ok(errors.length >= Object.keys(MERGE_SETTING_ENUMS).length);
});

test("settings retrieval failure fails closed", async () => {
  const fetcher = fetchMergeSettings(undefined, async () => ({ ok: false, status: 503 }));
  const result = await fetcher("frsvffcy/llm-wiki-km");

  assert.deepEqual(result, { ok: false, reason: "GitHub API HTTP 503" });
});

test("a minimal repository payload is retried with a cache-buster before succeeding", async () => {
  // GET /repos is CDN-cached without Vary: Authorization; an unauthenticated minimal body
  // (no merge settings fields) can be served to an authenticated request (#357 CI evidence).
  const calls = [];
  const fetchImpl = async (url) => {
    calls.push(String(url));
    if (calls.length === 1) {
      return { ok: true, json: async () => ({ name: "llm-wiki-km", private: false }) };
    }
    return { ok: true, json: async () => ({ ...RECORDED_BASELINE.values }) };
  };
  const result = await fetchMergeSettings("read-only-token", fetchImpl)("frsvffcy/llm-wiki-km");

  assert.equal(result.ok, true);
  assert.deepEqual(result.settings, RECORDED_BASELINE.values);
  assert.equal(calls.length, 2);
  assert.match(calls[1], /\?cache_bust=/u);
});

test("a persistently unusable payload fails closed with bounded diagnostics", async () => {
  const fetchImpl = async () => ({
    ok: true,
    json: async () => ({
      message: "Resource not accessible by integration",
      documentation_url: "https://docs.github.com",
    }),
  });
  const result = await fetchMergeSettings("read-only-token", fetchImpl)("frsvffcy/llm-wiki-km");

  assert.equal(result.ok, false);
  assert.match(result.reason, /缺少 governed fields/u);
  assert.match(result.reason, /message="Resource not accessible by integration"/u);
  assert.match(result.reason, /keys=\[documentation_url,message\]/u);
});

test("settings retrieval extracts exactly the governed fields", async () => {
  const fetcher = fetchMergeSettings("read-only-token", async () => ({
    ok: true,
    json: async () => ({
      private: true,
      default_branch: "main",
      ...RECORDED_BASELINE.values,
      squash_merge_commit_message: "COMMIT_MESSAGES",
    }),
  }));
  const result = await fetcher("frsvffcy/llm-wiki-km");

  assert.equal(result.ok, true);
  assert.deepEqual(result.settings, RECORDED_BASELINE.values);
  assert.equal("private" in result.settings, false);
});
