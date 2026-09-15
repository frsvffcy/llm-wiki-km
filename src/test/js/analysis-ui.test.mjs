import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

import {
  ANALYSIS_JOB_ERROR_MESSAGES,
  ANALYSIS_READINESS_ENDPOINT,
  analysisJobOutcome,
  analysisReadinessOutcome,
  createAnalysisController,
  renderAnalysisReadiness
} from "../../main/resources/static/analysis-ui.js";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SOURCE = readFileSync(
  path.join(HERE, "../../main/resources/static/analysis-ui.js"), "utf8");

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

function analysisElements() {
  return {
    state: new FakeElement("p"),
    detail: new FakeElement("div"),
    hint: new FakeElement("p"),
    refresh: new FakeElement("button")
  };
}

function jsonResponse(ok, status, payload) {
  return {
    ok,
    status,
    json: async () => payload
  };
}

function flatText(element) {
  return [element.textContent,
    ...element.children.map(child => flatText(child))].join(" ");
}

test("readiness endpoint is the backend-owned feature surface", () => {
  assert.equal(ANALYSIS_READINESS_ENDPOINT, "/api/v1/analysis/readiness");
});

test("ready readiness renders the provider identity without inventing state", () => {
  const outcome = analysisReadinessOutcome({
    workspaceReady: true,
    promptStatus: "READY",
    settingsValid: true,
    provider: "stub",
    model: "offline",
    analysisReady: true
  });

  assert.equal(outcome.tone, "ready");
  assert.match(outcome.label, /已就緒/u);
  assert.match(outcome.label, /stub\/offline/u);
});

test("missing prompt is never ready and carries the repair next action", () => {
  const outcome = analysisReadinessOutcome({
    workspaceReady: true,
    promptStatus: "MISSING",
    promptErrorCode: "PROMPT_TEMPLATE_NOT_FOUND",
    settingsValid: true,
    analysisReady: false
  });

  assert.equal(outcome.tone, "missing");
  assert.match(outcome.label, /缺少 prompt/u);
  assert.match(outcome.detail, /config\/prompts\/document-analysis\.md/u);
  assert.match(outcome.detail, /工作區修復/u);
});

test("invalid prompt preserves the typed backend code", () => {
  const outcome = analysisReadinessOutcome({
    workspaceReady: true,
    promptStatus: "INVALID",
    promptErrorCode: "PROMPT_VARIABLE_MISSING",
    promptErrorMessage: "backend safe message",
    settingsValid: true,
    analysisReady: false
  });

  assert.equal(outcome.tone, "invalid");
  assert.match(outcome.detail, /必要變數/u);
});

test("completed with failures is partial and never pure success", () => {
  const partial = analysisJobOutcome({
    status: "COMPLETED",
    successCount: 1,
    failedCount: 2,
    skippedCount: 0,
    failureCode: "PARTIAL_FAILURE",
    failureSummary: "Document analysis completed with failed items"
  });

  assert.equal(partial.tone, "partial");
  assert.match(partial.label, /部分完成/u);
  assert.doesNotMatch(partial.label, /^已完成/u);

  const clean = analysisJobOutcome({
    status: "COMPLETED",
    successCount: 2,
    failedCount: 0,
    skippedCount: 0,
    failureCode: null,
    failureSummary: null
  });

  assert.equal(clean.tone, "success");
  assert.match(clean.label, /已完成/u);
});

test("failed job with a typed prompt code carries the actionable reason", () => {
  const outcome = analysisJobOutcome({
    status: "FAILED",
    successCount: 0,
    failedCount: 1,
    skippedCount: 0,
    failureCode: "PROMPT_TEMPLATE_NOT_FOUND",
    failureSummary: "Document analysis prompt template is missing; repair the workspace"
  });

  assert.equal(outcome.tone, "failed");
  assert.match(outcome.label, /prompt 樣板/u);
  assert.match(outcome.label, /工作區修復/u);
});

test("readiness rendering uses safe text and never innerHTML", () => {
  assert.doesNotMatch(SOURCE, /\.innerHTML\s*=/);

  const elements = analysisElements();
  renderAnalysisReadiness(elements, {
    workspaceReady: true,
    promptStatus: "MISSING",
    promptErrorCode: "PROMPT_TEMPLATE_NOT_FOUND",
    settingsValid: true,
    analysisReady: false
  }, { createElement: () => new FakeElement() });

  assert.match(elements.state.textContent, /缺少 prompt/u);
  assert.match(flatText(elements.detail), /config\/prompts\/document-analysis\.md/u);
});

test("controller refresh renders backend readiness and resets on workspace change", async () => {
  const elements = analysisElements();
  const calls = [];
  const listeners = new Map();
  const documentRef = {
    createElement: () => new FakeElement(),
    addEventListener: (name, handler) => listeners.set(name, handler)
  };
  const fetchImpl = async url => {
    calls.push(String(url));
    return jsonResponse(true, 200, {
      data: {
        workspaceReady: true,
        promptStatus: "READY",
        settingsValid: true,
        provider: "stub",
        model: "offline",
        analysisReady: true
      }
    });
  };
  const controller = createAnalysisController(elements, fetchImpl, documentRef);

  await controller.refresh();

  assert.deepEqual(calls, ["/api/v1/analysis/readiness"]);
  assert.match(elements.state.textContent, /已就緒/u);

  // Workspace isolation: a switch clears current-scoped readiness and re-fetches.
  calls.length = 0;
  await listeners.get("workspace-changed")();

  assert.deepEqual(calls, ["/api/v1/analysis/readiness"]);
  assert.match(elements.state.textContent, /已就緒/u);
});

test("controller surfaces the typed no-workspace error without stale readiness", async () => {
  const elements = analysisElements();
  elements.state.textContent = "stale";
  const documentRef = {
    createElement: () => new FakeElement(),
    addEventListener: () => {}
  };
  const fetchImpl = async () => jsonResponse(false, 404, {
    error: { code: "NO_ACTIVE_WORKSPACE", message: "none" }
  });
  const controller = createAnalysisController(elements, fetchImpl, documentRef);

  await controller.refresh();

  assert.equal(elements.state.textContent, "");
  assert.match(elements.hint.textContent, /尚未開啟知識庫/u);
});

test("typed prompt messages stay actionable and human-readable", () => {
  assert.match(ANALYSIS_JOB_ERROR_MESSAGES.PROMPT_TEMPLATE_NOT_FOUND, /工作區修復/u);
  assert.match(ANALYSIS_JOB_ERROR_MESSAGES.PROMPT_VARIABLE_MISSING, /必要變數/u);
  assert.match(ANALYSIS_JOB_ERROR_MESSAGES.ANALYSIS_SETTING_INVALID, /設定/u);
});
