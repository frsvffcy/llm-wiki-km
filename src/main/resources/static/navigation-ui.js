/**
 * Vanilla-JS hash navigation for the multi-view shell (#352). Views live in the DOM as
 * sibling sections tagged with data-route; routing only toggles visibility and nav
 * state, so every existing panel keeps its element ids, module state, and deep links.
 * No framework, no inline script/style (strict CSP), no route-scoped re-rendering.
 */

const ROUTES = Object.freeze(["home", "wiki", "inbox", "ask", "inspect", "review", "quality"]);

/**
 * #571 task-first information architecture: the 7 hash routes keep their
 * URLs/meanings (bookmarks and deep links stay valid), but are grouped for
 * first-use comprehension. Everyday work (start, files, knowledge, asking,
 * and my pending reviews) comes first in task order; inspector and quality
 * stay one click away under advanced maintenance without dominating daily use.
 */
export const NAV_GROUPS = Object.freeze({
  basic: Object.freeze(["home", "inbox", "wiki", "ask", "review"]),
  advanced: Object.freeze(["inspect", "quality"])
});

export function navGroup(route) {
  if (NAV_GROUPS.basic.includes(route)) return "basic";
  if (NAV_GROUPS.advanced.includes(route)) return "advanced";
  return null;
}

export const NAV_GROUP_LABELS = Object.freeze({
  basic: "基本",
  advanced: "進階"
});

export const VIEW_TITLES = Object.freeze({
  home: "開始",
  inbox: "文件",
  wiki: "知識",
  ask: "提問",
  inspect: "檢視器",
  review: "待我審核",
  quality: "品質"
});

/**
 * Pending-review badge source (#571): the count of REVIEW proposals needing a
 * human decision. Rendered only when greater than zero; any fetch failure or
 * empty state hides the badge instead of blocking navigation (fail closed).
 */
export const REVIEW_PENDING_ENDPOINT = "/api/v1/proposals";

export async function fetchReviewPendingCount(fetchImpl = fetch) {
  const response = await fetchImpl(`${REVIEW_PENDING_ENDPOINT}?status=REVIEW&page=0&size=1`);
  let envelope = null;
  try {
    envelope = await response.json();
  } catch {
    envelope = null;
  }
  if (!response.ok || !envelope || typeof envelope.page !== "object" || envelope.page === null) {
    const error = new Error("review pending count unavailable");
    error.status = response.status;
    throw error;
  }
  const total = Number(envelope.page.totalElements);
  return Number.isFinite(total) && total > 0 ? Math.floor(total) : 0;
}

/**
 * Renders the pending badge onto the review link. Pure over the provided
 * elements: text only, no fetching, no invented authority.
 */
export function renderReviewBadge(elements, count) {
  const total = Number(count);
  if (!elements || !elements.badge || !Number.isFinite(total) || total <= 0) {
    if (elements && elements.badge) {
      elements.badge.hidden = true;
      elements.badge.textContent = "";
    }
    if (elements && elements.reviewLink && typeof elements.reviewLink.removeAttribute === "function") {
      elements.reviewLink.removeAttribute("aria-label");
    }
    return 0;
  }
  const pendingCount = Math.floor(total);
  const pendingLabel = `${pendingCount} 件待審`;
  elements.badge.hidden = false;
  elements.badge.textContent = pendingLabel;
  if (elements.reviewLink && typeof elements.reviewLink.setAttribute === "function") {
    elements.reviewLink.setAttribute("aria-label", `待我審核，${pendingLabel}`);
  }
  return pendingCount;
}

export function parseRoute(hash) {
  const raw = String(hash ?? "").replace(/^#\/?/, "").split("?", 1)[0].trim().toLowerCase();
  return ROUTES.includes(raw) ? raw : "home";
}

/**
 * Shows the section for `route`, hides the others, marks the matching nav link with
 * aria-current, and (optionally) moves focus to the view heading for keyboard/screen-
 * reader orientation. Pure over the provided document ref: no fetching, no state.
 */
export function applyRoute(documentRef, route, { focus = false } = {}) {
  let applied = "home";
  documentRef.querySelectorAll("[data-route]").forEach(section => {
    const isTarget = section.dataset.route === route;
    section.hidden = !isTarget;
    if (isTarget) {
      applied = route;
      if (focus) {
        const heading = section.querySelector("[data-view-heading]");
        if (heading && typeof heading.focus === "function") {
          heading.focus();
        }
      }
    }
  });
  documentRef.querySelectorAll("[data-route-link]").forEach(link => {
    if (link.dataset.routeLink === applied) {
      link.setAttribute("aria-current", "page");
    } else {
      link.removeAttribute("aria-current");
    }
  });
  return applied;
}

export function bootstrapNavigation(documentRef = document) {
  const view = documentRef.defaultView;
  const apply = () => {
    applyRoute(documentRef, parseRoute(view ? view.location.hash : ""), { focus: true });
  };
  // Initial paint never steals focus; only subsequent route changes move it.
  applyRoute(documentRef, parseRoute(view ? view.location.hash : ""), { focus: false });
  if (view && typeof view.addEventListener === "function") {
    view.addEventListener("hashchange", apply);
  }
  return { apply };
}

function badgeElementsFrom(documentRef) {
  if (!documentRef || typeof documentRef.querySelector !== "function") {
    return null;
  }
  const badge = documentRef.querySelector("#review-pending-badge");
  const reviewLink = documentRef.querySelector('[data-route-link="review"]');
  if (!badge) {
    return null;
  }
  return { badge, reviewLink };
}

/**
 * Pending-review badge controller (#571): refreshes the REVIEW count on
 * bootstrap, every route change, and workspace switches. Never blocks routing;
 * failures hide the badge. No transition authority is read or duplicated here.
 */
export function createNavBadgeController(elements, fetchImpl = fetch, documentRef = document) {
  let inFlight = false;
  let active = true;

  async function refresh() {
    if (!active || inFlight || !elements) {
      return;
    }
    inFlight = true;
    try {
      renderReviewBadge(elements, await fetchReviewPendingCount(fetchImpl));
    } catch {
      renderReviewBadge(elements, 0);
    } finally {
      inFlight = false;
    }
  }

  function reset() {
    renderReviewBadge(elements, 0);
  }

  function dispose() {
    active = false;
  }

  return { refresh, reset, dispose };
}

export function bootstrapNavBadge(documentRef = document, fetchImpl = fetch) {
  const elements = badgeElementsFrom(documentRef);
  if (!elements) return null;
  const controller = createNavBadgeController(elements, fetchImpl, documentRef);
  const view = documentRef.defaultView;
  if (view && typeof view.addEventListener === "function") {
    view.addEventListener("hashchange", () => controller.refresh());
  }
  if (documentRef && typeof documentRef.addEventListener === "function") {
    documentRef.addEventListener("workspace-changed", () => controller.refresh());
  }
  controller.refresh();
  return controller;
}

if (typeof document !== "undefined" && typeof window !== "undefined") {
  bootstrapNavigation();
  bootstrapNavBadge();
}
