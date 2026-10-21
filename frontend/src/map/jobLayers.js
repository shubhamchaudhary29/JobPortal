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
  offices: "offices-points",
  officeNames: "offices-names",
  localities: "localities-points",
  localityNames: "localities-names",
});
/** Zoom at which clusters have all split into single places (clusterMaxZoom + 1). */
export const PLACE_ZOOM = 10;
export const NAMES_MIN_ZOOM = 7;

const INDIGO_400 = "#818cf8";
const INDIGO_600 = "#4f46e5";
const INDIGO_900 = "#312e81";
const SLATE_700 = "#334155";
const WHITE = "#ffffff";
// Fontstacks the base style already ships (frontend/scripts/build-tiles.sh downloads exactly these).
const FONT_MEDIUM = ["Noto Sans Medium"];
const FONT_REGULAR = ["Noto Sans Regular"];
const NONE_SELECTED = "";

const JOBS = ["get", "jobs"];
const UNCLUSTERED = ["!", ["has", "point_count"]];
const IS_OFFICE = ["==", ["get", "kind"], "office"];
const IS_LOCALITY = ["==", ["get", "kind"], "locality"];
const CITY_POINTS = ["all", UNCLUSTERED, ["!", IS_OFFICE], ["!", IS_LOCALITY]];
const OFFICE_POINTS = ["all", UNCLUSTERED, IS_OFFICE];
const LOCALITY_POINTS = ["all", UNCLUSTERED, IS_LOCALITY];
/** Same shape as formatCount: 999, 1.2k, 12k. */
export const COUNT_LABEL = ["case",
  [">=", JOBS, 10000], ["concat", ["to-string", ["round", ["/", JOBS, 1000]]], "k"],
  [">=", JOBS, 1000], ["concat", ["number-format", ["/", JOBS, 1000], { "max-fraction-digits": 1 }], "k"],
  ["to-string", JOBS]];
const RADIUS_BY_JOBS = (small, large) => ["interpolate", ["linear"], ["sqrt", JOBS], 1, small, 100, large];
const CITY_RADIUS = RADIUS_BY_JOBS(9, 30);
const OFFICE_RADIUS = RADIUS_BY_JOBS(6, 14);
// Between a city (an area) and an office (a point): a neighbourhood or tech park.
const LOCALITY_RADIUS = RADIUS_BY_JOBS(7, 20);

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

/** Ring around the selected city, office or locality (ids never collide: gazetteer numbers, Mongo ids, "osm-…"). */
export const selectedFilter = (id) => ["all", UNCLUSTERED, ["==", ["get", "id"], id || NONE_SELECTED]];

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
    { id: LAYERS.points, type: "circle", source: PLACES_SOURCE, filter: CITY_POINTS,
      paint: { "circle-color": INDIGO_600, "circle-opacity": opacity.circle, "circle-stroke-color": WHITE,
        "circle-stroke-width": 1.5, "circle-stroke-opacity": opacity.stroke, "circle-radius": CITY_RADIUS } },
    // Localities: a lighter, smaller bubble than a city (an area, not a precise point like an office's ring).
    { id: LAYERS.localities, type: "circle", source: PLACES_SOURCE, filter: LOCALITY_POINTS,
      paint: { "circle-color": INDIGO_400, "circle-opacity": opacity.circle, "circle-stroke-color": WHITE,
        "circle-stroke-width": 1.5, "circle-stroke-opacity": opacity.stroke, "circle-radius": LOCALITY_RADIUS } },
    // Offices: white with an indigo ring and smaller, so they read as a precise point rather than an area.
    { id: LAYERS.offices, type: "circle", source: PLACES_SOURCE, filter: OFFICE_POINTS,
      paint: { "circle-color": WHITE, "circle-opacity": 1, "circle-stroke-color": INDIGO_600, "circle-stroke-width": 3,
        "circle-stroke-opacity": opacity.stroke, "circle-radius": OFFICE_RADIUS } },
    { id: LAYERS.selected, type: "circle", source: PLACES_SOURCE, filter: selectedFilter(null),
      paint: { "circle-color": "rgba(0,0,0,0)", "circle-stroke-color": INDIGO_900, "circle-stroke-width": 3,
        "circle-radius": ["+", ["case", IS_OFFICE, OFFICE_RADIUS, IS_LOCALITY, LOCALITY_RADIUS, CITY_RADIUS], 4] } },
  ];
  if (!hasGlyphs) return layers;
  return [
    ...layers,
    { id: LAYERS.clusterCounts, type: "symbol", source: PLACES_SOURCE, filter: ["has", "point_count"],
      layout: { "text-field": COUNT_LABEL, "text-font": FONT_MEDIUM, "text-size": 12, "text-allow-overlap": true },
      paint: { "text-color": WHITE, "text-opacity": opacity.text } },
    { id: LAYERS.pointCounts, type: "symbol", source: PLACES_SOURCE, filter: CITY_POINTS,
      layout: { "text-field": COUNT_LABEL, "text-font": FONT_MEDIUM, "text-size": 11, "text-allow-overlap": true },
      paint: { "text-color": WHITE, "text-opacity": opacity.text } },
    { id: LAYERS.names, type: "symbol", source: PLACES_SOURCE, minzoom: NAMES_MIN_ZOOM,
      filter: CITY_POINTS,
      layout: { "text-field": ["concat", ["get", "name"], " · ", COUNT_LABEL, " jobs"], "text-font": FONT_REGULAR,
        "text-size": 12, "text-anchor": "top", "text-offset": [0, 1.6], "text-optional": true },
      paint: { "text-color": SLATE_700, "text-halo-color": WHITE, "text-halo-width": 1.5, "text-opacity": opacity.text } },
    // "Whitefield · 42" once clusters have split.
    { id: LAYERS.localityNames, type: "symbol", source: PLACES_SOURCE, minzoom: PLACE_ZOOM, filter: LOCALITY_POINTS,
      layout: { "text-field": ["concat", ["get", "name"], " · ", COUNT_LABEL], "text-font": FONT_MEDIUM, "text-size": 11,
        "text-anchor": "top", "text-offset": [0, 1.2], "text-optional": true },
      paint: { "text-color": INDIGO_900, "text-halo-color": WHITE, "text-halo-width": 1.5, "text-opacity": opacity.text } },
    { id: LAYERS.officeNames, type: "symbol", source: PLACES_SOURCE, filter: OFFICE_POINTS,
      layout: { "text-field": ["get", "name"], "text-font": FONT_MEDIUM, "text-size": 11, "text-anchor": "left",
        "text-offset": [1.2, 0], "text-optional": true },
      paint: { "text-color": INDIGO_900, "text-halo-color": WHITE, "text-halo-width": 1.5, "text-opacity": opacity.text } },
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
    [LAYERS.localities, "circle-opacity", opacity.circle],
    [LAYERS.localities, "circle-stroke-opacity", opacity.stroke],
    [LAYERS.localityNames, "text-opacity", opacity.text],
    [LAYERS.offices, "circle-opacity", dimmed ? 0.4 : 1],
    [LAYERS.offices, "circle-stroke-opacity", opacity.stroke],
    [LAYERS.officeNames, "text-opacity", opacity.text],
    [LAYERS.clusterCounts, "text-opacity", opacity.text],
    [LAYERS.pointCounts, "text-opacity", opacity.text],
    [LAYERS.names, "text-opacity", opacity.text],
  ];
}
