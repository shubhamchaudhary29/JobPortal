import { useEffect, useMemo, useState } from "react";
import { getCentreCompanies, isAbortError } from "../services/map-service";
import { ringLayout, ringOutline } from "./centreRing";
import { EMPTY_COLLECTION, RING_ZOOM } from "./jobLayers";

export const RING_FETCH_DEBOUNCE_MS = 300;
export const RING_COMPANY_LIMIT = 40;

const finite = (place) => Number.isFinite(place?.lat) && Number.isFinite(place?.lng);
/** A snapshot place's "office not listed" jobs, as on its centre bubble (placesGeoJson). */
export const centreJobsOf = (place) => (place?.jobs ?? 0) - (place?.officeJobs || 0) - (place?.localityJobs || 0);

/** Zoom, centre and bounds ({@code [west, south, east, north]}) of the map right now. */
function readView(map) {
  const center = map.getCenter();
  const bounds = map.getBounds();
  return { zoom: map.getZoom(), center: [center.lng, center.lat],
    bounds: [bounds.getWest(), bounds.getSouth(), bounds.getEast(), bounds.getNorth()] };
}

/**
 * The city whose centre gets the ring: none below {@link RING_ZOOM}; else the selected city when it has city-centre
 * jobs; else the city with centre jobs inside the view that is nearest the view centre.
 */
export function focusCity(snapshot, selected, view) {
  if (!view || !(view.zoom >= RING_ZOOM)) return null;
  const places = (Array.isArray(snapshot?.places) ? snapshot.places : []).filter((place) => finite(place) && centreJobsOf(place) > 0);
  const chosen = selected?.id == null ? null : places.find((place) => place.id === selected.id);
  if (chosen) return chosen;
  const [west, south, east, north] = view.bounds;
  const [lng, lat] = view.center;
  const cos = Math.cos((lat * Math.PI) / 180);
  let best = null;
  let bestDistance = Infinity;
  for (const place of places) {
    if (place.lng < west || place.lng > east || place.lat < south || place.lat > north) continue;
    const distance = ((place.lng - lng) * cos) ** 2 + (place.lat - lat) ** 2;
    if (distance < bestDistance || (distance === bestDistance && String(place.id) < String(best.id))) {
      best = place;
      bestDistance = distance;
    }
  }
  return best;
}

/** Ring markers (most jobs first) and the dashed outline around {@code place}'s centre, laid out for {@code zoom}. */
export function ringGeoJson(place, companies, zoom) {
  if (!place || !companies?.length) return EMPTY_COLLECTION;
  const center = [place.lng, place.lat];
  const positions = ringLayout(center, companies.length, zoom);
  const outline = ringOutline(center, positions.length, zoom);
  const markers = positions.map((coordinates, index) => {
    const { company, jobs } = companies[index];
    return {
      type: "Feature",
      geometry: { type: "Point", coordinates },
      properties: { id: `${place.id}|${company}`, placeId: place.id, company, jobs,
        initial: (Array.from(company.trim())[0] ?? "?").toUpperCase() },
    };
  });
  return { type: "FeatureCollection",
    features: [...(outline ? [{ type: "Feature", geometry: outline, properties: {} }] : []), ...markers] };
}

/**
 * The centre-company ring for {@code map} (GEO-9E): picks the focus city, fetches its companies (debounced
 * {@value RING_FETCH_DEBOUNCE_MS} ms, aborted when the focus changes, kept per {@code placeId|snapshot.version}) and
 * lays them out. The view is read on {@code zoomend}/{@code moveend} only, never per frame. Returns the GeoJSON for the
 * {@code centre-ring} source, the focus city and whether any marker is shown.
 */
export function useCentreCompanies({ map, snapshot, selected }) {
  const [moved, setMoved] = useState({ map: null, view: null });
  const [byKey, setByKey] = useState({});

  useEffect(() => {
    if (!map) return undefined;
    const update = () => setMoved({ map, view: readView(map) });
    map.on("zoomend", update);
    map.on("moveend", update);
    return () => {
      map.off("zoomend", update);
      map.off("moveend", update);
    };
  }, [map]);

  // Until the first move ends, the map's current view.
  const view = !map ? null : moved.map === map ? moved.view : readView(map);
  const focus = useMemo(() => focusCity(snapshot, selected, view), [snapshot, selected, view]);
  const focusId = focus?.id ?? null;
  const key = focusId != null && snapshot ? `${focusId}|${snapshot.version}` : null;
  const known = key != null && key in byKey;

  useEffect(() => {
    if (key == null || known) return undefined;
    const controller = new AbortController();
    const timer = setTimeout(() => {
      getCentreCompanies(focusId, { limit: RING_COMPANY_LIMIT, signal: controller.signal })
        .then((centre) => setByKey((current) => ({ ...current, [key]: centre?.companies ?? [] })))
        .catch((error) => {
          // The ring is supplementary: a failed fetch just draws no ring for this city and snapshot.
          if (!isAbortError(error)) setByKey((current) => ({ ...current, [key]: [] }));
        });
    }, RING_FETCH_DEBOUNCE_MS);
    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, [key, known, focusId]);

  const companies = key == null ? null : byKey[key] ?? null;
  const zoom = view?.zoom;
  const geojson = useMemo(() => ringGeoJson(focus, companies, zoom), [focus, companies, zoom]);
  return { geojson, focus, visible: geojson.features.length > 0 };
}
