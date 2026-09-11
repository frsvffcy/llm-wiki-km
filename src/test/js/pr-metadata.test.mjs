import assert from "node:assert/strict";
import test from "node:test";

import {
  findClosingReferences,
  githubCommitMessages,
  githubIssueLookup,
  inspectCommitMessages,
  inspectPrBody,
  validatePrMetadata,
} from "../../../scripts/validate-pr-metadata.mjs";

const PULL_NUMBER = 400;

function event(body, base = "main") {
  return {
    pull_request: { number: PULL_NUMBER, base: { ref: base }, body },
    repository: { full_name: "frsvffcy/llm-wiki-km" },
  };
}

const CLEAN_COMMIT = "feat: refine PR metadata guard";
const DEFAULT_COMMIT_MESSAGES = [CLEAN_COMMIT, "docs: sync current truth\n\nRefs #234"];

function validate(prEvent, { commitMessages = DEFAULT_COMMIT_MESSAGES, commitResult, ...options } = {}) {
  return validatePrMetadata(prEvent, {
    commitMessagesFetcher: async () => commitResult ?? { ok: true, messages: commitMessages },
    ...options,
  });
}

const existingIssue = async (_repository, issueNumber) => ({
  exists: issueNumber !== 999,
  reason: issueNumber === 999 ? "GitHub API HTTP 404" : undefined,
});

for (const reference of ["Refs #234", "Implements #234", "Related to #234", "#234"]) {
  test(`accepts non-closing reference "${reference}" with an existing Issue`, async () => {
    const result = await validate(event(`## 相關 Issue\n\n${reference}`), {
      issueLookup: existingIssue,
    });

    assert.equal(result.valid, true);
    assert.deepEqual(result.referencedIssueNumbers, [234]);
    assert.deepEqual(result.closingIssueNumbers, []);
    assert.equal(result.commitMessageCount, DEFAULT_COMMIT_MESSAGES.length);
  });
}

for (const keyword of [
  "Closes",
  "closes",
  "Closed",
  "Fixes",
  "Fixed",
  "fix",
  "Resolves",
  "Resolved",
  "resolve",
]) {
  test(`rejects auto-closing "${keyword} #234" before the Completion Gate`, async () => {
    const result = await validate(event(`${keyword} #234\n\nRefs #234`), {
      issueLookup: existingIssue,
    });

    assert.equal(result.valid, false);
    assert.deepEqual(result.closingIssueNumbers, [234]);
    assert.match(result.errors.join("\n"), /自動關閉 Issue/u);
  });
}

test("rejects closing keyword with colon separator and with issue URL", async () => {
  const colon = await validate(event("Closes: #234"), {
    issueLookup: existingIssue,
  });
  const url = await validate(
    event("Fixes https://github.com/frsvffcy/llm-wiki-km/issues/234"),
    { issueLookup: existingIssue },
  );

  assert.equal(colon.valid, false);
  assert.deepEqual(colon.closingIssueNumbers, [234]);
  assert.equal(url.valid, false);
  assert.deepEqual(url.closingIssueNumbers, [234]);
});

test("rejects GitHub official OWNER/REPOSITORY#ISSUE closing syntax (#349)", async () => {
  const crossRepo = await validate(event("Fixes owner/other-repo#123\n\nRefs #234"), {
    issueLookup: existingIssue,
  });
  const crossRepoUppercaseColon = await validate(
    event("CLOSES: OWNER/OTHER-REPO#123\n\nRefs #234"),
    { issueLookup: existingIssue },
  );
  const crossRepoUrl = await validate(
    event("Resolves https://github.com/owner/other-repo/issues/123\n\nRefs #234"),
    { issueLookup: existingIssue },
  );

  for (const result of [crossRepo, crossRepoUppercaseColon, crossRepoUrl]) {
    assert.equal(result.valid, false);
    assert.match(result.errors.join("\n"), /自動關閉 Issue/u);
  }
  assert.deepEqual(crossRepo.closingIssueNumbers, [123]);
  const typed = crossRepo.closingReferences[0];
  assert.equal(typed.keyword, "fixes");
  assert.equal(typed.owner, "owner");
  assert.equal(typed.repository, "other-repo");
  assert.equal(typed.issueNumber, 123);
  assert.equal(typed.source, "PR_BODY");
  assert.deepEqual(crossRepoUppercaseColon.closingReferences[0].keyword, "closes");
});

test("multiple mixed closing forms are all found in one body", async () => {
  const result = await validate(
    event("Closes #201\nfixes owner/other#202\nResolves: https://github.com/owner/other/issues/203"),
    { issueLookup: existingIssue },
  );

  assert.equal(result.valid, false);
  assert.deepEqual(result.closingIssueNumbers, [201, 202, 203]);
  assert.deepEqual(
    result.closingReferences.map((reference) => reference.keyword),
    ["closes", "fixes", "resolves"],
  );
});

test("non-closing cross-repository references and ordinary URLs are not flagged", async () => {
  const crossRepo = await validate(event("Refs #234\nSee owner/other#123 for context"), {
    issueLookup: existingIssue,
  });
  const plainUrl = await validate(
    event("Refs #234\nContext: https://github.com/owner/other/issues/123"),
    { issueLookup: existingIssue },
  );

  for (const result of [crossRepo, plainUrl]) {
    assert.equal(result.valid, true);
    assert.deepEqual(result.closingIssueNumbers, []);
  }
});

test("does not mistake ordinary prose for a closing keyword", async () => {
  const result = await validate(
    event("## 摘要\n\nThis prefix hotfix resolves the flaky test.\n\nRefs #234"),
    { issueLookup: existingIssue },
  );
  const crossRepoProse = await validate(
    event("We plan to hotfix owner/other#123 in the next release.\n\nRefs #234"),
    { issueLookup: existingIssue },
  );

  for (const checked of [result, crossRepoProse]) {
    assert.equal(checked.valid, true);
    assert.deepEqual(checked.closingIssueNumbers, []);
  }
});

test("accepts an explicit non-Issue-driven exception without closing dependencies", async () => {
  const result = await validate(
    event("Related to #120\nDepends on #121\n\nPR-Metadata-Exception: non-issue-driven"),
    { issueLookup: existingIssue },
  );

  assert.equal(result.valid, true);
  assert.equal(result.exception, "non-issue-driven");
});

test("rejects a missing linkage on main", async () => {
  const missing = await validate(event("No issue linkage"), {
    issueLookup: existingIssue,
  });

  assert.equal(missing.valid, false);
  assert.match(missing.errors.join("\n"), /至少一個 Issue reference/u);
});

test("requires an auditable stacked marker for a non-main base", async () => {
  const missing = await validate(event("Refs #234", "feature/parent"), {
    issueLookup: existingIssue,
  });
  const marked = await validate(
    event("Depends on #220\nPR-Metadata-Exception: stacked-pr", "feature/parent"),
    { issueLookup: existingIssue },
  );

  assert.equal(missing.valid, false);
  assert.match(missing.errors.join("\n"), /不是 main/u);
  assert.equal(marked.valid, true);
  assert.equal(marked.exception, "stacked-pr");
});

test("rejects a closing keyword on a stacked base as well", async () => {
  const result = await validate(
    event("Closes #234\nPR-Metadata-Exception: stacked-pr", "feature/parent"),
    { issueLookup: existingIssue },
  );

  assert.equal(result.valid, false);
  assert.match(result.errors.join("\n"), /自動關閉 Issue/u);
});

test("rejects a body with only non-closing references when a commit uses a closing keyword (#349)", async () => {
  const result = await validate(event("Refs #234"), {
    issueLookup: existingIssue,
    commitMessages: ["feat: refine guard\n\nFixes #234"],
  });

  assert.equal(result.valid, false);
  assert.deepEqual(result.closingIssueNumbers, []);
  assert.match(result.errors.join("\n"), /PR commit message.*自動關閉 Issue/u);
  const typed = result.closingReferences.find((reference) => reference.source === "COMMIT_MESSAGE");
  assert.equal(typed.keyword, "fixes");
  assert.equal(typed.issueNumber, 234);
  assert.equal(typed.commitIndex, 0);
});

test("rejects a commit using cross-repository closing syntax", async () => {
  const result = await validate(event("Refs #234"), {
    issueLookup: existingIssue,
    commitMessages: ["Resolved owner/other-repo#123"],
  });

  assert.equal(result.valid, false);
  const typed = result.closingReferences.find((reference) => reference.source === "COMMIT_MESSAGE");
  assert.equal(typed.owner, "owner");
  assert.equal(typed.repository, "other-repo");
  assert.equal(typed.issueNumber, 123);
});

test("commit messages get no Markdown-strip privilege for fenced text", async () => {
  const inspected = inspectCommitMessages(["docs: note\n\n```\nFixes #234\n```"]);

  assert.equal(inspected.length, 1);
  assert.equal(inspected[0].issueNumber, 234);
  assert.equal(inspected[0].source, "COMMIT_MESSAGE");
});

test("uppercase and colon keyword variants are caught in commit messages too", async () => {
  const inspected = inspectCommitMessages(["CLOSES: #77", "FIXED OWNER/OTHER#78"]);

  assert.deepEqual(
    inspected.map((reference) => reference.issueNumber),
    [77, 78],
  );
});

test("a closing keyword in any older commit fails the gate, not only the latest one", async () => {
  const result = await validate(event("Refs #234"), {
    issueLookup: existingIssue,
    commitMessages: ["feat: early commit\n\nFixes #234", CLEAN_COMMIT, "chore: latest commit"],
  });

  assert.equal(result.valid, false);
  assert.equal(result.commitMessageCount, 3);
  assert.match(result.errors.join("\n"), /feat: early commit/u);
});

test("clean commits with non-closing references keep the gate green", async () => {
  const result = await validate(event("Refs #234"), {
    issueLookup: existingIssue,
    commitMessages: DEFAULT_COMMIT_MESSAGES,
  });

  assert.equal(result.valid, true);
  assert.deepEqual(result.closingReferences, []);
});

test("exception markers never bypass the commit closing ban", async () => {
  const stacked = await validate(
    event("Depends on #220\nPR-Metadata-Exception: stacked-pr", "feature/parent"),
    { commitMessages: ["feat: shortcut\n\nCloses #234"] },
  );
  const nonIssueDriven = await validate(
    event("Internal refactor\n\nPR-Metadata-Exception: non-issue-driven"),
    { commitMessages: ["feat: shortcut\n\nfix owner/other#123"] },
  );

  for (const result of [stacked, nonIssueDriven]) {
    assert.equal(result.valid, false);
    assert.match(result.errors.join("\n"), /PR commit message.*自動關閉 Issue/u);
  }
});

test("commit retrieval failure fails closed instead of scanning zero commits", async () => {
  const result = await validate(event("Refs #234"), {
    issueLookup: existingIssue,
    commitResult: { ok: false, reason: "GitHub API HTTP 502" },
  });

  assert.equal(result.valid, false);
  assert.match(result.errors.join("\n"), /無法取得 PR commits.*GitHub API HTTP 502.*fail-closed/u);
});

test("a missing pull number fails closed instead of skipping inspection", async () => {
  const prEvent = event("Refs #234");
  delete prEvent.pull_request.number;
  const result = await validatePrMetadata(prEvent, {
    issueLookup: existingIssue,
    commitMessagesFetcher: githubCommitMessages(undefined, async () => {
      throw new Error("must not fetch");
    }),
  });

  assert.equal(result.valid, false);
  assert.match(result.errors.join("\n"), /無法取得 PR commits.*pull_request\.number/u);
});

test("a missing commit fetcher fails closed", async () => {
  const result = await validatePrMetadata(event("Refs #234"), { issueLookup: existingIssue });

  assert.equal(result.valid, false);
  assert.match(result.errors.join("\n"), /Commit message inspection 未設定/u);
});

test("rejects a reference whose same-repository Issue does not exist", async () => {
  const result = await validate(event("Refs #999"), {
    issueLookup: existingIssue,
  });

  assert.equal(result.valid, false);
  assert.match(result.errors.join("\n"), /#999.*HTTP 404/u);
});

test("ignores placeholders, comments, inline code, and fenced examples in the PR body", () => {
  const inspected = inspectPrBody(`
<!-- Closes #111 -->
\`Fixes #112\`
\`\`\`
Resolves #113
\`\`\`
Refs #234
  `);

  assert.deepEqual(inspected.closingIssueNumbers, []);
  assert.deepEqual(inspected.referencedIssueNumbers, [234]);
});

test("the shared grammar produces identical typed references for body and commits", () => {
  const [bodyReference] = findClosingReferences("Fixes owner/other#123", "PR_BODY");
  const [commitReference] = findClosingReferences("Fixes owner/other#123", "COMMIT_MESSAGE", {
    stripMarkdown: false,
  });

  assert.equal(bodyReference.source, "PR_BODY");
  assert.equal(commitReference.source, "COMMIT_MESSAGE");
  const { source: _bodySource, ...bodyFields } = bodyReference;
  const { source: _commitSource, ...commitFields } = commitReference;
  assert.deepEqual(bodyFields, commitFields);
});

test("GitHub lookup rejects pull requests returned by the Issues endpoint", async () => {
  const lookup = githubIssueLookup("read-only-token", async () => ({
    ok: true,
    json: async () => ({ number: 233, pull_request: { url: "https://api.github.test/pr/233" } }),
  }));

  const result = await lookup("frsvffcy/llm-wiki-km", 233);

  assert.deepEqual(result, {
    exists: false,
    reason: "reference points to a pull request",
  });
});

test("GitHub lookup reports bounded HTTP and parse failures", async () => {
  const httpLookup = githubIssueLookup(undefined, async () => ({ ok: false, status: 403 }));
  const parseLookup = githubIssueLookup(undefined, async () => ({
    ok: true,
    json: async () => {
      throw new SyntaxError("invalid response");
    },
  }));

  assert.deepEqual(await httpLookup("frsvffcy/llm-wiki-km", 234), {
    exists: false,
    reason: "GitHub API HTTP 403",
  });
  assert.deepEqual(await parseLookup("frsvffcy/llm-wiki-km", 234), {
    exists: false,
    reason: "GitHub API response parse failed: SyntaxError",
  });
});

test("GitHub commit fetcher paginates beyond the first page and stops on a short page", async () => {
  const calls = [];
  const fetchImpl = async (url) => {
    calls.push(String(url));
    if (calls.length === 1) {
      return {
        ok: true,
        json: async () =>
          Array.from({ length: 100 }, (_, index) => ({ commit: { message: `commit ${index}` } })),
      };
    }
    return { ok: true, json: async () => [{ commit: { message: "final commit" } }] };
  };

  const result = await githubCommitMessages("read-only-token", fetchImpl)("frsvffcy/llm-wiki-km", PULL_NUMBER);

  assert.equal(result.ok, true);
  assert.equal(result.messages.length, 101);
  assert.equal(result.messages.at(-1), "final commit");
  assert.match(calls[0], /page=1/u);
  assert.match(calls[1], /page=2/u);
});

test("GitHub commit fetcher fails closed on HTTP, parse, and cap conditions", async () => {
  const httpFetcher = githubCommitMessages(undefined, async () => ({ ok: false, status: 502 }));
  assert.deepEqual(await httpFetcher("frsvffcy/llm-wiki-km", PULL_NUMBER), {
    ok: false,
    reason: "GitHub API HTTP 502",
  });

  const parseFetcher = githubCommitMessages(undefined, async () => ({
    ok: true,
    json: async () => {
      throw new SyntaxError("bad json");
    },
  }));
  assert.deepEqual(await parseFetcher("frsvffcy/llm-wiki-km", PULL_NUMBER), {
    ok: false,
    reason: "GitHub API request failed: SyntaxError",
  });

  const nonArrayFetcher = githubCommitMessages(undefined, async () => ({
    ok: true,
    json: async () => ({}),
  }));
  assert.deepEqual(await nonArrayFetcher("frsvffcy/llm-wiki-km", PULL_NUMBER), {
    ok: false,
    reason: "GitHub API response is not a commit array",
  });

  const missingNumber = githubCommitMessages(undefined, async () => {
    throw new Error("must not fetch");
  });
  assert.equal((await missingNumber("frsvffcy/llm-wiki-km", undefined)).ok, false);
});
