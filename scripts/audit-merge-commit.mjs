#!/usr/bin/env node

import { readFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";
import { findClosingReferences } from "./validate-pr-metadata.mjs";

// Post-merge auto-close guard (#357). The PR metadata status check runs before merge and
// can never see the commit message a maintainer may type into the GitHub merge UI — that
// residual is bounded here instead: every push to main inspects the actual head commit
// message (the merge/squash commit GitHub generated or the maintainer edited) plus the
// pushed commit messages. Any closing reference found restores the MERGED_PENDING_AUDIT
// invariant deterministically — referenced same-repository Issues that were auto-closed
// are reopened with an audit comment — and fails the workflow so the violation is visible.
const MAX_PUSH_COMMIT_MESSAGES = 100;

/** Pull requests and branch names are case-insensitive on GitHub. */
function sameRepository(reference, repository) {
  if (!reference.owner) {
    return true;
  }
  const expected = String(repository ?? "").toLowerCase();
  const actual = `${String(reference.owner).toLowerCase()}/${String(reference.repository).toLowerCase()}`;
  return actual === expected;
}

/** Same-repository issue numbers a closing reference would auto-close; others are reported only. */
export function autoClosedIssueNumbers(references = [], repository = "") {
  const targets = [];
  for (const reference of references) {
    if (sameRepository(reference, repository)) {
      targets.push(reference.issueNumber);
    }
  }
  return [...new Set(targets)].sort((a, b) => a - b);
}

/**
 * Decides the deterministic action per referenced issue: reopen a prematurely closed
 * issue, record one that stayed open, or flag an unresolvable reference.
 */
export function planReopenActions(issueNumbers = [], issueStates = {}) {
  return issueNumbers.map((issueNumber) => {
    const state = issueStates[issueNumber];
    if (state === "closed") {
      return { issueNumber, action: "reopen" };
    }
    if (state === "open") {
      return { issueNumber, action: "already-open" };
    }
    return { issueNumber, action: "unresolved" };
  });
}

/** Head commit message first, then the pushed commit messages, bounded and de-duplicated. */
export function collectScannedMessages(pushEvent = {}, headCommitMessage = "") {
  const messages = [String(headCommitMessage ?? "")];
  const commits = Array.isArray(pushEvent.commits) ? pushEvent.commits : [];
  for (const entry of commits.slice(0, MAX_PUSH_COMMIT_MESSAGES)) {
    messages.push(String(entry?.message ?? ""));
  }
  return messages;
}

function githubApi(token = process.env.GITHUB_TOKEN, fetchImpl = fetch) {
  return async (path, method = "GET", body) => {
    const headers = {
      Accept: "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28",
      "User-Agent": "llm-wiki-km-post-merge-guard",
    };
    if (token) {
      headers.Authorization = `Bearer ${token}`;
    }
    let response;
    try {
      response = await fetchImpl(`https://api.github.com${path}`, {
        headers,
        method,
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: AbortSignal.timeout(10_000),
      });
    } catch (error) {
      return { ok: false, reason: `GitHub API request failed: ${error.name}` };
    }
    if (!response.ok) {
      return { ok: false, reason: `GitHub API HTTP ${response.status}` };
    }
    if (method === "GET") {
      try {
        return { ok: true, payload: await response.json() };
      } catch (error) {
        return { ok: false, reason: `GitHub API response parse failed: ${error.name}` };
      }
    }
    return { ok: true };
  };
}

export function createPostMergeGuard({ api } = {}) {
  const call = api ?? githubApi();

  return async function runPostMergeGuard(pushEvent) {
    if (pushEvent?.deleted) {
      return { status: "clean", detail: "branch deletion push；無 commit message 可審查。" };
    }
    const repository = pushEvent?.repository?.full_name;
    const headSha = pushEvent?.after ?? pushEvent?.head_commit?.id;
    if (!repository || !headSha) {
      return {
        status: "failed",
        detail: "push event 缺少 repository.full_name 或 head commit sha；merge commit message 無法證明乾淨，依 fail-closed 政策擋下。",
      };
    }

    const headCommit = await call(`/repos/${repository}/commits/${headSha}`);
    if (!headCommit.ok) {
      return {
        status: "failed",
        detail: `無法取得 head commit ${headSha} 的 message（${headCommit.reason}）；merge commit message 無法證明乾淨，依 fail-closed 政策擋下。`,
      };
    }

    const scannedMessages = collectScannedMessages(pushEvent, headCommit.payload?.commit?.message);
    const closingReferences = scannedMessages.flatMap((message, index) =>
      findClosingReferences(message, index === 0 ? "MERGE_COMMIT_MESSAGE" : "PUSHED_COMMIT_MESSAGE", {
        stripMarkdown: false,
      }).map((reference) => ({ ...reference, messageIndex: index })),
    );

    if (closingReferences.length === 0) {
      return { status: "clean", detail: `${scannedMessages.length} 則 commit message 未含 closing keyword。` };
    }

    const issueNumbers = autoClosedIssueNumbers(closingReferences, repository);
    const issueStates = {};
    for (const issueNumber of issueNumbers) {
      const issue = await call(`/repos/${repository}/issues/${issueNumber}`);
      if (issue.ok) {
        issueStates[issueNumber] = issue.payload?.state;
      }
    }

    const actions = planReopenActions(issueNumbers, issueStates);
    for (const action of actions) {
      if (action.action !== "reopen") {
        continue;
      }
      const { issueNumber } = action;
      const reopened = await call(
        `/repos/${repository}/issues/${issueNumber}`,
        "PATCH",
        { state: "open" },
      );
      if (reopened.ok) {
        action.reopenOutcome = "reopened";
        await call(`/repos/${repository}/issues/${issueNumber}/comments`, "POST", {
          body:
            `Post-merge auto-close guard（#357）偵測到 commit ${String(headSha).slice(0, 12)} 的 message 含 closing keyword` +
            `（merge 時 commit message 可被人為編輯，status check 無法事先察覺），已將本 Issue deterministic reopen，` +
            `恢復「merge 後、Completion Audit 前保持 OPEN」invariant。請改用「Refs #N」等 non-closing reference；` +
            `Issue 由 Completion Audit 後明確關閉。`,
        });
      } else {
        // A failed PATCH must not be reported as a successful reopen (#363): record the
        // actual outcome so the human-readable report asks for manual verification.
        action.reopenOutcome = "reopen-failed";
        action.reopenFailureReason = reopened.reason ?? "unknown reason";
      }
    }

    return { status: "closing-reference-detected", detail: { closingReferences, actions, headSha } };
  };
}

async function main() {
  const eventPath = process.env.GITHUB_EVENT_PATH ?? process.argv[2];
  if (!eventPath) {
    throw new Error("缺少 GITHUB_EVENT_PATH 或 push event JSON path argument。");
  }

  const pushEvent = JSON.parse(await readFile(eventPath, "utf8"));
  const result = await createPostMergeGuard()(pushEvent);

  if (result.status === "clean") {
    console.log(`Post-merge auto-close guard passed：${result.detail}`);
    return;
  }
  if (result.status === "failed") {
    console.error(`Post-merge auto-close guard failed：${result.detail}`);
    process.exitCode = 1;
    return;
  }

  const { closingReferences, actions, headSha } = result.detail;
  console.error(`Post-merge auto-close guard 偵測到 commit ${String(headSha).slice(0, 12)} 的 message 含 closing keyword：`);
  for (const reference of closingReferences) {
    const target = reference.owner
      ? `${reference.owner}/${reference.repository}#${reference.issueNumber}`
      : `#${reference.issueNumber}`;
    console.error(`- ${reference.keyword} ${target}（source: ${reference.source}）`);
  }
  for (const { issueNumber, action, reopenOutcome, reopenFailureReason } of actions) {
    if (action === "reopen") {
      if (reopenOutcome === "reopened") {
        console.error(`- Issue #${issueNumber} 已 deterministic reopen，恢復 MERGED_PENDING_AUDIT invariant。`);
      } else {
        console.error(
          `- Issue #${issueNumber} reopen 嘗試失敗（${reopenFailureReason ?? "unknown reason"}）；`
          + `請人工確認 Issue 已恢復 OPEN，恢復 MERGED_PENDING_AUDIT invariant。`);
      }
    } else if (action === "already-open") {
      console.error(`- Issue #${issueNumber} 仍為 OPEN，無需 reopen。`);
    } else {
      console.error(`- Issue #${issueNumber} 無法解析（不存在或非 Issue），僅記錄。`);
    }
  }
  console.error("請改用「Refs #N」等 non-closing reference；Issue 由 Completion Audit 後明確關閉。");
  process.exitCode = 1;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  await main();
}
