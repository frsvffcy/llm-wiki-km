const INSPECT_ENDPOINT = "/api/v1/retrieval/inspect";

const ERROR_MESSAGES = Object.freeze({
  INVALID_REQUEST: ["查詢格式不正確", "請輸入查詢並選擇有效的檢索模式。"],
  NO_ACTIVE_WORKSPACE: ["尚未開啟知識庫", "請先在本機應用程式中建立或開啟 active workspace。"],
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
    elements.emptyMessage.textContent = "此查詢沒有通過 canonical 驗證的檢索結果。";
    return;
  }

  elements.result.hidden = false;

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
      `fusion policy：${text(data.fusionPolicyVersion)}`);
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
    appendTextElement(documentRef, elements.finalEvidence, "li", "inspector-final",
      `E${evidence.ordinal} ${text(evidence.identity)}`);
  });
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
    // on any failure, so nothing old can be mistaken for the current result.
    clearAll(elements);
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
