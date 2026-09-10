const LOCATOR_ENDPOINT = "/api/v1/source-chunks";

const ERROR_MESSAGES = Object.freeze({
  INVALID_REQUEST: ["來源位置格式不正確", "請重新點選 citation 後再試一次。"],
  NO_ACTIVE_WORKSPACE: ["尚未開啟知識庫", "請先在本機應用程式中建立或開啟 active workspace。"],
  RETRIEVAL_UNAVAILABLE: ["來源服務暫時無法使用", "目前無法取得來源位置，請稍後再試。"]
});

const GENERIC_ERROR = ["無法取得來源位置", "發生未預期的問題，請稍後再試。"];

const NOT_FOUND_MESSAGE = ["找不到來源位置",
  "此 citation 對應的來源片段已不存在（可能已重新抽取或重新分段）。citation 本身仍然有效。"];

const CURRENTNESS_NOTICES = Object.freeze({
  NOT_CURRENT: "此來源片段目前與 canonical 狀態不一致，內容不再顯示；citation 本身仍然有效。"
});

const CURRENTNESS_LABELS = Object.freeze({
  CURRENT: "與 canonical 狀態一致",
  NOT_CURRENT: "已與 canonical 狀態不一致"
});

const REASON_LABELS = Object.freeze({
  AUTHORITY_MISSING: "找不到對應的 canonical 來源",
  STALE_REVISION: "來源已更新",
  INELIGIBLE: "來源目前不可用",
  IDENTITY_MISMATCH: "來源識別不一致",
  WORKSPACE_MISMATCH: "來源屬於其他知識庫"
});

export function errorMessage(error) {
  const code = error && typeof error.code === "string" ? error.code : "";
  const [title, message] = ERROR_MESSAGES[code] || GENERIC_ERROR;
  return { title, message };
}

function text(value) {
  return value === null || value === undefined ? "" : String(value);
}

function appendTextElement(documentRef, parent, tag, className, value) {
  const node = documentRef.createElement(tag);
  if (className) node.className = className;
  node.textContent = text(value);
  parent.append(node);
  return node;
}

function detail(label, value) {
  const valueText = text(value);
  return valueText ? `${label}：${valueText}` : "";
}

function clearAll(elements) {
  elements.result.hidden = true;
  elements.error.hidden = true;
  elements.notFound.hidden = true;
  elements.metadata.replaceChildren();
  elements.preview.replaceChildren();
}

export function renderLocator(elements, payload, documentRef = document) {
  const data = payload && payload.data ? payload.data : {};
  clearAll(elements);
  elements.result.hidden = false;

  const currentness = typeof data.currentness === "string" ? data.currentness : "";
  const metadataParts = [
    detail("文件", data.documentName),
    detail("片段", data.sourceChunkId),
    detail("chunk", data.chunkNo),
    detail("頁碼", data.pageNo),
    detail("section", data.section),
    detail("heading", data.headingPath)
  ].filter(Boolean);
  appendTextElement(documentRef, elements.metadata, "p", "inspector-metadata-line",
    metadataParts.join(" · "));
  appendTextElement(documentRef, elements.metadata, "p", "inspector-currentness",
    CURRENTNESS_LABELS[currentness] || currentness);

  if (currentness === "NOT_CURRENT") {
    const reason = data.notCurrentReason && REASON_LABELS[data.notCurrentReason]
      ? REASON_LABELS[data.notCurrentReason] : text(data.notCurrentReason);
    appendTextElement(documentRef, elements.metadata, "p", "inspector-not-current",
      `${CURRENTNESS_NOTICES.NOT_CURRENT}${reason ? `（${reason}）` : ""}`);
    return;
  }

  appendTextElement(documentRef, elements.preview, "h3", "inspector-preview-heading",
    "bounded 預覽");
  const preview = appendTextElement(documentRef, elements.preview, "p",
    "inspector-preview", data.preview);
  if (data.previewTruncated === true) {
    appendTextElement(documentRef, elements.preview, "p", "inspector-preview-truncated",
      "預覽已截斷；完整內容請在來源文件中查看。");
  }
  return preview;
}

export function showLocatorError(elements, error) {
  clearAll(elements);
  if (error && error.code === "SOURCE_CHUNK_NOT_FOUND") {
    elements.notFound.hidden = false;
    elements.notFoundTitle.textContent = NOT_FOUND_MESSAGE[0];
    elements.notFoundMessage.textContent = NOT_FOUND_MESSAGE[1];
    return;
  }
  elements.error.hidden = false;
  const { title, message } = errorMessage(error);
  elements.errorTitle.textContent = title;
  elements.errorMessage.textContent = message;
}

let inspectionSequence = 0;

export async function inspectSourceChunk(elements, chunkId, fetchImpl = fetch,
                                          documentRef = document) {
  const numericId = Number(chunkId);
  if (!Number.isInteger(numericId) || numericId <= 0) {
    showLocatorError(elements, undefined);
    return;
  }
  const sequence = ++inspectionSequence;
  elements.loading.hidden = false;
  // Repeated open must never show a stale previous locator: clear before fetching and on any
  // failure, so nothing old can be mistaken for the current result.
  clearAll(elements);
  try {
    const response = await fetchImpl(`${LOCATOR_ENDPOINT}/${numericId}/locator`, {
      method: "GET",
      headers: { Accept: "application/json" }
    });
    let payload;
    try {
      payload = await response.json();
    } catch {
      payload = {};
    }
    if (sequence !== inspectionSequence) {
      // A newer open superseded this one; its result must not bleed into the panel.
      return;
    }
    elements.loading.hidden = true;
    if (!response.ok || !payload.data) {
      showLocatorError(elements, payload.error);
      return;
    }
    renderLocator(elements, payload, documentRef);
  } catch {
    if (sequence !== inspectionSequence) {
      return;
    }
    elements.loading.hidden = true;
    showLocatorError(elements, undefined);
  }
}

export function createSourceChunkInspectorController(elements, fetchImpl = fetch,
                                                     documentRef = document) {
  const citations = documentRef.getElementById("citations");
  if (!citations || typeof citations.addEventListener !== "function") return null;
  citations.addEventListener("click", clickEvent => {
    const target = clickEvent.target;
    // Real DOM attributes are a NamedNodeMap: read via getAttribute, never .get().
    const chunkId = target && typeof target.getAttribute === "function"
      ? target.getAttribute("data-chunk-id")
      : null;
    if (chunkId) {
      clickEvent.preventDefault();
      void inspectSourceChunk(elements, chunkId, fetchImpl, documentRef);
    }
  });
  return { inspectSourceChunk: chunkId => inspectSourceChunk(elements, chunkId, fetchImpl,
    documentRef) };
}

export function bootstrapSourceChunkInspector(documentRef = document) {
  const byId = id => documentRef.getElementById(id);
  const elements = {
    result: byId("source-inspector-result"),
    loading: byId("source-inspector-loading"),
    error: byId("source-inspector-error"),
    errorTitle: byId("source-inspector-error-title"),
    errorMessage: byId("source-inspector-error-message"),
    notFound: byId("source-inspector-not-found"),
    notFoundTitle: byId("source-inspector-not-found-title"),
    notFoundMessage: byId("source-inspector-not-found-message"),
    metadata: byId("source-inspector-metadata"),
    preview: byId("source-inspector-preview")
  };
  if (!elements.result) return null;
  return createSourceChunkInspectorController(elements, fetch, documentRef);
}

if (typeof document !== "undefined") bootstrapSourceChunkInspector();
