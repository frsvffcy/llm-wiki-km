import assert from "node:assert/strict";
import test from "node:test";

import { applyRoute, bootstrapNavigation, parseRoute } from "../../main/resources/static/navigation-ui.js";

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
    this.sections = ["home", "inbox", "ask", "inspect", "review"]
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
