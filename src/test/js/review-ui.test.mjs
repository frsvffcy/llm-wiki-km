import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import {
  createReviewController,
  draftStatusLabel,
  governanceErrorMessage,
  invalidationLabel,
  proposalStatusLabel,
  publishOutcomeLabel,
  renderProposalList,
  renderPublishOutcome,
  PROPOSAL_STATUSES
} from "../../main/resources/static/review-ui.js";

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
}

function uiElements() {
  return {
    proposalFilterForm: new FakeElement("form"),
    statusFilter: new FakeElement("select"),
    proposalList: new FakeElement("ul"),
    proposalEmpty: new FakeElement("p"),
    reviewHint: new FakeElement("p"),
    proposalPageInfo: new FakeElement("span"),
    proposalPrevPage: new FakeElement("button"),
    proposalNextPage: new FakeElement("button"),
    proposalDetail: new FakeElement("section"),
    proposalDetailTitle: new FakeElement("h4"),
    proposalDetailMeta: new FakeElement("p"),
    proposalDetailSummary: new FakeElement("p"),
    proposalDetailRationale: new FakeElement("p"),
    proposalDetailTarget: new FakeElement("p"),
    proposalDetailSource: new FakeElement("p"),
    proposalEvidence: new FakeElement("ul"),
    proposalActions: new FakeElement("div"),
    proposalDetailClose: new FakeElement("button"),
    draftCreate: new FakeElement("button"),
    draftPanel: new FakeElement("section"),
    draftMeta: new FakeElement("div"),
    draftHint: new FakeElement("p"),
    draftPreview: new FakeElement("button"),
    draftDiff: new FakeElement("button"),
    draftRegenerate: new FakeElement("button"),
    draftInvalidate: new FakeElement("button"),
    draftPublish: new FakeElement("button"),
    draftContent: new FakeElement("div"),
    publishResult: new FakeElement("p")
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

function proposalRow(overrides = {}) {
  return {
    id: 12, action: "CREATE", status: "REVIEW", title: "transformer 架構",
    summary: "建議建立 transformer 主題頁", rationale: "多份文件高信心指向此主題",
    confidence: 0.87, targetReference: "wiki:transformer",
    sourceDocument: { id: 3, fileName: "notes.pdf", sourcePath: "/host/absolute/inbox/notes.pdf" },
    evidence: [{ sourceChunkId: 9, chunkNo: 2, pageNo: 1, section: "arch",
      headingPath: "system > arch", content: "transformer 使用 self-attention" }],
    ...overrides
  };
}

function proposalDetailPayload(overrides = {}) {
  return { data: proposalDetailRow(overrides) };
}

function proposalDetailRow(overrides = {}) {
  return {
    id: 12, action: "CREATE", status: "REVIEW", title: "transformer 架構",
    summary: "建議建立 transformer 主題頁", rationale: "多份文件高信心指向此主題",
    confidence: 0.87, targetReference: "wiki:transformer",
    sourceDocument: { id: 3, fileName: "notes.pdf", sourcePath: "/host/absolute/inbox/notes.pdf" },
    evidence: [{ sourceChunkId: 9, chunkNo: 2, pageNo: 1, section: "arch",
      headingPath: "system > arch", content: "transformer 使用 self-attention" }],
    ...overrides
  };
}

function draftRow(overrides = {}) {
  return {
    id: 21, proposalId: 12, action: "CREATE", pageType: "CONCEPT",
    title: "transformer 架構", targetTitle: "transformer 架構", targetPageType: "CONCEPT",
    targetKnowledgeId: null, targetPath: "vault/concepts/transformer.md",
    status: "READY", publishReady: true,
    expectedContentHash: "a".repeat(64), baseContentHash: "b".repeat(64),
    renderedContentHash: "c".repeat(64), inputHash: "d".repeat(64),
    sourceChunkIds: [9], invalidatedReason: null, regeneratedFromDraftId: null,
    createdAt: "2026-09-13T00:00:00Z", updatedAt: "2026-09-13T00:00:00Z",
    ...overrides
  };
}

test("proposal statuses and labels cover the backend enum without drift", () => {
  assert.deepEqual(PROPOSAL_STATUSES, ["DRAFT", "REVIEW", "APPROVED", "REJECTED"]);
  assert.equal(proposalStatusLabel("REVIEW"), "審核中");
  assert.equal(proposalStatusLabel("FUTURE_STATE"), "FUTURE_STATE",
    "unknown states render raw instead of a guessed label");
  assert.equal(invalidationLabel("TARGET_CHANGED"), "目標頁面已變動");
  assert.equal(draftStatusLabel("READY"), "就緒（可發布）",
    "draft lifecycle states use their own label map, not the proposal one");
});

test("typed publish outcome and governance errors are operator-safe", () => {
  assert.equal(publishOutcomeLabel("PUBLISHED", "CREATED"), "已發布：新建 wiki 頁面");
  assert.equal(publishOutcomeLabel("NO_OP", "NO_OP"), "無操作：此 draft 先前已成功發布");
  assert.match(publishOutcomeLabel("WEIRD", "X"), /WEIRD/u);
  assert.equal(governanceErrorMessage({ code: "WIKI_PUBLISH_OPTIMISTIC_LOCK_CONFLICT" }).title,
    "內容已被他人更新");
  assert.equal(governanceErrorMessage(undefined).title, "治理操作失敗");
});

test("proposal list renders typed badges, meta, and pager honestly", () => {
  const elements = uiElements();
  renderProposalList(elements, [proposalRow()],
    { number: 0, size: 20, totalElements: 1, totalPages: 1 },
    { createElement: () => new FakeElement() }, { onSelect: () => {} });
  const text = flatText(elements.proposalList);
  assert.match(text, /#12 transformer 架構/u);
  assert.match(text, /審核中/u);
  assert.match(text, /建立新頁 · 信心 0\.87/u);
  assert.equal(elements.proposalPrevPage.disabled, true);
  assert.equal(elements.proposalNextPage.disabled, true);
});

test("detail renders evidence as text and hides host paths from the browser", async () => {
  const elements = uiElements();
  const calls = [];
  const fetchImpl = async url => {
    calls.push(String(url));
    return jsonResponse(200, proposalDetailPayload());
  };
  const controller = createReviewController(elements, fetchImpl, fakeDocument());
  await controller.selectProposal(12);

  assert.equal(calls.at(-1), "/api/v1/proposals/12");
  const evidenceText = flatText(elements.proposalEvidence);
  assert.match(evidenceText, /transformer 使用 self-attention/u);
  const detailText = [
    elements.proposalDetailTitle, elements.proposalDetailMeta,
    elements.proposalDetailSummary, elements.proposalDetailRationale,
    elements.proposalDetailTarget, elements.proposalDetailSource
  ].map(flatText).join(" ");
  assert.match(detailText, /notes\.pdf/u);
  assert.doesNotMatch(detailText, /\/host\/absolute\/inbox\/notes\.pdf/u,
    "workspace-relative stored path must not be rendered (challenge 9)");
  const buttons = elements.proposalActions.children.map(child => child.textContent);
  assert.deepEqual(buttons, ["核准", "拒絕"], "REVIEW offers approve/reject only");
});

test("proposal transition patches the existing contract and never auto-publishes", async () => {
  const elements = uiElements();
  const calls = [];
  const fetchImpl = async (url, options) => {
    calls.push({ url: String(url), method: options?.method, body: options?.body });
    if (String(url).endsWith("/status")) {
      return jsonResponse(200, proposalDetailPayload({ status: "APPROVED" }));
    }
    if (String(url).includes("/proposals/")) {
      return jsonResponse(200, proposalDetailPayload({ status: "APPROVED" }));
    }
    return jsonResponse(200, { data: [], page: { number: 0, totalPages: 0 } });
  };
  const controller = createReviewController(elements, fetchImpl, fakeDocument());

  await controller.transitionProposal(12, "APPROVED");

  assert.deepEqual(calls[0], {
    url: "/api/v1/proposals/12/status",
    method: "PATCH",
    body: JSON.stringify({ status: "APPROVED" })
  });
  assert.ok(calls.every(call => !String(call.url).includes("/publish")),
    "approval must not trigger publish (challenge 1)");
  assert.equal(elements.draftCreate.hidden, false,
    "an APPROVED proposal unlocks the draft lifecycle entry");
});

test("illegal transition surfaces the typed backend 400 instead of guessing", async () => {
  const elements = uiElements();
  const fetchImpl = async (url, options) => {
    if (options?.method === "PATCH") {
      return jsonResponse(400, {
        error: { code: "INVALID_REQUEST", message: "illegal transition" }
      });
    }
    return jsonResponse(200, proposalDetailPayload());
  };
  const controller = createReviewController(elements, fetchImpl, fakeDocument());
  await controller.transitionProposal(12, "APPROVED");
  assert.match(elements.reviewHint.textContent, /此狀態轉換不被允許/u);
});

test("create draft posts proposalId and renders the backend-owned draft state", async () => {
  const elements = uiElements();
  const calls = [];
  const fetchImpl = async (url, options) => {
    calls.push({ url: String(url), method: options?.method, body: options?.body });
    if (url === "/api/v1/wiki-drafts" && options?.method === "POST") {
      return jsonResponse(201, { data: draftRow() });
    }
    if (String(url) === "/api/v1/wiki-drafts/21") {
      return jsonResponse(200, { data: draftRow() });
    }
    if (String(url).includes("/proposals")) {
      return jsonResponse(200, proposalDetailPayload({ status: "APPROVED" }));
    }
    return jsonResponse(200, { data: [], page: { number: 0, totalPages: 0 } });
  };
  const controller = createReviewController(elements, fetchImpl, fakeDocument());
  await controller.selectProposal(12);
  await controller.createDraft();

  assert.deepEqual(calls.find(call => call.method === "POST"), {
    url: "/api/v1/wiki-drafts",
    method: "POST",
    body: JSON.stringify({ proposalId: 12 })
  });
  const meta = flatText(elements.draftMeta);
  assert.match(meta, /Draft #21（proposal #12）/u);
  assert.match(meta, /狀態 就緒（可發布） · publishReady：是/u);
  assert.equal(elements.draftPublish.disabled, false);
});

test("implicit invalidation from the server re-read is rendered as the truth", async () => {
  const elements = uiElements();
  const fetchImpl = async url => {
    if (String(url) === "/api/v1/wiki-drafts/21") {
      return jsonResponse(200, { data: draftRow({
        status: "INVALIDATED", publishReady: false,
        invalidatedReason: "TARGET_CHANGED" }) });
    }
    return jsonResponse(200, proposalDetailPayload({ status: "APPROVED" }));
  };
  const controller = createReviewController(elements, fetchImpl, fakeDocument());
  await controller.loadDraft(21);

  const meta = flatText(elements.draftMeta);
  assert.match(meta, /已失效：目標頁面已變動/u);
  assert.equal(elements.draftPublish.disabled, true,
    "a stale/invalidated draft can never be published (challenge 3)");
});

test("preview and diff render content as text nodes, never markup injection", async () => {
  const elements = uiElements();
  const documentRef = fakeDocument();
  const fetchImpl = async url => {
    if (String(url).endsWith("/preview")) {
      return jsonResponse(200, { data: {
        id: 21, proposalId: 12, action: "CREATE", targetPath: "vault/concepts/x.md",
        status: "READY", publishReady: true, sourceChunkIds: [9],
        evidence: [{ sourceChunkId: 9, chunkNo: 1, pageNo: null, section: "s",
          headingPath: "h", excerpt: "<img src=x onerror=alert(1)> excerpt" }],
        renderedContentHash: "c".repeat(64),
        markdown: "# Title with <script>alert(1)</script>" } });
    }
    if (String(url).endsWith("/diff")) {
      return jsonResponse(200, { data: {
        id: 21, status: "READY", publishReady: true, targetPath: "vault/concepts/x.md",
        baseContentHash: "b".repeat(64), renderedContentHash: "c".repeat(64),
        currentContent: "old", renderedContent: "new",
        unifiedDiff: "--- a/vault/concepts/x.md\n+++ b/vault/concepts/x.md\n@@\n-old\n+new" } });
    }
    return jsonResponse(200, { data: draftRow() });
  };
  const controller = createReviewController(elements, fetchImpl, documentRef);
  await controller.loadDraft(21);
  await controller.showPreview();
  const previewText = flatText(elements.draftContent);
  assert.match(previewText, /<script>alert\(1\)<\/script>/u,
    "unsafe content appears only as inert text");
  await controller.showDiff();
  assert.match(flatText(elements.draftContent), /-old\n\+new/u);
});

test("publish renders the backend typed outcome, including NO_OP on repeat", async () => {
  const elements = uiElements();
  let mode = "create";
  const fetchImpl = async (url, options) => {
    if (String(url).endsWith("/publish")) {
      if (mode === "conflict") {
        return jsonResponse(409, {
          error: { code: "WIKI_PUBLISH_OPTIMISTIC_LOCK_CONFLICT", message: "hash mismatch" }
        });
      }
      if (mode === "merge") {
        return jsonResponse(200, { data: {
          result: "PUBLISHED", outcome: "MERGED", attemptId: 4, operationId: "op",
          workspaceId: 1, proposalId: 12, draftId: 21, knowledgePageId: 2,
          knowledgeId: "WIKI:x", targetPath: "vault/concepts/x.md",
          contentHash: "e".repeat(64), revision: 2, publishedAt: "2026-09-13T01:00:00Z",
          beforeHash: "b".repeat(64), afterHash: "e".repeat(64) } });
      }
      return jsonResponse(201, { data: {
        result: "PUBLISHED", outcome: "CREATED", attemptId: 3, operationId: "op",
        workspaceId: 1, proposalId: 12, draftId: 21, knowledgePageId: 1,
        knowledgeId: "WIKI:x", targetPath: "vault/concepts/x.md",
        contentHash: "e".repeat(64), revision: 1, publishedAt: "2026-09-13T00:00:00Z" } });
    }
    return jsonResponse(200, { data: draftRow({ status: "PUBLISHED", publishReady: false }) });
  };
  const controller = createReviewController(elements, fetchImpl, fakeDocument());
  await controller.loadDraft(21);

  await controller.publishDraft();
  let text = flatText(elements.publishResult);
  assert.match(text, /已發布：新建 wiki 頁面/u);
  assert.match(text, /knowledgeId：WIKI:x/u);

  mode = "merge";
  await controller.publishDraft();
  assert.match(flatText(elements.publishResult), /已發布：合併至既有頁面/u);

  mode = "conflict";
  await controller.publishDraft();
  assert.match(elements.draftHint.textContent, /內容已被他人更新/u);
  assert.equal(flatText(elements.publishResult).includes("已發布：合併至既有頁面"), false,
    "a failed publish must not keep showing the previous success (challenge 4)");
  assert.ok(elements.publishResult.hidden === false || flatText(elements.publishResult).length === 0
    ? flatText(elements.publishResult).includes("已發布：合併至既有頁面") === false : true);
});

test("double-submit guard prevents a second concurrent publish request", async () => {
  const elements = uiElements();
  const calls = [];
  let release;
  const gate = new Promise(resolve => { release = resolve; });
  const fetchImpl = async (url, options) => {
    if (String(url).endsWith("/publish")) {
      calls.push("publish");
      await gate;
      return jsonResponse(201, { data: {
        result: "PUBLISHED", outcome: "CREATED", attemptId: 3, operationId: "op",
        workspaceId: 1, proposalId: 12, draftId: 21, knowledgePageId: 1,
        knowledgeId: "WIKI:x", targetPath: "vault/concepts/x.md",
        contentHash: "e".repeat(64), revision: 1, publishedAt: "2026-09-13T00:00:00Z" } });
    }
    return jsonResponse(200, { data: draftRow({ status: "PUBLISHED", publishReady: false }) });
  };
  const controller = createReviewController(elements, fetchImpl, fakeDocument());
  await controller.loadDraft(21);
  const first = controller.publishDraft();
  const second = controller.publishDraft();
  release();
  await Promise.all([first, second]);
  assert.equal(calls.filter(entry => entry === "publish").length, 1,
    "in-flight publish blocks a second submission (challenge 5)");
});

test("workspace switch clears proposal, draft, and publish state", async () => {
  const documentRef = fakeDocument();
  const elements = uiElements();
  const calls = [];
  const fetchImpl = async url => {
    calls.push(String(url));
    if (String(url) === "/api/v1/wiki-drafts/21") {
      return jsonResponse(200, { data: draftRow() });
    }
    if (String(url).includes("/proposals")) {
      return jsonResponse(200, proposalDetailPayload());
    }
    return jsonResponse(200, { data: [], page: { number: 0, totalPages: 0 } });
  };
  const controller = createReviewController(elements, fetchImpl, documentRef);
  await controller.selectProposal(12);
  elements.proposalDetail.hidden = false;
  elements.statusFilter.value = "APPROVED";

  assert.equal(documentRef.listeners.has("workspace-changed"), true);
  await documentRef.listeners.get("workspace-changed")();

  assert.equal(elements.statusFilter.value, "");
  assert.equal(elements.proposalDetail.hidden, true);
  assert.equal(elements.draftPanel.hidden, true);
  assert.equal(elements.publishResult.hidden, true);
  assert.match(calls.at(-1), /page=0&size=20$/u);
});

test("the module never injects markup via innerHTML", async () => {
  const source = await readFile(
    new URL("../../main/resources/static/review-ui.js", import.meta.url), "utf8");
  assert.doesNotMatch(source, /innerHTML/u,
    "proposal/draft/model content must only enter the DOM as text (challenge 7)");
});
