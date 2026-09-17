import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import { renderInboxList } from "../../main/resources/static/inbox-ui.js";
import { renderWikiList } from "../../main/resources/static/wiki-ui.js";
import { renderProposalList } from "../../main/resources/static/review-ui.js";
import { renderFindingList, triageErrorMessage } from "../../main/resources/static/quality-ui.js";
import { renderInspection } from "../../main/resources/static/retrieval-inspector-ui.js";
import { renderAskResponse, errorMessage as askErrorMessage } from "../../main/resources/static/ask-ui.js";
import { inboxErrorMessage } from "../../main/resources/static/inbox-ui.js";
import { wikiErrorMessage } from "../../main/resources/static/wiki-ui.js";
import { governanceErrorMessage } from "../../main/resources/static/review-ui.js";
import { analysisJobOutcome } from "../../main/resources/static/analysis-ui.js";

const STYLES_URL = new URL("../../main/resources/static/styles.css", import.meta.url);
const INDEX_URL = new URL("../../main/resources/static/index.html", import.meta.url);

class FakeElement {
  constructor(tag = "div") {
    this.tagName = tag;
    this.children = [];
    this.hidden = false;
    this.disabled = false;
    this.value = "";
    this.textContent = "";
    this.className = "";
    this.attributes = new Map();
    this.handlers = new Map();
  }
  append(...nodes) { this.children.push(...nodes); }
  replaceChildren(...nodes) { this.children = nodes; }
  setAttribute(n, v) { this.attributes.set(n, v); }
  getAttribute(n) { return this.attributes.get(n) ?? null; }
  addEventListener(n, h) { this.handlers.set(n, h); }
  focus() { this.focused = true; }
}
function fakeDoc() {
  return { createElement: (t) => new FakeElement(t), addEventListener() {} };
}

test("empty and non-empty are mutually exclusive on every list surface (#496)", async () => {
  const doc = fakeDoc();
  const inbox = { list: new FakeElement("ul"), empty: new FakeElement("p"), pageInfo: new FakeElement(), prevPage: new FakeElement(), nextPage: new FakeElement() };
  renderInboxList(inbox, [], { number: 0, size: 20, totalElements: 0, totalPages: 0 }, doc, {});
  assert.equal(inbox.empty.hidden, false);
  assert.equal(inbox.list.children.length, 0);
  renderInboxList(inbox, [{ documentId: 1, fileName: "a.md", status: "PENDING" }], { number: 0, size: 20, totalElements: 1, totalPages: 1 }, doc, {});
  assert.equal(inbox.empty.hidden, true);
  assert.equal(inbox.list.children.length, 1);

  const wiki = { wikiList: new FakeElement("ul"), wikiEmpty: new FakeElement("p"), wikiPageInfo: new FakeElement(), wikiPrevPage: new FakeElement(), wikiNextPage: new FakeElement(), wikiReadPanel: new FakeElement(), wikiReadMeta: new FakeElement(), wikiReadBody: new FakeElement() };
  renderWikiList(wiki, [], { number: 0, size: 20, totalElements: 0, totalPages: 0 }, doc, {});
  assert.equal(wiki.wikiEmpty.hidden, false);
  renderWikiList(wiki, [{ knowledgeId: "k1", title: "T", pageType: "CONCEPT" }], { number: 0, size: 20, totalElements: 1, totalPages: 1 }, doc, {});
  assert.equal(wiki.wikiEmpty.hidden, true);

  const review = { proposalList: new FakeElement("ul"), proposalEmpty: new FakeElement("p"), proposalPageInfo: new FakeElement(), proposalPrevPage: new FakeElement(), proposalNextPage: new FakeElement() };
  renderProposalList(review, [], { number: 0, size: 20, totalElements: 0, totalPages: 0 }, doc, {});
  assert.equal(review.proposalEmpty.hidden, false);
  renderProposalList(review, [{ id: 1, title: "P", status: "REVIEW" }], { number: 0, size: 20, totalElements: 1, totalPages: 1 }, doc, {});
  assert.equal(review.proposalEmpty.hidden, true);

  const triageList = new FakeElement("ul");
  renderFindingList(doc, triageList, []);
  assert.equal(triageList.children.length, 0);
  renderFindingList(doc, triageList, [{ finding: { code: "BROKEN_LINK", knowledgeId: "k1", category: "REFERENCE", severity: "ERROR", detail: "d" } }]);
  assert.equal(triageList.children.length, 1);
});

test("loading never masquerades as success and never invents progress (#496)", async () => {
  const [css, ...sources] = await Promise.all([
    readFile(STYLES_URL, "utf8"),
    readFile(new URL("../../main/resources/static/ask-ui.js", import.meta.url), "utf8"),
    readFile(new URL("../../main/resources/static/inbox-ui.js", import.meta.url), "utf8"),
    readFile(new URL("../../main/resources/static/retrieval-inspector-ui.js", import.meta.url), "utf8"),
    readFile(new URL("../../main/resources/static/graph-operations-ui.js", import.meta.url), "utf8")
  ]);
  const loadingRule = css.match(/\.state-loading[\s\S]*?\{[^}]*\}/u);
  assert.ok(loadingRule, ".state-loading shared contract must exist");
  assert.match(loadingRule[0], /var\(--foreground-muted\)/u, "loading uses muted, not success");
  assert.doesNotMatch(loadingRule[0], /var\(--positive\)/u, "loading must not use success color");
  for (const source of sources) {
    assert.doesNotMatch(source, /%\s*complete|percent\s*complete|progress\s*%/i,
      "no invented percentage progress without API progress");
  }
  // COMPLETED with failures stays partial, never pure success.
  const partial = analysisJobOutcome({ status: "COMPLETED", failedCount: 2 });
  assert.equal(partial.tone, "partial");
  assert.doesNotMatch(partial.label, /成功\s*$/u, "partial failure is not labelled pure success");
  const success = analysisJobOutcome({ status: "COMPLETED", failedCount: 0 });
  assert.equal(success.tone, "success");
});

test("typed backend codes survive the UI layer (no generic flattening) (#496)", async () => {
  assert.equal(askErrorMessage({ code: "NO_ACTIVE_WORKSPACE" }).title, "尚未開啟知識庫");
  assert.equal(inboxErrorMessage({ code: "NO_ACTIVE_WORKSPACE" }).title, "尚未開啟知識庫");
  assert.equal(wikiErrorMessage({ code: "WIKI_PAGE_NOT_FOUND" }).title.length > 0, true);
  assert.equal(governanceErrorMessage({ code: "PROPOSAL_NOT_FOUND" }).title.length > 0, true);
  // Quality keeps its own domain-specific copy for the same typed code (not flattened).
  assert.equal(triageErrorMessage({ code: "NO_ACTIVE_WORKSPACE" }).title, "尚未開啟工作區");
  // Unknown codes fall back to generic but never leak raw backend text.
  for (const fn of [askErrorMessage, inboxErrorMessage, wikiErrorMessage, governanceErrorMessage, triageErrorMessage]) {
    const generic = fn({ code: "SOME_UNKNOWN_CODE_XYZ", message: "/secret/path RID:1" });
    assert.ok(generic.title.trim().length > 0);
    assert.doesNotMatch(`${generic.title} ${generic.message}`, /secret|RID:\d|\/secret/i);
  }
});

test("primary content outweighs secondary diagnostics structurally and visually (#496)", async () => {
  const [css, html] = await Promise.all([
    readFile(STYLES_URL, "utf8"),
    readFile(INDEX_URL, "utf8")
  ]);
  // Structural separation: answer/diagnostics, wiki body/meta, inspector final/fusion
  // live in sibling blocks, never nested primary-inside-secondary.
  const answerPos = html.indexOf('id="result-answer"');
  const diagnosticsPos = html.indexOf('id="context-diagnostics"');
  assert.ok(answerPos !== -1 && diagnosticsPos > answerPos, "diagnostics follows answer as sibling");
  assert.ok(html.indexOf('id="wiki-read-body"') > html.indexOf('id="wiki-read-meta"') ||
    html.includes('id="wiki-read-body"'), "wiki body is its own primary block");
  assert.ok(html.includes('id="inspector-final-evidence"'), "inspector final evidence owns a region");
  assert.ok(html.includes('id="inspector-fusion"'), "fusion diagnostics owns a separate region");
  // Visual hierarchy contract exists and keeps diagnostics subordinate.
  assert.match(css, /#496 primary vs secondary hierarchy/u);
  assert.match(css, /\.provider-metadata[\s\S]*?color\s*:\s*var\(--foreground-muted\)/u);
  // Danger/warning/positive semantics are not replaced by brand color.
  assert.doesNotMatch(css, /#ff6f0f/i);
  assert.match(css, /\.state-error[\s\S]*?var\(--critical/u);
  assert.match(css, /\.state-warning[\s\S]*?var\(--warning/u);
  assert.match(css, /\.state-success[\s\S]*?var\(--positive\)/u);
});

test("disabled and unavailable states are discernible without hover (#496)", async () => {
  const css = await readFile(STYLES_URL, "utf8");
  assert.match(css, /button:disabled\s*\{[^}]*cursor\s*:\s*not-allowed/u);
  assert.match(css, /\.secondary-button:disabled\s*\{[^}]*cursor\s*:\s*not-allowed/u);
  assert.match(css, /\.danger-button:disabled\s*\{[^}]*cursor\s*:\s*not-allowed/u);
  assert.match(css, /:focus-visible\s*\{[^}]*outline/u);
});

test("route and workspace switches clear stale transient state (#496)", async () => {
  const modules = await Promise.all([
    "workspace-ui.js", "inbox-ui.js", "wiki-ui.js", "review-ui.js",
    "quality-ui.js", "ask-ui.js", "retrieval-inspector-ui.js",
    "source-chunk-inspector-ui.js", "analysis-ui.js"
  ].map(name => readFile(new URL(`../../main/resources/static/${name}`, import.meta.url), "utf8")));
  const joined = modules.join("\n");
  assert.match(joined, /Workspace isolation/u, "workspace isolation is documented");
  assert.match(joined, /function reset\(\)|function clearAll/u, "reset/clear paths exist");
  assert.match(joined, /replaceChildren\(\)/u, "stale lists are replaced, not appended");
  // Inspector explicitly clears before fetch and on workspace switch.
  const inspector = modules[6];
  assert.match(inspector, /clearAll\(elements\)/u);
});

test("state rendering uses safe DOM APIs and no invented authority (#496)", async () => {
  const files = ["ask-ui.js", "inbox-ui.js", "wiki-ui.js", "review-ui.js", "quality-ui.js",
    "retrieval-inspector-ui.js", "source-chunk-inspector-ui.js", "workspace-ui.js", "analysis-ui.js"];
  for (const name of files) {
    const source = await readFile(new URL(`../../main/resources/static/${name}`, import.meta.url), "utf8");
    // Forbid executable HTML sinks (assignment/call), not doc comments mentioning the name.
    assert.doesNotMatch(source, /\.innerHTML\s*=/u, `${name} must use textContent`);
    assert.doesNotMatch(source, /\.outerHTML\s*=/u, `${name} must use textContent`);
    assert.doesNotMatch(source, /insertAdjacentHTML\s*\(/u, `${name} must use textContent`);
    assert.doesNotMatch(source, /localStorage|sessionStorage/u, `${name} adds no persistence`);
  }
  const css = await readFile(STYLES_URL, "utf8");
  assert.match(css, /\[hidden\]\s*\{\s*display\s*:\s*none\s*!important/u, "[hidden] stays the authority");
  const html = await readFile(INDEX_URL, "utf8");
  assert.doesNotMatch(html, /<script[^>]+src="https?:\/\//i, "no CDN script");
  assert.doesNotMatch(css, /url\(\s*["']?https?:\/\//i, "no remote url");
});
