/**
 * Governance workbench (#353): a Browser projection of the existing Proposal review and
 * Wiki Draft lifecycle contracts — proposal list/detail/status decision, draft
 * create/preview/diff/regenerate/invalidate, and the explicit human publish action.
 * Three lifecycle stages are rendered separately (Proposal ≠ Draft ≠ Publish outcome);
 * nothing here is a mutation authority: every transition goes through the existing REST
 * endpoints, backend responses are the only truth, and approval never auto-publishes.
 */

const PROPOSALS_ENDPOINT = "/api/v1/proposals";
const DRAFTS_ENDPOINT = "/api/v1/wiki-drafts";
const PAGE_SIZE = 20;

export const PROPOSAL_STATUSES = Object.freeze(["DRAFT", "REVIEW", "APPROVED", "REJECTED"]);

const PROPOSAL_STATUS_LABELS = Object.freeze({
  DRAFT: "草稿（待送審）",
  REVIEW: "審核中",
  APPROVED: "已核准",
  REJECTED: "已拒絕"
});

const ACTION_LABELS = Object.freeze({
  CREATE: "建立新頁",
  MERGE: "合併既有頁",
  LINK_ONLY: "僅建立連結",
  IGNORE: "忽略",
  REVIEW: "需人工複核"
});

/**
 * Presentation copy for transition targets only — NOT a state machine. Which
 * transitions exist is decided by the backend's single domain authority and arrives
 * as the additive `allowedTransitions` capability projection on every proposal
 * response (#370). Unknown or missing capabilities fail closed: no mutation buttons.
 */
const TRANSITION_LABELS = Object.freeze({
  REVIEW: "送入審核",
  APPROVED: "核准",
  REJECTED: "拒絕"
});

const DRAFT_STATUS_LABELS = Object.freeze({
  DRAFT: "草稿",
  READY: "就緒（可發布）",
  PUBLISHED: "已發布",
  INVALIDATED: "已失效"
});

const INVALIDATION_LABELS = Object.freeze({
  MANUAL: "人工失效",
  SUPERSEDED_BY_REGENERATION: "已由重新產生的 draft 取代",
  SOURCE_PROPOSAL_INVALID: "來源 proposal 已失效",
  TARGET_CHANGED: "目標頁面已變動"
});

const PUBLISH_OUTCOME_LABELS = Object.freeze({
  "PUBLISHED|CREATED": "已發布：新建 wiki 頁面",
  "PUBLISHED|MERGED": "已發布：合併至既有頁面",
  "NO_OP|NO_OP": "無操作：此 draft 先前已成功發布"
});

const ERROR_MESSAGES = Object.freeze({
  NO_ACTIVE_WORKSPACE: ["尚未開啟知識庫", "請先在工作區建立或選擇 workspace。"],
  KNOWLEDGE_PROPOSAL_NOT_FOUND: ["找不到 Proposal", "指定的 proposal 不存在或已隨文件移除，請重新整理清單。"],
  WIKI_DRAFT_NOT_FOUND: ["找不到 Wiki Draft", "指定的 draft 不存在，請重新從 proposal 建立。"],
  WIKI_DRAFT_LIFECYCLE_CONFLICT: ["Draft 生命週期衝突", "此操作與 draft 目前狀態不符，請重新整理後再試。"],
  WIKI_DRAFT_TARGET_CREATE_TARGET_EXISTS: ["目標頁已存在", "建立新頁的目標檔案已存在，請改用合併流程或重新整理 draft。"],
  WIKI_DRAFT_TARGET_TARGET_FILE_MISSING: ["目標頁不存在", "合併的目標檔案已消失，請重新產生 draft。"],
  WIKI_DRAFT_TARGET_TARGET_NOT_REGULAR_FILE: ["目標不是一般檔案", "合併目標不是一般檔案，請人工檢查。"],
  WIKI_DRAFT_TARGET_TARGET_CONTENT_INVALID: ["目標內容無效", "目標頁面內容未通過驗證，請重新整理 draft。"],
  WIKI_DRAFT_TARGET_TARGET_CONTENT_HASH_MISMATCH: ["目標內容已變動", "目標頁面內容與 draft 建立時不一致，請重新產生 draft。"],
  WIKI_PUBLISH_DRAFT_NOT_READY: ["Draft 尚未就緒", "publish 僅允許於 READY 狀態的 draft。"],
  WIKI_PUBLISH_PROPOSAL_INVALID: ["來源 Proposal 已失效", "publish 前置檢查發現來源 proposal 已不再是 APPROVED。"],
  WIKI_PUBLISH_TARGET_CONFLICT: ["發布目標衝突", "目標頁面寫入衝突，請重新整理 draft 後再試。"],
  WIKI_PUBLISH_TARGET_MISSING: ["發布目標遺失", "合併的目標頁面在發布時已消失。"],
  WIKI_PUBLISH_OPTIMISTIC_LOCK_CONFLICT: ["內容已被他人更新", "目標頁面雜湊與 draft 建立時不一致（樂觀鎖衝突），請重新產生 draft。"],
  WIKI_PUBLISH_OPERATION_CONFLICT: ["發布進行中", "此 draft 已有進行中的發布作業，請稍後再試。"],
  WIKI_PUBLISH_FILESYSTEM_FAILURE: ["檔案系統寫入失敗", "發布寫入失敗，系統已保留審計紀錄；請稍後重試或聯絡管理者。"],
  WIKI_PUBLISH_CONTENT_VALIDATION_FAILED: ["發布內容驗證失敗", "產生的內容未通過發布驗證，系統已保留審計紀錄。"],
  WIKI_PUBLISH_METADATA_FAILURE: ["資料庫寫入失敗", "發布後的中繼資料寫入失敗，請聯絡管理者。"],
  WIKI_PUBLISH_RECONCILIATION_REQUIRED: ["需要帳務對帳", "發布結果需要人工對帳，請聯絡管理者。"],
  INVALID_REQUEST: ["要求不正確", "此狀態轉換不被允許，或輸入內容有誤。"]
});

const GENERIC_ERROR = ["治理操作失敗", "發生未預期的問題，請稍後再試。"];

export function proposalStatusLabel(status) {
  const key = typeof status === "string" ? status.toUpperCase() : "";
  return PROPOSAL_STATUS_LABELS[key] || text(status);
}

export function draftStatusLabel(status) {
  const key = typeof status === "string" ? status.toUpperCase() : "";
  return DRAFT_STATUS_LABELS[key] || text(status);
}

export function actionLabel(action) {
  const key = typeof action === "string" ? action.toUpperCase() : "";
  return ACTION_LABELS[key] || text(action);
}

export function invalidationLabel(reason) {
  const key = typeof reason === "string" ? reason.toUpperCase() : "";
  return INVALIDATION_LABELS[key] || text(reason);
}

export function publishOutcomeLabel(result, outcome) {
  const key = `${text(result).toUpperCase()}|${text(outcome).toUpperCase()}`;
  return PUBLISH_OUTCOME_LABELS[key] || `發布結果：${text(result)} / ${text(outcome)}`;
}

export function governanceErrorMessage(error) {
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

export function renderProposalList(elements, rows, pageMeta, documentRef = document,
                                    actions = {}) {
  elements.proposalList.replaceChildren();
  const items = Array.isArray(rows) ? rows : [];
  const meta = pageMeta && typeof pageMeta === "object" ? pageMeta : {};
  elements.proposalPageInfo.textContent = Number.isFinite(meta.totalPages)
    ? `第 ${(meta.number ?? 0) + 1} / ${meta.totalPages} 頁（共 ${meta.totalElements} 筆）` : "";
  elements.proposalPrevPage.disabled = !(meta.number > 0);
  elements.proposalNextPage.disabled = Number.isFinite(meta.totalPages)
    ? !(meta.number < meta.totalPages - 1) : true;
  if (items.length === 0) {
    elements.proposalEmpty.hidden = false;
    return;
  }
  elements.proposalEmpty.hidden = true;
  items.forEach(row => {
    const data = row && typeof row === "object" ? row : {};
    const item = documentRef.createElement("li");
    item.className = "proposal-item";
    appendTextElement(documentRef, item, "p", "proposal-title", `#${text(data.id)} ${text(data.title)}`);
    const badge = appendTextElement(documentRef, item, "span",
      `status-badge status-badge--proposal-${text(data.status).toLowerCase() || "unknown"}`,
      proposalStatusLabel(data.status));
    badge.setAttribute("data-status", text(data.status));
    appendTextElement(documentRef, item, "p", "proposal-item-meta",
      `${actionLabel(data.action)} · 信心 ${text(data.confidence)}`);
    if (typeof actions.onSelect === "function") {
      const open = documentRef.createElement("button");
      open.type = "button";
      open.className = "proposal-open";
      open.textContent = "檢視並審核";
      open.addEventListener("click", () => actions.onSelect(data.id));
      item.append(open);
    }
    elements.proposalList.append(item);
  });
}

export function renderProposalDetail(elements, detail, documentRef = document,
                                      { onTransition } = {}) {
  const data = detail && typeof detail === "object" ? detail : {};
  elements.proposalDetail.hidden = false;
  elements.proposalDetailTitle.textContent = `#${text(data.id)} ${text(data.title)}`;
  elements.proposalDetailMeta.textContent =
    `${proposalStatusLabel(data.status)} · ${actionLabel(data.action)} · 信心 ${text(data.confidence)}`;
  elements.proposalDetailSummary.textContent = text(data.summary);
  elements.proposalDetailRationale.textContent = text(data.rationale);
  elements.proposalDetailTarget.textContent = `目標：${text(data.targetReference)}`;
  const source = data.sourceDocument && typeof data.sourceDocument === "object" ? data.sourceDocument : {};
  elements.proposalDetailSource.textContent = `來源文件：${text(source.fileName)}`;
  elements.proposalEvidence.replaceChildren();
  const evidence = Array.isArray(data.evidence) ? data.evidence : [];
  evidence.forEach(entry => {
    const data2 = entry && typeof entry === "object" ? entry : {};
    const item = documentRef.createElement("li");
    item.className = "proposal-evidence-item";
    appendTextElement(documentRef, item, "p", "proposal-evidence-meta",
      `chunk ${text(data2.chunkNo)} · ${text(data2.section)}${data2.headingPath ? ` · ${text(data2.headingPath)}` : ""}`);
    appendTextElement(documentRef, item, "p", "proposal-evidence-content", text(data2.content));
    elements.proposalEvidence.append(item);
  });
  // Render-only: the mutation buttons come exclusively from the response's
  // allowedTransitions capability projection (#370). Missing/malformed/unknown
  // capabilities fail closed — no mutation action is invented client-side.
  elements.proposalActions.replaceChildren();
  const allowed = Array.isArray(data.allowedTransitions) ? data.allowedTransitions : [];
  allowed.forEach(target => {
    const next = typeof target === "string" ? target.toUpperCase() : "";
    if (!PROPOSAL_STATUSES.includes(next)) {
      return;
    }
    const button = documentRef.createElement("button");
    button.type = "button";
    button.className = "proposal-transition";
    button.textContent = TRANSITION_LABELS[next] || next;
    button.addEventListener("click", () => {
      if (typeof onTransition === "function") onTransition(data.id, next);
    });
    elements.proposalActions.append(button);
  });
  elements.proposalDetail.hidden = false;
}

export function renderDraft(elements, draft, documentRef = document) {
  const data = draft && typeof draft === "object" ? draft : {};
  elements.draftPanel.hidden = false;
  elements.draftMeta.replaceChildren();
  appendTextElement(documentRef, elements.draftMeta, "p", "draft-meta-line",
    `Draft #${text(data.id)}（proposal #${text(data.proposalId)}）`);
  appendTextElement(documentRef, elements.draftMeta, "p", "draft-meta-line",
    `${actionLabel(data.action)} · 狀態 ${draftStatusLabel(data.status)} · publishReady：${data.publishReady ? "是" : "否"}`);
  appendTextElement(documentRef, elements.draftMeta, "p", "draft-meta-line",
    `目標：${text(data.targetPath)}${data.targetKnowledgeId ? `（${text(data.targetKnowledgeId)}）` : "（新建）"}`);
  if (data.invalidatedReason) {
    appendTextElement(documentRef, elements.draftMeta, "p", "draft-invalidated",
      `已失效：${invalidationLabel(data.invalidatedReason)}`);
  }
  const status = text(data.status).toUpperCase();
  elements.draftPublish.disabled = !(status === "READY" && data.publishReady === true);
  elements.draftInvalidate.disabled = !(status === "DRAFT" || status === "READY");
  elements.draftRegenerate.disabled = status === "PUBLISHED";
}

export function renderDraftPreview(elements, preview, documentRef = document) {
  const data = preview && typeof preview === "object" ? preview : {};
  elements.draftContent.replaceChildren();
  appendTextElement(documentRef, elements.draftContent, "h4", "draft-content-title",
    `Preview：${text(data.targetPath)}（rendered hash ${text(data.renderedContentHash)}）`);
  const pre = documentRef.createElement("pre");
  pre.className = "draft-markdown";
  pre.textContent = text(data.markdown);
  elements.draftContent.append(pre);
  const evidence = Array.isArray(data.evidence) ? data.evidence : [];
  evidence.forEach(entry => {
    const data2 = entry && typeof entry === "object" ? entry : {};
    appendTextElement(documentRef, elements.draftContent, "p", "draft-preview-evidence",
      `chunk ${text(data2.chunkNo)}：${text(data2.excerpt)}`);
  });
}

export function renderDraftDiff(elements, diff, documentRef = document) {
  const data = diff && typeof diff === "object" ? diff : {};
  elements.draftContent.replaceChildren();
  appendTextElement(documentRef, elements.draftContent, "h4", "draft-content-title",
    `Diff：${text(data.targetPath)}（base ${text(data.baseContentHash)} → rendered ${text(data.renderedContentHash)}）`);
  const pre = documentRef.createElement("pre");
  pre.className = "draft-diff";
  pre.textContent = text(data.unifiedDiff);
  elements.draftContent.append(pre);
}

export function renderPublishOutcome(elements, envelope, httpStatus, documentRef = document) {
  const data = envelope && typeof envelope.data === "object" ? envelope.data : {};
  elements.publishResult.replaceChildren();
  // A publish "success" is only the backend's typed result — never the HTTP status alone.
  const label = publishOutcomeLabel(data.result, data.outcome);
  const line = appendTextElement(documentRef, elements.publishResult, "p",
    "publish-outcome", label);
  if (data.knowledgeId) {
    appendTextElement(documentRef, elements.publishResult, "p", "publish-meta",
      `knowledgeId：${text(data.knowledgeId)} · target：${text(data.targetPath)} · revision ${text(data.revision)}`);
  }
  if (httpStatus === 201) {
    line.className = "publish-outcome publish-outcome--created";
  }
  elements.publishResult.hidden = false;
}

async function readEnvelope(response) {
  try {
    return await response.json();
  } catch {
    return null;
  }
}

export function createReviewController(elements, fetchImpl = fetch, documentRef = document) {
  const state = { page: 0, status: "", proposalId: null, draftId: null };
  let inFlight = false;

  function showTypedError(error, hint = elements.reviewHint) {
    const { title, message } = governanceErrorMessage(error);
    hint.textContent = `${title}：${message}`;
  }

  function reset() {
    // Workspace isolation: no proposal/draft/publish state survives a workspace switch.
    state.page = 0;
    state.status = "";
    state.proposalId = null;
    state.draftId = null;
    elements.statusFilter.value = "";
    elements.proposalList.replaceChildren();
    elements.proposalDetail.hidden = true;
    elements.draftPanel.hidden = true;
    elements.draftContent.replaceChildren();
    elements.publishResult.hidden = true;
    elements.publishResult.replaceChildren();
    elements.reviewHint.textContent = "";
    elements.proposalPageInfo.textContent = "";
  }

  async function refreshProposals() {
    const params = new URLSearchParams({ page: String(state.page), size: String(PAGE_SIZE) });
    if (state.status) params.set("status", state.status);
    const response = await fetchImpl(`${PROPOSALS_ENDPOINT}?${params.toString()}`);
    const envelope = await readEnvelope(response);
    if (!response.ok) {
      showTypedError(envelope && envelope.error ? envelope.error : undefined);
      return;
    }
    renderProposalList(elements, envelope && envelope.data, envelope && envelope.page,
      documentRef, { onSelect: selectProposal });
  }

  async function applyFilter(event) {
    event.preventDefault();
    state.page = 0;
    state.status = elements.statusFilter.value;
    await refreshProposals();
  }

  async function nextPage() {
    state.page += 1;
    await refreshProposals();
  }

  async function prevPage() {
    state.page = Math.max(0, state.page - 1);
    await refreshProposals();
  }

  async function selectProposal(proposalId) {
    state.proposalId = proposalId;
    elements.reviewHint.textContent = "";
    const response = await fetchImpl(`${PROPOSALS_ENDPOINT}/${proposalId}`);
    const envelope = await readEnvelope(response);
    if (!response.ok) {
      showTypedError(envelope && envelope.error ? envelope.error : undefined);
      return;
    }
    renderProposalDetail(elements, envelope.data, documentRef, {
      onTransition: transitionProposal
    });
    // An APPROVED proposal is the entry point into the draft lifecycle.
    elements.draftCreate.hidden = text(envelope.data.status).toUpperCase() !== "APPROVED";
    if (text(envelope.data.status).toUpperCase() !== "APPROVED") {
      elements.draftPanel.hidden = true;
    }
  }

  async function transitionProposal(proposalId, status) {
    if (inFlight) return;
    inFlight = true;
    try {
      const response = await fetchImpl(`${PROPOSALS_ENDPOINT}/${proposalId}/status`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ status })
      });
      const envelope = await readEnvelope(response);
      if (!response.ok) {
        // A typed stale/invalid transition failure re-reads the authoritative
        // proposal state and capability so stale buttons disappear immediately (#370);
        // the typed failure is surfaced after the re-read so it stays visible.
        await selectProposal(proposalId);
        showTypedError(envelope && envelope.error ? envelope.error : undefined);
        return;
      }
      // Re-read authoritative state; approval never publishes anything by itself.
      await selectProposal(proposalId);
      await refreshProposals();
    } catch {
      showTypedError(undefined);
    } finally {
      inFlight = false;
    }
  }

  async function createDraft() {
    if (inFlight || !state.proposalId) return;
    inFlight = true;
    elements.draftHint.textContent = "建立 draft 中…";
    try {
      const response = await fetchImpl(DRAFTS_ENDPOINT, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ proposalId: state.proposalId })
      });
      const envelope = await readEnvelope(response);
      if (!response.ok) {
        showTypedError(envelope && envelope.error ? envelope.error : undefined,
          elements.draftHint);
        return;
      }
      state.draftId = envelope.data.id;
      elements.draftCreate.hidden = true;
      await loadDraft(state.draftId);
    } catch {
      showTypedError(undefined, elements.draftHint);
    } finally {
      inFlight = false;
    }
  }

  async function loadDraft(draftId) {
    state.draftId = draftId;
    const response = await fetchImpl(`${DRAFTS_ENDPOINT}/${draftId}`);
    const envelope = await readEnvelope(response);
    if (!response.ok) {
      showTypedError(envelope && envelope.error ? envelope.error : undefined,
        elements.draftHint);
      return;
    }
    // get() re-validates READY drafts server-side: an invalidated/stale draft comes
    // back with its updated status — the UI renders that truth, never stale content.
    renderDraft(elements, envelope.data, documentRef);
  }

  async function draftAction(pathSuffix, { status = 200, render } = {}) {
    const response = await fetchImpl(`${DRAFTS_ENDPOINT}/${state.draftId}${pathSuffix}`,
      { method: "POST" });
    const envelope = await readEnvelope(response);
    if (!response.ok) {
      showTypedError(envelope && envelope.error ? envelope.error : undefined,
        elements.draftHint);
      return null;
    }
    if (render) {
      render(envelope.data);
    }
    return envelope;
  }

  async function showPreview() {
    if (!state.draftId) return;
    await draftAction("/preview", { render: data => renderDraftPreview(elements, data, documentRef) });
  }

  async function showDiff() {
    if (!state.draftId) return;
    await draftAction("/diff", { render: data => renderDraftDiff(elements, data, documentRef) });
  }

  async function invalidateDraft() {
    if (inFlight || !state.draftId) return;
    inFlight = true;
    try {
      const envelope = await draftAction("/invalidate");
      if (envelope) {
        elements.draftHint.textContent = "Draft 已人工失效。";
        await loadDraft(state.draftId);
      }
    } finally {
      inFlight = false;
    }
  }

  async function regenerateDraft() {
    if (inFlight || !state.draftId) return;
    inFlight = true;
    try {
      const response = await fetchImpl(`${DRAFTS_ENDPOINT}/${state.draftId}/regenerate`,
        { method: "POST" });
      const envelope = await readEnvelope(response);
      if (!response.ok) {
        showTypedError(envelope && envelope.error ? envelope.error : undefined,
          elements.draftHint);
        return;
      }
      state.draftId = envelope.data.id;
      elements.draftHint.textContent = `已重新產生 draft #${text(envelope.data.id)}。`;
      await loadDraft(state.draftId);
    } catch {
      showTypedError(undefined, elements.draftHint);
    } finally {
      inFlight = false;
    }
  }

  async function publishDraft() {
    // Explicit human action with a double-submit guard; the backend owns idempotency
    // and correctness, and failures surface as typed errors — never as success.
    if (inFlight || !state.draftId) return;
    inFlight = true;
    const label = elements.draftPublish.textContent;
    elements.draftPublish.disabled = true;
    elements.draftPublish.textContent = "發布中…";
    elements.draftHint.textContent = "";
    try {
      const response = await fetchImpl(`${DRAFTS_ENDPOINT}/${state.draftId}/publish`,
        { method: "POST" });
      const envelope = await readEnvelope(response);
      if (!response.ok && response.status !== 201) {
        // A failed publish clears any previous success panel: a typed failure must
        // never be displayed next to (or behind) a stale success outcome (challenge 4).
        elements.publishResult.replaceChildren();
        elements.publishResult.hidden = true;
        showTypedError(envelope && envelope.error ? envelope.error : undefined,
          elements.draftHint);
        return;
      }
      renderPublishOutcome(elements, envelope, response.status, documentRef);
      // Re-read authoritative state instead of trusting a client-side PUBLISHED flag.
      await loadDraft(state.draftId);
    } catch {
      showTypedError(undefined, elements.draftHint);
    } finally {
      inFlight = false;
      elements.draftPublish.disabled = false;
      elements.draftPublish.textContent = label;
    }
  }

  elements.proposalFilterForm.addEventListener("submit", applyFilter);
  elements.proposalPrevPage.addEventListener("click", prevPage);
  elements.proposalNextPage.addEventListener("click", nextPage);
  elements.proposalDetailClose.addEventListener("click", () => {
    elements.proposalDetail.hidden = true;
  });
  elements.draftCreate.addEventListener("click", createDraft);
  elements.draftPreview.addEventListener("click", showPreview);
  elements.draftDiff.addEventListener("click", showDiff);
  elements.draftRegenerate.addEventListener("click", regenerateDraft);
  elements.draftInvalidate.addEventListener("click", invalidateDraft);
  elements.draftPublish.addEventListener("click", publishDraft);
  if (documentRef && typeof documentRef.addEventListener === "function") {
    documentRef.addEventListener("workspace-changed", () => {
      reset();
      refreshProposals();
    });
  }
  return {
    refreshProposals, applyFilter, selectProposal, transitionProposal, createDraft,
    loadDraft, showPreview, showDiff, invalidateDraft, regenerateDraft, publishDraft,
    reset, nextPage, prevPage
  };
}

function elementsFrom(documentRef) {
  const byId = id => documentRef.getElementById(id);
  return {
    proposalFilterForm: byId("proposal-filter-form"),
    statusFilter: byId("proposal-status-filter"),
    proposalList: byId("proposal-list"),
    proposalEmpty: byId("proposal-empty"),
    reviewHint: byId("review-hint"),
    proposalPageInfo: byId("proposal-page-info"),
    proposalPrevPage: byId("proposal-prev-page"),
    proposalNextPage: byId("proposal-next-page"),
    proposalDetail: byId("proposal-detail"),
    proposalDetailTitle: byId("proposal-detail-title"),
    proposalDetailMeta: byId("proposal-detail-meta"),
    proposalDetailSummary: byId("proposal-detail-summary"),
    proposalDetailRationale: byId("proposal-detail-rationale"),
    proposalDetailTarget: byId("proposal-detail-target"),
    proposalDetailSource: byId("proposal-detail-source"),
    proposalEvidence: byId("proposal-evidence"),
    proposalActions: byId("proposal-actions"),
    proposalDetailClose: byId("proposal-detail-close"),
    draftCreate: byId("draft-create"),
    draftPanel: byId("draft-panel"),
    draftMeta: byId("draft-meta"),
    draftHint: byId("draft-hint"),
    draftPreview: byId("draft-preview"),
    draftDiff: byId("draft-diff"),
    draftRegenerate: byId("draft-regenerate"),
    draftInvalidate: byId("draft-invalidate"),
    draftPublish: byId("draft-publish"),
    draftContent: byId("draft-content"),
    publishResult: byId("publish-result")
  };
}

export function bootstrapReviewUi(documentRef = document) {
  const elements = elementsFrom(documentRef);
  if (!elements.proposalList) return null;
  const controller = createReviewController(elements, fetch, documentRef);
  controller.refreshProposals();
  return controller;
}

if (typeof document !== "undefined") bootstrapReviewUi();
