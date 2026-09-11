/**
 * Inbox Browser surface (#352): a first-mile projection of the existing `/api/v1/inbox`
 * and `/api/v1/documents` contracts — paged/filtered document list with typed statuses,
 * single/batch upload, rescan, soft delete, the existing extraction trigger, and the
 * bounded extracted-content preview. The UI only projects backend-owned typed state:
 * no derived status machine, no automatic follow-up processing after upload, no new
 * authority. When the current workspace changes, all state is reset and re-fetched.
 */

const INBOX_ENDPOINT = "/api/v1/inbox";
const DOCUMENTS_ENDPOINT_BASE = "/api/v1/documents";
const PAGE_SIZE = 20;

export const DOCUMENT_STATUSES = Object.freeze([
  "PENDING", "PROCESSING", "PROCESSED", "ARCHIVED", "DUPLICATE",
  "UNSUPPORTED", "NEED_OCR", "FAILED", "DELETED", "SUPERSEDED"
]);

const STATUS_LABELS = Object.freeze({
  PENDING: "待處理",
  PROCESSING: "處理中",
  PROCESSED: "已處理",
  ARCHIVED: "已封存",
  DUPLICATE: "重複檔案",
  UNSUPPORTED: "不支援的格式",
  NEED_OCR: "需要 OCR",
  FAILED: "處理失敗",
  DELETED: "已刪除",
  SUPERSEDED: "已由新版取代"
});

/** Only these statuses may be removed, exactly as the backend soft-delete contract allows. */
export const DELETABLE_STATUSES = Object.freeze([
  "PENDING", "FAILED", "DUPLICATE", "UNSUPPORTED", "NEED_OCR"
]);

const EXTRACTION_ERROR_MESSAGES = Object.freeze({
  EXTRACTION_PARSE_FAILED: "文件解析失敗",
  EXTRACTION_RESOURCE_LIMIT: "文件超過抽取資源上限",
  EXTRACTION_SOURCE_UNAVAILABLE: "來源檔案目前無法讀取",
  EXTRACTION_UNSUPPORTED_TYPE: "不支援的檔案格式",
  OCR_REQUIRED: "此文件需要 OCR 後才能抽取",
  EXTRACTED_CONTENT_NOT_FOUND: "此文件尚未有抽取內容，請先執行抽取。"
});

const ERROR_MESSAGES = Object.freeze({
  NO_ACTIVE_WORKSPACE: ["尚未開啟知識庫", "請先在工作區建立或選擇 workspace。"],
  DOCUMENT_NOT_FOUND: ["找不到文件", "指定的文件不存在或已移除，請重新整理清單。"],
  DOCUMENT_ALREADY_PROCESSED: ["文件已處理", "此文件已進入處理流程，無法從收件匣移除。"],
  INVALID_REQUEST: ["要求不正確", "請確認輸入內容後再試一次。"]
});

const GENERIC_ERROR = ["收件匣操作失敗", "發生未預期的問題，請稍後再試。"];

export function statusLabel(status) {
  const key = typeof status === "string" ? status.toUpperCase() : "";
  return STATUS_LABELS[key] || text(status);
}

export function isDeletable(status) {
  const key = typeof status === "string" ? status.toUpperCase() : "";
  return DELETABLE_STATUSES.includes(key);
}

export function extractionOutcome(extraction, error) {
  if (error) {
    const code = typeof error.code === "string" ? error.code : "";
    return {
      tone: "failed",
      label: EXTRACTION_ERROR_MESSAGES[code] || "抽取失敗",
      detail: error.message ? text(error.message) : ""
    };
  }
  const parseStatus = extraction && typeof extraction.parseStatus === "string"
    ? extraction.parseStatus.toUpperCase() : "";
  if (parseStatus === "PROCESSED") {
    return {
      tone: "success",
      label: `抽取完成，共 ${text(extraction.chunkCount)} 個片段`,
      detail: ""
    };
  }
  if (parseStatus === "UNSUPPORTED") {
    return {
      tone: "unsupported",
      label: "不支援的檔案格式",
      detail: extraction.errorMessage ? text(extraction.errorMessage) : ""
    };
  }
  if (parseStatus === "NEED_OCR") {
    return {
      tone: "need-ocr",
      label: "此文件需要 OCR 後才能抽取",
      detail: extraction.errorMessage ? text(extraction.errorMessage) : ""
    };
  }
  return { tone: "failed", label: "抽取失敗", detail: "" };
}

export function inboxErrorMessage(error) {
  const code = error && typeof error.code === "string" ? error.code : "";
  const [title, message] = ERROR_MESSAGES[code] || GENERIC_ERROR;
  return { title, message };
}

function text(value) {
  return value === null || value === undefined ? "" : String(value);
}

export function appendTextElement(documentRef, parent, tag, className, value) {
  const element = documentRef.createElement(tag);
  element.className = className;
  element.textContent = value;
  parent.append(element);
  return element;
}

export function formatFileSize(bytes) {
  const value = Number(bytes);
  if (!Number.isFinite(value) || value < 0) return "";
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / (1024 * 1024)).toFixed(1)} MB`;
}

export function renderInboxList(elements, rows, pageMeta, documentRef = document, actions = {}) {
  elements.list.replaceChildren();
  const items = Array.isArray(rows) ? rows : [];
  // Pager state stays honest on every render, including the empty list.
  const meta = pageMeta && typeof pageMeta === "object" ? pageMeta : {};
  elements.pageInfo.textContent = Number.isFinite(meta.totalPages)
    ? `第 ${(meta.number ?? 0) + 1} / ${meta.totalPages} 頁（共 ${meta.totalElements} 筆）`
    : "";
  elements.prevPage.disabled = !(meta.number > 0);
  elements.nextPage.disabled = Number.isFinite(meta.totalPages)
    ? !(meta.number < meta.totalPages - 1) : true;
  if (items.length === 0) {
    elements.empty.hidden = false;
    return;
  }
  elements.empty.hidden = true;
  items.forEach(row => {
    const data = row && typeof row === "object" ? row : {};
    const item = documentRef.createElement("li");
    item.className = "inbox-item";
    const statusKey = typeof data.status === "string" ? data.status.toUpperCase() : "";
    appendTextElement(documentRef, item, "p", "inbox-file-name", text(data.fileName));
    appendTextElement(documentRef, item, "p", "inbox-item-meta",
      `${text(data.extension)} · ${formatFileSize(data.fileSize)} · 建立於 ${text(data.createdAt)}`);
    const badge = appendTextElement(documentRef, item, "span",
      `status-badge status-badge--${statusKey.toLowerCase() || "unknown"}`,
      statusLabel(data.status));
    badge.setAttribute("data-status", statusKey);
    if (data.errorCode) {
      appendTextElement(documentRef, item, "p", "inbox-item-error",
        `${text(data.errorCode)}${data.errorMessage ? `：${text(data.errorMessage)}` : ""}`);
    }
    const actionRow = documentRef.createElement("div");
    actionRow.className = "inbox-actions";
    if (typeof actions.onExtract === "function") {
      const extract = documentRef.createElement("button");
      extract.type = "button";
      extract.className = "inbox-extract";
      extract.textContent = "執行抽取";
      extract.addEventListener("click", () => actions.onExtract(data.documentId));
      actionRow.append(extract);
    }
    if (typeof actions.onPreview === "function") {
      const preview = documentRef.createElement("button");
      preview.type = "button";
      preview.className = "inbox-preview";
      preview.textContent = "查看抽取內容";
      preview.addEventListener("click", () => actions.onPreview(data.documentId));
      actionRow.append(preview);
    }
    if (typeof actions.onRemove === "function" && isDeletable(data.status)) {
      const remove = documentRef.createElement("button");
      remove.type = "button";
      remove.className = "inbox-remove";
      remove.textContent = "從收件匣移除";
      remove.addEventListener("click", () => actions.onRemove(data.documentId));
      actionRow.append(remove);
    }
    if (actionRow.children.length > 0) {
      item.append(actionRow);
    }
    elements.list.append(item);
  });
}

export function renderBatchResult(elements, batch, documentRef = document) {
  elements.batchResult.replaceChildren();
  const data = batch && typeof batch === "object" ? batch : {};
  // Partial failure must stay visible: the summary is only "all accepted" when the
  // backend reports zero failures (challenge case 3).
  const summary = `批次上傳：共 ${text(data.total)} 檔，接受 ${text(data.accepted)}，`
    + `重複 ${text(data.duplicate)}，失敗 ${text(data.failed)}`;
  appendTextElement(documentRef, elements.batchResult, "p", "inbox-batch-summary", summary);
  const failures = Array.isArray(data.failures) ? data.failures : [];
  if (failures.length > 0) {
    const list = documentRef.createElement("ul");
    list.className = "inbox-batch-failures";
    failures.forEach(failure => {
      const data2 = failure && typeof failure === "object" ? failure : {};
      appendTextElement(documentRef, list, "li", "inbox-batch-failure",
        `${text(data2.fileName)}：${text(data2.error)}`);
    });
    elements.batchResult.append(list);
  }
  elements.batchResult.hidden = false;
}

export function renderRescan(elements, rescan, documentRef = document) {
  const data = rescan && typeof rescan === "object" ? rescan : {};
  elements.rescanResult.textContent = `重新掃描完成：新文件 ${text(data.newDocuments)}，`
    + `重複 ${text(data.duplicates)}，既有 ${text(data.existing)}，移除 ${text(data.removed)}`;
  elements.rescanResult.hidden = false;
}

export function renderPreview(elements, preview, documentRef = document) {
  const data = preview && typeof preview === "object" ? preview : {};
  elements.previewPanel.hidden = false;
  elements.previewMeta.textContent = `文件 ${text(data.documentId)}：parseStatus ${text(data.parseStatus)}，`
    + `共 ${text(data.chunkCount)} 個片段（預覽為每片段前 2000 字元的 bounded 摘要）`;
  elements.previewChunks.replaceChildren();
  const chunks = Array.isArray(data.chunks) ? data.chunks : [];
  chunks.forEach(chunk => {
    const item = documentRef.createElement("li");
    item.className = "preview-chunk";
    appendTextElement(documentRef, item, "p", "preview-chunk-index",
      `片段 ${text(chunk && chunk.chunkIndex)}`);
    appendTextElement(documentRef, item, "p", "preview-chunk-content",
      text(chunk && chunk.content));
    elements.previewChunks.append(item);
  });
  const meta = data.page && typeof data.page === "object" ? data.page : {};
  elements.previewPageInfo.textContent = Number.isFinite(meta.totalPages)
    ? `預覽第 ${(meta.number ?? 0) + 1} / ${meta.totalPages} 頁` : "";
  elements.previewPrev.disabled = !(meta.number > 0);
  elements.previewNext.disabled = Number.isFinite(meta.totalPages)
    ? !(meta.number < meta.totalPages - 1) : true;
}

async function readEnvelope(response) {
  try {
    return await response.json();
  } catch {
    return null;
  }
}

export function createInboxController(elements, fetchImpl = fetch, documentRef = document) {
  const state = { page: 0, status: "", documentId: null, previewPage: 0 };
  let inFlight = false;

  function showTypedError(error) {
    const { title, message } = inboxErrorMessage(error);
    elements.hint.textContent = `${title}：${message}`;
  }

  function reset() {
    // Workspace isolation: nothing from the previous workspace survives a switch.
    state.page = 0;
    state.status = "";
    state.documentId = null;
    state.previewPage = 0;
    elements.statusFilter.value = "";
    elements.list.replaceChildren();
    elements.batchResult.hidden = true;
    elements.batchResult.replaceChildren();
    elements.rescanResult.hidden = true;
    elements.previewPanel.hidden = true;
    elements.pageInfo.textContent = "";
  }

  async function refresh() {
    if (inFlight) return;
    inFlight = true;
    elements.hint.textContent = "";
    try {
      const params = new URLSearchParams({ page: String(state.page), size: String(PAGE_SIZE) });
      if (state.status) params.set("status", state.status);
      const response = await fetchImpl(`${INBOX_ENDPOINT}?${params.toString()}`);
      const envelope = await readEnvelope(response);
      if (!response.ok) {
        showTypedError(envelope && envelope.error ? envelope.error : undefined);
        return;
      }
      const rows = envelope && Array.isArray(envelope.data) ? envelope.data : [];
      const pageMeta = envelope && envelope.page ? envelope.page : null;
      renderInboxList(elements, rows, pageMeta, documentRef, {
        onExtract: extract,
        onPreview: openPreview,
        onRemove: remove
      });
    } catch {
      showTypedError(undefined);
    } finally {
      inFlight = false;
    }
  }

  async function uploadSingle(event) {
    event.preventDefault();
    if (inFlight) return;
    const file = elements.fileInput.files && elements.fileInput.files[0];
    if (!file) {
      elements.hint.textContent = "請先選擇要上傳的檔案。";
      return;
    }
    inFlight = true;
    elements.hint.textContent = "上傳中…";
    try {
      const data = new FormData();
      data.append("file", file);
      const response = await fetchImpl(INBOX_ENDPOINT + "/files", { method: "POST", body: data });
      const envelope = await readEnvelope(response);
      if (!response.ok) {
        showTypedError(envelope && envelope.error ? envelope.error : undefined);
        return;
      }
      const uploaded = envelope.data || {};
      elements.hint.textContent = uploaded.duplicate
        ? `「${text(uploaded.fileName)}」為重複檔案：內容與既有文件相同。`
        : `「${text(uploaded.fileName)}」已上傳，狀態：待處理。可執行抽取以產生可檢索內容。`;
      elements.fileInput.value = "";
      await refresh();
    } catch {
      showTypedError(undefined);
    } finally {
      inFlight = false;
    }
  }

  async function uploadBatch(event) {
    event.preventDefault();
    if (inFlight) return;
    const files = Array.from(elements.batchInput.files || []);
    if (files.length === 0) {
      elements.hint.textContent = "請先選擇要批次上傳的檔案。";
      return;
    }
    inFlight = true;
    elements.hint.textContent = "批次上傳中…";
    try {
      const data = new FormData();
      files.forEach(file => data.append("files", file));
      const response = await fetchImpl(INBOX_ENDPOINT + "/files/batch",
        { method: "POST", body: data });
      const envelope = await readEnvelope(response);
      if (!response.ok) {
        showTypedError(envelope && envelope.error ? envelope.error : undefined);
        return;
      }
      renderBatchResult(elements, envelope.data, documentRef);
      await refresh();
    } catch {
      showTypedError(undefined);
    } finally {
      inFlight = false;
    }
  }

  async function rescan() {
    if (inFlight) return;
    inFlight = true;
    elements.hint.textContent = "";
    try {
      const response = await fetchImpl(INBOX_ENDPOINT + "/rescan", { method: "POST" });
      const envelope = await readEnvelope(response);
      if (!response.ok) {
        showTypedError(envelope && envelope.error ? envelope.error : undefined);
        return;
      }
      renderRescan(elements, envelope.data, documentRef);
      await refresh();
    } catch {
      showTypedError(undefined);
    } finally {
      inFlight = false;
    }
  }

  async function extract(documentId) {
    if (inFlight) return;
    inFlight = true;
    elements.hint.textContent = "抽取中…";
    try {
      const response = await fetchImpl(
        `${DOCUMENTS_ENDPOINT_BASE}/${documentId}/extract`, { method: "POST" });
      const envelope = await readEnvelope(response);
      if (!response.ok) {
        const outcome = extractionOutcome(null, envelope && envelope.error
          ? envelope.error : undefined);
        elements.hint.textContent = `抽取失敗：${outcome.label}`
          + (outcome.detail ? `（${outcome.detail}）` : "");
        return;
      }
      const outcome = extractionOutcome(envelope.data, null);
      elements.hint.textContent = outcome.label;
      await refresh();
    } catch {
      showTypedError(undefined);
    } finally {
      inFlight = false;
    }
  }

  async function openPreview(documentId, page = 0) {
    state.documentId = documentId;
    state.previewPage = page;
    elements.hint.textContent = "";
    try {
      const params = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE) });
      const response = await fetchImpl(
        `${DOCUMENTS_ENDPOINT_BASE}/${documentId}/extracted-content?${params.toString()}`);
      const envelope = await readEnvelope(response);
      if (!response.ok) {
        const error = envelope && envelope.error ? envelope.error : undefined;
        const code = error && typeof error.code === "string" ? error.code : "";
        elements.hint.textContent = EXTRACTION_ERROR_MESSAGES[code]
          || inboxErrorMessage(error).message;
        return;
      }
      renderPreview(elements, envelope.data, documentRef);
    } catch {
      showTypedError(undefined);
    }
  }

  async function remove(documentId) {
    if (inFlight) return;
    inFlight = true;
    elements.hint.textContent = "";
    try {
      const response = await fetchImpl(
        `${INBOX_ENDPOINT}/files/${documentId}`, { method: "DELETE" });
      if (!response.ok && response.status !== 204) {
        const envelope = await readEnvelope(response);
        showTypedError(envelope && envelope.error ? envelope.error : undefined);
        return;
      }
      elements.hint.textContent = "已從收件匣移除（soft delete）。";
      await refresh();
    } catch {
      showTypedError(undefined);
    } finally {
      inFlight = false;
    }
  }

  async function applyFilter(event) {
    event.preventDefault();
    state.page = 0;
    state.status = elements.statusFilter.value;
    await refresh();
  }

  async function nextPage() {
    state.page += 1;
    await refresh();
  }

  async function prevPage() {
    state.page = Math.max(0, state.page - 1);
    await refresh();
  }

  async function previewNext() {
    await openPreview(state.documentId, state.previewPage + 1);
  }

  async function previewPrev() {
    await openPreview(state.documentId, Math.max(0, state.previewPage - 1));
  }

  function closePreview() {
    elements.previewPanel.hidden = true;
    state.documentId = null;
  }

  elements.filterForm.addEventListener("submit", applyFilter);
  elements.uploadForm.addEventListener("submit", uploadSingle);
  elements.batchForm.addEventListener("submit", uploadBatch);
  elements.rescan.addEventListener("click", rescan);
  elements.prevPage.addEventListener("click", prevPage);
  elements.nextPage.addEventListener("click", nextPage);
  elements.previewPrev.addEventListener("click", previewPrev);
  elements.previewNext.addEventListener("click", previewNext);
  elements.previewClose.addEventListener("click", closePreview);
  if (documentRef && typeof documentRef.addEventListener === "function") {
    documentRef.addEventListener("workspace-changed", () => {
      reset();
      return refresh();
    });
  }
  return {
    refresh, reset, uploadSingle, uploadBatch, rescan, extract, openPreview, remove,
    applyFilter, nextPage, prevPage
  };
}

function elementsFrom(documentRef) {
  const byId = id => documentRef.getElementById(id);
  return {
    filterForm: byId("inbox-filter-form"),
    statusFilter: byId("inbox-status-filter"),
    list: byId("inbox-list"),
    empty: byId("inbox-empty"),
    hint: byId("inbox-hint"),
    pageInfo: byId("inbox-page-info"),
    prevPage: byId("inbox-prev-page"),
    nextPage: byId("inbox-next-page"),
    uploadForm: byId("inbox-upload-form"),
    fileInput: byId("inbox-file-input"),
    batchForm: byId("inbox-batch-form"),
    batchInput: byId("inbox-batch-input"),
    rescan: byId("inbox-rescan"),
    batchResult: byId("inbox-batch-result"),
    rescanResult: byId("inbox-rescan-result"),
    previewPanel: byId("inbox-preview-panel"),
    previewMeta: byId("inbox-preview-meta"),
    previewChunks: byId("inbox-preview-chunks"),
    previewPageInfo: byId("inbox-preview-page-info"),
    previewPrev: byId("inbox-preview-prev"),
    previewNext: byId("inbox-preview-next"),
    previewClose: byId("inbox-preview-close")
  };
}

export function bootstrapInboxUi(documentRef = document) {
  const elements = elementsFrom(documentRef);
  if (!elements.list) return null;
  const controller = createInboxController(elements, fetch, documentRef);
  controller.refresh();
  return controller;
}

if (typeof document !== "undefined") bootstrapInboxUi();
