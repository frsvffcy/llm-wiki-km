/**
 * Browser projection of Vault Lint triage (#383). Renders the backend-owned read-only
 * finding report (list/filter/detail + authoritative page preview + hand-off links).
 * Nothing here is a lint, link-resolution, currentness, or repair authority: findings
 * arrive sorted from the backend, navigation targets resolve through the existing
 * read APIs, and no mutation affordance exists in this view.
 */

const LINT_ENDPOINT = "/api/v1/vault-lint/findings";
const WIKI_ENDPOINT = "/api/v1/wiki";
const REPAIR_ENDPOINT = "/api/v1/repair/proposals";

export const FINDING_CATEGORIES = Object.freeze(["REFERENCE", "CANONICAL_CONTENT"]);

const CATEGORY_LABELS = Object.freeze({
  REFERENCE: "參照",
  CANONICAL_CONTENT: "內容"
});

export const FINDING_SEVERITIES = Object.freeze(["ERROR", "WARNING"]);

const SEVERITY_LABELS = Object.freeze({
  ERROR: "錯誤",
  WARNING: "警告"
});

const CODE_LABELS = Object.freeze({
  BROKEN_INTERNAL_LINK: "內部連結失效",
  ORPHAN_PAGE: "孤立頁面",
  CANONICAL_CONTENT_INVALID: "內容驗證失敗",
  CANONICAL_CONTENT_UNREADABLE: "內容無法讀取",
  DUPLICATE_IDENTITY: "識別重複",
  DANGLING_PROVENANCE: "來源懸空"
});

const ERROR_MESSAGES = Object.freeze({
  NO_ACTIVE_WORKSPACE: ["尚未開啟工作區", "請先至「工作區」建立或開啟知識庫，再回來重新整理。"],
  WIKI_PAGE_NOT_FOUND: ["頁面已不存在", "該頁面已移出 Published，finding 可能已過時，請重新整理後再判讀。"],
  REPAIR_FINDING_STALE: ["修復對象已變動", "finding 已消失或改變，不會套用舊狀態；已重新讀取最新診斷。"],
  REPAIR_NOT_ELIGIBLE: ["無法修復", "此類 finding 只能分類檢視，不提供修復動作。"]
});

/**
 * Presentation copy for backend refusal reasons only — NOT an eligibility matrix.
 * Whether the repair action exists comes solely from the backend `repairEligible`
 * capability on each finding; unknown codes render raw and never enable repair.
 */
const REPAIR_REFUSAL_LABELS = Object.freeze({
  AMBIGUOUS_TARGET: "連結目標不明確，無法推導修復動作，僅供分類檢視。",
  SEMANTIC_JUDGMENT_REQUIRED: "需要語意判斷，無法自動修復，僅供分類檢視。",
  TARGET_NOT_READABLE: "目標內容無法讀取，僅供分類檢視。",
  AMBIGUOUS_IDENTITY: "識別重複，無法判定修復對象，僅供分類檢視。",
  NO_RESOLVABLE_LINEAGE: "找不到可重建的 governed 來源，僅供分類檢視。"
});

const GENERIC_ERROR = ["讀取失敗", "發生未預期的問題，請稍後再試。"];

export function categoryLabel(category) {
  const key = typeof category === "string" ? category.toUpperCase() : "";
  return CATEGORY_LABELS[key] || text(category);
}

export function severityLabel(severity) {
  const key = typeof severity === "string" ? severity.toUpperCase() : "";
  return SEVERITY_LABELS[key] || text(severity);
}

export function codeLabel(code) {
  const key = typeof code === "string" ? code.toUpperCase() : "";
  return CODE_LABELS[key] || text(code);
}

export function triageErrorMessage(error) {
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

export function repairRefusalLabel(reason) {
  const key = typeof reason === "string" ? reason.toUpperCase() : "";
  return REPAIR_REFUSAL_LABELS[key] || text(reason);
}

export function renderFindingList(documentRef, listElement, findings) {
  listElement.replaceChildren();
  findings.forEach((entry, index) => {
    const finding = entry && entry.finding ? entry.finding : {};
    const item = documentRef.createElement("li");
    item.className = "triage-item";
    appendTextElement(documentRef, item, "p", "triage-item-title",
      `${codeLabel(finding.code)} · ${text(finding.knowledgeId)}`);
    const badgeRow = documentRef.createElement("p");
    badgeRow.className = "triage-item-badges";
    const severityBadge = appendTextElement(documentRef, badgeRow, "span",
      `status-badge status-badge--triage-${text(finding.severity).toLowerCase()}`,
      severityLabel(finding.severity));
    severityBadge.setAttribute("data-status", text(finding.severity));
    const categoryBadge = appendTextElement(documentRef, badgeRow, "span",
      `status-badge status-badge--triage-${text(finding.category).toLowerCase()}`,
      categoryLabel(finding.category));
    categoryBadge.setAttribute("data-status", text(finding.category));
    appendTextElement(documentRef, item, "p", "triage-item-detail", text(finding.detail));
    const open = documentRef.createElement("button");
    open.type = "button";
    open.className = "secondary-button";
    open.textContent = "查看內容與定位";
    open.setAttribute("data-finding-index", String(index));
    item.append(badgeRow, open);
    listElement.append(item);
  });
}

export function renderFindingDetail(documentRef, elements, finding) {
  elements.triageDetailTitle.textContent =
    `${codeLabel(finding.code)} · ${text(finding.knowledgeId)}`;
  elements.triageDetailMeta.textContent =
    `類別：${categoryLabel(finding.category)} · 嚴重度：${severityLabel(finding.severity)} · 路徑：${text(finding.logicalPath)}`;
  elements.triageDetailExplanation.textContent = text(finding.detail);
}

export function renderPagePreview(documentRef, pageElement, page) {
  pageElement.replaceChildren();
  appendTextElement(documentRef, pageElement, "p", "triage-page-title", text(page.title));
  appendTextElement(documentRef, pageElement, "p", "triage-page-meta",
    `類型：${text(page.pageType)} · revision ${text(page.revision)} · knowledgeId：${text(page.knowledgeId)}`);
}

async function readEnvelope(response) {
  try {
    return await response.json();
  } catch {
    return null;
  }
}

export function createQualityController(elements, fetchImpl = fetch, documentRef = document) {
  const state = { category: "", severity: "", findings: [], checkedPageCount: 0, fetchedAt: "", selectedIndex: -1 };
  let repairInFlight = false;

  function showTypedError(error, hint = elements.triageHint) {
    const { title, message } = triageErrorMessage(error);
    hint.textContent = `${title}：${message}`;
  }

  function entryFinding(entry) {
    return entry && entry.finding ? entry.finding : {};
  }

  function visibleFindings() {
    return state.findings.filter(entry => {
      const finding = entryFinding(entry);
      return (state.category === "" || text(finding.category).toUpperCase() === state.category)
        && (state.severity === "" || text(finding.severity).toUpperCase() === state.severity);
    });
  }

  function renderMeta() {
    const when = state.fetchedAt === "" ? "尚未讀取" : `讀取時間：${state.fetchedAt}`;
    elements.triageMeta.textContent =
      `共掃描 ${state.checkedPageCount} 頁 · 符合篩選 ${visibleFindings().length} 項 · ${when}`;
  }

  function renderList() {
    const visible = visibleFindings();
    renderFindingList(documentRef, elements.triageList, visible);
    elements.triageEmpty.hidden = visible.length !== 0;
    renderMeta();
  }

  function reset() {
    // Workspace isolation: no findings/detail/filter state survives a workspace switch.
    state.category = "";
    state.severity = "";
    state.findings = [];
    state.checkedPageCount = 0;
    state.fetchedAt = "";
    state.selectedIndex = -1;
    elements.categoryFilter.value = "";
    elements.severityFilter.value = "";
    elements.triageList.replaceChildren();
    elements.triageDetail.hidden = true;
    elements.triageDetailTitle.textContent = "";
    elements.triageDetailMeta.textContent = "";
    elements.triageDetailExplanation.textContent = "";
    elements.triagePage.replaceChildren();
    elements.triagePageHint.textContent = "";
    elements.triageRepair.hidden = true;
    elements.triageRepairHint.textContent = "";
    elements.triageRefusal.hidden = true;
    elements.triageRefusal.textContent = "";
    elements.triageHint.textContent = "";
    elements.triageMeta.textContent = "";
  }

  async function refresh() {
    elements.triageHint.textContent = "";
    let response;
    try {
      response = await fetchImpl(LINT_ENDPOINT);
    } catch {
      showTypedError(null);
      return;
    }
    const envelope = await readEnvelope(response);
    if (!response.ok) {
      showTypedError(envelope && envelope.error);
      return;
    }
    const data = envelope && envelope.data ? envelope.data : null;
    if (!data || !Array.isArray(data.findings)) {
      showTypedError(null);
      return;
    }
    // Backend order is the deterministic authority; the client never re-sorts.
    state.findings = data.findings;
    state.checkedPageCount = Number.isInteger(data.checkedPageCount) ? data.checkedPageCount : 0;
    state.fetchedAt = new Date().toISOString();
    state.selectedIndex = -1;
    elements.triageDetail.hidden = true;
    renderList();
  }

  function applyFilter() {
    state.category = text(elements.categoryFilter.value).toUpperCase();
    state.severity = text(elements.severityFilter.value).toUpperCase();
    state.selectedIndex = -1;
    elements.triageDetail.hidden = true;
    renderList();
  }

  async function selectFinding(index) {
    const visible = visibleFindings();
    const entry = visible[index];
    if (!entry) return;
    const finding = entryFinding(entry);
    state.selectedIndex = index;
    renderFindingDetail(documentRef, elements, finding);
    renderRepairCapability(entry);
    elements.triageDetail.hidden = false;
    elements.triagePageHint.textContent = "";
    elements.triagePage.replaceChildren();
    let response;
    try {
      response = await fetchImpl(`${WIKI_ENDPOINT}/${encodeURIComponent(text(finding.knowledgeId))}`);
    } catch {
      elements.triagePageHint.textContent = "讀取權威內容失敗，請稍後再試。";
      return;
    }
    const envelope = await readEnvelope(response);
    if (!response.ok) {
      const { title, message } = triageErrorMessage(envelope && envelope.error);
      elements.triagePageHint.textContent = `${title}：${message}`;
      return;
    }
    const page = envelope && envelope.data ? envelope.data : null;
    if (!page || typeof page.title === "undefined") {
      elements.triagePageHint.textContent = "讀取權威內容失敗，請稍後再試。";
      return;
    }
    renderPagePreview(documentRef, elements.triagePage, page);
  }

  function renderRepairCapability(entry) {
    // The repair action exists if and only if the backend capability says so; the
    // refusal reason is presentation copy for an already-decided backend verdict.
    const eligible = entry && entry.repairEligible === true;
    elements.triageRepair.hidden = !eligible;
    elements.triageRepairCreate.disabled = false;
    elements.triageRepairCreate.textContent = "建立修復 Proposal";
    elements.triageRepairHint.textContent = "";
    if (eligible) {
      elements.triageRefusal.hidden = true;
      elements.triageRefusal.textContent = "";
    } else {
      elements.triageRefusal.hidden = false;
      elements.triageRefusal.textContent =
        repairRefusalLabel(entry && entry.repairRefusalReason);
    }
  }

  async function createRepairProposal() {
    // Explicit human action with double-submit guard. Only the canonical identity
    // travels to the backend; finding detail, paths, and repair text are rebuilt
    // server-side at command time and stale states fail closed there.
    if (repairInFlight) return;
    const entry = visibleFindings()[state.selectedIndex];
    const finding = entryFinding(entry);
    if (!entry || entry.repairEligible !== true || text(finding.knowledgeId) === "") return;
    repairInFlight = true;
    elements.triageRepairCreate.disabled = true;
    elements.triageRepairCreate.textContent = "建立修復 Proposal 中…";
    elements.triageRepairHint.textContent = "";
    try {
      const response = await fetchImpl(REPAIR_ENDPOINT, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ knowledgeId: text(finding.knowledgeId) })
      });
      const envelope = await readEnvelope(response);
      if (!response.ok) {
        const { title, message } = triageErrorMessage(envelope && envelope.error);
        elements.triageRepairHint.textContent = `${title}：${message}已重新讀取最新診斷。`;
        await refresh();
        return;
      }
      const duplicate = envelope && envelope.data && envelope.data.duplicate === true;
      elements.triageRepairHint.textContent = duplicate
        ? "已存在相同狀態的修復 Proposal，未重複建立；請至「審核」繼續。"
        : "修復 Proposal 已建立並進入審核；核准後才會產生 Draft，核准不會自動發布。請至「審核」繼續。";
      await refresh();
    } catch {
      elements.triageRepairHint.textContent = "建立修復 Proposal 失敗，請稍後再試。";
    } finally {
      repairInFlight = false;
      elements.triageRepairCreate.disabled = false;
      elements.triageRepairCreate.textContent = "建立修復 Proposal";
    }
  }

  function closeDetail() {
    state.selectedIndex = -1;
    elements.triageDetail.hidden = true;
  }

  if (elements.triageList) {
    elements.triageList.addEventListener("click", event => {
      const target = event && event.target;
      const raw = target && typeof target.getAttribute === "function"
        ? target.getAttribute("data-finding-index") : null;
      if (raw === null || raw === "") return;
      selectFinding(Number(raw));
    });
  }
  if (elements.triageFilterForm) {
    elements.triageFilterForm.addEventListener("submit", event => {
      if (event && typeof event.preventDefault === "function") event.preventDefault();
      applyFilter();
    });
  }
  if (elements.triageRefresh) {
    elements.triageRefresh.addEventListener("click", () => refresh());
  }
  if (elements.triageDetailClose) {
    elements.triageDetailClose.addEventListener("click", () => closeDetail());
  }
  if (elements.triageRepairCreate) {
    elements.triageRepairCreate.addEventListener("click", () => createRepairProposal());
  }
  if (documentRef && typeof documentRef.addEventListener === "function") {
    documentRef.addEventListener("workspace-changed", () => {
      reset();
      refresh();
    });
  }
  return { refresh, applyFilter, selectFinding, closeDetail, createRepairProposal, reset };
}

function elementsFrom(documentRef) {
  const byId = id => documentRef.getElementById(id);
  return {
    triageFilterForm: byId("triage-filter-form"),
    categoryFilter: byId("triage-category-filter"),
    severityFilter: byId("triage-severity-filter"),
    triageRefresh: byId("triage-refresh"),
    triageHint: byId("triage-hint"),
    triageMeta: byId("triage-meta"),
    triageEmpty: byId("triage-empty"),
    triageList: byId("triage-list"),
    triageDetail: byId("triage-detail"),
    triageDetailTitle: byId("triage-detail-title"),
    triageDetailMeta: byId("triage-detail-meta"),
    triageDetailExplanation: byId("triage-detail-explanation"),
    triagePage: byId("triage-page"),
    triagePageHint: byId("triage-page-hint"),
    triageDetailClose: byId("triage-detail-close"),
    triageRepair: byId("triage-repair"),
    triageRepairCreate: byId("triage-repair-create"),
    triageRepairHint: byId("triage-repair-hint"),
    triageRefusal: byId("triage-refusal")
  };
}

export function bootstrapQualityUi(documentRef = document) {
  const elements = elementsFrom(documentRef);
  if (!elements.triageList) return null;
  const controller = createQualityController(elements, fetch, documentRef);
  controller.refresh();
  return controller;
}

if (typeof document !== "undefined") bootstrapQualityUi();
