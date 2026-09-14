import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import {
  categoryLabel,
  codeLabel,
  createQualityController,
  repairRefusalLabel,
  severityLabel,
  triageErrorMessage,
  FINDING_CATEGORIES,
  FINDING_SEVERITIES
} from "../../main/resources/static/quality-ui.js";

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

  append(...nodes) { this.children.push(...nodes); }
  replaceChildren(...nodes) { this.children = nodes; }
  addEventListener(name, handler) { this.handlers.set(name, handler); }
  setAttribute(name, value) { this[`attr_${name}`] = value; }
  getAttribute(name) { return this[`attr_${name}`] ?? null; }
}

function uiElements() {
  return {
    triageFilterForm: new FakeElement("form"),
    categoryFilter: new FakeElement("select"),
    severityFilter: new FakeElement("select"),
    triageRefresh: new FakeElement("button"),
    triageHint: new FakeElement("p"),
    triageMeta: new FakeElement("p"),
    triageEmpty: new FakeElement("p"),
    triageList: new FakeElement("ul"),
    triageDetail: new FakeElement("section"),
    triageDetailTitle: new FakeElement("h4"),
    triageDetailMeta: new FakeElement("p"),
    triageDetailExplanation: new FakeElement("p"),
    triagePage: new FakeElement("div"),
    triagePageHint: new FakeElement("p"),
    triageDetailClose: new FakeElement("button"),
    triageRepair: new FakeElement("div"),
    triageRepairCreate: new FakeElement("button"),
    triageRepairHint: new FakeElement("p"),
    triageRefusal: new FakeElement("p")
  };
}

function fakeDocument() {
  const listeners = new Map();
  return {
    createElement: () => new FakeElement(),
    listeners,
    addEventListener(name, handler) { listeners.set(name, handler); }
  };
}

function flatText(element) {
  return [element.textContent,
    ...element.children.map(child => flatText(child))].join(" ");
}

function jsonResponse(status, payload) {
  return { ok: status < 400, status, json: async () => payload };
}

function findingRow(overrides = {}) {
  return {
    code: "BROKEN_INTERNAL_LINK",
    category: "REFERENCE",
    severity: "ERROR",
    knowledgeId: "wiki-hub",
    logicalPath: "vault/concepts/hub-page.md",
    detail: "wikilink target not published: missing page",
    ...overrides
  };
}

function triageEntry(findingOverrides = {}, capabilityOverrides = {}) {
  return {
    finding: findingRow(findingOverrides),
    repairEligible: false,
    repairRefusalReason: "AMBIGUOUS_TARGET",
    ...capabilityOverrides
  };
}

function lintPayload(findings, checkedPageCount = 3) {
  return { data: { workspaceId: 7, checkedPageCount, findings } };
}

function pagePayload(overrides = {}) {
  return {
    data: {
      knowledgeId: "wiki-hub",
      title: "Hub Page",
      pageType: "CONCEPT",
      revision: 4,
      ...overrides
    }
  };
}

function controllerWithLint(findings, pageResponse = pagePayload()) {
  const elements = uiElements();
  const calls = [];
  const fetchImpl = async (url) => {
    calls.push(url);
    if (url.startsWith("/api/v1/wiki/")) return jsonResponse(200, pageResponse);
    return jsonResponse(200, lintPayload(findings));
  };
  const controller = createQualityController(elements, fetchImpl, fakeDocument());
  return { elements, calls, controller };
}

function eligibleEntry(findingOverrides = {}) {
  return triageEntry(findingOverrides, { repairEligible: true, repairRefusalReason: null });
}

test("label maps cover the finding taxonomy with raw fallback", () => {
  assert.deepEqual([...FINDING_CATEGORIES], ["REFERENCE", "CANONICAL_CONTENT"]);
  assert.deepEqual([...FINDING_SEVERITIES], ["ERROR", "WARNING"]);
  assert.equal(categoryLabel("REFERENCE"), "參照");
  assert.equal(severityLabel("ERROR"), "錯誤");
  assert.equal(codeLabel("BROKEN_INTERNAL_LINK"), "內部連結失效");
  assert.equal(codeLabel("FUTURE_CODE"), "FUTURE_CODE");
  assert.equal(severityLabel("INFO"), "INFO");
});

test("list renders backend order with badges and counts", async () => {
  const findings = [
    triageEntry(),
    triageEntry({ code: "ORPHAN_PAGE", category: "REFERENCE", severity: "WARNING", knowledgeId: "wiki-lonely", detail: "no inbound reference from other published pages" })
  ];
  const { elements, controller } = controllerWithLint(findings);
  await controller.refresh();

  assert.equal(elements.triageList.children.length, 2);
  const body = flatText(elements.triageList);
  assert.match(body, /內部連結失效/);
  assert.match(body, /孤立頁面/);
  // Backend deterministic order is preserved, never client-side re-sorted.
  assert.ok(body.indexOf("wiki-hub") < body.indexOf("wiki-lonely"));
  assert.match(elements.triageMeta.textContent, /共掃描 3 頁/);
  assert.match(elements.triageMeta.textContent, /符合篩選 2 項/);
  assert.equal(elements.triageEmpty.hidden, true);
});

test("filters narrow the snapshot without refetching", async () => {
  const findings = [
    triageEntry(),
    triageEntry({ code: "ORPHAN_PAGE", severity: "WARNING", knowledgeId: "wiki-lonely" }),
    triageEntry({ code: "CANONICAL_CONTENT_INVALID", category: "CANONICAL_CONTENT", knowledgeId: "wiki-drift", detail: "hash differs" })
  ];
  const { elements, calls, controller } = controllerWithLint(findings);
  await controller.refresh();
  const fetchCount = calls.length;

  elements.severityFilter.value = "WARNING";
  await controller.applyFilter();
  assert.equal(elements.triageList.children.length, 1);
  assert.match(flatText(elements.triageList), /wiki-lonely/);
  assert.equal(calls.length, fetchCount);

  elements.severityFilter.value = "";
  elements.categoryFilter.value = "CANONICAL_CONTENT";
  await controller.applyFilter();
  assert.equal(elements.triageList.children.length, 1);
  assert.match(flatText(elements.triageList), /wiki-drift/);
});

test("empty snapshot shows the empty state", async () => {
  const { elements, controller } = controllerWithLint([]);
  await controller.refresh();

  assert.equal(elements.triageList.children.length, 0);
  assert.equal(elements.triageEmpty.hidden, false);
  assert.match(elements.triageMeta.textContent, /符合篩選 0 項/);
});

test("detail renders finding, authoritative preview, and stale handling", async () => {
  const { elements, controller } = controllerWithLint([triageEntry()]);
  await controller.refresh();
  await controller.selectFinding(0);

  assert.equal(elements.triageDetail.hidden, false);
  assert.match(elements.triageDetailTitle.textContent, /wiki-hub/);
  assert.match(elements.triageDetailMeta.textContent, /vault\/concepts\/hub-page\.md/);
  assert.match(elements.triageDetailExplanation.textContent, /missing page/);
  assert.match(flatText(elements.triagePage), /Hub Page/);
  assert.match(flatText(elements.triagePage), /revision 4/);
  await controller.closeDetail();
  assert.equal(elements.triageDetail.hidden, true);
});

test("stale page never fabricates content", async () => {
  const elements = uiElements();
  const fetchImpl = async (url) => {
    if (url.startsWith("/api/v1/wiki/")) {
      return jsonResponse(404, { error: { code: "WIKI_PAGE_NOT_FOUND", message: "gone" } });
    }
    return jsonResponse(200, lintPayload([triageEntry()]));
  };
  const controller = createQualityController(elements, fetchImpl, fakeDocument());
  await controller.refresh();
  await controller.selectFinding(0);

  assert.match(elements.triagePageHint.textContent, /已不存在/);
  assert.equal(flatText(elements.triagePage), "");
});

test("missing workspace surfaces the typed hint without throwing", async () => {
  const elements = uiElements();
  const fetchImpl = async () => jsonResponse(404,
    { error: { code: "NO_ACTIVE_WORKSPACE", message: "none" } });
  const controller = createQualityController(elements, fetchImpl, fakeDocument());
  await controller.refresh();

  assert.match(elements.triageHint.textContent, /尚未開啟工作區/);
  assert.equal(elements.triageList.children.length, 0);
});

test("transport failure surfaces the generic hint", async () => {
  const elements = uiElements();
  const controller = createQualityController(elements, async () => {
    throw new Error("network down");
  }, fakeDocument());
  await controller.refresh();

  assert.match(elements.triageHint.textContent, /讀取失敗/);
});

test("workspace switch clears state and re-reads authoritative data", async () => {
  const documentRef = fakeDocument();
  const { elements, calls, controller } = (() => {
    const elements = uiElements();
    const calls = [];
    const fetchImpl = async (url) => {
      calls.push(url);
      return jsonResponse(200, lintPayload([triageEntry()]));
    };
    return { elements, calls, controller: createQualityController(elements, fetchImpl, documentRef) };
  })();
  await controller.refresh();
  assert.equal(elements.triageList.children.length, 1);

  await documentRef.listeners.get("workspace-changed")();
  assert.equal(elements.triageList.children.length, 0);
  assert.equal(elements.triageDetail.hidden, true);
  assert.ok(calls.filter(url => url === "/api/v1/vault-lint/findings").length >= 2);
});

test("untrusted finding content renders as inert text only", async () => {
  const evil = triageEntry({
    knowledgeId: "wiki-<script>alert(1)</script>",
    logicalPath: "vault/concepts/<img src=x onerror=alert(1)>.md",
    detail: "<img src=x onerror=alert(2)>"
  });
  const { elements, controller } = controllerWithLint([evil]);
  await controller.refresh();
  await controller.selectFinding(0);

  const body = flatText(elements.triageList)
    + elements.triageDetailTitle.textContent
    + elements.triageDetailMeta.textContent
    + elements.triageDetailExplanation.textContent;
  assert.match(body, /<script>/);
  assert.match(body, /onerror/);
});

test("module carries no mutation affordance beyond the governed repair command", async () => {
  const source = await readFile(
    new URL("../../main/resources/static/quality-ui.js", import.meta.url), "utf8");
  assert.doesNotMatch(source, /innerHTML/);
  assert.doesNotMatch(source, /method:\s*"PATCH"/);
  assert.doesNotMatch(source, /method:\s*"PUT"/);
  assert.doesNotMatch(source, /method:\s*"DELETE"/);
  // Exactly one POST exists: the governed repair command carrying only the
  // canonical identity. Finding detail, paths, and repair text never travel.
  assert.equal(source.match(/method:\s*"POST"/g)?.length ?? 0, 1);
  assert.match(source, /\/api\/v1\/repair\/proposals/);
  assert.match(source, /JSON\.stringify\(\{\s*knowledgeId/);
  assert.doesNotMatch(source, /normalizeTitle|extractWikilink|frontmatter/i);
  // No finding-code decision matrix: the action exists solely from the backend
  // capability, codes only map to presentation labels, unknown codes fail closed.
  // (The [^=] guards distinguish assignment from === / !== comparisons.)
  assert.doesNotMatch(source, /entry\.repairEligible\s*=\s*[^=]/);
  assert.doesNotMatch(source, /repairEligible\s*=\s*[^=]true/);
  assert.doesNotMatch(source, /case\s*"(BROKEN_INTERNAL_LINK|ORPHAN_PAGE|CANONICAL_CONTENT_INVALID)"/);
  assert.match(source, /workspace-changed/);
  assert.equal(repairRefusalLabel("AMBIGUOUS_TARGET"), "連結目標不明確，無法推導修復動作，僅供分類檢視。");
  assert.equal(repairRefusalLabel("SOMETHING_NEW"), "SOMETHING_NEW");
});

test("repair action renders only from the backend capability", async () => {
  const { elements, controller } = controllerWithLint([
    eligibleEntry({ code: "CANONICAL_CONTENT_INVALID", category: "CANONICAL_CONTENT", knowledgeId: "wiki-drift", detail: "hash differs" }),
    triageEntry({ code: "ORPHAN_PAGE", severity: "WARNING", knowledgeId: "wiki-lonely" },
      { repairEligible: false, repairRefusalReason: "SEMANTIC_JUDGMENT_REQUIRED" })
  ]);
  await controller.refresh();

  await controller.selectFinding(0);
  assert.equal(elements.triageRepair.hidden, false);
  assert.equal(elements.triageRefusal.hidden, true);

  await controller.selectFinding(1);
  assert.equal(elements.triageRepair.hidden, true);
  assert.equal(elements.triageRefusal.hidden, false);
  assert.match(elements.triageRefusal.textContent, /語意判斷/);
});

test("repair posts only the canonical identity and reports governed outcomes", async () => {
  const elements = uiElements();
  const posts = [];
  let repairCalls = 0;
  const fetchImpl = async (url, options) => {
    if (url === "/api/v1/repair/proposals") {
      repairCalls += 1;
      posts.push(JSON.parse(options.body));
      return jsonResponse(201, { data: { proposal: { id: 9 }, duplicate: false } });
    }
    if (url.startsWith("/api/v1/wiki/")) return jsonResponse(200, pagePayload());
    return jsonResponse(200, lintPayload([
      eligibleEntry({ code: "CANONICAL_CONTENT_INVALID", category: "CANONICAL_CONTENT", knowledgeId: "wiki-drift", detail: "hash differs" })
    ]));
  };
  const controller = createQualityController(elements, fetchImpl, fakeDocument());
  await controller.refresh();
  await controller.selectFinding(0);
  await controller.createRepairProposal();

  assert.equal(repairCalls, 1);
  assert.deepEqual(posts[0], { knowledgeId: "wiki-drift" });
  assert.match(elements.triageRepairHint.textContent, /審核/);
});

test("duplicate repair reports the existing proposal without forking", async () => {
  const elements = uiElements();
  const fetchImpl = async (url, options) => {
    if (url === "/api/v1/repair/proposals") {
      return jsonResponse(200, { data: { proposal: { id: 9 }, duplicate: true } });
    }
    if (url.startsWith("/api/v1/wiki/")) return jsonResponse(200, pagePayload());
    return jsonResponse(200, lintPayload([
      eligibleEntry({ code: "CANONICAL_CONTENT_INVALID", category: "CANONICAL_CONTENT", knowledgeId: "wiki-drift", detail: "hash differs" })
    ]));
  };
  const controller = createQualityController(elements, fetchImpl, fakeDocument());
  await controller.refresh();
  await controller.selectFinding(0);
  await controller.createRepairProposal();

  assert.match(elements.triageRepairHint.textContent, /已存在/);
});

test("stale and ineligible repair commands surface typed hints and reload", async () => {
  for (const [code, payload, pattern] of [
    [409, { error: { code: "REPAIR_FINDING_STALE" } }, /已變動/],
    [422, { error: { code: "REPAIR_NOT_ELIGIBLE" } }, /無法修復/]
  ]) {
    const elements = uiElements();
    let lintCalls = 0;
    const fetchImpl = async (url) => {
      if (url === "/api/v1/repair/proposals") return jsonResponse(code, payload);
      if (url.startsWith("/api/v1/wiki/")) return jsonResponse(200, pagePayload());
      lintCalls += 1;
      return jsonResponse(200, lintPayload([
        eligibleEntry({ code: "CANONICAL_CONTENT_INVALID", category: "CANONICAL_CONTENT", knowledgeId: "wiki-drift", detail: "hash differs" })
      ]));
    };
    const controller = createQualityController(elements, fetchImpl, fakeDocument());
    await controller.refresh();
    await controller.selectFinding(0);
    await controller.createRepairProposal();

    assert.match(elements.triageRepairHint.textContent, pattern);
    assert.ok(lintCalls >= 2);
  }
});

test("concurrent repair clicks send a single command", async () => {
  const elements = uiElements();
  let repairCalls = 0;
  let release;
  const gate = new Promise(resolve => { release = resolve; });
  const fetchImpl = async (url) => {
    if (url === "/api/v1/repair/proposals") {
      repairCalls += 1;
      await gate;
      return jsonResponse(201, { data: { proposal: { id: 9 }, duplicate: false } });
    }
    if (url.startsWith("/api/v1/wiki/")) return jsonResponse(200, pagePayload());
    return jsonResponse(200, lintPayload([
      eligibleEntry({ code: "CANONICAL_CONTENT_INVALID", category: "CANONICAL_CONTENT", knowledgeId: "wiki-drift", detail: "hash differs" })
    ]));
  };
  const controller = createQualityController(elements, fetchImpl, fakeDocument());
  await controller.refresh();
  await controller.selectFinding(0);
  const first = controller.createRepairProposal();
  const second = controller.createRepairProposal();
  release();
  await Promise.all([first, second]);

  assert.equal(repairCalls, 1);
});
