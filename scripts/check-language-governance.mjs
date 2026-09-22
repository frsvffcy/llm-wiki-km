#!/usr/bin/env node

/**
 * CURRENT 人類可讀介面的可執行語言 gate。
 *
 * 詳細語意與術語仍以 docs/development/language-and-terminology.md 為準。本檢查
 * 只做 bounded、可重現的結構與自然語句偵測；它不是逐字翻譯器，也不掃描
 * 歷史證據、舊版架構文件或外部引用原文。
 */

import { readFile, readdir } from "node:fs/promises";
import { fileURLToPath, pathToFileURL } from "node:url";
import { dirname, resolve, sep } from "node:path";

export const REQUIRED_CURRENT_SURFACES = [
  "AGENTS.md",
  "README.md",
  "docs/README.md",
  "docs/development/language-and-terminology.md",
  "docs/guides/getting-started-zh-TW.md",
];

const HISTORICAL_ROOTS = ["docs/adr/", "docs/architecture/legacy/", "docs/evaluations/"];

const TECHNICAL_ALLOWLIST = new Set([
  "api", "arcadedb", "ascii", "browser", "ci", "cli", "codex", "commit",
  "conventional", "cors", "css", "csv", "docker", "dom", "enum", "flyway",
  "fts", "fts5", "git", "github", "graph", "html", "http", "https", "id",
  "java", "javascript", "jdbc", "jooq", "json", "jwt", "linux", "llm",
  "markdown", "maven", "mcp", "oauth", "openai", "pr", "rag", "rest",
  "sha", "spring", "sql", "sqlite", "sse", "toml", "typescript", "ui",
  "unix", "uri", "url", "uuid", "web", "webhook", "windows", "yaml", "zh", "tw",
]);

const NATURAL_FUNCTION_WORDS = new Set([
  "a", "an", "and", "are", "as", "at", "be", "been", "but", "by", "can",
  "for", "from", "has", "have", "if", "in", "into", "is", "it", "its",
  "must", "not", "of", "on", "only", "or", "should", "that", "the", "their",
  "then", "this", "to", "use", "used", "using", "when", "where", "which",
  "will", "with", "without",
]);

const GOVERNANCE_DRIFT = new Map([
  ["authority", "權威來源／權限"], ["invariant", "不變條件"],
  ["currentness", "現行性"], ["freshness", "新鮮度"], ["scope", "範圍"],
  ["handoff", "交接"], ["pipeline", "處理流程"], ["fallback", "備援"],
  ["admission", "准入"], ["candidate", "候選項"], ["baseline", "基準"],
  ["benchmark", "基準測試"], ["gate", "關卡"], ["governance", "治理"],
  ["audit", "稽核"], ["evidence", "證據"], ["derived", "衍生"],
  ["rebuildable", "可重建"], ["bounded", "有界"],
]);

const ONBOARDING_NAVIGATION_SURFACES = new Set([
  "README.md",
  "docs/guides/getting-started-zh-TW.md",
]);
const CURRENT_ONBOARDING_LABELS = ["「文件」", "「知識」", "「待我審核」"];
const LEGACY_ONBOARDING_LABELS = ["「收件匣」", "「Wiki」", "「審核」"];

const currentUiFiles = [
  "src/main/resources/static/index.html", "src/main/resources/static/graph-operations-ui.js",
  "src/main/resources/static/quality-ui.js", "src/main/resources/static/retrieval-inspector-ui.js",
  "src/main/resources/static/wiki-ui.js", "src/main/resources/static/review-ui.js",
  "src/main/resources/static/inbox-ui.js", "src/main/resources/static/source-chunk-inspector-ui.js",
  "src/main/resources/static/ask-ui.js", "src/main/resources/static/analysis-ui.js",
  "src/main/resources/static/workspace-ui.js", "src/main/resources/static/owner-auth-ui.js",
  "src/main/resources/static/navigation-ui.js",
];

const forbiddenUiPatterns = [
  ["查看", /查看/gu], ["登录", /登录/gu], ["数据", /数据/gu], ["信息", /信息/gu], ["默认", /默认/gu],
  ["Graph projection 的可見文案", /Graph projection(?:功能|尚未|正在|已|目前|需要|服務|資料|狀態|操作|未通過)/gu],
  ["readiness 檢查", /readiness\s+檢查/gu], ["canonical 內容", /canonical\s+內容/gu],
  ["policy：", /(?:^|["`])policy：/gu], ["fusion policy：", /(?:^|["`])fusion policy：/gu],
  ["revision 顯示", /(?:^|["`])[^\n"`]*\brevision\s+\$?\{/gu],
  ["target：顯示", /(?:^|["`])[^\n"`]*\btarget：/gu],
];

function normalizePath(path) { return path.split(sep).join("/"); }
function isHistorical(path) { return HISTORICAL_ROOTS.some((root) => path.startsWith(root)); }

async function walkMarkdown(root, directory = "docs") {
  const entries = await readdir(resolve(root, directory), { withFileTypes: true });
  const files = [];
  for (const entry of entries) {
    const child = normalizePath(`${directory}/${entry.name}`);
    if (isHistorical(`${child}${entry.isDirectory() ? "/" : ""}`)) continue;
    if (entry.isDirectory()) files.push(...await walkMarkdown(root, child));
    else if (entry.isFile() && entry.name.endsWith(".md")) files.push(child);
  }
  return files;
}

export async function collectCurrentHumanReadableSurfaces(root) {
  const selected = new Set(REQUIRED_CURRENT_SURFACES);
  let docs;
  try { docs = await walkMarkdown(root); }
  catch (error) { throw new Error(`docs/: 無法列舉 CURRENT 文件（${error.code || error.message}）`); }
  for (const relativePath of docs) {
    if (relativePath.startsWith("docs/guides/")) selected.add(relativePath);
    let source;
    try { source = await readFile(resolve(root, relativePath), "utf8"); }
    catch (error) { throw new Error(`${relativePath}: 無法讀取以判定 CURRENT 狀態（${error.code || error.message}）`); }
    if (/^>\s*狀態：`?CURRENT`?(?:[。.]|\s|$)/mu.test(source)) selected.add(relativePath);
  }
  return [...selected].sort();
}

function stripMarkdownSyntax(line) {
  return line.replace(/<!--.*?-->/gu, " ").replace(/`[^`]*`/gu, " ")
    .replace(/!?\[([^\]]*)\]\([^)]*\)/gu, "$1").replace(/https?:\/\/\S+/gu, " ")
    .replace(/<[^>]+>/gu, " ").replace(/^\s{0,3}(?:#{1,6}|[-*+] |>+)\s*/u, "")
    .replace(/[|*_~()[\]{}<>]/gu, " ");
}

function englishTokens(text) { return text.match(/[A-Za-z][A-Za-z'-]*/gu) || []; }
function isIdentifierOrContract(token) {
  return /[A-Z].*[A-Z]/u.test(token) || /[a-z][A-Z]/u.test(token) || /_/u.test(token)
    || /^v?\d/u.test(token) || /^[A-Z][A-Za-z]*\d+[A-Za-z\d]*$/u.test(token);
}
function residualTokens(text) {
  return englishTokens(text).filter((token) => !TECHNICAL_ALLOWLIST.has(token.toLowerCase()) && !isIdentifierOrContract(token));
}
function lineKind(raw) {
  if (/^\s{0,3}#{1,6}\s+/u.test(raw)) return "heading";
  if (/^\s*\|.*\|\s*$/u.test(raw) && !/^\s*\|?(?:\s*:?-+:?\s*\|)+\s*$/u.test(raw)) return "table";
  return "prose";
}
function short(text) {
  const compact = text.trim().replace(/\s+/gu, " ");
  return compact.length > 100 ? `${compact.slice(0, 97)}...` : compact;
}

export function analyzeHumanReadableMarkdown(relativePath, source) {
  const findings = [];
  const lines = source.split(/\r?\n/u);
  let inFence = false;
  let externalQuote = false;
  for (let index = 0; index < lines.length; index += 1) {
    const raw = lines[index];
    if (/^\s*(```|~~~)/u.test(raw)) { inFence = !inFence; continue; }
    if (inFence) continue;
    if (/^\s*<!--\s*language-governance:\s*external-quote\s*-->\s*$/u.test(raw)) { externalQuote = true; continue; }
    if (externalQuote && /^\s*>/u.test(raw)) continue;
    if (externalQuote && raw.trim() === "") continue;
    externalQuote = false;
    const text = stripMarkdownSyntax(raw).trim();
    if (!text || /^[-:\s]+$/u.test(text)) continue;
    const tokens = residualTokens(text);
    const kind = lineKind(raw);
    const hasHan = /\p{Script=Han}/u.test(text);
    const functionWords = tokens.filter((token) => NATURAL_FUNCTION_WORDS.has(token.toLowerCase()));
    const nextLineIsTableDivider = /^\s*\|?(?:\s*:?-+:?\s*\|)+\s*$/u.test(lines[index + 1] || "");
    if (kind === "table" && nextLineIsTableDivider) {
      const englishCell = raw.split("|").map((cell) => stripMarkdownSyntax(cell).trim()).find((cell) => {
        const cellTokens = residualTokens(cell);
        return cellTokens.length >= 2 && !/\p{Script=Han}/u.test(cell);
      });
      if (englishCell) {
        findings.push({ relativePath, line: index + 1, rule: "純英文表格標籤", excerpt: short(englishCell) });
        continue;
      }
    }
    if (!hasHan && (kind === "heading" || kind === "table") && tokens.length >= 2) {
      findings.push({ relativePath, line: index + 1, rule: `純英文${kind === "heading" ? "標題" : "表格標籤"}`, excerpt: short(raw) });
      continue;
    }
    if (tokens.length >= 7 && functionWords.length >= 2) {
      findings.push({ relativePath, line: index + 1, rule: "未登錄的長英文自然語句", excerpt: short(raw) });
      continue;
    }
    // 短標籤與標題中的治理詞最容易直接出現在人類介面；長段落則由自然語句
    // 規則處理，避免把含大量 class／status／契約字面值的技術句誤判成翻譯問題。
    const drift = tokens.find((token) => GOVERNANCE_DRIFT.has(token.toLowerCase()));
    if (drift && (kind !== "prose" || tokens.length <= 4)) {
      findings.push({ relativePath, line: index + 1, rule: `治理詞彙漂移：${drift} → ${GOVERNANCE_DRIFT.get(drift.toLowerCase())}`, excerpt: short(raw) });
    }
  }
  if (inFence) findings.push({ relativePath, line: lines.length, rule: "未關閉的 Markdown 程式碼區塊", excerpt: short(lines.at(-1) || "") });
  return findings;
}

function requireText(failures, path, source, expected) {
  if (!source.includes(expected)) failures.push(`${path}: 找不到必要入口「${expected}」`);
}

export function analyzeOnboardingNavigationCopy(relativePath, source) {
  if (!ONBOARDING_NAVIGATION_SURFACES.has(relativePath)) return [];
  const findings = [];
  for (const label of CURRENT_ONBOARDING_LABELS) {
    if (!source.includes(label)) findings.push(`缺少現行操作入口 ${label}`);
  }
  for (const label of LEGACY_ONBOARDING_LABELS) {
    if (source.includes(label)) findings.push(`仍以舊導覽名稱 ${label} 指示操作`);
  }
  return findings;
}

export async function runLanguageGovernance(root) {
  const failures = [];
  let surfaces;
  try { surfaces = await collectCurrentHumanReadableSurfaces(root); }
  catch (error) { return { failures: [error.message], surfaces: [] }; }
  const sources = new Map();
  for (const path of surfaces) {
    try {
      const source = await readFile(resolve(root, path), "utf8");
      sources.set(path, source);
      for (const finding of analyzeHumanReadableMarkdown(path, source)) failures.push(`${finding.relativePath}:${finding.line}: ${finding.rule}（${finding.excerpt}）`);
    } catch (error) { failures.push(`${path}: 無法讀取（${error.code || error.message}）`); }
  }
  requireText(failures, "README.md", sources.get("README.md") || "", "docs/guides/getting-started-zh-TW.md");
  requireText(failures, "README.md", sources.get("README.md") || "", "docs/development/language-and-terminology.md");
  requireText(failures, "docs/README.md", sources.get("docs/README.md") || "", "guides/getting-started-zh-TW.md");
  requireText(failures, "docs/README.md", sources.get("docs/README.md") || "", "development/language-and-terminology.md");
  requireText(failures, "AGENTS.md", sources.get("AGENTS.md") || "", "docs/development/language-and-terminology.md");
  for (const path of ONBOARDING_NAVIGATION_SURFACES) {
    const source = sources.get(path) || "";
    for (const finding of analyzeOnboardingNavigationCopy(path, source)) {
      failures.push(`${path}: ${finding}`);
    }
  }
  for (const path of currentUiFiles) {
    let source;
    try { source = await readFile(resolve(root, path), "utf8"); }
    catch (error) { failures.push(`${path}: 無法讀取（${error.code || error.message}）`); continue; }
    for (const [label, pattern] of forbiddenUiPatterns) {
      pattern.lastIndex = 0;
      if (pattern.test(source)) failures.push(`${path}: 發現待人工檢視的「${label}」`);
    }
  }
  return { failures, surfaces };
}

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const isDirectRun = process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href;
if (isDirectRun) {
  const { failures, surfaces } = await runLanguageGovernance(repositoryRoot);
  if (failures.length > 0) {
    console.error("語言治理檢查未通過：");
    for (const failure of failures) console.error(`- ${failure}`);
    process.exitCode = 1;
  } else console.log(`語言治理檢查通過：已檢查 ${surfaces.length} 份 CURRENT 人類可讀文件與 ${currentUiFiles.length} 個現行瀏覽器介面。`);
}
