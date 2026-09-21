import assert from "node:assert/strict";
import test from "node:test";

import {
  createInboxController,
  DELETABLE_STATUSES,
  extractActionLabel,
  formatFileSize,
  inboxErrorMessage,
  isDeletable,
  LIFECYCLE_FILTER_STATUSES,
  PARSE_STATUSES,
  parseStatusLabel,
  renderInboxList,
  renderBatchResult,
  statusLabel,
  usabilityDetail,
  usabilityLabel
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
    parseStatusFilter: new FakeElement("select"),
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
    usability: { status: "NOT_PROCESSED", searchReady: false, nextAction: "RETRY_PROCESSING" },
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

test("usability labels keep search readiness distinct from extraction state", () => {
  assert.equal(usabilityLabel({ status: "PROCESSING" }), "系統正在處理");
  assert.equal(usabilityLabel({ status: "READY_TO_USE" }), "可以開始使用");
  assert.equal(usabilityLabel({ status: "INDEX_PENDING" }), "搜尋索引尚未就緒");
  assert.equal(usabilityLabel({ status: "INDEX_STALE" }), "搜尋索引需要更新");
  assert.match(usabilityDetail({ status: "INDEX_PENDING" }), /目前不會把它當成可搜尋內容/u);
});

test("lifecycle and extraction states stay on separate typed projections (#451)", () => {
  assert.deepEqual(LIFECYCLE_FILTER_STATUSES, ["PENDING", "DUPLICATE"]);
  assert.deepEqual(PARSE_STATUSES, ["PROCESSED", "FAILED", "UNSUPPORTED", "NEED_OCR"]);
  assert.equal(parseStatusLabel(null), "尚未抽取");
  assert.equal(parseStatusLabel(undefined), "尚未抽取");
  assert.equal(parseStatusLabel("PROCESSED"), "已抽取");
  assert.equal(parseStatusLabel("FAILED"), "抽取失敗");
  assert.equal(parseStatusLabel("UNSUPPORTED"), "不支援抽取");
  assert.equal(parseStatusLabel("NEED_OCR"), "需要 OCR");
  assert.equal(parseStatusLabel("SOMETHING_NEW"), "SOMETHING_NEW");
  assert.equal(extractActionLabel(null), "執行抽取");
  assert.equal(extractActionLabel("NEED_OCR"), "執行抽取");
  assert.equal(extractActionLabel("PROCESSED"), "重新抽取");
});

test("list render follows backend usability instead of deriving readiness from parseStatus", () => {
  const elements = uiElements();
  renderInboxList(elements, [
    row({ documentId: 1, status: "PENDING", parseStatus: null }),
    row({ documentId: 2, status: "ARCHIVED", parseStatus: "PROCESSED",
      usability: { status: "READY_TO_USE", searchReady: true, nextAction: "START_USING" } }),
    row({ documentId: 3, status: "DUPLICATE", parseStatus: null, errorCode: null,
      usability: { status: "DUPLICATE", searchReady: false, nextAction: "USE_EXISTING_DOCUMENT" } })
  ], { number: 0, size: 20, totalElements: 3, totalPages: 1 },
  { createElement: () => new FakeElement() }, {
    onExtract: () => {}, onPreview: () => {}, onRemove: () => {}
  });

  assert.equal(elements.empty.hidden, true);
  const text = flatText(elements.list);
  assert.match(text, /report\.pdf/u);
  assert.match(text, /尚未處理/u);
  assert.match(text, /可以開始使用/u);
  assert.match(text, /內容已存在/u);
  assert.match(text, /2\.0 KB/u);
  const items = elements.list.children;
  const actionsOf = item => item.children.find(child => child.className === "inbox-actions");
  const actionText = item => flatText(actionsOf(item));
  assert.match(actionText(items[0]), /重新處理/u);
  assert.match(actionText(items[0]), /從收件匣移除/u);
  assert.match(actionText(items[1]), /開始提問/u);
  assert.match(actionText(items[1]), /檢視處理內容/u);
  assert.doesNotMatch(actionText(items[1]), /從收件匣移除/u);
  assert.match(actionText(items[2]), /從收件匣移除/u);
});

test("ready, failed and unsupported documents render task-oriented next actions", () => {
  const elements = uiElements();
  renderInboxList(elements, [
    row({ documentId: 1, parseStatus: "PROCESSED",
      usability: { status: "READY_TO_USE", searchReady: true, nextAction: "START_USING" } }),
    row({ documentId: 2, parseStatus: "FAILED",
      usability: { status: "FAILED", searchReady: false, nextAction: "RETRY_PROCESSING" } }),
    row({ documentId: 3, parseStatus: "UNSUPPORTED",
      usability: { status: "UNSUPPORTED", searchReady: false, nextAction: "NONE" } })
  ], { number: 0, size: 20, totalElements: 3, totalPages: 1 },
  { createElement: () => new FakeElement() }, {
    onExtract: () => {}, onPreview: () => {}, onRemove: () => {}
  });

  const text = flatText(elements.list);
  assert.match(text, /可以開始使用/u);
  assert.match(text, /處理失敗/u);
  assert.match(text, /不支援此格式/u);
  const items = elements.list.children;
  const actionsOf = item => item.children.find(child => child.className === "inbox-actions");
  assert.match(flatText(actionsOf(items[0])), /開始提問/u);
  assert.match(flatText(actionsOf(items[0])), /檢視處理內容/u);
  assert.match(flatText(actionsOf(items[1])), /重新處理/u);
  assert.doesNotMatch(flatText(actionsOf(items[2])), /重新處理/u);
  for (const item of items) {
    assert.match(flatText(actionsOf(item)), /從收件匣移除/u);
  }
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

  elements.statusFilter.value = "PENDING";
  elements.parseStatusFilter.value = "PROCESSED";
  await controller.applyFilter({ preventDefault() {} });
  assert.equal(calls.at(-1).url,
    "/api/v1/inbox?page=0&size=20&status=PENDING&parseStatus=PROCESSED");

  await controller.nextPage();
  assert.equal(calls.at(-1).url,
    "/api/v1/inbox?page=1&size=20&status=PENDING&parseStatus=PROCESSED");
});

test("single upload opts into backend auto-processing without Browser calling extract", async () => {
  const elements = uiElements();
  const calls = [];
  let duplicate = false;
  const fetchImpl = async (url, options) => {
    calls.push({ url: String(url), method: options?.method });
    if (String(url) === "/api/v1/inbox/files?autoProcess=true") {
      return jsonResponse(true, 201, {
        data: { documentId: 5, fileName: "a.pdf", status: "PENDING", duplicate }
      });
    }
    return jsonResponse(true, 200, { data: [], page: { number: 0, totalPages: 0 } });
  };
  const controller = createInboxController(elements, fetchImpl, fakeDocument());
  elements.fileInput.files = [{ name: "a.pdf" }];

  await elements.uploadForm.handlers.get("submit")({ preventDefault() {} });
  assert.match(elements.hint.textContent, /已上傳，系統會自動處理/u);
  assert.ok(calls.every(call => !String(call.url).includes("/extract")),
    "Browser must request backend orchestration instead of calling /extract itself");

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

test("processing rows schedule an authoritative refresh until backend reports ready", async () => {
  const elements = uiElements();
  let listCalls = 0;
  let scheduled = null;
  const timers = {
    set(fn) { scheduled = fn; return 1; },
    clear() { scheduled = null; }
  };
  const fetchImpl = async () => {
    listCalls += 1;
    const usability = listCalls === 1
      ? { status: "PROCESSING", searchReady: false, nextAction: "WAIT" }
      : { status: "READY_TO_USE", searchReady: true, nextAction: "START_USING" };
    return jsonResponse(true, 200, {
      data: [row({ parseStatus: listCalls === 1 ? null : "PROCESSED", usability })],
      page: { number: 0, size: 20, totalElements: 1, totalPages: 1 }
    });
  };
  const controller = createInboxController(elements, fetchImpl, fakeDocument(), timers);
  await controller.refresh();
  assert.match(flatText(elements.list), /系統正在處理/u);
  assert.equal(typeof scheduled, "function");

  const callback = scheduled;
  scheduled = null; // real one-shot timers are no longer pending when their callback fires
  await callback();

  assert.equal(listCalls, 2);
  assert.match(flatText(elements.list), /可以開始使用/u);
  assert.equal(scheduled, null);
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
  assert.match(elements.hint.textContent, /已從收件匣移除（標記為已刪除）/u);

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
  elements.statusFilter.value = "PENDING";
  elements.parseStatusFilter.value = "FAILED";

  assert.equal(documentRef.listeners.has("workspace-changed"), true);
  await documentRef.listeners.get("workspace-changed")();

  assert.equal(elements.statusFilter.value, "",
    "filter from the previous workspace must be cleared");
  assert.equal(elements.parseStatusFilter.value, "",
    "extraction filter from the previous workspace must be cleared");
  assert.match(calls.at(-1), /page=0&size=20$/u);
  assert.match(flatText(elements.list), /other-ws\.txt/u);
});

test("inbox typed errors stay operator-safe", () => {
  assert.equal(inboxErrorMessage({ code: "NO_ACTIVE_WORKSPACE" }).title, "尚未開啟知識庫");
  assert.equal(inboxErrorMessage({ code: "DOCUMENT_ALREADY_PROCESSED" }).title, "文件已處理");
  assert.equal(inboxErrorMessage(undefined).title, "收件匣操作失敗");
  assert.equal(formatFileSize(2048), "2.0 KB");
});

test("extract success re-fetches authoritative row without manual filter (#517)", async () => {
  const elements = uiElements();
  const calls = [];
  let listCalls = 0;
  const fetchImpl = async (url, options) => {
    calls.push({ url: String(url), method: options?.method });
    if (String(url).endsWith("/extract")) {
      return jsonResponse(true, 200, {
        data: { documentId: 1, parseStatus: "PROCESSED", chunkCount: 2,
          errorCode: null, errorMessage: null }
      });
    }
    listCalls += 1;
    if (listCalls === 1) {
      return jsonResponse(true, 200, {
        data: [row({ documentId: 1, parseStatus: null,
          usability: { status: "NOT_PROCESSED", searchReady: false, nextAction: "RETRY_PROCESSING" } })],
        page: { number: 0, size: 20, totalElements: 1, totalPages: 1 }
      });
    }
    return jsonResponse(true, 200, {
      data: [row({ documentId: 1, parseStatus: "PROCESSED",
        usability: { status: "READY_TO_USE", searchReady: true, nextAction: "START_USING" } })],
      page: { number: 0, size: 20, totalElements: 1, totalPages: 1 }
    });
  };
  const controller = createInboxController(elements, fetchImpl, fakeDocument());

  elements.statusFilter.value = "PENDING";
  await controller.applyFilter({ preventDefault() {} });
  assert.match(flatText(elements.list), /尚未處理/u);
  assert.match(flatText(elements.list), /重新處理/u);

  await controller.extract(1);

  const inboxGets = calls.filter(call => call.url.startsWith("/api/v1/inbox?"));
  assert.equal(inboxGets.length, 2);
  assert.match(inboxGets.at(-1).url, /status=PENDING/u);
  assert.equal(elements.statusFilter.value, "PENDING",
    "mutation refresh must preserve the user filter");
  assert.match(flatText(elements.list), /可以開始使用/u);
  assert.match(flatText(elements.list), /開始提問/u);
  assert.match(elements.hint.textContent, /抽取完成，共 2 個片段/u);
});

test("single upload success appends authoritative row without clearing filters (#517)", async () => {
  const elements = uiElements();
  const calls = [];
  let listCalls = 0;
  const fetchImpl = async (url, options) => {
    calls.push({ url: String(url), method: options?.method });
    if (String(url) === "/api/v1/inbox/files?autoProcess=true") {
      return jsonResponse(true, 201, {
        data: { documentId: 7, fileName: "new.pdf", status: "PENDING", duplicate: false }
      });
    }
    listCalls += 1;
    if (listCalls === 1) {
      return jsonResponse(true, 200, {
        data: [], page: { number: 0, size: 20, totalElements: 0, totalPages: 0 }
      });
    }
    return jsonResponse(true, 200, {
      data: [row({ documentId: 7, fileName: "new.pdf" })],
      page: { number: 0, size: 20, totalElements: 1, totalPages: 1 }
    });
  };
  const controller = createInboxController(elements, fetchImpl, fakeDocument());

  elements.statusFilter.value = "PENDING";
  await controller.applyFilter({ preventDefault() {} });
  assert.equal(elements.list.children.length, 0);

  elements.fileInput.files = [{ name: "new.pdf" }];
  await elements.uploadForm.handlers.get("submit")({ preventDefault() {} });

  const inboxGets = calls.filter(call => call.url.startsWith("/api/v1/inbox?"));
  assert.equal(inboxGets.length, 2);
  assert.match(inboxGets.at(-1).url, /status=PENDING/u);
  assert.equal(elements.statusFilter.value, "PENDING");
  assert.match(flatText(elements.list), /new\.pdf/u);
  assert.match(elements.hint.textContent, /已上傳，系統會自動處理/u);
});

test("batch upload success refreshes from backend authority (#517)", async () => {
  const elements = uiElements();
  const calls = [];
  let listCalls = 0;
  const fetchImpl = async (url, options) => {
    calls.push({ url: String(url), method: options?.method });
    if (String(url) === "/api/v1/inbox/files/batch?autoProcess=true") {
      return jsonResponse(true, 201, {
        data: { total: 1, accepted: 1, duplicate: 0, failed: 0,
          documents: [{ documentId: 8 }], failures: [] }
      });
    }
    listCalls += 1;
    if (listCalls === 1) {
      return jsonResponse(true, 200, {
        data: [], page: { number: 0, size: 20, totalElements: 0, totalPages: 0 }
      });
    }
    return jsonResponse(true, 200, {
      data: [row({ documentId: 8, fileName: "batch.pdf" })],
      page: { number: 0, size: 20, totalElements: 1, totalPages: 1 }
    });
  };
  const controller = createInboxController(elements, fetchImpl, fakeDocument());
  await controller.refresh();
  assert.equal(elements.list.children.length, 0);

  elements.batchInput.files = [{ name: "batch.pdf" }];
  await elements.batchForm.handlers.get("submit")({ preventDefault() {} });

  const inboxGets = calls.filter(call => call.url.startsWith("/api/v1/inbox?"));
  assert.equal(inboxGets.length, 2);
  assert.match(flatText(elements.batchResult), /共 1 檔/u);
  assert.match(flatText(elements.list), /batch\.pdf/u);
});

test("rescan success refreshes from backend authority (#517)", async () => {
  const elements = uiElements();
  const calls = [];
  let listCalls = 0;
  const fetchImpl = async (url, options) => {
    calls.push({ url: String(url), method: options?.method });
    if (String(url) === "/api/v1/inbox/rescan") {
      return jsonResponse(true, 200, {
        data: { newDocuments: 1, duplicates: 0, existing: 0, removed: 0 }
      });
    }
    listCalls += 1;
    if (listCalls === 1) {
      return jsonResponse(true, 200, {
        data: [], page: { number: 0, size: 20, totalElements: 0, totalPages: 0 }
      });
    }
    return jsonResponse(true, 200, {
      data: [row({ documentId: 9, fileName: "rescanned.pdf" })],
      page: { number: 0, size: 20, totalElements: 1, totalPages: 1 }
    });
  };
  const controller = createInboxController(elements, fetchImpl, fakeDocument());
  await controller.refresh();

  await controller.rescan();

  const inboxGets = calls.filter(call => call.url.startsWith("/api/v1/inbox?"));
  assert.equal(inboxGets.length, 2);
  assert.match(elements.rescanResult.textContent, /新文件 1/u);
  assert.match(flatText(elements.list), /rescanned\.pdf/u);
});

test("remove success refreshes from backend authority (#517)", async () => {
  const elements = uiElements();
  const calls = [];
  let listCalls = 0;
  const fetchImpl = async (url, options) => {
    calls.push({ url: String(url), method: options?.method });
    if (String(url) === "/api/v1/inbox/files/1" && options?.method === "DELETE") {
      return { ok: true, status: 204, json: async () => null };
    }
    listCalls += 1;
    if (listCalls === 1) {
      return jsonResponse(true, 200, {
        data: [row({ documentId: 1, fileName: "gone.pdf" })],
        page: { number: 0, size: 20, totalElements: 1, totalPages: 1 }
      });
    }
    return jsonResponse(true, 200, {
      data: [], page: { number: 0, size: 20, totalElements: 0, totalPages: 0 }
    });
  };
  const controller = createInboxController(elements, fetchImpl, fakeDocument());
  await controller.refresh();
  assert.match(flatText(elements.list), /gone\.pdf/u);

  await controller.remove(1);

  const inboxGets = calls.filter(call => call.url.startsWith("/api/v1/inbox?"));
  assert.equal(inboxGets.length, 2);
  assert.equal(elements.list.children.length, 0);
  assert.match(elements.hint.textContent, /已從收件匣移除/u);
});

test("concurrent refresh and mutation stay guarded without dropping follow-up fetch (#517)", async () => {
  const elements = uiElements();
  const calls = [];
  let releaseUpload;
  const uploadGate = new Promise(resolve => { releaseUpload = resolve; });
  const fetchImpl = async (url, options) => {
    calls.push({ url: String(url), method: options?.method });
    if (String(url) === "/api/v1/inbox/files?autoProcess=true") {
      await uploadGate;
      return jsonResponse(true, 201, {
        data: { documentId: 11, fileName: "slow.pdf", status: "PENDING", duplicate: false }
      });
    }
    return jsonResponse(true, 200, {
      data: [row({ documentId: 11, fileName: "slow.pdf" })],
      page: { number: 0, size: 20, totalElements: 1, totalPages: 1 }
    });
  };
  const controller = createInboxController(elements, fetchImpl, fakeDocument());
  elements.fileInput.files = [{ name: "slow.pdf" }];

  const pending = controller.uploadSingle({ preventDefault() {} });
  await new Promise(resolve => setTimeout(resolve, 0));
  const callsDuringMutation = calls.length;

  await controller.extract(1);
  await controller.refresh();
  assert.equal(calls.length, callsDuringMutation,
    "concurrent mutation/refresh during an upload must not issue duplicate fetches");

  releaseUpload();
  await pending;

  const inboxGets = calls.filter(call => call.url.startsWith("/api/v1/inbox?"));
  assert.equal(inboxGets.length, 1,
    "successful mutation must still issue its follow-up authoritative GET");
  assert.match(flatText(elements.list), /slow\.pdf/u);

  await controller.refresh();
  assert.equal(calls.filter(call => call.url.startsWith("/api/v1/inbox?")).length, 2,
    "lock must be released after the mutation so later refresh works");
});

test("mutation failure keeps typed error without optimistic rows (#517)", async () => {
  const elements = uiElements();
  const calls = [];
  const fetchImpl = async (url, options) => {
    calls.push({ url: String(url), method: options?.method });
    if (String(url).endsWith("/extract")) {
      return jsonResponse(false, 422, {
        error: { code: "EXTRACTION_PARSE_FAILED", message: "parse failed" }
      });
    }
    if (String(url) === "/api/v1/inbox/files?autoProcess=true") {
      return jsonResponse(false, 400, {
        error: { code: "INVALID_REQUEST", message: "bad" }
      });
    }
    return jsonResponse(true, 200, {
      data: [row({ documentId: 1, parseStatus: null })],
      page: { number: 0, size: 20, totalElements: 1, totalPages: 1 }
    });
  };
  const controller = createInboxController(elements, fetchImpl, fakeDocument());
  await controller.refresh();
  assert.match(flatText(elements.list), /尚未處理/u);

  await controller.extract(1);
  assert.match(elements.hint.textContent, /抽取失敗：文件解析失敗/u);
  assert.doesNotMatch(flatText(elements.list), /可以開始使用/u,
    "failure must not render an optimistic extracted row");

  elements.fileInput.files = [{ name: "bad.pdf" }];
  await elements.uploadForm.handlers.get("submit")({ preventDefault() {} });
  assert.match(elements.hint.textContent, /要求不正確|收件匣操作失敗/u);
  assert.doesNotMatch(flatText(elements.list), /bad\.pdf/u,
    "failed upload must not invent a local row");
});
