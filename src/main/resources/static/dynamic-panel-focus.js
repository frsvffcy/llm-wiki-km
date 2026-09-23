import { parseRoute } from "./navigation-ui.js";

/**
 * 共用 inline 詳情面板焦點生命週期：成功顯示後聚焦面板標題，關閉時回到
 * 仍有效的觸發控制項；工作區或路由切換會使先前的觸發控制項失效。
 */
export function createDynamicPanelFocus({ panel, heading, documentRef = document }) {
  const windowRef = documentRef && documentRef.defaultView;
  let generation = 0;
  let activeContext = null;
  let opener = null;

  function currentRoute() {
    const hash = windowRef && windowRef.location ? windowRef.location.hash : "";
    return parseRoute(hash);
  }

  function routeFor(element) {
    let current = element;
    while (current) {
      const route = current.dataset && current.dataset.route;
      if (typeof route === "string" && route !== "") return route;
      current = current.parentElement;
    }
    return null;
  }

  function isHidden(element) {
    let current = element;
    while (current) {
      if (current.hidden === true
          || current.inert === true
          || (typeof current.getAttribute === "function"
            && String(current.getAttribute("aria-hidden")).toLowerCase() === "true")
          || (typeof current.hasAttribute === "function" && current.hasAttribute("hidden"))) {
        return true;
      }
      current = current.parentElement;
    }
    return false;
  }

  function isUsable(element) {
    if (!element || typeof element.focus !== "function" || element.isConnected === false
        || element.disabled === true || isHidden(element)) {
      return false;
    }
    const elementRoute = routeFor(element);
    const route = currentRoute();
    return !elementRoute || !route || elementRoute === route;
  }

  function focusIsInsidePanel() {
    let current = documentRef && documentRef.activeElement;
    while (current) {
      if (current === panel) return true;
      current = current.parentElement;
    }
    return false;
  }

  function begin(trigger = null) {
    generation += 1;
    opener = trigger || null;
    activeContext = Object.freeze({
      generation,
      route: routeFor(panel) || currentRoute()
    });
    return activeContext;
  }

  function isCurrent(context) {
    if (!context || !activeContext || context.generation !== generation
        || activeContext !== context) {
      return false;
    }
    const route = currentRoute();
    return !context.route || !route || context.route === route;
  }

  function focusPanel(context) {
    if (!isCurrent(context) || !panel || panel.hidden === true || !isUsable(heading)) {
      return false;
    }
    heading.focus();
    return true;
  }

  function currentViewHeading() {
    const route = currentRoute();
    if (documentRef && typeof documentRef.querySelectorAll === "function") {
      const sections = documentRef.querySelectorAll("[data-route]");
      for (const section of sections) {
        if (route && section.dataset && section.dataset.route !== route) continue;
        if (isHidden(section)) continue;
        const candidate = typeof section.querySelector === "function"
          ? section.querySelector("[data-view-heading]") : null;
        if (isUsable(candidate)) return candidate;
      }
    }
    if (documentRef && typeof documentRef.querySelector === "function") {
      const candidate = documentRef.querySelector("[data-view-heading]");
      if (isUsable(candidate)) return candidate;
    }
    return null;
  }

  function invalidate() {
    generation += 1;
    activeContext = null;
    opener = null;
  }

  function focusViewHeading() {
    const fallback = currentViewHeading();
    if (isUsable(fallback)) {
      fallback.focus();
      return true;
    }
    return false;
  }

  function dismiss() {
    const focusedInPanel = focusIsInsidePanel();
    invalidate();
    if (focusedInPanel) focusViewHeading();
  }

  function close() {
    const trigger = opener;
    const context = activeContext;
    const panelRoute = routeFor(panel);
    const triggerRoute = routeFor(trigger);
    invalidate();
    if (isUsable(trigger)
        && (!context || !context.route || !panelRoute || context.route === panelRoute)
        && (!panelRoute || !triggerRoute || panelRoute === triggerRoute)) {
      trigger.focus();
      return "opener";
    }
    return focusViewHeading() ? "view-heading" : "none";
  }

  if (windowRef && typeof windowRef.addEventListener === "function") {
    windowRef.addEventListener("hashchange", invalidate);
  }
  if (documentRef && typeof documentRef.addEventListener === "function") {
    documentRef.addEventListener("workspace-changed", () => {
      const focusedInPanel = focusIsInsidePanel();
      invalidate();
      if (focusedInPanel) focusViewHeading();
    });
  }

  return { begin, isCurrent, focusPanel, invalidate, dismiss, close };
}
