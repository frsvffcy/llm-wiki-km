/**
 * Browser hash-route context helpers (#645).
 *
 * Route query values are navigation hints only. Backend endpoints remain the
 * authority and must revalidate workspace/currentness before rendering data.
 */

function routeParams(hash, expectedRoute) {
  const raw = String(hash ?? "");
  const match = raw.match(/^#\/([a-z]+)(?:\?(.*))?$/u);
  if (!match || match[1] !== expectedRoute) return null;
  return new URLSearchParams(match[2] || "");
}

export function positiveIntegerRouteParam(hash, route, name) {
  const params = routeParams(hash, route);
  if (!params) return null;
  const raw = params.get(name);
  if (!raw || !/^[1-9][0-9]*$/u.test(raw)) return null;
  const value = Number(raw);
  return Number.isSafeInteger(value) ? value : null;
}

export function boundedRouteParam(hash, route, name, maxLength = 128) {
  const params = routeParams(hash, route);
  if (!params) return null;
  const raw = params.get(name);
  if (!raw || raw.length > maxLength) return null;
  if (/[\u0000-\u001f\u007f\s/#?]/u.test(raw)) return null;
  return raw;
}

export function routeMatches(hash, route) {
  return routeParams(hash, route) !== null;
}

export function clearRouteQuery(view, route) {
  if (!view || !view.location) return false;
  const hash = String(view.location.hash || "");
  if (!routeMatches(hash, route) || !hash.includes("?")) return false;
  const next = `#/${route}`;
  if (view.history && typeof view.history.replaceState === "function") {
    view.history.replaceState(null, "", next);
  } else {
    view.location.hash = next;
  }
  return true;
}
