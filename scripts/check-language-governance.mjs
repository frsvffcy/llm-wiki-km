#!/usr/bin/env node

/**
 * Repository 語言規範的狹義回歸提醒。
 *
 * 本檢查刻意只涵蓋 current 入口與瀏覽器使用者可見文案；它不是翻譯
 * linter。identifier、API 契約、歷史內容與深層技術文件仍需人工審查。
 */

import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const failures = [];

async function read(relativePath) {
  try {
    return await readFile(resolve(repositoryRoot, relativePath), "utf8");
  } catch (error) {
    failures.push(`${relativePath}: 無法讀取（${error.code || "未知錯誤"}）`);
    return "";
  }
}

function requireText(relativePath, source, expected) {
  if (!source.includes(expected)) {
    failures.push(`${relativePath}: 找不到必要入口「${expected}」`);
  }
}

const requiredFiles = [
  "AGENTS.md",
  "README.md",
  "docs/README.md",
  "docs/development/language-and-terminology.md",
  "docs/guides/getting-started-zh-TW.md"
];

for (const relativePath of requiredFiles) {
  await read(relativePath);
}

const readme = await read("README.md");
requireText("README.md", readme, "docs/guides/getting-started-zh-TW.md");
requireText("README.md", readme, "docs/development/language-and-terminology.md");

const docsReadme = await read("docs/README.md");
requireText("docs/README.md", docsReadme, "guides/getting-started-zh-TW.md");
requireText("docs/README.md", docsReadme, "development/language-and-terminology.md");

const agents = await read("AGENTS.md");
requireText("AGENTS.md", agents, "docs/development/language-and-terminology.md");

const currentUiFiles = [
  "src/main/resources/static/index.html",
  "src/main/resources/static/graph-operations-ui.js",
  "src/main/resources/static/quality-ui.js",
  "src/main/resources/static/retrieval-inspector-ui.js",
  "src/main/resources/static/wiki-ui.js",
  "src/main/resources/static/review-ui.js",
  "src/main/resources/static/inbox-ui.js",
  "src/main/resources/static/source-chunk-inspector-ui.js",
  "src/main/resources/static/ask-ui.js",
  "src/main/resources/static/analysis-ui.js",
  "src/main/resources/static/workspace-ui.js",
  "src/main/resources/static/owner-auth-ui.js",
  "src/main/resources/static/navigation-ui.js"
];

// 這些是本輪 bounded 盤點找到的使用者可見漂移模式。
// 清單保持短小且精確，避免技術 identifier 與歷史敘述產生誤報。
const forbiddenPatterns = [
  ["查看", /查看/gu],
  ["登录", /登录/gu],
  ["数据", /数据/gu],
  ["信息", /信息/gu],
  ["默认", /默认/gu],
  ["Graph projection 的可見文案", /Graph projection(?:功能|尚未|正在|已|目前|需要|服務|資料|狀態|操作|未通過)/gu],
  ["readiness 檢查", /readiness\s+檢查/gu],
  ["canonical 內容", /canonical\s+內容/gu],
  ["policy：", /(?:^|["`])policy：/gu],
  ["fusion policy：", /(?:^|["`])fusion policy：/gu],
  ["revision 顯示", /(?:^|["`])[^\n"`]*\brevision\s+\$?\{/gu],
  ["target：顯示", /(?:^|["`])[^\n"`]*\btarget：/gu]
];

for (const relativePath of currentUiFiles) {
  const source = await read(relativePath);
  for (const [label, pattern] of forbiddenPatterns) {
    pattern.lastIndex = 0;
    if (pattern.test(source)) {
      failures.push(`${relativePath}: 發現待人工檢視的「${label}」`);
    }
  }
}

if (failures.length > 0) {
  console.error("語言治理檢查未通過：");
  for (const failure of failures) console.error(`- ${failure}`);
  process.exitCode = 1;
} else {
  console.log(`語言治理檢查通過：已確認 ${requiredFiles.length} 份入口文件與 ${currentUiFiles.length} 個 current UI surface。`);
}
