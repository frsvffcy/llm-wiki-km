/**
 * 治理工作台（#353）：既有提案審核與 Wiki 草稿生命週期契約的瀏覽器投影，涵蓋
 * 提案清單／詳細資料／狀態決策、草稿建立／預覽／差異／重新產生／標記失效，以及
 * 明確的人工作發布操作。三個生命週期階段分開呈現（提案 ≠ 草稿 ≠ 發布結果）；
 * 本檔案不是變更權威來源：所有轉換都經由既有 REST endpoint，後端回應才是唯一
 * 真實狀態，核准不會自動發布。
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
 * 這裡只提供轉換目標的顯示文字，不是狀態機。哪些轉換可用由後端唯一的領域
 * 權威來源決定，並以每筆提案回應中的附加 `allowedTransitions` 能力投影傳回
 *（#370）。未知或缺少能力時安全拒絕，不顯示變更按鈕。
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
  SUPERSEDED_BY_REGENERATION: "已由重新產生的草稿取代",
  SOURCE_PROPOSAL_INVALID: "來源提案已失效",
  TARGET_CHANGED: "目標頁面已變動"
});

const PUBLISH_OUTCOME_LABELS = Object.freeze({
  "PUBLISHED|CREATED": "已發布：建立新知識頁面",
  "PUBLISHED|MERGED": "已發布：更新既有知識頁面",
  "NO_OP|NO_OP": "無操作：此草稿先前已成功發布"
});

const ERROR_MESSAGES = Object.freeze({
  NO_ACTIVE_WORKSPACE: ["尚未開啟知識庫", "請先在工作區建立或選擇工作區。"],
  KNOWLEDGE_PROPOSAL_NOT_FOUND: ["找不到提案", "指定的提案不存在或已隨文件移除，請重新整理清單。"],
  WIKI_DRAFT_NOT_FOUND: ["找不到知識草稿", "指定的草稿不存在，請重新從提案建立。"],
  WIKI_DRAFT_LIFECYCLE_CONFLICT: ["草稿生命週期衝突", "此操作與草稿目前狀態不符，請重新整理後再試。"],
  WIKI_DRAFT_TARGET_CREATE_TARGET_EXISTS: ["目標頁已存在", "建立新頁的目標檔案已存在，請改用合併流程或重新整理草稿。"],
  WIKI_DRAFT_TARGET_TARGET_FILE_MISSING: ["目標頁不存在", "合併的目標檔案已消失，請重新產生草稿。"],
  WIKI_DRAFT_TARGET_TARGET_NOT_REGULAR_FILE: ["目標不是一般檔案", "合併目標不是一般檔案，請人工檢查。"],
  WIKI_DRAFT_TARGET_TARGET_CONTENT_INVALID: ["目標內容無效", "目標頁面內容未通過驗證，請重新整理草稿。"],
  WIKI_DRAFT_TARGET_TARGET_CONTENT_HASH_MISMATCH: ["目標內容已變動", "目標頁面內容與草稿建立時不一致，請重新產生草稿。"],
  WIKI_PUBLISH_DRAFT_NOT_READY: ["草稿尚未就緒", "發布僅允許於 READY 狀態的草稿。"],
  WIKI_PUBLISH_PROPOSAL_INVALID: ["來源提案已失效", "發布前置檢查發現來源提案已不再是 APPROVED。"],
  WIKI_PUBLISH_TARGET_CONFLICT: ["發布目標衝突", "目標頁面寫入衝突，請重新整理草稿後再試。"],
  WIKI_PUBLISH_TARGET_MISSING: ["發布目標遺失", "合併的目標頁面在發布時已消失。"],
  WIKI_PUBLISH_OPTIMISTIC_LOCK_CONFLICT: ["內容已被他人更新", "目標頁面雜湊與草稿建立時不一致（樂觀鎖衝突），請重新產生草稿。"],
  WIKI_PUBLISH_OPERATION_CONFLICT: ["發布進行中", "此草稿已有進行中的發布作業，請稍後再試。"],
  WIKI_PUBLISH_FILESYSTEM_FAILURE: ["檔案系統寫入失敗", "發布寫入失敗，系統已保留審計紀錄；請稍後重試或聯絡管理者。"],
  WIKI_PUBLISH_CONTENT_VALIDATION_FAILED: ["發布內容驗證失敗", "產生的內容未通過發布驗證，系統已保留審計紀錄。"],
  WIKI_PUBLISH_METADATA_FAILURE: ["資料庫寫入失敗", "發布後的中繼資料寫入失敗，請聯絡管理者。"],
  WIKI_PUBLISH_RECONCILIATION_REQUIRED: ["需要帳務對帳", "發布結果需要人工對帳，請聯絡管理者。"],
  INVALID_REQUEST: ["要求不正確", "此狀態轉換不被允許，或輸入內容有誤。"]
});

const GENERIC_ERROR = ["治理操作失敗", "發生未預期的問題，請稍後再試。"];

// #570：核准後自動草稿準備失敗的 typed recovery 文案（與後端 ProposalAutoDraft
// errorCode 同一集合；此處只呈現，不重做轉換決策）。
const AUTO_DRAFT_ERROR_MESSAGES = Object.freeze({
  AUTO_DRAFT_FAILED: "草稿自動準備失敗，提案已保持核准；可按「建立草稿」重試，不會重複發布。",
  AUTO_DRAFT_PROPOSAL_NOT_APPROVED: "提案狀態尚未就緒，請重新整理後再試。",
  AUTO_DRAFT_UNSUPPORTED_ACTION: "此提案類型不會產生草稿，可直接關閉審核。",
  AUTO_DRAFT_AMBIGUOUS_CANDIDATE_MAPPING: "系統無法自動決定草稿位置，請按「建立草稿」手動選擇類型。",
  AUTO_DRAFT_UNSUPPORTED_CANDIDATE_MAPPING: "系統無法自動決定草稿位置，請按「建立草稿」手動選擇類型。",
  AUTO_DRAFT_INVALID_NORMALIZED_DATA: "提案資料驗證失敗，提案已保持核准；可按「建立草稿」重試或檢查提案內容。",
  AUTO_DRAFT_INVALID_EVIDENCE: "提案證據驗證失敗，提案已保持核准；可按「建立草稿」重試或檢查提案內容。",
  AUTO_DRAFT_UNSAFE_TARGET_REFERENCE: "草稿目標驗證失敗，提案已保持核准；可按「建立草稿」重試並檢查目標。",
  AUTO_DRAFT_PATH_CONTRACT_MISMATCH: "草稿目標驗證失敗，提案已保持核准；可按「建立草稿」重試並檢查目標。"
});

const AUTO_DRAFT_GENERIC_ERROR = "草稿自動準備失敗，提案已保持核准；可按「建立草稿」重試。";

export function autoDraftErrorMessage(code) {
  const key = typeof code === "string" ? code.toUpperCase() : "";
  return AUTO_DRAFT_ERROR_MESSAGES[key] || AUTO_DRAFT_GENERIC_ERROR;
}

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
      `片段 ${text(data2.chunkNo)} · ${text(data2.section)}${data2.headingPath ? ` · ${text(data2.headingPath)}` : ""}`);
    appendTextElement(documentRef, item, "p", "proposal-evidence-content", text(data2.content));
    elements.proposalEvidence.append(item);
  });
  // 僅負責呈現：變更按鈕完全取自回應中的 allowedTransitions 能力投影（#370）。
  // 缺少、格式錯誤或未知能力時安全拒絕，不在使用者端自行發明變更操作。
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
    `草稿 #${text(data.id)}（提案 #${text(data.proposalId)}）`);
  appendTextElement(documentRef, elements.draftMeta, "p", "draft-meta-line",
    `${actionLabel(data.action)} · 狀態 ${draftStatusLabel(data.status)} · 可發布（publishReady）：${data.publishReady ? "是" : "否"}`);
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
    `預覽：${text(data.targetPath)}（內容雜湊 ${text(data.renderedContentHash)}）`);
  const pre = documentRef.createElement("pre");
  pre.className = "draft-markdown";
  pre.textContent = text(data.markdown);
  elements.draftContent.append(pre);
  const evidence = Array.isArray(data.evidence) ? data.evidence : [];
  evidence.forEach(entry => {
    const data2 = entry && typeof entry === "object" ? entry : {};
    appendTextElement(documentRef, elements.draftContent, "p", "draft-preview-evidence",
      `片段 ${text(data2.chunkNo)}：${text(data2.excerpt)}`);
  });
}

export function renderDraftDiff(elements, diff, documentRef = document) {
  const data = diff && typeof diff === "object" ? diff : {};
  elements.draftContent.replaceChildren();
  appendTextElement(documentRef, elements.draftContent, "h4", "draft-content-title",
    `差異：${text(data.targetPath)}（基準 ${text(data.baseContentHash)} → 產生內容 ${text(data.renderedContentHash)}）`);
  const pre = documentRef.createElement("pre");
  pre.className = "draft-diff";
  pre.textContent = text(data.unifiedDiff);
  elements.draftContent.append(pre);
}

export function renderPublishOutcome(elements, envelope, httpStatus, documentRef = document) {
  const data = envelope && typeof envelope.data === "object" ? envelope.data : {};
  elements.publishResult.replaceChildren();
  // 「發布成功」只能以後端回傳的型別化結果為準，不能只看 HTTP 狀態。
  const label = publishOutcomeLabel(data.result, data.outcome);
  const line = appendTextElement(documentRef, elements.publishResult, "p",
    "publish-outcome", label);
  if (data.knowledgeId) {
    appendTextElement(documentRef, elements.publishResult, "p", "publish-meta",
      `knowledgeId：${text(data.knowledgeId)} · 目標：${text(data.targetPath)} · 版本 ${text(data.revision)}`);
  }
  const handoff = documentRef.createElement("a");
  handoff.className = "wiki-handoff";
  handoff.textContent = "查看已發布知識";
  handoff.setAttribute("href", "#/wiki");
  elements.publishResult.append(handoff);
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
    // 工作區隔離：切換工作區後，不保留提案、草稿或發布狀態。
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
    // APPROVED 提案是草稿生命週期的入口。
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
        // 型別化的過時／無效轉換失敗會重新讀取權威提案狀態與能力，
        // 讓過時按鈕立即消失（#370）；重新讀取後再顯示失敗，確保提示仍可見。
        await selectProposal(proposalId);
        showTypedError(envelope && envelope.error ? envelope.error : undefined);
        return;
      }
      // 重新讀取權威狀態；核准本身不會自動發布任何內容。
      const autoDraft = envelope && envelope.data && typeof envelope.data.autoDraft === "object"
        ? envelope.data.autoDraft : null;
      await selectProposal(proposalId);
      await refreshProposals();
      // #570：核准後在同一 task flow 內呈現自動準備的草稿（預覽可見，發布仍需明確人工作動）；
      // 準備失敗時提案保持核准並顯示 typed recovery，不假裝發布。
      if (text(status).toUpperCase() === "APPROVED") {
        await presentAutoDraft(autoDraft);
      }
    } catch {
      showTypedError(undefined);
    } finally {
      inFlight = false;
    }
  }

  async function presentAutoDraft(autoDraft) {
    const draftId = autoDraft && Number.isFinite(Number(autoDraft.draftId))
      ? Number(autoDraft.draftId) : 0;
    if (draftId > 0) {
      elements.draftCreate.hidden = true;
      elements.draftHint.textContent =
        "草稿已自動準備完成，可直接預覽內容；發布仍需你明確確認，不會自動發布。";
      await loadDraft(draftId);
      await showPreview();
      return;
    }
    if (autoDraft && typeof autoDraft.errorCode === "string" && autoDraft.errorCode) {
      // Recovery path：草稿改由既有明確建立入口重試（同一按鈕、同一後端契約）。
      elements.draftCreate.hidden = false;
      elements.draftHint.textContent = autoDraftErrorMessage(autoDraft.errorCode);
    }
  }

  async function createDraft() {
    if (inFlight || !state.proposalId) return;
    inFlight = true;
    elements.draftHint.textContent = "建立草稿中…";
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
    // get() 會在伺服器端重新驗證 READY 草稿：失效或過時草稿會帶著最新狀態回傳，
    // UI 只呈現該權威結果，不呈現過時內容。
    renderDraft(elements, envelope.data, documentRef);
  }

  async function draftAction(pathSuffix, { method = "POST", render } = {}) {
    const response = await fetchImpl(`${DRAFTS_ENDPOINT}/${state.draftId}${pathSuffix}`,
      { method });
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
    await draftAction("/preview", { method: "GET", render: data => renderDraftPreview(elements, data, documentRef) });
  }

  async function showDiff() {
    if (!state.draftId) return;
    await draftAction("/diff", { method: "GET", render: data => renderDraftDiff(elements, data, documentRef) });
  }

  async function invalidateDraft() {
    if (inFlight || !state.draftId) return;
    inFlight = true;
    try {
      const envelope = await draftAction("/invalidate", { method: "POST" });
      if (envelope) {
        elements.draftHint.textContent = "草稿已由人工標記為失效。";
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
      elements.draftHint.textContent = `已重新產生草稿 #${text(envelope.data.id)}。`;
      await loadDraft(state.draftId);
    } catch {
      showTypedError(undefined, elements.draftHint);
    } finally {
      inFlight = false;
    }
  }

  async function publishDraft() {
    // 這是明確的人工作動，並以防重複送出保護；冪等性與正確性由後端負責，
    // 失敗會以型別化錯誤呈現，不會誤顯示為成功。
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
        // 發布失敗時清除先前的成功面板：型別化失敗不可與過時的成功結果並列或遮蓋
        // （challenge 4）。
        elements.publishResult.replaceChildren();
        elements.publishResult.hidden = true;
        showTypedError(envelope && envelope.error ? envelope.error : undefined,
          elements.draftHint);
        return;
      }
      renderPublishOutcome(elements, envelope, response.status, documentRef);
      // 重新讀取權威狀態，不信任使用者端自行設定的 PUBLISHED 標記。
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
