import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { promisify } from "node:util";
import { fileURLToPath } from "node:url";

import {
  RELEASE_SHARED_CREDENTIAL_KEYS,
  inspectPath,
  isSafePlaceholder,
  scanText
} from "../../../scripts/check-repo-public-hygiene.mjs";

const execFileAsync = promisify(execFile);
const SCANNER = fileURLToPath(
  new URL("../../../scripts/check-repo-public-hygiene.mjs", import.meta.url)
);
const RELEASE_HYGIENE = fileURLToPath(
  new URL("../../../scripts/check-release-bundle-hygiene.sh", import.meta.url)
);

function fakeOpenAiToken() {
  return ["sk", "-", "A1b2C3d4E5f6G7h8I9j0K1L2M3N4"].join("");
}

function fakeGithubToken() {
  return ["gh", "p_", "AbCdEf0123456789GhIjKlMnOpQrStUv"].join("");
}

function privateKeyHeader() {
  return ["-----BEGIN ", "PRIVATE KEY-----"].join("");
}

function macPrivatePath() {
  return ["/Users/", "alice", "/private-vault/note.md"].join("");
}

function linuxPrivatePath() {
  return ["/home/", "builduser", "/workspace/private/note.md"].join("");
}

function windowsPrivatePath() {
  return ["C:", "\\", "Users", "\\", "devname", "\\", "private", "\\", "note.md"].join("");
}

function safeEnvReference() {
  return ["OPENAI_API_KEY", "=", "$", "{", "OPENAI_API_KEY", "}"].join("");
}

async function git(root, args) {
  return execFileAsync("git", args, { cwd: root, encoding: "utf8" });
}

async function initRepo() {
  const root = await mkdtemp(path.join(os.tmpdir(), "repo-hygiene-test-"));
  await git(root, ["init", "-q"]);
  await git(root, ["config", "user.email", "test@example.invalid"]);
  await git(root, ["config", "user.name", "Repo Hygiene Test"]);
  await writeFile(path.join(root, "README.md"), "# safe\n", "utf8");
  await git(root, ["add", "README.md"]);
  await git(root, ["commit", "-qm", "test: baseline"]);
  return root;
}

async function runScanner(root, args) {
  try {
    const result = await execFileAsync(process.execPath, [SCANNER, "--root", root, ...args], {
      cwd: root,
      encoding: "utf8"
    });
    return { code: 0, stdout: result.stdout, stderr: result.stderr };
  } catch (error) {
    return {
      code: Number.isInteger(error.code) ? error.code : 1,
      stdout: String(error.stdout ?? ""),
      stderr: String(error.stderr ?? "")
    };
  }
}

test("safe placeholders and environment references remain allowed", () => {
  assert.equal(isSafePlaceholder("<OPENAI_API_KEY>"), true);
  assert.equal(isSafePlaceholder("$" + "{OPENAI_API_KEY}"), true);
  assert.equal(isSafePlaceholder("YOUR_API_KEY"), true);
  assert.equal(isSafePlaceholder("change-me"), true);

  const safe = [
    "OPENAI_API_KEY",
    safeEnvReference(),
    "OPENAI_API_KEY=<OPENAI_API_KEY>",
    "authorization: Bearer <TOKEN>",
    "/Users/you/project",
    "/home/example/workspace"
  ].join("\n");
  assert.deepEqual(scanText("docs/example.md", safe), []);
});

test("credential shapes, private-key blocks, assignments and home paths are categorized", () => {
  const assigned = ["OPENAI_API_KEY", "=", fakeOpenAiToken()].join("");
  const hardcoded = ["password", "=", "\"", "AbCd1234!EfGh5678@IjKl9012", "\""].join("");
  const text = [
    fakeOpenAiToken(),
    fakeGithubToken(),
    privateKeyHeader(),
    assigned,
    hardcoded,
    macPrivatePath(),
    linuxPrivatePath(),
    windowsPrivatePath()
  ].join("\n");

  const categories = scanText("docs/unsafe.md", text).map(item => item.category);
  assert.ok(categories.includes("CREDENTIAL_SHAPE"));
  assert.ok(categories.includes("PRIVATE_KEY_BLOCK"));
  assert.ok(categories.includes("ASSIGNED_CREDENTIAL"));
  assert.ok(categories.includes("HARDCODED_SECRET"));
  assert.ok(categories.filter(category => category === "DEVELOPER_ABSOLUTE_PATH").length >= 3);
});

test("private runtime paths fail while documented example env files remain allowed", () => {
  assert.equal(inspectPath("vault/private.md").category, "PRIVATE_RUNTIME_ARTIFACT");
  assert.equal(inspectPath("archive/source.md").category, "PRIVATE_RUNTIME_ARTIFACT");
  assert.equal(inspectPath("data/knowledge.db").category, "PRIVATE_RUNTIME_ARTIFACT");
  assert.equal(inspectPath("runtime/knowledge.db-wal").category, "PRIVATE_RUNTIME_ARTIFACT");
  assert.equal(inspectPath(".env").category, "ENV_FILE");
  assert.equal(inspectPath(".env.local").category, "ENV_FILE");
  assert.equal(inspectPath("credentials.json").category, "CREDENTIAL_FILE");
  assert.equal(inspectPath(".env.example"), null);
  assert.equal(inspectPath(".env.template.local"), null);
  assert.equal(inspectPath("src/main/java/org/km/llmwiki/graph/GraphEntity.java"), null);
});

test("tracked scan fails without echoing matched credential or raw source line", async t => {
  const root = await initRepo();
  t.after(() => rm(root, { recursive: true, force: true }));

  await mkdir(path.join(root, "docs"), { recursive: true });
  const token = fakeOpenAiToken();
  const rawLine = ["provider-token: ", token].join("");
  await writeFile(path.join(root, "docs", "unsafe.md"), rawLine + "\n", "utf8");
  await git(root, ["add", "docs/unsafe.md"]);
  await git(root, ["commit", "-qm", "test: unsafe fixture"]);

  const result = await runScanner(root, ["--mode", "tracked"]);
  assert.notEqual(result.code, 0);
  assert.match(result.stderr, /CREDENTIAL_SHAPE docs\/unsafe\.md:1/u);
  assert.doesNotMatch(result.stderr, new RegExp(token, "u"));
  assert.equal(result.stderr.includes(rawLine), false);
});

test("staged mode reads the Git index and rejects a private artifact path", async t => {
  const root = await initRepo();
  t.after(() => rm(root, { recursive: true, force: true }));

  await writeFile(path.join(root, ".env"), "", "utf8");
  await git(root, ["add", ".env"]);

  const result = await runScanner(root, ["--mode", "staged"]);
  assert.notEqual(result.code, 0);
  assert.match(result.stderr, /ENV_FILE \.env/u);
});

test("changed mode scans the PR-equivalent commit range", async t => {
  const root = await initRepo();
  t.after(() => rm(root, { recursive: true, force: true }));

  const base = (await git(root, ["rev-parse", "HEAD"])).stdout.trim();
  await writeFile(path.join(root, "notes.md"), macPrivatePath() + "\n", "utf8");
  await git(root, ["add", "notes.md"]);
  await git(root, ["commit", "-qm", "test: changed unsafe fixture"]);

  const result = await runScanner(root, [
    "--mode", "changed", "--base", base, "--head", "HEAD"
  ]);
  assert.notEqual(result.code, 0);
  assert.match(result.stderr, /DEVELOPER_ABSOLUTE_PATH notes\.md:1/u);
});

test("safe tracked tree passes and changed-mode infrastructure failure is fail-closed", async t => {
  const root = await initRepo();
  t.after(() => rm(root, { recursive: true, force: true }));

  const safe = await runScanner(root, ["--mode", "tracked"]);
  assert.equal(safe.code, 0);
  assert.match(safe.stdout, /PASS: tracked scan/u);

  const broken = await runScanner(root, [
    "--mode", "changed", "--base", "definitely-missing-base", "--head", "HEAD"
  ]);
  assert.notEqual(broken.code, 0);
  assert.match(broken.stderr, /FAIL: SCANNER_OPERATION_FAILED/u);
  assert.doesNotMatch(broken.stderr, /fatal:|ambiguous argument|unknown revision/iu);
});

test("release bundle and repository scanners retain the shared credential contract", async () => {
  const releaseScript = await readFile(RELEASE_HYGIENE, "utf8");
  for (const key of RELEASE_SHARED_CREDENTIAL_KEYS) {
    assert.match(releaseScript, new RegExp(key, "u"), key + " stays covered by release hygiene");
  }
  assert.match(releaseScript, /gh\[pousr\]_/u);
  assert.match(releaseScript, /sk-/u);
  assert.match(releaseScript, /\/Users\//u);
  assert.match(releaseScript, /\/home\//u);
  assert.match(releaseScript, /C:\\\\/u);
});
