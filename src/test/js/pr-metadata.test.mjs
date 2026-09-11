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

for (const reference of ["Refs #234", "Implements #234", "Related to #234", "#234"]) {
  test(`accepts non-closing reference "${reference}" with an existing Issue`, async () => {
    const result = await validatePrMetadata(event(`## 相關 Issue\n\n${reference}`), {
      issueLookup: existingIssue,
    });

    assert.equal(result.valid, true);
    assert.deepEqual(result.referencedIssueNumbers, [234]);
    assert.deepEqual(result.closingIssueNumbers, []);
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
    const result = await validatePrMetadata(event(`${keyword} #234\n\nRefs #234`), {
      issueLookup: existingIssue,
    });

    assert.equal(result.valid, false);
    assert.deepEqual(result.closingIssueNumbers, [234]);
    assert.match(result.errors.join("\n"), /自動關閉 Issue/u);
  });
}

test("rejects closing keyword with colon separator and with issue URL", async () => {
  const colon = await validatePrMetadata(event("Closes: #234"), {
    issueLookup: existingIssue,
  });
  const url = await validatePrMetadata(
    event("Fixes https://github.com/frsvffcy/llm-wiki-km/issues/234"),
    { issueLookup: existingIssue },
  );

  assert.equal(colon.valid, false);
  assert.deepEqual(colon.closingIssueNumbers, [234]);
  assert.equal(url.valid, false);
  assert.deepEqual(url.closingIssueNumbers, [234]);
});

test("does not mistake ordinary prose for a closing keyword", async () => {
  const result = await validatePrMetadata(
    event("## 摘要\n\nThis prefix hotfix resolves the flaky test.\n\nRefs #234"),
    { issueLookup: existingIssue },
  );

  assert.equal(result.valid, true);
  assert.deepEqual(result.closingIssueNumbers, []);
});

test("accepts an explicit non-Issue-driven exception without closing dependencies", async () => {
  const result = await validatePrMetadata(
    event("Related to #120\nDepends on #121\n\nPR-Metadata-Exception: non-issue-driven"),
    { issueLookup: existingIssue },
  );

  assert.equal(result.valid, true);
  assert.equal(result.exception, "non-issue-driven");
});

test("rejects a missing linkage on main", async () => {
  const missing = await validatePrMetadata(event("No issue linkage"), {
    issueLookup: existingIssue,
  });

  assert.equal(missing.valid, false);
  assert.match(missing.errors.join("\n"), /至少一個 Issue reference/u);
});

test("requires an auditable stacked marker for a non-main base", async () => {
  const missing = await validatePrMetadata(event("Refs #234", "feature/parent"), {
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

test("rejects a closing keyword on a stacked base as well", async () => {
  const result = await validatePrMetadata(
    event("Closes #234\nPR-Metadata-Exception: stacked-pr", "feature/parent"),
    { issueLookup: existingIssue },
  );

  assert.equal(result.valid, false);
  assert.match(result.errors.join("\n"), /自動關閉 Issue/u);
});

test("rejects a reference whose same-repository Issue does not exist", async () => {
  const result = await validatePrMetadata(event("Refs #999"), {
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
Refs #234
  `);

  assert.deepEqual(inspected.closingIssueNumbers, []);
  assert.deepEqual(inspected.referencedIssueNumbers, [234]);
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
