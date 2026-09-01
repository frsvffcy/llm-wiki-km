const RETRIEVAL_MODES = Object.freeze([
  { value: "HYBRID_FTS", label: "Wiki 與來源文件" },
  { value: "WIKI_ONLY", label: "僅 Wiki" },
  { value: "SOURCE_ONLY", label: "僅來源文件" }
]);

const ERROR_MESSAGES = Object.freeze({
  INVALID_REQUEST: ["問題格式不正確", "請輸入問題後再試一次。"],
  ANSWER_REQUEST_REJECTED: ["問題格式不正確", "請確認問題內容後再試一次。"],
  NO_ACTIVE_WORKSPACE: ["尚未開啟知識庫", "請先在本機應用程式中建立或開啟 active workspace。"],
  RETRIEVAL_UNAVAILABLE: ["搜尋服務暫時無法使用", "目前無法取得已索引的知識內容，請稍後再試。"],
  ANSWER_PROVIDER_NOT_CONFIGURED: ["尚未設定回答服務", "請由管理者設定 answer provider 後再試。"],
  ANSWER_PROVIDER_AUTHENTICATION_FAILED: ["回答服務驗證失敗", "回答服務目前無法驗證請求，請聯絡管理者。"],
  ANSWER_PROVIDER_RATE_LIMITED: ["回答服務目前忙碌", "已達回答服務的使用限制，請稍後再試。"],
  ANSWER_PROVIDER_UNAVAILABLE: ["回答服務暫時無法使用", "回答服務目前沒有回應，請稍後再試。"],
  ANSWER_PROVIDER_SERVER_FAILURE: ["回答服務發生問題", "回答服務目前無法完成請求，請稍後再試。"],
  ANSWER_PROVIDER_INVALID_RESPONSE: ["回答服務回應無效", "這次回答未能通過安全驗證，請稍後再試。"]
});

const GENERIC_ERROR = ["無法取得回答", "發生未預期的問題，請稍後再試。"];

export function validateQuestion(question) {
  return typeof question === "string" && question.trim() ? null : "請先輸入問題。";
}

export function errorMessage(error) {
  const code = error && typeof error.code === "string" ? error.code : "";
  const [title, message] = ERROR_MESSAGES[code] || GENERIC_ERROR;
  return { title, message };
}

function text(value) {
  return value === null || value === undefined ? "" : String(value);
}

function detail(label, value) {
  const valueText = text(value);
  return valueText ? `${label}：${valueText}` : "";
}

function provenanceTitle(provenance) {
  if (!provenance) return "未命名來源";
  return provenance.type === "WIKI"
    ? text(provenance.title) || "Wiki 頁面"
    : text(provenance.documentName) || "來源文件";
}

function provenanceDetails(provenance) {
  if (!provenance) return [];
  if (provenance.type === "WIKI") {
    return [
      detail("路徑", provenance.path),
      detail("revision", provenance.revision)
    ].filter(Boolean);
  }
  return [
    detail("頁碼", provenance.pageNo),
    detail("section", provenance.section),
    detail("heading", provenance.headingPath),
    detail("chunk", provenance.chunkNo)
  ].filter(Boolean);
}

function appendTextElement(documentRef, parent, tag, className, value) {
  const node = documentRef.createElement(tag);
  if (className) node.className = className;
  node.textContent = text(value);
  parent.append(node);
  return node;
}

export function renderAskResponse(elements, payload, documentRef = document) {
  const data = payload && payload.data ? payload.data : {};
  elements.empty.hidden = true;
  elements.error.hidden = true;
  elements.insufficient.hidden = true;
  elements.answer.hidden = true;
  elements.metadata.hidden = true;
  elements.citations.replaceChildren();
  elements.citationCount.textContent = "";
  elements.metadata.replaceChildren();

  if (data.status === "INSUFFICIENT_EVIDENCE" || data.insufficientEvidence === true) {
    elements.insufficient.hidden = false;
    return;
  }

  if (data.status !== "ANSWERED" || typeof data.answer !== "string") {
    showError(elements, undefined);
    return;
  }

  elements.answer.hidden = false;
  elements.answerText.textContent = data.answer;
  const citations = Array.isArray(data.citations) ? data.citations : [];
  elements.citationCount.textContent = `${citations.length} 筆`;

  citations.forEach((citation, index) => {
    const citationData = citation && typeof citation === "object" ? citation : {};
    const item = documentRef.createElement("li");
    item.className = "citation-item";
    appendTextElement(documentRef, item, "span", "citation-number", index + 1);
    const content = documentRef.createElement("div");
    appendTextElement(documentRef, content, "p", "citation-kind",
      citationData.evidenceKind === "SOURCE_CHUNK" ? "SOURCE" : "WIKI");
    appendTextElement(documentRef, content, "p", "citation-title",
      provenanceTitle(citationData.provenance));
    const details = provenanceDetails(citationData.provenance).join(" · ");
    appendTextElement(documentRef, content, "p", "citation-meta", details);
    item.append(content);
    elements.citations.append(item);
  });

  const provider = data.providerMetadata;
  if (provider && (provider.provider || provider.model)) {
    elements.metadata.hidden = false;
    elements.metadata.textContent = `回答服務：${text(provider.provider)}${provider.model ? ` · ${text(provider.model)}` : ""}`;
  }
}

function showError(elements, error) {
  const copy = errorMessage(error);
  elements.empty.hidden = true;
  elements.answer.hidden = true;
  elements.insufficient.hidden = true;
  elements.error.hidden = false;
  elements.errorTitle.textContent = copy.title;
  elements.errorMessage.textContent = copy.message;
  elements.citations.replaceChildren();
  elements.citationCount.textContent = "";
  elements.metadata.hidden = true;
  elements.metadata.replaceChildren();
}

function elementsFrom(documentRef) {
  return {
    form: documentRef.getElementById("ask-form"),
    question: documentRef.getElementById("question"),
    retrievalMode: documentRef.getElementById("retrieval-mode"),
    submit: documentRef.getElementById("ask-submit"),
    hint: documentRef.getElementById("form-hint"),
    result: documentRef.getElementById("ask-result"),
    empty: documentRef.getElementById("result-empty"),
    error: documentRef.getElementById("result-error"),
    errorTitle: documentRef.getElementById("error-title"),
    errorMessage: documentRef.getElementById("error-message"),
    insufficient: documentRef.getElementById("result-insufficient"),
    answer: documentRef.getElementById("result-answer"),
    answerText: documentRef.getElementById("answer-text"),
    metadata: documentRef.getElementById("provider-metadata"),
    citations: documentRef.getElementById("citations"),
    citationCount: documentRef.getElementById("citation-count")
  };
}

export function createAskController(elements, fetchImpl = fetch, documentRef = document) {
  let inFlight = false;
  const submitLabel = elements.submit.textContent || "取得回答";

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
    elements.hint.textContent = "正在搜尋並整理回答…";
    elements.submit.disabled = true;
    elements.submit.textContent = "處理中…";
    elements.result.setAttribute("aria-busy", "true");
    try {
      const response = await fetchImpl("/api/v1/ask", {
        method: "POST",
        headers: { "Content-Type": "application/json", Accept: "application/json" },
        body: JSON.stringify({
          question: question.trim(),
          retrievalMode: elements.retrievalMode.value
        })
      });
      let payload;
      try {
        payload = await response.json();
      } catch {
        payload = {};
      }
      if (!response.ok || !payload.data) {
        showError(elements, payload.error, documentRef);
      } else {
        renderAskResponse(elements, payload, documentRef);
      }
    } catch {
      showError(elements, undefined, documentRef);
    } finally {
      inFlight = false;
      elements.submit.disabled = false;
      elements.submit.textContent = submitLabel;
      elements.result.setAttribute("aria-busy", "false");
      elements.hint.textContent = "";
    }
  }

  elements.form.addEventListener("submit", submit);
  return { submit };
}

export function bootstrapAskUi(documentRef = document) {
  const elements = elementsFrom(documentRef);
  if (!elements.form) return null;
  return createAskController(elements, fetch, documentRef);
}

if (typeof document !== "undefined") bootstrapAskUi();

export { RETRIEVAL_MODES };
