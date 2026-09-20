import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

// Wiring lock for the shared Browser contract runner (Refs #561): CI must
// gate the deterministic suite through scripts/run-browser-contract-tests.sh
// instead of hand-enumerated `node --test` steps, so a new contract test can
// never be silently ungated and a copy/paste duplicate can never hide a gap.
// Behavioral classification completeness (unclassified-file fail-closed) is
// proven by the runner's `check` mode, executed in PR CI and the canary.

const PR_CI = readFileSync(
  new URL("../../../.github/workflows/pr-ci.yml", import.meta.url),
  "utf8"
);
const CANARY = readFileSync(
  new URL("../../../.github/workflows/full-regression-canary.yml", import.meta.url),
  "utf8"
);
const RUNNER = readFileSync(
  new URL("../../../scripts/run-browser-contract-tests.sh", import.meta.url),
  "utf8"
);

function block(name) {
  const after = RUNNER.split(`${name}="`)[1];
  assert.ok(after, `runner must define a ${name} list`);
  return after.split('"')[0];
}

test("PR CI gates Browser contracts through the shared runner, never hand-enumerated steps", () => {
  assert.ok(
    PR_CI.includes("run-browser-contract-tests.sh governance"),
    "PR Metadata job must run the governance set via the runner"
  );
  assert.ok(
    PR_CI.includes("run-browser-contract-tests.sh check"),
    "Fast job must run the classification completeness guard"
  );
  assert.ok(
    PR_CI.includes("run-browser-contract-tests.sh required"),
    "Fast job must run the deterministic suite via the runner"
  );
  assert.ok(
    !PR_CI.includes("node --test src/test/js/"),
    "PR CI must not hand-enumerate Browser contract files anymore"
  );
});

test("Full Regression Canary reuses the same deterministic suite authority", () => {
  assert.ok(
    CANARY.includes("run-browser-contract-tests.sh check"),
    "canary must run the classification completeness guard"
  );
  assert.ok(
    CANARY.includes("run-browser-contract-tests.sh required"),
    "canary must run the deterministic suite via the runner"
  );
});

test("required list holds the deterministic offline suites exactly once, without live interop", () => {
  const required = block("REQUIRED");
  // AC-01: the 7 previously ungated deterministic suites.
  for (const file of [
    "analysis-ui.test.mjs",
    "cross-surface-states.test.mjs",
    "design-system-foundation.test.mjs",
    "hidden-visibility.test.mjs",
    "navigation-ia.test.mjs",
    "owner-auth-ui.test.mjs",
    "quality-ui.test.mjs",
    "browser-suite-manifest.test.mjs"
  ]) {
    assert.ok(required.includes(file), `required list must gate ${file}`);
  }
  // AC-06: live/manual interop must never become required evidence.
  for (const file of ["mcp-sdk-interop.test.mjs", "mcp-modern-interop.test.mjs"]) {
    assert.ok(!required.includes(file), `required list must not contain live ${file}`);
  }
  // AC-02: no duplicate execution of the same file.
  for (const file of ["retrieval-inspector-ui.test.mjs", "source-chunk-inspector-ui.test.mjs"]) {
    const occurrences = required.split(file).length - 1;
    assert.equal(occurrences, 1, `${file} must appear exactly once in required`);
  }
});

test("governance ownership stays explicit and live tests stay classified non-required", () => {
  const governance = block("GOVERNANCE");
  for (const file of [
    "pr-metadata.test.mjs",
    "merge-settings.test.mjs",
    "merge-commit-guard.test.mjs",
    "language-governance.test.mjs"
  ]) {
    assert.ok(governance.includes(file), `governance list must own ${file}`);
  }
  const live = block("LIVE");
  for (const file of ["mcp-sdk-interop.test.mjs", "mcp-modern-interop.test.mjs"]) {
    assert.ok(live.includes(file), `live list must classify ${file} as non-required`);
  }
  assert.ok(
    RUNNER.includes("unclassified or multi-listed"),
    "runner must keep the fail-closed completeness guard"
  );
});
