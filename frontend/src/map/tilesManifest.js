export const TILES_BASE = "/tiles";

const PMTILES_NAME = /^[A-Za-z0-9._-]+\.pmtiles$/;
const ASSETS_PATH = /^assets\/[A-Za-z0-9._-]+$/;

/**
 * Validated tiles manifest, or null when it is missing or malformed. Names are restricted to one path segment
 * so a tampered manifest cannot point the map outside /tiles/.
 */
export function parseTilesManifest(value) {
  if (!value || typeof value !== "object") return null;
  const { pmtiles, maxzoom, bbox, assets, attribution } = value;
  if (typeof pmtiles !== "string" || !PMTILES_NAME.test(pmtiles) || pmtiles.includes("..")) return null;
  if (!Number.isFinite(maxzoom) || maxzoom < 0 || maxzoom > 15) return null;
  if (!Array.isArray(bbox) || bbox.length !== 4 || !bbox.every(Number.isFinite)) return null;
  if (typeof assets !== "string" || !ASSETS_PATH.test(assets) || assets.includes("..")) return null;
  if (typeof attribution !== "string") return null;
  return { pmtiles, maxzoom, bbox: [...bbox], assets, attribution };
}

/** Loads /tiles/manifest.json; resolves to null on 404, network errors, bad JSON or a bad shape. Never throws. */
export async function loadTilesManifest(fetchImpl = fetch) {
  try {
    const response = await fetchImpl(`${TILES_BASE}/manifest.json`, { cache: "no-cache" });
    if (!response.ok) return null;
    return parseTilesManifest(await response.json());
  } catch {
    return null;
  }
}
