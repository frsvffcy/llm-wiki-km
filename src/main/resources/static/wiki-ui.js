/**
 * Published Wiki workspace (#373): a read-only Browser consumption surface over the
 * existing `/api/v1/wiki` authority projection — paged/type-filtered list, one-page
 * reading with the hash-validated canonical body, and hand-off links into Ask and the
 * diagnostics view. The Browser never decides publish state and never writes anything.
 */

const WIKI_ENDPOINT = "/api/v1/wiki";
const PAGE_SIZE = 20;

export const WIKI_PAGE_TYPES = Object.freeze([
  "CONCEPT", "TECHNOLOGY", "TROUBLESHOOTING", "DECISION", "PROJECT",
  "REFERENCE", "HOWTO", "PERSON", "ORGANIZATION"
]);

const PAGE_TYPE_LABELS = Object.freeze({
  CONCEPT: "概念",
  TECHNOLOGY: "技術",
  TROUBLESHOOTING: "疑難排解",
  DECISION: "決策",
  PROJECT: "專案",
  REFERENCE: "參考",
  HOWTO: "操作指南",
  PERSON: "人物",
  ORGANIZATION: "組織"
});

const ERROR_MESSAGES = Object.freeze({
  NO_ACTIVE_WORKSPACE: ["尚未開啟知識庫", "請先在工作區建立或選擇工作區。"],
  WIKI_PAGE_NOT_FOUND: ["找不到頁面", "此頁面不存在、已刪除或尚未發布。"],
  WIKI_PAGE_UNAVAILABLE: ["頁面內容不可用", "此頁面內容未通過權威內容驗證或暫時無法讀取；以後端狀態為準。"],
  INVALID_REQUEST: ["要求不正確", "請確認篩選條件後再試一次。"]
});

const GENERIC_ERROR = ["Wiki 讀取失敗", "發生未預期的問題，請稍後再試。"];

export function pageTypeLabel(pageType) {
  const key = typeof pageType === "string" ? pageType.toUpperCase() : "";
  return PAGE_TYPE_LABELS[key] || text(pageType);
}

export function wikiErrorMessage(error) {
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

export function renderWikiList(elements, rows, pageMeta, documentRef = document, actions = {}) {
  elements.wikiList.replaceChildren();
  const items = Array.isArray(rows) ? rows : [];
  const meta = pageMeta && typeof pageMeta === "object" ? pageMeta : {};
  elements.wikiPageInfo.textContent = Number.isFinite(meta.totalPages)
    ? `第 ${(meta.number ?? 0) + 1} / ${meta.totalPages} 頁（共 ${meta.totalElements} 頁）` : "";
  elements.wikiPrevPage.disabled = !(meta.number > 0);
  elements.wikiNextPage.disabled = Number.isFinite(meta.totalPages)
    ? !(meta.number < meta.totalPages - 1) : true;
  if (items.length === 0) {
    elements.wikiEmpty.hidden = false;
    return;
  }
  elements.wikiEmpty.hidden = true;
  items.forEach(row => {
    const data = row && typeof row === "object" ? row : {};
    const item = documentRef.createElement("li");
    item.className = "wiki-item";
    appendTextElement(documentRef, item, "p", "wiki-item-title", text(data.title));
    appendTextElement(documentRef, item, "p", "wiki-item-meta",
      `${pageTypeLabel(data.pageType)} · 版本 ${text(data.revision)} · 更新於 ${text(data.updatedAt)}`);
    appendTextElement(documentRef, item, "p", "wiki-item-id", text(data.knowledgeId));
    if (typeof actions.onOpen === "function") {
      const open = documentRef.createElement("button");
      open.type = "button";
      open.className = "wiki-open";
      open.textContent = "閱讀";
      open.addEventListener("click", () => actions.onOpen(data.knowledgeId));
      item.append(open);
    }
    elements.wikiList.append(item);
  });
}

export function renderWikiPage(elements, page, documentRef = document) {
  const data = page && typeof page === "object" ? page : {};
  elements.wikiReadPanel.hidden = false;
  elements.wikiReadMeta.replaceChildren();
  appendTextElement(documentRef, elements.wikiReadMeta, "p", "wiki-read-title", text(data.title));
  appendTextElement(documentRef, elements.wikiReadMeta, "p", "wiki-read-meta",
    `${pageTypeLabel(data.pageType)} · knowledgeId：${text(data.knowledgeId)} · 版本 ${text(data.revision)}`
    + ` · 更新於 ${text(data.updatedAt)}`);
  appendTextElement(documentRef, elements.wikiReadMeta, "p", "wiki-read-hash",
    `contentHash：${text(data.contentHash)}`);
  elements.wikiReadBody.textContent = text(data.markdown);
}

async function readEnvelope(response) {
  try {
    return await response.json();
  } catch {
    return null;
  }
}

export function createWikiController(elements, fetchImpl = fetch, documentRef = document) {
  const state = { page: 0, pageType: "", knowledgeId: null };
  let inFlight = false;

  function showTypedError(error) {
    const { title, message } = wikiErrorMessage(error);
    elements.wikiHint.textContent = `${title}：${message}`;
  }

  function reset() {
    state.page = 0;
    state.pageType = "";
    state.knowledgeId = null;
    elements.typeFilter.value = "";
    elements.wikiList.replaceChildren();
    elements.wikiReadPanel.hidden = true;
    elements.wikiHint.textContent = "";
    elements.wikiPageInfo.textContent = "";
  }

  async function refresh() {
    const params = new URLSearchParams({ page: String(state.page), size: String(PAGE_SIZE) });
    if (state.pageType) params.set("pageType", state.pageType);
    const response = await fetchImpl(`${WIKI_ENDPOINT}?${params.toString()}`);
    const envelope = await readEnvelope(response);
    if (!response.ok) {
      showTypedError(envelope && envelope.error ? envelope.error : undefined);
      return;
    }
    renderWikiList(elements, envelope && envelope.data, envelope && envelope.page,
      documentRef, { onOpen: openPage });
  }

  async function openPage(knowledgeId) {
    state.knowledgeId = knowledgeId;
    elements.wikiHint.textContent = "";
    const response = await fetchImpl(`${WIKI_ENDPOINT}/${encodeURIComponent(knowledgeId)}`);
    const envelope = await readEnvelope(response);
    if (!response.ok) {
      showTypedError(envelope && envelope.error ? envelope.error : undefined);
      return;
    }
    renderWikiPage(elements, envelope.data, documentRef);
  }

  async function applyFilter(event) {
    event.preventDefault();
    state.page = 0;
    state.pageType = elements.typeFilter.value;
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

  function closePage() {
    elements.wikiReadPanel.hidden = true;
    state.knowledgeId = null;
  }

  elements.wikiFilterForm.addEventListener("submit", applyFilter);
  elements.wikiPrevPage.addEventListener("click", prevPage);
  elements.wikiNextPage.addEventListener("click", nextPage);
  elements.wikiReadClose.addEventListener("click", closePage);
  if (documentRef && typeof documentRef.addEventListener === "function") {
    documentRef.addEventListener("workspace-changed", () => {
      reset();
      refresh();
    });
  }
  return { refresh, reset, openPage, applyFilter, nextPage, prevPage, closePage };
}

function elementsFrom(documentRef) {
  const byId = id => documentRef.getElementById(id);
  return {
    wikiFilterForm: byId("wiki-filter-form"),
    typeFilter: byId("wiki-type-filter"),
    wikiList: byId("wiki-list"),
    wikiEmpty: byId("wiki-empty"),
    wikiHint: byId("wiki-hint"),
    wikiPageInfo: byId("wiki-page-info"),
    wikiPrevPage: byId("wiki-prev-page"),
    wikiNextPage: byId("wiki-next-page"),
    wikiReadPanel: byId("wiki-read-panel"),
    wikiReadMeta: byId("wiki-read-meta"),
    wikiReadBody: byId("wiki-read-body"),
    wikiReadClose: byId("wiki-read-close")
  };
}

export function bootstrapWikiUi(documentRef = document) {
  const elements = elementsFrom(documentRef);
  if (!elements.wikiList) return null;
  const controller = createWikiController(elements, fetch, documentRef);
  controller.refresh();
  return controller;
}

if (typeof document !== "undefined") bootstrapWikiUi();
