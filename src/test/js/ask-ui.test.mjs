import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import {
  createAskController,
  errorMessage,
  renderAskResponse,
  validateQuestion
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
    citationCount: new FakeElement()
  };
}

function event() { return { preventDefault() {} }; }

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

test("does not add persistence or unsafe HTML APIs to the UI module", async () => {
  const source = await readFile(new URL("../../main/resources/static/ask-ui.js", import.meta.url), "utf8");
  assert.doesNotMatch(source, /localStorage|sessionStorage/);
  assert.doesNotMatch(source, /innerHTML/);
});
