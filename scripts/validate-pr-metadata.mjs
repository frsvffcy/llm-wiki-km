#!/usr/bin/env node

import { readFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";

// GitHub official closing-reference grammar (docs.github.com "Linking a pull request to
// an issue"): KEYWORD #ISSUE / KEYWORD OWNER/REPOSITORY#ISSUE / KEYWORD issue-URL, with
// colon and case variants. The PR body and every source commit message are scanned with
// this single grammar so the two surfaces cannot drift (#349): a merge must never
// auto-close an Issue ahead of the Completion Code Review Gate.
const CLOSING_KEYWORD_PATTERN =
  /\b((?:close[sd]?|fix(?:e[sd]?)?|resolve[sd]?))\s*:?\s*(?:(?:([\w.-]+)\/([\w.-]+))?#(\d+)|https:\/\/github\.com\/([\w.-]+)\/([\w.-]+)\/(?:issues|pull)\/(\d+))\b/giu;
const ISSUE_REFERENCE_PATTERN = /(?:^|[^\w])#(\d+)\b/gu;
const EXCEPTION_PATTERN = /^PR-Metadata-Exception:\s*(stacked-pr|non-issue-driven)\s*$/gimu;
const MAX_ISSUE_REFERENCES = 20;
const MAX_COMMIT_PAGES = 5;
const MAX_COMMIT_MESSAGES = MAX_COMMIT_PAGES * 100;

export function semanticMarkdown(markdown = "") {
  return markdown
    .replace(/<!--[\s\S]*?-->/gu, " ")
    .replace(/```[\s\S]*?```/gu, " ")
    .replace(/~~~[\s\S]*?~~~/gu, " ")
    .replace(/`[^`\r\n]*`/gu, " ");
}

/**
 * Single closing-reference authority shared by the PR body and commit-message scans.
 * Returns typed references ({ keyword, owner, repository, issueNumber, source }) so any
 * future policy change happens in exactly one place. Commit messages scan raw text:
 * fenced or code-like wording in a commit message still reaches GitHub's own closing
 * parser, so it must not enjoy the Markdown-strip privilege the PR body has.
 */
export function findClosingReferences(text = "", source = "PR_BODY", { stripMarkdown = true } = {}) {
  const scanned = stripMarkdown ? semanticMarkdown(text) : String(text);
  return [...scanned.matchAll(CLOSING_KEYWORD_PATTERN)].map((match) => ({
    keyword: match[1].toLowerCase(),
    owner: match[2] ?? match[5] ?? null,
    repository: match[3] ?? match[6] ?? null,
    issueNumber: Number(match[4] ?? match[7]),
    source,
  }));
}

export function inspectPrBody(body = "") {
  const closingReferences = findClosingReferences(body, "PR_BODY");
  const closingIssueNumbers = [...new Set(closingReferences.map((reference) => reference.issueNumber))];
  const referencedIssueNumbers = [
    ...new Set([...semanticMarkdown(body).matchAll(ISSUE_REFERENCE_PATTERN)].map((match) => Number(match[1]))),
  ];
  const exceptions = new Set(
    [...semanticMarkdown(body).matchAll(EXCEPTION_PATTERN)].map((match) => match[1].toLowerCase()),
  );

  return { closingReferences, closingIssueNumbers, referencedIssueNumbers, exceptions };
}

/** Closing references across all source commit messages; indexes identify the offender. */
export function inspectCommitMessages(messages = []) {
  const closingReferences = [];
  messages.forEach((message, index) => {
    for (const reference of findClosingReferences(String(message ?? ""), "COMMIT_MESSAGE", {
      stripMarkdown: false,
    })) {
      closingReferences.push({ ...reference, commitIndex: index });
    }
  });
  return closingReferences;
}

function commitSubject(message = "") {
  return String(message).split("\n", 1)[0].slice(0, 80);
}

export async function validatePrMetadata(event, { issueLookup, commitMessagesFetcher } = {}) {
  const errors = [];
  const base = event?.pull_request?.base?.ref;
  const body = event?.pull_request?.body ?? "";
  const repository = event?.repository?.full_name;
  const pullNumber = event?.pull_request?.number;
  const { closingReferences, closingIssueNumbers, referencedIssueNumbers, exceptions } =
    inspectPrBody(body);
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

  // Auto-closing keywords would close the Issue on merge, before the Completion Code
  // Review Gate can run — they are banned on every base, in both the PR body and every
  // source commit message, targeting any repository, with no exception track (#349).
  if (closingIssueNumbers.length > 0) {
    errors.push(
      `PR body 不得使用會自動關閉 Issue 的 keyword（命中 #${closingIssueNumbers.join(", #")}）；請改用「Refs #N」等 non-closing reference，Issue 由 Completion Audit 後明確關閉。`,
    );
  }

  // Fail closed: when the source commits cannot be proven keyword-free, the gate fails.
  // Exception markers never bypass this scan — only the Issue-linkage requirement is
  // exception-aware.
  let commitMessageCount = null;
  if (typeof commitMessagesFetcher !== "function") {
    errors.push(
      "Commit message inspection 未設定，無法證明 PR commits 不含 auto-close keyword；依 fail-closed 政策擋下。",
    );
  } else {
    const commits = await commitMessagesFetcher(repository, pullNumber);
    if (!commits?.ok) {
      errors.push(
        `無法取得 PR commits 以檢查 auto-close keyword（${commits?.reason ?? "unknown reason"}）；依 fail-closed 政策擋下。`,
      );
    } else {
      const commitReferences = inspectCommitMessages(commits.messages);
      commitMessageCount = commits.messages.length;
      closingReferences.push(...commitReferences);
      for (const reference of commitReferences) {
        const target = reference.owner
          ? `${reference.owner}/${reference.repository}#${reference.issueNumber}`
          : `#${reference.issueNumber}`;
        errors.push(
          `PR commit message（「${commitSubject(commits.messages[reference.commitIndex])}」）不得使用會自動關閉 Issue 的 keyword（命中 ${reference.keyword} ${target}）；請改用「Refs #N」等 non-closing reference，Issue 由 Completion Audit 後明確關閉。`,
        );
      }
    }
  }

  if (isMainTarget && referencedIssueNumbers.length === 0 && !isNonIssueDriven) {
    errors.push(
      "Issue-driven PR 必須提供至少一個 Issue reference（例如「Refs #123」）；非 Issue-driven PR 必須加入獨立一行「PR-Metadata-Exception: non-issue-driven」。",
    );
  }

  if (referencedIssueNumbers.length > MAX_ISSUE_REFERENCES) {
    errors.push(`Issue references 超過上限 ${MAX_ISSUE_REFERENCES}，請縮小 PR scope。`);
  } else if (referencedIssueNumbers.length > 0 && !isNonIssueDriven) {
    if (!repository) {
      errors.push("無法從 pull_request event 取得 repository.full_name。");
    } else if (typeof issueLookup !== "function") {
      errors.push("Issue existence lookup 未設定，無法驗證 references。");
    } else {
      const results = await Promise.all(
        referencedIssueNumbers.map(async (issueNumber) => ({
          issueNumber,
          result: await issueLookup(repository, issueNumber),
        })),
      );
      for (const { issueNumber, result } of results) {
        if (!result.exists) {
          errors.push(`Reference #${issueNumber} 不是同 repository 的有效 Issue（${result.reason}）。`);
        }
      }
    }
  }

  return {
    valid: errors.length === 0,
    errors: errors.slice(0, MAX_ISSUE_REFERENCES + 5),
    referencedIssueNumbers,
    closingIssueNumbers,
    closingReferences,
    commitMessageCount,
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

/**
 * Fetches every source commit message of the PR (bounded pagination — scanning only the
 * latest commit would miss keywords planted in older ones). Any retrieval failure returns
 * { ok: false } so the gate can fail closed instead of silently scanning zero commits.
 */
export function githubCommitMessages(token = process.env.GITHUB_TOKEN, fetchImpl = fetch) {
  return async (repository, pullNumber) => {
    if (!repository || !pullNumber) {
      return { ok: false, reason: "event缺少 repository.full_name 或 pull_request.number" };
    }
    const headers = {
      Accept: "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28",
      "User-Agent": "llm-wiki-km-pr-metadata-guard",
    };
    if (token) {
      headers.Authorization = `Bearer ${token}`;
    }

    const messages = [];
    try {
      for (let page = 1; page <= MAX_COMMIT_PAGES; page++) {
        const response = await fetchImpl(
          `https://api.github.com/repos/${repository}/pulls/${pullNumber}/commits?per_page=100&page=${page}`,
          { headers, signal: AbortSignal.timeout(10_000) },
        );
        if (!response.ok) {
          return { ok: false, reason: `GitHub API HTTP ${response.status}` };
        }
        const payload = await response.json();
        if (!Array.isArray(payload)) {
          return { ok: false, reason: "GitHub API response is not a commit array" };
        }
        for (const entry of payload) {
          messages.push(entry?.commit?.message ?? "");
        }
        if (payload.length < 100) {
          return { ok: true, messages };
        }
      }
      return { ok: false, reason: `PR commits exceed the bounded inspection cap (${MAX_COMMIT_MESSAGES})` };
    } catch (error) {
      return { ok: false, reason: `GitHub API request failed: ${error.name}` };
    }
  };
}

async function main() {
  const eventPath = process.env.GITHUB_EVENT_PATH ?? process.argv[2];
  if (!eventPath) {
    throw new Error("缺少 GITHUB_EVENT_PATH 或 event JSON path argument。");
  }

  const event = JSON.parse(await readFile(eventPath, "utf8"));
  const result = await validatePrMetadata(event, {
    issueLookup: githubIssueLookup(),
    commitMessagesFetcher: githubCommitMessages(),
  });
  if (!result.valid) {
    console.error("PR metadata validation failed:");
    for (const error of result.errors) {
      console.error(`- ${error}`);
    }
    process.exitCode = 1;
    return;
  }

  const linkage =
    result.referencedIssueNumbers.length > 0
      ? `referenced Issue: ${result.referencedIssueNumbers.map((number) => `#${number}`).join(", ")}`
      : `reviewed exception: ${result.exception}`;
  console.log(
    `PR metadata validation passed (${linkage}; ${result.commitMessageCount ?? "unknown"} commit messages inspected).`,
  );
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  await main();
}
