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
  addEventListener(name, handler) { this.handlers.set(name, handler); }
  focus() { this.focused = true; }
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
