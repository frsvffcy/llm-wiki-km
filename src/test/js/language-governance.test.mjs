import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import test from "node:test";
import {
  analyzeHumanReadableMarkdown,
  collectCurrentHumanReadableSurfaces,
  runLanguageGovernance,
} from "../../../scripts/check-language-governance.mjs";

// #501：語言治理檢查必須由 required PR CI 自動執行，不得只依賴作者手動勾 checklist。
// 本 suite 鎖定 workflow wiring（執行位置、bypass 禁止、Gate fail-closed）與
// 權威邊界（terminology 文件為唯一詳細來源，checker 是有界且 fail-closed 的 gate）。

async function readRelative(relativePath) {
  return readFile(new URL(`../../../${relativePath}`, import.meta.url), "utf8");
}

function prMetadataSection(workflow) {
  const start = workflow.indexOf("pr-metadata:");
  const end = workflow.indexOf("fast-tests:");
  assert.ok(start !== -1 && end !== -1 && end > start, "pr-ci.yml 必須同時包含 pr-metadata 與 fast-tests job");
  return workflow.slice(start, end);
}

function prGateSection(workflow) {
  const start = workflow.indexOf("pr-gate:");
  assert.ok(start !== -1, "pr-ci.yml 必須包含 pr-gate job");
  return workflow.slice(start);
}

test("在 required upstream job 中執行語言治理檢查", async () => {
  const workflow = await readRelative(".github/workflows/pr-ci.yml");

  assert.match(workflow, /node scripts\/check-language-governance\.mjs/u);

  const section = prMetadataSection(workflow);
  assert.match(
    section,
    /node scripts\/check-language-governance\.mjs/u,
    "語言治理檢查必須位於 pr-metadata job 內（現有 required upstream job），不得只放在文件或 template",
  );

  const gate = prGateSection(workflow);
  assert.match(gate, /pr-metadata/u, "PR Gate 必須將 pr-metadata 列為 needs，檢查失敗才會擋下合併");
});

test("語言治理檢查不得以 warning-only 或 bypass 弱化", async () => {
  const workflow = await readRelative(".github/workflows/pr-ci.yml");
  const section = prMetadataSection(workflow);

  assert.doesNotMatch(section, /continue-on-error/u, "pr-metadata job 不得使用 continue-on-error 弱化治理檢查");

  const checkerLines = workflow
    .split("\n")
    .filter((line) => line.includes("check-language-governance"));
  assert.ok(checkerLines.length >= 1, "pr-ci.yml 必須有執行 checker 的 run 行");
  for (const line of checkerLines) {
    assert.doesNotMatch(line, /\|\|\s*true/u, "checker 執行不得以 || true 繞過失敗");
    assert.doesNotMatch(line, /\|\|\s*exit 0/u, "checker 執行不得以 || exit 0 繞過失敗");
    assert.doesNotMatch(line, /;\s*exit 0/u, "checker 執行不得以 ; exit 0 掩蓋失敗");
  }
  assert.doesNotMatch(workflow, /check-language-governance\.mjs\s*\|\|/u);
});

test("PR Gate 對語言治理檢查維持 fail-closed", async () => {
  const workflow = await readRelative(".github/workflows/pr-ci.yml");
  const gate = prGateSection(workflow);

  assert.match(gate, /if:\s*\$\{\{\s*always\(\)\s*\}\}/u, "PR Gate 必須在 upstream 失敗時仍執行判定");
  assert.match(
    gate,
    /needs\.pr-metadata\.result\s*==\s*'success'/u,
    "PR Gate 成功條件必須要求 pr-metadata（含語言治理檢查）為 success",
  );
  assert.match(
    gate,
    /needs\.pr-metadata\.result\s*!=\s*'success'/u,
    "PR Gate 拒絕條件必須涵蓋 pr-metadata 非 success",
  );
  assert.match(gate, /exit 1/u, "PR Gate 拒絕路徑必須以非零 exit 失敗");
});

test("語言治理契約測試本身由 PR CI 執行", async () => {
  const workflow = await readRelative(".github/workflows/pr-ci.yml");
  const section = prMetadataSection(workflow);

  // Refs #561：governance set 經共用 runner 執行，不再手動列舉；兩層同時鎖定，
  // wiring 才不會被靜默移除。
  assert.match(
    section,
    /run-browser-contract-tests\.sh governance/u,
    "pr-metadata job 必須經共用 runner 執行 governance set",
  );
  const runner = await readRelative("scripts/run-browser-contract-tests.sh");
  assert.match(
    runner,
    /src\/test\/js\/language-governance\.test\.mjs/u,
    "runner 的 governance 清單必須包含本契約測試",
  );
});

test("語言規範以 terminology 文件為唯一詳細來源，checker 是有界的執行關卡", async () => {
  const [checker, terminology] = await Promise.all([
    readRelative("scripts/check-language-governance.mjs"),
    readRelative("docs/development/language-and-terminology.md"),
  ]);

  assert.match(
    terminology,
    /單一規範/u,
    "terminology 文件必須聲明自己是 repository-owned 單一規範來源",
  );
  assert.match(
    checker,
    /language-and-terminology\.md/u,
    "checker 必須指向 terminology 文件，不得自立第二套術語來源",
  );
  assert.match(checker, /不是逐字翻譯器/u, "checker 必須聲明自己不是逐字翻譯器");
  assert.doesNotMatch(checker, /單一規範/u, "checker 不得自稱為單一規範 authority");
});

test("實際 README、AGENTS 與 CURRENT 文件內容由語言 gate 檢查", async () => {
  const repositoryRoot = fileURLToPath(new URL("../../../", import.meta.url));
  const result = await runLanguageGovernance(repositoryRoot);
  assert.deepEqual(result.failures, [], `實際 CURRENT 文件必須通過：\n${result.failures.join("\n")}`);
  assert.ok(result.surfaces.includes("README.md"));
  assert.ok(result.surfaces.includes("AGENTS.md"));
  assert.ok(result.surfaces.includes("docs/architecture/api.md"));
});

test("未登錄英文自然句、純英文標題與表格標籤會 fail closed", () => {
  const fixture = [
    "# This heading should never remain as plain English prose",
    "",
    "This is a long English sentence that should be rejected by the current language gate.",
    "",
    "| Governance owner | Current responsibility |",
    "| --- | --- |",
    "| 團隊 | 維護 |",
  ].join("\n");
  const findings = analyzeHumanReadableMarkdown("README.md", fixture);
  assert.ok(findings.some(({ rule }) => rule === "純英文標題"));
  assert.ok(findings.some(({ rule }) => rule === "未登錄的長英文自然語句"));
  assert.ok(findings.some(({ rule }) => rule === "純英文表格標籤"));
});

test("混合表格中的純英文標籤與未關閉程式碼區塊也會 fail closed", () => {
  const mixedTable = analyzeHumanReadableMarkdown(
    "docs/guides/example.md",
    "| 中文欄位 | Human readable label |\n| --- | --- |",
  );
  assert.ok(mixedTable.some(({ rule }) => rule === "純英文表格標籤"));

  const unclosedFence = analyzeHumanReadableMarkdown(
    "README.md",
    "```text\nThis sentence must not hide behind a malformed fence.",
  );
  assert.ok(unclosedFence.some(({ rule }) => rule === "未關閉的 Markdown 程式碼區塊"));
});

test("治理英文漂移即使混在中文內仍會被指出", () => {
  const findings = analyzeHumanReadableMarkdown("AGENTS.md", "這是 repository authority 的說明。");
  assert.equal(findings.length, 1);
  assert.match(findings[0].rule, /authority/u);
});

test("技術名稱、識別字、路徑、指令與程式碼區塊可保留", () => {
  const fixture = [
    "## SQLite 與 Flyway 設定",
    "執行 `mvn clean verify -Pfull`，並設定 `OPENAI_API_KEY`。",
    "呼叫 `/api/workspaces/{workspaceId}` 後檢查 `READY_TO_USE`。",
    "```java",
    "public final class CurrentAuthorityProvider {",
    "  // This sentence is source code and is not human-readable documentation.",
    "}",
    "```",
  ].join("\n");
  assert.deepEqual(analyzeHumanReadableMarkdown("docs/guides/example.md", fixture), []);
});

test("外部原文必須用明確 marker 才能排除", () => {
  const quoted = "This is a long English quotation that must otherwise fail the current language gate.";
  assert.ok(analyzeHumanReadableMarkdown("README.md", `> ${quoted}`).length > 0);
  assert.deepEqual(
    analyzeHumanReadableMarkdown("README.md", `<!-- language-governance: external-quote -->\n> ${quoted}`),
    [],
  );
});

test("CURRENT 掃描範圍排除歷史證據並在列舉失敗時關閉關卡", async () => {
  const repositoryRoot = fileURLToPath(new URL("../../../", import.meta.url));
  const surfaces = await collectCurrentHumanReadableSurfaces(repositoryRoot);
  assert.ok(surfaces.every((path) => !path.startsWith("docs/adr/")));
  assert.ok(surfaces.every((path) => !path.startsWith("docs/evaluations/")));
  assert.ok(surfaces.every((path) => !path.startsWith("docs/architecture/legacy/")));

  const missingRoot = fileURLToPath(new URL("./definitely-missing-repository/", import.meta.url));
  const result = await runLanguageGovernance(missingRoot);
  assert.equal(result.failures.length, 1);
  assert.match(result.failures[0], /無法列舉 CURRENT 文件/u);
});
