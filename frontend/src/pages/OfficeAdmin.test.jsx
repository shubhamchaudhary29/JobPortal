// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";

// jsdom has no WebGL, so MapLibre itself is mocked, including the draggable Marker the editor uses.
const maplibre = vi.hoisted(() => {
  const instances = [];
  const markers = [];
  class Map {
    constructor(options) {
      this.options = options;
      this.handlers = [];
      this.canvas = { style: {} };
      this.remove = vi.fn();
      this.addControl = vi.fn();
      this.touchZoomRotate = { disableRotation: vi.fn() };
      this.keyboard = { disableRotation: vi.fn() };
      this.on = vi.fn((...args) => this.handlers.push(args));
      this.off = vi.fn();
      this.jumpTo = vi.fn();
      this.getCanvas = () => this.canvas;
      instances.push(this);
    }

    handler(event) { return this.handlers.find(([name]) => name === event)[1]; }
  }
  class Marker {
    constructor(options) {
      this.options = options;
      this.handlers = {};
      this.position = null;
      this.remove = vi.fn();
      markers.push(this);
    }

    on(event, handler) { this.handlers[event] = handler; return this; }
    setLngLat([lng, lat]) { this.position = { lat, lng }; return this; }
    getLngLat() { return this.position; }
    addTo(map) { this.map = map; return this; }
  }
  return { instances, markers, Map, Marker, addProtocol: vi.fn(), setWorkerUrl: vi.fn(), NavigationControl: vi.fn(),
    AttributionControl: vi.fn() };
});
vi.mock("maplibre-gl", () => maplibre);
vi.mock("maplibre-gl/dist/maplibre-gl.css", () => ({}));
vi.mock("maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url", () => ({ default: "/assets/maplibre-worker.js" }));
vi.mock("../map/tilesManifest", async (original) => ({ ...(await original()), loadTilesManifest: vi.fn().mockResolvedValue(null) }));
const api = vi.hoisted(() => ({
  listOffices: vi.fn(), getOfficeCandidates: vi.fn(), createOffice: vi.fn(), updateOffice: vi.fn(), getOfficeAudit: vi.fn(),
  getMapSnapshot: vi.fn(), getOsmReport: vi.fn(), pickOsmOffice: vi.fn(), rejectOsmOffice: vi.fn(),
}));
vi.mock("../services/office-admin-service", async (original) => ({ ...(await original()),
  listOffices: api.listOffices, getOfficeCandidates: api.getOfficeCandidates, createOffice: api.createOffice,
  updateOffice: api.updateOffice, getOfficeAudit: api.getOfficeAudit, getOsmReport: api.getOsmReport,
  pickOsmOffice: api.pickOsmOffice, rejectOsmOffice: api.rejectOsmOffice }));
vi.mock("../services/map-service", async (original) => ({ ...(await original()), getMapSnapshot: api.getMapSnapshot }));
vi.mock("../components/Header", () => ({ default: () => <header>Header</header> }));
vi.mock("../components/Footer", () => ({ default: () => <footer>Footer</footer> }));

import OfficeAdmin from "./OfficeAdmin";

const BLR = { id: "1277333", name: "Bengaluru", state: "Karnataka", lat: 12.97194, lng: 77.59369, jobs: 214 };
const office = { id: "o1", company: "Acme", companyKey: "acme", label: "Bellandur", placeId: BLR.id, placeName: "Bengaluru",
  state: "Karnataka", lat: 12.926, lng: 77.6762, enabled: true, updatedBy: "admin@example.com", updatedAt: "2026-10-15T10:00:00Z" };
const page = (content) => ({ content, page: 0, totalPages: 1, totalElements: content.length, first: true, last: true });
const OSM_MATCHES = [
  { osmRef: "osm-n101", name: "Acme Campus", lat: 12.9352, lng: 77.6245, distanceKm: 4.4, url: "https://www.openstreetmap.org/node/101" },
  { osmRef: "osm-w7", name: "Acme Tower B", lat: 12.98, lng: 77.6, distanceKm: 1.0, url: "https://www.openstreetmap.org/way/7" },
];
const REPORT = {
  autoApply: false, osmOffices: 14768, noMatch: { pairs: 30, jobs: 300 }, oneMatch: { pairs: 2, jobs: 1250 },
  severalMatches: { pairs: 1, jobs: 1234 }, jobsMovedByAutoApply: 1250,
  topMatches: [{ company: "Globex", companyKey: "globex", placeId: "1259229", placeName: "Pune", osmName: "Globex House",
    osmRef: "osm-r9", url: "https://www.openstreetmap.org/relation/9", jobs: 40 }],
};

async function readyMap() {
  await waitFor(() => expect(maplibre.instances.length).toBeGreaterThan(0));
  const map = maplibre.instances[maplibre.instances.length - 1];
  act(() => map.handler("load")());
  return map;
}

describe("OfficeAdmin", () => {
  beforeEach(() => {
    maplibre.instances.length = 0;
    maplibre.markers.length = 0;
    Object.values(api).forEach((mock) => mock.mockReset());
    api.getOfficeCandidates.mockResolvedValue([
      { company: "Acme Pvt Ltd", companyKey: "acme", placeId: BLR.id, placeName: "Bengaluru", state: "Karnataka", jobs: 1234,
        osmCandidates: OSM_MATCHES, autoApplied: null },
      { company: "Globex", companyKey: "globex", placeId: "1259229", placeName: "Pune", state: "Maharashtra", jobs: 40 },
    ]);
    api.listOffices.mockResolvedValue(page([office]));
    api.getMapSnapshot.mockResolvedValue({ places: [BLR], offices: [] });
  });
  afterEach(cleanup);

  const renderPage = () => render(<MemoryRouter><OfficeAdmin /></MemoryRouter>);

  it("lists the worklist and existing offices", async () => {
    renderPage();
    const worklist = await screen.findByRole("table", { name: "Office pin candidates" });
    expect(within(worklist).getByText("Acme Pvt Ltd")).toBeInTheDocument();
    expect(within(worklist).getByText("1.2k")).toBeInTheDocument();
    const offices = await screen.findByRole("table", { name: "Pinned offices" });
    expect(within(offices).getByText("12.92600, 77.67620")).toBeInTheDocument();
    expect(api.getOfficeCandidates).toHaveBeenCalledWith(50);
  });

  it("pins a candidate by clicking and dragging on the map and saves it", async () => {
    api.createOffice.mockResolvedValue({ ...office, id: "o2", company: "Acme Pvt Ltd", label: "Bellandur campus" });
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: "Pin office for Acme Pvt Ltd in Bengaluru" }));
    const map = await readyMap();
    await waitFor(() => expect(map.jumpTo).toHaveBeenLastCalledWith({ center: [BLR.lng, BLR.lat], zoom: 12 }));
    expect(screen.getByRole("textbox", { name: "Office company" })).toHaveValue("Acme Pvt Ltd");

    act(() => map.handler("click")({ lngLat: { lat: 12.9259712, lng: 77.6762381 } }));
    expect(screen.getByTestId("pin-coordinates")).toHaveTextContent("12.92597, 77.67624");
    const marker = maplibre.markers.find((candidate) => candidate.options.draggable);
    expect(marker.options).toEqual({ draggable: true, color: "#4f46e5" });
    expect(marker.map).toBe(map);

    act(() => {
      marker.position = { lat: 12.93, lng: 77.68 };
      marker.handlers.dragend();
    });
    expect(screen.getByTestId("pin-coordinates")).toHaveTextContent("12.93000, 77.68000");
    expect(maplibre.markers.filter((candidate) => candidate.options.draggable)).toHaveLength(1);

    fireEvent.change(screen.getByRole("textbox", { name: "Office label" }), { target: { value: " Bellandur campus " } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(api.createOffice).toHaveBeenCalledWith({
      company: "Acme Pvt Ltd", label: "Bellandur campus", lat: 12.93, lng: 77.68, placeId: BLR.id,
    }));
    expect(await screen.findByText(/Saved Acme Pvt Ltd · Bellandur campus \(Bengaluru\)/)).toBeInTheDocument();
    expect(api.getOfficeCandidates).toHaveBeenCalledTimes(2);
  });

  it("suggests OpenStreetMap matches in the worklist and the editor, and picks one", async () => {
    api.pickOsmOffice.mockResolvedValue({ ...office, id: "o3", company: "Acme Pvt Ltd", label: "Acme Campus", source: "OSM",
      osmRef: "osm-n101" });
    renderPage();
    const worklist = await screen.findByRole("table", { name: "Office pin candidates" });
    expect(within(worklist).getByText("2 OpenStreetMap matches")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Pin office for Acme Pvt Ltd in Bengaluru" }));
    const map = await readyMap();

    const matches = await screen.findByRole("list", { name: "OpenStreetMap matches" });
    expect(within(matches).getByRole("link", { name: "Acme Campus" })).toHaveAttribute("href", "https://www.openstreetmap.org/node/101");
    expect(within(matches).getByRole("link", { name: "Acme Campus" })).toHaveAttribute("rel", "noopener noreferrer");
    expect(screen.getAllByText(/OpenStreetMap suggestions come from volunteer map data and can be wrong/).length).toBeGreaterThan(0);
    const grey = maplibre.markers.filter((marker) => marker.options.color === "#64748b");
    expect(grey.map((marker) => marker.position)).toEqual([{ lat: 12.9352, lng: 77.6245 }, { lat: 12.98, lng: 77.6 }]);
    expect(grey.every((marker) => marker.map === map)).toBe(true);

    fireEvent.click(within(matches).getByRole("button", { name: "Use Acme Campus as the office" }));
    await waitFor(() => expect(api.pickOsmOffice).toHaveBeenCalledWith("osm-n101", { companyKey: "acme", placeId: BLR.id }));
    expect(await screen.findByText(/Saved Acme Pvt Ltd · Acme Campus \(Bengaluru\) from OpenStreetMap/)).toBeInTheDocument();
    expect(api.getOfficeCandidates).toHaveBeenCalledTimes(2);
  });

  it("rejects a match from the editor and drops it from the list", async () => {
    api.rejectOsmOffice.mockResolvedValue({ decision: "REJECT" });
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: "Pin office for Acme Pvt Ltd in Bengaluru" }));
    await readyMap();
    fireEvent.click(await screen.findByRole("button", { name: "Acme Tower B is not this company" }));
    await waitFor(() => expect(api.rejectOsmOffice).toHaveBeenCalledWith("osm-w7", { companyKey: "acme", placeId: BLR.id }));
    expect(await screen.findByText("Acme Tower B will not be used for Acme Pvt Ltd in Bengaluru.")).toBeInTheDocument();
    const matches = screen.getByRole("list", { name: "OpenStreetMap matches" });
    expect(within(matches).queryByText("Acme Tower B")).not.toBeInTheDocument();
    expect(within(matches).getByText("Acme Campus")).toBeInTheDocument();
  });

  it("lists matches for review in their own tab with links and Reject", async () => {
    api.getOsmReport.mockResolvedValue(REPORT);
    api.rejectOsmOffice.mockResolvedValue({ decision: "REJECT" });
    renderPage();
    fireEvent.click(await screen.findByRole("tab", { name: "OpenStreetMap matches" }));
    const table = await screen.findByRole("table", { name: "OpenStreetMap office matches by jobs" });
    expect(screen.getByText(/Auto-apply is off: nothing below is on the map yet/)).toBeInTheDocument();
    expect(screen.getByText(/1\.3k jobs would be placed/)).toBeInTheDocument();
    expect(within(table).getByRole("link", { name: "Globex House" })).toHaveAttribute("href", "https://www.openstreetmap.org/relation/9");
    expect(screen.queryByRole("table", { name: "Office pin candidates" })).not.toBeInTheDocument();

    fireEvent.click(within(table).getByRole("button", { name: "Reject Globex House for Globex in Pune" }));
    await waitFor(() => expect(api.rejectOsmOffice).toHaveBeenCalledWith("osm-r9", { companyKey: "globex", placeId: "1259229" }));
    await waitFor(() => expect(api.getOsmReport).toHaveBeenCalledTimes(2));
  });

  it("shows the server's validation message inline", async () => {
    api.createOffice.mockRejectedValue({ response: { status: 400, data: {
      code: "BAD_REQUEST", detail: "Pin is far from Bengaluru (130 km); offices must be within 60 km of their city" } } });
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: "Pin office for Acme Pvt Ltd in Bengaluru" }));
    const map = await readyMap();
    act(() => map.handler("click")({ lngLat: { lat: 12.3, lng: 76.64 } }));
    fireEvent.change(screen.getByRole("textbox", { name: "Office label" }), { target: { value: "HQ" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Pin is far from Bengaluru (130 km)");
  });

  it("edits an office without changing its company and disables offices", async () => {
    api.updateOffice.mockResolvedValue({ ...office, enabled: false });
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: "Edit" }));
    const map = await readyMap();
    await waitFor(() => expect(map.jumpTo).toHaveBeenLastCalledWith({ center: [office.lng, office.lat], zoom: 12 }));
    expect(screen.getByRole("textbox", { name: "Office company" })).toHaveAttribute("readonly");
    act(() => map.handler("click")({ lngLat: { lat: 12.927, lng: 77.677 } }));
    api.updateOffice.mockResolvedValueOnce({ ...office, lat: 12.927, lng: 77.677 });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(api.updateOffice).toHaveBeenCalledWith("o1", { label: "Bellandur", lat: 12.927, lng: 77.677 }));

    fireEvent.click(await screen.findByRole("button", { name: "Disable Acme Bellandur" }));
    await waitFor(() => expect(api.updateOffice).toHaveBeenLastCalledWith("o1", { enabled: false }));
    expect(await screen.findByText("Acme · Bellandur is now disabled.")).toBeInTheDocument();
  });

  it("shows an office's audit history", async () => {
    api.getOfficeAudit.mockResolvedValue({ content: [
      { id: "a1", action: "CREATE", actor: "admin@example.com", at: "2026-10-15T10:00:00Z", before: null,
        after: { label: "Bellandur", lat: 12.926, lng: 77.6762, enabled: true } },
    ] });
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: "History" }));
    const history = await screen.findByRole("region", { name: /History · Acme · Bellandur/ });
    expect(within(history).getByText("CREATE")).toBeInTheDocument();
    expect(within(history).getByText(/Bellandur · 12.92600, 77.67620/)).toBeInTheDocument();
  });
});
