import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import { renderInboxList } from "../../main/resources/static/inbox-ui.js";
import { renderWikiList } from "../../main/resources/static/wiki-ui.js";
import { renderProposalList } from "../../main/resources/static/review-ui.js";

const STYLES_URL = new URL("../../main/resources/static/styles.css", import.meta.url);
const INDEX_URL = new URL("../../main/resources/static/index.html", import.meta.url);

class FakeElement {
  constructor(tagName = "div") {
    this.tagName = tagName;
    this.children = [];
    this.hidden = false;
    this.disabled = false;
    this.value = "";
    this.textContent = "";
    this.className = "";
    this.handlers = new Map();
  }

  append(...nodes) {
    this.children.push(...nodes);
  }

  replaceChildren(...nodes) {
    this.children = nodes;
  }

  addEventListener(name, handler) {
    this.handlers.set(name, handler);
  }

  setAttribute(name, value) {
    this[`attr_${name}`] = value;
  }
}

function fakeDocument() {
  return {
    createElement: () => new FakeElement(),
    addEventListener() {}
  };
}

function inboxElements() {
  return {
    list: new FakeElement("ul"),
    empty: new FakeElement("p"),
    pageInfo: new FakeElement("span"),
    prevPage: new FakeElement("button"),
    nextPage: new FakeElement("button")
  };
}

function wikiElements() {
  return {
    wikiList: new FakeElement("ul"),
    wikiEmpty: new FakeElement("p"),
    wikiPageInfo: new FakeElement("span"),
    wikiPrevPage: new FakeElement("button"),
    wikiNextPage: new FakeElement("button"),
    wikiReadPanel: new FakeElement("section"),
    wikiReadMeta: new FakeElement("div"),
    wikiReadBody: new FakeElement("pre")
  };
}

function reviewElements() {
  return {
    proposalList: new FakeElement("ul"),
    proposalEmpty: new FakeElement("p"),
    proposalPageInfo: new FakeElement("span"),
    proposalPrevPage: new FakeElement("button"),
    proposalNextPage: new FakeElement("button")
  };
}

test("author stylesheet guarantees [hidden] wins over component display rules (#450)", async () => {
  const css = await readFile(STYLES_URL, "utf8");
  assert.match(
    css,
    /\[hidden\]\s*\{\s*display\s*:\s*none\s*!important\s*;\s*\}/u,
    "global [hidden] rule must exist with !important so .empty-state display:grid cannot override it"
  );
  assert.match(
    css,
    /\.empty-state\s*\{\s*display\s*:\s*grid/u,
    "the visible empty-state layout must stay grid; the fix is the hidden authority, not removing layout"
  );
});

test("inbox empty state follows hidden authority for both empty and non-empty lists (#450)", async () => {
  const documentRef = fakeDocument();

  const withRows = inboxElements();
  renderInboxList(
    withRows,
    [{ documentId: 1, fileName: "test-note.md", extension: "md", fileSize: 16, status: "PENDING", createdAt: "2026-09-14T00:00:00Z" }],
    { number: 0, size: 20, totalElements: 1, totalPages: 1 },
    documentRef,
    {}
  );
  assert.equal(withRows.empty.hidden, true);
  assert.equal(withRows.list.children.length, 1);

  const empty = inboxElements();
  renderInboxList(empty, [], { number: 0, size: 20, totalElements: 0, totalPages: 0 }, documentRef, {});
  assert.equal(empty.empty.hidden, false);
  assert.equal(empty.list.children.length, 0);
});

test("non-inbox surfaces use the same hidden authority (wiki + review audit, #450)", async () => {
  const documentRef = fakeDocument();

  const wikiWithRows = wikiElements();
  renderWikiList(
    wikiWithRows,
    [{ knowledgeId: "k1", title: "Page", pageType: "CONCEPT", revision: 1, updatedAt: "2026-09-14T00:00:00Z" }],
    { number: 0, size: 20, totalElements: 1, totalPages: 1 },
    documentRef,
    {}
  );
  assert.equal(wikiWithRows.wikiEmpty.hidden, true);

  const wikiEmpty = wikiElements();
  renderWikiList(wikiEmpty, [], { number: 0, size: 20, totalElements: 0, totalPages: 0 }, documentRef, {});
  assert.equal(wikiEmpty.wikiEmpty.hidden, false);

  const reviewWithRows = reviewElements();
  renderProposalList(
    reviewWithRows,
    [{ id: 1, title: "Proposal", status: "REVIEW", action: "CREATE", confidence: 0.5 }],
    { number: 0, size: 20, totalElements: 1, totalPages: 1 },
    documentRef,
    {}
  );
  assert.equal(reviewWithRows.proposalEmpty.hidden, true);

  const reviewEmpty = reviewElements();
  renderProposalList(reviewEmpty, [], { number: 0, size: 20, totalElements: 0, totalPages: 0 }, documentRef, {});
  assert.equal(reviewEmpty.proposalEmpty.hidden, false);
});

test("static shell audit: every .empty-state hidden panel is covered by the global rule (#450)", async () => {
  const [css, html] = await Promise.all([
    readFile(STYLES_URL, "utf8"),
    readFile(INDEX_URL, "utf8")
  ]);
  assert.match(css, /\[hidden\]\s*\{\s*display\s*:\s*none\s*!important/u);
  const emptyStateHidden = html.match(/class="[^"]*\bempty-state\b[^"]*"[^>]*\bhidden\b|\bhidden\b[^>]*class="[^"]*\bempty-state\b/g) || [];
  assert.ok(
    emptyStateHidden.length >= 2,
    `expected at least inbox + wiki empty-state hidden panels, found ${emptyStateHidden.length}`
  );
});
