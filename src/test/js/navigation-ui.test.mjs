import assert from "node:assert/strict";
import test from "node:test";

import {
  applyRoute,
  bootstrapNavBadge,
  bootstrapNavigation,
  createNavBadgeController,
  parseRoute
} from "../../main/resources/static/navigation-ui.js";

class FakeHeading {
  constructor() {
    this.focused = false;
  }

  focus() {
    this.focused = true;
  }
}

class FakeSection {
  constructor(route) {
    this.dataset = { route };
    this.hidden = false;
    this.heading = new FakeHeading();
  }

  querySelector(selector) {
    return selector === "[data-view-heading]" ? this.heading : null;
  }
}

class FakeLink {
  constructor(route) {
    this.dataset = { routeLink: route };
    this.current = null;
  }

  setAttribute(name, value) {
    if (name === "aria-current") this.current = value;
  }

  removeAttribute(name) {
    if (name === "aria-current") this.current = null;
  }
}

class FakeDocument {
  constructor() {
    this.sections = ["home", "wiki", "inbox", "ask", "inspect", "review", "quality"]
      .map(route => new FakeSection(route));
    this.links = this.sections.map(section => new FakeLink(section.dataset.route));
    this.defaultView = {
      location: { hash: "" },
      handler: null,
      addEventListener(name, handler) {
        if (name === "hashchange") this.handler = handler;
      }
    };
  }

  querySelectorAll(selector) {
    if (selector === "[data-route]") return this.sections;
    if (selector === "[data-route-link]") return this.links;
    return [];
  }

  section(route) {
    return this.sections.find(section => section.dataset.route === route);
  }

  link(route) {
    return this.links.find(link => link.dataset.routeLink === route);
  }
}

test("parseRoute maps hash values to known routes with home as the default", () => {
  assert.equal(parseRoute("#/inbox"), "inbox");
  assert.equal(parseRoute("#/ask"), "ask");
  assert.equal(parseRoute("#/ask?documentId=42"), "ask");
  assert.equal(parseRoute("#/inspect"), "inspect");
  assert.equal(parseRoute("#/review"), "review");
  assert.equal(parseRoute("#/home"), "home");
  assert.equal(parseRoute(""), "home");
  assert.equal(parseRoute("#/"), "home");
  assert.equal(parseRoute("#/nonsense"), "home");
  assert.equal(parseRoute("#/INBOX"), "inbox");
  assert.equal(parseRoute(null), "home");
});

test("applyRoute shows only the target section and marks its nav link", () => {
  const documentRef = new FakeDocument();
  assert.equal(applyRoute(documentRef, "inbox"), "inbox");
  assert.equal(documentRef.section("inbox").hidden, false);
  for (const route of ["home", "ask", "inspect", "review"]) {
    assert.equal(documentRef.section(route).hidden, true);
  }
  assert.equal(documentRef.link("inbox").current, "page");
  for (const route of ["home", "ask", "inspect", "review"]) {
    assert.equal(documentRef.link(route).current, null);
  }
});

test("applyRoute does not move focus unless requested", () => {
  const documentRef = new FakeDocument();
  applyRoute(documentRef, "ask");
  assert.equal(documentRef.section("ask").heading.focused, false);
  applyRoute(documentRef, "ask", { focus: true });
  assert.equal(documentRef.section("ask").heading.focused, true);
});

test("unknown routes fall back to home while keeping the applied route honest", () => {
  const documentRef = new FakeDocument();
  const applied = applyRoute(documentRef, "home");
  assert.equal(applied, "home");
  assert.equal(documentRef.section("home").hidden, false);
});

class FakeBadge {
  constructor() {
    this.hidden = true;
    this.textContent = "";
  }
}

class FakeReviewLink {
  constructor() {
    this.label = null;
  }

  setAttribute(name, value) {
    if (name === "aria-label") this.label = value;
  }

  removeAttribute(name) {
    if (name === "aria-label") this.label = null;
  }
}

function badgeDocument() {
  const badge = new FakeBadge();
  const reviewLink = new FakeReviewLink();
  const docListeners = new Map();
  const viewListeners = new Map();
  return {
    badge,
    reviewLink,
    docListeners,
    viewListeners,
    defaultView: {
      location: { hash: "" },
      addEventListener(name, handler) { viewListeners.set(name, handler); }
    },
    addEventListener(name, handler) { docListeners.set(name, handler); },
    querySelector(selector) {
      if (selector === "#review-pending-badge") return badge;
      if (selector === '[data-route-link="review"]') return reviewLink;
      return null;
    }
  };
}

function countEnvelope(totalElements) {
  return {
    ok: true,
    status: 200,
    json: async () => ({ data: [], page: { number: 0, totalElements } })
  };
}

test("badge controller refreshes on route change and workspace switch, hiding on failure (#571)", async () => {
  const documentRef = badgeDocument();
  let pending = 2;
  const calls = [];
  const fetchImpl = async url => {
    calls.push(String(url));
    return countEnvelope(pending);
  };
  const controller = createNavBadgeController(
    { badge: documentRef.badge, reviewLink: documentRef.reviewLink }, fetchImpl, documentRef);
  await controller.refresh();
  assert.equal(documentRef.badge.hidden, false);
  assert.equal(documentRef.badge.textContent, "2");

  pending = 0;
  await controller.refresh();
  assert.equal(documentRef.badge.hidden, true,
    "no pending work leaves primary attention alone");

  controller.reset();
  assert.equal(documentRef.badge.hidden, true);
  assert.equal(calls.length, 2);
});

test("badge refresh never rejects: backend failures hide instead of blocking (#571)", async () => {
  const documentRef = badgeDocument();
  const failingFetch = async () => ({ ok: false, status: 500, json: async () => ({}) });
  const controller = createNavBadgeController(
    { badge: documentRef.badge, reviewLink: documentRef.reviewLink }, failingFetch, documentRef);
  await controller.refresh();
  assert.equal(documentRef.badge.hidden, true);
});

test("badge bootstrap wires hashchange and workspace-changed and skips cleanly without the anchor (#571)", async () => {
  const documentRef = badgeDocument();
  const fetchImpl = async () => countEnvelope(4);
  const controller = bootstrapNavBadge(documentRef, fetchImpl);
  assert.ok(controller);
  assert.ok(documentRef.viewListeners.has("hashchange"));
  assert.ok(documentRef.docListeners.has("workspace-changed"));
  for (let attempt = 0; attempt < 10 && documentRef.badge.hidden; attempt++) {
    await new Promise(resolve => setTimeout(resolve, 0));
  }
  assert.equal(documentRef.badge.textContent, "4");

  documentRef.viewListeners.get("hashchange")();
  documentRef.docListeners.get("workspace-changed")();

  const bare = { defaultView: { location: { hash: "" }, addEventListener() {} },
    addEventListener() {}, querySelector: () => null };
  assert.equal(bootstrapNavBadge(bare, fetchImpl), null);
});

test("bootstrapNavigation applies the initial route without focus and re-applies on hashchange", () => {
  const documentRef = new FakeDocument();
  documentRef.defaultView.location.hash = "#/inspect";
  bootstrapNavigation(documentRef);
  assert.equal(documentRef.section("inspect").hidden, false);
  assert.equal(documentRef.section("inspect").heading.focused, false,
    "initial paint must not steal focus");

  documentRef.defaultView.location.hash = "#/inbox";
  documentRef.defaultView.handler();
  assert.equal(documentRef.section("inbox").hidden, false);
  assert.equal(documentRef.section("inbox").heading.focused, true,
    "route changes move focus to the view heading for orientation");
  assert.equal(documentRef.section("inspect").hidden, true);
});
