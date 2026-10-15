// Mean Earth radius, the same constant the backend uses (MapJobService.haversineKm).
const EARTH_RADIUS_KM = 6371.0088;
const MIN_RADIUS_KM = 1;
const MAX_RADIUS_KM = 100;

const toRadians = (degrees) => (degrees * Math.PI) / 180;

/**
 * Rounds a coordinate to the backend's 0.05° grid (≈5 km), half up, e.g. 12.9716 → 12.95 and 77.5946 → 77.6.
 * Same rule as MapJobService.snap (round(v × 20) / 20); user coordinates go through this before any request.
 */
export function roundToGrid(value, step = 0.05) {
  const stepsPerDegree = Math.round(1 / step);
  return Number((Math.round(value * stepsPerDegree) / stepsPerDegree).toFixed(2));
}

/** Great-circle distance in km between two {@code {lat, lng}} points. */
export function haversineKm(a, b) {
  const dLat = toRadians(b.lat - a.lat);
  const dLng = toRadians(b.lng - a.lng);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(toRadians(a.lat)) * Math.cos(toRadians(b.lat)) * Math.sin(dLng / 2) ** 2;
  return EARTH_RADIUS_KM * 2 * Math.asin(Math.min(1, Math.sqrt(h)));
}

/** Accepts a MapLibre LngLatBounds or {@code [[west, south], [east, north]]}. */
function edges(bounds) {
  if (Array.isArray(bounds)) {
    const [[west, south], [east, north]] = bounds;
    return { west, south, east, north };
  }
  return { west: bounds.getWest(), south: bounds.getSouth(), east: bounds.getEast(), north: bounds.getNorth() };
}

/** Search radius for the visible map: half the shorter side of the bounds in km, an integer clamped to 1–100. */
export function radiusForView(bounds) {
  const { west, south, east, north } = edges(bounds);
  const midLat = (south + north) / 2;
  const midLng = (west + east) / 2;
  const width = haversineKm({ lat: midLat, lng: west }, { lat: midLat, lng: east });
  const height = haversineKm({ lat: south, lng: midLng }, { lat: north, lng: midLng });
  const radius = Math.round(Math.min(width, height) / 2);
  return Math.min(MAX_RADIUS_KM, Math.max(MIN_RADIUS_KM, radius));
}

/** Polygon ring approximating a circle on the map, as [lng, lat] pairs (closed). */
export function circleRing({ lat, lng }, radiusKm, steps = 64) {
  const angular = radiusKm / EARTH_RADIUS_KM;
  const lat1 = toRadians(lat);
  const lng1 = toRadians(lng);
  const ring = [];
  for (let index = 0; index <= steps; index += 1) {
    const bearing = (2 * Math.PI * index) / steps;
    const lat2 = Math.asin(Math.sin(lat1) * Math.cos(angular) + Math.cos(lat1) * Math.sin(angular) * Math.cos(bearing));
    const lng2 = lng1 + Math.atan2(Math.sin(bearing) * Math.sin(angular) * Math.cos(lat1),
      Math.cos(angular) - Math.sin(lat1) * Math.sin(lat2));
    ring.push([(lng2 * 180) / Math.PI, (lat2 * 180) / Math.PI]);
  }
  return ring;
}

/** {@code [[west, south], [east, north]]} around a circle, for fitBounds. */
export function circleBounds(center, radiusKm) {
  const ring = circleRing(center, radiusKm, 16);
  const lngs = ring.map(([lng]) => lng);
  const lats = ring.map(([, lat]) => lat);
  return [[Math.min(...lngs), Math.min(...lats)], [Math.max(...lngs), Math.max(...lats)]];
}
