const SESSION_ENDPOINT = "/api/v1/owner/session";
const ROTATION_ENDPOINT = "/api/v1/owner/session/rotation";

const ERROR_MESSAGES = Object.freeze({
  OWNER_AUTH_REQUIRED: ["需要擁有者登入", "請先登入後再試一次。"],
  OWNER_HOST_REJECTED: ["主機未被允許", "目前的主機不在允許清單內，請確認存取位置。"],
  OWNER_ORIGIN_REJECTED: ["來源未被允許", "目前的瀏覽器來源未通過安全檢查，請由允許的來源存取。"],
  OWNER_RATE_LIMITED: ["請求過於頻繁", "已達安全限制，請稍後再試。"]
});

const GENERIC_ERROR = ["無法完成登入", "發生未預期的問題，請稍後再試。"];
const LOCAL_MODE_MESSAGE = "本機模式：無需登入。";
const LOGGED_IN_MESSAGE = "已登入：擁有者工作階段有效。";
const LOGGED_OUT_MESSAGE = "已登出。";

export function errorMessage(error) {
  const code = error && typeof error.code === "string" ? error.code : "";
  const [title, message] = ERROR_MESSAGES[code] || GENERIC_ERROR;
  return { title, message };
}

function text(value) {
  return value === null || value === undefined ? "" : String(value);
}

async function readEnvelope(response) {
  try {
    return await response.json();
  } catch {
    return null;
  }
}

function typedError(envelope) {
  return envelope && envelope.error ? envelope.error : undefined;
}

export async function login(password, fetchImpl = fetch) {
  const response = await fetchImpl(SESSION_ENDPOINT, {
    method: "POST",
    credentials: "same-origin",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ password })
  });
  const envelope = await readEnvelope(response);
  if (!response.ok) {
    return { ok: false, status: response.status, error: typedError(envelope) };
  }
  return { ok: true, status: response.status, data: envelope && envelope.data };
}

export async function logout(fetchImpl = fetch) {
  const response = await fetchImpl(SESSION_ENDPOINT, {
    method: "DELETE",
    credentials: "same-origin"
  });
  if (!response.ok && response.status !== 204) {
    const envelope = await readEnvelope(response);
    return { ok: false, status: response.status, error: typedError(envelope) };
  }
  return { ok: true, status: response.status };
}

export async function fetchSessionStatus(fetchImpl = fetch) {
  const response = await fetchImpl(SESSION_ENDPOINT, {
    method: "GET",
    credentials: "same-origin"
  });
  const envelope = await readEnvelope(response);
  if (!response.ok) {
    return { ok: false, status: response.status, error: typedError(envelope) };
  }
  return { ok: true, status: response.status, data: envelope && envelope.data };
}

export function createOwnerAuthController(elements, fetchImpl = fetch, documentRef = document) {
  let inFlight = false;

  function showStatus(message) {
    elements.status.textContent = text(message);
  }

  function showForm() {
    elements.form.hidden = false;
    if (elements.logout) elements.logout.hidden = true;
  }

  function showLoggedIn() {
    elements.form.hidden = true;
    if (elements.logout) elements.logout.hidden = false;
    showStatus(LOGGED_IN_MESSAGE);
  }

  async function refresh() {
    const result = await fetchSessionStatus(fetchImpl);
    if (result.ok) {
      if (result.data && result.data.authEnabled === false) {
        showStatus(LOCAL_MODE_MESSAGE);
        elements.form.hidden = true;
        if (elements.logout) elements.logout.hidden = true;
        return;
      }
      showLoggedIn();
      return;
    }
    if (result.status === 401) {
      showStatus(LOGGED_OUT_MESSAGE);
      showForm();
      return;
    }
    const { title, message } = errorMessage(result.error);
    showStatus(`${title}：${message}`);
    showForm();
  }

  async function submit(event) {
    if (event && typeof event.preventDefault === "function") event.preventDefault();
    if (inFlight) return;
    inFlight = true;
    try {
      const password = elements.password ? text(elements.password.value) : "";
      if (!password) {
        showStatus("請先輸入擁有者密碼。");
        return;
      }
      const result = await login(password, fetchImpl);
      if (!result.ok) {
        const { title, message } = errorMessage(result.error);
        showStatus(`${title}：${message}`);
        return;
      }
      if (elements.password) elements.password.value = "";
      showLoggedIn();
    } catch {
      showStatus(`${GENERIC_ERROR[0]}：${GENERIC_ERROR[1]}`);
    } finally {
      inFlight = false;
    }
  }

  async function signOut(event) {
    if (event && typeof event.preventDefault === "function") event.preventDefault();
    if (inFlight) return;
    inFlight = true;
    try {
      const result = await logout(fetchImpl);
      if (!result.ok) {
        const { title, message } = errorMessage(result.error);
        showStatus(`${title}：${message}`);
        return;
      }
      showStatus(LOGGED_OUT_MESSAGE);
      showForm();
    } catch {
      showStatus(`${GENERIC_ERROR[0]}：${GENERIC_ERROR[1]}`);
    } finally {
      inFlight = false;
    }
  }

  if (elements.form && typeof elements.form.addEventListener === "function") {
    elements.form.addEventListener("submit", submit);
  }
  if (elements.logout && typeof elements.logout.addEventListener === "function") {
    elements.logout.addEventListener("click", signOut);
  }
  return { refresh, submit, signOut };
}

export function bootstrapOwnerAuthUi(documentRef = document) {
  const elements = {
    panel: documentRef.getElementById("owner-auth-panel"),
    status: documentRef.getElementById("owner-auth-status"),
    form: documentRef.getElementById("owner-auth-form"),
    password: documentRef.getElementById("owner-auth-password"),
    logout: documentRef.getElementById("owner-auth-logout")
  };
  if (!elements.panel || !elements.status || !elements.form) return null;
  const controller = createOwnerAuthController(elements, fetch, documentRef);
  void controller.refresh();
  return controller;
}

if (typeof document !== "undefined") bootstrapOwnerAuthUi();

export { SESSION_ENDPOINT, ROTATION_ENDPOINT };
