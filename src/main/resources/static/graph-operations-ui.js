const READINESS_ENDPOINT = "/api/v1/graph/projection/readiness";
const REBUILD_ENDPOINT = "/api/v1/graph/projection/rebuild";
const REPAIR_ENDPOINT = "/api/v1/graph/projection/repair";

const STATUS_COPY = Object.freeze({
  DISABLED: ["已停用", "Graph projection 功能目前已停用。"],
  NOT_CONFIGURED: ["尚未設定", "Graph projection 尚未完成設定。"],
  NOT_READY: ["尚未就緒", "目前沒有可供使用的 Graph projection。"],
  BUILDING: ["重建中", "Graph projection 正在重建，請稍後重新整理。"],
  REPAIRING: ["修復中", "Graph projection 正在修復，請稍後重新整理。"],
  CLEARING: ["處理中", "Graph projection 正在處理維護作業。"],
  READY: ["已就緒", "Graph projection 已通過 readiness 檢查。"],
  FAILED: ["失敗", "Graph projection 目前未通過 readiness 檢查。"],
  DEGRADED: ["已降級", "Graph projection 目前只能部分使用。"],
  STALE: ["已過期", "Graph projection 與目前 canonical 內容不同步。"],
  BACKEND_UNAVAILABLE: ["服務暫時無法使用", "Graph projection 的作業服務目前無法使用。"],
  PROJECTION_INCOMPATIBLE: ["版本不相容", "Graph projection 版本與目前應用程式不相容。"],
  REPAIR_REQUIRED: ["需要修復", "Graph projection 需要明確執行 repair。"]
});

const FAILURE_COPY = Object.freeze({
  GRAPH_CAPABILITY_DISABLED: ["功能已停用", "請由管理者啟用 Graph projection 後再試。"],
  GRAPH_CONFIGURATION_INVALID: ["尚未完成設定", "請由管理者完成 Graph projection 設定後再試。"],
  GRAPH_PROJECTION_NOT_READY: ["投影尚未就緒", "目前無法在尚未就緒的投影上執行這項操作。"],
  GRAPH_PROJECTION_STALE: ["投影已過期", "目前投影與 canonical 內容不同步，請執行 rebuild 或 repair。"],
  GRAPH_PROJECTION_INCOMPATIBLE: ["投影版本不相容", "請執行 rebuild 以建立相容的投影。"],
  GRAPH_CAPABILITY_UNAVAILABLE: ["服務暫時無法使用", "Graph projection 服務目前無法使用，請稍後再試。"],
  GRAPH_BACKEND_LOCKED: ["投影目前被鎖定", "Graph projection 正由其他程序使用，請稍後再試。"],
  GRAPH_FILESYSTEM_UNAVAILABLE: ["儲存空間暫時無法使用", "Graph projection 儲存空間目前無法使用，請稍後再試。"],
  GRAPH_TRANSACTION_FAILURE: ["維護作業未完成", "Graph projection 維護作業未完成，請稍後再試。"],
  GRAPH_BACKEND_FAILURE: ["服務暫時無法使用", "Graph projection 服務發生暫時性問題，請稍後再試。"],
  GRAPH_PROJECTION_CORRUPT: ["投影資料需要重建", "Graph projection 資料可能已損壞，請執行 rebuild。"],
  GRAPH_INVALID_PROJECTION_INPUT: ["投影輸入無效", "無法安全建立 Graph projection，請聯絡管理者。"],
  GRAPH_INVALID_PROVENANCE: ["來源資料無效", "Graph projection 的來源資料未通過安全檢查，請聯絡管理者。"],
  GRAPH_CROSS_WORKSPACE: ["工作區檢查失敗", "Graph projection 工作區範圍未通過安全檢查，請聯絡管理者。"],
  GRAPH_INVALID_TRAVERSAL_BOUNDS: ["作業參數無效", "Graph projection 作業參數未通過安全檢查，請聯絡管理者。"],
  GRAPH_LOCAL_VALIDATION: ["投影檢查失敗", "Graph projection 未通過本機安全檢查，請聯絡管理者。"]
});

const UNKNOWN_STATUS = ["無法確認", "目前無法確認 Graph projection 狀態。"];

function text(value) {
  return value === null || value === undefined ? "" : String(value);
}

function isRecord(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function statusCopy(status) {
  return STATUS_COPY[status] || UNKNOWN_STATUS;
}

function isStatusPayload(payload) {
  const data = payload && payload.data;
  return isRecord(data)
    && typeof data.status === "string"
    && Object.prototype.hasOwnProperty.call(STATUS_COPY, data.status)
    && typeof data.targetGeneration === "number"
    && Number.isFinite(data.targetGeneration)
    && data.targetGeneration >= 0
    && typeof data.appliedGeneration === "number"
    && Number.isFinite(data.appliedGeneration)
    && data.appliedGeneration >= 0;
}

function generationText(value) {
  return typeof value === "number" && Number.isFinite(value) && value >= 0
    ? String(value)
    : "—";
}

function knownFailure(code) {
  return typeof code === "string" && Object.prototype.hasOwnProperty.call(FAILURE_COPY, code)
    ? code
    : null;
}

function failureText(code) {
  const safeCode = knownFailure(code);
  if (!safeCode) return "—";
  return `${FAILURE_COPY[safeCode][0]}（${safeCode}）`;
}

function setText(element, value) {
  element.textContent = text(value);
}

function clearStatusDetails(elements) {
  setText(elements.version, "—");
  setText(elements.generation, "—");
  setText(elements.operation, "—");
  setText(elements.failure, "—");
  setText(elements.failureDiagnostic, "");
}

function renderUnknown(elements, message = UNKNOWN_STATUS[1]) {
  elements.statusBadge.className = "graph-status-badge graph-status--unknown";
  elements.statusBadge.setAttribute("data-status", "UNKNOWN");
  setText(elements.statusBadge, UNKNOWN_STATUS[0]);
  setText(elements.statusMessage, message);
  clearStatusDetails(elements);
}

export function renderGraphReadiness(elements, payload) {
  if (!isStatusPayload(payload)) {
    renderUnknown(elements);
    return false;
  }

  const data = payload.data;
  const [label, message] = statusCopy(data.status);
  elements.statusBadge.className = `graph-status-badge graph-status--${data.status.toLowerCase()}`;
  elements.statusBadge.setAttribute("data-status", data.status);
  setText(elements.statusBadge, label);
  setText(elements.statusMessage, message);
  setText(elements.version, typeof data.projectionVersion === "string"
    ? data.projectionVersion : "—");
  setText(elements.generation,
    `${generationText(data.appliedGeneration)} / ${generationText(data.targetGeneration)}`);
  setText(elements.operation, typeof data.operationKind === "string"
    ? data.operationKind === "REBUILD" ? "重建中" : data.operationKind === "REPAIR" ? "修復中" : "—"
    : "—");

  const currentFailure = knownFailure(data.failureCode);
  const lastFailure = knownFailure(data.lastFailureCode);
  setText(elements.failure, failureText(currentFailure || lastFailure));
  setText(elements.failureDiagnostic, currentFailure && typeof data.failureDiagnostic === "string"
    ? data.failureDiagnostic : "");
  return true;
}

export function graphErrorMessage({ status = 0, code = "", malformed = false, network = false } = {}) {
  const safeCode = knownFailure(code);
  if (safeCode) {
    const [title, message] = FAILURE_COPY[safeCode];
    return { title, message, code: safeCode };
  }
  if (network) {
    return {
      title: "無法連線至 Graph 操作服務",
      message: "目前無法讀取或更新 Graph projection，請確認本機服務後再試。",
      code: "NETWORK_ERROR"
    };
  }
  if (malformed) {
    return {
      title: "Graph 狀態回應無效",
      message: "服務回傳的 Graph projection 狀態無法驗證，請重新整理或稍後再試。",
      code: "MALFORMED_RESPONSE"
    };
  }
  if (status === 409) {
    return {
      title: "目前狀態不允許這項操作",
      message: "Graph projection 尚未符合這項維護作業的條件，請先重新整理狀態。",
      code: "HTTP_CONFLICT"
    };
  }
  if (status === 503) {
    return {
      title: "Graph projection 服務暫時無法使用",
      message: "維護作業未完成，請稍後再試。",
      code: "HTTP_UNAVAILABLE"
    };
  }
  if (status >= 500) {
    return {
      title: "Graph projection 檢查失敗",
      message: "Graph projection 未通過安全檢查，請聯絡管理者。",
      code: "HTTP_SERVER_ERROR"
    };
  }
  return {
    title: "Graph 操作未完成",
    message: "目前無法完成 Graph projection 操作，請稍後再試。",
    code: "HTTP_ERROR"
  };
}

export function renderGraphError(elements, error) {
  const copy = graphErrorMessage(error);
  renderUnknown(elements, copy.message);
  elements.feedback.className = "graph-feedback graph-feedback--error";
  elements.feedback.hidden = false;
  setText(elements.feedback, `${copy.title}：${copy.message}`);
  return copy;
}

function setFeedback(elements, message, kind = "pending") {
  elements.feedback.className = `graph-feedback graph-feedback--${kind}`;
  elements.feedback.hidden = !message;
  setText(elements.feedback, message);
}

function setBusy(elements, busy) {
  elements.panel.setAttribute("aria-busy", String(busy));
  elements.refresh.disabled = busy;
  elements.rebuild.disabled = busy;
  elements.repair.disabled = busy;
}

async function readPayload(response) {
  try {
    return await response.json();
  } catch {
    return null;
  }
}

async function request(fetchImpl, endpoint, method) {
  try {
    const response = await fetchImpl(endpoint, {
      method,
      headers: { Accept: "application/json" }
    });
    const payload = await readPayload(response);
    return { response, payload };
  } catch {
    return { network: true };
  }
}

function requestError(result) {
  if (result.network) return { network: true };
  if (!result.response || !result.response.ok) {
    return {
      status: result.response ? result.response.status : 0,
      code: result.payload && result.payload.error && result.payload.error.code
    };
  }
  if (!isStatusPayload(result.payload)) return { malformed: true };
  return null;
}

function operationEndpoint(kind) {
  return kind === "REBUILD" ? REBUILD_ENDPOINT : REPAIR_ENDPOINT;
}

export function createGraphOperationsController(elements, fetchImpl = fetch) {
  let inFlight = false;

  async function refresh() {
    if (inFlight) return false;
    inFlight = true;
    setBusy(elements, true);
    setFeedback(elements, "正在讀取 Graph projection 狀態…", "pending");
    const result = await request(fetchImpl, READINESS_ENDPOINT, "GET");
    const error = requestError(result);
    if (error) {
      renderGraphError(elements, error);
    } else {
      renderGraphReadiness(elements, result.payload);
      setFeedback(elements, "Graph projection 狀態已更新。", "success");
    }
    inFlight = false;
    setBusy(elements, false);
    return !error;
  }

  async function operate(kind) {
    if (inFlight) return false;
    inFlight = true;
    setBusy(elements, true);
    const label = kind === "REBUILD" ? "rebuild" : "repair";
    setFeedback(elements, `正在執行 ${label}…`, "pending");

    const result = await request(fetchImpl, operationEndpoint(kind), "POST");
    const error = requestError(result);
    if (error) {
      renderGraphError(elements, error);
      inFlight = false;
      setBusy(elements, false);
      return false;
    }

    renderGraphReadiness(elements, result.payload);
    setFeedback(elements, `${label} 已完成，正在確認最新狀態…`, "pending");
    const readiness = await request(fetchImpl, READINESS_ENDPOINT, "GET");
    const readinessError = requestError(readiness);
    if (readinessError) {
      renderGraphError(elements, readinessError);
      inFlight = false;
      setBusy(elements, false);
      return false;
    }

    renderGraphReadiness(elements, readiness.payload);
    setFeedback(elements, `${label} 已完成，Graph projection 狀態已更新。`, "success");
    inFlight = false;
    setBusy(elements, false);
    return true;
  }

  elements.refresh.addEventListener("click", () => { void refresh(); });
  elements.rebuild.addEventListener("click", () => { void operate("REBUILD"); });
  elements.repair.addEventListener("click", () => { void operate("REPAIR"); });

  return Object.freeze({ refresh, rebuild: () => operate("REBUILD"), repair: () => operate("REPAIR") });
}

function elementsFrom(documentRef) {
  return {
    panel: documentRef.getElementById("graph-projection-panel"),
    statusBadge: documentRef.getElementById("graph-status-badge"),
    statusMessage: documentRef.getElementById("graph-status-message"),
    version: documentRef.getElementById("graph-projection-version"),
    generation: documentRef.getElementById("graph-generation"),
    operation: documentRef.getElementById("graph-operation"),
    failure: documentRef.getElementById("graph-failure"),
    failureDiagnostic: documentRef.getElementById("graph-failure-diagnostic"),
    feedback: documentRef.getElementById("graph-feedback"),
    refresh: documentRef.getElementById("graph-refresh"),
    rebuild: documentRef.getElementById("graph-rebuild"),
    repair: documentRef.getElementById("graph-repair")
  };
}

export function bootstrapGraphOperationsUi(documentRef = document, fetchImpl = fetch) {
  const panel = documentRef.getElementById("graph-projection-panel");
  if (!panel) return null;
  const controller = createGraphOperationsController(elementsFrom(documentRef), fetchImpl);
  void controller.refresh();
  return controller;
}

if (typeof document !== "undefined") bootstrapGraphOperationsUi();

export { FAILURE_COPY, READINESS_ENDPOINT, STATUS_COPY };
