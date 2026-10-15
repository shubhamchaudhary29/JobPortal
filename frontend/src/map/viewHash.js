// Same "#zoom/lat/lng" format as MapLibre's built-in hash option, so existing links keep working.
const VIEW_HASH = /^#(\d+(?:\.\d+)?)\/(-?\d+(?:\.\d+)?)\/(-?\d+(?:\.\d+)?)(?:\/|$)/;

/** {@code {zoom, center: [lng, lat]}} from a URL hash, or null. */
export function parseViewHash(hash) {
  const match = VIEW_HASH.exec(hash ?? "");
  if (!match) return null;
  const [zoom, lat, lng] = match.slice(1).map(Number);
  if (Math.abs(lat) > 90 || Math.abs(lng) > 180) return null;
  return { zoom, center: [lng, lat] };
}

/** "#zoom/lat/lng" with MapLibre's precision. */
export function formatViewHash({ zoom, lat, lng }) {
  return `#${Math.round(zoom * 100) / 100}/${lat.toFixed(4)}/${lng.toFixed(4)}`;
}

/** Replaces the URL fragment in place (no history entry); an empty hash removes it. */
export function replaceHash(hash) {
  const { pathname, search } = window.location;
  window.history.replaceState(window.history.state, "", `${pathname}${search}${hash}`);
}
