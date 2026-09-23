import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import {
  bootstrapOrganizeUi,
  candidateTypeLabel,
  createOrganizeController,
  fetchReviewProposals,
  fetchTagSuggestions,
  freshnessCopy,
  organizeErrorMessage,
  parseTagInput,
  renderOrganizePanel,
  saveProposalTags
} from "../../main/resources/static/organize-ui.js";

class FakeElement {
  constructor(tagName = "div") {
    this.tagName = tagName;
    this.children = [];
    this.hidden = true;
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
    panel: new FakeElement("section"),
    heading: new FakeElement("h3"),
    close: new FakeElement("button"),
    freshness: new FakeElement("p"),
    empty: new FakeElement("p"),
    list: new FakeElement("ul"),
    proposalSelect: new FakeElement("select"),
    tagInput: new FakeElement("input"),
    save: new FakeElement("button"),
    result: new FakeElement("p")
  };
  elements.heading.hidden = false;
  return elements;
}

function fakeDocument(byId = {}) {
  const listeners = new Map();
  return {
    createElement: () => new FakeElement(),
    getElementById: id => byId[id] ?? null,
    listeners,
    addEventListener(name, handler) { listeners.set(name, handler); },
    dispatchEvent(event) {
      const handler = listeners.get(event.type);
      if (handler) handler(event);
      return true;
    }
  };
}

function flatText(element) {
  return [element.textContent,
    ...element.children.map(child => flatText(child))].join(" ");
}

function jsonResponse(status, payload) {
  return { ok: status < 400, status, json: async () => payload };
}

function suggestionsPayload(overrides = {}) {
  return {
    data: {
      documentId: 7,
      analysisId: 3,
      analyzedAt: "2026-08-27T01:00:00Z",
      current: true,
      freshness: "CURRENT",
      suggestions: [
        {
          candidateId: 11, candidateNo: 1, title: "部署流程", candidateType: "PROCEDURE",
          confidence: 0.8, summary: "部署摘要", rationale: "部署理由",
          suggestedPageType: null, tags: ["live"], tagsOrigin: "PROPOSAL"
        },
        {
          candidateId: 12, candidateNo: 2, title: "核心概念", candidateType: "CONCEPT",
          confidence: 0.9, summary: "概念摘要", rationale: "概念理由",
          suggestedPageType: "CONCEPT", tags: [], tagsOrigin: "NONE"
        }
      ],
      ...overrides
    }
  };
}

function proposalsPayload() {
  return { data: [{ id: 31, title: "部署流程" }], page: { number: 0, totalElements: 1 } };
}

test("labels use task language and never invent unknown states", () => {
  assert.equal(candidateTypeLabel("CONCEPT"), "概念");
  assert.equal(candidateTypeLabel("PROCEDURE"), "流程");
  assert.equal(candidateTypeLabel("FUTURE_TYPE"), "FUTURE_TYPE");
  const [title] = freshnessCopy("CURRENT");
  assert.equal(title, "建議為最新狀態");
  assert.equal(freshnessCopy("FUTURE")[0], "無法確認整理狀態");
});

test("tag input splits on common separators and drops empties", () => {
  assert.deepEqual(parseTagInput("a, b；c、d e;;"), ["a", "b", "c", "d", "e"]);
  assert.deepEqual(parseTagInput("   "), []);
  assert.deepEqual(parseTagInput(null), []);
});

test("suggestion panel renders backend authority as text, never invented", () => {
  const elements = uiElements();
  renderOrganizePanel(elements, suggestionsPayload().data, proposalsPayload().data, fakeDocument());

  const body = flatText(elements.list);
  assert.match(body, /部署流程/u);
  assert.match(body, /需要你在審核時決定放置位置/u);
  assert.match(body, /目前標籤：live/u);
  assert.match(body, /系統建議分類：概念/u);
  assert.match(body, /目前還沒有標籤/u);
  assert.match(elements.freshness.textContent, /建議為最新狀態/u);
  assert.equal(elements.empty.hidden, true);
  assert.equal(elements.proposalSelect.children.length, 2);
  assert.equal(elements.save.disabled, false);
});

test("stale and empty states stay honest without hiding the reason", () => {
  const staleElements = uiElements();
  renderOrganizePanel(staleElements,
    suggestionsPayload({ freshness: "DOCUMENT_CHANGED_AFTER_ANALYSIS", current: false }).data,
    [], fakeDocument());
  assert.match(staleElements.freshness.textContent, /可能已過期/u);
  assert.equal(staleElements.save.disabled, true);

  const emptyElements = uiElements();
  renderOrganizePanel(emptyElements,
    suggestionsPayload({ freshness: "NO_ANALYSIS", suggestions: [] }).data, [], fakeDocument());
  assert.match(emptyElements.freshness.textContent, /尚無可整理的建議/u);
  assert.equal(emptyElements.empty.hidden, false);
});

test("suggestion fetch uses the read-only contract and fails closed", async () => {
  const calls = [];
  const fetchImpl = async url => {
    calls.push(String(url));
    return jsonResponse(200, suggestionsPayload());
  };
  const data = await fetchTagSuggestions(fetchImpl, 7);
  assert.equal(calls[0], "/api/v1/organization/tag-suggestions?documentId=7");
  assert.equal(data.suggestions.length, 2);

  const badFetch = async () => jsonResponse(200, { data: null });
  await assert.rejects(() => fetchTagSuggestions(badFetch, 7));

  const errorFetch = async () => jsonResponse(404, { error: { code: "DOCUMENT_NOT_FOUND" } });
  const error = await fetchTagSuggestions(errorFetch, 9).catch(value => value);
  assert.equal(error.code, "DOCUMENT_NOT_FOUND");
});

test("proposal options come from the review list, ask-excluded by the backend filter", async () => {
  const calls = [];
  const fetchImpl = async url => {
    calls.push(String(url));
    return jsonResponse(200, proposalsPayload());
  };
  const rows = await fetchReviewProposals(fetchImpl, 7);
  assert.match(calls[0], /\/api\/v1\/proposals\?status=REVIEW&documentId=7/u);
  assert.deepEqual(rows.map(row => row.id), [31]);
});

test("tag save patches the single human mutation point with double-submit guard", async () => {
  const calls = [];
  let resolvePatch;
  const gate = new Promise(resolve => { resolvePatch = resolve; });
  const fetchImpl = async (url, options) => {
    calls.push({ url: String(url), method: options?.method, body: options?.body });
    if (String(url).includes("/tags")) {
      await gate;
      return jsonResponse(200, { data: { id: 31, status: "REVIEW" } });
    }
    if (String(url).startsWith("/api/v1/organization/")) {
      return jsonResponse(200, suggestionsPayload());
    }
    return jsonResponse(200, proposalsPayload());
  };
  const elements = uiElements();
  const controller = createOrganizeController(elements, fetchImpl, fakeDocument());
  await controller.open(7);
  elements.proposalSelect.value = "31";
  elements.tagInput.value = "新標籤， second";

  const first = controller.save();
  await controller.save();
  resolvePatch();
  await first;

  const patches = calls.filter(call => String(call.url).includes("/tags"));
  assert.equal(patches.length, 1);
  assert.deepEqual(patches[0], {
    url: "/api/v1/proposals/31/tags",
    method: "PATCH",
    body: JSON.stringify({ tags: ["新標籤", "second"] })
  });
  assert.match(elements.result.textContent, /後續建立或重新產生的草稿會使用新標籤/u);
});

test("save without a selected proposal never touches the backend", async () => {
  let fetched = false;
  const fetchImpl = async (url, options) => {
    if (options?.method === "PATCH") fetched = true;
    if (String(url).startsWith("/api/v1/organization/")) {
      return jsonResponse(200, suggestionsPayload());
    }
    return jsonResponse(200, proposalsPayload());
  };
  const elements = uiElements();
  const controller = createOrganizeController(elements, fetchImpl, fakeDocument());
  await controller.open(7);
  elements.proposalSelect.value = "";
  await controller.save();
  assert.equal(fetched, false);
  assert.match(elements.result.textContent, /請先選擇要調整標籤的提案/u);
});

test("typed failures render operator-safe copy without backend leakage", async () => {
  assert.equal(organizeErrorMessage("PROPOSAL_TAGS_NOT_EDITABLE")[0], "此提案的標籤無法手動調整");
  assert.equal(organizeErrorMessage("DOCUMENT_NOT_FOUND")[0], "找不到文件");
  assert.equal(organizeErrorMessage("SOMETHING_ELSE")[0], "整理失敗");

  const elements = uiElements();
  const fetchImpl = async (url, options) => {
    if (options?.method === "PATCH") {
      return jsonResponse(422, { error: { code: "PROPOSAL_TAGS_NOT_EDITABLE" } });
    }
    if (String(url).startsWith("/api/v1/organization/")) {
      return jsonResponse(200, suggestionsPayload());
    }
    return jsonResponse(200, proposalsPayload());
  };
  const controller = createOrganizeController(elements, fetchImpl, fakeDocument());
  await controller.open(7);
  elements.proposalSelect.value = "31";
  elements.tagInput.value = "x";
  await controller.save();
  assert.match(elements.result.textContent, /此提案的標籤無法手動調整/u);
});

test("open-organize event opens the document and workspace switch resets", async () => {
  const elements = uiElements();
  const byId = {
    "organize-panel": elements.panel,
    "organize-heading": elements.heading,
    "organize-close": elements.close,
    "organize-freshness": elements.freshness,
    "organize-empty": elements.empty,
    "organize-list": elements.list,
    "organize-proposal-select": elements.proposalSelect,
    "organize-tag-input": elements.tagInput,
    "organize-save": elements.save,
    "organize-result": elements.result
  };
  const documentRef = fakeDocument(byId);
  const calls = [];
  const fetchImpl = async url => {
    calls.push(String(url));
    if (String(url).startsWith("/api/v1/organization/")) {
      return jsonResponse(200, suggestionsPayload());
    }
    return jsonResponse(200, proposalsPayload());
  };
  globalThis.fetch = fetchImpl;
  const controller = bootstrapOrganizeUi(documentRef);
  assert.ok(controller);
  assert.ok(documentRef.listeners.has("open-organize"));
  assert.ok(documentRef.listeners.has("workspace-changed"));

  documentRef.dispatchEvent({ type: "open-organize", detail: { documentId: 7 } });
  for (let attempt = 0; attempt < 20 && elements.list.children.length === 0; attempt++) {
    await new Promise(resolve => setTimeout(resolve, 0));
  }
  assert.equal(elements.panel.hidden, false);
  assert.ok(calls.some(url => url.includes("documentId=7")));

  await documentRef.listeners.get("workspace-changed")();
  assert.equal(elements.panel.hidden, true);
  assert.equal(elements.list.children.length, 0);
  delete globalThis.fetch;
});

test("整理面板載入成功後聚焦標題，關閉時返回觸發按鈕", async () => {
  const elements = uiElements();
  const opener = new FakeElement("button");
  opener.hidden = false;
  const fetchImpl = async url => String(url).startsWith("/api/v1/organization/")
    ? jsonResponse(200, suggestionsPayload()) : jsonResponse(200, proposalsPayload());
  const controller = createOrganizeController(elements, fetchImpl, fakeDocument());

  await controller.open(7, opener);
  assert.equal(elements.heading.focusCount, 1);
  assert.equal(elements.panel.hidden, false);

  controller.close();
  assert.equal(elements.panel.hidden, true);
  assert.equal(opener.focusCount, 1);
});

test("整理面板載入失敗時不將焦點移入未完成內容", async () => {
  const elements = uiElements();
  const opener = new FakeElement("button");
  opener.hidden = false;
  const fetchImpl = async url => String(url).startsWith("/api/v1/organization/")
    ? jsonResponse(503, { error: { code: "UNAVAILABLE" } })
    : jsonResponse(200, proposalsPayload());
  const controller = createOrganizeController(elements, fetchImpl, fakeDocument());

  await controller.open(7, opener);
  assert.equal(elements.heading.focusCount, 0);
  assert.equal(elements.panel.hidden, false);
  assert.equal(elements.result.hidden, false);
  assert.equal(opener.focusCount, 0);

  controller.close();
  assert.equal(opener.focusCount, 1);
});

test("the module never injects markup via innerHTML", async () => {
  const source = await readFile(
    new URL("../../main/resources/static/organize-ui.js", import.meta.url), "utf8");
  // Forbid executable HTML sinks (assignment/call), not doc comments mentioning the name.
  assert.doesNotMatch(source, /\.innerHTML\s*=/u, "organize surface must use textContent");
  assert.doesNotMatch(source, /\.outerHTML\s*=/u, "organize surface must use textContent");
  assert.doesNotMatch(source, /insertAdjacentHTML\s*\(/u, "organize surface must use textContent");
  assert.doesNotMatch(source, /localStorage|sessionStorage/u, "no browser persistence");
});
