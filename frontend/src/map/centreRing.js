/**
 * Layout of the centre-company ring (GEO-9E): companies whose listings only name the city, placed on concentric rings
 * around its centre bubble at street zoom. The positions are a drawing aid around the centre, never office locations.
 *
 * Ring r (0-based) holds up to 10 + 6·r markers at 44 + 30·r px from the centre, so later rings have room for more;
 * each ring is turned by r·π/capacity so markers on neighbouring rings do not line up. Input order (most jobs first)
 * fills the inner rings first. Pure and deterministic: the same input always gives the same positions.
 */
export const MAX_RING_COMPANIES = 60;
export const RING_BASE_CAPACITY = 10;
export const RING_CAPACITY_STEP = 6;
export const RING_BASE_RADIUS_PX = 44;
export const RING_RADIUS_STEP_PX = 30;
/** Gap between the outermost markers and the dashed outline. */
export const OUTLINE_GAP_PX = 18;
/**
 * Metres per pixel at zoom 0 on the equator for MapLibre's 512-px tiles (40 075 016.686 m / 512). The often-quoted
 * 156 543.03392 is for 256-px tiles; with MapLibre zoom levels it would put every marker twice as far out.
 */
export const METRES_PER_PX_Z0 = 78271.51696;
const METRES_PER_DEGREE = 40075016.686 / 360;
const OUTLINE_SEGMENTS = 64;

export const ringCapacity = (ring) => RING_BASE_CAPACITY + RING_CAPACITY_STEP * ring;
export const ringRadiusPx = (ring) => RING_BASE_RADIUS_PX + RING_RADIUS_STEP_PX * ring;

/** Metres covered by one screen pixel at this latitude and zoom. */
export const metresPerPixel = (lat, zoom) => (METRES_PER_PX_Z0 * Math.cos((lat * Math.PI) / 180)) / 2 ** zoom;

const clampCount = (count) => Math.max(0, Math.min(MAX_RING_COMPANIES, Math.floor(Number(count) || 0)));

/** [ring, slots] per ring for {@code count} markers; the last ring may be partly filled. */
export function ringSlots(count) {
  const rings = [];
  let left = clampCount(count);
  for (let ring = 0; left > 0; ring += 1) {
    const slots = Math.min(ringCapacity(ring), left);
    rings.push([ring, slots]);
    left -= slots;
  }
  return rings;
}

/** {@code [lng, lat]} that is {@code dx} px east and {@code dy} px north of {@code center} at this zoom. */
function offset([lng, lat], dx, dy, zoom) {
  const metres = metresPerPixel(lat, zoom);
  const cos = Math.cos((lat * Math.PI) / 180);
  return [lng + (dx * metres) / (METRES_PER_DEGREE * cos), lat + (dy * metres) / METRES_PER_DEGREE];
}

/**
 * Marker positions {@code [[lng, lat], …]} for {@code count} companies (at most {@value MAX_RING_COMPANIES}) around
 * {@code center} ({@code [lng, lat]}) at {@code zoom}. Index i is the i-th company of the input. A partly filled outer
 * ring spreads its markers evenly so it does not bunch up on one side.
 */
export function ringLayout(center, count, zoom) {
  if (!Array.isArray(center) || !center.every(Number.isFinite) || !Number.isFinite(zoom)) return [];
  const positions = [];
  for (const [ring, slots] of ringSlots(count)) {
    const radius = ringRadiusPx(ring);
    const turn = (ring * Math.PI) / ringCapacity(ring);
    for (let slot = 0; slot < slots; slot += 1) {
      // Clockwise from north.
      const angle = turn + (2 * Math.PI * slot) / slots;
      positions.push(offset(center, radius * Math.sin(angle), radius * Math.cos(angle), zoom));
    }
  }
  return positions;
}

/**
 * A GeoJSON LineString circle just outside the outermost ring, marking the ring as one group around the centre; null
 * when there is nothing to ring.
 */
export function ringOutline(center, count, zoom) {
  const rings = ringSlots(count);
  if (!rings.length || !Array.isArray(center) || !center.every(Number.isFinite) || !Number.isFinite(zoom)) return null;
  const radius = ringRadiusPx(rings[rings.length - 1][0]) + OUTLINE_GAP_PX;
  const coordinates = [];
  for (let step = 0; step <= OUTLINE_SEGMENTS; step += 1) {
    const angle = (2 * Math.PI * (step % OUTLINE_SEGMENTS)) / OUTLINE_SEGMENTS;
    coordinates.push(offset(center, radius * Math.sin(angle), radius * Math.cos(angle), zoom));
  }
  return { type: "LineString", coordinates };
}
