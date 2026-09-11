import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import {
  createAskController,
  errorMessage,
  loadAiEgress,
  renderAskResponse,
  validateQuestion,
  RETRIEVAL_MODES
} from "../../main/resources/static/ask-ui.js";

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

const documentRef = { createElement: () => new FakeElement() };

function uiElements() {
  return {
    form: new FakeElement(), question: new FakeElement(), retrievalMode: new FakeElement(),
    submit: new FakeElement(), hint: new FakeElement(), result: new FakeElement(),
    empty: new FakeElement(), error: new FakeElement(), errorTitle: new FakeElement(),
    errorMessage: new FakeElement(), insufficient: new FakeElement(), answer: new FakeElement(),
    answerText: new FakeElement(), metadata: new FakeElement(), citations: new FakeElement(),
    citationCount: new FakeElement(), contextDiagnostics: new FakeElement(),
    contextDiagnosticsList: new FakeElement(), aiEgress: new FakeElement(),
    aiEgressLabel: new FakeElement(), aiEgressDetail: new FakeElement(),
    aiEgressToggle: new FakeElement()
  };
}

function event() { return { preventDefault() {} }; }

function diagnosticsText(element) {
  return element.children.map(row => row.children.map(child => child.textContent).join("::"))
    .join("\n");
}

function contextDiagnostics() {
  return {
    retrievedEvidenceCount: 4,
    admittedEvidenceCount: 3,
    answerContextBlockCount: 2,
    originalCodePoints: 120,
    packedCodePoints: 80,
    projectedCodePoints: 50,
    reductionRatio: 0.583333,
    truncated: true,
    compacted: true,
    contextPolicyVersion: "context-policy-v1-current",
    projectionKindDistribution: { VERBATIM: 0, NO_OP: 0, EXTRACTIVE: 1, TRUNCATED: 1 },
    projectionFallbackUsed: false,
    projectionFailureType: null,
    projectionLatencyMs: 2,
    answerLatencyMs: 7,
    providerUsageStatus: "AVAILABLE",
    providerInputTokens: 31,
    providerOutputTokens: 11,
    providerTotalTokens: 42
  };
}

test("validates empty questions and accepts trimmed Unicode questions", () => {
  assert.equal(validateQuestion("  \n"), "請先輸入問題。");
  assert.equal(validateQuestion("  什麼是 local-first？  "), null);
});

test("renders a normal answer and Wiki/Source provenance as text", () => {
  const elements = uiElements();
  renderAskResponse(elements, { data: {
    status: "ANSWERED",
    answer: "Answer <script>alert('x')</script>",
    insufficientEvidence: false,
    citations: [
      { evidenceKind: "WIKI", provenance: { type: "WIKI", title: "Wiki <img onerror=alert(1)>", path: "vault/page.md", revision: 3 } },
      { evidenceKind: "SOURCE_CHUNK", provenance: { type: "SOURCE", documentName: "design.pdf", pageNo: 8, section: "Summary", chunkNo: 2 } }
    ]
  } }, documentRef);

  assert.equal(elements.answer.hidden, false);
  assert.equal(elements.answerText.textContent, "Answer <script>alert('x')</script>");
  assert.equal(elements.citations.children.length, 2);
  assert.equal(elements.citations.children[0].children[1].children[1].textContent,
    "Wiki <img onerror=alert(1)>");
  assert.equal(elements.citations.children[1].children[1].children[2].textContent,
    "頁碼：8 · section：Summary · chunk：2");
});

test("source citations expose a safe locate button and wiki citations do not", () => {
  const elements = uiElements();
  renderAskResponse(elements, { data: {
    status: "ANSWERED",
    answer: "Answer",
    insufficientEvidence: false,
    citations: [
      { evidenceKind: "SOURCE_CHUNK", provenance: { type: "SOURCE", documentName: "design.pdf", documentId: 900, sourceChunkId: 42, chunkNo: 3, pageNo: 17 } },
      { evidenceKind: "WIKI", provenance: { type: "WIKI", title: "Wiki", path: "vault/page.md", revision: 3 } }
    ]
  } }, documentRef);

  const sourceItem = elements.citations.children[0];
  const wikiItem = elements.citations.children[1];
  const sourceButton = sourceItem.children[2];
  assert.equal(sourceButton.className, "citation-locate");
  assert.equal(sourceButton.attributes.get("data-chunk-id"), "42");
  assert.equal(wikiItem.children.length, 2);
});

test("renders insufficient evidence separately from an answer", () => {
  const elements = uiElements();
  renderAskResponse(elements, { data: {
    status: "INSUFFICIENT_EVIDENCE", insufficientEvidence: true, citations: []
  } }, documentRef);
  assert.equal(elements.insufficient.hidden, false);
  assert.equal(elements.answer.hidden, true);
  assert.equal(elements.citations.children.length, 0);
  assert.equal(elements.metadata.hidden, true);
});

test("renders bounded context diagnostics including provider usage", () => {
  const elements = uiElements();
  renderAskResponse(elements, { data: {
    status: "ANSWERED",
    answer: "grounded",
    citations: [{ evidenceKind: "WIKI", provenance: { type: "WIKI", title: "Page" } }],
    executionMetadata: { contextDiagnostics: contextDiagnostics() }
  } }, documentRef);

  assert.equal(elements.contextDiagnostics.hidden, false);
  const rendered = diagnosticsText(elements.contextDiagnosticsList);
  assert.match(rendered, /檢索 evidence::4/);
  assert.match(rendered, /通過 admission 的 evidence::3/);
  assert.match(rendered, /原始 code points::120/);
  assert.match(rendered, /Packed code points::80/);
  assert.match(rendered, /Projected code points::50/);
  assert.match(rendered, /Reduction::58\.3%/);
  assert.match(rendered, /Baseline truncated::是/);
  assert.match(rendered, /Compacted::是/);
  assert.match(rendered, /Projection 類型::EXTRACTIVE 1 · TRUNCATED 1/);
  assert.match(rendered, /Provider usage::已取得/);
  assert.match(rendered, /Provider input tokens::31/);
  assert.match(rendered, /Provider total tokens::42/);
});

test("renders context diagnostics on insufficient evidence without calling a provider", () => {
  const elements = uiElements();
  const diagnostics = contextDiagnostics();
  diagnostics.providerUsageStatus = "NOT_ATTEMPTED";
  diagnostics.providerInputTokens = null;
  diagnostics.providerOutputTokens = null;
  diagnostics.providerTotalTokens = null;
  renderAskResponse(elements, { data: {
    status: "INSUFFICIENT_EVIDENCE", insufficientEvidence: true, citations: [],
    executionMetadata: { contextDiagnostics: diagnostics }
  } }, documentRef);

  assert.equal(elements.insufficient.hidden, false);
  assert.equal(elements.contextDiagnostics.hidden, false);
  assert.match(diagnosticsText(elements.contextDiagnosticsList), /Provider usage::尚未呼叫/);
  assert.match(diagnosticsText(elements.contextDiagnosticsList), /Provider input tokens::—/);
});

test("ignores malformed or stale context diagnostics safely", () => {
  const elements = uiElements();
  renderAskResponse(elements, { data: {
    status: "ANSWERED", answer: "first",
    citations: [{ evidenceKind: "WIKI", provenance: { type: "WIKI", title: "Page" } }],
    executionMetadata: { contextDiagnostics: contextDiagnostics() }
  } }, documentRef);
  assert.equal(elements.contextDiagnostics.hidden, false);
  assert.ok(elements.contextDiagnosticsList.children.length > 0);

  renderAskResponse(elements, { data: {
    status: "ANSWERED", answer: "second",
    citations: [{ evidenceKind: "WIKI", provenance: { type: "WIKI", title: "Page" } }],
    executionMetadata: { contextDiagnostics: "malformed" }
  } }, documentRef);
  assert.equal(elements.contextDiagnostics.hidden, true);
  assert.equal(elements.contextDiagnosticsList.children.length, 0);
});

test("diagnostics use text nodes and reject secret-like free-form values", async () => {
  const elements = uiElements();
  const diagnostics = contextDiagnostics();
  diagnostics.contextPolicyVersion = "/Users/private/prompt-secret";
  diagnostics.projectionFailureType = "RID:secret-token";
  renderAskResponse(elements, { data: {
    status: "ANSWERED", answer: "safe",
    citations: [{ evidenceKind: "WIKI", provenance: { type: "WIKI", title: "Page" } }],
    executionMetadata: { contextDiagnostics: diagnostics }
  } }, documentRef);

  const rendered = diagnosticsText(elements.contextDiagnosticsList);
  assert.doesNotMatch(rendered, /private|prompt-secret|RID|secret-token/);
  assert.match(rendered, /Context policy::—/);
  assert.match(rendered, /Projection failure::—/);
  const source = await readFile("src/main/resources/static/ask-ui.js", "utf8");
  assert.doesNotMatch(source, /innerHTML/);

  diagnostics.contextPolicyVersion = "RID:secret-token";
  renderAskResponse(elements, { data: {
    status: "ANSWERED", answer: "safe",
    citations: [{ evidenceKind: "WIKI", provenance: { type: "WIKI", title: "Page" } }],
    executionMetadata: { contextDiagnostics: diagnostics }
  } }, documentRef);
  assert.match(diagnosticsText(elements.contextDiagnosticsList), /Context policy::—/);
  assert.doesNotMatch(diagnosticsText(elements.contextDiagnosticsList), /RID|secret-token/);
});

test("clears stale metadata and preserves it for a valid successful payload", () => {
  const elements = uiElements();
  elements.metadata.hidden = false;
  renderAskResponse(elements, { data: {
    status: "ANSWERED",
    answer: "fresh",
    citations: [{ evidenceKind: "WIKI", provenance: { type: "WIKI", title: "Architecture" } }],
    providerMetadata: { provider: "stub", model: "offline" }
  } }, documentRef);
  assert.equal(elements.metadata.hidden, false);
});

test("rejects malformed ANSWERED payloads safely", () => {
  const elements = uiElements();
  elements.metadata.hidden = false;
  const malformedPayloads = [
    { status: "ANSWERED", answer: "fresh" },
    { status: "ANSWERED", answer: "fresh", citations: null },
    { status: "ANSWERED", answer: "fresh", citations: {} },
    { status: "ANSWERED", answer: "fresh", citations: [] },
    { status: "ANSWERED", answer: "   ", citations: [{}] }
  ];

  for (const data of malformedPayloads) {
    renderAskResponse(elements, { data }, documentRef);
    assert.equal(elements.error.hidden, false);
    assert.equal(elements.answer.hidden, true);
    assert.equal(elements.citations.children.length, 0);
    assert.equal(elements.citationCount.textContent, "");
    assert.equal(elements.metadata.hidden, true);
  }
});

test("maps typed errors to safe user-facing messages", () => {
  assert.deepEqual(errorMessage({ code: "NO_ACTIVE_WORKSPACE" }), {
    title: "尚未開啟知識庫",
    message: "請先在本機應用程式中建立或開啟 active workspace。"
  });
  assert.deepEqual(errorMessage({ code: "ANSWER_PROVIDER_UNAVAILABLE", message: "secret" }).title,
    "回答服務暫時無法使用");
  assert.equal(errorMessage({ code: "UNKNOWN" }).title, "無法取得回答");
  assert.deepEqual(errorMessage({ code: "RETRIEVAL_VECTOR_UNAVAILABLE" }), {
    title: "語意搜尋暫時無法使用",
    message: "目前無法使用語意搜尋能力，請稍後再試或改用全文搜尋。"
  });
});

test("exposes additive semantic and graph modes without redefining HYBRID_FTS", () => {
  assert.deepEqual(RETRIEVAL_MODES.map(mode => mode.value), [
    "HYBRID_FTS", "WIKI_ONLY", "SOURCE_ONLY",
    "SEMANTIC_WIKI", "SEMANTIC_SOURCE", "HYBRID_VECTOR", "HYBRID_GRAPH"
  ]);
  assert.match(RETRIEVAL_MODES[0].label, /全文搜尋/);
  const graphMode = RETRIEVAL_MODES[RETRIEVAL_MODES.length - 1];
  assert.equal(graphMode.value, "HYBRID_GRAPH");
  assert.match(graphMode.label, /圖譜/);
  assert.doesNotMatch(graphMode.label, /ArcadeDB|sqlite|vendor/i);
});

test("renders a safe degraded hybrid notice without exposing diagnostics", () => {
  const elements = uiElements();
  renderAskResponse(elements, { data: {
    status: "ANSWERED", answer: "grounded", citations: [
      { evidenceKind: "WIKI", provenance: { type: "WIKI", title: "Page" } }
    ],
    retrievalMetadata: {
      strategy: "HYBRID", lexicalSignalUsed: true, vectorSignalUsed: false,
      degradedFallback: true, vectorUnavailable: true,
      vectorUnavailableReason: "native sqlite-vec path / secret"
    }
  } }, documentRef);
  assert.equal(elements.metadata.hidden, false);
  assert.match(elements.metadata.textContent, /全文搜尋結果/);
  assert.doesNotMatch(elements.metadata.textContent, /sqlite|secret|native/);
});

test("prevents double submit while the independent request is in flight", async () => {
  const elements = uiElements();
  elements.question.value = "How does this work?";
  elements.retrievalMode.value = "HYBRID_FTS";
  let calls = 0;
  let release;
  const pending = new Promise(resolve => { release = resolve; });
  const controller = createAskController(elements, async (url, options) => {
    calls += 1;
    assert.equal(url, "/api/v1/ask");
    assert.equal(JSON.parse(options.body).retrievalMode, "HYBRID_FTS");
    await pending;
    return { ok: true, async json() { return { data: { status: "INSUFFICIENT_EVIDENCE", insufficientEvidence: true, citations: [] } }; } };
  }, documentRef);
  const first = controller.submit(event());
  await Promise.resolve();
  const second = controller.submit(event());
  assert.equal(calls, 1);
  assert.equal(elements.submit.disabled, true);
  release();
  await first;
  await second;
  assert.equal(elements.submit.disabled, false);
});

test("sends the selected semantic retrieval mode", async () => {
  const elements = uiElements();
  elements.question.value = "Explain embeddings";
  elements.retrievalMode.value = "HYBRID_VECTOR";
  let requestBody;
  const controller = createAskController(elements, async (url, options) => {
    requestBody = JSON.parse(options.body);
    return { ok: true, async json() {
      return { data: { status: "INSUFFICIENT_EVIDENCE", insufficientEvidence: true, citations: [] } };
    } };
  }, documentRef);
  await controller.submit(event());
  assert.equal(requestBody.retrievalMode, "HYBRID_VECTOR");
});

test("sends the selected graph-grounded retrieval mode", async () => {
  const elements = uiElements();
  elements.question.value = "Explain the graph mode";
  elements.retrievalMode.value = "HYBRID_GRAPH";
  let requestBody;
  const controller = createAskController(elements, async (url, options) => {
    requestBody = JSON.parse(options.body);
    return { ok: true, async json() {
      return { data: { status: "INSUFFICIENT_EVIDENCE", insufficientEvidence: true, citations: [] } };
    } };
  }, documentRef);
  await controller.submit(event());
  assert.equal(requestBody.retrievalMode, "HYBRID_GRAPH");
});

test("renders a graph-grounded answer with degradation as a safe notice, not a failure", () => {
  const elements = uiElements();
  renderAskResponse(elements, { data: {
    status: "ANSWERED",
    answer: "graph-grounded answer",
    citations: [
      { citationId: "E1", evidenceKind: "WIKI", provenance: { type: "WIKI", title: "Page" } },
      { citationId: "E2", evidenceKind: "SOURCE_CHUNK", provenance: { type: "SOURCE", documentName: "design.pdf" } }
    ],
    retrievalMetadata: {
      strategy: "FUSED", lexicalSignalUsed: true, vectorSignalUsed: true,
      degradedFallback: false, vectorUnavailable: false,
      graphSignalUsed: true, graphDegraded: true, graphUnavailable: false,
      graphDetail: "internal handoff projection currentness drift: GRAPH_PROJECTION_STALE"
    }
  } }, documentRef);

  assert.equal(elements.error.hidden, true);
  assert.equal(elements.answer.hidden, false);
  assert.equal(elements.insufficient.hidden, true);
  assert.equal(elements.citations.children.length, 2);
  assert.match(elements.metadata.textContent, /知識圖譜訊號已降級/);
  assert.doesNotMatch(elements.metadata.textContent,
    /handoff|projection|stale|generation|snapshot|ArcadeDB/i);
});

test("renders an unavailable graph signal as a safe notice while the answer stays valid", () => {
  const elements = uiElements();
  renderAskResponse(elements, { data: {
    status: "ANSWERED",
    answer: "baseline answer",
    citations: [{ citationId: "E1", evidenceKind: "WIKI", provenance: { type: "WIKI", title: "Page" } }],
    retrievalMetadata: {
      strategy: "FUSED", lexicalSignalUsed: true, vectorSignalUsed: true,
      degradedFallback: false, vectorUnavailable: false,
      graphSignalUsed: false, graphDegraded: false, graphUnavailable: true,
      graphDetail: "internal readiness status"
    }
  } }, documentRef);

  assert.equal(elements.answer.hidden, false);
  assert.equal(elements.error.hidden, true);
  assert.match(elements.metadata.textContent, /知識圖譜訊號暫時無法使用/);
  assert.doesNotMatch(elements.metadata.textContent, /readiness|snapshot|ArcadeDB/i);
});

test("graph degradation with insufficient evidence still shows the insufficient state, not an error", () => {
  const elements = uiElements();
  renderAskResponse(elements, { data: {
    status: "INSUFFICIENT_EVIDENCE", insufficientEvidence: true, citations: [],
    retrievalMetadata: {
      strategy: "FUSED", lexicalSignalUsed: true, vectorSignalUsed: true,
      degradedFallback: false, vectorUnavailable: false,
      graphSignalUsed: true, graphDegraded: true, graphUnavailable: false
    }
  } }, documentRef);
  assert.equal(elements.insufficient.hidden, false);
  assert.equal(elements.error.hidden, true);
  assert.equal(elements.answer.hidden, true);
  assert.equal(elements.metadata.hidden, true);
});

test("keeps server citation order without re-ranking citations in the browser", () => {
  const elements = uiElements();
  // The server order is authoritative; the browser must not reorder citations by modality,
  // vendor score, or any local policy.
  renderAskResponse(elements, { data: {
    status: "ANSWERED",
    answer: "ordered answer",
    citations: [
      { citationId: "E2", evidenceKind: "SOURCE_CHUNK", provenance: { type: "SOURCE", documentName: "b.pdf" } },
      { citationId: "E1", evidenceKind: "WIKI", provenance: { type: "WIKI", title: "a" } }
    ]
  } }, documentRef);
  assert.equal(elements.citations.children[0].children[1].children[0].textContent, "SOURCE");
  assert.equal(elements.citations.children[1].children[1].children[0].textContent, "WIKI");
});

test("does not add persistence, unsafe HTML APIs, vendor internals, or graph endpoints to the UI module", async () => {
  const source = await readFile(new URL("../../main/resources/static/ask-ui.js", import.meta.url), "utf8");
  assert.doesNotMatch(source, /localStorage|sessionStorage/);
  assert.doesNotMatch(source, /innerHTML/);
  assert.doesNotMatch(source, /ArcadeDB|sqlite-vec|snapshotToken|sourceFingerprint|vendorScore/i);
  // The only allowed non-ask endpoint is the read-only provider egress transparency surface;
  // it is an application-owned descriptor (no credentials, no raw endpoints, no mutations).
  assert.doesNotMatch(source, /api\/v1\/(?!ask\b|system\/ai-provider-egress\b|system\/ai-provider-egress\?)/);
});

test("loads and renders the provider egress trust indicator with safe text only", async () => {
  const elements = { aiEgress: new FakeElement(), aiEgressLabel: new FakeElement(),
    aiEgressDetail: new FakeElement(), aiEgressToggle: null };
  const responses = [
    {
      ok: true,
      json: async () => ({ data: [
        {
          purpose: "ANSWER",
          destinationClass: "REMOTE_SECURE",
          providerType: "openai-compatible",
          modelDisplayName: "offline-model",
          egressCategories: [
            "QUESTION_TEXT",
            "EVIDENCE_CONTEXT_REPRESENTATION",
            "INSTRUCTION_CONTEXT",
            "GENERATION_SETTINGS",
            "PROVIDER_RESPONSE_METADATA"
          ]
        },
        {
          purpose: "EMBEDDING",
          destinationClass: "LOCAL_LOOPBACK",
          providerType: "openai-compatible",
          modelDisplayName: null,
          egressCategories: ["EMBEDDING_INPUT_REPRESENTATION"]
        }
      ] })
    }
  ];
  let fetchCalls = 0;
  const fetchImpl = async (url) => {
    fetchCalls += 1;
    assert.equal(url, "/api/v1/system/ai-provider-egress");
    return responses[Math.min(fetchCalls - 1, responses.length - 1)];
  };

  await loadAiEgress(elements, fetchImpl, documentRef);

  assert.equal(elements.aiEgress.hidden, false);
  assert.equal(elements.aiEgressLabel.textContent, "遠端安全連線");
  const detailText = diagnosticsText(elements.aiEgressDetail);
  assert.match(detailText, /遠端安全連線/);
  assert.match(detailText, /使用者問題文字/);
  assert.match(detailText, /經挑選的文本表示/);
  assert.doesNotMatch(detailText, /https?:\/\//);
  assert.doesNotMatch(detailText, /api-key|bearer|secret/i);
  assert.equal(elements.aiEgress.className, "ai-egress ai-egress--remote");
});

test("marks insecure explicit opt-in transport prominently and disabled as not local", async () => {
  const insecure = { aiEgress: new FakeElement(), aiEgressLabel: new FakeElement(),
    aiEgressDetail: new FakeElement() };
  await loadAiEgress(insecure, async () => ({ ok: true, json: async () => ({ data: [
    { purpose: "ANSWER", destinationClass: "REMOTE_INSECURE_OPT_IN", providerType: null,
      modelDisplayName: null, egressCategories: [] }
  ] }) }), documentRef);
  assert.match(insecure.aiEgress.className, /insecure/);
  assert.equal(insecure.aiEgressLabel.textContent, "遠端明文連線（已明確開啟）");

  const disabled = { aiEgress: new FakeElement(), aiEgressLabel: new FakeElement(),
    aiEgressDetail: new FakeElement() };
  await loadAiEgress(disabled, async () => ({ ok: true, json: async () => ({ data: [
    { purpose: "ANSWER", destinationClass: "DISABLED", providerType: null,
      modelDisplayName: null, egressCategories: [] }
  ] }) }), documentRef);
  assert.equal(disabled.aiEgressLabel.textContent, "AI 未啟用");
  assert.doesNotMatch(disabled.aiEgress.className, /local/);
});

test("hides the indicator when egress disclosure is unavailable and never blocks asking", async () => {
  const networkFailure = { aiEgress: new FakeElement(), aiEgressLabel: new FakeElement(),
    aiEgressDetail: new FakeElement() };
  await loadAiEgress(networkFailure, async () => { throw new Error("network down"); },
    documentRef);
  assert.equal(networkFailure.aiEgress.hidden, true);

  const notFound = { aiEgress: new FakeElement(), aiEgressLabel: new FakeElement(),
    aiEgressDetail: new FakeElement() };
  await loadAiEgress(notFound, async () => ({ ok: false, json: async () => ({}) }),
    documentRef);
  assert.equal(notFound.aiEgress.hidden, true);
});

test("refreshes the indicator after every completed submit to avoid stale destinations", async () => {
  const elements = uiElements();
  elements.aiEgress = new FakeElement();
  elements.aiEgressLabel = new FakeElement();
  elements.aiEgressDetail = new FakeElement();
  elements.aiEgressToggle = new FakeElement();
  let egressCalls = 0;
  const fetchImpl = async (url) => {
    if (url === "/api/v1/system/ai-provider-egress") {
      egressCalls += 1;
      return { ok: true, json: async () => ({ data: [
        { purpose: "ANSWER", destinationClass: "DISABLED", providerType: null,
          modelDisplayName: null, egressCategories: [] }
      ] }) };
    }
    return { ok: true, json: async () => ({ data: { status: "FAILED", error: {
      code: "LOCAL_VALIDATION", message: "provider disabled" } } }) };
  };
  const controller = createAskController(elements, fetchImpl, documentRef);
  elements.question.value = "question";
  elements.retrievalMode.value = "WIKI_ONLY";

  await controller.submit(event());

  assert.ok(egressCalls >= 1, "submit must refresh the egress indicator");
});
