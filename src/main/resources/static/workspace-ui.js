/**
 * Workspace Browser surface (#352): a first-mile projection of the existing
 * `/api/v1/workspaces` contract — current workspace status, list, create, switch, and
 * the safe repair operation. The Browser never touches the filesystem or database:
 * root paths are supplied by the user exactly as the existing request contract defines
 * them, and every switch/create re-fetches current-scoped state so no previous
 * workspace's data can bleed into other views.
 */

const WORKSPACES_ENDPOINT = "/api/v1/workspaces";
const CURRENT_WORKSPACE_ENDPOINT = "/api/v1/workspaces/current";

const ERROR_MESSAGES = Object.freeze({
  NO_ACTIVE_WORKSPACE: ["尚未開啟知識庫", "建立一個 workspace 或從清單中選擇既有 workspace。"],
  WORKSPACE_ALREADY_EXISTS: ["Workspace 已存在", "同名或同路徑的 workspace 已經存在，請改用名稱或路徑。"],
  WORKSPACE_NOT_FOUND: ["找不到 Workspace", "指定的 workspace 不存在，請重新整理清單。"],
  INVALID_REQUEST: ["輸入不正確", "請確認名稱與 root path 皆已填寫（root path 需為絕對路徑）。"],
  NOT_FOUND: ["找不到資源", "要求的 workspace 不存在，請重新整理。"]
});

const GENERIC_ERROR = ["無法取得 workspace 狀態", "發生未預期的問題，請稍後再試。"];

export function workspaceErrorMessage(error) {
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

export function validateWorkspaceInput(name, rootPath) {
  if (!String(name ?? "").trim()) {
    return "請輸入 workspace 名稱。";
  }
  if (!String(rootPath ?? "").trim()) {
    return "請輸入 root path（絕對路徑）。";
  }
  return null;
}

export function renderCurrentWorkspace(elements, status, documentRef = document) {
  const workspace = status && status.workspace ? status.workspace : null;
  const layout = status && status.layout ? status.layout : {};
  elements.currentEmpty.hidden = Boolean(workspace);
  elements.current.hidden = !workspace;
  elements.repair.hidden = !workspace;
  elements.currentName.textContent = workspace ? text(workspace.name) : "";
  elements.currentPath.textContent = workspace ? text(workspace.rootPath) : "";
  elements.currentStatus.textContent = workspace ? `狀態：${text(workspace.status)}` : "";
  const problems = Array.isArray(layout.problems) ? layout.problems : [];
  const repaired = Array.isArray(layout.repairedDirectories) ? layout.repairedDirectories : [];
  elements.layoutState.textContent = workspace
    ? (layout.valid ? "目錄結構完整" : "目錄結構需要修復")
    : "";
  elements.layoutState.className = workspace
    ? (layout.valid ? "workspace-layout workspace-layout--ok" : "workspace-layout workspace-layout--broken")
    : "workspace-layout";
  elements.layoutDetail.replaceChildren();
  if (problems.length > 0) {
    problems.forEach(problem => {
      appendTextElement(documentRef, elements.layoutDetail, "p", "workspace-problem",
        `問題：${text(problem)}`);
    });
  }
  if (repaired.length > 0) {
    appendTextElement(documentRef, elements.layoutDetail, "p", "workspace-repaired",
      `已自動修復目錄：${repaired.join("、")}`);
  }
}

export function renderWorkspaceList(elements, workspaces, currentWorkspaceId,
                                     documentRef = document, { onSwitch } = {}) {
  elements.list.replaceChildren();
  const rows = Array.isArray(workspaces) ? workspaces : [];
  if (rows.length === 0) {
    appendTextElement(documentRef, elements.list, "li", "workspace-list-empty",
      "目前沒有其他 workspace。");
    return;
  }
  rows.forEach(workspace => {
    const item = documentRef.createElement("li");
    item.className = "workspace-list-item";
    appendTextElement(documentRef, item, "span", "workspace-list-name", text(workspace.name));
    appendTextElement(documentRef, item, "span", "workspace-list-meta", text(workspace.rootPath));
    if (workspace.id === currentWorkspaceId) {
      appendTextElement(documentRef, item, "span", "workspace-list-current", "使用中");
    } else {
      const open = documentRef.createElement("button");
      open.type = "button";
      open.className = "workspace-switch";
      open.textContent = "切換至此前 workspace";
      open.addEventListener("click", () => {
        if (typeof onSwitch === "function") onSwitch(workspace.id);
      });
      item.append(open);
    }
    elements.list.append(item);
  });
}

async function readEnvelope(response) {
  try {
    return await response.json();
  } catch {
    return null;
  }
}

export function createWorkspaceController(elements, fetchImpl = fetch, documentRef = document,
                                          hooks = {}) {
  let inFlight = false;
  const submitLabel = elements.createSubmit.textContent || "建立 workspace";

  function showTypedError(error) {
    const { title, message } = workspaceErrorMessage(error);
    elements.hint.textContent = `${title}：${message}`;
  }

  function announceWorkspaceChanged() {
    if (typeof hooks.onWorkspaceChanged === "function") {
      hooks.onWorkspaceChanged();
    }
  }

  async function refresh() {
    const [currentResponse, listResponse] = await Promise.all([
      fetchImpl(CURRENT_WORKSPACE_ENDPOINT),
      fetchImpl(WORKSPACES_ENDPOINT)
    ]);
    const currentEnvelope = await readEnvelope(currentResponse);
    const listEnvelope = await readEnvelope(listResponse);
    const status = currentResponse.ok && currentEnvelope ? currentEnvelope.data : null;
    const workspaces = listResponse.ok && listEnvelope ? listEnvelope.data : [];
    renderCurrentWorkspace(elements, status, documentRef);
    const currentId = status && status.workspace ? status.workspace.id : null;
    renderWorkspaceList(elements, workspaces, currentId, documentRef,
      { onSwitch: workspaceId => switchTo(workspaceId) });
    elements.hint.textContent = status ? "" : workspaceErrorMessage({ code: "NO_ACTIVE_WORKSPACE" }).message;
    return status;
  }

  async function submitCreate(event) {
    event.preventDefault();
    if (inFlight) return;
    const name = elements.name.value;
    const rootPath = elements.rootPath.value;
    const validationMessage = validateWorkspaceInput(name, rootPath);
    if (validationMessage) {
      elements.hint.textContent = validationMessage;
      return;
    }
    inFlight = true;
    elements.createSubmit.disabled = true;
    elements.createSubmit.textContent = "建立中…";
    elements.hint.textContent = "";
    try {
      const response = await fetchImpl(WORKSPACES_ENDPOINT, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name: name.trim(), rootPath: rootPath.trim() })
      });
      const envelope = await readEnvelope(response);
      if (!response.ok) {
        showTypedError(envelope && envelope.error ? envelope.error : undefined);
        return;
      }
      elements.name.value = "";
      elements.rootPath.value = "";
      // A freshly created workspace becomes active server-side: treat it like a switch.
      announceWorkspaceChanged();
      await refresh();
      elements.hint.textContent = `Workspace「${text(envelope.data.name)}」已建立並啟用。`;
    } catch {
      showTypedError(undefined);
    } finally {
      inFlight = false;
      elements.createSubmit.disabled = false;
      elements.createSubmit.textContent = submitLabel;
    }
  }

  async function switchTo(workspaceId) {
    if (inFlight) return;
    inFlight = true;
    elements.hint.textContent = "";
    try {
      const response = await fetchImpl(CURRENT_WORKSPACE_ENDPOINT, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ workspaceId })
      });
      const envelope = await readEnvelope(response);
      if (!response.ok) {
        showTypedError(envelope && envelope.error ? envelope.error : undefined);
        return;
      }
      announceWorkspaceChanged();
      await refresh();
      elements.hint.textContent = `已切換至 workspace「${text(envelope.data.workspace.name)}」，相關畫面已重新載入。`;
    } catch {
      showTypedError(undefined);
    } finally {
      inFlight = false;
    }
  }

  async function repair() {
    if (inFlight) return;
    inFlight = true;
    elements.hint.textContent = "";
    try {
      const response = await fetchImpl(`${CURRENT_WORKSPACE_ENDPOINT}/repair`, {
        method: "POST"
      });
      const envelope = await readEnvelope(response);
      if (!response.ok) {
        showTypedError(envelope && envelope.error ? envelope.error : undefined);
        return;
      }
      await refresh();
      elements.hint.textContent = "已執行目錄修復。";
    } catch {
      showTypedError(undefined);
    } finally {
      inFlight = false;
    }
  }

  elements.form.addEventListener("submit", submitCreate);
  elements.repair.addEventListener("click", repair);
  return { refresh, submitCreate, switchTo, repair };
}

function elementsFrom(documentRef) {
  const byId = id => documentRef.getElementById(id);
  return {
    form: byId("workspace-create-form"),
    name: byId("workspace-name"),
    rootPath: byId("workspace-root-path"),
    createSubmit: byId("workspace-create-submit"),
    hint: byId("workspace-hint"),
    current: byId("workspace-current"),
    currentEmpty: byId("workspace-current-empty"),
    currentName: byId("workspace-current-name"),
    currentPath: byId("workspace-current-path"),
    currentStatus: byId("workspace-current-status"),
    layoutState: byId("workspace-layout-state"),
    layoutDetail: byId("workspace-layout-detail"),
    list: byId("workspace-list"),
    repair: byId("workspace-repair")
  };
}

export function bootstrapWorkspaceUi(documentRef = document, hooks = {}) {
  const elements = elementsFrom(documentRef);
  if (!elements.form) return null;
  return createWorkspaceController(elements, fetch, documentRef, {
    ...hooks,
    onWorkspaceChanged: () => {
      // The inbox (and future workspace-scoped views) subscribe to this document event
      // so a switch clears and re-fetches everything current-scoped (no cross-
      // workspace bleed). Hooks compose on top of the event for direct consumers.
      if (typeof hooks.onWorkspaceChanged === "function") {
        hooks.onWorkspaceChanged();
      }
      if (documentRef && typeof documentRef.dispatchEvent === "function"
              && typeof CustomEvent === "function") {
        documentRef.dispatchEvent(new CustomEvent("workspace-changed"));
      }
    }
  });
}

if (typeof document !== "undefined") bootstrapWorkspaceUi();
