import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import {
  bootstrapGraphOperationsUi,
  createGraphOperationsController,
  graphErrorMessage,
  renderGraphError,
  renderGraphReadiness,
  STATUS_COPY
} from "../../main/resources/static/graph-operations-ui.js";

class FakeElement {
  constructor() {
    this.children = [];
    this.hidden = false;
    this.disabled = false;
    this.textContent = "";
    this.className = "";
    this.attributes = new Map();
    this.handlers = new Map();
  }

  replaceChildren(...nodes) { this.children = nodes; }
  setAttribute(name, value) { this.attributes.set(name, value); }
  addEventListener(name, handler) { this.handlers.set(name, handler); }
}

function uiElements() {
  return {
    panel: new FakeElement(),
    statusBadge: new FakeElement(),
    statusMessage: new FakeElement(),
    version: new FakeElement(),
    generation: new FakeElement(),
    operation: new FakeElement(),
    failure: new FakeElement(),
    failureDiagnostic: new FakeElement(),
    feedback: new FakeElement(),
    refresh: new FakeElement(),
    rebuild: new FakeElement(),
    repair: new FakeElement()
  };
}

function statusPayload(status = "READY", overrides = {}) {
  return { data: {
    status,
    projectionVersion: "graph-projection-v2",
    targetGeneration: 4,
    appliedGeneration: 4,
    operationKind: null,
    failureCode: null,
    failureDiagnostic: null,
    lastFailureCode: null,
    retryable: false,
    repairRecommended: false,
    ...overrides
  } };
}

function response(payload, { ok = true, status = 200 } = {}) {
  return { ok, status, async json() { return payload; } };
}

function documentFor(elements) {
  const nodes = new Map([
    ["graph-projection-panel", elements.panel],
    ["graph-status-badge", elements.statusBadge],
    ["graph-status-message", elements.statusMessage],
    ["graph-projection-version", elements.version],
    ["graph-generation", elements.generation],
    ["graph-operation", elements.operation],
    ["graph-failure", elements.failure],
    ["graph-failure-diagnostic", elements.failureDiagnostic],
    ["graph-feedback", elements.feedback],
    ["graph-refresh", elements.refresh],
    ["graph-rebuild", elements.rebuild],
    ["graph-repair", elements.repair]
  ]);
  return { getElementById: id => nodes.get(id) };
}

test("renders the public readiness lifecycle states without exposing raw enum values", () => {
  const rendered = [];
  for (const status of ["DISABLED", "NOT_CONFIGURED", "NOT_READY", "BUILDING",
    "REPAIRING", "READY", "STALE", "REPAIR_REQUIRED"]) {
    const elements = uiElements();
    assert.equal(renderGraphReadiness(elements, statusPayload(status)), true);
    rendered.push(elements.statusBadge.textContent);
    assert.equal(elements.statusBadge.attributes.get("data-status"), status);
  }
  assert.deepEqual(rendered, [
    "已停用", "尚未設定", "尚未就緒", "重建中", "修復中", "已就緒", "已過期", "需要修復"
  ]);
  assert.equal(Object.keys(STATUS_COPY).includes("READY"), true);
});

test("renders safe DTO fields as text and never as HTML", () => {
  const elements = uiElements();
  renderGraphReadiness(elements, statusPayload("READY", {
    projectionVersion: "<img src=x onerror=alert(1)>",
    operationKind: "REBUILD",
    failureCode: "GRAPH_PROJECTION_STALE",
    failureDiagnostic: "safe <script>alert(1)</script> detail"
  }));

  assert.equal(elements.version.textContent, "<img src=x onerror=alert(1)>");
  assert.equal(elements.operation.textContent, "重建中");
  assert.match(elements.failure.textContent, /投影已過期/);
  assert.equal(elements.failureDiagnostic.textContent, "safe <script>alert(1)</script> detail");
  assert.doesNotMatch(elements.statusBadge.textContent, /<|>/);
});

test("readiness response validation changes a previously READY panel to unknown", () => {
  const elements = uiElements();
  renderGraphReadiness(elements, statusPayload("READY"));
  assert.equal(elements.statusBadge.textContent, "已就緒");
  assert.equal(elements.version.textContent, "graph-projection-v2");

  assert.equal(renderGraphReadiness(elements, { data: { status: "READY" } }), false);
  assert.equal(elements.statusBadge.textContent, "無法確認");
  assert.equal(elements.statusBadge.attributes.get("data-status"), "UNKNOWN");
  assert.equal(elements.version.textContent, "—");
  assert.equal(elements.generation.textContent, "—");
  assert.equal(elements.failure.textContent, "—");
});

test("keeps typed 409, 503, and 500 failures distinct and safe", () => {
  const conflict = graphErrorMessage({ status: 409, code: "GRAPH_PROJECTION_STALE" });
  const unavailable = graphErrorMessage({ status: 503, code: "GRAPH_BACKEND_LOCKED" });
  const corrupt = graphErrorMessage({ status: 500, code: "GRAPH_PROJECTION_CORRUPT" });
  assert.equal(conflict.title, "投影已過期");
  assert.match(conflict.message, /rebuild|repair/);
  assert.equal(unavailable.title, "投影目前被鎖定");
  assert.match(unavailable.message, /稍後/);
  assert.equal(corrupt.title, "投影資料需要重建");
  assert.doesNotMatch(corrupt.message, /暫時無法使用/);
  assert.doesNotMatch(`${conflict.title}${conflict.message}${unavailable.title}${corrupt.message}`,
    /stack|exception|\/Users|\/tmp|RID|snapshot|token|password/i);
});

test("maps network and malformed responses to explicit unknown states", () => {
  const elements = uiElements();
  renderGraphReadiness(elements, statusPayload("READY"));
  renderGraphError(elements, { network: true });
  assert.equal(elements.statusBadge.textContent, "無法確認");
  assert.match(elements.feedback.textContent, /無法連線/);

  renderGraphError(elements, { malformed: true });
  assert.equal(elements.statusBadge.textContent, "無法確認");
  assert.match(elements.feedback.textContent, /回應無效/);
  assert.doesNotMatch(elements.feedback.textContent, /Error|exception|stack/i);
});

test("controller initialization performs no request until refresh is explicitly called", async () => {
  const elements = uiElements();
  const calls = [];
  const controller = createGraphOperationsController(elements, async (url, options) => {
    calls.push({ url, options });
    return response(statusPayload("READY"));
  });
  assert.equal(calls.length, 0);

  await controller.refresh();
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, "/api/v1/graph/projection/readiness");
  assert.equal(calls[0].options.method, "GET");
  assert.equal(calls[0].options.body, undefined);
});

test("bootstrap loads readiness only and never starts rebuild or repair", async () => {
  const elements = uiElements();
  const calls = [];
  const controller = bootstrapGraphOperationsUi(documentFor(elements), async (url, options) => {
    calls.push({ url, options });
    return response(statusPayload("NOT_READY", { appliedGeneration: 0, targetGeneration: 1 }));
  });
  await new Promise(resolve => setImmediate(resolve));
  assert.ok(controller);
  assert.deepEqual(calls.map(call => [call.url, call.options.method]), [
    ["/api/v1/graph/projection/readiness", "GET"]
  ]);
});

test("rebuild uses an explicit POST without a client Graph payload and refreshes readiness", async () => {
  const elements = uiElements();
  const calls = [];
  const controller = createGraphOperationsController(elements, async (url, options) => {
    calls.push({ url, options });
    return calls.length === 1
      ? response(statusPayload("READY", { appliedGeneration: 5, targetGeneration: 5 }))
      : response(statusPayload("READY", { appliedGeneration: 5, targetGeneration: 5 }));
  });

  const result = await controller.rebuild();
  assert.equal(result, true);
  assert.deepEqual(calls.map(call => [call.url, call.options.method]), [
    ["/api/v1/graph/projection/rebuild", "POST"],
    ["/api/v1/graph/projection/readiness", "GET"]
  ]);
  assert.equal(calls[0].options.body, undefined);
  assert.equal(elements.statusBadge.textContent, "已就緒");
  assert.equal(elements.rebuild.disabled, false);
  assert.equal(elements.repair.disabled, false);
});

test("repair uses the existing endpoint and restores controls after completion", async () => {
  const elements = uiElements();
  const calls = [];
  const controller = createGraphOperationsController(elements, async (url, options) => {
    calls.push({ url, options });
    return response(statusPayload("READY", { appliedGeneration: 7, targetGeneration: 7 }));
  });

  await controller.repair();
  assert.equal(calls[0].url, "/api/v1/graph/projection/repair");
  assert.equal(calls[0].options.method, "POST");
  assert.equal(calls.length, 2);
  assert.equal(elements.refresh.disabled, false);
  assert.equal(elements.repair.disabled, false);
  assert.match(elements.feedback.textContent, /repair 已完成/);
});

test("prevents concurrent rebuild submits while the operation and follow-up refresh are pending", async () => {
  const elements = uiElements();
  const calls = [];
  let releasePost;
  let releaseReadiness;
  const postPending = new Promise(resolve => { releasePost = resolve; });
  const readinessPending = new Promise(resolve => { releaseReadiness = resolve; });
  const controller = createGraphOperationsController(elements, async (url, options) => {
    calls.push({ url, options });
    if (calls.length === 1) {
      await postPending;
      return response(statusPayload("BUILDING", {
        operationKind: "REBUILD", appliedGeneration: 1, targetGeneration: 2
      }));
    }
    await readinessPending;
    return response(statusPayload("READY", { appliedGeneration: 2, targetGeneration: 2 }));
  });

  const first = controller.rebuild();
  await Promise.resolve();
  const second = await controller.rebuild();
  assert.equal(second, false);
  assert.equal(calls.length, 1);
  assert.equal(elements.rebuild.disabled, true);
  assert.equal(elements.repair.disabled, true);
  releasePost();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(calls.length, 2);
  releaseReadiness();
  assert.equal(await first, true);
  assert.equal(elements.rebuild.disabled, false);
});

test("repair failure gives typed feedback and does not leave controls disabled", async () => {
  const elements = uiElements();
  const controller = createGraphOperationsController(elements, async () => response(
    { error: { code: "GRAPH_PROJECTION_CORRUPT", message: "raw stack /Users/secret" } },
    { ok: false, status: 500 }
  ));

  assert.equal(await controller.repair(), false);
  assert.equal(elements.statusBadge.textContent, "無法確認");
  assert.match(elements.feedback.textContent, /投影資料需要重建/);
  assert.doesNotMatch(elements.feedback.textContent, /raw stack|\/Users|secret/i);
  assert.equal(elements.repair.disabled, false);
});

test("readiness network failure never preserves a prior READY badge", async () => {
  const elements = uiElements();
  let callCount = 0;
  const controller = createGraphOperationsController(elements, async () => {
    callCount += 1;
    if (callCount === 1) return response(statusPayload("READY"));
    throw new Error("network detail");
  });

  await controller.refresh();
  assert.equal(elements.statusBadge.textContent, "已就緒");
  await controller.refresh();
  assert.equal(elements.statusBadge.textContent, "無法確認");
  assert.equal(elements.version.textContent, "—");
  assert.match(elements.feedback.textContent, /無法連線/);
});

test("Graph UI has no destructive controls, backend console inputs, or unsafe rendering APIs", async () => {
  const source = await readFile(new URL("../../main/resources/static/graph-operations-ui.js", import.meta.url), "utf8");
  const html = await readFile(new URL("../../main/resources/static/index.html", import.meta.url), "utf8");
  assert.doesNotMatch(source, /innerHTML|localStorage|sessionStorage/);
  assert.doesNotMatch(source, /ArcadeDB|sqlite|snapshotToken|sourceFingerprint|ownerToken|RID|absolute path|credential/i);
  assert.doesNotMatch(source, /api\/v1\/graph\/projection\/(?:clear|reset|delete)/i);
  assert.doesNotMatch(html, /graph-(clear|reset|delete)|\bRID\b|snapshotToken|sourceFingerprint|ownerToken/i);
  assert.match(source, /api\/v1\/graph\/projection\/readiness/);
  assert.match(source, /api\/v1\/graph\/projection\/rebuild/);
  assert.match(source, /api\/v1\/graph\/projection\/repair/);
});
