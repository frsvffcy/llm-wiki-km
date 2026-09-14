import assert from "node:assert/strict";
import test from "node:test";

import {
  findClosingReferences,
  findPullReferences,
  githubCommitMessages,
  githubCurrentPullMetadata,
  githubIssueLookup,
  inspectCommitMessages,
  inspectPrBody,
  inspectPrTitle,
  validatePrMetadata,
} from "../../../scripts/validate-pr-metadata.mjs";

const PULL_NUMBER = 400;

function event(body, base = "main", title = "") {
  return {
    pull_request: { number: PULL_NUMBER, base: { ref: base }, body, title },
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

// --- PR title (#357): with merge_commit_message = PR_TITLE the title becomes the merge
// commit body on the default branch, so it shares the body/commit closing ban ---

test("rejects a closing-keyword PR title even with a clean body and clean commits", async () => {
  const result = await validate(event("Refs #234", "main", "Fixes #234"), {
    issueLookup: existingIssue,
  });

  assert.equal(result.valid, false);
  assert.deepEqual(result.closingIssueNumbers, []);
  assert.match(result.errors.join("\n"), /PR title.*自動關閉 Issue/u);
  const typed = result.closingReferences.find((reference) => reference.source === "PR_TITLE");
  assert.equal(typed.keyword, "fixes");
  assert.equal(typed.issueNumber, 234);
});

test("rejects cross-repository, uppercase, colon and URL closing forms in the PR title", async () => {
  const crossRepo = await validate(event("Refs #234", "main", "CLOSES: owner/other-repo#123"), {
    issueLookup: existingIssue,
  });
  const uppercase = await validate(event("Refs #234", "main", "FIXED OWNER/OTHER#123"), {
    issueLookup: existingIssue,
  });
  const url = await validate(
    event("Refs #234", "main", "Resolves https://github.com/owner/other/issues/123"),
    { issueLookup: existingIssue },
  );

  for (const result of [crossRepo, uppercase, url]) {
    assert.equal(result.valid, false);
    assert.match(result.errors.join("\n"), /PR title.*自動關閉 Issue/u);
  }
  assert.deepEqual(uppercase.closingReferences.find((r) => r.source === "PR_TITLE").keyword, "fixed");
  assert.deepEqual(url.closingReferences.find((r) => r.source === "PR_TITLE").issueNumber, 123);
});

test("does not mistake a Conventional Commit title without an issue target for a closing keyword", async () => {
  const result = await validate(event("Refs #234", "main", "fix: 修正 MCP parser"), {
    issueLookup: existingIssue,
  });

  assert.equal(result.valid, true);
  assert.deepEqual(result.closingReferences, []);
});

test("the PR title is scanned raw without the Markdown-strip privilege", () => {
  // The body test above proves HTML comments and inline code are ignored in the PR body;
  // a title is plain merge input, so the same content must still be caught (#357).
  const inspected = inspectPrTitle("<!-- Fixes #123 -->");

  assert.equal(inspected.closingReferences.length, 1);
  assert.equal(inspected.closingReferences[0].source, "PR_TITLE");
  assert.equal(inspected.closingReferences[0].issueNumber, 123);
});

test("exception markers never bypass the PR title closing ban", async () => {
  const stacked = await validate(
    event("Depends on #220\nPR-Metadata-Exception: stacked-pr", "feature/parent", "Closes #234"),
    { issueLookup: existingIssue },
  );
  const nonIssueDriven = await validate(
    event("Internal refactor\n\nPR-Metadata-Exception: non-issue-driven", "main", "Resolves #234"),
    { issueLookup: existingIssue },
  );

  for (const result of [stacked, nonIssueDriven]) {
    assert.equal(result.valid, false);
    assert.match(result.errors.join("\n"), /PR title.*自動關閉 Issue/u);
  }
});

test("synthetic gate dry-run: clean body and commits with a malicious PR title cannot pass", async () => {
  const result = await validate(event("## 相關 Issue\n\nRefs #342", "main", "Fixes #342"), {
    issueLookup: existingIssue,
  });

  assert.equal(result.valid, false);
  assert.match(result.errors.join("\n"), /PR title.*命中 fixes #342/u);
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

// --- #411: Issue linkage vs Pull Request lineage/reference separation ---

test("findPullReferences captures PR text forms without touching Issue refs", () => {
  for (const body of ["PR #407", "pr #407", "PRs #407", "Pull Request #407", "pull requests #407", "PR: #407"]) {
    const refs = findPullReferences(body);
    assert.equal(refs.length, 1);
    assert.equal(refs[0].pullNumber, 407);
    assert.equal(refs[0].kind, "text");
  }
  assert.deepEqual(findPullReferences("Refs #408"), []);
  assert.deepEqual(findPullReferences("#408"), []);
});

test("findPullReferences captures /pull/N URLs with owner/repo and ignores /issues/N", () => {
  const pull = findPullReferences("See https://github.com/frsvffcy/llm-wiki-km/pull/407 for lineage");
  assert.equal(pull.length, 1);
  assert.equal(pull[0].pullNumber, 407);
  assert.equal(pull[0].owner, "frsvffcy");
  assert.equal(pull[0].repository, "llm-wiki-km");
  assert.equal(pull[0].kind, "url");

  assert.deepEqual(findPullReferences("See https://github.com/frsvffcy/llm-wiki-km/issues/408"), []);
  assert.deepEqual(findPullReferences("See owner/other#123"), []);
});

test("findPullReferences ignores fenced, inline-code and commented PR mentions", () => {
  assert.deepEqual(findPullReferences("<!-- PR #407 -->"), []);
  assert.deepEqual(findPullReferences("`PR #407`"), []);
  assert.deepEqual(findPullReferences("```\nPR #407\n```"), []);
});

test("inspectPrBody separates Issue linkage from PR lineage (incident #408/#409)", () => {
  const inspected = inspectPrBody("Refs #408\nRefs #401（capability seam，PR #407 已 merge）");
  assert.deepEqual(inspected.referencedIssueNumbers, [408, 401]);
  assert.deepEqual(inspected.referencedPullNumbers, [407]);
});

test("inspectPrBody keeps Refs #N as Issue even when N happens to be a PR number", () => {
  const inspected = inspectPrBody("Refs #407");
  assert.deepEqual(inspected.referencedIssueNumbers, [407]);
  assert.deepEqual(inspected.referencedPullNumbers, []);
});

test("inspectPrBody records bare and URL PR refs but not /issues/ URLs", () => {
  const text = inspectPrBody("Refs #408\nPR #407");
  assert.deepEqual(text.referencedIssueNumbers, [408]);
  assert.deepEqual(text.referencedPullNumbers, [407]);

  const url = inspectPrBody(
    "Refs #408\nLineage: https://github.com/frsvffcy/llm-wiki-km/pull/407",
  );
  assert.deepEqual(url.referencedIssueNumbers, [408]);
  assert.deepEqual(url.referencedPullNumbers, [407]);

  const issuesUrl = inspectPrBody(
    "Refs #408\nContext: https://github.com/frsvffcy/llm-wiki-km/issues/123",
  );
  assert.deepEqual(issuesUrl.referencedIssueNumbers, [408]);
  assert.deepEqual(issuesUrl.referencedPullNumbers, []);
});

test("Refs #408 + PR #407 passes when both entities exist (#411 AC)", async () => {
  const calls = [];
  const lookup = async (_repo, n) => {
    calls.push(n);
    if (n === 407) {
      return { exists: false, reason: "reference points to a pull request" };
    }
    return { exists: true };
  };
  const result = await validate(event("Refs #408\nLineage: PR #407"), { issueLookup: lookup });
  assert.equal(result.valid, true);
  assert.deepEqual(result.referencedIssueNumbers, [408]);
  assert.deepEqual(result.referencedPullNumbers, [407]);
  assert.ok(!calls.includes(407), "PR lineage must not trigger Issue-existence lookup");
});

test("Refs #408 + /pull/407 URL passes without Issue-existence error for the PR", async () => {
  const calls = [];
  const result = await validate(
    event("Refs #408\nLineage: https://github.com/frsvffcy/llm-wiki-km/pull/407"),
    {
      issueLookup: async (_repo, n) => {
        calls.push(n);
        return { exists: true };
      },
    },
  );
  assert.equal(result.valid, true);
  assert.deepEqual(result.referencedIssueNumbers, [408]);
  assert.deepEqual(result.referencedPullNumbers, [407]);
  assert.deepEqual(calls, [408]);
});

test("only PR #407 without Issue linkage still fails the Issue-driven requirement", async () => {
  const result = await validate(event("Lineage: PR #407"), { issueLookup: existingIssue });
  assert.equal(result.valid, false);
  assert.deepEqual(result.referencedIssueNumbers, []);
  assert.deepEqual(result.referencedPullNumbers, [407]);
  assert.match(result.errors.join("\n"), /至少一個 Issue reference/u);
});

test("only a /pull/ URL without Issue linkage still fails", async () => {
  const result = await validate(
    event("Lineage: https://github.com/frsvffcy/llm-wiki-km/pull/407"),
    { issueLookup: existingIssue },
  );
  assert.equal(result.valid, false);
  assert.deepEqual(result.referencedIssueNumbers, []);
  assert.deepEqual(result.referencedPullNumbers, [407]);
});

test("Refs #407 pointing at a real PR still fails closed", async () => {
  const prLookup = async (_repo, n) => {
    if (n === 407) {
      return { exists: false, reason: "reference points to a pull request" };
    }
    return { exists: true };
  };
  const result = await validate(event("Refs #407"), { issueLookup: prLookup });
  assert.equal(result.valid, false);
  assert.match(result.errors.join("\n"), /#407.*pull request/u);
});

test("/issues/ URL alone does not satisfy Issue linkage and is not a PR", async () => {
  const onlyUrl = await validate(
    event("Context: https://github.com/frsvffcy/llm-wiki-km/issues/408"),
    { issueLookup: existingIssue },
  );
  assert.equal(onlyUrl.valid, false);
  assert.deepEqual(onlyUrl.referencedIssueNumbers, []);
  assert.deepEqual(onlyUrl.referencedPullNumbers, []);
});

test("cross-repository /pull/ URL is PR lineage, not Issue linkage", async () => {
  const inspected = inspectPrBody(
    "Refs #234\nSee https://github.com/owner/other/pull/123 for lineage",
  );
  assert.deepEqual(inspected.referencedIssueNumbers, [234]);
  assert.deepEqual(inspected.referencedPullNumbers, [123]);
  assert.equal(inspected.pullReferences[0].owner, "owner");

  const result = await validate(
    event("Refs #234\nSee https://github.com/owner/other/pull/123 for lineage"),
    { issueLookup: existingIssue },
  );
  assert.equal(result.valid, true);
});

test("PR lineage does not weaken the closing-keyword guard", async () => {
  const bodyClosing = await validate(event("Fixes #234\nLineage: PR #407"), {
    issueLookup: existingIssue,
  });
  assert.equal(bodyClosing.valid, false);
  assert.match(bodyClosing.errors.join("\n"), /自動關閉 Issue/u);

  const pullUrlClosing = await validate(
    event("Fixes https://github.com/frsvffcy/llm-wiki-km/pull/407\n\nRefs #234"),
    { issueLookup: existingIssue },
  );
  assert.equal(pullUrlClosing.valid, false);
  assert.match(pullUrlClosing.errors.join("\n"), /自動關閉 Issue/u);

  const cleanLineage = await validate(event("Refs #234\nLineage: PR #407"), {
    issueLookup: existingIssue,
  });
  assert.equal(cleanLineage.valid, true);
  assert.deepEqual(cleanLineage.closingIssueNumbers, []);
});

test("exception markers do not turn PR lineage into Issue linkage", async () => {
  const nonIssue = await validate(
    event("Lineage: PR #407\n\nPR-Metadata-Exception: non-issue-driven"),
    { issueLookup: existingIssue },
  );
  assert.equal(nonIssue.valid, true);
  assert.deepEqual(nonIssue.referencedIssueNumbers, []);
  assert.deepEqual(nonIssue.referencedPullNumbers, [407]);

  const stacked = await validate(
    event("Depends on #220\nLineage: PR #407\nPR-Metadata-Exception: stacked-pr", "feature/parent"),
    { issueLookup: existingIssue },
  );
  assert.equal(stacked.valid, true);
  assert.deepEqual(stacked.referencedPullNumbers, [407]);
});

test("Pull Request reference bound is enforced", async () => {
  const body = Array.from({ length: 21 }, (_, i) => `PR #${500 + i}`).join("\n") + "\nRefs #234";
  const result = await validate(event(body), { issueLookup: existingIssue });
  assert.equal(result.valid, false);
  assert.match(result.errors.join("\n"), /Pull Request references 超過上限/u);
});

// --- #411 C: current metadata authority (stale-event rerun) ---

function currentFetcher({ title = "", body = "", base = "main" } = {}) {
  return async () => ({ ok: true, title, body, base });
}

test("stale event body is ignored once the current PR body is fixed", async () => {
  const stale = event("No issue linkage");
  const result = await validatePrMetadata(stale, {
    issueLookup: existingIssue,
    commitMessagesFetcher: async () => ({ ok: true, messages: DEFAULT_COMMIT_MESSAGES }),
    currentPrFetcher: currentFetcher({ body: "Refs #234" }),
  });
  assert.equal(result.valid, true);
  assert.equal(result.metadataSource, "current");
  assert.equal(result.validatedBody, "Refs #234");
  assert.deepEqual(result.referencedIssueNumbers, [234]);
});

test("current body closing keyword fails even when the stale event body looks clean", async () => {
  const stale = event("Refs #234");
  const result = await validatePrMetadata(stale, {
    issueLookup: existingIssue,
    commitMessagesFetcher: async () => ({ ok: true, messages: DEFAULT_COMMIT_MESSAGES }),
    currentPrFetcher: currentFetcher({ body: "Fixes #234\n\nRefs #234" }),
  });
  assert.equal(result.valid, false);
  assert.equal(result.metadataSource, "current");
  assert.match(result.errors.join("\n"), /自動關閉 Issue/u);
});

test("current title and base win over stale event values", async () => {
  const titleStale = event("Refs #234", "main", "chore: clean title");
  const titleCurrent = await validatePrMetadata(titleStale, {
    issueLookup: existingIssue,
    commitMessagesFetcher: async () => ({ ok: true, messages: DEFAULT_COMMIT_MESSAGES }),
    currentPrFetcher: currentFetcher({ title: "Fixes #234", body: "Refs #234", base: "main" }),
  });
  assert.equal(titleCurrent.valid, false);
  assert.match(titleCurrent.errors.join("\n"), /PR title.*自動關閉 Issue/u);

  const baseStale = event("Refs #234", "main");
  const baseCurrent = await validatePrMetadata(baseStale, {
    issueLookup: existingIssue,
    commitMessagesFetcher: async () => ({ ok: true, messages: DEFAULT_COMMIT_MESSAGES }),
    currentPrFetcher: currentFetcher({ body: "Refs #234", base: "feature/other" }),
  });
  assert.equal(baseCurrent.valid, false);
  assert.match(baseCurrent.errors.join("\n"), /不是 main/u);
  assert.equal(baseCurrent.validatedBase, "feature/other");
});

test("without a current fetcher the validator keeps the event payload source", async () => {
  const result = await validate(event("Refs #234"), { issueLookup: existingIssue });
  assert.equal(result.valid, true);
  assert.equal(result.metadataSource, "event");
});

test("current metadata fetch failure fails closed instead of silently using stale body", async () => {
  const result = await validatePrMetadata(event("Refs #234"), {
    issueLookup: existingIssue,
    commitMessagesFetcher: async () => ({ ok: true, messages: DEFAULT_COMMIT_MESSAGES }),
    currentPrFetcher: async () => ({ ok: false, reason: "GitHub API HTTP 502" }),
  });
  assert.equal(result.valid, false);
  assert.equal(result.metadataSource, "current-fetch-failed");
  assert.match(result.errors.join("\n"), /無法取得當下 PR metadata.*fail-closed/u);
});

test("githubCurrentPullMetadata maps success, null body, and bounded failures", async () => {
  const ok = githubCurrentPullMetadata("t", async () => ({
    ok: true,
    json: async () => ({ title: "feat: x", body: "Refs #234", base: { ref: "main" } }),
  }));
  assert.deepEqual(await ok("frsvffcy/llm-wiki-km", 400), {
    ok: true,
    title: "feat: x",
    body: "Refs #234",
    base: "main",
  });

  const nullBody = githubCurrentPullMetadata("t", async () => ({
    ok: true,
    json: async () => ({ title: "feat: x", body: null, base: { ref: "main" } }),
  }));
  assert.deepEqual(await nullBody("frsvffcy/llm-wiki-km", 400), {
    ok: true,
    title: "feat: x",
    body: "",
    base: "main",
  });

  const http = githubCurrentPullMetadata(undefined, async () => ({ ok: false, status: 502 }));
  assert.deepEqual(await http("frsvffcy/llm-wiki-km", 400), {
    ok: false,
    reason: "GitHub API HTTP 502",
  });

  const parse = githubCurrentPullMetadata(undefined, async () => ({
    ok: true,
    json: async () => {
      throw new SyntaxError("bad json");
    },
  }));
  assert.deepEqual(await parse("frsvffcy/llm-wiki-km", 400), {
    ok: false,
    reason: "GitHub API response parse failed: SyntaxError",
  });

  const missingBase = githubCurrentPullMetadata(undefined, async () => ({
    ok: true,
    json: async () => ({ title: "t", body: "b" }),
  }));
  assert.deepEqual(await missingBase("frsvffcy/llm-wiki-km", 400), {
    ok: false,
    reason: "GitHub API response missing base ref",
  });

  const transport = githubCurrentPullMetadata(undefined, async () => {
    throw new TypeError("network down");
  });
  assert.deepEqual(await transport("frsvffcy/llm-wiki-km", 400), {
    ok: false,
    reason: "GitHub API request failed: TypeError",
  });

  const missingArgs = githubCurrentPullMetadata(undefined, async () => {
    throw new Error("must not fetch");
  });
  assert.equal((await missingArgs("frsvffcy/llm-wiki-km", undefined)).ok, false);
});
