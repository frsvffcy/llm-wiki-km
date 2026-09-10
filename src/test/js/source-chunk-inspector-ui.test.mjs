import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import {
  createSourceChunkInspectorController,
  errorMessage,
  inspectSourceChunk,
  renderLocator,
  showLocatorError
} from "../../main/resources/static/source-chunk-inspector-ui.js";

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
    result: new FakeElement(), loading: new FakeElement(), error: new FakeElement(),
    errorTitle: new FakeElement(), errorMessage: new FakeElement(),
    notFound: new FakeElement(), notFoundTitle: new FakeElement(),
    notFoundMessage: new FakeElement(), metadata: new FakeElement(), preview: new FakeElement()
  };
}

function locatorPayload() {
  return { data: {
    sourceChunkId: 42,
    documentId: 900,
    documentName: "design.pdf",
    chunkNo: 3,
    pageNo: 17,
    section: "Graph lifecycle",
    headingPath: "Projection > Generation ownership",
    currentness: "CURRENT",
    notCurrentReason: null,
    preview: "authoritative text with <script>alert(1)</script> and <img src=x onerror=alert(1)>",
    previewTruncated: false
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

test("renders current locator metadata and bounded preview as text", () => {
  const elements = uiElements();
  renderLocator(elements, locatorPayload(), documentRef);

  assert.equal(elements.result.hidden, false);
  assert.equal(elements.error.hidden, true);
  const metadata = flatText(elements.metadata);
  assert.match(metadata, /文件：design\.pdf/);
  assert.match(metadata, /chunk：3/);
  assert.match(metadata, /頁碼：17/);
  assert.match(metadata, /section：Graph lifecycle/);
  assert.match(metadata, /heading：Projection > Generation ownership/);
  assert.match(metadata, /與 canonical 狀態一致/);
  const preview = flatText(elements.preview);
  assert.match(preview, /<script>alert\(1\)<\/script>/);
  assert.doesNotMatch(preview, /innerHTML/);
  assert.equal(elements.notFound.hidden, true);
});

test("truncated previews carry a safe bounded-notice", () => {
  const elements = uiElements();
  const payload = locatorPayload();
  payload.data.preview = "字".repeat(10);
  payload.data.previewTruncated = true;
  renderLocator(elements, payload, documentRef);

  assert.match(flatText(elements.preview), /預覽已截斷/);
});

test("not-current locators show typed reason and never show content", () => {
  const elements = uiElements();
  const payload = locatorPayload();
  payload.data.currentness = "NOT_CURRENT";
  payload.data.notCurrentReason = "INELIGIBLE";
  payload.data.preview = null;
  payload.data.documentName = null;
  renderLocator(elements, payload, documentRef);

  assert.equal(elements.result.hidden, false);
  const metadata = flatText(elements.metadata);
  assert.match(metadata, /已與 canonical 狀態不一致/);
  assert.match(metadata, /來源目前不可用/);
  assert.equal(elements.preview.children.length, 0);
});

test("not-found locator keeps the citation framing instead of a raw error", () => {
  const elements = uiElements();
  showLocatorError(elements, { code: "SOURCE_CHUNK_NOT_FOUND", message: "x" });

  assert.equal(elements.notFound.hidden, false);
  assert.equal(elements.error.hidden, true);
  assert.match(elements.notFoundMessage.textContent, /citation 本身仍然有效/);
  assert.doesNotMatch(elements.notFoundMessage.textContent, /exception|stack/i);
});

test("unknown error codes map to the generic safe message", () => {
  const mapped = errorMessage({ code: "SOMETHING_ELSE" });
  assert.equal(mapped.title, "無法取得來源位置");
});

test("inspect fetches the read-only locator endpoint and re-renders on repeated opens", async () => {
  const elements = uiElements();
  let calls = 0;
  const fetchImpl = async (url, init) => {
    calls += 1;
    assert.equal(url, "/api/v1/source-chunks/42/locator");
    assert.equal(init.method, "GET");
    return { ok: true, json: async () => locatorPayload() };
  };

  await inspectSourceChunk(elements, 42, fetchImpl, documentRef);
  await inspectSourceChunk(elements, "42", fetchImpl, documentRef);

  assert.equal(calls, 2);
  assert.equal(elements.result.hidden, false);
  assert.equal(elements.loading.hidden, true);
});

test("a failed inspection clears the previous locator before showing the error", async () => {
  const elements = uiElements();
  renderLocator(elements, locatorPayload(), documentRef);
  assert.equal(elements.result.hidden, false);

  await inspectSourceChunk(elements, 42, async () => ({
    ok: false,
    json: async () => ({ error: { code: "RETRIEVAL_UNAVAILABLE", message: "x" } })
  }), documentRef);

  assert.equal(elements.error.hidden, false);
  assert.equal(elements.errorTitle.textContent, "來源服務暫時無法使用");
  assert.equal(elements.result.hidden, true);
  assert.equal(elements.metadata.children.length, 0);
  assert.equal(elements.preview.children.length, 0);
  assert.equal(elements.loading.hidden, true);
});

test("a network failure also clears the previous locator", async () => {
  const elements = uiElements();
  renderLocator(elements, locatorPayload(), documentRef);

  await inspectSourceChunk(elements, 42, async () => {
    throw new Error("network down");
  }, documentRef);

  assert.equal(elements.error.hidden, false);
  assert.equal(elements.metadata.children.length, 0);
  assert.equal(elements.preview.children.length, 0);
});

test("controller binds citation clicks to the inspector without touching the ask flow", () => {
  const elements = uiElements();
  const citations = new FakeElement();
  const documentWithCitations = {
    createElement: () => new FakeElement(),
    getElementById: id => (id === "citations" ? citations : null)
  };
  const controller = createSourceChunkInspectorController(elements, async () => locatorPayload(),
    documentWithCitations);

  assert.notEqual(controller, null);
  assert.equal(citations.handlers.get("click") instanceof Function, true);
});

test("citation click dispatch uses real-DOM getAttribute and triggers the inspector", async () => {
  const elements = uiElements();
  const citations = new FakeElement();
  const documentWithCitations = {
    createElement: () => new FakeElement(),
    getElementById: id => (id === "citations" ? citations : null)
  };
  let fetched = 0;
  createSourceChunkInspectorController(elements, async (url) => {
    fetched += 1;
    assert.equal(url, "/api/v1/source-chunks/42/locator");
    return { ok: true, json: async () => locatorPayload() };
  }, documentWithCitations);

  const button = new FakeElement();
  button.setAttribute("data-chunk-id", "42");
  const handler = citations.handlers.get("click");
  // The DOM fires handlers without awaiting: pump the microtask queue like a real browser.
  handler({ target: button, preventDefault() {} });
  for (let i = 0; i < 10; i++) {
    await new Promise(resolve => setImmediate(resolve));
  }

  assert.equal(fetched, 1);
  assert.equal(elements.result.hidden, false);
  assert.equal(elements.loading.hidden, true);
});

test("clicks on non-element targets are ignored without throwing", async () => {
  const elements = uiElements();
  const citations = new FakeElement();
  const documentWithCitations = {
    createElement: () => new FakeElement(),
    getElementById: id => (id === "citations" ? citations : null)
  };
  let fetched = 0;
  createSourceChunkInspectorController(elements, async () => {
    fetched += 1;
    return { ok: true, json: async () => locatorPayload() };
  }, documentWithCitations);

  const handler = citations.handlers.get("click");
  // Text nodes and unrelated targets have no getAttribute: the handler must not throw.
  await handler({ target: { textContent: "plain text node" }, preventDefault() {} });
  await handler({ target: null, preventDefault() {} });

  assert.equal(fetched, 0);
});

test("inspector js never mutates, never renders unsafely, never leaks internals", async () => {
  const source = await readFile(
    "src/main/resources/static/source-chunk-inspector-ui.js", "utf8");
  assert.doesNotMatch(source, /method:\s*"POST"/);
  assert.doesNotMatch(source, /\brebuild\b|\brepair\b|index\/rebuild|extract|re-chunk/);
  assert.doesNotMatch(source, /innerHTML|localStorage|document\.cookie|eval\(/);
  assert.doesNotMatch(source, /file:\/\/|archive\/|absolute/i);
  assert.doesNotMatch(source, /fingerprint|snapshot|token|exception/i);
});

test("index html wires the inspector panel through CSP-safe modules only", async () => {
  const html = await readFile("src/main/resources/static/index.html", "utf8");
  assert.match(html, /source-chunk-inspector-ui\.js/);
  assert.match(html, /id="source-chunk-inspector-panel"/);
  assert.doesNotMatch(html, /on(load|click|error)=/);
});
