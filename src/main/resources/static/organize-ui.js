/**
 * Post-upload organize surface (#569): task-language projection of the backend
 * tag-suggestion contract plus the single human-controlled tag mutation point.
 *
 * Boundaries (mirror the backend KnowledgeOrganizationPolicy, never re-decided here):
 * - suggestions are ephemeral and read-only; this module never persists them,
 *   never derives search readiness, and never touches DB/FS/provider keys;
 * - human tag edits PATCH only REVIEW-stage proposals and flow into subsequently
 *   created drafts; nothing here publishes or invents a transition state machine
 *   (buttons/options render strictly from backend responses, fail closed);
 * - safe DOM only (textContent, no innerHTML), workspace switch resets all state.
 */

export const TAG_SUGGESTIONS_ENDPOINT = "/api/v1/organization/tag-suggestions";
export const PROPOSALS_ENDPOINT = "/api/v1/proposals";

export const CANDIDATE_TYPE_LABELS = Object.freeze({
  CONCEPT: "概念",
  FACT: "事實",
  PROCEDURE: "流程",
  DECISION: "決策",
  REFERENCE: "參考資料"
});

export const FRESHNESS_COPY = Object.freeze({
  CURRENT: ["建議為最新狀態", "系統已依最近一次文件分析整理出以下分類建議，可直接使用或微調標籤。"],
  DOCUMENT_CHANGED_AFTER_ANALYSIS:
    ["建議可能已過期", "文件在分析後曾變動，以下建議僅供參考；重新處理文件後會產生新的建議。"],
  NO_ANALYSIS: ["尚無可整理的建議", "這份文件還沒有完成分析；處理完成後會在這裡顯示分類建議。"],
  UNKNOWN: ["無法確認整理狀態", "請重新整理後再試一次。"]
});

const ERROR_MESSAGES = Object.freeze({
  NO_ACTIVE_WORKSPACE: ["尚未開啟知識庫", "請先在工作區建立或選擇工作區。"],
  DOCUMENT_NOT_FOUND: ["找不到文件", "指定的文件不存在或已移除，請重新整理清單。"],
  KNOWLEDGE_PROPOSAL_NOT_FOUND: ["找不到提案", "對應的審核提案不存在或已不在此工作區。"],
  PROPOSAL_TAGS_NOT_EDITABLE: ["此提案的標籤無法手動調整", "系統維護用的提案標籤由系統管理，請選擇其他審核中的提案。"],
  INVALID_REQUEST: ["要求不正確", "請確認標籤內容後再試一次。"]
});

const GENERIC_ERROR = ["整理失敗", "發生未預期的問題，請稍後再試。"];

function text(value) {
  return value === null || value === undefined ? "" : String(value);
}

export function candidateTypeLabel(candidateType) {
  const key = typeof candidateType === "string" ? candidateType.toUpperCase() : "";
  return CANDIDATE_TYPE_LABELS[key] || text(candidateType);
}

export function freshnessCopy(freshness) {
  const key = typeof freshness === "string" ? freshness.toUpperCase() : "";
  return FRESHNESS_COPY[key] || FRESHNESS_COPY.UNKNOWN;
}

/** Parses human tag input: half/full-width commas, whitespace, ideographic separators; trimmed, empties dropped. */
export function parseTagInput(value) {
  return text(value).split(/[,\s、;；，]+/u).map(part => part.trim()).filter(part => part !== "");
}

export function organizeErrorMessage(code) {
  const key = typeof code === "string" ? code.toUpperCase() : "";
  return ERROR_MESSAGES[key] || GENERIC_ERROR;
}

async function readPayload(response) {
  try {
    return await response.json();
  } catch (error) {
    return {};
  }
}

function throwForPayload(response, payload) {
  const code = payload && payload.error && typeof payload.error.code === "string"
    ? payload.error.code : "";
  const error = new Error(code || `request failed with status ${response.status}`);
  error.status = response.status;
  error.code = code;
  throw error;
}

export async function fetchTagSuggestions(fetchImpl, documentId) {
  const response = await fetchImpl(
    `${TAG_SUGGESTIONS_ENDPOINT}?documentId=${encodeURIComponent(String(documentId))}`);
  const payload = await readPayload(response);
  if (!response.ok || !payload || typeof payload.data !== "object" || payload.data === null) {
    throwForPayload(response, payload);
  }
  return payload.data;
}

export async function fetchReviewProposals(fetchImpl, documentId) {
  const response = await fetchImpl(
    `${PROPOSALS_ENDPOINT}?status=REVIEW&documentId=${encodeURIComponent(String(documentId))}&size=20`);
  const payload = await readPayload(response);
  if (!response.ok || !payload || !Array.isArray(payload.data)) {
    throwForPayload(response, payload);
  }
  return payload.data;
}

export async function saveProposalTags(fetchImpl, proposalId, tags) {
  const response = await fetchImpl(`${PROPOSALS_ENDPOINT}/${encodeURIComponent(String(proposalId))}/tags`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ tags })
  });
  const payload = await readPayload(response);
  if (!response.ok || !payload || typeof payload.data !== "object" || payload.data === null) {
    throwForPayload(response, payload);
  }
  return payload.data;
}

function appendText(documentRef, parent, tag, className, value) {
  const element = documentRef.createElement(tag);
  element.className = className;
  element.textContent = value;
  parent.append(element);
  return element;
}

/**
 * Renders the suggestion projection and the tag editor target list.
 * Pure over the provided elements/document ref: no fetching, no state writes.
 */
export function renderOrganizePanel(elements, payload, proposals, documentRef = document) {
  const data = payload && typeof payload === "object" ? payload : {};
  const [freshTitle, freshDetail] = freshnessCopy(data.freshness);
  elements.freshness.textContent = `${freshTitle}：${freshDetail}`;
  elements.list.replaceChildren();
  const suggestions = Array.isArray(data.suggestions) ? data.suggestions : [];
  if (suggestions.length === 0) {
    elements.empty.hidden = false;
  } else {
    elements.empty.hidden = true;
  }
  suggestions.forEach(suggestion => {
    const row = suggestion && typeof suggestion === "object" ? suggestion : {};
    const item = documentRef.createElement("li");
    item.className = "organize-item";
    appendText(documentRef, item, "p", "organize-item-title", text(row.title));
    const kind = row.suggestedPageType
      ? `系統建議分類：${candidateTypeLabel(row.suggestedPageType)}`
      : `系統辨識為「${candidateTypeLabel(row.candidateType)}」，需要你在審核時決定放置位置`;
    appendText(documentRef, item, "p", "organize-item-kind", kind);
    const tags = Array.isArray(row.tags) ? row.tags : [];
    appendText(documentRef, item, "p", "organize-item-tags",
      tags.length > 0 ? `目前標籤：${tags.map(tag => text(tag)).join("、")}` : "目前還沒有標籤");
    if (row.tagsOrigin === "PROPOSAL") {
      appendText(documentRef, item, "p", "organize-item-origin",
        "標籤來自審核中提案，可在下方調整；調整後需重新產生草稿才會生效");
    }
    elements.list.append(item);
  });
  renderProposalOptions(elements, proposals, documentRef);
}

function renderProposalOptions(elements, proposals, documentRef) {
  elements.proposalSelect.replaceChildren();
  const items = Array.isArray(proposals) ? proposals : [];
  const placeholder = documentRef.createElement("option");
  placeholder.value = "";
  placeholder.textContent = items.length > 0 ? "請選擇要調整標籤的提案" : "目前沒有可調整標籤的審核中提案";
  elements.proposalSelect.append(placeholder);
  items.forEach(proposal => {
    const row = proposal && typeof proposal === "object" ? proposal : {};
    if (!Number.isFinite(Number(row.id))) {
      return;
    }
    const option = documentRef.createElement("option");
    option.value = String(row.id);
    option.textContent = `#${row.id} ${text(row.title)}`;
    elements.proposalSelect.append(option);
  });
  elements.save.disabled = items.length === 0;
}

export function createOrganizeController(elements, fetchImpl = fetch, documentRef = document) {
  let currentDocumentId = null;
  let pending = false;

  function reset() {
    currentDocumentId = null;
    pending = false;
    elements.panel.hidden = true;
    elements.freshness.textContent = "";
    elements.list.replaceChildren();
    elements.empty.hidden = true;
    elements.proposalSelect.replaceChildren();
    elements.tagInput.value = "";
    elements.result.textContent = "";
    elements.result.hidden = true;
    elements.save.disabled = true;
  }

  function showError(error) {
    const [title, message] = organizeErrorMessage(error && error.code);
    elements.result.textContent = `${title}：${message}`;
    elements.result.hidden = false;
  }

  async function refresh() {
    if (currentDocumentId === null || pending) {
      return;
    }
    pending = true;
    elements.save.disabled = true;
    try {
      const [payload, proposals] = await Promise.all([
        fetchTagSuggestions(fetchImpl, currentDocumentId),
        fetchReviewProposals(fetchImpl, currentDocumentId)
      ]);
      // A workspace switch or close during fetch must not paint a foreign document.
      if (currentDocumentId === null) {
        return;
      }
      renderOrganizePanel(elements, payload, proposals, documentRef);
      elements.result.hidden = true;
    } catch (error) {
      showError(error);
    } finally {
      pending = false;
      if (currentDocumentId !== null && elements.proposalSelect.children.length > 1) {
        elements.save.disabled = false;
      }
    }
  }

  async function open(documentId) {
    currentDocumentId = Number(documentId);
    if (!Number.isFinite(currentDocumentId) || currentDocumentId <= 0) {
      reset();
      return;
    }
    elements.panel.hidden = false;
    elements.freshness.textContent = "正在載入整理建議…";
    elements.list.replaceChildren();
    elements.empty.hidden = true;
    elements.result.hidden = true;
    await refresh();
  }

  function close() {
    reset();
  }

  async function save() {
    if (currentDocumentId === null || pending) {
      return;
    }
    const proposalId = Number(elements.proposalSelect.value);
    if (!Number.isFinite(proposalId) || proposalId <= 0) {
      elements.result.textContent = "請先選擇要調整標籤的提案。";
      elements.result.hidden = false;
      return;
    }
    const tags = parseTagInput(elements.tagInput.value);
    pending = true;
    elements.save.disabled = true;
    try {
      await saveProposalTags(fetchImpl, proposalId, tags);
      elements.result.textContent = "標籤已更新；後續建立或重新產生的草稿會使用新標籤，不會直接發布。";
      elements.result.hidden = false;
      pending = false;
      await refresh();
    } catch (error) {
      pending = false;
      showError(error);
      if (currentDocumentId !== null && elements.proposalSelect.children.length > 1) {
        elements.save.disabled = false;
      }
    }
  }

  if (elements.close && typeof elements.close.addEventListener === "function") {
    elements.close.addEventListener("click", close);
  }
  if (elements.save && typeof elements.save.addEventListener === "function") {
    elements.save.addEventListener("click", save);
  }
  return { open, close, save, reset };
}

function elementsFrom(documentRef) {
  const byId = id => documentRef.getElementById(id);
  return {
    panel: byId("organize-panel"),
    close: byId("organize-close"),
    freshness: byId("organize-freshness"),
    empty: byId("organize-empty"),
    list: byId("organize-list"),
    proposalSelect: byId("organize-proposal-select"),
    tagInput: byId("organize-tag-input"),
    save: byId("organize-save"),
    result: byId("organize-result")
  };
}

export function bootstrapOrganizeUi(documentRef = document) {
  const elements = elementsFrom(documentRef);
  if (!elements.panel) return null;
  const controller = createOrganizeController(elements, fetch, documentRef);
  if (documentRef && typeof documentRef.addEventListener === "function") {
    // inbox 的整理入口經此事件開啟（handoff，不共享狀態）；工作區切換清空。
    documentRef.addEventListener("open-organize", event => {
      const documentId = event && event.detail ? event.detail.documentId : null;
      controller.open(documentId);
    });
    documentRef.addEventListener("workspace-changed", () => controller.reset());
  }
  return controller;
}

if (typeof document !== "undefined") bootstrapOrganizeUi();
