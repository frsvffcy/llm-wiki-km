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
    documentScope: new FakeElement(), documentScopeLabel: new FakeElement(),
    documentScopeClear: new FakeElement(),
    empty: new FakeElement(), error: new FakeElement(), errorTitle: new FakeElement(),
    errorMessage: new FakeElement(), insufficient: new FakeElement(), answer: new FakeElement(),
    answerText: new FakeElement(), metadata: new FakeElement(), citations: new FakeElement(),
    citationCount: new FakeElement(), contextDiagnostics: new FakeElement(),
    contextDiagnosticsList: new FakeElement(), aiEgress: new FakeElement(),
    aiEgressLabel: new FakeElement(), aiEgressDetail: new FakeElement(),
    aiEgressToggle: new FakeElement(),
    toProposal: new FakeElement(), toProposalHint: new FakeElement(),
    toReview: new FakeElement(),
    viewRetrieval: new FakeElement(),
    sourcePreview: new FakeElement(), sourcePreviewLoading: new FakeElement(),
    sourcePreviewMeta: new FakeElement(),
    sourcePreviewBody: new FakeElement(), sourcePreviewClose: new FakeElement(),
    sourcePreviewError: new FakeElement(), sourcePreviewErrorTitle: new FakeElement(),
    sourcePreviewErrorMessage: new FakeElement(), sourcePreviewNotFound: new FakeElement(),
    sourcePreviewNotFoundTitle: new FakeElement(),
    sourcePreviewNotFoundMessage: new FakeElement()
  };
}

function routedDocument(hash = "#/ask") {
  const documentListeners = new Map();
  const viewListeners = new Map();
  const view = {
    location: { hash },
    addEventListener(name, handler) { viewListeners.set(name, handler); }
  };
  return {
    createElement: () => new FakeElement(),
    defaultView: view,
    addEventListener(name, handler) { documentListeners.set(name, handler); },
    documentListeners,
    viewListeners
  };
}

function event() { return { preventDefault() {} }; }

function flatText(element) {
  return [element.textContent,
    ...element.children.map(child => flatText(child))].join(" ");
}

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
    "頁碼：8 · 節次：Summary · 片段編號：2");
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
  assert.match(rendered, /檢索證據::4/);
  assert.match(rendered, /通過納入檢查的證據::3/);
  assert.match(rendered, /原始字元數（code points）::120/);
  assert.match(rendered, /打包後字元數（code points）::80/);
  assert.match(rendered, /投影後字元數（code points）::50/);
  assert.match(rendered, /縮減比例::58\.3%/);
  assert.match(rendered, /基準已截斷::是/);
  assert.match(rendered, /已壓縮::是/);
  assert.match(rendered, /投影類型::EXTRACTIVE 1 · TRUNCATED 1/);
  assert.match(rendered, /提供者使用情況::已取得/);
  assert.match(rendered, /提供者輸入 token 數::31/);
  assert.match(rendered, /提供者總 token 數::42/);
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
  assert.match(diagnosticsText(elements.contextDiagnosticsList), /提供者使用情況::尚未呼叫/);
  assert.match(diagnosticsText(elements.contextDiagnosticsList), /提供者輸入 token 數::—/);
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
  assert.match(rendered, /上下文政策::—/);
  assert.match(rendered, /投影失敗類型::—/);
  const source = await readFile("src/main/resources/static/ask-ui.js", "utf8");
  assert.doesNotMatch(source, /innerHTML/);

  diagnostics.contextPolicyVersion = "RID:secret-token";
  renderAskResponse(elements, { data: {
    status: "ANSWERED", answer: "safe",
    citations: [{ evidenceKind: "WIKI", provenance: { type: "WIKI", title: "Page" } }],
    executionMetadata: { contextDiagnostics: diagnostics }
  } }, documentRef);
  assert.match(diagnosticsText(elements.contextDiagnosticsList), /上下文政策::—/);
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
    message: "請先在本機應用程式中建立或開啟目前工作區。"
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

test("loads an authoritative document scope and submits its application identity", async () => {
  const elements = uiElements();
  const routed = routedDocument("#/ask?documentId=42");
  const bodies = [];
  const controller = createAskController(elements, async (url, options) => {
    if (String(url) === "/api/v1/inbox/documents/42") {
      return { ok: true, async json() { return { data: {
        documentId: 42, fileName: "規格 A.pdf",
        usability: { status: "READY_TO_USE", searchReady: true }
      } }; } };
    }
    bodies.push(JSON.parse(options.body));
    return { ok: true, async json() { return { data: {
      status: "INSUFFICIENT_EVIDENCE", insufficientEvidence: true, citations: []
    } }; } };
  }, routed);

  await controller.loadDocumentScope();
  assert.equal(elements.documentScope.hidden, false);
  assert.equal(elements.documentScopeLabel.textContent, "目前針對：規格 A.pdf");
  elements.question.value = "這份文件的限制是什麼？";
  elements.retrievalMode.value = "HYBRID_GRAPH";
  await controller.submit(event());

  assert.equal(bodies.length, 1);
  assert.equal(bodies[0].documentId, 42);
  assert.equal(bodies[0].retrievalMode, "HYBRID_GRAPH");
});

test("document scope labels describe strategy only and never claim Wiki corpus", async () => {
  const elements = uiElements();
  const routed = routedDocument("#/ask?documentId=22");
  const controller = createAskController(elements, async (url) => {
    assert.match(String(url), /\/inbox\/documents\/22$/u);
    return { ok: true, async json() { return { data: {
      documentId: 22, fileName: "design.pdf", originalFileName: "design.pdf",
      usability: { status: "READY_TO_USE", searchReady: true }
    } }; } };
  }, routed);

  await controller.loadDocumentScope();
  const scopedLabels = elements.retrievalMode.children.map(option => option.textContent);
  assert.equal(scopedLabels.length, RETRIEVAL_MODES.length);
  assert.ok(scopedLabels.every(label => label.includes("此文件")));
  assert.ok(scopedLabels.every(label => !/Wiki|來源文件/u.test(label)));

  elements.retrievalMode.value = "SEMANTIC_WIKI";
  elements.documentScopeClear.handlers.get("click")();
  const unscopedLabels = elements.retrievalMode.children.map(option => option.textContent);
  assert.deepEqual(unscopedLabels, RETRIEVAL_MODES.map(mode => mode.label));
  assert.equal(elements.retrievalMode.value, "SEMANTIC_WIKI");
});

test("clearing document scope restores the original unscoped Ask request", async () => {
  const elements = uiElements();
  const routed = routedDocument("#/ask?documentId=7");
  let body;
  const controller = createAskController(elements, async (url, options) => {
    if (String(url).includes("/inbox/documents/")) {
      return { ok: true, async json() { return { data: {
        documentId: 7, fileName: "A.txt",
        usability: { status: "READY_TO_USE", searchReady: true }
      } }; } };
    }
    body = JSON.parse(options.body);
    return { ok: true, async json() { return { data: {
      status: "INSUFFICIENT_EVIDENCE", insufficientEvidence: true, citations: []
    } }; } };
  }, routed);
  await controller.loadDocumentScope();

  elements.documentScopeClear.handlers.get("click")();
  assert.equal(routed.defaultView.location.hash, "#/ask");
  assert.equal(elements.documentScope.hidden, true);
  elements.question.value = "改問全部";
  elements.retrievalMode.value = "HYBRID_FTS";
  await controller.submit(event());

  assert.equal(Object.hasOwn(body, "documentId"), false);
});

test("clearing scope rejects an in-flight response from the previous document", async () => {
  const elements = uiElements();
  const routed = routedDocument("#/ask?documentId=8");
  let releaseAsk;
  const pendingAsk = new Promise(resolve => { releaseAsk = resolve; });
  const controller = createAskController(elements, async (url) => {
    if (String(url).includes("/inbox/documents/")) {
      return { ok: true, async json() { return { data: {
        documentId: 8, fileName: "舊範圍.txt",
        usability: { status: "READY_TO_USE", searchReady: true }
      } }; } };
    }
    await pendingAsk;
    return { ok: true, async json() { return { data: {
      status: "ANSWERED", insufficientEvidence: false, answer: "舊範圍回答",
      citations: [{ kind: "SOURCE_CHUNK", documentId: 8, title: "舊範圍.txt" }]
    } }; } };
  }, routed);
  await controller.loadDocumentScope();
  elements.question.value = "先問舊範圍";
  elements.retrievalMode.value = "HYBRID_FTS";

  const submission = controller.submit(event());
  elements.documentScopeClear.handlers.get("click")();
  releaseAsk();
  await submission;

  assert.equal(elements.documentScope.hidden, true);
  assert.equal(elements.answer.hidden, true);
  assert.equal(elements.citations.children.length, 0);
  assert.equal(routed.defaultView.location.hash, "#/ask");
});

test("workspace switch clears scope and ignores the previous workspace response", async () => {
  const elements = uiElements();
  const routed = routedDocument("#/ask?documentId=9");
  let release;
  const pending = new Promise(resolve => { release = resolve; });
  const controller = createAskController(elements, async url => {
    if (String(url).includes("/inbox/documents/")) {
      await pending;
      return { ok: true, async json() { return { data: {
        documentId: 9, fileName: "舊工作區.txt",
        usability: { status: "READY_TO_USE", searchReady: true }
      } }; } };
    }
    throw new Error("workspace switch must prevent Ask submission");
  }, routed);

  routed.documentListeners.get("workspace-changed")();
  assert.equal(routed.defaultView.location.hash, "#/ask");
  release();
  await controller.loadDocumentScope();
  assert.equal(elements.documentScope.hidden, true);
  assert.equal(elements.documentScopeLabel.textContent, "");
  assert.deepEqual(elements.retrievalMode.children.map(option => option.textContent),
    RETRIEVAL_MODES.map(mode => mode.label));
});

test("invalid document scope fails closed instead of silently asking the whole knowledge base", async () => {
  const elements = uiElements();
  const routed = routedDocument("#/ask?documentId=15");
  let askCalls = 0;
  const controller = createAskController(elements, async (url) => {
    if (String(url).includes("/inbox/documents/")) {
      return { ok: false, async json() { return { error: {
        code: "DOCUMENT_NOT_FOUND", message: "not found"
      } }; } };
    }
    askCalls += 1;
    throw new Error("must not ask");
  }, routed);
  await controller.loadDocumentScope();
  elements.question.value = "不能偷跑";
  elements.retrievalMode.value = "HYBRID_FTS";
  await controller.submit(event());

  assert.equal(askCalls, 0);
  assert.match(elements.documentScopeLabel.textContent, /無法使用/u);
  assert.match(elements.hint.textContent, /尚未通過可用性確認/u);
  assert.deepEqual(elements.retrievalMode.children.map(option => option.textContent),
    RETRIEVAL_MODES.map(mode => mode.label));
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
  assert.doesNotMatch(source,
    /api\/v1\/(?!ask\b|inbox\/documents\/[${}\w-]+|system\/ai-provider-egress\b|system\/ai-provider-egress\?)/);
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
        },
        {
          purpose: "QUERY_REWRITE",
          destinationClass: "REMOTE_SECURE",
          providerType: "openai-compatible",
          modelDisplayName: "rewrite-model",
          egressCategories: ["QUERY_REWRITE_INPUT", "QUERY_REWRITE_RESPONSE_METADATA"]
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
  assert.match(detailText, /查詢改寫 · 用途::查詢改寫提供者/);
  assert.match(detailText, /原始查詢與受保護的精確詞/);
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

// --- Governed Ask -> Proposal hand-off (#374) ---

function proposalEnvelope(duplicate = false) {
  return {
    ok: true,
    async json() {
      return { data: { proposal: { id: 77, status: "REVIEW" }, duplicate } };
    }
  };
}

function groundedPayload() {
  return {
    ok: true,
    async json() {
      return {
        data: {
          status: "ANSWERED",
          answer: "Transformer 以 self-attention 為核心。",
          insufficientEvidence: false,
          citations: [
            { citationId: "E1", evidenceKind: "SOURCE_CHUNK",
              provenance: { type: "SOURCE", documentName: "a.pdf", sourceChunkId: 201 } },
            { citationId: "E2", evidenceKind: "WIKI",
              provenance: { type: "WIKI", title: "Attention", path: "vault/concepts/attention.md", revision: 2 } }
          ],
          providerMetadata: { provider: "openai-compatible", model: "gpt-test" }
        }
      };
    }
  };
}

test("the proposal hand-off is explicit, grounded-only, and posts the governed payload", async () => {
  const elements = uiElements();
  const calls = [];
  let answered = false;
  const fetchImpl = async (url, options) => {
    calls.push({ url: String(url), method: options?.method, body: options?.body });
    if (String(url) === "/api/v1/ask") {
      answered = true;
      return groundedPayload();
    }
    if (String(url) === "/api/v1/ask/proposals") {
      return proposalEnvelope(false);
    }
    return { ok: true, async json() { return { data: { disclosures: [] } }; } };
  };
  const controller = createAskController(elements, fetchImpl, documentRef);
  elements.question.value = "transformer 的核心架構原則是什麼？";

  // Before any ask, the hand-off must not be offered or callable.
  await controller.proposeFromAnswer();
  assert.equal(calls.filter(call => String(call.url).includes("/ask/proposals")).length, 0,
    "no proposal can be created without a grounded answer");

  await elements.form.handlers.get("submit")({ preventDefault() {} });
  assert.equal(answered, true);
  assert.equal(elements.toProposal.hidden, false);

  await elements.toProposal.handlers.get("click")();
  const proposalCall = calls.find(call => String(call.url) === "/api/v1/ask/proposals");
  assert.equal(proposalCall.method, "POST");
  const body = JSON.parse(proposalCall.body);
  assert.equal(body.question, "transformer 的核心架構原則是什麼？");
  assert.equal(body.answerText, "Transformer 以 self-attention 為核心。");
  assert.equal(body.provider, "openai-compatible");
  assert.equal(body.model, "gpt-test");
  assert.equal(body.citations[0].sourceChunkId, 201);
  assert.equal(body.citations[0].kind, "SOURCE");
  assert.equal(body.citations[1].kind, "WIKI");
  assert.equal(body.citations[1].wikiPath, "vault/concepts/attention.md");
  assert.equal(body.citations[1].wikiRevision, 2);
  assert.ok(!JSON.stringify(body).includes('"sourceChunkId":null'),
    "WIKI citations must not carry a null chunk id");
  assert.match(elements.toProposalHint.textContent, /已保存成知識並進入審核/u);
  assert.match(elements.toProposalHint.textContent, /不會立即發布/u);
  assert.equal(elements.toReview.hidden, false,
    "a saved answer hands directly into the same review workflow (#570)");
});

test("double-submit is guarded and typed failures surface without success copy", async () => {
  const elements = uiElements();
  let release;
  const gate = new Promise(resolve => { release = resolve; });
  const calls = [];
  const fetchImpl = async (url, options) => {
    if (String(url) === "/api/v1/ask") {
      return groundedPayload();
    }
    if (String(url) === "/api/v1/ask/proposals") {
      calls.push("proposal");
      await gate;
      return proposalEnvelope(false);
    }
    return { ok: true, async json() { return { data: { disclosures: [] } }; } };
  };
  const controller = createAskController(elements, fetchImpl, documentRef);
  elements.question.value = "transformer 的核心架構原則是什麼？";
  await elements.form.handlers.get("submit")({ preventDefault() {} });
  const first = elements.toProposal.handlers.get("click")();
  const second = elements.toProposal.handlers.get("click")();
  release();
  await Promise.all([first, second]);
  assert.equal(calls.filter(entry => entry === "proposal").length, 1,
    "in-flight hand-off blocks a second submission");

  // Typed failure: the stale-citation copy replaces any success hint.
  let stale = true;
  const staleFetch = async (url, options) => {
    if (String(url) === "/api/v1/ask") return groundedPayload();
    if (String(url) === "/api/v1/ask/proposals") {
      return { ok: false, status: 422, json: async () => ({
        error: { code: "ASK_CITATION_INVALID", message: "stale" } }) };
    }
    return { ok: true, async json() { return { data: { disclosures: [] } }; } };
  };
  const staleElements = uiElements();
  const staleController = createAskController(staleElements, staleFetch, documentRef);
  staleElements.question.value = "transformer 的核心架構原則是什麼？";
  await staleElements.form.handlers.get("submit")({ preventDefault() {} });
  await staleElements.toProposal.handlers.get("click")();
  assert.match(staleElements.toProposalHint.textContent,
    /引用的證據已失效/u, "stale citations surface a typed, actionable failure");
  assert.equal(staleElements.toProposal.disabled, false,
    "a failed hand-off stays retryable");
  assert.equal(staleElements.toReview.hidden, true,
    "a failed hand-off offers no review handoff");
});

test("a duplicated save reuses task copy and still hands into review", async () => {
  const elements = uiElements();
  const fetchImpl = async url => {
    if (String(url) === "/api/v1/ask") return groundedPayload();
    if (String(url) === "/api/v1/ask/proposals") return proposalEnvelope(true);
    return { ok: true, async json() { return { data: { disclosures: [] } }; } };
  };
  const controller = createAskController(elements, fetchImpl, documentRef);
  elements.question.value = "transformer 的核心架構原則是什麼？";
  await elements.form.handlers.get("submit")({ preventDefault() {} });
  await elements.toProposal.handlers.get("click")();
  assert.match(elements.toProposalHint.textContent, /先前已保存成知識/u);
  assert.equal(elements.toReview.hidden, false);
});

test("the retrieval diagnostics hand-off prefills the inspector question and navigates", async () => {
  const elements = uiElements();
  const fetchImpl = async url => {
    if (String(url) === "/api/v1/ask") return groundedPayload();
    return { ok: true, async json() { return { data: { disclosures: [] } }; } };
  };
  const inspectorQuestion = new FakeElement();
  const navigator = { location: { hash: "#/ask" } };
  const documentRef = {
    createElement: () => new FakeElement(),
    getElementById: id => id === "inspector-question" ? inspectorQuestion : new FakeElement(),
    defaultView: navigator
  };
  const controller = createAskController(elements, fetchImpl, documentRef);
  elements.question.value = "transformer 的核心架構原則是什麼？";
  await elements.form.handlers.get("submit")({ preventDefault() {} });

  elements.viewRetrieval.handlers.get("click")();

  assert.equal(inspectorQuestion.value, "transformer 的核心架構原則是什麼？",
    "the inspector opens with the asked question");
  assert.equal(navigator.location.hash, "#/inspect");
});

test("the hand-off never fabricates a query before a grounded answer exists", async () => {
  const elements = uiElements();
  const inspectorQuestion = new FakeElement();
  const navigator = { location: { hash: "" } };
  const documentRef = {
    createElement: () => new FakeElement(),
    getElementById: id => id === "inspector-question" ? inspectorQuestion : new FakeElement(),
    defaultView: navigator
  };
  const controller = createAskController(elements, async () => {
    throw new Error("must not fetch");
  }, documentRef);

  controller.viewRetrievalDiagnostics();
  assert.equal(inspectorQuestion.value, "",
    "no question may be prefilled or fabricated before any ask ran");
});

// --- Inline citation source preview (#381) ---

function locatorPayload(overrides = {}) {
  return { data: {
    sourceChunkId: 201, documentId: 3, documentName: "a.pdf", chunkNo: 2,
    pageNo: 4, section: "arch", headingPath: "system > arch",
    currentness: "CURRENT", notCurrentReason: null,
    preview: "Transformer uses self-attention <img src=x> as the core.",
    previewTruncated: false, ...overrides } };
}

function locatorResponse(payload) {
  return { ok: true, status: 200, async json() { return payload; } };
}

async function submitAndOpenPreview(customFetch) {
  const elements = uiElements();
  let answered = false;
  const navigator = { location: { hash: "#/ask" } };
  const documentRef = {
    createElement: () => new FakeElement(),
    defaultView: navigator
  };
  elements.question.value = "transformer 的核心架構原則是什麼？";
  const controller = createAskController(elements, async url => {
    if (String(url) === "/api/v1/ask") { answered = true; return groundedPayload(); }
    return customFetch(url);
  }, documentRef);
  await elements.form.handlers.get("submit")({ preventDefault() {} });
  assert.equal(answered, true);
  await elements.citations.handlers.get("click")({
    preventDefault() {}, target: { getAttribute: () => "201" } });
  return { elements, navigator };
}

test("citation click renders the authoritative preview inline without navigation", async () => {
  const { elements, navigator } = await submitAndOpenPreview(async url => {
    if (String(url) === "/api/v1/source-chunks/201/locator") return locatorResponse(locatorPayload());
    if (String(url) === "/api/v1/ask") { answered = true; return groundedPayload(); }
    return { ok: true, async json() { return { data: { disclosures: [] } }; } };
  });

  assert.equal(elements.sourcePreview.hidden, false);
  const text = flatText(elements.sourcePreviewBody);
  assert.match(text, /Transformer uses self-attention <img src=x> as the core\./u,
    "preview renders as inert text — no HTML execution");
  assert.match(flatText(elements.sourcePreviewMeta), /a\.pdf/u);
  assert.match(flatText(elements.sourcePreviewMeta), /頁碼：4/u);
  assert.equal(navigator.location.hash, "#/ask",
    "the inline preview never navigates away from the answer");
});

test("coarse locators degrade deterministically without fabricating precision", async () => {
  const { elements, navigator } = await submitAndOpenPreview(async url => {
    if (String(url) === "/api/v1/source-chunks/201/locator") {
      return locatorResponse(locatorPayload({ pageNo: null, section: null, headingPath: null }));
    }
    if (String(url) === "/api/v1/ask") { answered = true; return groundedPayload(); }
    return { ok: true, async json() { return { data: { disclosures: [] } }; } };
  });

  const meta = flatText(elements.sourcePreviewMeta);
  assert.doesNotMatch(meta, /頁碼/u, "no page anchor exists — none is shown");
  console.log("DEBUG31 body:", JSON.stringify(flatText(elements.sourcePreviewBody).slice(0, 120)),
    "panelHidden:", elements.sourcePreview.hidden, "bodyHidden:", elements.sourcePreviewBody.hidden,
    "meta:", JSON.stringify(flatText(elements.sourcePreviewMeta).slice(0, 80)),
    "errorHidden:", elements.sourcePreviewError.hidden,
    "errorTitle:", JSON.stringify(elements.sourcePreviewErrorTitle.textContent),
    "notFoundHidden:", elements.sourcePreviewNotFound.hidden);
  assert.match(flatText(elements.sourcePreviewBody), /self-attention/u,
    "the bounded chunk preview is still the authoritative highlight");
});

test("stale sources expose the typed failure and never show cached content", async () => {
  const { elements, navigator } = await submitAndOpenPreview(async url => {
    if (String(url) === "/api/v1/source-chunks/201/locator") {
      return locatorResponse(locatorPayload({ currentness: "NOT_CURRENT",
        notCurrentReason: "STALE_REVISION", preview: null }));
    }
    if (String(url) === "/api/v1/ask") { answered = true; return groundedPayload(); }
    return { ok: true, async json() { return { data: { disclosures: [] } }; } };
  });

  assert.match(flatText(elements.sourcePreviewMeta), /已與權威狀態不一致/u);
  assert.doesNotMatch(flatText(elements.sourcePreviewBody), /self-attention/u,
    "not-current sources must not expose content");
});

test("unknown sources render the safe not-found state inline", async () => {
  const { elements, navigator } = await submitAndOpenPreview(async url => {
    if (String(url) === "/api/v1/source-chunks/201/locator") {
      return {
        ok: false,
        status: 404,
        async json() {
          return { error: { code: "SOURCE_CHUNK_NOT_FOUND", message: "gone" } };
        }
      };
    }
    if (String(url) === "/api/v1/ask") { answered = true; return groundedPayload(); }
    return { ok: true, async json() { return { data: { disclosures: [] } }; } };
  });

  assert.match(flatText(elements.sourcePreviewNotFoundTitle), /找不到來源位置/u,
    "the inline not-found state carries the typed locator copy");
});

test("truncated previews are labelled", async () => {
  const { elements, navigator } = await submitAndOpenPreview(async url => {
    if (String(url) === "/api/v1/source-chunks/201/locator") {
      return locatorResponse(locatorPayload({ previewTruncated: true }));
    }
    if (String(url) === "/api/v1/ask") { answered = true; return groundedPayload(); }
    return { ok: true, async json() { return { data: { disclosures: [] } }; } };
  });

  assert.match(flatText(elements.sourcePreviewBody), /預覽已截斷/u);
});

// --- Ask answer visual hierarchy (#486) ---

test("answered success exposes the answer text as the primary visible region", () => {
  const elements = uiElements();
  renderAskResponse(elements, { data: {
    status: "ANSWERED",
    answer: "Synthetic answer body for visual hierarchy regression.",
    insufficientEvidence: false,
    citations: [{ evidenceKind: "WIKI", provenance: { type: "WIKI", title: "Synthetic Page" } }],
    providerMetadata: { provider: "synthetic-provider", model: "synthetic-model" },
    executionMetadata: { contextDiagnostics: contextDiagnostics() }
  } }, documentRef);

  assert.equal(elements.answer.hidden, false);
  assert.ok(elements.answerText.textContent.trim().length > 0);
  assert.equal(elements.error.hidden, true);
  assert.equal(elements.insufficient.hidden, true);
  assert.equal(elements.citations.children.length, 1);
  // Secondary surfaces stay visible but do not replace the answer state.
  assert.equal(elements.contextDiagnostics.hidden, false);
  assert.match(elements.metadata.textContent, /synthetic-provider/);
});

test("insufficient and error states keep their own regions and never reuse the answer body", () => {
  const insufficient = uiElements();
  renderAskResponse(insufficient, { data: {
    status: "INSUFFICIENT_EVIDENCE", insufficientEvidence: true, citations: []
  } }, documentRef);
  assert.equal(insufficient.insufficient.hidden, false);
  assert.equal(insufficient.answer.hidden, true);
  assert.equal(insufficient.error.hidden, true);

  const failed = uiElements();
  renderAskResponse(failed, { data: { status: "ANSWERED", answer: "   ", citations: [{}] } },
    documentRef);
  assert.equal(failed.error.hidden, false);
  assert.equal(failed.answer.hidden, true);
  assert.equal(failed.insufficient.hidden, true);
});

test("answer body and egress/diagnostics are separate structural blocks, not color contracts (#486)", async () => {
  const [css, html, js] = await Promise.all([
    readFile(new URL("../../main/resources/static/styles.css", import.meta.url), "utf8"),
    readFile(new URL("../../main/resources/static/index.html", import.meta.url), "utf8"),
    readFile(new URL("../../main/resources/static/ask-ui.js", import.meta.url), "utf8")
  ]);

  // DOM separation: the answer lives in #result-answer; egress lives near the input
  // form; diagnostics is a sibling section after the answer, never inside it.
  const answerPos = html.indexOf('id="result-answer"');
  const answerTextPos = html.indexOf('id="answer-text"');
  const egressPos = html.indexOf('id="ai-egress"');
  const diagnosticsPos = html.indexOf('id="context-diagnostics"');
  assert.ok(answerPos !== -1 && answerTextPos > answerPos,
    "answer text lives inside the answer region");
  assert.ok(egressPos !== -1 && egressPos < answerPos,
    "egress disclosure lives near the input, outside the answer region");
  assert.ok(diagnosticsPos > answerPos,
    "diagnostics follows the answer as a separate section");
  assert.ok(html.includes('id="ai-egress-detail"'),
    "egress detail disclosure still exists");
  assert.ok(!html.slice(answerPos, diagnosticsPos).includes('id="ai-egress"'),
    "egress block is not nested inside the answer region");

  // Presentation contract is structural (container/spacing/typography), never a color value.
  const answerRule = css.match(/\.answer-text\s*\{[^}]*\}/u);
  assert.ok(answerRule, ".answer-text owns a dedicated container rule");
  const rule = answerRule[0];
  assert.match(rule, /border/u, "answer body has an explicit container boundary");
  assert.match(rule, /padding/u, "answer body has explicit spacing");
  assert.match(rule, /font-size/u, "answer body has explicit body typography");
  assert.match(rule, /line-height/u, "answer body keeps readable line spacing");
  assert.match(rule, /overflow-wrap/u, "answer body wraps long synthetic tokens on narrow screens");
  assert.match(css, /@media\s*\(max-width:\s*600px\)[\s\S]*?\.answer-text/u,
    "narrow screens keep a readable answer container");

  // Behavior boundary: rendering an answer never toggles the egress disclosure.
  const renderFn = js.slice(js.indexOf("export function renderAskResponse"),
    js.indexOf("function showError"));
  assert.doesNotMatch(renderFn, /aiEgress/,
    "answer rendering does not replace or obscure the egress disclosure");
});

// --- AI egress toggle visibility (#485) ---

test("egress toggle collapsed state owns a readable text color, not global white (#485)", async () => {
  const css = await readFile(new URL("../../main/resources/static/styles.css", import.meta.url), "utf8");
  const toggleRule = css.match(/\.ai-egress-toggle\s*\{[^}]*\}/u);
  assert.ok(toggleRule, ".ai-egress-toggle owns a dedicated rule");
  const rule = toggleRule[0];
  // #494 allows the semantic alias var(--foreground) (= var(--ink)) as bounded migration.
  assert.match(rule, /color\s*:\s*var\(--(ink|foreground)\)/u,
    "collapsed normal state uses dark ink text so the headline is readable without hover");
  assert.doesNotMatch(rule, /#fff/u,
    "the toggle must not reintroduce the global button white text");
  assert.match(rule, /background\s*:\s*transparent/u,
    "the toggle stays transparent over the light egress container");
});

test("egress toggle hover and focus keep dark-on-light text, not the global dark button (#485)", async () => {
  const css = await readFile(new URL("../../main/resources/static/styles.css", import.meta.url), "utf8");
  const hoverRule = css.match(/\.ai-egress-toggle:hover\s*\{[^}]*\}/u);
  assert.ok(hoverRule, ".ai-egress-toggle:hover overrides the global button:hover");
  assert.match(hoverRule[0], /color\s*:\s*var\(--(ink|foreground)\)/u,
    "hover keeps the headline readable instead of revealing it only on hover");
  assert.doesNotMatch(hoverRule[0], /var\(--accent-dark\)|#115e59/u,
    "hover must not reuse the global dark accent background on this light toggle");
  assert.match(hoverRule[0], /background/u, "hover owns an explicit light background");

  const focusRule = css.match(/\.ai-egress-toggle:focus-visible\s*\{[^}]*\}/u);
  assert.ok(focusRule, ".ai-egress-toggle:focus-visible exists for keyboard users");
  assert.match(focusRule[0], /color\s*:\s*var\(--(ink|foreground)\)/u,
    "keyboard focus keeps the headline readable");
  assert.match(focusRule[0], /outline/u, "keyboard focus stays discernible");
  assert.match(focusRule[0], /background/u,
    "focus keeps a light background instead of the global dark one");
});

test("egress toggle keeps severity modifiers and a hover-independent native button (#485)", async () => {
  const [css, html, js] = await Promise.all([
    readFile(new URL("../../main/resources/static/styles.css", import.meta.url), "utf8"),
    readFile(new URL("../../main/resources/static/index.html", import.meta.url), "utf8"),
    readFile(new URL("../../main/resources/static/ask-ui.js", import.meta.url), "utf8")
  ]);
  for (const modifier of ["ai-egress--insecure", "ai-egress--remote", "ai-egress--local", "ai-egress--off"]) {
    assert.match(css, new RegExp(`\\.${modifier}\\b`, "u"),
      `${modifier} severity styling is preserved`);
  }
  const toggleTag = html.match(/<button[^>]*id="ai-egress-toggle"[^>]*>/u);
  assert.ok(toggleTag, "the disclosure header is a native button, operable by touch and keyboard");
  assert.match(toggleTag[0], /type="button"/u, "the toggle never submits the Ask form");
  assert.match(toggleTag[0], /aria-expanded="false"/u, "collapsed is the explicit initial state");
  assert.match(toggleTag[0], /aria-controls="ai-egress-detail"/u, "the toggle controls the detail region");
  assert.doesNotMatch(js, /aiEgress\w*\s*\.\s*hidden\s*=\s*true[^]*mouseout|mouseout[^]*aiEgress/su,
    "no hover-out path hides the egress disclosure");
  assert.doesNotMatch(js, /addEventListener\(\s*["'](?:mouseout|mouseleave)["']/u,
    "header visibility never depends on pointer hover handlers");
});

test("egress toggle expands and collapses while the header label stays intact (#485)", () => {
  const elements = uiElements();
  elements.aiEgressToggle.setAttribute("aria-expanded", "false");
  elements.aiEgressDetail.hidden = true;
  elements.aiEgressLabel.textContent = "AI 未啟用";
  createAskController(elements, async () => { throw new Error("must not fetch"); }, documentRef);
  const click = elements.aiEgressToggle.handlers.get("click");
  assert.ok(click, "the toggle owns a click handler covering mouse, touch, and keyboard activation");

  const labelBefore = elements.aiEgressLabel.textContent;
  assert.ok(labelBefore.trim().length > 0, "the collapsed header already carries a readable label");

  click();
  assert.equal(elements.aiEgressToggle.getAttribute("aria-expanded"), "true");
  assert.equal(elements.aiEgressDetail.hidden, false);
  assert.equal(elements.aiEgressLabel.textContent, labelBefore,
    "expanding keeps the header text so the state stays understandable");

  click();
  assert.equal(elements.aiEgressToggle.getAttribute("aria-expanded"), "false");
  assert.equal(elements.aiEgressDetail.hidden, true);
  assert.equal(elements.aiEgressLabel.textContent, labelBefore,
    "leaving the expanded state (mouse-out equivalent) keeps the header readable");
});

test("disabled and remote headlines both render a non-empty label for the readable toggle (#485)", async () => {
  const cases = [
    ["DISABLED", "AI 未啟用"],
    ["REMOTE_SECURE", "遠端安全連線"],
    ["LOCAL_LOOPBACK", "本機 AI"]
  ];
  for (const [destination, expected] of cases) {
    const els = { aiEgress: new FakeElement(), aiEgressLabel: new FakeElement(),
      aiEgressDetail: new FakeElement() };
    await loadAiEgress(els, async () => ({ ok: true, json: async () => ({ data: [
      { purpose: "ANSWER", destinationClass: destination, providerType: null,
        modelDisplayName: null, egressCategories: [] }
    ] }) }), documentRef);
    assert.equal(els.aiEgress.hidden, false, `${destination} keeps the disclosure visible`);
    assert.equal(els.aiEgressLabel.textContent, expected,
      `${destination} headline renders its destination label without hover`);
  }
});
