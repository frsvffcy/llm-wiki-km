import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import { applyRoute, navGroup, NAV_GROUPS, parseRoute } from "../../main/resources/static/navigation-ui.js";

const STYLES_URL = new URL("../../main/resources/static/styles.css", import.meta.url);
const INDEX_URL = new URL("../../main/resources/static/index.html", import.meta.url);
const NAV_URL = new URL("../../main/resources/static/navigation-ui.js", import.meta.url);

class FakeHeading {
  constructor() { this.focused = false; }
  focus() { this.focused = true; }
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
  setAttribute(name, value) { if (name === "aria-current") this.current = value; }
  removeAttribute(name) { if (name === "aria-current") this.current = null; }
}
class FakeDocument {
  constructor() {
    this.sections = ["home", "wiki", "inbox", "ask", "inspect", "review", "quality"]
      .map(route => new FakeSection(route));
    this.links = this.sections.map(section => new FakeLink(section.dataset.route));
    this.defaultView = { location: { hash: "" }, addEventListener() {} };
  }
  querySelectorAll(selector) {
    if (selector === "[data-route]") return this.sections;
    if (selector === "[data-route-link]") return this.links;
    return [];
  }
}

test("IA groups 7 routes without changing hash contract or hiding governance (#495)", async () => {
  assert.deepEqual([...NAV_GROUPS.core], ["home", "wiki", "inbox", "ask"]);
  assert.deepEqual([...NAV_GROUPS.govern], ["inspect", "review", "quality"]);
  assert.equal(navGroup("home"), "core");
  assert.equal(navGroup("ask"), "core");
  assert.equal(navGroup("inspect"), "govern");
  assert.equal(navGroup("review"), "govern");
  assert.equal(navGroup("quality"), "govern");
  assert.equal(navGroup("nonsense"), null);

  const html = await readFile(INDEX_URL, "utf8");
  const nav = html.match(/<nav[^>]*class="app-nav"[^>]*>[\s\S]*?<\/nav>/u);
  assert.ok(nav, "app-nav shell must exist");
  const hrefs = [...nav[0].matchAll(/href="#\/([a-z]+)"/gu)].map(m => m[1]);
  assert.deepEqual(hrefs, ["home", "wiki", "inbox", "ask", "inspect", "review", "quality"],
    "hash URLs and ordering stay compatible");
  for (const route of hrefs) {
    assert.match(nav[0], new RegExp(`data-route-link="${route}"`, "u"));
  }
  // Governance entries stay visible links, diagnostics is in the second group.
  const governGroup = nav[0].match(/aria-label="驗證與治理"[\s\S]*?(?=<div class="app-nav-group"|<\/nav>)/u);
  assert.ok(governGroup);
  assert.match(governGroup[0], /data-route-link="inspect"/u);
  assert.match(governGroup[0], /data-route-link="review"/u);
  assert.match(governGroup[0], /data-route-link="quality"/u);
  assert.ok(nav[0].includes('role="group"'), "groups expose screen-reader structure");
});

test("active route is aria-current and not hover-only (#495)", async () => {
  const documentRef = new FakeDocument();
  applyRoute(documentRef, "review");
  const active = documentRef.links.find(l => l.dataset.routeLink === "review");
  assert.equal(active.current, "page");
  for (const link of documentRef.links.filter(l => l.dataset.routeLink !== "review")) {
    assert.equal(link.current, null);
  }
  const css = await readFile(STYLES_URL, "utf8");
  const activeRule = css.match(/\.app-nav a\[aria-current="page"\]\s*\{[^}]*\}/u);
  assert.ok(activeRule, "active state owns a dedicated rule");
  assert.match(activeRule[0], /font-weight\s*:\s*700/u, "active is not color-only (weight)");
  assert.match(activeRule[0], /box-shadow|text-decoration|border/u, "active has a non-color marker");
  const hoverRule = css.match(/\.app-nav a:hover\s*\{[^}]*\}/u);
  assert.ok(hoverRule);
  assert.match(hoverRule[0], /text-decoration\s*:\s*underline/u, "hover adds underline beyond color");
  assert.match(hoverRule[0], /background/u, "hover adds background beyond color");
});

test("keyboard focus is discernible and route change moves heading focus (#495)", async () => {
  const css = await readFile(STYLES_URL, "utf8");
  const focusRule = css.match(/\.app-nav a:focus-visible\s*\{[^}]*\}/u);
  assert.ok(focusRule, "nav links own :focus-visible");
  assert.match(focusRule[0], /outline/u);
  assert.match(focusRule[0], /text-decoration|background|box-shadow/u,
    "focus is not outline-color-only");

  const documentRef = new FakeDocument();
  applyRoute(documentRef, "wiki");
  assert.equal(documentRef.sections.find(s => s.dataset.route === "wiki").heading.focused, false);
  applyRoute(documentRef, "wiki", { focus: true });
  assert.equal(documentRef.sections.find(s => s.dataset.route === "wiki").heading.focused, true);
  assert.equal(parseRoute("#/wiki"), "wiki");
  assert.equal(parseRoute("#/nonsense"), "home");
});

test("route navigation does not touch workspace state or fetch (#495)", async () => {
  const source = await readFile(NAV_URL, "utf8");
  assert.doesNotMatch(source, /fetch\s*\(/u, "navigation never fetches");
  assert.doesNotMatch(source, /\/api\/v1/u, "navigation never calls backend");
  assert.doesNotMatch(source, /workspace/i, "navigation never reads workspace state");
  assert.doesNotMatch(source, /localStorage|sessionStorage/u);
  const documentRef = new FakeDocument();
  documentRef.sentinel = "untouched";
  applyRoute(documentRef, "inbox");
  assert.equal(documentRef.sentinel, "untouched");
});

test("no framework or third-party navigation dependency (#495)", async () => {
  const [js, html, css] = await Promise.all([
    readFile(NAV_URL, "utf8"),
    readFile(INDEX_URL, "utf8"),
    readFile(STYLES_URL, "utf8")
  ]);
  assert.doesNotMatch(js, /from\s+["'](react|vue|svelte|preact|lit|router)/i);
  assert.doesNotMatch(js, /import\s*\(/u, "no dynamic framework import");
  assert.doesNotMatch(html, /react|vue|svelte|tailwind|bootstrap/i);
  assert.doesNotMatch(html, /<script[^>]+src="https?:\/\//i);
  assert.doesNotMatch(css, /url\(\s*["']?https?:\/\//i);
});

test("narrow viewport keeps every route reachable with touch targets (#495)", async () => {
  const css = await readFile(STYLES_URL, "utf8");
  assert.match(css, /@media\s*\(max-width:\s*600px\)[\s\S]*?\.app-nav/u,
    "narrow breakpoint owns a navigation contract");
  const narrow = css.slice(css.indexOf("@media (max-width: 600px)"));
  assert.match(narrow, /\.app-nav-group/u, "groups stack instead of squeezing");
  assert.match(narrow, /min-height\s*:\s*44px/u, "narrow links meet a touch target");
  assert.doesNotMatch(css, /overflow-x\s*:\s*hidden[\s\S]*?\.app-nav/u,
    "routes must not be clipped away");
});
