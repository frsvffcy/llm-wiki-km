import assert from "node:assert/strict";
import test from "node:test";

import {
  createWorkspaceController,
  renderCurrentWorkspace,
  validateWorkspaceInput,
  workspaceErrorMessage
} from "../../main/resources/static/workspace-ui.js";

class FakeElement {
  constructor(tagName = "div") {
    this.tagName = tagName;
    this.children = [];
    this.hidden = false;
    this.disabled = false;
    this.value = "";
    this.textContent = "";
    this.className = "";
    this.handlers = new Map();
  }

  append(...nodes) {
    this.children.push(...nodes);
  }

  replaceChildren(...nodes) {
    this.children = nodes;
  }

  addEventListener(name, handler) {
    this.handlers.set(name, handler);
  }
}

function uiElements() {
  return {
    form: new FakeElement("form"),
    name: new FakeElement("input"),
    rootPath: new FakeElement("input"),
    createSubmit: new FakeElement("button"),
    hint: new FakeElement("p"),
    current: new FakeElement("div"),
    currentEmpty: new FakeElement("p"),
    currentName: new FakeElement("p"),
    currentPath: new FakeElement("p"),
    currentStatus: new FakeElement("p"),
    layoutState: new FakeElement("p"),
    layoutDetail: new FakeElement("div"),
    list: new FakeElement("ul"),
    repair: new FakeElement("button")
  };
}

function flatText(element) {
  return [element.textContent,
    ...element.children.map(child => flatText(child))].join(" ");
}

function jsonResponse(ok, status, payload) {
  return {
    ok,
    status,
    json: async () => payload
  };
}

function workspaceResponse(id, name) {
  return {
    data: {
      id, name, rootPath: `/tmp/ws-${id}`, status: "ACTIVE",
      layout: { valid: true, repairedDirectories: [], problems: [] }
    }
  };
}

function statusResponse(id, name, layoutOverrides = {}) {
  return {
    data: {
      workspace: { id, name, rootPath: `/tmp/ws-${id}`, status: "ACTIVE" },
      layout: { valid: true, repairedDirectories: [], problems: [], ...layoutOverrides }
    }
  };
}

test("typed error messages stay human-readable and workspace-scoped", () => {
  assert.equal(workspaceErrorMessage({ code: "WORKSPACE_ALREADY_EXISTS" }).title,
    "Workspace 已存在");
  assert.equal(workspaceErrorMessage({ code: "NO_ACTIVE_WORKSPACE" }).title, "尚未開啟知識庫");
  assert.equal(workspaceErrorMessage(undefined).title, "無法取得 workspace 狀態");
});

test("workspace input requires name and root path", () => {
  assert.equal(validateWorkspaceInput("personal", "/tmp/km"), null);
  assert.match(validateWorkspaceInput("", "/tmp/km"), /名稱/u);
  assert.match(validateWorkspaceInput("personal", " "), /root path/u);
});

test("refresh renders the empty state when no current workspace exists", async () => {
  const elements = uiElements();
  const calls = [];
  const fetchImpl = async url => {
    calls.push(String(url));
    if (String(url).endsWith("/current")) {
      return jsonResponse(false, 404, {
        error: { code: "NO_ACTIVE_WORKSPACE", message: "no active workspace" }
      });
    }
    return jsonResponse(true, 200, { data: [] });
  };
  const controller = createWorkspaceController(elements, fetchImpl, {
    createElement: () => new FakeElement()
  });
  await controller.refresh();

  assert.equal(elements.currentEmpty.hidden, false);
  assert.equal(elements.current.hidden, true);
  assert.match(elements.hint.textContent, /建立一個 workspace 或從清單中選擇/u);
  assert.deepEqual(calls, ["/api/v1/workspaces/current", "/api/v1/workspaces"]);
});

test("create submits the existing request contract and announces the workspace change", async () => {
  const elements = uiElements();
  elements.name.value = "personal";
  elements.rootPath.value = "/tmp/km";
  let changed = 0;
  const calls = [];
  const fetchImpl = async (url, options) => {
    calls.push({ url: String(url), method: options?.method, body: options?.body });
    if (url === "/api/v1/workspaces" && options?.method === "POST") {
      return jsonResponse(true, 201, workspaceResponse(7, "personal"));
    }
    if (String(url).endsWith("/current")) {
      return jsonResponse(true, 200, statusResponse(7, "personal"));
    }
    return jsonResponse(true, 200, { data: [] });
  };
  const controller = createWorkspaceController(elements, fetchImpl,
    { createElement: () => new FakeElement() }, { onWorkspaceChanged: () => { changed += 1; } });

  await elements.form.handlers.get("submit")({ preventDefault() {} });

  assert.deepEqual(calls[0], {
    url: "/api/v1/workspaces",
    method: "POST",
    body: JSON.stringify({ name: "personal", rootPath: "/tmp/km" })
  });
  assert.equal(changed, 1);
  assert.match(elements.hint.textContent, /已建立並啟用/u);
  assert.equal(elements.name.value, "", "form resets after success");
});

test("create failure surfaces the typed backend code without a workspace change", async () => {
  const elements = uiElements();
  elements.name.value = "dup";
  elements.rootPath.value = "/tmp/km";
  let changed = 0;
  const fetchImpl = async (url, options) => {
    if (options?.method === "POST") {
      return jsonResponse(false, 409, {
        error: { code: "WORKSPACE_ALREADY_EXISTS", message: "exists" }
      });
    }
    return jsonResponse(false, 404, {
      error: { code: "NO_ACTIVE_WORKSPACE", message: "none" }
    });
  };
  const controller = createWorkspaceController(elements, fetchImpl,
    { createElement: () => new FakeElement() }, { onWorkspaceChanged: () => { changed += 1; } });

  await elements.form.handlers.get("submit")({ preventDefault() {} });

  assert.match(elements.hint.textContent, /Workspace 已存在/u);
  assert.equal(changed, 0);
});

test("switch calls the existing open contract and re-fetches current state", async () => {
  const elements = uiElements();
  const calls = [];
  const fetchImpl = async (url, options) => {
    calls.push({ url: String(url), method: options?.method });
    if (url === "/api/v1/workspaces/current" && options?.method === "PUT") {
      return jsonResponse(true, 200, statusResponse(3, "other"));
    }
    if (String(url).endsWith("/current")) {
      return jsonResponse(true, 200, statusResponse(3, "other"));
    }
    return jsonResponse(true, 200, { data: [workspaceResponse(3, "other").data] });
  };
  let changed = 0;
  const controller = createWorkspaceController(elements, fetchImpl,
    { createElement: () => new FakeElement() }, { onWorkspaceChanged: () => { changed += 1; } });

  await controller.switchTo(3);

  assert.deepEqual(calls[0], { url: "/api/v1/workspaces/current", method: "PUT" });
  assert.equal(changed, 1);
  assert.equal(elements.currentName.textContent, "other");
});

test("repair posts to the existing repair endpoint and refreshes", async () => {
  const elements = uiElements();
  const calls = [];
  const fetchImpl = async (url, options) => {
    calls.push({ url: String(url), method: options?.method });
    if (String(url).endsWith("/current/repair")) {
      return jsonResponse(true, 200, statusResponse(1, "main",
        { repairedDirectories: ["logs"] }));
    }
    if (String(url).endsWith("/current")) {
      return jsonResponse(true, 200, statusResponse(1, "main"));
    }
    return jsonResponse(true, 200, { data: [] });
  };
  const controller = createWorkspaceController(elements, fetchImpl,
    { createElement: () => new FakeElement() });

  await controller.repair();

  assert.deepEqual(calls[0], { url: "/api/v1/workspaces/current/repair", method: "POST" });
  assert.match(elements.hint.textContent, /已執行目錄修復/u);
});

test("current workspace renders layout validity and backend-safe problems", () => {
  const elements = uiElements();
  renderCurrentWorkspace(elements, statusResponse(1, "main", {
    valid: false,
    problems: ["archive directory is missing"]
  }).data, { createElement: () => new FakeElement() });

  assert.equal(elements.current.hidden, false);
  assert.equal(elements.currentName.textContent, "main");
  assert.equal(elements.layoutState.textContent, "目錄結構需要修復");
  assert.match(flatText(elements.layoutDetail), /archive directory is missing/u);
  assert.match(elements.layoutState.className, /workspace-layout--broken/u);
});
