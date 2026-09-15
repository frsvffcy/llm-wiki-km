/**
 * Browser projection of Document Analysis readiness and job outcome (#448).
 *
 * The backend owns every authority: workspace bootstrap provisions the default
 * `config/prompts/document-analysis.md`, `GET /api/v1/analysis/readiness` reports
 * feature-scoped readiness, and `GET /api/v1/analysis/jobs/{jobId}` reports the
 * persisted job lifecycle with typed prompt failure codes. This module only renders
 * those backend projections as safe text (textContent, never innerHTML) and never
 * derives its own readiness, status machine, or retry/repair decision. A
 * `COMPLETED` job with `failedCount > 0` is always rendered as partial, never as
 * pure success. When the current workspace changes, all state is reset and
 * re-fetched.
 */

export const ANALYSIS_READINESS_ENDPOINT = "/api/v1/analysis/readiness";

export const ANALYSIS_JOB_ERROR_MESSAGES = Object.freeze({
  PARTIAL_FAILURE: "部分項目失敗，請檢查失敗項目的 typed 錯誤碼。",
  PROMPT_CONFIGURATION_FAILED: "文件分析設定載入失敗。",
  PROMPT_TEMPLATE_NOT_FOUND:
    "缺少 prompt 樣板（config/prompts/document-analysis.md），請執行工作區修復或還原該檔案後重試。",
  PROMPT_TEMPLATE_INVALID: "prompt 樣板格式無效，請檢查樣板內容後重試。",
  PROMPT_VARIABLE_MISSING: "prompt 缺少必要變數，請確認樣板包含文件與證據變數後重試。",
  ANALYSIS_SETTING_INVALID: "分析設定無效，請檢查 analysis 設定值後重試。",
  PROVIDER_UNAVAILABLE: "分析服務目前無法使用，請稍後重試。",
  PROVIDER_TIMEOUT: "分析服務回應逾時，請稍後重試。",
  PERSISTENCE_FAILED: "分析結果無法安全保存，請稍後重試。",
  MALFORMED_JSON: "分析回應格式無效。",
  CONTRACT_VALIDATION_FAILED: "分析回應不符合契約。",
  UNKNOWN_ENUM: "分析回應包含不支援的值。",
  ILLEGAL_EVIDENCE: "分析回應引用了無效證據。",
  INSUFFICIENT_EVIDENCE: "分析證據不足。",
  UNEXPECTED_FAILURE: "文件分析發生未預期錯誤。"
});

const READINESS_ERROR_MESSAGES = Object.freeze({
  NO_ACTIVE_WORKSPACE: ["尚未開啟知識庫", "請先在工作區建立或選擇 workspace。"]
});

const GENERIC_READINESS_ERROR = ["無法取得文件分析狀態", "發生未預期的問題，請稍後再試。"];

function text(value) {
  return value === null || value === undefined ? "" : String(value);
}

export function readinessErrorMessage(error) {
  const code = error && typeof error.code === "string" ? error.code : "";
  const [title, message] = READINESS_ERROR_MESSAGES[code] || GENERIC_READINESS_ERROR;
  return { title, message };
}

export function analysisReadinessOutcome(readiness) {
  if (!readiness || typeof readiness !== "object") {
    return { tone: "unknown", label: "無法確認文件分析狀態", detail: "" };
  }
  if (readiness.workspaceReady === false) {
    return { tone: "unavailable", label: "工作區目錄目前無法使用", detail: "" };
  }
  if (readiness.analysisReady === true) {
    const provider = text(readiness.provider);
    const model = text(readiness.model);
    const identity = provider || model ? `（${provider}/${model}）` : "";
    return { tone: "ready", label: `文件分析已就緒${identity}`, detail: "" };
  }
  const promptStatus = typeof readiness.promptStatus === "string"
    ? readiness.promptStatus.toUpperCase() : "";
  if (promptStatus === "MISSING") {
    return {
      tone: "missing",
      label: "文件分析尚未就緒：缺少 prompt 樣板",
      detail: ANALYSIS_JOB_ERROR_MESSAGES.PROMPT_TEMPLATE_NOT_FOUND
    };
  }
  if (promptStatus === "INVALID") {
    const code = typeof readiness.promptErrorCode === "string"
      ? readiness.promptErrorCode.toUpperCase() : "";
    return {
      tone: "invalid",
      label: "文件分析尚未就緒：prompt 樣板無效",
      detail: ANALYSIS_JOB_ERROR_MESSAGES[code] || text(readiness.promptErrorMessage)
    };
  }
  if (readiness.settingsValid === false) {
    return {
      tone: "invalid",
      label: "文件分析尚未就緒：分析設定無效",
      detail: ANALYSIS_JOB_ERROR_MESSAGES.ANALYSIS_SETTING_INVALID
    };
  }
  return { tone: "unknown", label: "文件分析尚未就緒", detail: "" };
}

export function analysisJobOutcome(job) {
  if (!job || typeof job !== "object") {
    return { tone: "unknown", label: "無法確認分析工作狀態", detail: "" };
  }
  const status = typeof job.status === "string" ? job.status.toUpperCase() : "";
  const success = Number(job.successCount);
  const failed = Number(job.failedCount);
  const skipped = Number(job.skippedCount);
  const counts = `成功 ${text(Number.isFinite(success) ? success : 0)}／`
    + `失敗 ${text(Number.isFinite(failed) ? failed : 0)}／`
    + `略過 ${text(Number.isFinite(skipped) ? skipped : 0)}`;
  if (status === "QUEUED") {
    return { tone: "queued", label: "等待執行", detail: "" };
  }
  if (status === "RUNNING") {
    return { tone: "running", label: `執行中（${counts}）`, detail: "" };
  }
  if (status === "COMPLETED") {
    // PARTIAL_FAILURE must stay visible: COMPLETED with failures is partial, never success.
    if (Number.isFinite(failed) && failed > 0) {
      return {
        tone: "partial",
        label: `部分完成：${counts}`,
        detail: text(job.failureSummary) || ANALYSIS_JOB_ERROR_MESSAGES.PARTIAL_FAILURE
      };
    }
    return { tone: "success", label: `已完成：${counts}`, detail: "" };
  }
  if (status === "FAILED") {
    const code = typeof job.failureCode === "string" ? job.failureCode.toUpperCase() : "";
    return {
      tone: "failed",
      label: ANALYSIS_JOB_ERROR_MESSAGES[code] || "文件分析失敗。",
      detail: text(job.failureSummary)
    };
  }
  if (status === "CANCELLED") {
    return { tone: "cancelled", label: "已取消", detail: "" };
  }
  if (status === "PAUSED") {
    return { tone: "paused", label: "已暫停", detail: "" };
  }
  return { tone: "unknown", label: "無法確認分析工作狀態", detail: "" };
}

export function appendTextElement(documentRef, parent, tag, className, value) {
  const element = documentRef.createElement(tag);
  element.className = className;
  element.textContent = value;
  parent.append(element);
  return element;
}

export function renderAnalysisReadiness(elements, readiness, documentRef = document) {
  const outcome = analysisReadinessOutcome(readiness);
  elements.state.textContent = outcome.label;
  elements.state.className = `analysis-readiness analysis-readiness--${outcome.tone}`;
  elements.detail.replaceChildren();
  if (outcome.detail) {
    appendTextElement(documentRef, elements.detail, "p", "analysis-readiness-detail",
      outcome.detail);
  }
  if (readiness && typeof readiness === "object"
      && typeof readiness.promptErrorMessage === "string"
      && readiness.promptErrorMessage
      && outcome.tone === "invalid" && readiness.promptStatus !== undefined
      && !ANALYSIS_JOB_ERROR_MESSAGES[String(readiness.promptErrorCode || "").toUpperCase()]) {
    appendTextElement(documentRef, elements.detail, "p", "analysis-readiness-backend",
      text(readiness.promptErrorMessage));
  }
}

async function readEnvelope(response) {
  try {
    return await response.json();
  } catch {
    return null;
  }
}

export function createAnalysisController(elements, fetchImpl = fetch, documentRef = document) {
  let inFlight = false;

  function showTypedError(error) {
    const { title, message } = readinessErrorMessage(error);
    elements.hint.textContent = `${title}：${message}`;
  }

  function reset() {
    // Workspace isolation: nothing from the previous workspace survives a switch.
    elements.state.textContent = "";
    elements.state.className = "analysis-readiness";
    elements.detail.replaceChildren();
    elements.hint.textContent = "";
  }

  async function refresh() {
    if (inFlight) return;
    inFlight = true;
    elements.hint.textContent = "";
    try {
      const response = await fetchImpl(ANALYSIS_READINESS_ENDPOINT);
      const envelope = await readEnvelope(response);
      if (!response.ok) {
        reset();
        showTypedError(envelope && envelope.error ? envelope.error : undefined);
        return;
      }
      renderAnalysisReadiness(elements, envelope && envelope.data ? envelope.data : null,
        documentRef);
    } catch {
      reset();
      showTypedError(undefined);
    } finally {
      inFlight = false;
    }
  }

  if (elements.refresh) {
    elements.refresh.addEventListener("click", refresh);
  }
  if (documentRef && typeof documentRef.addEventListener === "function") {
    documentRef.addEventListener("workspace-changed", () => {
      reset();
      return refresh();
    });
  }
  return { refresh, reset };
}

function elementsFrom(documentRef) {
  const byId = id => documentRef.getElementById(id);
  return {
    state: byId("analysis-readiness-state"),
    detail: byId("analysis-readiness-detail"),
    hint: byId("analysis-readiness-hint"),
    refresh: byId("analysis-readiness-refresh")
  };
}

export function bootstrapAnalysisUi(documentRef = document) {
  const elements = elementsFrom(documentRef);
  if (!elements.state) return null;
  const controller = createAnalysisController(elements, fetch, documentRef);
  controller.refresh();
  return controller;
}

if (typeof document !== "undefined") bootstrapAnalysisUi();
