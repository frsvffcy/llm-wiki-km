#!/usr/bin/env node

import { pathToFileURL } from "node:url";

// Merge-settings governance contract (#357). The PR metadata guard scans the PR title, the
// PR body and every source commit message with one closing-reference grammar. The claim
// "every GitHub-generated merge/squash/rebase commit text is therefore covered" is only
// true relative to the repository's merge settings, so those settings are pinned here and
// audited against GitHub's official REST schema enums:
//   github/rest-api-description descriptions/api.github.com.json → components.schemas
//   .full-repository (verified 2026-09-12):
//     merge_commit_title           PR_TITLE | MERGE_MESSAGE
//     merge_commit_message         PR_BODY | PR_TITLE | BLANK
//     squash_merge_commit_title    PR_TITLE | COMMIT_OR_PR_TITLE
//     squash_merge_commit_message  PR_BODY | COMMIT_MESSAGES | BLANK
// Unknown values mean GitHub introduced a text source the guard mapping cannot model —
// the audit fails closed. Any deviation from RECORDED_BASELINE is a governance-visible
// change: the baseline and docs/development/github-delivery-governance.md must be
// re-confirmed through a PR, never silently.
export const MERGE_SETTING_ENUMS = {
  allow_merge_commit: { kind: "boolean" },
  allow_squash_merge: { kind: "boolean" },
  allow_rebase_merge: { kind: "boolean" },
  merge_commit_title: { kind: "enum", values: ["PR_TITLE", "MERGE_MESSAGE"] },
  merge_commit_message: { kind: "enum", values: ["PR_BODY", "PR_TITLE", "BLANK"] },
  squash_merge_commit_title: { kind: "enum", values: ["PR_TITLE", "COMMIT_OR_PR_TITLE"] },
  squash_merge_commit_message: { kind: "enum", values: ["PR_BODY", "COMMIT_MESSAGES", "BLANK"] },
};

export const RECORDED_BASELINE = {
  recordedAt: "2026-09-12",
  evidence: "gh api repos/frsvffcy/llm-wiki-km；enum 對照 github/rest-api-description api.github.com.json full-repository schema",
  values: {
    allow_merge_commit: true,
    allow_squash_merge: true,
    allow_rebase_merge: true,
    merge_commit_title: "MERGE_MESSAGE",
    merge_commit_message: "PR_TITLE",
    squash_merge_commit_title: "COMMIT_OR_PR_TITLE",
    squash_merge_commit_message: "COMMIT_MESSAGES",
  },
};

// Text surfaces the PR metadata guard scans: PR_TITLE / PR_BODY / COMMIT_MESSAGE.
// KEYWORD_FREE_CONSTANT marks generated text that cannot contain a closing reference by
// construction ("Merge pull request #N from <branch>" — branch names cannot contain "#",
// and the line carries no closing keyword).
export const GUARDED_SOURCE_MAPPING = {
  merge_commit_title: {
    MERGE_MESSAGE: ["KEYWORD_FREE_CONSTANT"],
    PR_TITLE: ["PR_TITLE"],
  },
  merge_commit_message: {
    PR_BODY: ["PR_BODY"],
    PR_TITLE: ["PR_TITLE"],
    BLANK: [],
  },
  squash_merge_commit_title: {
    PR_TITLE: ["PR_TITLE"],
    COMMIT_OR_PR_TITLE: ["PR_TITLE", "COMMIT_MESSAGE"],
  },
  squash_merge_commit_message: {
    PR_BODY: ["PR_BODY"],
    COMMIT_MESSAGES: ["COMMIT_MESSAGE"],
    BLANK: [],
  },
};

/**
 * Audits repository merge settings against the official enums and the recorded baseline.
 * Returns { errors } — non-empty means the coverage claim is unprovable or the settings
 * drifted from the governance record, and the caller must fail closed.
 */
export function auditMergeSettings(settings = {}) {
  const errors = [];
  for (const [field, rule] of Object.entries(MERGE_SETTING_ENUMS)) {
    const actual = settings[field];
    const recorded = RECORDED_BASELINE.values[field];
    const officialValues = rule.kind === "enum" ? rule.values.join(" | ") : "boolean";
    const unmodelable = rule.kind === "boolean" ? typeof actual !== "boolean" : !rule.values.includes(actual);
    if (unmodelable) {
      errors.push(
        `repository merge setting「${field}」實際值「${String(actual)}」無法建模（官方 enum：${officialValues}）；coverage mapping 無法建立，依 fail-closed 政策擋下。`,
      );
      continue;
    }
    if (actual !== recorded) {
      errors.push(
        `repository merge setting「${field}」已由 ${String(recorded)} 變更為 ${String(actual)}；merge-generated text 的 coverage mapping 必須重新確認，請透過 PR 更新 scripts/audit-merge-settings.mjs 的 RECORDED_BASELINE 與 docs/development/github-delivery-governance.md，不得 silent drift。`,
      );
    }
  }
  return { errors };
}

/** Resolves which guarded text surfaces a setting value feeds; null = un-modelable. */
export function resolveGuardedSources(field, value) {
  return GUARDED_SOURCE_MAPPING[field]?.[value] ?? null;
}

/**
 * Fetches the live repository merge settings (bounded timeout, read-only). GET /repos is
 * CDN-cached with a Vary that excludes Authorization, so an unauthenticated "minimal
 * repository" body (without the merge settings fields) can be served to an authenticated
 * request; a payload missing any governed field is therefore treated as unusable evidence
 * and retried once with a cache-busting query parameter before failing closed. Any
 * retrieval or parse failure returns { ok: false } so the caller fails closed instead of
 * auditing nothing and passing.
 */
export function fetchMergeSettings(token = process.env.GITHUB_TOKEN, fetchImpl = fetch) {
  return async (repository) => {
    if (!repository) {
      return { ok: false, reason: "缺少 repository full_name" };
    }
    const headers = {
      Accept: "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28",
      "User-Agent": "llm-wiki-km-merge-settings-guard",
    };
    if (token) {
      headers.Authorization = `Bearer ${token}`;
    }

    let lastFailure = "unknown reason";
    for (let attempt = 0; attempt < 2; attempt++) {
      const url = `https://api.github.com/repos/${repository}${
        attempt === 0 ? "" : `?cache_bust=${Date.now()}-${Math.random().toString(36).slice(2)}`
      }`;
      let response;
      try {
        response = await fetchImpl(url, { headers, signal: AbortSignal.timeout(10_000) });
      } catch (error) {
        lastFailure = `GitHub API request failed: ${error.name}`;
        continue;
      }
      if (!response.ok) {
        lastFailure = `GitHub API HTTP ${response.status}`;
        continue;
      }
      let payload;
      try {
        payload = await response.json();
      } catch (error) {
        lastFailure = `GitHub API response parse failed: ${error.name}`;
        continue;
      }
      const settings = Object.fromEntries(
        Object.keys(MERGE_SETTING_ENUMS).map((field) => [field, payload[field]]),
      );
      if (Object.values(settings).every((value) => value !== undefined)) {
        return { ok: true, settings };
      }
      const diagnostic = [
        typeof payload?.message === "string" ? `message="${payload.message.slice(0, 120)}"` : null,
        `keys=[${Object.keys(payload ?? {}).slice(0, 10).sort().join(",")}]`,
      ]
        .filter(Boolean)
        .join("；");
      lastFailure = `回應缺少 governed fields（${diagnostic}）；可能是 unauthenticated minimal repository view 被 CDN 快取服務`;
    }
    return { ok: false, reason: lastFailure };
  };
}

async function main() {
  const repository = process.env.GITHUB_REPOSITORY ?? process.argv[2];
  const fetched = await fetchMergeSettings()(repository);
  if (!fetched.ok) {
    console.error(`Merge settings audit 無法取得 repository 設定（${fetched.reason}）；coverage 契約無法證明，依 fail-closed 政策擋下。`);
    process.exitCode = 1;
    return;
  }

  const { errors } = auditMergeSettings(fetched.settings);
  if (errors.length > 0) {
    console.error("Merge settings audit failed:");
    for (const error of errors) {
      console.error(`- ${error}`);
    }
    process.exitCode = 1;
    return;
  }

  console.log(
    `Merge settings audit passed（baseline ${RECORDED_BASELINE.recordedAt}）：`,
  );
  for (const [field, rule] of Object.entries(MERGE_SETTING_ENUMS)) {
    const value = fetched.settings[field];
    const sources = rule.kind === "enum" ? resolveGuardedSources(field, value).join(" + ") || "（無文本）" : "merge 方法開關";
    console.log(`- ${field} = ${value} → guarded surfaces: ${sources}`);
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  await main();
}
