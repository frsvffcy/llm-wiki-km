import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import {
  createInspectorController,
  errorMessage,
  renderInspection,
  validateQuestion
} from "../../main/resources/static/retrieval-inspector-ui.js";

class FakeElement {
  constructor() {
    this.children = [];
    this.hidden = false;
    this.disabled = false;
    this.value = "";
    this.textContent = "";
    this.attributes = new Map();
    this.handlers = new Map();
    this.focused = false;
  }

  append(...nodes) { this.children.push(...nodes); }
  replaceChildren(...nodes) { this.children = nodes; }
  setAttribute(name, value) { this.attributes.set(name, value); }
  getAttribute(name) {
    if (typeof name !== "string") throw new TypeError("attribute name must be a string");
    return this.attributes.has(name) ? this.attributes.get(name) : null;
  }
  addEventListener(name, handler) { this.handlers.set(name, handler); }
  focus() { this.focused = true; }
}

class FakeDocument {
  constructor() {
    this.byId = new Map();
    this.listeners = new Map();
  }

  createElement() { return new FakeElement(); }

  getElementById(id) { return this.byId.has(id) ? this.byId.get(id) : null; }

  addEventListener(name, handler) { this.listeners.set(name, handler); }

  fire(name, event) { this.listeners.get(name)?.(event); }
}

const documentRef = { createElement: () => new FakeElement() };

function uiElements() {
  return {
    form: new FakeElement(), question: new FakeElement(), retrievalMode: new FakeElement(),
    submit: new FakeElement(), hint: new FakeElement(), result: new FakeElement(),
    empty: new FakeElement(), emptyMessage: new FakeElement(), error: new FakeElement(),
    errorTitle: new FakeElement(), errorMessage: new FakeElement(),
    modalities: new FakeElement(), fusion: new FakeElement(), fusionDetail: new FakeElement(),
    selection: new FakeElement(), finalEvidence: new FakeElement()
  };
}

function event() { return { preventDefault() {} }; }

function inspectionPayload() {
  return { data: {
    query: "檢索架構",
    mode: "HYBRID_GRAPH",
    strategy: "FUSED",
    queryTransformation: {
      policyVersion: "query-transform-single-rewrite-v1",
      status: "REWRITE_APPLIED",
      applicability: "LEXICAL_MISS_CROWD_OUT",
      providerUsageStatus: "AVAILABLE",
      retrievalInputCount: 2
    },
    retrievalInputs: [
      { ordinal: 1, role: "ORIGINAL", query: "檢索架構怎麼設定",
        modalities: [{ modality: "LEXICAL", outcome: "EMPTY", candidates: [], rejected: [] }],
        fusedOrder: [], selection: [] },
      { ordinal: 2, role: "REWRITE", query: "檢索架構",
        modalities: [{ modality: "LEXICAL", outcome: "CONTRIBUTED", candidates: [], rejected: [] }],
        fusedOrder: [], selection: [] }
    ],
    fusionPolicyVersion: "fusion-rrf-v2-graph-damped",
    modalities: [
      {
        modality: "LEXICAL", outcome: "CONTRIBUTED",
        candidates: [{ identity: "WIKI:arch", ordinal: 1 }],
        rejected: [{ identity: "WIKI:stale", reason: "INELIGIBLE" }]
      },
      {
        modality: "GRAPH", outcome: "CONTRIBUTED",
        candidates: [{ identity: "WIKI:goal", ordinal: 1 }], rejected: []
      }
    ],
    fusedOrder: ["WIKI:arch", "WIKI:goal"],
    selection: [
      { identity: "WIKI:arch", disposition: "SELECTED", reason: null },
      { identity: "WIKI:stale", disposition: "REJECTED", reason: "INELIGIBLE" }
    ],
    finalEvidence: [
      { ordinal: 1, identity: "WIKI:arch" },
      { ordinal: 2, identity: "WIKI:goal" }
    ],
    modalityDiagnostics: { lexical: "CONTRIBUTED", vector: "DISABLED", graph: "CONTRIBUTED" },
    searchedCandidateCount: 5,
    rejectedCandidateCount: 1,
    insufficientEvidence: false,
    budget: { maxItems: 8, maxCharacters: 12000, usedItems: 2, usedCharacters: 500,
      estimatedTokens: 125, truncated: false }
  } };
}

function flatText(element) {
  const parts = [];
  function walk(node) {
    parts.push(`${node.className}::${node.textContent}`);
    for (const child of node.children) walk(child);
  }
  walk(element);
  return parts.join("\n");
}

function enrichedPayload() {
  const payload = inspectionPayload();
  payload.data.finalEvidence = [
    { ordinal: 1, identity: "SOURCE_CHUNK:42", kind: "SOURCE_CHUNK", sourceChunkId: 42,
      knowledgeId: null, displayLabel: "design.pdf · chunk 3", currentness: "CURRENT" },
    { ordinal: 2, identity: "WIKI:arch", kind: "WIKI", sourceChunkId: null,
      knowledgeId: "arch", displayLabel: "架構總覽", currentness: "CURRENT" }
  ];
  return payload;
}

function descendants(element) {
  const found = [];
  function walk(node) {
    found.push(node);
    for (const child of node.children) walk(child);
  }
  for (const child of element.children) walk(child);
  return found;
}

function locatorPanelDocument() {
  const documentRef = new FakeDocument();
  for (const id of ["source-inspector-result", "source-inspector-loading",
    "source-inspector-error", "source-inspector-error-title",
    "source-inspector-error-message", "source-inspector-not-found",
    "source-inspector-not-found-title", "source-inspector-not-found-message",
    "source-inspector-metadata", "source-inspector-preview"]) {
    documentRef.byId.set(id, new FakeElement());
  }
  return documentRef;
}

test("validates empty queries", () => {
  assert.equal(validateQuestion("   "), "請先輸入查詢。");
  assert.equal(validateQuestion("檢索架構"), null);
});

test("renders modality candidates, fusion policy, selection and final evidence as text", () => {
  const elements = uiElements();
  renderInspection(elements, inspectionPayload(), documentRef);

  assert.equal(elements.result.hidden, false);
  assert.equal(elements.error.hidden, true);
  const modalityText = flatText(elements.modalities);
  assert.match(modalityText, /查詢轉換 · 已套用單次改寫/);
  assert.match(modalityText, /query-transform-single-rewrite-v1/);
  assert.match(modalityText, /LEXICAL_MISS_CROWD_OUT/);
  assert.match(modalityText, /1\. 原始查詢/);
  assert.match(modalityText, /2\. 改寫查詢/);
  assert.match(modalityText, /檢索架構怎麼設定/);
  assert.match(modalityText, /LEXICAL · 已貢獻/);
  assert.match(modalityText, /1\. WIKI:arch/);
  assert.match(modalityText, /WIKI:stale（INELIGIBLE）/);
  assert.match(modalityText, /GRAPH · 已貢獻/);
  assert.equal(elements.fusion.hidden, false);
  const fusionText = flatText(elements.fusionDetail);
  assert.match(fusionText, /fusion-rrf-v2-graph-damped/);
  assert.match(fusionText, /2\. WIKI:goal/);
  const selectionText = flatText(elements.selection);
  assert.match(selectionText, /WIKI:arch：進入最終證據/);
  assert.match(selectionText, /WIKI:stale：被擋下（INELIGIBLE）/);
  const finalText = flatText(elements.finalEvidence);
  assert.match(finalText, /E1 WIKI:arch/);
  assert.match(finalText, /E2 WIKI:goal/);
});

test("renders degraded and unavailable modality outcomes as typed notices, not failures", () => {
  const elements = uiElements();
  const payload = inspectionPayload();
  payload.data.modalities[1] = {
    modality: "GRAPH", outcome: "UNAVAILABLE", candidates: [], rejected: []
  };
  payload.data.modalityDiagnostics.graph = "UNAVAILABLE";
  renderInspection(elements, payload, documentRef);

  assert.equal(elements.error.hidden, true);
  const modalityText = flatText(elements.modalities);
  assert.match(modalityText, /GRAPH · 無法使用/);
  assert.match(modalityText, /此訊號目前無法使用；下方仍顯示其他可用訊號的結果。/);
  assert.equal(elements.finalEvidence.children.length, 2);
});

test("renders insufficient evidence without any final evidence", () => {
  const elements = uiElements();
  const payload = inspectionPayload();
  payload.data.insufficientEvidence = true;
  renderInspection(elements, payload, documentRef);

  assert.equal(elements.empty.hidden, false);
  assert.equal(elements.result.hidden, true);
  assert.equal(elements.finalEvidence.children.length, 0);
});

test("controller fetches the read-only inspect endpoint and re-renders a new trace", async () => {
  const elements = uiElements();
  elements.question.value = "檢索架構";
  elements.retrievalMode.value = "HYBRID_GRAPH";
  let calls = 0;
  const controller = createInspectorController(elements, async (url, init) => {
    calls += 1;
    assert.equal(url, "/api/v1/retrieval/inspect?question=%E6%AA%A2%E7%B4%A2%E6%9E%B6%E6%A7%8B&mode=HYBRID_GRAPH");
    assert.equal(init.method, "GET");
    return { ok: true, json: async () => inspectionPayload() };
  }, documentRef);

  await controller.submit(event());
  await controller.submit(event());

  assert.equal(calls, 2);
  assert.equal(elements.result.hidden, false);
  assert.equal(elements.submit.disabled, false);
});

test("a failed inspection clears the previous trace before showing the error", async () => {
  const elements = uiElements();
  elements.question.value = "檢索架構";
  elements.retrievalMode.value = "WIKI_ONLY";
  renderInspection(elements, inspectionPayload(), documentRef);
  assert.equal(elements.result.hidden, false);

  const controller = createInspectorController(elements, async () => ({
    ok: false,
    json: async () => ({ error: { code: "RETRIEVAL_UNAVAILABLE", message: "x" } })
  }), documentRef);

  await controller.submit(event());

  assert.equal(elements.error.hidden, false);
  assert.equal(elements.errorTitle.textContent, "檢索服務暫時無法使用");
  assert.equal(elements.result.hidden, true);
  assert.equal(elements.modalities.children.length, 0);
  assert.equal(elements.finalEvidence.children.length, 0);
  assert.equal(elements.submit.disabled, false);
});

test("a network failure also clears the previous trace", async () => {
  const elements = uiElements();
  elements.question.value = "檢索架構";
  elements.retrievalMode.value = "WIKI_ONLY";
  renderInspection(elements, inspectionPayload(), documentRef);

  const controller = createInspectorController(elements, async () => {
    throw new Error("network down");
  }, documentRef);

  await controller.submit(event());

  assert.equal(elements.error.hidden, false);
  assert.equal(elements.modalities.children.length, 0);
  assert.equal(elements.finalEvidence.children.length, 0);
});

test("maps unknown error codes to the generic safe message", () => {
  const mapped = errorMessage({ code: "SOMETHING_ELSE" });
  assert.equal(mapped.title, "無法取得檢索結果");
});

test("inspector js never calls the answer endpoint, mutation endpoints, sliders, or unsafe DOM", async () => {
  const source = await readFile("src/main/resources/static/retrieval-inspector-ui.js", "utf8");
  assert.doesNotMatch(source, /\/api\/v1\/ask/);
  assert.doesNotMatch(source, /method:\s*"POST"/);
  assert.doesNotMatch(source, /\brebuild\b|\brepair\b|graph\/projection|index\/rebuild|embed/);
  assert.doesNotMatch(source, /innerHTML|localStorage|document\.cookie|eval\(/);
  assert.doesNotMatch(source, /<input[^>]*type=["']range/);
  assert.doesNotMatch(source, /score|similarity|fingerprint|snapshot|exception/i);
});

test("index html wires the inspector panel through CSP-safe modules only", async () => {
  const html = await readFile("src/main/resources/static/index.html", "utf8");
  assert.match(html, /retrieval-inspector-ui\.js/);
  assert.match(html, /id="inspector-form"/);
  assert.doesNotMatch(html, /type=["']range["']/);
  assert.doesNotMatch(html, /on(load|click|error)=/);
});

test("enriched final evidence shows kind, safe source label, currentness and navigation", () => {
  const elements = uiElements();
  renderInspection(elements, enrichedPayload(), documentRef);

  assert.equal(elements.finalEvidence.children.length, 2);
  const finalText = flatText(elements.finalEvidence);
  assert.match(finalText, /E1 SOURCE_CHUNK:42/);
  assert.match(finalText, /來源文件/);
  assert.match(finalText, /design\.pdf · chunk 3/);
  assert.match(finalText, /檢視當下與目前狀態一致/);
  assert.match(finalText, /E2 WIKI:arch/);
  assert.match(finalText, /架構總覽/);

  const nodes = descendants(elements.finalEvidence);
  const locate = nodes.find(node => node.className === "inspector-final-locate");
  assert.notEqual(locate, undefined);
  assert.equal(locate.textContent, "檢視來源片段");
  assert.equal(locate.getAttribute("data-chunk-id"), "42");
  const wikiLink = nodes.find(node => node.className === "inspector-final-wiki-link");
  assert.notEqual(wikiLink, undefined);
  assert.equal(wikiLink.textContent, "前往 Wiki 閱讀");
  assert.equal(wikiLink.href, "#/wiki");
  assert.equal(wikiLink.getAttribute("data-knowledge-id"), "arch");
});

test("final evidence without usable navigation stays plain text and never fabricates a target", () => {
  const elements = uiElements();
  const payload = inspectionPayload();
  payload.data.finalEvidence = [
    { ordinal: 1, identity: "SOURCE_CHUNK:9", kind: "SOURCE_CHUNK", sourceChunkId: null,
      knowledgeId: null, displayLabel: null, currentness: null },
    { ordinal: 2, identity: "SOURCE_CHUNK:0", kind: "SOURCE_CHUNK", sourceChunkId: 0,
      knowledgeId: null, displayLabel: "stale.pdf", currentness: "CURRENT" },
    { ordinal: 3, identity: "WIKI:x", kind: "WIKI", sourceChunkId: null,
      knowledgeId: "   ", displayLabel: null, currentness: "CURRENT" },
    { ordinal: 4, identity: "BOGUS:1", kind: "BOGUS", sourceChunkId: null,
      knowledgeId: null, displayLabel: null, currentness: null },
    { ordinal: 5, identity: "WIKI:legacy", kind: null, sourceChunkId: null,
      knowledgeId: null, displayLabel: null, currentness: null }
  ];
  renderInspection(elements, payload, documentRef);

  assert.equal(elements.finalEvidence.children.length, 5);
  const finalText = flatText(elements.finalEvidence);
  assert.match(finalText, /E1 SOURCE_CHUNK:9/);
  assert.match(finalText, /E5 WIKI:legacy/);
  const nodes = descendants(elements.finalEvidence);
  assert.equal(nodes.filter(node => node.className === "inspector-final-locate").length, 0);
  assert.equal(nodes.filter(node => node.className === "inspector-final-wiki-link").length, 0);
});

test("clicking a source evidence button opens the canonical locator panel read-only", async () => {
  const panelDocument = locatorPanelDocument();
  const elements = uiElements();
  let fetchedUrl = "";
  const fetchImpl = async url => {
    fetchedUrl = String(url);
    return { ok: true,
      json: async () => ({ data: { sourceChunkId: 42, documentName: "design.pdf", chunkNo: 3,
        currentness: "CURRENT", preview: "authoritative text", previewTruncated: false } }) };
  };
  createInspectorController(elements, fetchImpl, panelDocument);
  renderInspection(elements, enrichedPayload(), panelDocument);

  const click = elements.finalEvidence.handlers.get("click");
  assert.equal(click instanceof Function, true);
  const button = new FakeElement();
  button.setAttribute("data-chunk-id", "42");
  click({ target: button, preventDefault() {} });
  for (let i = 0; i < 10; i++) {
    await new Promise(resolve => setImmediate(resolve));
  }

  assert.equal(fetchedUrl, "/api/v1/source-chunks/42/locator");
  assert.equal(panelDocument.byId.get("source-inspector-result").hidden, false);
  assert.match(
    panelDocument.byId.get("source-inspector-metadata").children
      .map(child => child.textContent).join("\n"), /design\.pdf/);
});

test("a new inspection clears the previous locator panel together with the trace", async () => {
  const panelDocument = locatorPanelDocument();
  const elements = uiElements();
  elements.question.value = "檢索架構";
  elements.retrievalMode.value = "HYBRID_GRAPH";
  panelDocument.byId.get("source-inspector-result").hidden = false;
  panelDocument.byId.get("source-inspector-metadata").children.push(new FakeElement());

  const controller = createInspectorController(elements, async () => ({
    ok: true, json: async () => enrichedPayload()
  }), panelDocument);
  await controller.submit(event());

  assert.equal(elements.result.hidden, false);
  assert.equal(panelDocument.byId.get("source-inspector-result").hidden, true);
  assert.equal(panelDocument.byId.get("source-inspector-metadata").children.length, 0);
});

test("workspace switch clears enriched final evidence projections", () => {
  const panelDocument = locatorPanelDocument();
  const elements = uiElements();
  elements.question.value = "檢索架構";
  createInspectorController(elements, async () => ({}), panelDocument);
  renderInspection(elements, enrichedPayload(), panelDocument);
  assert.equal(elements.finalEvidence.children.length, 2);

  panelDocument.fire("workspace-changed");

  assert.equal(elements.finalEvidence.children.length, 0);
  assert.equal(elements.result.hidden, true);
  assert.equal(elements.question.value, "");
});
