import { inspectSourceChunk } from "./source-chunk-inspector-ui.js";
const RETRIEVAL_MODES = Object.freeze([
  { value: "HYBRID_FTS", label: "Wiki 與來源文件（全文搜尋）" },
  { value: "WIKI_ONLY", label: "僅 Wiki" },
  { value: "SOURCE_ONLY", label: "僅來源文件" },
  { value: "SEMANTIC_WIKI", label: "Wiki 語意搜尋" },
  { value: "SEMANTIC_SOURCE", label: "來源文件語意搜尋" },
  { value: "HYBRID_VECTOR", label: "Wiki 與來源文件（語意混合）" },
  { value: "HYBRID_GRAPH", label: "Wiki 與來源文件（圖譜增強）" }
]);

const ERROR_MESSAGES = Object.freeze({
  INVALID_REQUEST: ["問題格式不正確", "請輸入問題後再試一次。"],
  ANSWER_REQUEST_REJECTED: ["問題格式不正確", "請確認問題內容後再試一次。"],
  ASK_DOCUMENT_SCOPE_INVALID: ["這份文件目前無法提問", "文件已失效、移除或尚未準備完成；請重新選擇文件，或改問整個知識庫。"],
  ASK_DOCUMENT_SCOPE_STALE: ["這份文件需要重新處理", "文件的搜尋索引已過期；請重新處理文件，或改問整個知識庫。"],
  NO_ACTIVE_WORKSPACE: ["尚未開啟知識庫", "請先在本機應用程式中建立或開啟目前工作區。"],
  RETRIEVAL_UNAVAILABLE: ["搜尋服務暫時無法使用", "目前無法取得已索引的知識內容，請稍後再試。"],
  RETRIEVAL_VECTOR_UNAVAILABLE: ["語意搜尋暫時無法使用", "目前無法使用語意搜尋能力，請稍後再試或改用全文搜尋。"],
  ANSWER_PROVIDER_NOT_CONFIGURED: ["尚未設定回答服務", "請由管理者設定回答服務後再試。"],
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
      detail("版本", provenance.revision)
    ].filter(Boolean);
  }
  return [
    detail("頁碼", provenance.pageNo),
    detail("節次", provenance.section),
    detail("標題路徑", provenance.headingPath),
    detail("片段編號", provenance.chunkNo)
  ].filter(Boolean);
}

function appendTextElement(documentRef, parent, tag, className, value) {
  const node = documentRef.createElement(tag);
  if (className) node.className = className;
  node.textContent = text(value);
  parent.append(node);
  return node;
}

function isValidAnsweredPayload(data) {
  return data.status === "ANSWERED"
    && typeof data.answer === "string"
    && data.answer.trim().length > 0
    && Array.isArray(data.citations)
    && data.citations.length >= 1;
}

function formatReductionRatio(value) {
  return typeof value === "number" && Number.isFinite(value) && value >= 0 && value <= 1
    ? `${(value * 100).toFixed(1)}%`
    : "—";
}

function formatBoolean(value) {
  return value === true ? "是" : value === false ? "否" : "—";
}

function formatCount(value) {
  return Number.isSafeInteger(value) && value >= 0 ? String(value) : "—";
}

function formatLatencyMs(value) {
  return Number.isSafeInteger(value) && value >= 0 ? `${value} ms` : "—";
}

function formatSafeString(value) {
  return typeof value === "string" && /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/.test(value)
    ? value : "—";
}

function formatProjectionFailureType(value) {
  return [
    "INVALID_POLICY",
    "PROJECTION_INVARIANT_VIOLATION",
    "PROJECTION_LIMIT_EXCEEDED",
    "UNSUPPORTED_CONTENT_KIND"
  ].includes(value) ? value : "—";
}

function formatProjectionKinds(distribution) {
  if (!distribution || typeof distribution !== "object") return "—";
  const labels = ["VERBATIM", "NO_OP", "EXTRACTIVE", "TRUNCATED"];
  const parts = labels
    .filter(kind => Number.isSafeInteger(distribution[kind]) && distribution[kind] > 0)
    .map(kind => `${kind} ${distribution[kind]}`);
  return parts.length > 0 ? parts.join(" · ") : "—";
}

function formatProviderUsageStatus(status) {
  return {
    NOT_ATTEMPTED: "尚未呼叫",
    AVAILABLE: "已取得",
    UNAVAILABLE: "未提供"
  }[status] || "—";
}

function egressDestinationLabel(destination) {
  return {
    DISABLED: "AI 未啟用",
    LOCAL_LOOPBACK: "本機 AI",
    REMOTE_SECURE: "遠端安全連線",
    REMOTE_INSECURE_OPT_IN: "遠端明文連線（已明確開啟）",
    UNAVAILABLE_OR_INVALID: "AI 設定不可用"
  }[destination] || "AI 設定不可用";
}

function egressPurposeLabel(purpose) {
  return {
    ANSWER: "回答提供者",
    EMBEDDING: "向量嵌入提供者",
    QUERY_REWRITE: "查詢改寫提供者"
  }[purpose] || "AI 提供者";
}

function egressCategoryLabel(category) {
  return {
    QUESTION_TEXT: "使用者問題文字",
    EVIDENCE_CONTEXT_REPRESENTATION: "已通過驗證的證據所衍生的上下文表示",
    INSTRUCTION_CONTEXT: "應用程式內建的指示脈絡",
    GENERATION_SETTINGS: "生成參數（不含機密）",
    PROVIDER_RESPONSE_METADATA: "回答與使用量中繼資料",
    EMBEDDING_INPUT_REPRESENTATION: "經挑選的文本表示",
    QUERY_REWRITE_INPUT: "原始查詢與受保護的精確詞",
    QUERY_REWRITE_RESPONSE_METADATA: "查詢改寫與使用量中繼資料"
  }[category] || category;
}

function egressRows(descriptor) {
  const rows = [
    ["用途", egressPurposeLabel(descriptor.purpose)],
    ["資料去向", egressDestinationLabel(descriptor.destinationClass)]
  ];
  if (descriptor.providerType) rows.push(["提供者類型", descriptor.providerType]);
  if (descriptor.modelDisplayName) rows.push(["模型名稱", descriptor.modelDisplayName]);
  const categories = (descriptor.egressCategories || []).map(egressCategoryLabel);
  rows.push(["可能送出的資料類型", categories.length > 0 ? categories.join("、") : "無"]);
  return rows;
}

/**
 * Loads the application-owned provider egress disclosure and renders a low-noise trust
 * indicator next to the Ask input. Renders safe text only: no endpoints, no credentials, no
 * raw provider details. Configuration-level destination is not an execution fact; the answer
 * execution's actual provider usage is shown separately in the context diagnostics.
 */
export async function loadAiEgress(elements, fetchImpl = fetch, documentRef = document) {
  if (!elements.aiEgress || !elements.aiEgressLabel || !elements.aiEgressDetail) return;
  try {
    const response = await fetchImpl("/api/v1/system/ai-provider-egress", {
      headers: { Accept: "application/json" }
    });
    if (!response.ok) {
      elements.aiEgress.hidden = true;
      return;
    }
    const payload = await response.json();
    const descriptors = Array.isArray(payload && payload.data) ? payload.data : [];
    if (descriptors.length === 0) {
      elements.aiEgress.hidden = true;
      return;
    }
    const byPurpose = new Map(descriptors.map(item => [item.purpose, item]));
    const answer = byPurpose.get("ANSWER") || descriptors[0];
    // The collapsed headline must never hide the most severe boundary (e.g. answer local but
    // embedding remote insecure); it always surfaces the worst destination across both.
    const worst = descriptors
      .filter(item => item && item.destinationClass)
      .reduce((severe, item) => egressSeverity(item.destinationClass) >
              egressSeverity(severe.destinationClass) ? item : severe, answer);
    elements.aiEgressLabel.textContent = egressDestinationLabel(worst.destinationClass);
    elements.aiEgress.className = "ai-egress "
        + egressDestinationClass(worst.destinationClass);
    elements.aiEgress.hidden = false;

    const rows = [];
    descriptors.forEach(descriptor => {
      egressRows(descriptor).forEach(([labelText, value]) => {
        const prefix = { ANSWER: "回答", EMBEDDING: "向量嵌入", QUERY_REWRITE: "查詢改寫" }
          [descriptor.purpose] || "AI";
        rows.push([`${prefix} · ${labelText}`, value]);
      });
    });
    rows.push(["本次執行是否實際呼叫提供者", "見回答結果的上下文與執行診斷（提供者使用情況）"]);
    elements.aiEgressDetail.replaceChildren(
      ...rows.map(([labelText, value]) => {
        const wrapper = documentRef.createElement("div");
        wrapper.className = "ai-egress-metric";
        const title = documentRef.createElement("dt");
        title.textContent = labelText;
        const content = documentRef.createElement("dd");
        content.textContent = value;
        wrapper.append(title, content);
        return wrapper;
      }));
  } catch (error) {
    // Transparency is best-effort: a network failure must never block asking or leak details.
    elements.aiEgress.hidden = true;
  }
}

function egressSeverity(destination) {
  return {
    REMOTE_INSECURE_OPT_IN: 4,
    UNAVAILABLE_OR_INVALID: 3,
    REMOTE_SECURE: 2,
    LOCAL_LOOPBACK: 1,
    DISABLED: 0
  }[destination] || 0;
}

function egressDestinationClass(destination) {
  if (destination === "REMOTE_INSECURE_OPT_IN") return "ai-egress--insecure";
  if (destination === "REMOTE_SECURE") return "ai-egress--remote";
  if (destination === "LOCAL_LOOPBACK") return "ai-egress--local";
  return "ai-egress--off";
}

function appendDiagnosticMetric(documentRef, list, label, value) {
  const row = documentRef.createElement("div");
  row.className = "context-diagnostic-item";
  appendTextElement(documentRef, row, "dt", "context-diagnostic-label", label);
  appendTextElement(documentRef, row, "dd", "context-diagnostic-value", value);
  list.append(row);
}

function clearContextDiagnostics(elements) {
  if (elements.contextDiagnostics) elements.contextDiagnostics.hidden = true;
  if (elements.contextDiagnosticsList) elements.contextDiagnosticsList.replaceChildren();
}

function renderContextDiagnostics(elements, executionMetadata, documentRef) {
  clearContextDiagnostics(elements);
  if (!elements.contextDiagnostics || !elements.contextDiagnosticsList) return;
  const diagnostics = executionMetadata && executionMetadata.contextDiagnostics;
  if (!diagnostics || typeof diagnostics !== "object" || Array.isArray(diagnostics)) return;

  const metrics = [
    ["檢索證據", formatCount(diagnostics.retrievedEvidenceCount)],
    ["通過納入檢查的證據", formatCount(diagnostics.admittedEvidenceCount)],
    ["回答上下文區塊", formatCount(diagnostics.answerContextBlockCount)],
    ["原始字元數（code points）", formatCount(diagnostics.originalCodePoints)],
    ["打包後字元數（code points）", formatCount(diagnostics.packedCodePoints)],
    ["投影後字元數（code points）", formatCount(diagnostics.projectedCodePoints)],
    ["縮減比例", formatReductionRatio(diagnostics.reductionRatio)],
    ["基準已截斷", formatBoolean(diagnostics.truncated)],
    ["已壓縮", formatBoolean(diagnostics.compacted)],
    ["上下文政策", formatSafeString(diagnostics.contextPolicyVersion)],
    ["投影類型", formatProjectionKinds(diagnostics.projectionKindDistribution)],
    ["投影回退", formatBoolean(diagnostics.projectionFallbackUsed)],
    ["投影失敗類型", formatProjectionFailureType(diagnostics.projectionFailureType)],
    ["投影延遲", formatLatencyMs(diagnostics.projectionLatencyMs)],
    ["回答延遲", formatLatencyMs(diagnostics.answerLatencyMs)],
    ["提供者使用情況", formatProviderUsageStatus(diagnostics.providerUsageStatus)],
    ["提供者輸入 token 數", formatCount(diagnostics.providerInputTokens)],
    ["提供者輸出 token 數", formatCount(diagnostics.providerOutputTokens)],
    ["提供者總 token 數", formatCount(diagnostics.providerTotalTokens)]
  ];
  metrics.forEach(([label, value]) => appendDiagnosticMetric(
    documentRef, elements.contextDiagnosticsList, label, value));
  elements.contextDiagnostics.hidden = false;
}

export function renderAskResponse(elements, payload, documentRef = document) {
  const data = payload && payload.data ? payload.data : {};
  elements.empty.hidden = true;
  elements.error.hidden = true;
  elements.insufficient.hidden = true;
  elements.answer.hidden = true;
  elements.metadata.hidden = true;
  clearContextDiagnostics(elements);
  elements.citations.replaceChildren();
  elements.citationCount.textContent = "";
  elements.metadata.replaceChildren();

  if (data.status === "INSUFFICIENT_EVIDENCE" || data.insufficientEvidence === true) {
    elements.insufficient.hidden = false;
    renderContextDiagnostics(elements, data.executionMetadata, documentRef);
    return;
  }

  if (!isValidAnsweredPayload(data)) {
    showError(elements, undefined);
    return;
  }

  elements.answer.hidden = false;
  renderContextDiagnostics(elements, data.executionMetadata, documentRef);
  // The governed hand-off is offered only for a grounded answer (#374).
  if (elements.toProposal) {
    elements.toProposal.hidden = false;
    elements.toProposal.disabled = false;
    elements.toProposalHint.textContent = "";
  }
  elements.answerText.textContent = data.answer;
  const citations = data.citations;
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
    const chunkId = citationData.provenance && citationData.provenance.sourceChunkId;
    if (citationData.evidenceKind === "SOURCE_CHUNK" && chunkId) {
      const locate = documentRef.createElement("button");
      locate.type = "button";
      locate.className = "citation-locate";
      locate.textContent = "檢視來源位置";
      locate.setAttribute("data-chunk-id", String(chunkId));
      item.append(locate);
    }
    elements.citations.append(item);
  });

  const provider = data.providerMetadata;
  const retrieval = data.retrievalMetadata;
  const metadataParts = [];
  if (provider && (provider.provider || provider.model)) {
    metadataParts.push(`回答服務：${text(provider.provider)}${provider.model ? ` · ${text(provider.model)}` : ""}`);
  }
  if (retrieval && retrieval.degradedFallback === true) {
    metadataParts.push("搜尋提示：語意搜尋暫時不可用，已改用全文搜尋結果");
  }
  // Graph signal degradation is a typed diagnostic from the server contract, never a failure:
  // the answer and its citations remain valid on the lexical/vector baseline.
  if (retrieval && retrieval.graphDegraded === true) {
    metadataParts.push("搜尋提示：知識圖譜訊號已降級，此回答仍以全文與語意搜尋結果為依據");
  }
  if (retrieval && retrieval.graphUnavailable === true) {
    metadataParts.push("搜尋提示：知識圖譜訊號暫時無法使用，此回答仍以全文與語意搜尋結果為依據");
  }
  if (metadataParts.length > 0) {
    elements.metadata.hidden = false;
    elements.metadata.textContent = metadataParts.join(" · ");
  }
}

function showError(elements, error) {
  const copy = errorMessage(error);
  elements.empty.hidden = true;
  elements.answer.hidden = true;
  elements.insufficient.hidden = true;
  elements.error.hidden = false;
  clearContextDiagnostics(elements);
  elements.errorTitle.textContent = copy.title;
  elements.errorMessage.textContent = copy.message;
  elements.citations.replaceChildren();
  elements.citationCount.textContent = "";
  elements.metadata.hidden = true;
  elements.metadata.replaceChildren();
  if (elements.toProposal) {
    elements.toProposal.hidden = false;
    elements.toProposal.disabled = false;
  }
  if (elements.toProposalHint) {
    elements.toProposalHint.textContent = "";
  }
}

function elementsFrom(documentRef) {
  return {
    form: documentRef.getElementById("ask-form"),
    question: documentRef.getElementById("question"),
    retrievalMode: documentRef.getElementById("retrieval-mode"),
    submit: documentRef.getElementById("ask-submit"),
    hint: documentRef.getElementById("form-hint"),
    documentScope: documentRef.getElementById("ask-document-scope"),
    documentScopeLabel: documentRef.getElementById("ask-document-scope-label"),
    documentScopeClear: documentRef.getElementById("ask-document-scope-clear"),
    result: documentRef.getElementById("ask-result"),
    empty: documentRef.getElementById("result-empty"),
    error: documentRef.getElementById("result-error"),
    errorTitle: documentRef.getElementById("error-title"),
    errorMessage: documentRef.getElementById("error-message"),
    insufficient: documentRef.getElementById("result-insufficient"),
    answer: documentRef.getElementById("result-answer"),
    answerText: documentRef.getElementById("answer-text"),
    metadata: documentRef.getElementById("provider-metadata"),
    contextDiagnostics: documentRef.getElementById("context-diagnostics"),
    contextDiagnosticsList: documentRef.getElementById("context-diagnostics-list"),
    aiEgress: documentRef.getElementById("ai-egress"),
    aiEgressToggle: documentRef.getElementById("ai-egress-toggle"),
    aiEgressLabel: documentRef.getElementById("ai-egress-label"),
    aiEgressDetail: documentRef.getElementById("ai-egress-detail"),
    citations: documentRef.getElementById("citations"),
    citationCount: documentRef.getElementById("citation-count"),
    toProposal: documentRef.getElementById("ask-to-proposal"),
    toProposalHint: documentRef.getElementById("ask-to-proposal-hint"),
    viewRetrieval: documentRef.getElementById("ask-view-retrieval"),
    sourcePreview: documentRef.getElementById("ask-source-preview"),
    sourcePreviewLoading: documentRef.getElementById("ask-source-preview-loading"),
    sourcePreviewMeta: documentRef.getElementById("ask-source-preview-meta"),
    sourcePreviewBody: documentRef.getElementById("ask-source-preview-body"),
    sourcePreviewClose: documentRef.getElementById("ask-source-preview-close"),
    sourcePreviewError: documentRef.getElementById("ask-source-preview-error"),
    sourcePreviewErrorTitle: documentRef.getElementById("ask-source-preview-error-title"),
    sourcePreviewErrorMessage: documentRef.getElementById("ask-source-preview-error-message"),
    sourcePreviewNotFound: documentRef.getElementById("ask-source-preview-not-found"),
    sourcePreviewNotFoundTitle: documentRef.getElementById("ask-source-preview-not-found-title"),
    sourcePreviewNotFoundMessage: documentRef.getElementById("ask-source-preview-not-found-message")
  };
}

export function createAskController(elements, fetchImpl = fetch, documentRef = document) {
  let inFlight = false;
  let proposalInFlight = false;
  let lastGroundedSubmission = null;
  let requestedDocumentId = null;
  let activeDocumentId = null;
  let workspaceEpoch = 0;
  const submitLabel = elements.submit.textContent || "取得回答";

  function routeDocumentId() {
    const view = documentRef.defaultView;
    const hash = view && view.location ? String(view.location.hash || "") : "";
    const query = hash.includes("?") ? hash.slice(hash.indexOf("?") + 1) : "";
    const raw = new URLSearchParams(query).get("documentId");
    if (!raw || !/^[1-9][0-9]*$/.test(raw)) return null;
    const value = Number(raw);
    return Number.isSafeInteger(value) ? value : null;
  }

  function renderScope(label, available = true) {
    if (!elements.documentScope || !elements.documentScopeLabel) return;
    elements.documentScope.hidden = false;
    elements.documentScopeLabel.textContent = available
      ? `目前針對：${label}` : "指定的文件目前無法使用";
  }

  function clearScope({ updateHash = true, invalidate = true } = {}) {
    if (invalidate && (requestedDocumentId !== null || activeDocumentId !== null)) {
      workspaceEpoch += 1;
    }
    requestedDocumentId = null;
    activeDocumentId = null;
    if (elements.documentScope) elements.documentScope.hidden = true;
    if (elements.documentScopeLabel) elements.documentScopeLabel.textContent = "";
    if (updateHash) {
      const view = documentRef.defaultView;
      if (view && view.location && String(view.location.hash || "").startsWith("#/ask")) {
        view.location.hash = "#/ask";
      }
    }
  }

  async function loadDocumentScope() {
    const documentId = routeDocumentId();
    if (documentId === null) {
      clearScope({ updateHash: false });
      return;
    }
    if (requestedDocumentId !== documentId) {
      workspaceEpoch += 1;
    }
    const epoch = workspaceEpoch;
    requestedDocumentId = documentId;
    activeDocumentId = null;
    renderScope("正在確認文件…");
    try {
      const response = await fetchImpl(`/api/v1/inbox/documents/${documentId}`, {
        headers: { Accept: "application/json" }
      });
      const payload = await response.json();
      if (epoch !== workspaceEpoch || requestedDocumentId !== documentId) return;
      const row = payload && payload.data;
      const ready = response.ok && row && row.documentId === documentId
        && row.usability && row.usability.status === "READY_TO_USE"
        && row.usability.searchReady === true;
      if (!ready) {
        renderScope("", false);
        return;
      }
      activeDocumentId = documentId;
      renderScope(row.fileName || row.originalFileName || `文件 ${documentId}`);
    } catch {
      if (epoch === workspaceEpoch && requestedDocumentId === documentId) {
        renderScope("", false);
      }
    }
  }

  if (elements.aiEgressToggle && elements.aiEgressDetail) {
    elements.aiEgressToggle.addEventListener("click", () => {
      const expanded = elements.aiEgressToggle.getAttribute("aria-expanded") === "true";
      elements.aiEgressToggle.setAttribute("aria-expanded", expanded ? "false" : "true");
      elements.aiEgressDetail.hidden = expanded;
    });
  }

  async function submit(event) {
    event.preventDefault();
    if (inFlight) return;
    const refreshEgress = () => loadAiEgress(elements, fetchImpl, documentRef);

    const question = elements.question.value;
    const validationMessage = validateQuestion(question);
    if (validationMessage) {
      elements.hint.textContent = validationMessage;
      elements.question.focus();
      return;
    }
    if (requestedDocumentId !== null && activeDocumentId !== requestedDocumentId) {
      elements.hint.textContent = "指定的文件尚未通過可用性確認，請改問整個知識庫或重新選擇文件。";
      return;
    }

    inFlight = true;
    elements.hint.textContent = "正在搜尋並整理回答…";
    elements.submit.disabled = true;
    elements.submit.textContent = "處理中…";
    elements.result.setAttribute("aria-busy", "true");
    const submissionEpoch = workspaceEpoch;
    try {
      const requestBody = {
        question: question.trim(),
        retrievalMode: elements.retrievalMode.value
      };
      if (activeDocumentId !== null) requestBody.documentId = activeDocumentId;
      const response = await fetchImpl("/api/v1/ask", {
        method: "POST",
        headers: { "Content-Type": "application/json", Accept: "application/json" },
        body: JSON.stringify(requestBody)
      });
      let payload;
      try {
        payload = await response.json();
      } catch {
        payload = {};
      }
      if (submissionEpoch !== workspaceEpoch) return;
      if (!response.ok || !payload.data) {
        showError(elements, payload.error, documentRef);
      } else {
        lastGroundedSubmission = { question: question.trim(), data: payload.data };
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
      refreshEgress();
    }
  }

  // Explicit Ask -> Proposal hand-off (#374): a separate governed mutation command,
  // never an Ask side effect. Only a grounded answer can be handed off, with a
  // double-submit guard and typed failure display.
  async function proposeFromAnswer() {
    if (proposalInFlight || !lastGroundedSubmission) return;
    const data = lastGroundedSubmission.data;
    const citations = (data.citations || []).map(citation => ({
      evidenceId: citation.citationId,
      kind: citation.evidenceKind === "WIKI" ? "WIKI" : "SOURCE",
      sourceChunkId: citation.provenance && citation.provenance.sourceChunkId,
      wikiPath: citation.provenance && citation.provenance.path,
      wikiRevision: citation.provenance && citation.provenance.revision
    }));
    proposalInFlight = true;
    elements.toProposal.disabled = true;
    elements.toProposalHint.textContent = "建立提案中…";
    try {
      const response = await fetchImpl("/api/v1/ask/proposals", {
        method: "POST",
        headers: { "Content-Type": "application/json", Accept: "application/json" },
        body: JSON.stringify({
          question: lastGroundedSubmission.question,
          answerText: data.answer,
          provider: data.providerMetadata && data.providerMetadata.provider,
          model: data.providerMetadata && data.providerMetadata.model,
          citations
        })
      });
      let envelope;
      try { envelope = await response.json(); } catch { envelope = {}; }
      if (!response.ok) {
        // A failed hand-off stays retryable: only a successful creation pins the button.
        elements.toProposal.disabled = false;
        const code = envelope && envelope.error && envelope.error.code;
        elements.toProposalHint.textContent = code === "ASK_CITATION_INVALID"
          ? "提案建立失敗：引用的證據已失效或不在目前工作區，請重新提問後再試。"
          : "提案建立失敗，請稍後再試。";
        return;
      }
      const duplicate = envelope.data && envelope.data.duplicate;
      elements.toProposalHint.textContent = duplicate
        ? "此結果先前已建立提案，已在審核佇列中。可前往審核工作台繼續。"
        : "提案已建立並進入審核佇列（REVIEW）。後續仍需人工核准與發布。可前往審核工作台繼續。";
      elements.toProposal.disabled = true;
    } catch {
      elements.toProposalHint.textContent = "提案建立失敗，請稍後再試。";
    } finally {
      proposalInFlight = false;
    }
  }

  // Diagnostics hand-off (#375): jump to the retrieval inspector with the same query,
  // so the user can see why this evidence was found. Read-only navigation only.
  function viewRetrievalDiagnostics() {
    const inspectorQuestion = documentRef.getElementById("inspector-question");
    if (inspectorQuestion && lastGroundedSubmission) {
      inspectorQuestion.value = lastGroundedSubmission.question;
    }
    const view = documentRef.defaultView;
    if (view && view.location) {
      view.location.hash = "#/inspect";
    }
  }

  // Inline citation preview (#381): the citation click renders the authoritative
  // locator (same renderer as the diagnostics workspace) directly in the Ask view —
  // the user never has to leave the answer to understand a source. Pure read-only.
  async function openInlinePreview(chunkId) {
    if (!elements.sourcePreview) return;
    elements.sourcePreview.hidden = false;
    await inspectInlineChunk(chunkId);
  }

  async function inspectInlineChunk(chunkId) {
    // Same authoritative renderer as the diagnostics workspace; the inline panel owns a
    // full state surface (result/not-found/error) so typed failures render inline too.
    const inlineElements = {
      result: elements.sourcePreview,
      loading: elements.sourcePreviewLoading,
      error: elements.sourcePreviewError,
      errorTitle: elements.sourcePreviewErrorTitle,
      errorMessage: elements.sourcePreviewErrorMessage,
      notFound: elements.sourcePreviewNotFound,
      notFoundTitle: elements.sourcePreviewNotFoundTitle,
      notFoundMessage: elements.sourcePreviewNotFoundMessage,
      metadata: elements.sourcePreviewMeta,
      preview: elements.sourcePreviewBody
    };
    try {
      await inspectSourceChunk(inlineElements, chunkId, fetchImpl, documentRef);
    } catch {
      elements.sourcePreviewMeta.textContent = "來源預覽暫時無法取得，請稍後再試。";
    }
  }

  // Inline citation interaction (#381): citation "檢視來源位置" buttons render the
  // authoritative locator preview directly in the Ask view.
  if (elements.citations && typeof elements.citations.addEventListener === "function") {
    elements.citations.addEventListener("click", clickEvent => {
      const target = clickEvent.target;
      const chunkId = target && typeof target.getAttribute === "function"
        ? target.getAttribute("data-chunk-id")
        : null;
      if (chunkId) {
        clickEvent.preventDefault();
        return openInlinePreview(chunkId);
      }
    });
  }
  elements.form.addEventListener("submit", submit);
  if (elements.toProposal) {
    elements.toProposal.addEventListener("click", proposeFromAnswer);
  }
  if (elements.viewRetrieval) {
    elements.viewRetrieval.addEventListener("click", viewRetrievalDiagnostics);
  }
  if (elements.sourcePreviewClose) {
    elements.sourcePreviewClose.addEventListener("click", () => {
      elements.sourcePreview.hidden = true;
    });
  }
  if (elements.documentScopeClear) {
    elements.documentScopeClear.addEventListener("click", () => {
      clearScope();
      lastGroundedSubmission = null;
      elements.empty.hidden = false;
      elements.answer.hidden = true;
      elements.insufficient.hidden = true;
      elements.error.hidden = true;
      elements.citations.replaceChildren();
    });
  }
  const view = documentRef.defaultView;
  if (view && typeof view.addEventListener === "function") {
    view.addEventListener("hashchange", loadDocumentScope);
  }
  if (typeof documentRef.addEventListener === "function") {
    documentRef.addEventListener("workspace-changed", () => {
      workspaceEpoch += 1;
      clearScope({ invalidate: false });
      lastGroundedSubmission = null;
      elements.empty.hidden = false;
      elements.answer.hidden = true;
      elements.insufficient.hidden = true;
      elements.error.hidden = true;
      elements.citations.replaceChildren();
    });
  }
  loadDocumentScope();
  return { submit, proposeFromAnswer, viewRetrievalDiagnostics, openInlinePreview,
    loadDocumentScope, clearScope };
}

export function bootstrapAskUi(documentRef = document) {
  const elements = elementsFrom(documentRef);
  if (!elements.form) return null;
  const controller = createAskController(elements, fetch, documentRef);
  loadAiEgress(elements, fetch, documentRef);
  return controller;
}

if (typeof document !== "undefined") bootstrapAskUi();

export { RETRIEVAL_MODES };
