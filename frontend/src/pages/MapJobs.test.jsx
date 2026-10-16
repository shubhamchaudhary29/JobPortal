// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { BrowserRouter, Route, Routes } from "react-router-dom";

// jsdom has no WebGL, so MapLibre itself is mocked (as in BaseMap.test.jsx), with just enough of a Map to record calls.
const maplibre = vi.hoisted(() => {
  const instances = [];
  class Map {
    constructor(options) {
      this.options = options;
      this.handlers = [];
      this.sources = {};
      this.layers = {};
      this.canvas = { style: {} };
      this.remove = vi.fn();
      this.addControl = vi.fn();
      this.touchZoomRotate = { disableRotation: vi.fn() };
      this.keyboard = { disableRotation: vi.fn() };
      this.on = vi.fn((...args) => this.handlers.push(args));
      this.off = vi.fn();
      this.addSource = vi.fn((id, spec) => {
        this.sources[id] = { spec, setData: vi.fn(), getClusterExpansionZoom: vi.fn().mockResolvedValue(8) };
      });
      this.getSource = (id) => this.sources[id];
      this.addLayer = vi.fn((layer) => { this.layers[layer.id] = layer; });
      this.getLayer = (id) => this.layers[id];
      this.removeLayer = vi.fn((id) => { delete this.layers[id]; });
      this.removeSource = vi.fn((id) => { delete this.sources[id]; });
      this.setFilter = vi.fn();
      this.setPaintProperty = vi.fn();
      this.getCanvas = () => this.canvas;
      this.easeTo = vi.fn();
      this.jumpTo = vi.fn();
      this.fitBounds = vi.fn();
      this.getZoom = () => 5;
      this.getCenter = () => ({ lat: 12.9716, lng: 77.5946 });
      this.getBounds = () => ({ getWest: () => 77, getSouth: () => 12.75, getEast: () => 78.5, getNorth: () => 13.25 });
      instances.push(this);
    }

    handler(event, layer) {
      const match = this.handlers.find((args) => args[0] === event && (layer ? args[1] === layer : typeof args[1] === "function"));
      return match[match.length - 1];
    }
  }
  return { instances, Map, addProtocol: vi.fn(), setWorkerUrl: vi.fn(), NavigationControl: vi.fn(), AttributionControl: vi.fn() };
});
vi.mock("maplibre-gl", () => maplibre);
vi.mock("maplibre-gl/dist/maplibre-gl.css", () => ({}));
vi.mock("maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url", () => ({ default: "/assets/maplibre-worker.js" }));
vi.mock("../map/tilesManifest", async (original) => ({
  ...(await original()),
  loadTilesManifest: vi.fn().mockResolvedValue({
    pmtiles: "india-20261014-z11.pmtiles", maxzoom: 11, bbox: [66, 5, 98.5, 37.5], assets: "assets/028c18f713ba",
    attribution: "© OpenStreetMap contributors",
  }),
}));
const service = vi.hoisted(() => ({
  getMapSnapshot: vi.fn(), getPlaceJobs: vi.fn(), getNearbyJobs: vi.fn(), getUnplacedJobs: vi.fn(), getOfficeJobs: vi.fn(),
}));
vi.mock("../services/map-service", async (original) => ({ ...(await original()), ...service }));
vi.mock("../components/Header", () => ({ default: () => <header>Header</header> }));

import MapJobs from "./MapJobs";
import { MAX_SNAPSHOT_RETRIES } from "../map/useMapSnapshot";

const BENGALURU = { id: "1277333", name: "Bengaluru", state: "Karnataka", lat: 12.97194, lng: 77.59369, jobs: 214 };
const DELHI = { id: "1273294", name: "Delhi", state: "Delhi", lat: 28.65195, lng: 77.23149, jobs: 150 };
const MUMBAI = { id: "1275339", name: "Mumbai", state: "Maharashtra", lat: 19.07283, lng: 72.88261, jobs: 1200 };
const snapshot = {
  version: "v1", placedJobs: 1564, places: [BENGALURU, DELHI, MUMBAI], unplaced: { REMOTE: 40, STATE: 5, COUNTRY: 12, UNRESOLVED: 9 },
};
const OFFICE = { id: "665f1c2e9b1e8a0012345678", company: "Acme", label: "Bellandur", placeId: BENGALURU.id,
  lat: 12.926, lng: 77.6762, jobs: 30 };
const withOffice = { ...snapshot, places: [{ ...BENGALURU, officeJobs: 30 }, DELHI, MUMBAI], offices: [OFFICE] };
const job = (id, extra = {}) => ({
  id, title: `Engineer ${id}`, company: "Acme", location: "Bengaluru", employmentType: "FULL_TIME", salaryMin: 800000,
  salaryMax: 1200000, source: "adzuna", createdAt: "2026-10-10T09:00:00", publishedAt: null,
  places: [{ id: BENGALURU.id, name: "Bengaluru", distanceKm: null }], ...extra,
});
const slice = (content, extra = {}) => ({ content, page: 0, size: 20, hasNext: false, totalHint: null, ...extra });
const nearby = (content, places = []) => ({ centerLat: 28.6, centerLng: 77.2, radiusKm: 25, places, jobs: slice(content) });

function renderAt(url) {
  window.history.replaceState(null, "", url);
  return render(
    <BrowserRouter>
      <Routes>
        <Route path="/jobs/map" element={<MapJobs />} />
        <Route path="/jobs/:jobId" element={<p>Job details page</p>} />
      </Routes>
    </BrowserRouter>,
  );
}

async function readyMap() {
  await waitFor(() => expect(maplibre.instances).toHaveLength(1));
  const [map] = maplibre.instances;
  act(() => map.handler("load")());
  await waitFor(() => expect(map.addSource).toHaveBeenCalled());
  return map;
}

const params = () => new URLSearchParams(window.location.search);

function mockGeolocation(getCurrentPosition) {
  Object.defineProperty(navigator, "geolocation", { configurable: true, value: { getCurrentPosition } });
}

describe("MapJobs page", () => {
  beforeEach(() => {
    maplibre.instances.length = 0;
    Object.values(service).forEach((mock) => mock.mockReset());
    service.getMapSnapshot.mockResolvedValue(snapshot);
    service.getPlaceJobs.mockResolvedValue(slice([job("job-1")], { totalHint: 214 }));
    service.getUnplacedJobs.mockResolvedValue(slice([job("remote-1", { location: "Remote", places: [] })]));
    service.getNearbyJobs.mockResolvedValue(nearby([]));
  });
  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    delete navigator.geolocation;
  });

  it("shows tab counts from the snapshot and the top places by jobs", async () => {
    renderAt("/jobs/map");
    expect(await screen.findByText("1,564 jobs across 3 cities")).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: /On map 1\.6k/ })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("tab", { name: "Remote 40" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Anywhere in India 17" })).toBeInTheDocument();
    const places = within(screen.getByRole("list")).getAllByRole("button");
    expect(places.map((button) => button.getAttribute("aria-label"))).toEqual([
      "Mumbai, Maharashtra, 1.2k jobs", "Bengaluru, Karnataka, 214 jobs", "Delhi, Delhi, 150 jobs",
    ]);
  });

  it("captions the Remote tab and loads remote jobs", async () => {
    renderAt("/jobs/map");
    fireEvent.click(await screen.findByRole("tab", { name: /Remote/ }));
    expect(await screen.findByText("Remote roles — check location eligibility on each posting.")).toBeInTheDocument();
    await screen.findByText("Engineer remote-1");
    expect(service.getUnplacedJobs).toHaveBeenCalledWith(expect.objectContaining({ kind: "remote", q: "", page: 0 }));
    expect(params().get("tab")).toBe("remote");
    expect(screen.queryByRole("button", { name: /Near me/ })).not.toBeInTheDocument();
  });

  it("selects a top place, lists its jobs with attribution, updates ?place= and loads more", async () => {
    service.getPlaceJobs
      .mockResolvedValueOnce(slice([job("job-1")], { hasNext: true, totalHint: 214 }))
      .mockResolvedValueOnce(slice([job("job-2")], { page: 1, totalHint: 214 }));
    renderAt("/jobs/map");
    fireEvent.click(await screen.findByRole("button", { name: /Bengaluru/ }));

    expect(await screen.findByText("Engineer job-1")).toBeInTheDocument();
    expect(service.getPlaceJobs).toHaveBeenCalledWith("1277333", expect.objectContaining({ page: 0, q: "" }));
    expect(params().get("place")).toBe("1277333");
    expect(screen.getByRole("heading", { name: "Bengaluru" })).toHaveFocus();
    expect(screen.getByText("214 jobs")).toBeInTheDocument();
    expect(screen.getAllByRole("link", { name: "Jobs by Adzuna" })).toHaveLength(1);
    expect(screen.getByRole("link", { name: /Engineer job-1/ })).toHaveAttribute("href", "/jobs/job-1");

    fireEvent.click(screen.getByRole("button", { name: "Load more" }));
    expect(await screen.findByText("Engineer job-2")).toBeInTheDocument();
    expect(screen.getByText("Engineer job-1")).toBeInTheDocument();
    expect(service.getPlaceJobs).toHaveBeenLastCalledWith("1277333", expect.objectContaining({ page: 1 }));
    expect(screen.queryByRole("button", { name: "Load more" })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "← All places" }));
    expect(await screen.findByText("Top places")).toBeInTheDocument();
    expect(params().has("place")).toBe(false);
  });

  it("debounces the search into one request and aborts the previous one", async () => {
    let firstSignal;
    service.getPlaceJobs.mockImplementationOnce((id, { signal }) => {
      firstSignal = signal;
      return new Promise(() => {});
    });
    renderAt("/jobs/map?place=1277333");
    await waitFor(() => expect(service.getPlaceJobs).toHaveBeenCalledTimes(1));

    const search = screen.getByRole("searchbox", { name: "Search jobs on the map" });
    fireEvent.change(search, { target: { value: "j" } });
    fireEvent.change(search, { target: { value: "ja" } });
    fireEvent.change(search, { target: { value: "java" } });
    await new Promise((resolve) => setTimeout(resolve, 200));
    expect(service.getPlaceJobs).toHaveBeenCalledTimes(1);

    await waitFor(() => expect(service.getPlaceJobs).toHaveBeenCalledTimes(2));
    expect(service.getPlaceJobs).toHaveBeenLastCalledWith("1277333", expect.objectContaining({ q: "java", page: 0 }));
    expect(firstSignal.aborted).toBe(true);
    expect(params().get("q")).toBe("java");
    expect(screen.getByText("Markers show all openings; the list is filtered.")).toBeInTheDocument();
    expect(await screen.findByText("Results")).toBeInTheDocument();
  });

  it("searches near the user with rounded coordinates and never puts them in the URL", async () => {
    const getCurrentPosition = vi.fn((success) => success({ coords: { latitude: 28.6139, longitude: 77.209 } }));
    mockGeolocation(getCurrentPosition);
    service.getNearbyJobs.mockResolvedValue(nearby([job("near-1", { places: [{ id: DELHI.id, name: "Delhi", distanceKm: 5.6 }] })],
      [{ ...DELHI, distanceKm: 5.6 }]));
    renderAt("/jobs/map");
    const map = await readyMap();
    fireEvent.click(await screen.findByRole("button", { name: /Near me/ }));

    expect(await screen.findByText("Engineer near-1")).toBeInTheDocument();
    expect(getCurrentPosition).toHaveBeenCalledWith(expect.any(Function), expect.any(Function),
      { enableHighAccuracy: false, timeout: 10000, maximumAge: 600000 });
    expect(service.getNearbyJobs).toHaveBeenCalledWith(expect.objectContaining({ lat: 28.6, lng: 77.2, radiusKm: 25, page: 0 }));
    expect(screen.getByRole("heading", { name: "Within 25 km" })).toBeInTheDocument();
    expect(screen.getByText("Approximate area around you")).toBeInTheDocument();
    expect(screen.getByText("5.6 km away")).toBeInTheDocument();
    expect(map.fitBounds).toHaveBeenCalled();
    expect(map.sources["search-area"].setData).toHaveBeenLastCalledWith(expect.objectContaining({
      features: [expect.objectContaining({ geometry: expect.objectContaining({ type: "Polygon" }) })],
    }));

    // The framed view is the user's rounded position: moves must not write it to the URL hash.
    act(() => map.handler("moveend")({ ownMove: true }));
    expect(window.location.hash).toBe("");
    expect(window.location.href).not.toMatch(/28\.6|77\.2|12\.97|lat|lng/);

    fireEvent.click(screen.getByRole("button", { name: "Delhi · 5.6 km" }));
    await waitFor(() => expect(params().get("place")).toBe(DELHI.id));
    expect(service.getPlaceJobs).toHaveBeenCalledWith(DELHI.id, expect.anything());
  });

  it("offers a city picker when location is denied", async () => {
    mockGeolocation(vi.fn((success, failure) => failure({ code: 1 })));
    renderAt("/jobs/map");
    fireEvent.click(await screen.findByRole("button", { name: /Near me/ }));

    expect(await screen.findByText(/Location access is turned off/)).toBeInTheDocument();
    const picker = screen.getByRole("combobox", { name: "City" });
    expect(within(picker).getAllByRole("option").map((option) => option.textContent))
      .toEqual(["Choose a city", "Mumbai, Maharashtra", "Bengaluru, Karnataka", "Delhi, Delhi"]);
    fireEvent.change(picker, { target: { value: BENGALURU.id } });
    await waitFor(() => expect(service.getNearbyJobs).toHaveBeenCalledWith(expect.objectContaining({
      lat: BENGALURU.lat, lng: BENGALURU.lng, radiusKm: 25,
    })));
    expect(screen.getByText("Around Bengaluru")).toBeInTheDocument();
  });

  it("offers a larger radius and remote roles when nothing is nearby", async () => {
    mockGeolocation(vi.fn((success) => success({ coords: { latitude: 28.6139, longitude: 77.209 } })));
    renderAt("/jobs/map");
    fireEvent.click(await screen.findByRole("button", { name: /Near me/ }));
    expect(await screen.findByText("No openings within 25 km.")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Increase radius" }));
    await waitFor(() => expect(service.getNearbyJobs).toHaveBeenLastCalledWith(expect.objectContaining({ radiusKm: 50 })));
    expect(await screen.findByText("No openings within 50 km.")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "See remote roles" }));
    await waitFor(() => expect(params().get("tab")).toBe("remote"));
    expect(await screen.findByText("Engineer remote-1")).toBeInTheDocument();
  });

  it("searches the visible area after the user moves the map, but not after its own moves", async () => {
    renderAt("/jobs/map");
    const map = await readyMap();
    act(() => {
      map.handler("movestart")({ ownMove: true });
      map.handler("moveend")({ ownMove: true });
    });
    await new Promise((resolve) => setTimeout(resolve, 600));
    expect(screen.queryByRole("button", { name: "Jobs in this area" })).not.toBeInTheDocument();

    act(() => {
      map.handler("movestart")({ originalEvent: {} });
      map.handler("moveend")({ originalEvent: {} });
    });
    expect(window.location.hash).toBe("#5/12.9716/77.5946");
    fireEvent.click(await screen.findByRole("button", { name: "Jobs in this area" }));
    await waitFor(() => expect(service.getNearbyJobs).toHaveBeenCalledWith(expect.objectContaining({
      lat: 12.95, lng: 77.6, radiusKm: 28,
    })));
    expect(screen.getByRole("heading", { name: "Within 28 km" })).toBeInTheDocument();
  });

  it("selects a place clicked on the map and expands clusters", async () => {
    renderAt("/jobs/map");
    const map = await readyMap();
    await waitFor(() => expect(map.sources.places.setData).toHaveBeenLastCalledWith(
      expect.objectContaining({ features: expect.arrayContaining([expect.objectContaining({ properties: expect.objectContaining({ id: DELHI.id }) })]) })));

    act(() => map.handler("click", "places-points")({ features: [{ properties: { id: DELHI.id } }] }));
    await waitFor(() => expect(params().get("place")).toBe(DELHI.id));
    expect(service.getPlaceJobs).toHaveBeenCalledWith(DELHI.id, expect.anything());
    expect(await screen.findByRole("heading", { name: "Delhi" })).toBeInTheDocument();
    await waitFor(() => expect(map.easeTo).toHaveBeenCalledWith(expect.objectContaining({ center: [DELHI.lng, DELHI.lat], zoom: 10 }),
      { ownMove: true }));
    expect(map.setFilter).toHaveBeenLastCalledWith("places-selected", expect.arrayContaining([["==", ["get", "id"], DELHI.id]]));

    await act(async () => map.handler("click", "places-clusters")({
      features: [{ properties: { cluster_id: 7 }, geometry: { coordinates: [77, 20] } }],
    }));
    expect(map.sources.places.getClusterExpansionZoom).toHaveBeenCalledWith(7);
    expect(map.easeTo).toHaveBeenLastCalledWith(expect.objectContaining({ center: [77, 20], zoom: 8 }), { ownMove: true });
  });

  it("dims the markers on the Remote and India tabs", async () => {
    renderAt("/jobs/map");
    const map = await readyMap();
    fireEvent.click(await screen.findByRole("tab", { name: /Anywhere in India/ }));
    await waitFor(() => expect(map.setPaintProperty).toHaveBeenCalledWith("places-points", "circle-opacity", 0.25));
    expect(service.getUnplacedJobs).toHaveBeenCalledWith(expect.objectContaining({ kind: "india" }));
  });

  it("restores tab and query from the URL", async () => {
    renderAt("/jobs/map?tab=remote&q=java");
    expect(await screen.findByText("Engineer remote-1")).toBeInTheDocument();
    expect(service.getUnplacedJobs).toHaveBeenCalledWith(expect.objectContaining({ kind: "remote", q: "java", page: 0 }));
    expect(service.getUnplacedJobs).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("tab", { name: /Remote/ })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("searchbox")).toHaveValue("java");
  });

  it("restores a selected place from the URL without moving a view restored from the hash", async () => {
    renderAt("/jobs/map?place=1277333#9/12.97/77.59");
    const map = await readyMap();
    expect(await screen.findByRole("heading", { name: "Bengaluru" })).toBeInTheDocument();
    await waitFor(() => expect(map.setFilter).toHaveBeenLastCalledWith("places-selected", expect.anything()));
    expect(map.jumpTo).toHaveBeenCalledWith({ zoom: 9, center: [77.59, 12.97] }, { ownMove: true });
    expect(map.easeTo).not.toHaveBeenCalled();
    expect(window.location.hash).toBe("#9/12.97/77.59");
  });

  it("follows a view hash edited on the open page", async () => {
    renderAt("/jobs/map");
    const map = await readyMap();
    act(() => {
      window.history.replaceState(null, "", "/jobs/map#11/12.97/77.59");
      window.dispatchEvent(new HashChangeEvent("hashchange"));
    });
    expect(map.jumpTo).toHaveBeenLastCalledWith({ zoom: 11, center: [77.59, 12.97] }, { ownMove: true });
  });

  it("drops a place that is not in the snapshot and shows top places", async () => {
    renderAt("/jobs/map?place=999");
    expect(await screen.findByText("Top places")).toBeInTheDocument();
    await waitFor(() => expect(params().has("place")).toBe(false));
  });

  it("drops a place the server does not know with a notice", async () => {
    service.getPlaceJobs.mockRejectedValue({ kind: "error", status: 404 });
    service.getMapSnapshot.mockReturnValue(new Promise(() => {}));
    renderAt("/jobs/map?place=999");
    expect(await screen.findByText(/That place is no longer on the map/)).toBeInTheDocument();
    expect(params().has("place")).toBe(false);
  });

  it("shows a list error with Retry", async () => {
    service.getPlaceJobs.mockRejectedValueOnce({ kind: "error", status: 500 });
    renderAt("/jobs/map?place=1277333");
    fireEvent.click(await screen.findByRole("button", { name: "Retry" }));
    expect(await screen.findByText("Engineer job-1")).toBeInTheDocument();
  });

  it("explains a rate-limited list instead of failing silently", async () => {
    service.getPlaceJobs.mockRejectedValueOnce({ kind: "rateLimited", status: 429 });
    renderAt("/jobs/map?place=1277333");
    expect(await screen.findByText("You're browsing a little fast. Wait a moment, then try again.")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    expect(await screen.findByText("Engineer job-1")).toBeInTheDocument();
  });

  it("retries an unavailable snapshot, then offers Retry", async () => {
    service.getMapSnapshot.mockRejectedValue({ kind: "unavailable", retryAfterSeconds: 0.01 });
    renderAt("/jobs/map");
    expect(await screen.findByText("We couldn't load the map's places right now.")).toBeInTheDocument();
    expect(service.getMapSnapshot).toHaveBeenCalledTimes(1 + MAX_SNAPSHOT_RETRIES);
    expect(maplibre.instances).toHaveLength(1);

    service.getMapSnapshot.mockResolvedValue(snapshot);
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    expect(await screen.findByText("Top places")).toBeInTheDocument();
  });

  describe("office pins", () => {
    const officeJob = job("office-1", { places: [{ id: BENGALURU.id, name: "Bengaluru", distanceKm: 3.1, officeId: OFFICE.id,
      officeLabel: "Bellandur" }] });
    beforeEach(() => {
      service.getMapSnapshot.mockResolvedValue(withOffice);
      service.getOfficeJobs.mockResolvedValue(slice([officeJob], { totalHint: 30 }));
    });

    it("draws offices separately from the city remainder", async () => {
      renderAt("/jobs/map");
      const map = await readyMap();
      await waitFor(() => expect(map.sources.places.setData).toHaveBeenLastCalledWith(expect.objectContaining({
        features: expect.arrayContaining([
          expect.objectContaining({ properties: expect.objectContaining({ id: BENGALURU.id, kind: "city", jobs: 184 }) }),
          expect.objectContaining({ properties: expect.objectContaining({ id: OFFICE.id, kind: "office", jobs: 30 }) }),
        ]),
      })));
      expect(map.layers["offices-points"]).toBeDefined();
    });

    it("opens an office clicked on the map and lists its jobs", async () => {
      renderAt("/jobs/map?place=1277333");
      const map = await readyMap();
      act(() => map.handler("click", "offices-points")({ features: [{ properties: { id: OFFICE.id } }] }));
      await waitFor(() => expect(params().get("office")).toBe(OFFICE.id));
      expect(params().has("place")).toBe(false);
      expect(await screen.findByRole("heading", { name: "Acme" })).toBeInTheDocument();
      expect(screen.getByText("Bellandur · Bengaluru")).toBeInTheDocument();
      expect(screen.getByText("30 jobs")).toBeInTheDocument();
      expect(service.getOfficeJobs).toHaveBeenCalledWith(OFFICE.id, expect.objectContaining({ page: 0, q: "" }));
      expect(await screen.findByText("📍 Bellandur")).toBeInTheDocument();
      await waitFor(() => expect(map.easeTo).toHaveBeenCalledWith(expect.objectContaining({ center: [OFFICE.lng, OFFICE.lat] }),
        { ownMove: true }));
    });

    it("restores ?office= and searches within it", async () => {
      renderAt("/jobs/map?office=665f1c2e9b1e8a0012345678&q=java");
      expect(await screen.findByRole("heading", { name: "Acme" })).toBeInTheDocument();
      expect(service.getOfficeJobs).toHaveBeenCalledWith(OFFICE.id, expect.objectContaining({ q: "java" }));
      expect(screen.getByText("Results")).toBeInTheDocument();
    });

    it("drops an office that is not in the snapshot", async () => {
      renderAt("/jobs/map?office=unknown");
      expect(await screen.findByText("Top places")).toBeInTheDocument();
      await waitFor(() => expect(params().has("office")).toBe(false));
    });

    it("shows how many city jobs are at pinned offices", async () => {
      service.getPlaceJobs.mockResolvedValue(slice([job("job-1")], { totalHint: 214 }));
      renderAt("/jobs/map?place=1277333");
      expect(await screen.findByText("214 jobs · 30 at pinned offices")).toBeInTheDocument();
    });

    it("offers office chips near the user and opens the office", async () => {
      mockGeolocation(vi.fn((success) => success({ coords: { latitude: 12.95, longitude: 77.65 } })));
      service.getNearbyJobs.mockResolvedValue(nearby([officeJob], [
        { ...BENGALURU, distanceKm: 6.2, kind: "city" },
        { id: BENGALURU.id, name: "Bengaluru", state: "Karnataka", lat: OFFICE.lat, lng: OFFICE.lng, distanceKm: 3.1, jobs: 30,
          kind: "office", officeId: OFFICE.id, company: "Acme", label: "Bellandur" },
      ]));
      renderAt("/jobs/map");
      fireEvent.click(await screen.findByRole("button", { name: /Near me/ }));
      expect(await screen.findByText("3.1 km away")).toBeInTheDocument();
      fireEvent.click(screen.getByRole("button", { name: /Acme · Bellandur · 3.1 km/ }));
      await waitFor(() => expect(params().get("office")).toBe(OFFICE.id));
      expect(service.getOfficeJobs).toHaveBeenCalledWith(OFFICE.id, expect.anything());
    });
  });

  it("cleans up map layers and listeners on unmount", async () => {
    const { unmount } = renderAt("/jobs/map");
    const map = await readyMap();
    unmount();
    expect(map.off).toHaveBeenCalledWith("click", "places-points", expect.any(Function));
    expect(map.removeSource).toHaveBeenCalledWith("places");
    expect(map.remove).toHaveBeenCalled();
  });
});
