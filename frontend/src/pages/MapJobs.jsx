import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import Header from "../components/Header";
import MapJobList, { PanelError, SkeletonCards } from "../components/map/MapJobList";
import MapTabs from "../components/map/MapTabs";
import TopPlaces from "../components/map/TopPlaces";
import { formatCount, formatDistance } from "../map/format";
import { roundToGrid } from "../map/geo";
import { MAP_TABS, tabCounts, topPlaces } from "../map/panelData";
import { useJobList } from "../map/useJobList";
import { useMapSnapshot } from "../map/useMapSnapshot";
import { getLocalityJobs, getNearbyJobs, getOfficeJobs, getPlaceJobs, getUnplacedJobs } from "../services/map-service";

// MapLibre is the bulk of this page; the panel renders while it downloads.
const JobsMap = lazy(() => import("../components/map/JobsMap"));

const TAB_IDS = MAP_TABS.map((tab) => tab.id);
const RADII_KM = [10, 25, 50, 100];
const DEFAULT_RADIUS_KM = 25;
const SEARCH_DEBOUNCE_MS = 300;
const MAX_QUERY_LENGTH = 100;
const MAX_COMPANY_LENGTH = 200;
const TOP_PLACE_COUNT = 12;
const PICKER_PLACE_COUNT = 50;
// Coarse, cached positions are plenty: the result is rounded to a ≈5 km grid anyway.
const GEOLOCATION_OPTIONS = { enableHighAccuracy: false, timeout: 10000, maximumAge: 600000 };
const GEOLOCATION_MESSAGES = {
  denied: "Location access is turned off.",
  timeout: "Finding your location took too long.",
  unavailable: "Your location isn't available right now.",
};
const PANEL_ID = "map-panel-list";
const INDIA_CAPTION = "Jobs open across a state or anywhere in India.";
const REMOTE_CAPTION = "Remote roles — check location eligibility on each posting.";
const UNLISTED_CAPTION = "These listings only name the city.";

const backButton = "text-sm font-semibold text-indigo-600 hover:text-indigo-700";
const distanceOf = (job) => job.places?.[0]?.distanceKm;

/**
 * /jobs/map: clustered city markers, localities and pinned offices plus a list panel. URL holds only place (with
 * scope=unlisted for a city bubble's "office not listed" jobs, and company for one company of its street-zoom ring),
 * locality or office, tab and q (the map view lives in the hash); near-me and this-area searches stay in memory, with
 * coordinates rounded before any request.
 */
export default function MapJobs() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const tab = TAB_IDS.includes(searchParams.get("tab")) ? searchParams.get("tab") : "map";
  // Only one of office, locality and city is selected; a hand-edited URL with several keeps the most precise.
  const officeId = searchParams.get("office") || null;
  const localityId = officeId ? null : searchParams.get("locality") || null;
  const placeId = officeId || localityId ? null : searchParams.get("place") || null;
  const unlisted = Boolean(placeId) && searchParams.get("scope") === "unlisted";
  // A centre-ring company (GEO-9E) narrows only the city-centre list.
  const company = unlisted ? (searchParams.get("company") || "").slice(0, MAX_COMPANY_LENGTH) || null : null;
  const q = (searchParams.get("q") || "").trim().slice(0, MAX_QUERY_LENGTH);

  const [queryInput, setQueryInput] = useState(q);
  const [area, setArea] = useState(null);
  const [geoStatus, setGeoStatus] = useState("idle");
  const [mobileView, setMobileView] = useState("map");
  const [notice, setNotice] = useState("");
  const headingRef = useRef(null);
  const focusHeading = useRef(false);
  const { snapshot, status: snapshotStatus, retry: retrySnapshot } = useMapSnapshot();

  /** Changes query params, keeping MapLibre's #zoom/lat/lng hash (it updates the URL behind the router's back). */
  const updateParams = useCallback((changes, { replace = false } = {}) => {
    const params = new URLSearchParams(window.location.search);
    Object.entries(changes).forEach(([key, value]) => (value ? params.set(key, value) : params.delete(key)));
    const search = params.toString();
    navigate({ search: search ? `?${search}` : "", hash: window.location.hash }, { replace });
  }, [navigate]);

  useEffect(() => {
    const next = queryInput.trim();
    if (next === q) return undefined;
    const timer = setTimeout(() => updateParams({ q: next }, { replace: true }), SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [queryInput, q, updateParams]);

  const placesById = useMemo(() => new Map((snapshot?.places ?? []).map((place) => [place.id, place])), [snapshot]);
  const selectedPlace = placeId ? placesById.get(placeId) ?? null : null;
  const officesById = useMemo(() => new Map((snapshot?.offices ?? []).map((office) => [office.id, office])), [snapshot]);
  const selectedOffice = officeId ? officesById.get(officeId) ?? null : null;
  const localitiesById = useMemo(() => new Map((snapshot?.localities ?? []).map((locality) => [locality.id, locality])),
    [snapshot]);
  const selectedLocality = localityId ? localitiesById.get(localityId) ?? null : null;

  // A place, locality or office that is not in the snapshot (stale link, or its jobs closed) falls back to Top places.
  useEffect(() => {
    if (!snapshot) return;
    if (placeId && !placesById.has(placeId)) updateParams({ place: null, scope: null, company: null }, { replace: true });
    if (officeId && !officesById.has(officeId)) updateParams({ office: null }, { replace: true });
    if (localityId && !localitiesById.has(localityId)) updateParams({ locality: null }, { replace: true });
  }, [snapshot, placeId, placesById, officeId, officesById, localityId, localitiesById, updateParams]);

  const onPlaceMissing = useCallback(() => {
    setNotice("That place is no longer on the map, so we're showing top places instead.");
    updateParams({ place: null, office: null, locality: null, scope: null, company: null }, { replace: true });
  }, [updateParams]);

  const mode = tab !== "map" ? "unplaced" : area ? "nearby" : officeId ? "office" : localityId ? "locality"
    : placeId ? "place" : null;
  const fetcher = useMemo(() => {
    if (mode === "unplaced") return ({ page, signal }) => getUnplacedJobs({ kind: tab, q, page, signal });
    if (mode === "nearby") {
      return ({ page, signal }) => getNearbyJobs({ lat: area.lat, lng: area.lng, radiusKm: area.radiusKm, q, page, signal })
        .then((result) => ({ ...result.jobs, extra: { places: result.places ?? [], radiusKm: result.radiusKm } }));
    }
    if (mode === "office") {
      return ({ page, signal }) => getOfficeJobs(officeId, { q, page, signal }).catch((error) => {
        if (error?.status === 404) onPlaceMissing();
        throw error;
      });
    }
    if (mode === "locality") {
      return ({ page, signal }) => getLocalityJobs(localityId, { q, page, signal }).catch((error) => {
        if (error?.status === 404) onPlaceMissing();
        throw error;
      });
    }
    if (mode === "place") {
      return ({ page, signal }) => getPlaceJobs(placeId, { scope: unlisted ? "unlisted" : undefined, company, q, page, signal })
        .catch((error) => {
          if (error?.status === 404) onPlaceMissing();
          throw error;
        });
    }
    return null;
  }, [mode, tab, q, area, placeId, unlisted, company, officeId, localityId, onPlaceMissing]);
  const list = useJobList(fetcher, [mode, tab, placeId, unlisted, company, officeId, localityId, area?.lat ?? null,
    area?.lng ?? null, area?.radiusKm ?? null, q]);

  useEffect(() => {
    if (!focusHeading.current || !headingRef.current) return;
    focusHeading.current = false;
    headingRef.current.focus();
  });

  const select = useCallback((changes) => {
    setArea(null);
    setNotice("");
    setMobileView("list");
    focusHeading.current = true;
    updateParams({ place: null, office: null, locality: null, scope: null, company: null, tab: null, ...changes });
  }, [updateParams]);

  /** All jobs of a city (Top places, near-me chips, "Show all"). */
  const selectPlace = useCallback((id) => select({ place: id }), [select]);
  /** A city bubble on the map holds only the jobs whose listings name no office or locality. */
  const selectCityMarker = useCallback((id) => select({ place: id, scope: "unlisted" }), [select]);
  /** A company in the centre ring: that city's "office not listed" jobs of this company only. */
  const selectCentreCompany = useCallback((id, name) => select({ place: id, scope: "unlisted", company: name }), [select]);
  const selectOffice = useCallback((id) => select({ office: id }), [select]);
  const selectLocality = useCallback((id) => select({ locality: id }), [select]);

  const clearPlace = () => updateParams({ place: null, office: null, locality: null, scope: null, company: null });

  const selectTab = (id) => {
    setNotice("");
    updateParams({ tab: id === "map" ? null : id }, { replace: true });
  };

  const startArea = useCallback((next) => {
    setArea(next);
    setNotice("");
    setMobileView("list");
    focusHeading.current = true;
    const params = new URLSearchParams(window.location.search);
    if (params.has("place") || params.has("office") || params.has("locality")) {
      updateParams({ place: null, office: null, locality: null, scope: null, company: null }, { replace: true });
    }
  }, [updateParams]);

  const locate = () => {
    if (!navigator.geolocation) {
      setGeoStatus("unavailable");
      return;
    }
    setGeoStatus("locating");
    navigator.geolocation.getCurrentPosition(
      ({ coords }) => {
        setGeoStatus("idle");
        // Rounded immediately: the precise position is never stored, sent, put in the URL or logged.
        startArea({ lat: roundToGrid(coords.latitude), lng: roundToGrid(coords.longitude), radiusKm: DEFAULT_RADIUS_KM,
          origin: "nearMe", approximate: true, fit: true });
      },
      (error) => setGeoStatus(error?.code === 1 ? "denied" : error?.code === 3 ? "timeout" : "unavailable"),
      GEOLOCATION_OPTIONS,
    );
  };

  const pickCity = (event) => {
    const place = placesById.get(event.target.value);
    if (!place) return;
    startArea({ lat: place.lat, lng: place.lng, radiusKm: area?.radiusKm ?? DEFAULT_RADIUS_KM, origin: "city",
      label: place.name, fit: true });
  };

  const searchArea = useCallback((next) => {
    startArea({ ...next, origin: "area", fit: false });
  }, [startArea]);

  const changeRadius = (radiusKm) => setArea((current) => ({ ...current, radiusKm, fit: true }));
  const largerRadius = area ? RADII_KM.find((radius) => radius > area.radiusKm) : undefined;

  const counts = tabCounts(snapshot);
  const pickerPlaces = useMemo(() => topPlaces(snapshot, PICKER_PLACE_COUNT), [snapshot]);
  const geoFailed = geoStatus in GEOLOCATION_MESSAGES;

  const renderMapTab = () => {
    if (area) {
      const places = list.extra?.places ?? [];
      const radii = RADII_KM.includes(area.radiusKm) ? RADII_KM : [...RADII_KM, area.radiusKm].sort((a, b) => a - b);
      return (
        <div>
          <button type="button" onClick={() => setArea(null)} className={backButton}>← All places</button>
          <div className="mt-2 flex items-end justify-between gap-3">
            <div>
              <h2 ref={headingRef} tabIndex={-1} className="text-xl font-extrabold text-slate-900 focus:outline-none">
                Within {area.radiusKm} km
              </h2>
              <p className="text-sm text-slate-500">
                {area.origin === "nearMe" ? "Approximate area around you" : area.origin === "city" ? `Around ${area.label}` : "Around the map centre"}
              </p>
            </div>
            <label className="text-sm font-medium text-slate-600">
              <span className="sr-only">Search radius</span>
              <select value={area.radiusKm} onChange={(event) => changeRadius(Number(event.target.value))}
                className="rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm font-semibold text-slate-700 focus:outline-none focus:ring-2 focus:ring-indigo-500">
                {radii.map((radius) => <option key={radius} value={radius}>{radius} km</option>)}
              </select>
            </label>
          </div>
          {places.length > 0 && (
            <ul className="-mx-1 mt-4 flex gap-2 overflow-x-auto px-1 pb-2" aria-label="Places in this area">
              {places.map((place) => (place.kind === "office" ? (
                <li key={`office-${place.officeId}`} className="shrink-0">
                  <button type="button" onClick={() => selectOffice(place.officeId)}
                    className="rounded-full border border-indigo-200 bg-indigo-50 px-3 py-1.5 text-xs font-semibold text-indigo-700 hover:border-indigo-300 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500">
                    <span aria-hidden="true">◉ </span>{place.company} · {place.label} · {formatDistance(place.distanceKm)}
                  </button>
                </li>
              ) : place.kind === "locality" ? (
                <li key={`locality-${place.localityId}`} className="shrink-0">
                  <button type="button" onClick={() => selectLocality(place.localityId)}
                    className="rounded-full border border-indigo-100 bg-white px-3 py-1.5 text-xs font-semibold text-indigo-700 hover:border-indigo-300 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500">
                    {place.label} · {place.name} · {formatDistance(place.distanceKm)}
                  </button>
                </li>
              ) : (
                <li key={place.id} className="shrink-0">
                  <button type="button" onClick={() => selectPlace(place.id)}
                    className="rounded-full border border-slate-200 bg-white px-3 py-1.5 text-xs font-semibold text-slate-700 hover:border-indigo-200 hover:text-indigo-600 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500">
                    {place.name} · {formatDistance(place.distanceKm)}
                  </button>
                </li>
              )))}
            </ul>
          )}
          <div className="mt-4">
            <MapJobList list={list} distanceOf={distanceOf} label={`Jobs within ${area.radiusKm} km`} empty={(
              <div className="rounded-3xl border border-dashed border-slate-300 bg-white px-5 py-8 text-center">
                <p className="font-semibold text-slate-700">No openings within {area.radiusKm} km{q ? ` matching “${q}”` : ""}.</p>
                <div className="mt-4 flex flex-wrap justify-center gap-2">
                  {largerRadius && (
                    <button type="button" onClick={() => changeRadius(largerRadius)}
                      className="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white hover:bg-indigo-700">
                      Increase radius
                    </button>
                  )}
                  <button type="button" onClick={() => selectTab("remote")}
                    className="rounded-lg bg-indigo-50 px-4 py-2 text-sm font-semibold text-indigo-600 hover:bg-indigo-100">
                    See remote roles
                  </button>
                </div>
              </div>
            )} />
          </div>
        </div>
      );
    }

    if (officeId) {
      const company = selectedOffice?.company ?? list.items[0]?.company ?? "Pinned office";
      const label = selectedOffice?.label ?? list.items[0]?.places?.[0]?.officeLabel;
      const city = placesById.get(selectedOffice?.placeId)?.name ?? list.items[0]?.places?.[0]?.name;
      return (
        <div>
          <button type="button" onClick={clearPlace} className={backButton}>← All places</button>
          <div className="mt-2 flex items-end justify-between gap-3">
            <div className="min-w-0">
              <h2 ref={headingRef} tabIndex={-1} className="truncate text-xl font-extrabold text-slate-900 focus:outline-none">{company}</h2>
              <p className="text-sm text-slate-500">
                <span aria-hidden="true">◉ </span>{[label, city].filter(Boolean).join(" · ")}
              </p>
            </div>
            <p className="shrink-0 text-sm font-semibold text-slate-500">
              {q ? "Results" : list.totalHint != null ? `${formatCount(list.totalHint)} jobs` : ""}
            </p>
          </div>
          <div className="mt-4">
            <MapJobList list={list} label={`Jobs at ${company}${label ? `, ${label}` : ""}`} empty={(
              <p className="rounded-3xl border border-dashed border-slate-300 bg-white px-5 py-8 text-center text-sm text-slate-500">
                {q ? `No jobs at this office match “${q}”.` : "No openings at this office right now."}
              </p>
            )} />
          </div>
        </div>
      );
    }

    if (localityId) {
      const name = selectedLocality?.name ?? list.items[0]?.places?.[0]?.localityName ?? "Selected locality";
      const city = placesById.get(selectedLocality?.placeId)?.name ?? list.items[0]?.places?.[0]?.name;
      return (
        <div>
          <button type="button" onClick={clearPlace} className={backButton}>← All places</button>
          <div className="mt-2 flex items-end justify-between gap-3">
            <div className="min-w-0">
              <h2 ref={headingRef} tabIndex={-1} className="truncate text-xl font-extrabold text-slate-900 focus:outline-none">{name}</h2>
              {city && <p className="text-sm text-slate-500">{city}</p>}
            </div>
            <p className="shrink-0 text-sm font-semibold text-slate-500">
              {q ? "Results" : list.totalHint != null ? `${formatCount(list.totalHint)} jobs` : ""}
            </p>
          </div>
          <div className="mt-4">
            <MapJobList list={list} label={`Jobs in ${name}${city ? `, ${city}` : ""}`} empty={(
              <p className="rounded-3xl border border-dashed border-slate-300 bg-white px-5 py-8 text-center text-sm text-slate-500">
                {q ? `No jobs here match “${q}”.` : "No openings here right now."}
              </p>
            )} />
          </div>
        </div>
      );
    }

    if (placeId && unlisted) {
      const name = selectedPlace?.name ?? list.items[0]?.places?.[0]?.name ?? "Selected place";
      return (
        <div>
          <button type="button" onClick={clearPlace} className={backButton}>← All places</button>
          <div className="mt-2 flex items-end justify-between gap-3">
            <div className="min-w-0">
              <h2 ref={headingRef} tabIndex={-1} className="truncate text-xl font-extrabold text-slate-900 focus:outline-none">{name}</h2>
              {selectedPlace?.state && <p className="text-sm text-slate-500">{selectedPlace.state}</p>}
            </div>
            <p className="shrink-0 text-sm font-semibold text-slate-500">
              {q ? "Results" : list.totalHint != null ? `${formatCount(list.totalHint)} jobs · office not listed` : "Office not listed"}
            </p>
          </div>
          <p className="mt-2 text-sm text-slate-500">{UNLISTED_CAPTION}</p>
          {company && (
            <div className="mt-2 flex flex-wrap gap-2">
              <span className="inline-flex items-center gap-1 rounded-full bg-slate-100 py-1 pl-3 pr-1 text-xs font-semibold text-slate-700">
                Company: {company}
                <button type="button" onClick={() => updateParams({ company: null })} aria-label={`Remove company filter ${company}`}
                  className="flex h-5 w-5 items-center justify-center rounded-full text-slate-500 hover:bg-slate-200 hover:text-slate-700 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500">
                  ×
                </button>
              </span>
            </div>
          )}
          <button type="button" onClick={() => updateParams({ scope: null, company: null })} className={`${backButton} mt-1`}>
            Show all {name} jobs
          </button>
          <div className="mt-4">
            <MapJobList list={list} label={`Jobs in ${name} without a listed office${company ? ` at ${company}` : ""}`} empty={(
              <p className="rounded-3xl border border-dashed border-slate-300 bg-white px-5 py-8 text-center text-sm text-slate-500">
                {q ? `No jobs here match “${q}”.` : company ? `No openings from ${company} here right now.`
                  : "Every opening here names its office or area."}
              </p>
            )} />
          </div>
        </div>
      );
    }

    if (placeId) {
      const name = selectedPlace?.name ?? list.items[0]?.places?.[0]?.name ?? "Selected place";
      return (
        <div>
          <button type="button" onClick={clearPlace} className={backButton}>← All places</button>
          <div className="mt-2 flex items-end justify-between gap-3">
            <div className="min-w-0">
              <h2 ref={headingRef} tabIndex={-1} className="truncate text-xl font-extrabold text-slate-900 focus:outline-none">{name}</h2>
              {selectedPlace?.state && <p className="text-sm text-slate-500">{selectedPlace.state}</p>}
            </div>
            <p className="shrink-0 text-sm font-semibold text-slate-500">
              {q ? "Results" : list.totalHint != null ? `${formatCount(list.totalHint)} jobs` : ""}
              {!q && list.totalHint != null && selectedPlace?.officeJobs > 0
                && ` · ${formatCount(selectedPlace.officeJobs)} at pinned offices`}
              {!q && list.totalHint != null && selectedPlace?.localityJobs > 0
                && ` · ${formatCount(selectedPlace.localityJobs)} in localities`}
            </p>
          </div>
          <div className="mt-4">
            <MapJobList list={list} label={`Jobs in ${name}`} empty={(
              <p className="rounded-3xl border border-dashed border-slate-300 bg-white px-5 py-8 text-center text-sm text-slate-500">
                {q ? `No jobs here match “${q}”.` : "No openings here right now."}
              </p>
            )} />
          </div>
        </div>
      );
    }

    if (snapshotStatus === "error") {
      return <PanelError message="We couldn't load the map's places right now." onRetry={retrySnapshot} />;
    }
    if (!snapshot) {
      return (
        <div>
          <p role="status" className="mb-4 text-sm font-medium text-slate-500">
            {snapshotStatus === "retrying" ? "Map data is being prepared — retrying shortly…" : "Loading places…"}
          </p>
          <SkeletonCards count={6} />
        </div>
      );
    }
    return <TopPlaces snapshot={snapshot} places={topPlaces(snapshot, TOP_PLACE_COUNT)} onSelect={selectPlace} />;
  };

  const renderUnplacedTab = () => (
    <div>
      <p className="text-sm text-slate-500">{tab === "remote" ? REMOTE_CAPTION : INDIA_CAPTION}</p>
      <div className="mt-4">
        <MapJobList list={list} label={tab === "remote" ? "Remote jobs" : "Jobs anywhere in India"} empty={(
          <p className="rounded-3xl border border-dashed border-slate-300 bg-white px-5 py-8 text-center text-sm text-slate-500">
            {q ? `No jobs match “${q}”.` : "No openings here right now."}
          </p>
        )} />
      </div>
    </div>
  );

  return (
    <div className="flex h-[100dvh] flex-col bg-slate-50">
      <Header />
      <h1 className="sr-only">Jobs on the map</h1>
      <main className="relative min-h-0 flex-1 lg:grid lg:grid-cols-[420px_minmax(0,1fr)] lg:gap-4 lg:p-4">
        <aside aria-label="Job list"
          className={`${mobileView === "list" ? "flex" : "hidden"} absolute inset-0 z-20 flex-col overflow-y-auto bg-slate-50 lg:static lg:z-auto lg:flex lg:rounded-3xl lg:bg-white lg:shadow-sm lg:ring-1 lg:ring-slate-200`}>
          <div className="sticky top-0 z-10 space-y-3 border-b border-slate-100 bg-white/95 p-4 backdrop-blur">
            <div>
              <div className="relative">
                <label htmlFor="map-search" className="sr-only">Search jobs on the map</label>
                <input id="map-search" type="search" value={queryInput} maxLength={MAX_QUERY_LENGTH}
                  onChange={(event) => setQueryInput(event.target.value.slice(0, MAX_QUERY_LENGTH))}
                  placeholder="Search title, company or skill"
                  className="block w-full rounded-full border border-slate-200 bg-slate-50 py-2.5 pl-4 pr-10 text-sm text-slate-900 placeholder-slate-400 transition-all focus:bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500" />
                {queryInput && (
                  <button type="button" onClick={() => setQueryInput("")} aria-label="Clear search"
                    className="absolute inset-y-0 right-2 my-auto flex h-7 w-7 items-center justify-center rounded-full text-slate-400 hover:bg-slate-100 hover:text-slate-600">
                    ×
                  </button>
                )}
              </div>
              {q && <p className="mt-1.5 px-1 text-xs text-slate-500">Markers show all openings; the list is filtered.</p>}
            </div>
            <MapTabs active={tab} counts={counts} onChange={selectTab} panelId={PANEL_ID} />
            {tab === "map" && (
              <div>
                <button type="button" onClick={locate} disabled={geoStatus === "locating"}
                  className="inline-flex items-center gap-1.5 rounded-full bg-indigo-50 px-4 py-2 text-sm font-semibold text-indigo-600 hover:bg-indigo-100 disabled:opacity-60">
                  <span aria-hidden="true">◎</span> {geoStatus === "locating" ? "Finding you…" : "Near me"}
                </button>
                {geoFailed && (
                  <div role="status" className="mt-2 rounded-2xl bg-amber-50 px-3 py-2 text-sm text-amber-800">
                    <p>{GEOLOCATION_MESSAGES[geoStatus]} Pick a city instead:</p>
                    <label htmlFor="map-city" className="sr-only">City</label>
                    <select id="map-city" defaultValue="" onChange={pickCity} disabled={pickerPlaces.length === 0}
                      className="mt-2 w-full rounded-xl border border-amber-200 bg-white px-3 py-2 text-sm text-slate-700 focus:outline-none focus:ring-2 focus:ring-indigo-500">
                      <option value="" disabled>Choose a city</option>
                      {pickerPlaces.map((place) => <option key={place.id} value={place.id}>{place.name}{place.state ? `, ${place.state}` : ""}</option>)}
                    </select>
                  </div>
                )}
              </div>
            )}
          </div>
          <div id={PANEL_ID} role="tabpanel" aria-labelledby={`map-tab-${tab}`} className="flex-1 p-4 pb-24 lg:pb-4">
            {notice && (
              <p role="status" className="mb-4 flex items-start justify-between gap-3 rounded-2xl bg-amber-50 px-3 py-2 text-sm text-amber-800">
                {notice}
                <button type="button" onClick={() => setNotice("")} aria-label="Dismiss" className="font-bold">×</button>
              </p>
            )}
            {tab === "map" ? renderMapTab() : renderUnplacedTab()}
          </div>
        </aside>

        <div className="absolute inset-0 lg:static">
          <Suspense fallback={<div className="h-full w-full animate-pulse bg-slate-100 lg:rounded-3xl" />}>
            <JobsMap snapshot={snapshot} selected={tab === "map" ? selectedOffice ?? selectedLocality ?? selectedPlace : null}
              dimmed={tab !== "map"} area={tab === "map" ? area : null} allowAreaSearch={tab === "map"}
              onSelectPlace={selectCityMarker} onSelectOffice={selectOffice} onSelectLocality={selectLocality}
              onSelectCentreCompany={selectCentreCompany}
              onSearchArea={searchArea} className="h-full w-full bg-slate-100 lg:rounded-3xl lg:ring-1 lg:ring-slate-200" />
          </Suspense>
        </div>

        <div role="group" aria-label="Show" className="absolute bottom-5 left-1/2 z-30 flex -translate-x-1/2 rounded-full bg-white p-1 shadow-lg ring-1 ring-slate-200 lg:hidden">
          {[["map", "Map"], ["list", "List"]].map(([id, label]) => (
            <button key={id} type="button" aria-pressed={mobileView === id} onClick={() => setMobileView(id)}
              className={`min-w-[88px] rounded-full px-5 py-2.5 text-sm font-semibold ${mobileView === id ? "bg-indigo-600 text-white" : "text-slate-600"}`}>
              {label}
            </button>
          ))}
        </div>
      </main>
    </div>
  );
}
