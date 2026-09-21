import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import { applyRoute, fetchReviewPendingCount, navGroup, NAV_GROUPS, parseRoute, renderReviewBadge } from "../../main/resources/static/navigation-ui.js";

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

test("IA groups 7 routes task-first without changing hash contract or hiding diagnostics (#571)", async () => {
  assert.deepEqual([...NAV_GROUPS.basic], ["home", "inbox", "wiki", "ask", "review"]);
  assert.deepEqual([...NAV_GROUPS.advanced], ["inspect", "quality"]);
  assert.equal(navGroup("home"), "basic");
  assert.equal(navGroup("inbox"), "basic");
  assert.equal(navGroup("ask"), "basic");
  assert.equal(navGroup("review"), "basic");
  assert.equal(navGroup("inspect"), "advanced");
  assert.equal(navGroup("quality"), "advanced");
  assert.equal(navGroup("nonsense"), null);

  const html = await readFile(INDEX_URL, "utf8");
  const nav = html.match(/<nav[^>]*class="app-nav"[^>]*>[\s\S]*?<\/nav>/u);
  assert.ok(nav, "app-nav shell must exist");
  const hrefs = [...nav[0].matchAll(/href="#\/([a-z]+)"/gu)].map(m => m[1]);
  assert.deepEqual(hrefs, ["home", "inbox", "wiki", "ask", "review", "inspect", "quality"],
    "hash URLs stay compatible; visual order follows the task flow");
  for (const route of hrefs) {
    assert.match(nav[0], new RegExp(`data-route-link="${route}"`, "u"));
  }
  // Task-language labels: no implementation terms in primary navigation.
  for (const [route, label] of [["home", "開始"], ["inbox", "文件"], ["wiki", "知識"],
      ["ask", "提問"], ["review", "待我審核"]]) {
    assert.match(nav[0], new RegExp(`data-route-link="${route}">[^<]*${label}`, "u"),
      `${route} uses task language`);
  }
  // Advanced entries stay visible links with deep links intact, grouped second.
  const advancedGroup = nav[0].match(/aria-label="進階維護"[\s\S]*?(?=<div class="app-nav-group"|<\/nav>)/u);
  assert.ok(advancedGroup);
  assert.match(advancedGroup[0], /data-route-link="inspect"/u);
  assert.match(advancedGroup[0], /data-route-link="quality"/u);
  assert.ok(nav[0].includes('role="group"'), "groups expose screen-reader structure");
  // Review carries the pending badge anchor without抢占 primary attention by itself.
  assert.match(nav[0], /id="review-pending-badge"/u);
  assert.match(nav[0], /<span id="review-pending-badge"[^>]*hidden/u,
    "the badge starts hidden; only a positive backend count reveals it");
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
  assert.equal(parseRoute("#/ask?documentId=42"), "ask");
  assert.equal(parseRoute("#/nonsense"), "home");
});

test("route navigation never fetches; the pending badge is the single backend touchpoint (#571)", async () => {
  const source = await readFile(NAV_URL, "utf8");
  // Routing core stays fetch-free: applyRoute/parseRoute/bootstrapNavigation
  // only toggle visibility and nav state.
  // Routing core stays fetch-free: slice each routing function body (up to the
  // next top-level export) and assert no backend touchpoint inside.
  for (const marker of ["export function parseRoute", "export function applyRoute",
      "export function bootstrapNavigation"]) {
    const start = source.indexOf(marker);
    assert.ok(start !== -1, `${marker} must exist`);
    const next = source.indexOf("\nexport ", start + marker.length);
    const body = next === -1 ? source.slice(start) : source.slice(start, next);
    assert.doesNotMatch(body, /fetchImpl\s*\(/u, "routing never fetches");
    assert.doesNotMatch(body, /\/api\/v1/u, "routing never calls backend");
  }
  // The badge path touches exactly one read-only count endpoint and only
  // listens to workspace-changed as a reset trigger (never reads workspace state).
  const endpoints = [...source.matchAll(/\/api\/v1\/[a-z][a-z0-9/\-_]*/gu)].map(m => m[0]);
  assert.deepEqual(endpoints, ["/api/v1/proposals"],
    "the badge reads only the REVIEW count, nothing else");
  // The badge listens to workspace-changed only as a refresh trigger: no workspace
  // identity is ever read (no id lookup, no workspace API, no stored state).
  assert.doesNotMatch(source, /workspaceId|activeWorkspace|\/api\/v1\/workspaces/iu,
    "badge never reads workspace state");
  assert.match(source, /workspace-changed/u, "badge refreshes on workspace switches");
  assert.doesNotMatch(source, /localStorage|sessionStorage/u);
  const documentRef = new FakeDocument();
  documentRef.sentinel = "untouched";
  applyRoute(documentRef, "inbox");
  assert.equal(documentRef.sentinel, "untouched");
});

test("pending badge renders a positive count and fails closed otherwise (#571)", async () => {
  const okFetch = async url => {
    assert.match(String(url), /\/api\/v1\/proposals\?status=REVIEW&page=0&size=1/u);
    return { ok: true, status: 200, json: async () => ({ data: [], page: { totalElements: 3 } }) };
  };
  assert.equal(await fetchReviewPendingCount(okFetch), 3);

  const emptyFetch = async () => (
    { ok: true, status: 200, json: async () => ({ data: [], page: { totalElements: 0 } }) });
  assert.equal(await fetchReviewPendingCount(emptyFetch), 0);

  const brokenFetch = async () => ({ ok: false, status: 500, json: async () => ({}) });
  await assert.rejects(() => fetchReviewPendingCount(brokenFetch),
    "failures throw so the caller hides the badge instead of guessing");
  const malformedFetch = async () => ({ ok: true, status: 200, json: async () => ({ data: [] }) });
  await assert.rejects(() => fetchReviewPendingCount(malformedFetch));

  const badge = new FakeLink("review");
  badge.hidden = true;
  badge.textContent = "";
  const link = { label: null, setAttribute(name, value) { this.label = value; },
    removeAttribute() { this.label = null; } };
  assert.equal(renderReviewBadge({ badge, reviewLink: link }, 3), 3);
  assert.equal(badge.hidden, false);
  assert.equal(badge.textContent, "3");
  assert.equal(link.label, "待我審核，3 件待審");
  renderReviewBadge({ badge, reviewLink: link }, 0);
  assert.equal(badge.hidden, true);
  assert.equal(badge.textContent, "");
  assert.equal(link.label, null);
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
