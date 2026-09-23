import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import {
  createWikiController,
  pageTypeLabel,
  renderWikiList,
  wikiErrorMessage,
  WIKI_PAGE_TYPES
} from "../../main/resources/static/wiki-ui.js";
import { applyRoute, parseRoute } from "../../main/resources/static/navigation-ui.js";

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
    this.focusCount = 0;
    this.isConnected = true;
  }

  append(...nodes) { this.children.push(...nodes); }
  replaceChildren(...nodes) { this.children = nodes; }
  addEventListener(name, handler) { this.handlers.set(name, handler); }
  setAttribute(name, value) { this[`attr_${name}`] = value; }
  focus() { this.focusCount += 1; }
}

function uiElements() {
  const elements = {
    wikiFilterForm: new FakeElement("form"),
    typeFilter: new FakeElement("select"),
    wikiList: new FakeElement("ul"),
    wikiEmpty: new FakeElement("p"),
    wikiHint: new FakeElement("p"),
    wikiPageInfo: new FakeElement("span"),
    wikiPrevPage: new FakeElement("button"),
    wikiNextPage: new FakeElement("button"),
    wikiReadPanel: new FakeElement("section"),
    wikiReadHeading: new FakeElement("h3"),
    wikiReadMeta: new FakeElement("div"),
    wikiReadBody: new FakeElement("pre"),
    wikiReadClose: new FakeElement("button")
  };
  elements.wikiReadPanel.hidden = true;
  return elements;
}

function fakeDocument() {
  const listeners = new Map();
  return {
    createElement: () => new FakeElement(),
    listeners,
    addEventListener(name, handler) { listeners.set(name, handler); }
  };
}

function flatText(element) {
  return [element.textContent,
    ...element.children.map(child => flatText(child))].join(" ");
}

function jsonResponse(status, payload) {
  return { ok: status < 400, status, json: async () => payload };
}

function pageRow(overrides = {}) {
  return {
    id: 1, knowledgeId: "wiki-arch", title: "Transformer Architecture",
    pageType: "CONCEPT", revision: 2, contentHash: "a".repeat(64),
    updatedAt: "2026-09-13T00:00:00Z", ...overrides
  };
}

function listPayload(rows, totalElements = rows.length) {
  return { data: rows, page: { number: 0, size: 20, totalElements, totalPages: 1 } };
}

test("page type filter covers the backend enum without guessing", () => {
  assert.deepEqual(WIKI_PAGE_TYPES, ["CONCEPT", "TECHNOLOGY", "TROUBLESHOOTING", "DECISION",
    "PROJECT", "REFERENCE", "HOWTO", "PERSON", "ORGANIZATION"]);
  assert.equal(pageTypeLabel("HOWTO"), "操作指南");
  assert.equal(pageTypeLabel("FUTURE_TYPE"), "FUTURE_TYPE");
});

test("typed errors stay operator-safe", () => {
  assert.equal(wikiErrorMessage({ code: "WIKI_PAGE_NOT_FOUND" }).title, "找不到頁面");
  assert.equal(wikiErrorMessage({ code: "WIKI_PAGE_UNAVAILABLE" }).title, "頁面內容不可用");
  assert.equal(wikiErrorMessage(undefined).title, "知識讀取失敗");
});

test("list renders typed rows with open actions and honest pager state", () => {
  const elements = uiElements();
  const opened = [];
  renderWikiList(elements, [pageRow(), pageRow({ knowledgeId: "wiki-b", title: "B" })],
    { number: 0, size: 20, totalElements: 2, totalPages: 1 },
    { createElement: () => new FakeElement() }, { onOpen: id => opened.push(id) });

  const text = flatText(elements.wikiList);
  assert.match(text, /Transformer Architecture/u);
  assert.match(text, /概念 · 版本 2/u);
  assert.match(text, /wiki-arch/u);
  assert.equal(elements.wikiEmpty.hidden, true);
  assert.equal(elements.wikiPrevPage.disabled, true);
  assert.equal(elements.wikiNextPage.disabled, true);
  elements.wikiList.children[0].children
    .find(child => child.className === "wiki-open").handlers.get("click")();
  assert.deepEqual(opened, ["wiki-arch"]);
});

test("refresh pages and filters through the existing read contract", async () => {
  const elements = uiElements();
  const calls = [];
  const fetchImpl = async url => {
    calls.push(String(url));
    return jsonResponse(200, listPayload([pageRow()]));
  };
  const controller = createWikiController(elements, fetchImpl, fakeDocument());
  await controller.refresh();
  assert.equal(calls.at(-1), "/api/v1/wiki?page=0&size=20");

  elements.typeFilter.value = "HOWTO";
  await controller.applyFilter({ preventDefault() {} });
  assert.equal(calls.at(-1), "/api/v1/wiki?page=0&size=20&pageType=HOWTO");

  await controller.nextPage();
  assert.equal(calls.at(-1), "/api/v1/wiki?page=1&size=20&pageType=HOWTO");
});

test("read renders the canonical markdown as inert text with metadata hand-offs", async () => {
  const elements = uiElements();
  const fetchImpl = async url => {
    if (String(url) === "/api/v1/wiki/wiki-arch") {
      return jsonResponse(200, { data: pageRow({ markdown: "# Transformer Architecture\n\n<script>alert(1)</script>" }) });
    }
    return jsonResponse(200, listPayload([]));
  };
  const controller = createWikiController(elements, fetchImpl, fakeDocument());
  await controller.openPage("wiki-arch");

  assert.equal(elements.wikiReadPanel.hidden, false);
  assert.match(flatText(elements.wikiReadMeta), /knowledgeId：wiki-arch/u);
  assert.equal(elements.wikiReadBody.textContent,
    "# Transformer Architecture\n\n<script>alert(1)</script>",
    "markdown is inert text, never injected markup");
});

test("閱讀成功後將焦點移至標題，關閉時返回觸發按鈕", async () => {
  const elements = uiElements();
  const opener = new FakeElement("button");
  const fetchImpl = async () => jsonResponse(200, {
    data: pageRow({ markdown: "body" })
  });
  const controller = createWikiController(elements, fetchImpl, fakeDocument());

  await controller.openPage("wiki-arch", opener);
  assert.equal(elements.wikiReadHeading.focusCount, 1);
  assert.equal(elements.wikiReadPanel.hidden, false);

  controller.closePage();
  assert.equal(opener.focusCount, 1);
  assert.equal(elements.wikiReadPanel.hidden, true);
});

test("Wiki 讀取失敗時不將焦點移入面板", async () => {
  const elements = uiElements();
  const opener = new FakeElement("button");
  const fetchImpl = async () => jsonResponse(404, {
    error: { code: "WIKI_PAGE_NOT_FOUND" }
  });
  const controller = createWikiController(elements, fetchImpl, fakeDocument());

  await controller.openPage("wiki-missing", opener);
  assert.equal(elements.wikiReadHeading.focusCount, 0);
  assert.equal(opener.focusCount, 0);
  assert.equal(elements.wikiReadPanel.hidden, true);
  assert.match(elements.wikiHint.textContent, /找不到頁面/u);
});

test("Wiki 回應不完整時不聚焦未完成面板", async () => {
  const elements = uiElements();
  const opener = new FakeElement("button");
  const fetchImpl = async () => jsonResponse(200, { data: pageRow({ markdown: undefined }) });
  const controller = createWikiController(elements, fetchImpl, fakeDocument());

  await controller.openPage("wiki-arch", opener);
  assert.equal(elements.wikiReadHeading.focusCount, 0);
  assert.equal(elements.wikiReadPanel.hidden, true);
  assert.equal(opener.focusCount, 0);
  assert.match(elements.wikiHint.textContent, /知識讀取失敗/u);
});

test("Wiki 頁面 revision 必須是已發布版本", async () => {
  const elements = uiElements();
  const opener = new FakeElement("button");
  const fetchImpl = async () => jsonResponse(200, {
    data: pageRow({ revision: 0, markdown: "body" })
  });
  const controller = createWikiController(elements, fetchImpl, fakeDocument());

  await controller.openPage("wiki-arch", opener);
  assert.equal(elements.wikiReadHeading.focusCount, 0);
  assert.equal(elements.wikiReadPanel.hidden, true);
  assert.equal(opener.focusCount, 0);
  assert.match(elements.wikiHint.textContent, /知識讀取失敗/u);
});

test("unknown page and unavailable content surface distinct typed failures", async () => {
  const elements = uiElements();
  let mode = "not-found";
  const fetchImpl = async url => {
    if (String(url).startsWith("/api/v1/wiki/wiki-")) {
      if (mode === "not-found") {
        return jsonResponse(404, { error: { code: "WIKI_PAGE_NOT_FOUND", message: "gone" } });
      }
      return jsonResponse(409, { error: { code: "WIKI_PAGE_UNAVAILABLE", message: "drifted" } });
    }
    return jsonResponse(200, listPayload([]));
  };
  const controller = createWikiController(elements, fetchImpl, fakeDocument());

  mode = "not-found";
  await controller.openPage("wiki-unknown");
  assert.match(elements.wikiHint.textContent, /找不到頁面/u);

  mode = "unavailable";
  await controller.openPage("wiki-arch");
  assert.match(elements.wikiHint.textContent, /頁面內容不可用/u,
    "drifted/unavailable content is a distinct state, never silently stale");
});

test("empty workspace renders the empty state", async () => {
  const elements = uiElements();
  const fetchImpl = async () => jsonResponse(200, listPayload([]));
  const controller = createWikiController(elements, fetchImpl, fakeDocument());
  await controller.refresh();
  assert.equal(elements.wikiEmpty.hidden, false);
});

test("workspace switch clears the reading state and re-fetches", async () => {
  const documentRef = fakeDocument();
  const elements = uiElements();
  const calls = [];
  const fetchImpl = async url => {
    calls.push(String(url));
    if (String(url) === "/api/v1/wiki/wiki-arch") {
      return jsonResponse(200, { data: pageRow({ markdown: "body" }) });
    }
    return jsonResponse(200, listPayload([]));
  };
  const controller = createWikiController(elements, fetchImpl, documentRef);
  await controller.refresh();
  await controller.openPage("wiki-arch");
  elements.typeFilter.value = "CONCEPT";

  assert.equal(documentRef.listeners.has("workspace-changed"), true);
  await documentRef.listeners.get("workspace-changed")();

  assert.equal(elements.typeFilter.value, "");
  assert.equal(elements.wikiReadPanel.hidden, true);
  assert.match(calls.at(-1), /page=0&size=20$/u);
});

test("the wiki view is wired into the shared navigation shell", () => {
  assert.equal(parseRoute("#/wiki"), "wiki");
  const sections = ["home", "wiki", "inbox", "ask", "inspect", "review"]
    .map(route => ({ dataset: { route }, hidden: false, querySelector: () => null }));
  const links = sections.map(section => ({
    dataset: { routeLink: section.dataset.route }, current: null,
    setAttribute(name, value) { if (name === "aria-current") this.current = value; },
    removeAttribute(name) { if (name === "aria-current") this.current = null; }
  }));
  const documentRef = {
    querySelectorAll: selector => selector === "[data-route]" ? sections
      : selector === "[data-route-link]" ? links : [],
    defaultView: null
  };
  applyRoute(documentRef, "wiki");
  const wikiSection = sections.find(section => section.dataset.route === "wiki");
  const wikiLink = links.find(link => link.dataset.routeLink === "wiki");
  assert.equal(wikiSection.hidden, false);
  assert.equal(wikiLink.current, "page");
  for (const section of sections.filter(item => item.dataset.route !== "wiki")) {
    assert.equal(section.hidden, true);
  }
});

test("the module never injects markup via innerHTML", async () => {
  const source = await readFile(
    new URL("../../main/resources/static/wiki-ui.js", import.meta.url), "utf8");
  assert.doesNotMatch(source, /innerHTML/u);
});
