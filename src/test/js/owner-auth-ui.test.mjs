import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import {
  createOwnerAuthController,
  errorMessage,
  fetchSessionStatus,
  login,
  logout
} from "../../main/resources/static/owner-auth-ui.js";

const MODULE_URL = new URL(
  "../../main/resources/static/owner-auth-ui.js",
  import.meta.url
);

class FakeElement {
  constructor() {
    this.hidden = false;
    this.disabled = false;
    this.value = "";
    this.textContent = "";
    this.handlers = new Map();
  }

  addEventListener(name, handler) { this.handlers.set(name, handler); }
}

function elements() {
  return {
    panel: new FakeElement(),
    status: new FakeElement(),
    form: new FakeElement(),
    password: new FakeElement(),
    logout: new FakeElement()
  };
}

function event() { return { preventDefault() {} }; }

function jsonResponse({ ok, status, body }) {
  return {
    ok,
    status,
    async json() { return body; }
  };
}

test("login posts the owner credential same-origin without persisting it", async () => {
  const seen = [];
  const fetchImpl = async (url, init) => {
    seen.push([url, init]);
    return jsonResponse({
      ok: true,
      status: 201,
      body: { data: { authenticated: true, token: "opaque-session" } }
    });
  };
  const result = await login("owner-test-password", fetchImpl);
  assert.equal(result.ok, true);
  assert.equal(seen.length, 1);
  assert.equal(seen[0][0], "/api/v1/owner/session");
  assert.equal(seen[0][1].method, "POST");
  assert.equal(seen[0][1].credentials, "same-origin");
  assert.deepEqual(JSON.parse(seen[0][1].body), { password: "owner-test-password" });
});

test("login failure surfaces the typed owner error as safe text", async () => {
  const ui = elements();
  const fetchImpl = async () => jsonResponse({
    ok: false,
    status: 401,
    body: { error: { code: "OWNER_AUTH_REQUIRED", message: "Owner authentication is required" } }
  });
  const controller = createOwnerAuthController(ui, fetchImpl, {});
  ui.password.value = "wrong";
  await controller.submit(event());
  assert.match(ui.status.textContent, /需要擁有者登入/);
  assert.doesNotMatch(ui.status.textContent, /wrong/);
});

test("rate-limited login renders the typed throttling message", async () => {
  const { title } = errorMessage({ code: "OWNER_RATE_LIMITED" });
  assert.equal(title, "請求過於頻繁");
  const unknown = errorMessage({ code: "SOMETHING_ELSE" });
  assert.equal(unknown.title, "無法完成登入");
});

test("logout issues an authenticated same-origin DELETE", async () => {
  const seen = [];
  const fetchImpl = async (url, init) => {
    seen.push([url, init]);
    return { ok: true, status: 204, async json() { return null; } };
  };
  const result = await logout(fetchImpl);
  assert.equal(result.ok, true);
  assert.equal(seen[0][0], "/api/v1/owner/session");
  assert.equal(seen[0][1].method, "DELETE");
  assert.equal(seen[0][1].credentials, "same-origin");
});

test("status 401 shows the login form while local mode hides it", async () => {
  const loggedOut = elements();
  const loggedOutFetch = async () => jsonResponse({
    ok: false,
    status: 401,
    body: { error: { code: "OWNER_AUTH_REQUIRED", message: "required" } }
  });
  const loggedOutController = createOwnerAuthController(loggedOut, loggedOutFetch, {});
  await loggedOutController.refresh();
  assert.equal(loggedOut.form.hidden, false);
  assert.match(loggedOut.status.textContent, /已登出/);

  const local = elements();
  const localFetch = async () => jsonResponse({
    ok: true,
    status: 200,
    body: { data: { authEnabled: false, authenticated: true } }
  });
  const localController = createOwnerAuthController(local, localFetch, {});
  await localController.refresh();
  assert.equal(local.form.hidden, true);
  assert.match(local.status.textContent, /本機模式/);
});

test("fetchSessionStatus reports typed failures without secrets", async () => {
  const fetchImpl = async () => jsonResponse({
    ok: false,
    status: 403,
    body: { error: { code: "OWNER_ORIGIN_REJECTED", message: "rejected" } }
  });
  const result = await fetchSessionStatus(fetchImpl);
  assert.equal(result.ok, false);
  assert.equal(result.error.code, "OWNER_ORIGIN_REJECTED");
});

test("the module never injects markup and never persists credentials", async () => {
  const source = await readFile(MODULE_URL, "utf8");
  assert.doesNotMatch(source, /innerHTML|localStorage|sessionStorage|document\.cookie|eval\(/);
  assert.ok(!source.includes("Authorization"), "Browser uses the HttpOnly cookie, never Bearer");
});
