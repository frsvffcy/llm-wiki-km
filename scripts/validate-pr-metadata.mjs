#!/usr/bin/env node

import { readFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";

const CLOSING_KEYWORD_PATTERN = /\b(?:closes|fixes|resolves)\s+#(\d+)\b/giu;
const ISSUE_REFERENCE_PATTERN = /(?:^|[^\w])#(\d+)\b/gu;
const EXCEPTION_PATTERN = /^PR-Metadata-Exception:\s*(stacked-pr|non-issue-driven)\s*$/gimu;
const MAX_ISSUE_REFERENCES = 20;

export function semanticMarkdown(markdown = "") {
  return markdown
    .replace(/<!--[\s\S]*?-->/gu, " ")
    .replace(/```[\s\S]*?```/gu, " ")
    .replace(/~~~[\s\S]*?~~~/gu, " ")
    .replace(/`[^`\r\n]*`/gu, " ");
}

export function inspectPrBody(body = "") {
  const semanticBody = semanticMarkdown(body);
  const closingIssueNumbers = [
    ...new Set([...semanticBody.matchAll(CLOSING_KEYWORD_PATTERN)].map((match) => Number(match[1]))),
  ];
  const referencedIssueNumbers = [
    ...new Set([...semanticBody.matchAll(ISSUE_REFERENCE_PATTERN)].map((match) => Number(match[1]))),
  ];
  const exceptions = new Set(
    [...semanticBody.matchAll(EXCEPTION_PATTERN)].map((match) => match[1].toLowerCase()),
  );

  return { closingIssueNumbers, referencedIssueNumbers, exceptions };
}

export async function validatePrMetadata(event, { issueLookup } = {}) {
  const errors = [];
  const base = event?.pull_request?.base?.ref;
  const body = event?.pull_request?.body ?? "";
  const repository = event?.repository?.full_name;
  const { closingIssueNumbers, referencedIssueNumbers, exceptions } = inspectPrBody(body);
  const isMainTarget = base === "main";
  const isStacked = exceptions.has("stacked-pr");
  const isNonIssueDriven = exceptions.has("non-issue-driven");

  if (!base) {
    errors.push("無法從 pull_request event 取得 base branch。");
  } else if (!isMainTarget && !isStacked) {
    errors.push(
      `PR base 是 ${base}，不是 main；stacked PR 必須加入獨立一行「PR-Metadata-Exception: stacked-pr」。`,
    );
  } else if (isMainTarget && isStacked) {
    errors.push("PR 已 target main，不得使用 stacked-pr 例外標記。");
  }

  if (isStacked && isNonIssueDriven) {
    errors.push("stacked-pr 與 non-issue-driven 例外標記不得同時使用。");
  }

  if (isMainTarget && closingIssueNumbers.length === 0 && !isNonIssueDriven) {
    if (referencedIssueNumbers.length > 0) {
      errors.push(
        "找到 Issue reference，但沒有有效 closing keyword；請使用「Closes #123」、「Fixes #123」或「Resolves #123」。",
      );
    } else {
      errors.push(
        "Issue-driven PR 必須提供 closing keyword；非 Issue-driven PR 必須加入獨立一行「PR-Metadata-Exception: non-issue-driven」。",
      );
    }
  }

  if (closingIssueNumbers.length > 0 && isNonIssueDriven) {
    errors.push("已有 closing keyword，不得同時宣告 non-issue-driven 例外。");
  }

  if (closingIssueNumbers.length > MAX_ISSUE_REFERENCES) {
    errors.push(`closing references 超過上限 ${MAX_ISSUE_REFERENCES}，請縮小 PR scope。`);
  } else if (closingIssueNumbers.length > 0) {
    if (!repository) {
      errors.push("無法從 pull_request event 取得 repository.full_name。");
    } else if (typeof issueLookup !== "function") {
      errors.push("Issue existence lookup 未設定，無法驗證 closing references。");
    } else {
      const results = await Promise.all(
        closingIssueNumbers.map(async (issueNumber) => ({
          issueNumber,
          result: await issueLookup(repository, issueNumber),
        })),
      );
      for (const { issueNumber, result } of results) {
        if (!result.exists) {
          errors.push(`Closing reference #${issueNumber} 不是同 repository 的有效 Issue（${result.reason}）。`);
        }
      }
    }
  }

  return {
    valid: errors.length === 0,
    errors: errors.slice(0, MAX_ISSUE_REFERENCES + 5),
    closingIssueNumbers,
    exception: isStacked ? "stacked-pr" : isNonIssueDriven ? "non-issue-driven" : null,
  };
}

export function githubIssueLookup(token = process.env.GITHUB_TOKEN, fetchImpl = fetch) {
  return async (repository, issueNumber) => {
    const headers = {
      Accept: "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28",
      "User-Agent": "llm-wiki-km-pr-metadata-guard",
    };
    if (token) {
      headers.Authorization = `Bearer ${token}`;
    }

    let response;
    try {
      response = await fetchImpl(`https://api.github.com/repos/${repository}/issues/${issueNumber}`, {
        headers,
        signal: AbortSignal.timeout(10_000),
      });
    } catch (error) {
      return { exists: false, reason: `GitHub API request failed: ${error.name}` };
    }

    if (!response.ok) {
      return { exists: false, reason: `GitHub API HTTP ${response.status}` };
    }

    let payload;
    try {
      payload = await response.json();
    } catch (error) {
      return { exists: false, reason: `GitHub API response parse failed: ${error.name}` };
    }
    if (payload.pull_request) {
      return { exists: false, reason: "reference points to a pull request" };
    }
    return { exists: true };
  };
}

async function main() {
  const eventPath = process.env.GITHUB_EVENT_PATH ?? process.argv[2];
  if (!eventPath) {
    throw new Error("缺少 GITHUB_EVENT_PATH 或 event JSON path argument。");
  }

  const event = JSON.parse(await readFile(eventPath, "utf8"));
  const result = await validatePrMetadata(event, { issueLookup: githubIssueLookup() });
  if (!result.valid) {
    console.error("PR metadata validation failed:");
    for (const error of result.errors) {
      console.error(`- ${error}`);
    }
    process.exitCode = 1;
    return;
  }

  const linkage =
    result.closingIssueNumbers.length > 0
      ? `closing Issue: ${result.closingIssueNumbers.map((number) => `#${number}`).join(", ")}`
      : `reviewed exception: ${result.exception}`;
  console.log(`PR metadata validation passed (${linkage}).`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  await main();
}
