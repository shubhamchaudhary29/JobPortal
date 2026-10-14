import { layers, namedFlavor } from "@protomaps/basemaps";
import { TILES_BASE } from "./tilesManifest";

export const SOURCE_ID = "protomaps";
export const FALLBACK_ATTRIBUTION = "© OpenStreetMap contributors · GeoNames";
const FALLBACK_BACKGROUND = "#f1f5f9"; // slate-100
const HIDDEN_PLACE_KINDS = new Set(["country", "region"]);

/**
 * Disputed-boundary policy: India's official map differs from OpenStreetMap's de facto boundaries, so the base map
 * draws no boundary lines and no country or state/region labels (city labels, water, land use and roads stay).
 * With @protomaps/basemaps 5.7.2 (light, en) this removes exactly:
 *   boundaries_country, boundaries   (source-layer "boundaries")
 *   places_region, places_country    (source-layer "places", kind region / country)
 */
export function isHiddenLayer(layer) {
  if (layer["source-layer"] === "boundaries") return true;
  if (layer["source-layer"] !== "places" || !Array.isArray(layer.filter)) return false;
  const [operator, property, ...values] = layer.filter;
  return (operator === "==" || operator === "in") && property === "kind"
    && values.length > 0 && values.every((value) => HIDDEN_PLACE_KINDS.has(value));
}

/**
 * Base map style for a tiles manifest: tiles, glyphs and sprites all come from this site's own /tiles/ (no
 * external URLs). MapLibre 6 requires absolute sprite and glyph URLs, so paths are prefixed with the page origin.
 */
export function buildBaseStyle(manifest, origin = globalThis.location?.origin ?? "") {
  const tiles = `${origin}${TILES_BASE}`;
  const assets = `${tiles}/${manifest.assets}`;
  return {
    version: 8,
    glyphs: `${assets}/fonts/{fontstack}/{range}.pbf`,
    sprite: `${assets}/sprites/light`,
    sources: {
      [SOURCE_ID]: {
        type: "vector",
        url: `pmtiles://${tiles}/${manifest.pmtiles}`,
        attribution: manifest.attribution,
      },
    },
    layers: layers(SOURCE_ID, namedFlavor("light"), { lang: "en" }).filter((layer) => !isHiddenLayer(layer)),
  };
}

/** Plain background used when the tiles manifest is unavailable; markers still render on top. */
export function buildFallbackStyle() {
  return {
    version: 8,
    sources: {},
    layers: [{ id: "background", type: "background", paint: { "background-color": FALLBACK_BACKGROUND } }],
  };
}
