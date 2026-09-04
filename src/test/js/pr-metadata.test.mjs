import assert from "node:assert/strict";
import test from "node:test";

import {
  githubIssueLookup,
  inspectPrBody,
  validatePrMetadata,
} from "../../../scripts/validate-pr-metadata.mjs";

function event(body, base = "main") {
  return {
    pull_request: { base: { ref: base }, body },
    repository: { full_name: "frsvffcy/llm-wiki-km" },
  };
}

const existingIssue = async (_repository, issueNumber) => ({
  exists: issueNumber !== 999,
  reason: issueNumber === 999 ? "GitHub API HTTP 404" : undefined,
});

for (const keyword of ["Closes", "Fixes", "Resolves"]) {
  test(`accepts ${keyword} with an existing same-repository Issue`, async () => {
    const result = await validatePrMetadata(event(`${keyword} #234`), {
      issueLookup: existingIssue,
    });

    assert.equal(result.valid, true);
    assert.deepEqual(result.closingIssueNumbers, [234]);
  });
}

test("rejects the PR #233 bare-reference pattern", async () => {
  const result = await validatePrMetadata(event("## 相關 Issue\n\n- #232"), {
    issueLookup: existingIssue,
  });

  assert.equal(result.valid, false);
  assert.match(result.errors.join("\n"), /沒有有效 closing keyword/u);
});

test("accepts an explicit non-Issue-driven exception without closing dependencies", async () => {
  const result = await validatePrMetadata(
    event("Related to #120\nDepends on #121\n\nPR-Metadata-Exception: non-issue-driven"),
    { issueLookup: existingIssue },
  );

  assert.equal(result.valid, true);
  assert.equal(result.exception, "non-issue-driven");
});

test("rejects a missing linkage and a conflicting non-Issue-driven marker", async () => {
  const missing = await validatePrMetadata(event("No issue linkage"), {
    issueLookup: existingIssue,
  });
  const conflicting = await validatePrMetadata(
    event("Closes #234\nPR-Metadata-Exception: non-issue-driven"),
    { issueLookup: existingIssue },
  );

  assert.equal(missing.valid, false);
  assert.equal(conflicting.valid, false);
  assert.match(conflicting.errors.join("\n"), /不得同時宣告/u);
});

test("requires an auditable stacked marker for a non-main base", async () => {
  const missing = await validatePrMetadata(event("Closes #234", "feature/parent"), {
    issueLookup: existingIssue,
  });
  const marked = await validatePrMetadata(
    event("Depends on #220\nPR-Metadata-Exception: stacked-pr", "feature/parent"),
    { issueLookup: existingIssue },
  );

  assert.equal(missing.valid, false);
  assert.match(missing.errors.join("\n"), /不是 main/u);
  assert.equal(marked.valid, true);
  assert.equal(marked.exception, "stacked-pr");
});

test("rejects a closing reference whose same-repository Issue does not exist", async () => {
  const result = await validatePrMetadata(event("Closes #999"), {
    issueLookup: existingIssue,
  });

  assert.equal(result.valid, false);
  assert.match(result.errors.join("\n"), /#999.*HTTP 404/u);
});

test("ignores placeholders, comments, inline code, and fenced examples", () => {
  const inspected = inspectPrBody(`
<!-- Closes #111 -->
\`Fixes #112\`
\`\`\`
Resolves #113
\`\`\`
Closes #234
  `);

  assert.deepEqual(inspected.closingIssueNumbers, [234]);
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
