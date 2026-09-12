import assert from "node:assert/strict";
import test from "node:test";

import {
  autoClosedIssueNumbers,
  collectScannedMessages,
  createPostMergeGuard,
  planReopenActions,
} from "../../../scripts/audit-merge-commit.mjs";
import { findClosingReferences } from "../../../scripts/validate-pr-metadata.mjs";

const REPO = "frsvffcy/llm-wiki-km";

function fakeApi(responses) {
  const calls = [];
  const api = async (path, method = "GET", body) => {
    calls.push({ path, method, body });
    const handler = responses[`${method} ${path}`];
    if (!handler) {
      return { ok: false, reason: `unexpected call: ${method} ${path}` };
    }
    return typeof handler === "function" ? handler() : handler;
  };
  api.calls = calls;
  return api;
}

function pushEvent({ headSha = "abc123def456", commits = [], deleted = false } = {}) {
  return {
    repository: { full_name: REPO },
    after: headSha,
    head_commit: { id: headSha },
    commits,
    deleted,
  };
}

test("a merge commit whose body carries the PR title closing keyword is detected (#357 synthetic dry-run)", () => {
  // With merge_commit_message = PR_TITLE a closing keyword typed into the PR title (or the
  // merge UI) lands in the default-branch commit message exactly like this.
  const mergeMessage = "Merge pull request #359 from frsvffcy/fix/some-branch\n\nFixes #342";
  const references = findClosingReferences(mergeMessage, "MERGE_COMMIT_MESSAGE", {
    stripMarkdown: false,
  });

  assert.deepEqual(autoClosedIssueNumbers(references, REPO), [342]);
});

test("a prematurely closed issue is deterministically reopened, commented, and the guard fails", async () => {
  const api = fakeApi({
    [`GET /repos/${REPO}/commits/abc123def456`]: {
      ok: true,
      payload: { commit: { message: "Merge pull request #360 from frsvffcy/fix/x\n\nCloses #342" } },
    },
    [`GET /repos/${REPO}/issues/342`]: { ok: true, payload: { state: "closed" } },
    [`PATCH /repos/${REPO}/issues/342`]: { ok: true },
    [`POST /repos/${REPO}/issues/342/comments`]: { ok: true },
  });
  const result = await createPostMergeGuard({ api })(pushEvent());

  assert.equal(result.status, "closing-reference-detected");
  assert.deepEqual(result.detail.actions, [
    { issueNumber: 342, action: "reopen", reopenOutcome: "reopened" },
  ]);
  const patch = api.calls.find((call) => call.method === "PATCH");
  assert.deepEqual(patch.body, { state: "open" });
  const comment = api.calls.find((call) => call.path.endsWith("/issues/342/comments"));
  assert.match(comment.body.body, /deterministic reopen/u);
  assert.match(comment.body.body, /Completion Audit/u);
});

test("a reopen whose PATCH fails is reported as an outcome, not as a success (#363)", async () => {
  const api = fakeApi({
    [`GET /repos/${REPO}/commits/abc123def456`]: {
      ok: true,
      payload: { commit: { message: "Merge pull request #360 from frsvffcy/fix/x\n\nFixes #342" } },
    },
    [`GET /repos/${REPO}/issues/342`]: { ok: true, payload: { state: "closed" } },
    [`PATCH /repos/${REPO}/issues/342`]: { ok: false, reason: "GitHub API HTTP 502" },
  });
  const result = await createPostMergeGuard({ api })(pushEvent());

  assert.equal(result.status, "closing-reference-detected");
  assert.deepEqual(result.detail.actions, [
    {
      issueNumber: 342,
      action: "reopen",
      reopenOutcome: "reopen-failed",
      reopenFailureReason: "GitHub API HTTP 502",
    },
  ]);
  // No success comment may be left for a reopen that did not happen.
  assert.equal(api.calls.some((call) => call.method === "POST"), false);
});

test("an issue that stayed open is reported without a reopen call", async () => {
  const api = fakeApi({
    [`GET /repos/${REPO}/commits/abc123def456`]: {
      ok: true,
      payload: { commit: { message: "Merge pull request #360 from frsvffcy/fix/x\n\nFixes #342" } },
    },
    [`GET /repos/${REPO}/issues/342`]: { ok: true, payload: { state: "open" } },
  });
  const result = await createPostMergeGuard({ api })(pushEvent());

  assert.equal(result.status, "closing-reference-detected");
  assert.deepEqual(result.detail.actions, [{ issueNumber: 342, action: "already-open" }]);
  assert.equal(api.calls.some((call) => call.method === "PATCH"), false);
});

test("cross-repository closing references are reported but never reopened here", async () => {
  const api = fakeApi({
    [`GET /repos/${REPO}/commits/abc123def456`]: {
      ok: true,
      payload: { commit: { message: "Resolved owner/other-repo#123" } },
    },
  });
  const result = await createPostMergeGuard({ api })(pushEvent());

  assert.equal(result.status, "closing-reference-detected");
  assert.deepEqual(result.detail.actions, []);
  assert.deepEqual(result.detail.closingReferences[0].owner, "owner");
});

test("a clean merge commit message passes the guard", async () => {
  const api = fakeApi({
    [`GET /repos/${REPO}/commits/abc123def456`]: {
      ok: true,
      payload: {
        commit: { message: "Merge pull request #360 from frsvffcy/fix/x\n\nfix: PR Metadata guard 補上 title 防護" },
      },
    },
  });
  const result = await createPostMergeGuard({ api })(pushEvent());

  assert.equal(result.status, "clean");
});

test("a branch deletion push passes without inspection", async () => {
  const api = fakeApi({});
  const result = await createPostMergeGuard({ api })(pushEvent({ deleted: true }));

  assert.equal(result.status, "clean");
  assert.equal(api.calls.length, 0);
});

test("a missing head sha fails closed instead of assuming a clean merge", async () => {
  const api = fakeApi({});
  const result = await createPostMergeGuard({ api })({
    repository: { full_name: REPO },
    commits: [],
  });

  assert.equal(result.status, "failed");
  assert.match(result.detail, /fail-closed/u);
});

test("commit message retrieval failure fails closed", async () => {
  const api = fakeApi({
    [`GET /repos/${REPO}/commits/abc123def456`]: { ok: false, reason: "GitHub API HTTP 502" },
  });
  const result = await createPostMergeGuard({ api })(pushEvent());

  assert.equal(result.status, "failed");
  assert.match(result.detail, /GitHub API HTTP 502.*fail-closed/u);
});

test("pushed commit messages are scanned in addition to the merge commit", async () => {
  const api = fakeApi({
    [`GET /repos/${REPO}/commits/abc123def456`]: {
      ok: true,
      payload: { commit: { message: "Merge pull request #360 from frsvffcy/fix/x" } },
    },
  });
  const result = await createPostMergeGuard({ api })(
    pushEvent({ commits: [{ message: "feat: work\n\nResolves #342" }] }),
  );

  assert.equal(result.status, "closing-reference-detected");
  assert.equal(result.detail.closingReferences[0].source, "PUSHED_COMMIT_MESSAGE");
});

test("collectScannedMessages puts the head commit first and bounds pushed commits", () => {
  const commits = Array.from({ length: 150 }, (_, index) => ({ message: `commit ${index}` }));
  const messages = collectScannedMessages({ commits }, "head message");

  assert.equal(messages[0], "head message");
  assert.equal(messages.length, 101);
  assert.equal(messages.at(-1), "commit 99");
});

test("planReopenActions covers closed, open, and unresolvable issues", () => {
  assert.deepEqual(planReopenActions([1, 2, 3], { 1: "closed", 2: "open" }), [
    { issueNumber: 1, action: "reopen" },
    { issueNumber: 2, action: "already-open" },
    { issueNumber: 3, action: "unresolved" },
  ]);
});

test("autoClosedIssueNumbers de-duplicates and sorts same-repository targets", () => {
  const references = [
    { issueNumber: 9, owner: null },
    { issueNumber: 2, owner: "FRSVFFCY", repository: "Llm-Wiki-Km" },
    { issueNumber: 5, owner: "owner", repository: "other" },
    { issueNumber: 2, owner: null },
  ];

  assert.deepEqual(autoClosedIssueNumbers(references, REPO), [2, 9]);
});
