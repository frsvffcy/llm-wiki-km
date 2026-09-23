import assert from "node:assert/strict";
import test from "node:test";

import { createDynamicPanelFocus } from "../../main/resources/static/dynamic-panel-focus.js";

class FakeElement {
  constructor(documentRef, route = null) {
    this.documentRef = documentRef;
    this.dataset = route ? { route } : {};
    this.parentElement = null;
    this.hidden = false;
    this.inert = false;
    this.disabled = false;
    this.isConnected = true;
    this.attributes = new Map();
    this.focusCount = 0;
  }

  focus() {
    this.focusCount += 1;
    this.documentRef.activeElement = this;
  }

  getAttribute(name) { return this.attributes.get(name) ?? null; }
  hasAttribute(name) { return this.attributes.has(name); }
}

function makeRoute(documentRef, route) {
  const section = new FakeElement(documentRef, route);
  const heading = new FakeElement(documentRef);
  heading.dataset.viewHeading = "";
  heading.parentElement = section;
  section.querySelector = selector => selector === "[data-view-heading]" ? heading : null;
  return { section, heading };
}

function harness(routeName = "wiki") {
  const windowListeners = new Map();
  const documentListeners = new Map();
  const windowRef = {
    location: { hash: `#/${routeName}` },
    addEventListener(name, handler) { windowListeners.set(name, handler); }
  };
  const documentRef = {
    activeElement: null,
    defaultView: windowRef,
    sections: [],
    addEventListener(name, handler) { documentListeners.set(name, handler); },
    querySelectorAll(selector) { return selector === "[data-route]" ? this.sections : []; }
  };
  const current = makeRoute(documentRef, routeName);
  documentRef.sections.push(current.section);
  const panel = new FakeElement(documentRef);
  panel.parentElement = current.section;
  const heading = new FakeElement(documentRef);
  heading.parentElement = panel;
  const opener = new FakeElement(documentRef);
  opener.parentElement = current.section;
  const focus = createDynamicPanelFocus({ panel, heading, documentRef });
  return { documentRef, windowRef, windowListeners, documentListeners,
    current, panel, heading, opener, focus };
}

test("動態面板載入成功後聚焦標題，關閉時返回仍有效的觸發項", () => {
  const { panel, heading, opener, focus } = harness();
  const context = focus.begin(opener);
  focus.focusPanel(context);

  assert.equal(heading.focusCount, 1);
  assert.equal(panel.hidden, false);
  assert.equal(focus.close(), "opener");
  assert.equal(opener.focusCount, 1);
});

test("路由切換會取消待執行聚焦，關閉時聚焦目前檢視標題", () => {
  const { documentRef, windowRef, windowListeners, current, opener, focus } = harness();
  const context = focus.begin(opener);
  const other = makeRoute(documentRef, "review");
  current.section.hidden = true;
  documentRef.sections.push(other.section);
  windowRef.location.hash = "#/review";
  windowListeners.get("hashchange")();

  assert.equal(focus.isCurrent(context), false);
  assert.equal(focus.close(), "view-heading");
  assert.equal(opener.focusCount, 0);
  assert.equal(other.heading.focusCount, 1);
});

test("工作區切換會將焦點移出面板並清除觸發項", () => {
  const { documentRef, documentListeners, heading, opener, focus } = harness();
  const context = focus.begin(opener);
  focus.focusPanel(context);

  documentListeners.get("workspace-changed")();
  assert.equal(heading.focusCount, 1);
  assert.equal(opener.focusCount, 0);
  assert.equal(focus.isCurrent(context), false);
  assert.equal(focus.close(), "view-heading");
  assert.equal(opener.focusCount, 0);
});

test("觸發項已移除或隱藏時，關閉改聚焦目前檢視標題", () => {
  for (const invalidateOpener of [
    opener => { opener.isConnected = false; },
    opener => { opener.hidden = true; }
  ]) {
    const { current, opener, focus } = harness();
    focus.begin(opener);
    invalidateOpener(opener);
    assert.equal(focus.close(), "view-heading");
    assert.equal(opener.focusCount, 0);
    assert.equal(current.heading.focusCount, 1);
  }
});
