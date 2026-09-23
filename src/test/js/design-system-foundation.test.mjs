import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const STYLES_URL = new URL("../../main/resources/static/styles.css", import.meta.url);
const INDEX_URL = new URL("../../main/resources/static/index.html", import.meta.url);

async function css() {
  return readFile(STYLES_URL, "utf8");
}

test("真人驗收表單共用間距，列表操作共用樣式 (#626)", async () => {
  const [source, html] = await Promise.all([css(), readFile(INDEX_URL, "utf8")]);
  for (const id of ["workspace-create-form", "wiki-filter-form", "inbox-upload-form",
    "inbox-batch-form", "inbox-filter-form", "proposal-filter-form", "triage-filter-form"]) {
    assert.match(html, new RegExp(`<form id="${id}" class="control-stack"`, "u"));
  }
  assert.match(html, /<div class="control-stack">\s*<div class="field-group">\s*<label for="organize-proposal-select">/u);
  assert.match(source, /\.control-stack\s*\{[^}]*flex-direction:\s*column;[^}]*gap:\s*var\(--space-stack\)/u);
  assert.match(source, /\.control-stack > \.field-group > label\s*\{[^}]*margin-bottom:\s*var\(--space-control\)/u);
  assert.match(source, /#inbox-upload-form \+ #inbox-batch-form\s*\{[^}]*margin-top:\s*var\(--space-section\)/u);
  assert.match(source, /\.inbox-item-meta\s*\{[^}]*flex-wrap:\s*wrap/u);
  assert.match(source, /\.list-action,\s*\.inbox-actions \.list-action\s*\{/u);
});

test("list-action 在連結與按鈕間維持同一視覺契約 (#628)", async () => {
  const source = await css();
  const action = source.match(/\.list-action,\s*\.inbox-actions \.list-action\s*\{([^}]*)\}/u);
  assert.ok(action, "shared list-action rule must own element-neutral styling");
  for (const declaration of [
    /box-sizing:\s*border-box/u,
    /display:\s*inline-flex/u,
    /width:\s*auto/u,
    /font-weight:\s*600/u,
    /transition:/u
  ]) assert.match(action[1], declaration);
  assert.match(source, /\.list-action:hover,\s*\.inbox-actions \.list-action:hover\s*\{[^}]*border-color:\s*var\(--primary\)/u);
  assert.match(source, /\.list-action:focus-visible\s*\{[^}]*outline:\s*3px solid var\(--focus-ring\)/u);
  assert.match(source, /\.list-action:active,\s*\.inbox-actions \.list-action:active\s*\{[^}]*background:\s*var\(--surface-muted\)/u);
  const narrow = source.match(/@media \(max-width: 600px\)\s*\{([^]*?)\n\}/u);
  assert.ok(narrow, "narrow viewport rules must exist");
  assert.match(narrow[1], /button\s*\{\s*width:\s*100%/u, "global button width regression remains represented");
  assert.match(action[1], /width:\s*auto/u, "shared contract overrides global button width at desktop");
});

test("主要產品列表共用卡片式邊界且不升格診斷清單 (#630)", async () => {
  const source = await css();
  const shared = source.match(
    /\.workspace-list-item,\s*\.inbox-item,\s*\.wiki-item,\s*\.proposal-item,\s*\.organize-item,\s*\.triage-item\s*\{([^}]*)\}/u);
  assert.ok(shared, "daily product list items must share one visual surface contract");
  for (const declaration of [
    /padding:\s*var\(--space-3\)/u,
    /border:\s*1px solid var\(--border\)/u,
    /border-radius:\s*var\(--radius-compact\)/u,
    /margin-bottom:\s*var\(--space-3\)/u,
    /min-width:\s*0/u
  ]) assert.match(shared[1], declaration);
  for (const legacy of ["workspace-list-item", "inbox-item", "wiki-item", "proposal-item"]) {
    const rule = source.match(new RegExp(`\\.${legacy}\\s*\\{([^}]*)\\}`, "u"));
    if (rule) assert.doesNotMatch(rule[1], /border-bottom:/u,
      `${legacy} must not keep the old bottom-divider contract`);
  }
  assert.match(source, /\.organize-list:not\(:empty\)\s*\{[^}]*margin-bottom:\s*var\(--space-4\)/u);
  assert.doesNotMatch(shared[0], /citation-item|inspector/u,
    "citations and inspector diagnostics must stay visually subordinate");
});

test("semantic token layer exists with purpose-named roles (#494)", async () => {
  const source = await css();
  for (const token of [
    "--foreground",
    "--foreground-muted",
    "--canvas",
    "--background",
    "--surface",
    "--border",
    "--hairline",
    "--primary",
    "--primary-hover",
    "--primary-pressed",
    "--primary-soft",
    "--critical",
    "--warning",
    "--positive",
    "--informative",
    "--focus-ring"
  ]) {
    assert.match(source, new RegExp(`${token}\\s*:`, "u"), `${token} must be defined`);
  }
  // 視覺更新保留語意角色，改用專案自有的深色／黃銅色票。
  assert.match(source, /--primary\s*:\s*var\(--accent\)/u);
  assert.match(source, /color-scheme:\s*dark/u);
  assert.match(source, /--accent\s*:\s*#d5a956/u);
  assert.match(source, /--canvas\s*:\s*#171614/u);
  assert.match(source, /--surface\s*:\s*var\(--panel\)/u);
  assert.doesNotMatch(source, /#ff6f0f/i, "must not adopt Karrot orange as primary");
});

test("spacing uses 4px-based scale with documented roles (#494)", async () => {
  const source = await css();
  for (const [token, value] of [
    ["--space-1", "4px"], ["--space-2", "8px"], ["--space-3", "12px"],
    ["--space-4", "16px"], ["--space-5", "20px"], ["--space-6", "24px"],
    ["--space-8", "32px"], ["--space-10", "40px"], ["--space-12", "48px"],
    ["--space-14", "56px"], ["--space-16", "64px"]
  ]) {
    assert.match(source, new RegExp(`${token}\\s*:\\s*${value}`, "u"), `${token} must be ${value}`);
  }
  assert.match(source, /--space-section\s*:/u);
  assert.match(source, /--space-control\s*:/u);
  assert.match(source, /--radius-compact\s*:\s*8px/u);
  assert.match(source, /--radius-normal\s*:\s*12px/u);
});

test("typography uses system font with no external webfont (#494)", async () => {
  const source = await css();
  const html = await readFile(INDEX_URL, "utf8");
  assert.match(source, /--font-system\s*:/u);
  assert.match(source, /font-family:\s*var\(--font-system\)/u);
  assert.doesNotMatch(source, /@import\s+url\(/i);
  assert.doesNotMatch(source, /url\(\s*["']?https?:\/\//i);
  assert.doesNotMatch(source, /fonts\.googleapis|fonts\.gstatic|cdn\.jsdelivr|unpkg\.com|cdnjs/i);
  assert.doesNotMatch(html, /fonts\.googleapis|fonts\.gstatic|cdn\.jsdelivr|unpkg\.com|cdnjs/i);
  assert.doesNotMatch(html, /<link[^>]+href="https?:\/\//i);
});

test("stylesheet adds no remote url or frontend framework dependency (#494)", async () => {
  const source = await css();
  const html = await readFile(INDEX_URL, "utf8");
  assert.doesNotMatch(source, /url\(\s*["']?https?:\/\//i);
  assert.doesNotMatch(source, /tailwind|bootstrap|bulma|foundation\.css/i);
  assert.doesNotMatch(html, /<script[^>]+src="https?:\/\//i);
  assert.doesNotMatch(html, /<link[^>]+href="https?:\/\//i);
  const csp = html.match(/Content-Security-Policy[^>]*content="([^"]*)"/u);
  assert.ok(csp, "CSP meta must exist");
  assert.doesNotMatch(csp[1], /https?:\/\//u, "CSP must stay self-only");
});

test("[hidden] authority is preserved by the foundation (#494)", async () => {
  const source = await css();
  assert.match(source, /\[hidden\]\s*\{\s*display\s*:\s*none\s*!important/u);
});

test("primary / secondary / danger / disabled share one contract (#494)", async () => {
  const source = await css();
  const primary = source.match(/button\s*\{[^}]*\}/u);
  assert.ok(primary, "base button (primary) rule must exist");
  assert.match(primary[0], /background\s*:\s*var\(--primary\)/u);
  assert.match(primary[0], /color\s*:\s*var\(--on-primary\)/u);
  assert.match(primary[0], /border-radius\s*:\s*var\(--radius-control\)/u);

  const secondary = source.match(/\.secondary-button\s*\{[^}]*\}/u);
  assert.ok(secondary, ".secondary-button must exist");
  assert.match(secondary[0], /color\s*:\s*var\(--primary-hover\)/u);
  assert.doesNotMatch(secondary[0], /#fff/i, "secondary must not inherit unreadable white text");
  const secondaryHover = source.match(/\.secondary-button:hover\s*\{[^}]*\}/u);
  assert.ok(secondaryHover);
  assert.match(secondaryHover[0], /color\s*:\s*var\(--primary-hover\)/u);

  const danger = source.match(/\.danger-button\s*\{[^}]*\}/u);
  assert.ok(danger, ".danger-button must exist for critical actions only");
  assert.match(danger[0], /background\s*:\s*var\(--critical\)/u);

  const disabled = source.match(/button:disabled\s*\{[^}]*\}/u);
  assert.ok(disabled);
  assert.match(disabled[0], /cursor\s*:\s*not-allowed/u, "disabled must not rely on opacity alone");
  assert.match(disabled[0], /border\s*:/u, "disabled keeps geometry via border");
  assert.match(disabled[0], /background\s*:/u, "disabled keeps a background shift");
});

test("focus-visible is present and discernible (#494)", async () => {
  const source = await css();
  assert.match(source, /:focus-visible\s*\{[^}]*outline/u, "focus-visible must own an outline");
  assert.match(source, /--focus-ring\s*:/u);
  const focusRule = source.match(/[^{}]*:focus-visible\s*\{[^}]*\}/u);
  assert.ok(focusRule);
  assert.match(focusRule[0], /outline-offset/u);
});

test("motion is bounded with prefers-reduced-motion fallback (#494)", async () => {
  const source = await css();
  assert.match(source, /--motion-fast\s*:/u);
  assert.match(source, /--motion-base\s*:/u);
  assert.match(source, /@media\s*\(\s*prefers-reduced-motion\s*:\s*reduce\s*\)/u);
  const reduced = source.match(/@media\s*\(\s*prefers-reduced-motion\s*:\s*reduce\s*\)\s*\{[\s\S]*?\n\}/u);
  assert.ok(reduced);
  assert.match(reduced[0], /transition\s*:\s*none/u);
  assert.match(reduced[0], /animation\s*:\s*none/u);
});

test("shared primitives use tokens, not new one-off raw colors (#494)", async () => {
  const source = await css();
  // Strip the :root token definition block and all comments: raw hex values are
  // only allowed inside :root. Issue references like #494 live in comments and
  // must not be mistaken for colors.
  const withoutRoot = source.replace(/:root\s*\{[^}]*\}/u, "").replace(/\/\*[\s\S]*?\*\//gu, "");
  const rawHex = withoutRoot.match(/#[0-9a-fA-F]{3,8}\b/g) || [];
  // Only the danger pressed/hover aliases were tokenized; no other raw hex may remain
  // outside :root. If this fails, the new rule must use a semantic token instead.
  assert.deepEqual(rawHex, [], `no raw hex outside :root, found: ${rawHex.join(", ")}`);
  assert.match(source, /\.helper-text\s*\{[^}]*\}/u, ".helper-text neutral guidance must exist");
  assert.match(source, /\.inline-feedback--error\s*\{[^}]*\}/u);
  assert.match(source, /input\[type="text"\]/u, "text inputs share the base field contract");
});
