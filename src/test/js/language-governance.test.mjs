import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

// #501：語言治理檢查必須由 required PR CI 自動執行，不得只依賴作者手動勾 checklist。
// 本 suite 鎖定 workflow wiring（執行位置、bypass 禁止、Gate fail-closed）與
// authority 邊界（terminology 文件為唯一詳細來源，checker 僅為 bounded 提醒）。

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

  assert.match(
    section,
    /src\/test\/js\/language-governance\.test\.mjs/u,
    "pr-metadata job 的 guard tests 必須包含本契約測試，wiring 才不會被靜默移除",
  );
});

test("語言規範以 terminology 文件為唯一詳細來源，checker 僅為 bounded 提醒", async () => {
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
  assert.match(checker, /不是翻譯/u, "checker 必須聲明自己不是完整翻譯 linter");
  assert.doesNotMatch(checker, /單一規範/u, "checker 不得自稱為單一規範 authority");
});

test("語言治理檢查維持 bounded scope，不掃描不可變歷史證據", async () => {
  const checker = await readRelative("scripts/check-language-governance.mjs");

  for (const historical of ["docs/adr/", "docs/evaluations/", "architecture/legacy"]) {
    assert.ok(
      !checker.includes(historical),
      `checker 不得掃描不可變歷史證據（${historical}），維持 touched-when-edited`,
    );
  }
});
