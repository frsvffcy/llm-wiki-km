import assert from "node:assert/strict";
import test from "node:test";

import {
  createInboxController,
  DELETABLE_STATUSES,
  formatFileSize,
  inboxErrorMessage,
  isDeletable,
  renderInboxList,
  renderBatchResult,
  statusLabel
} from "../../main/resources/static/inbox-ui.js";

class FakeElement {
  constructor(tagName = "div") {
    this.tagName = tagName;
    this.children = [];
    this.hidden = false;
    this.disabled = false;
    this.value = "";
    this.textContent = "";
    this.className = "";
    this.files = [];
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

function uiElements() {
  return {
    filterForm: new FakeElement("form"),
    statusFilter: new FakeElement("select"),
    list: new FakeElement("ul"),
    empty: new FakeElement("p"),
    hint: new FakeElement("p"),
    pageInfo: new FakeElement("span"),
    prevPage: new FakeElement("button"),
    nextPage: new FakeElement("button"),
    uploadForm: new FakeElement("form"),
    fileInput: new FakeElement("input"),
    batchForm: new FakeElement("form"),
    batchInput: new FakeElement("input"),
    rescan: new FakeElement("button"),
    batchResult: new FakeElement("div"),
    rescanResult: new FakeElement("p"),
    previewPanel: new FakeElement("section"),
    previewMeta: new FakeElement("p"),
    previewChunks: new FakeElement("ul"),
    previewPageInfo: new FakeElement("span"),
    previewPrev: new FakeElement("button"),
    previewNext: new FakeElement("button"),
    previewClose: new FakeElement("button")
  };
}

function fakeDocument() {
  const listeners = new Map();
  return {
    createElement: () => new FakeElement(),
    listeners,
    addEventListener(name, handler) {
      listeners.set(name, handler);
    }
  };
}

function flatText(element) {
  return [element.textContent,
    ...element.children.map(child => flatText(child))].join(" ");
}

function jsonResponse(ok, status, payload) {
  return {
    ok,
    status,
    json: async () => payload
  };
}

function row(overrides = {}) {
  return {
    documentId: 1,
    fileName: "report.pdf",
    originalFileName: "report.pdf",
    extension: "pdf",
    mimeType: "application/pdf",
    fileSize: 2048,
    status: "PENDING",
    parseStatus: null,
    errorCode: null,
    errorMessage: null,
    createdAt: "2026-09-12T00:00:00Z",
    ...overrides
  };
}

test("typed status labels cover DocumentStatus without guessing the state machine", () => {
  assert.equal(statusLabel("PENDING"), "待處理");
  assert.equal(statusLabel("NEED_OCR"), "需要 OCR");
  assert.equal(statusLabel("UNSUPPORTED"), "不支援的格式");
  assert.equal(statusLabel("DUPLICATE"), "重複檔案");
  assert.equal(statusLabel("FAILED"), "處理失敗");
  assert.equal(statusLabel("SOMETHING_NEW"), "SOMETHING_NEW",
    "unknown states render raw instead of a guessed label");
  assert.deepEqual(DELETABLE_STATUSES,
    ["PENDING", "FAILED", "DUPLICATE", "UNSUPPORTED", "NEED_OCR"]);
  assert.equal(isDeletable("PROCESSED"), false);
  assert.equal(isDeletable("FAILED"), true);
});

test("list render exposes row actions only for the allowed delete contract", () => {
  const elements = uiElements();
  renderInboxList(elements, [
    row({ documentId: 1, status: "PENDING" }),
    row({ documentId: 2, status: "PROCESSED" }),
    row({ documentId: 3, status: "NEED_OCR", errorCode: "OCR_REQUIRED",
      errorMessage: "scanned copy" })
  ], { number: 0, size: 20, totalElements: 3, totalPages: 1 },
  { createElement: () => new FakeElement() }, {
    onExtract: () => {}, onPreview: () => {}, onRemove: () => {}
  });

  assert.equal(elements.empty.hidden, true);
  const text = flatText(elements.list);
  assert.match(text, /report\.pdf/u);
  assert.match(text, /需要 OCR/u);
  assert.match(text, /OCR_REQUIRED：scanned copy/u);
  assert.match(text, /2\.0 KB/u);
  const items = elements.list.children;
  const actionsOf = item => item.children.find(child => child.className === "inbox-actions");
  const actionText = item => flatText(actionsOf(item));
  for (const item of items) {
    assert.match(actionText(item), /執行抽取/u);
    assert.match(actionText(item), /查看抽取內容/u);
  }
  assert.match(actionText(items[0]), /從收件匣移除/u);
  assert.doesNotMatch(actionText(items[1]), /從收件匣移除/u);
  assert.match(actionText(items[2]), /從收件匣移除/u);
});

test("empty list renders the empty state and pager meta stays honest", () => {
  const elements = uiElements();
  renderInboxList(elements, [], { number: 0, size: 20, totalElements: 0, totalPages: 0 },
    { createElement: () => new FakeElement() });
  assert.equal(elements.empty.hidden, false);
  assert.equal(elements.prevPage.disabled, true);
  assert.equal(elements.nextPage.disabled, true);
});

test("batch upload partial failure stays visible instead of reporting all success", () => {
  const elements = uiElements();
  renderBatchResult(elements, {
    total: 3, accepted: 1, duplicate: 1, failed: 1,
    documents: [],
    failures: [{ fileName: "broken.docx", error: "INVALID_REQUEST" }]
  }, { createElement: () => new FakeElement() });

  assert.equal(elements.batchResult.hidden, false);
  const text = flatText(elements.batchResult);
  assert.match(text, /共 3 檔，接受 1，重複 1，失敗 1/u);
  assert.match(text, /broken\.docx：INVALID_REQUEST/u);
});

test("refresh filters and pages through the existing list contract", async () => {
  const elements = uiElements();
  const calls = [];
  const fetchImpl = async (url, options) => {
    calls.push({ url: String(url), method: options?.method });
    return jsonResponse(true, 200, {
      data: [row()],
      page: { number: 0, size: 20, totalElements: 1, totalPages: 1 }
    });
  };
  const controller = createInboxController(elements, fetchImpl, fakeDocument());
  await controller.refresh();
  assert.equal(calls.at(-1).url, "/api/v1/inbox?page=0&size=20");

  elements.statusFilter.value = "NEED_OCR";
  await controller.applyFilter({ preventDefault() {} });
  assert.equal(calls.at(-1).url, "/api/v1/inbox?page=0&size=20&status=NEED_OCR");

  await controller.nextPage();
  assert.equal(calls.at(-1).url, "/api/v1/inbox?page=1&size=20&status=NEED_OCR");
});

test("single upload reports duplicate versus accepted without assuming extraction", async () => {
  const elements = uiElements();
  const calls = [];
  let duplicate = false;
  const fetchImpl = async (url, options) => {
    calls.push({ url: String(url), method: options?.method });
    if (url === "/api/v1/inbox/files") {
      return jsonResponse(true, 201, {
        data: { documentId: 5, fileName: "a.pdf", status: "PENDING", duplicate }
      });
    }
    return jsonResponse(true, 200, { data: [], page: { number: 0, totalPages: 0 } });
  };
  const controller = createInboxController(elements, fetchImpl, fakeDocument());
  elements.fileInput.files = [{ name: "a.pdf" }];

  await elements.uploadForm.handlers.get("submit")({ preventDefault() {} });
  assert.match(elements.hint.textContent, /已上傳，狀態：待處理/u);
  assert.ok(calls.every(call => !String(call.url).includes("/extract")),
    "upload must not trigger extraction by itself (challenge 5)");

  duplicate = true;
  elements.fileInput.files = [{ name: "a.pdf" }];
  await elements.uploadForm.handlers.get("submit")({ preventDefault() {} });
  assert.match(elements.hint.textContent, /重複檔案：內容與既有文件相同/u);
});

test("extraction outcomes are typed: success, NEED_OCR, UNSUPPORTED and failures differ", async () => {
  const elements = uiElements();
  const outcomes = [
    { status: 200, payload: { data: { documentId: 1, parseStatus: "PROCESSED",
      chunkCount: 3, errorCode: null, errorMessage: null } }, expected: /抽取完成，共 3 個片段/u },
    { status: 200, payload: { data: { documentId: 1, parseStatus: "NEED_OCR",
      chunkCount: 0, errorCode: "OCR_REQUIRED", errorMessage: "scanned" } },
    expected: /此文件需要 OCR 後才能抽取/u },
    { status: 200, payload: { data: { documentId: 1, parseStatus: "UNSUPPORTED",
      chunkCount: 0, errorCode: "EXTRACTION_UNSUPPORTED_TYPE", errorMessage: "binary" } },
    expected: /不支援的檔案格式/u },
    { status: 422, payload: { error: { code: "EXTRACTION_PARSE_FAILED",
      message: "parse failed" } }, expected: /抽取失敗：文件解析失敗/u },
    { status: 422, payload: { error: { code: "EXTRACTION_RESOURCE_LIMIT",
      message: "limit" } }, expected: /超過抽取資源上限/u }
  ];
  let current = outcomes[0];
  const fetchImpl = async (url, options) => {
    if (String(url).endsWith("/extract")) {
      return jsonResponse(current.status < 400, current.status, current.payload);
    }
    return jsonResponse(true, 200, { data: [], page: { number: 0, totalPages: 0 } });
  };
  const controller = createInboxController(elements, fetchImpl, fakeDocument());
  for (const outcome of outcomes) {
    current = outcome;
    await controller.extract(1);
    assert.match(elements.hint.textContent, outcome.expected,
      `outcome for ${JSON.stringify(outcome.payload)}`);
  }
});

test("preview renders bounded chunks and reports missing extraction as typed state", async () => {
  const documentRef = fakeDocument();
  const elements = uiElements();
  let mode = "present";
  const fetchImpl = async url => {
    if (String(url).includes("/extracted-content")) {
      if (mode === "missing") {
        return jsonResponse(false, 422, {
          error: { code: "EXTRACTED_CONTENT_NOT_FOUND", message: "not extracted yet" }
        });
      }
      return jsonResponse(true, 200, {
        data: {
          documentId: 1, parseStatus: "PROCESSED", chunkCount: 2,
          chunks: [
            { chunkIndex: 0, content: "first chunk" },
            { chunkIndex: 1, content: "second chunk" }
          ],
          page: { number: 0, size: 20, totalElements: 2, totalPages: 1 }
        }
      });
    }
    return jsonResponse(true, 200, { data: [], page: { number: 0, totalPages: 0 } });
  };
  const controller = createInboxController(elements, fetchImpl, documentRef);

  await controller.openPreview(1);
  assert.equal(elements.previewPanel.hidden, false);
  assert.match(elements.previewMeta.textContent, /共 2 個片段/u);
  assert.match(flatText(elements.previewChunks), /first chunk/u);
  assert.match(flatText(elements.previewChunks), /second chunk/u);

  mode = "missing";
  await controller.openPreview(2);
  assert.match(elements.hint.textContent, /尚未有抽取內容，請先執行抽取/u);
});

test("remove uses the soft-delete contract and typed failure for processed documents", async () => {
  const elements = uiElements();
  const calls = [];
  let conflict = false;
  const fetchImpl = async (url, options) => {
    calls.push({ url: String(url), method: options?.method });
    if (conflict) {
      return jsonResponse(false, 409, {
        error: { code: "DOCUMENT_ALREADY_PROCESSED", message: "processed" }
      });
    }
    return { ok: true, status: 204, json: async () => null };
  };
  const controller = createInboxController(elements, fetchImpl, fakeDocument());

  await controller.remove(1);
  assert.deepEqual(calls[0], { url: "/api/v1/inbox/files/1", method: "DELETE" });
  assert.match(elements.hint.textContent, /已從收件匣移除（soft delete）/u);

  conflict = true;
  await controller.remove(1);
  assert.match(elements.hint.textContent, /文件已處理/u);
});

test("workspace switch resets local state and re-fetches the new workspace inbox", async () => {
  const documentRef = fakeDocument();
  const elements = uiElements();
  const calls = [];
  const fetchImpl = async url => {
    calls.push(String(url));
    return jsonResponse(true, 200, {
      data: [row({ documentId: 9, fileName: "other-ws.txt" })],
      page: { number: 0, size: 20, totalElements: 1, totalPages: 1 }
    });
  };
  const controller = createInboxController(elements, fetchImpl, documentRef);
  await controller.refresh();
  elements.statusFilter.value = "FAILED";

  assert.equal(documentRef.listeners.has("workspace-changed"), true);
  await documentRef.listeners.get("workspace-changed")();

  assert.equal(elements.statusFilter.value, "",
    "filter from the previous workspace must be cleared");
  assert.match(calls.at(-1), /page=0&size=20$/u);
  assert.match(flatText(elements.list), /other-ws\.txt/u);
});

test("inbox typed errors stay operator-safe", () => {
  assert.equal(inboxErrorMessage({ code: "NO_ACTIVE_WORKSPACE" }).title, "尚未開啟知識庫");
  assert.equal(inboxErrorMessage({ code: "DOCUMENT_ALREADY_PROCESSED" }).title, "文件已處理");
  assert.equal(inboxErrorMessage(undefined).title, "收件匣操作失敗");
  assert.equal(formatFileSize(2048), "2.0 KB");
});
