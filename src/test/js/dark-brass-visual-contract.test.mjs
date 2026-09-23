import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const staticRoot = new URL("../../main/resources/static/", import.meta.url);

test("深色品牌列沿用既有路由，提問與引用仍由動態結果承載 (#624)", async () => {
  const html = await readFile(new URL("index.html", staticRoot), "utf8");
  const css = await readFile(new URL("styles.css", staticRoot), "utf8");
  assert.match(html, /<header class="site-topbar">/u);
  assert.match(html, /class="site-brand" href="#\/home"/u);
  assert.match(css, /\.site-topbar\s*\{[^}]*position:\s*sticky/u);
  assert.match(css, /\.app-nav\s*\{[^}]*background:\s*var\(--surface\)/u);
  assert.match(css, /\[data-view-heading\]\s*\{[^}]*scroll-margin-top/u);
  assert.match(css, /\.ask-panel\s*\{/u);
  assert.match(css, /\.citation-item\s*\{/u);
  for (const id of ["ask-form", "question", "retrieval-mode", "ask-submit", "ask-result",
    "result-empty", "result-error", "result-insufficient", "result-answer", "answer-text", "citations"]) {
    assert.match(html, new RegExp(`id="${id}"`, "u"));
  }
  assert.match(html, /<div id="result-answer" hidden>/u);
  assert.match(html, /<ol id="citations" class="citation-list"><\/ol>/u);
  assert.doesNotMatch(html, /1,284|淨利潤|相關度\s*\d+%|資料不離開你的裝置/u);
  assert.doesNotMatch(html + css, /fonts\.googleapis|fonts\.gstatic|Manrope|Sora/u);
});
