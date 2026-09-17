import { clearSourceChunkInspector, inspectSourceChunk } from "./source-chunk-inspector-ui.js";

const INSPECT_ENDPOINT = "/api/v1/retrieval/inspect";

const ERROR_MESSAGES = Object.freeze({
  INVALID_REQUEST: ["查詢格式不正確", "請輸入查詢並選擇有效的檢索模式。"],
  NO_ACTIVE_WORKSPACE: ["尚未開啟知識庫", "請先在本機應用程式中建立或開啟目前工作區。"],
  RETRIEVAL_UNAVAILABLE: ["檢索服務暫時無法使用", "目前無法取得檢索結果，請稍後再試。"],
  RETRIEVAL_VECTOR_UNAVAILABLE: ["語意搜尋暫時無法使用", "語意搜尋能力目前無法使用，檢視器僅能顯示可用的檢索訊號。"]
});

const GENERIC_ERROR = ["無法取得檢索結果", "發生未預期的問題，請稍後再試。"];

const OUTCOME_LABELS = Object.freeze({
  CONTRIBUTED: "已貢獻",
  EMPTY: "無結果",
  UNAVAILABLE: "無法使用",
  DEGRADED: "已降級",
  DISABLED: "此模式未啟用",
  NOT_READY: "尚未就緒"
});

const DISPOSITION_LABELS = Object.freeze({
  SELECTED: "進入最終證據",
  REJECTED: "被擋下",
  DUPLICATE_FOLDED: "重複摺疊",
  BUDGET_EXCLUDED: "超出配額"
});

const OUTCOME_NOTICES = Object.freeze({
  UNAVAILABLE: "此訊號目前無法使用；下方仍顯示其他可用訊號的結果。",
  DEGRADED: "此訊號已降級；下方仍顯示其他可用訊號的結果。",
  NOT_READY: "此訊號尚未就緒；完成建置後即可使用。",
  DISABLED: "此模式未啟用此訊號。"
});

const TRANSFORMATION_STATUS_LABELS = Object.freeze({
  REWRITE_APPLIED: "已套用單次改寫",
  NO_OP_POLICY_DISABLED: "政策未啟用",
  NO_OP_NOT_APPLICABLE: "查詢不適用",
  NO_OP_DUPLICATE: "改寫與原查詢相同",
  FALLBACK_PROVIDER_UNAVAILABLE: "改寫提供者無法使用，已回退",
  FALLBACK_PROVIDER_INVALID: "改寫回應無效，已回退",
  FALLBACK_OUTPUT_OVER_LIMIT: "改寫超出界線，已回退",
  FALLBACK_EXACT_TOKEN_LOSS: "改寫遺失精確詞，已回退",
  FALLBACK_RETRIEVAL_UNAVAILABLE: "改寫檢索無法使用，已回退",
  FALLBACK_RETRIEVAL_INVALID: "改寫檢索無效，已回退"
});

const INPUT_ROLE_LABELS = Object.freeze({ ORIGINAL: "原始查詢", REWRITE: "改寫查詢" });

// Final evidence source projection (#484): kind labels and inspection-time currentness
// wording. The Browser never decides currentness itself — freshness always comes from
// the canonical locator/read flow when the user follows the navigation control.
const EVIDENCE_KIND_LABELS = Object.freeze({
  WIKI: "Wiki",
  SOURCE_CHUNK: "來源文件"
});

const EVIDENCE_CURRENTNESS_LABELS = Object.freeze({
  CURRENT: "檢視當下與目前狀態一致"
});

export function validateQuestion(question) {
  return typeof question === "string" && question.trim() ? null : "請先輸入查詢。";
}

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

function clearAll(elements) {
  elements.empty.hidden = true;
  elements.error.hidden = true;
  elements.result.hidden = true;
  elements.modalities.replaceChildren();
  elements.fusion.hidden = true;
  elements.fusionDetail.replaceChildren();
  elements.selection.replaceChildren();
  elements.finalEvidence.replaceChildren();
}

export function renderInspection(elements, payload, documentRef = document) {
  const data = payload && payload.data ? payload.data : {};
  clearAll(elements);

  if (data.insufficientEvidence === true) {
    elements.empty.hidden = false;
    elements.emptyMessage.textContent = "此查詢沒有通過權威內容驗證的檢索結果。";
    return;
  }

  elements.result.hidden = false;

  const transformation = data.queryTransformation;
  if (transformation && typeof transformation === "object") {
    const summary = documentRef.createElement("li");
    summary.className = "inspector-transformation";
    appendTextElement(documentRef, summary, "p", "inspector-modality-title",
      `查詢轉換 · ${TRANSFORMATION_STATUS_LABELS[transformation.status] || text(transformation.status)}`);
    appendTextElement(documentRef, summary, "p", "inspector-transformation-policy",
      `查詢轉換規則版本：${text(transformation.policyVersion)}`);
    appendTextElement(documentRef, summary, "p", "inspector-transformation-applicability",
      `適用性：${text(transformation.applicability)}`);
    elements.modalities.append(summary);
  }

  const retrievalInputs = Array.isArray(data.retrievalInputs) ? data.retrievalInputs : [];
  retrievalInputs.forEach(input => {
    const item = documentRef.createElement("li");
    item.className = "inspector-retrieval-input";
    appendTextElement(documentRef, item, "p", "inspector-modality-title",
      `${text(input.ordinal)}. ${INPUT_ROLE_LABELS[input.role] || text(input.role)}`);
    appendTextElement(documentRef, item, "p", "inspector-input-query", text(input.query));
    const inputModalities = Array.isArray(input.modalities) ? input.modalities : [];
    inputModalities.forEach(modality => appendTextElement(documentRef, item, "p",
      "inspector-input-modality", `${text(modality.modality)} · ${OUTCOME_LABELS[modality.outcome]
        || text(modality.outcome)}`));
    elements.modalities.append(item);
  });

  const modalities = Array.isArray(data.modalities) ? data.modalities : [];
  modalities.forEach(section => {
    const item = documentRef.createElement("li");
    item.className = "inspector-modality";
    const outcome = section && typeof section.outcome === "string" ? section.outcome : "";
    appendTextElement(documentRef, item, "p", "inspector-modality-title",
      `${text(section.modality)} · ${OUTCOME_LABELS[outcome] || outcome}`);
    const candidates = section && Array.isArray(section.candidates) ? section.candidates : [];
    candidates.forEach(candidate => {
      appendTextElement(documentRef, item, "p", "inspector-candidate",
        `${candidate.ordinal}. ${text(candidate.identity)}`);
    });
    const rejected = section && Array.isArray(section.rejected) ? section.rejected : [];
    rejected.forEach(entry => {
      appendTextElement(documentRef, item, "p", "inspector-rejected",
        `${text(entry.identity)}（${text(entry.reason)}）`);
    });
    const notice = OUTCOME_NOTICES[outcome];
    if (notice) {
      appendTextElement(documentRef, item, "p", "inspector-modality-notice", notice);
    }
    elements.modalities.append(item);
  });

  if (data.fusionPolicyVersion) {
    elements.fusion.hidden = false;
    appendTextElement(documentRef, elements.fusionDetail, "p", "inspector-fusion-policy",
      `融合規則版本：${text(data.fusionPolicyVersion)}`);
    const order = Array.isArray(data.fusedOrder) ? data.fusedOrder : [];
    appendTextElement(documentRef, elements.fusionDetail, "p", "inspector-fusion-order",
      `融合順序：${order.map((identity, index) => `${index + 1}. ${text(identity)}`).join("　")}`);
  }

  const selection = Array.isArray(data.selection) ? data.selection : [];
  selection.forEach(entry => {
    const disposition = entry && typeof entry.disposition === "string" ? entry.disposition : "";
    appendTextElement(documentRef, elements.selection, "li", "inspector-selection",
      `${text(entry.identity)}：${DISPOSITION_LABELS[disposition] || disposition}`
        + (entry.reason ? `（${text(entry.reason)}）` : ""));
  });

  const finalEvidence = Array.isArray(data.finalEvidence) ? data.finalEvidence : [];
  finalEvidence.forEach(evidence => {
    const item = documentRef.createElement("li");
    item.className = "inspector-final";
    appendTextElement(documentRef, item, "span", "inspector-final-identity",
      `E${evidence && evidence.ordinal ? evidence.ordinal : "?"} ${text(evidence && evidence.identity)}`);
    const kind = evidence && typeof evidence.kind === "string" ? evidence.kind : "";
    if (EVIDENCE_KIND_LABELS[kind]) {
      appendTextElement(documentRef, item, "span", "inspector-final-kind",
        EVIDENCE_KIND_LABELS[kind]);
    }
    if (evidence && evidence.displayLabel) {
      appendTextElement(documentRef, item, "span", "inspector-final-source",
        text(evidence.displayLabel));
    }
    const currentness = evidence && typeof evidence.currentness === "string"
      ? evidence.currentness : "";
    if (EVIDENCE_CURRENTNESS_LABELS[currentness]) {
      appendTextElement(documentRef, item, "span", "inspector-final-currentness",
        EVIDENCE_CURRENTNESS_LABELS[currentness]);
    }
    appendFinalEvidenceNavigation(documentRef, item, evidence);
    elements.finalEvidence.append(item);
  });
}

// Navigation controls reuse the canonical read-only views: source chunks open through
// the shared locator renderer (same endpoint and fail-closed typed states as the Ask
// citation flow), wiki pages link to the existing read-only Wiki view. Anything without
// a usable navigation identifier stays plain text — the Browser never fabricates a
// target.
function appendFinalEvidenceNavigation(documentRef, item, evidence) {
  const kind = evidence && typeof evidence.kind === "string" ? evidence.kind : "";
  if (kind === "SOURCE_CHUNK") {
    if (!isNavigableChunkId(evidence && evidence.sourceChunkId)) return;
    const locate = documentRef.createElement("button");
    locate.type = "button";
    locate.className = "inspector-final-locate";
    locate.textContent = "檢視來源片段";
    if (typeof locate.setAttribute === "function") {
      locate.setAttribute("data-chunk-id", String(Number(evidence.sourceChunkId)));
    }
    item.append(locate);
    return;
  }
  if (kind === "WIKI") {
    const knowledgeId = evidence && typeof evidence.knowledgeId === "string"
      ? evidence.knowledgeId.trim() : "";
    if (!knowledgeId) return;
    const open = documentRef.createElement("a");
    open.className = "inspector-final-wiki-link";
    open.textContent = "前往 Wiki 閱讀";
    open.href = "#/wiki";
    if (typeof open.setAttribute === "function") {
      open.setAttribute("data-knowledge-id", knowledgeId);
    }
    item.append(open);
  }
}

function isNavigableChunkId(value) {
  const numeric = typeof value === "number" ? value : Number(value);
  return Number.isInteger(numeric) && numeric > 0;
}

function sourcePanelElements(documentRef) {
  if (!documentRef || typeof documentRef.getElementById !== "function") return null;
  const byId = id => documentRef.getElementById(id);
  const panel = {
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
  return panel.result ? panel : null;
}

export function createInspectorController(elements, fetchImpl = fetch, documentRef = document) {
  let inFlight = false;
  const submitLabel = elements.submit.textContent || "檢視檢索過程";

  async function submit(event) {
    event.preventDefault();
    if (inFlight) return;
    const question = elements.question.value;
    const validationMessage = validateQuestion(question);
    if (validationMessage) {
      elements.hint.textContent = validationMessage;
      elements.question.focus();
      return;
    }

    inFlight = true;
    elements.hint.textContent = "正在執行檢索…";
    elements.submit.disabled = true;
    elements.submit.textContent = "處理中…";
    // Repeated inspection must never show a stale previous trace: clear before fetching and
    // on any failure, so nothing old can be mistaken for the current result. The locator
    // panel belongs to the previous trace as well, so it is cleared together with it.
    clearAll(elements);
    clearSourceChunkInspector(sourcePanelElements(documentRef));
    elements.empty.hidden = false;
    elements.emptyMessage.textContent = "正在執行檢索…";
    try {
      const params = new URLSearchParams({
        question: question.trim(),
        mode: elements.retrievalMode.value
      });
      const response = await fetchImpl(`${INSPECT_ENDPOINT}?${params.toString()}`, {
        method: "GET",
        headers: { Accept: "application/json" }
      });
      let payload;
      try {
        payload = await response.json();
      } catch {
        payload = {};
      }
      elements.empty.hidden = true;
      if (!response.ok || !payload.data) {
        clearAll(elements);
        showError(elements, payload.error, documentRef);
      } else {
        renderInspection(elements, payload, documentRef);
      }
    } catch {
      clearAll(elements);
      showError(elements, undefined, documentRef);
    } finally {
      inFlight = false;
      elements.submit.disabled = false;
      elements.submit.textContent = submitLabel;
      elements.hint.textContent = "";
    }
  }

  elements.form.addEventListener("submit", submit);
  // Final evidence navigation (#484): delegate button clicks to the shared locator
  // renderer so each evidence item opens in the existing Source Chunk Inspector panel.
  // Wiki items are native anchors and need no handler. Read-only throughout.
  if (elements.finalEvidence && typeof elements.finalEvidence.addEventListener === "function") {
    elements.finalEvidence.addEventListener("click", clickEvent => {
      const target = clickEvent && clickEvent.target;
      const chunkId = target && typeof target.getAttribute === "function"
        ? target.getAttribute("data-chunk-id")
        : null;
      if (!chunkId) return;
      if (clickEvent && typeof clickEvent.preventDefault === "function") {
        clickEvent.preventDefault();
      }
      const panel = sourcePanelElements(documentRef);
      if (!panel) return;
      void inspectSourceChunk(panel, chunkId, fetchImpl, documentRef);
    });
  }
  // Workspace isolation (#375): inspection results are current-workspace projections
  // and must never survive a workspace switch.
  if (documentRef && typeof documentRef.addEventListener === "function") {
    documentRef.addEventListener("workspace-changed", () => {
      elements.question.value = "";
      elements.hint.textContent = "";
      elements.result.hidden = true;
      elements.fusion.hidden = true;
      elements.error.hidden = true;
      elements.empty.hidden = true;
      elements.modalities.replaceChildren();
      elements.selection.replaceChildren();
      elements.finalEvidence.replaceChildren();
    });
  }

  return { submit };
}

function showError(elements, error) {
  elements.error.hidden = false;
  const { title, message } = errorMessage(error);
  elements.errorTitle.textContent = title;
  elements.errorMessage.textContent = message;
}

export function bootstrapInspectorUi(documentRef = document) {
  const elements = elementsFrom(documentRef);
  if (!elements.form) return null;
  return createInspectorController(elements, fetch, documentRef);
}

function elementsFrom(documentRef) {
  const byId = id => documentRef.getElementById(id);
  return {
    form: byId("inspector-form"),
    question: byId("inspector-question"),
    retrievalMode: byId("inspector-mode"),
    submit: byId("inspector-submit"),
    hint: byId("inspector-hint"),
    result: byId("inspector-result"),
    empty: byId("inspector-empty"),
    emptyMessage: byId("inspector-empty-message"),
    error: byId("inspector-error"),
    errorTitle: byId("inspector-error-title"),
    errorMessage: byId("inspector-error-message"),
    modalities: byId("inspector-modalities"),
    fusion: byId("inspector-fusion"),
    fusionDetail: byId("inspector-fusion-detail"),
    selection: byId("inspector-selection"),
    finalEvidence: byId("inspector-final-evidence")
  };
}

if (typeof document !== "undefined") bootstrapInspectorUi();
