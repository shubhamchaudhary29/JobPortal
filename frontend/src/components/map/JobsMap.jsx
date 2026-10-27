import { useCallback, useEffect, useRef, useState } from "react";
import BaseMap from "../../map/BaseMap";
import { circleBounds, circleRing, radiusForView, roundToGrid } from "../../map/geo";
import {
  AREA_SOURCE, CENTRE_RING_SOURCE, centreRingLayers, dimmingPaint, EMPTY_COLLECTION, jobLayers, LAYERS, PLACE_ZOOM,
  PLACES_SOURCE, placesSource, RING_LAYERS, selectedFilter,
} from "../../map/jobLayers";
import { snapshotToGeoJson } from "../../map/placesGeoJson";
import { useCentreCompanies } from "../../map/useCentreCompanies";
import { formatViewHash, parseViewHash, replaceHash } from "../../map/viewHash";

const AREA_BUTTON_DELAY_MS = 500;
const OWN_MOVE = Object.freeze({ ownMove: true });
const CLICKABLE = [LAYERS.clusters, LAYERS.points, LAYERS.offices, LAYERS.localities, RING_LAYERS.companies];
const RING_CAPTION = "Grey circles: companies whose listings only name the city. Shown around the centre, not at their offices.";
const MARKERS_MARK = "jobs-map:markers-visible";

const prefersReducedMotion = () => Boolean(globalThis.matchMedia?.("(prefers-reduced-motion: reduce)").matches);

/** Camera moves we start ourselves carry OWN_MOVE, so they never count as the user exploring the map. */
function moveCamera(map, options) {
  if (prefersReducedMotion()) map.jumpTo(options, OWN_MOVE);
  else map.easeTo({ ...options, duration: 700 }, OWN_MOVE);
}

function fitArea(map, area) {
  map.fitBounds(circleBounds(area, area.radiusKm), { padding: 48, animate: !prefersReducedMotion(), maxDuration: 1500 },
    OWN_MOVE);
}

const areaGeoJson = (area) => !area ? EMPTY_COLLECTION : {
  type: "FeatureCollection",
  features: [{ type: "Feature", properties: {}, geometry: { type: "Polygon", coordinates: [circleRing(area, area.radiusKm)] } }],
};

function removeOverlay(map) {
  // The map may already be gone (BaseMap removes it on unmount); there is nothing left to clean up then.
  try {
    [...Object.values(LAYERS), ...Object.values(RING_LAYERS)].forEach((id) => { if (map.getLayer(id)) map.removeLayer(id); });
    [PLACES_SOURCE, AREA_SOURCE, CENTRE_RING_SOURCE].forEach((id) => { if (map.getSource(id)) map.removeSource(id); });
  } catch { /* map already removed */ }
}

/**
 * Clustered city, locality and office markers over the base map. Supplementary to the panel, which offers everything
 * keyboard users need. {@code selected}: the city, locality or office shown in the panel ({@code {id, lat, lng}}),
 * ringed and framed.
 * {@code area}: {@code {lat, lng, radiusKm, fit, approximate}} draws the search circle (and frames it when fit).
 * At street zoom the focus city's centre bubble is ringed by the companies hiring there (GEO-9E); clicking one calls
 * {@code onSelectCentreCompany(placeId, company)}. The ring has its own source, so cluster sums never include it.
 */
export default function JobsMap({ snapshot, selected, dimmed, area, allowAreaSearch, onSelectPlace, onSelectOffice,
  onSelectLocality, onSelectCentreCompany, onSearchArea, className = "" }) {
  const [ready, setReady] = useState(null);
  const [showAreaButton, setShowAreaButton] = useState(false);
  const callbacks = useRef({ onSelectPlace, onSelectOffice, onSelectLocality, onSelectCentreCompany, onSearchArea });
  // A view restored from the URL hash (reload, or Back from a job) wins over framing the place it was showing.
  const [restoredPlaceId] = useState(() => {
    const { hash = "", search = "" } = globalThis.location ?? {};
    const params = new URLSearchParams(search);
    return /^#\d/.test(hash) ? params.get("office") || params.get("locality") || params.get("place") : null;
  });
  const framedPlaceId = useRef(restoredPlaceId);
  // While a near-me area is shown the view centre is the user's (rounded) position, so it stays out of the URL.
  const privateView = useRef(false);

  useEffect(() => {
    callbacks.current = { onSelectPlace, onSelectOffice, onSelectLocality, onSelectCentreCompany, onSearchArea };
  });

  const handleReady = useCallback((map, { hasGlyphs }) => {
    const view = parseViewHash(window.location.hash);
    if (view) map.jumpTo(view, OWN_MOVE);
    map.addSource(PLACES_SOURCE, placesSource(EMPTY_COLLECTION));
    map.addSource(AREA_SOURCE, { type: "geojson", data: EMPTY_COLLECTION });
    map.addSource(CENTRE_RING_SOURCE, { type: "geojson", data: EMPTY_COLLECTION });
    jobLayers({ hasGlyphs }).forEach((layer) => map.addLayer(layer));
    // Ring circles under office pins (the precise points stay on top); their labels above everything.
    const ring = centreRingLayers({ hasGlyphs });
    ring.circles.forEach((layer) => map.addLayer(layer, LAYERS.offices));
    ring.labels.forEach((layer) => map.addLayer(layer));
    setReady({ map });
  }, []);

  const map = ready?.map;

  useEffect(() => {
    if (!map) return undefined;
    const pointer = () => { map.getCanvas().style.cursor = "pointer"; };
    const plain = () => { map.getCanvas().style.cursor = ""; };
    const clickCluster = async (event) => {
      const feature = event.features?.[0];
      if (!feature) return;
      try {
        const zoom = await map.getSource(PLACES_SOURCE).getClusterExpansionZoom(feature.properties.cluster_id);
        moveCamera(map, { center: feature.geometry.coordinates, zoom });
      } catch { /* the cluster vanished, e.g. the snapshot changed mid-click */ }
    };
    const clickPlace = (event) => {
      const id = event.features?.[0]?.properties?.id;
      if (id != null) callbacks.current.onSelectPlace?.(String(id));
    };
    const clickOffice = (event) => {
      const id = event.features?.[0]?.properties?.id;
      if (id != null) callbacks.current.onSelectOffice?.(String(id));
    };
    const clickLocality = (event) => {
      const id = event.features?.[0]?.properties?.id;
      if (id != null) callbacks.current.onSelectLocality?.(String(id));
    };
    const clickCentreCompany = (event) => {
      const { placeId, company } = event.features?.[0]?.properties ?? {};
      if (placeId != null && company) callbacks.current.onSelectCentreCompany?.(String(placeId), String(company));
    };
    let userMoving = false;
    let timer = null;
    const moveStart = (event) => {
      if (event.ownMove || !event.originalEvent) return;
      userMoving = true;
      clearTimeout(timer);
      setShowAreaButton(false);
    };
    const moveEnd = (event) => {
      if (privateView.current) replaceHash("");
      else {
        const center = map.getCenter();
        replaceHash(formatViewHash({ zoom: map.getZoom(), lat: center.lat, lng: center.lng }));
      }
      if (event.ownMove || !userMoving) return;
      userMoving = false;
      clearTimeout(timer);
      timer = setTimeout(() => setShowAreaButton(true), AREA_BUTTON_DELAY_MS);
    };
    CLICKABLE.forEach((layer) => { map.on("mouseenter", layer, pointer); map.on("mouseleave", layer, plain); });
    map.on("click", LAYERS.clusters, clickCluster);
    map.on("click", LAYERS.points, clickPlace);
    map.on("click", LAYERS.offices, clickOffice);
    map.on("click", LAYERS.localities, clickLocality);
    map.on("click", RING_LAYERS.companies, clickCentreCompany);
    map.on("movestart", moveStart);
    map.on("moveend", moveEnd);
    // A pasted or edited view hash on an open page moves the map, as MapLibre's own hash option did.
    const hashChange = () => {
      const view = parseViewHash(window.location.hash);
      if (view) map.jumpTo(view, OWN_MOVE);
    };
    window.addEventListener("hashchange", hashChange);
    return () => {
      window.removeEventListener("hashchange", hashChange);
      clearTimeout(timer);
      CLICKABLE.forEach((layer) => { map.off("mouseenter", layer, pointer); map.off("mouseleave", layer, plain); });
      map.off("click", LAYERS.clusters, clickCluster);
      map.off("click", LAYERS.points, clickPlace);
      map.off("click", LAYERS.offices, clickOffice);
      map.off("click", LAYERS.localities, clickLocality);
      map.off("click", RING_LAYERS.companies, clickCentreCompany);
      map.off("movestart", moveStart);
      map.off("moveend", moveEnd);
      removeOverlay(map);
    };
  }, [map]);

  useEffect(() => {
    if (!map) return;
    map.getSource(PLACES_SOURCE)?.setData(snapshot ? snapshotToGeoJson(snapshot) : EMPTY_COLLECTION);
    // Navigation → markers on screen, for performance checks (performance.getEntriesByName(MARKERS_MARK)).
    if (snapshot && !performance.getEntriesByName(MARKERS_MARK).length) {
      map.once?.("idle", () => performance.mark(MARKERS_MARK));
    }
  }, [map, snapshot]);

  // Dimmed tabs (Remote, India) are not about a city, so they get no ring.
  const ring = useCentreCompanies({ map, snapshot: dimmed ? null : snapshot, selected });
  useEffect(() => {
    if (!map) return;
    map.getSource(CENTRE_RING_SOURCE)?.setData(ring.geojson);
  }, [map, ring.geojson]);

  useEffect(() => {
    if (!map) return;
    dimmingPaint(dimmed).forEach(([layer, property, value]) => {
      if (map.getLayer(layer)) map.setPaintProperty(layer, property, value);
    });
  }, [map, dimmed]);

  const placeId = selected?.id ?? null;
  const placeLat = selected?.lat;
  const placeLng = selected?.lng;
  useEffect(() => {
    if (!map) return;
    map.setFilter(LAYERS.selected, selectedFilter(placeId));
    if (placeId == null) {
      framedPlaceId.current = null;
      return;
    }
    if (placeId === framedPlaceId.current || !Number.isFinite(placeLat) || !Number.isFinite(placeLng)) return;
    framedPlaceId.current = placeId;
    moveCamera(map, { center: [placeLng, placeLat], zoom: Math.max(map.getZoom(), PLACE_ZOOM) });
  }, [map, placeId, placeLat, placeLng]);

  useEffect(() => {
    if (!map) return;
    privateView.current = Boolean(area?.approximate);
    if (privateView.current) replaceHash("");
    map.getSource(AREA_SOURCE)?.setData(areaGeoJson(area));
    if (area?.fit) fitArea(map, area);
  }, [map, area]);

  const searchThisArea = () => {
    const center = map.getCenter();
    setShowAreaButton(false);
    callbacks.current.onSearchArea?.({
      lat: roundToGrid(center.lat), lng: roundToGrid(center.lng), radiusKm: radiusForView(map.getBounds()),
    });
  };

  return (
    <section aria-label="Map of job openings by city, locality and office. The list panel offers the same places for keyboard users."
      className={`relative overflow-hidden ${className}`}>
      {/* View sync is done here rather than with BaseMap's hash option, so near-me views can stay out of the URL. */}
      <BaseMap onReady={handleReady} className="h-full w-full" />
      {map && allowAreaSearch && showAreaButton && (
        <button type="button" onClick={searchThisArea}
          className="absolute left-1/2 top-4 z-10 -translate-x-1/2 rounded-full bg-white px-4 py-2 text-sm font-semibold text-indigo-600 shadow-lg ring-1 ring-slate-200 hover:bg-indigo-50">
          Jobs in this area
        </button>
      )}
      {(area?.approximate || ring.visible) && (
        <div className="pointer-events-none absolute bottom-3 left-3 right-3 z-10 flex flex-col items-start gap-2 sm:right-auto">
          {ring.visible && (
            <p role="note" className="max-w-xs rounded-2xl bg-white/90 px-3 py-1.5 text-xs font-medium text-slate-600 shadow-sm ring-1 ring-slate-200">
              <span aria-hidden="true" className="mr-1.5 inline-block h-2.5 w-2.5 rounded-full border border-slate-400 bg-slate-100 align-middle" />
              {RING_CAPTION}
            </p>
          )}
          {area?.approximate && (
            <p className="rounded-full bg-white/90 px-3 py-1 text-xs font-medium text-slate-600 shadow-sm ring-1 ring-slate-200">
              Approximate area · {area.radiusKm} km
            </p>
          )}
        </div>
      )}
    </section>
  );
}
