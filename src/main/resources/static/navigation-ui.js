/**
 * Vanilla-JS hash navigation for the multi-view shell (#352). Views live in the DOM as
 * sibling sections tagged with data-route; routing only toggles visibility and nav
 * state, so every existing panel keeps its element ids, module state, and deep links.
 * No framework, no inline script/style (strict CSP), no route-scoped re-rendering.
 */

const ROUTES = Object.freeze(["home", "inbox", "ask", "inspect", "review"]);

export const VIEW_TITLES = Object.freeze({
  home: "工作區",
  inbox: "收件匣",
  ask: "提問",
  inspect: "檢視器",
  review: "審核"
});

export function parseRoute(hash) {
  const raw = String(hash ?? "").replace(/^#\/?/, "").trim().toLowerCase();
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

if (typeof document !== "undefined" && typeof window !== "undefined") {
  bootstrapNavigation();
}
