export const PLACES_SOURCE = "places";
export const AREA_SOURCE = "search-area";
export const LAYERS = Object.freeze({
  areaFill: "search-area-fill",
  areaLine: "search-area-line",
  clusters: "places-clusters",
  clusterCounts: "places-cluster-counts",
  points: "places-points",
  selected: "places-selected",
  pointCounts: "places-counts",
  names: "places-names",
});
/** Zoom at which clusters have all split into single places (clusterMaxZoom + 1). */
export const PLACE_ZOOM = 10;
export const NAMES_MIN_ZOOM = 7;

const INDIGO_600 = "#4f46e5";
const INDIGO_900 = "#312e81";
const SLATE_700 = "#334155";
const WHITE = "#ffffff";
// Fontstacks the base style already ships (frontend/scripts/build-tiles.sh downloads exactly these).
const FONT_MEDIUM = ["Noto Sans Medium"];
const FONT_REGULAR = ["Noto Sans Regular"];
const NONE_SELECTED = "";

const JOBS = ["get", "jobs"];
/** Same shape as formatCount: 999, 1.2k, 12k. */
export const COUNT_LABEL = ["case",
  [">=", JOBS, 10000], ["concat", ["to-string", ["round", ["/", JOBS, 1000]]], "k"],
  [">=", JOBS, 1000], ["concat", ["number-format", ["/", JOBS, 1000], { "max-fraction-digits": 1 }], "k"],
  ["to-string", JOBS]];
const RADIUS_BY_JOBS = (small, large) => ["interpolate", ["linear"], ["sqrt", JOBS], 1, small, 100, large];

export const placesSource = (data) => ({
  type: "geojson",
  data,
  cluster: true,
  clusterRadius: 50,
  clusterMaxZoom: PLACE_ZOOM - 1,
  clusterProperties: { jobs: ["+", JOBS] },
});

export const EMPTY_COLLECTION = Object.freeze({ type: "FeatureCollection", features: [] });

/** Opacities for markers and their labels; the Remote and India tabs dim the map. */
export function markerOpacity(dimmed) {
  return dimmed ? { circle: 0.25, stroke: 0.4, text: 0.4 } : { circle: 0.85, stroke: 1, text: 1 };
}

export const selectedFilter = (placeId) =>
  ["all", ["!", ["has", "point_count"]], ["==", ["get", "id"], placeId || NONE_SELECTED]];

/** Layer specs in drawing order. Text layers are included only when the style has glyphs. */
export function jobLayers({ hasGlyphs }) {
  const opacity = markerOpacity(false);
  const layers = [
    { id: LAYERS.areaFill, type: "fill", source: AREA_SOURCE, paint: { "fill-color": INDIGO_600, "fill-opacity": 0.08 } },
    { id: LAYERS.areaLine, type: "line", source: AREA_SOURCE,
      paint: { "line-color": INDIGO_600, "line-width": 1.5, "line-dasharray": [2, 2], "line-opacity": 0.6 } },
    { id: LAYERS.clusters, type: "circle", source: PLACES_SOURCE, filter: ["has", "point_count"],
      paint: { "circle-color": INDIGO_600, "circle-opacity": opacity.circle, "circle-stroke-color": WHITE,
        "circle-stroke-width": 2, "circle-stroke-opacity": opacity.stroke, "circle-radius": RADIUS_BY_JOBS(14, 38) } },
    { id: LAYERS.points, type: "circle", source: PLACES_SOURCE, filter: ["!", ["has", "point_count"]],
      paint: { "circle-color": INDIGO_600, "circle-opacity": opacity.circle, "circle-stroke-color": WHITE,
        "circle-stroke-width": 1.5, "circle-stroke-opacity": opacity.stroke, "circle-radius": RADIUS_BY_JOBS(9, 30) } },
    { id: LAYERS.selected, type: "circle", source: PLACES_SOURCE, filter: selectedFilter(null),
      paint: { "circle-color": "rgba(0,0,0,0)", "circle-stroke-color": INDIGO_900, "circle-stroke-width": 3,
        "circle-radius": ["+", RADIUS_BY_JOBS(9, 30), 4] } },
  ];
  if (!hasGlyphs) return layers;
  return [
    ...layers,
    { id: LAYERS.clusterCounts, type: "symbol", source: PLACES_SOURCE, filter: ["has", "point_count"],
      layout: { "text-field": COUNT_LABEL, "text-font": FONT_MEDIUM, "text-size": 12, "text-allow-overlap": true },
      paint: { "text-color": WHITE, "text-opacity": opacity.text } },
    { id: LAYERS.pointCounts, type: "symbol", source: PLACES_SOURCE, filter: ["!", ["has", "point_count"]],
      layout: { "text-field": COUNT_LABEL, "text-font": FONT_MEDIUM, "text-size": 11, "text-allow-overlap": true },
      paint: { "text-color": WHITE, "text-opacity": opacity.text } },
    { id: LAYERS.names, type: "symbol", source: PLACES_SOURCE, minzoom: NAMES_MIN_ZOOM,
      filter: ["!", ["has", "point_count"]],
      layout: { "text-field": ["concat", ["get", "name"], " · ", COUNT_LABEL, " jobs"], "text-font": FONT_REGULAR,
        "text-size": 12, "text-anchor": "top", "text-offset": [0, 1.6], "text-optional": true },
      paint: { "text-color": SLATE_700, "text-halo-color": WHITE, "text-halo-width": 1.5, "text-opacity": opacity.text } },
  ];
}

/** setPaintProperty calls that dim or restore every marker layer present on the map. */
export function dimmingPaint(dimmed) {
  const opacity = markerOpacity(dimmed);
  return [
    [LAYERS.clusters, "circle-opacity", opacity.circle],
    [LAYERS.clusters, "circle-stroke-opacity", opacity.stroke],
    [LAYERS.points, "circle-opacity", opacity.circle],
    [LAYERS.points, "circle-stroke-opacity", opacity.stroke],
    [LAYERS.selected, "circle-stroke-opacity", opacity.stroke],
    [LAYERS.clusterCounts, "text-opacity", opacity.text],
    [LAYERS.pointCounts, "text-opacity", opacity.text],
    [LAYERS.names, "text-opacity", opacity.text],
  ];
}
