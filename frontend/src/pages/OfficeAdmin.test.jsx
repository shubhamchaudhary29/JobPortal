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
  getMapSnapshot: vi.fn(),
}));
vi.mock("../services/office-admin-service", async (original) => ({ ...(await original()),
  listOffices: api.listOffices, getOfficeCandidates: api.getOfficeCandidates, createOffice: api.createOffice,
  updateOffice: api.updateOffice, getOfficeAudit: api.getOfficeAudit }));
vi.mock("../services/map-service", async (original) => ({ ...(await original()), getMapSnapshot: api.getMapSnapshot }));
vi.mock("../components/Header", () => ({ default: () => <header>Header</header> }));
vi.mock("../components/Footer", () => ({ default: () => <footer>Footer</footer> }));

import OfficeAdmin from "./OfficeAdmin";

const BLR = { id: "1277333", name: "Bengaluru", state: "Karnataka", lat: 12.97194, lng: 77.59369, jobs: 214 };
const office = { id: "o1", company: "Acme", companyKey: "acme", label: "Bellandur", placeId: BLR.id, placeName: "Bengaluru",
  state: "Karnataka", lat: 12.926, lng: 77.6762, enabled: true, updatedBy: "admin@example.com", updatedAt: "2026-10-15T10:00:00Z" };
const page = (content) => ({ content, page: 0, totalPages: 1, totalElements: content.length, first: true, last: true });

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
      { company: "Acme Pvt Ltd", companyKey: "acme", placeId: BLR.id, placeName: "Bengaluru", state: "Karnataka", jobs: 1234 },
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
    const [marker] = maplibre.markers;
    expect(marker.options).toEqual({ draggable: true, color: "#4f46e5" });
    expect(marker.map).toBe(map);

    act(() => {
      marker.position = { lat: 12.93, lng: 77.68 };
      marker.handlers.dragend();
    });
    expect(screen.getByTestId("pin-coordinates")).toHaveTextContent("12.93000, 77.68000");
    expect(maplibre.markers).toHaveLength(1);

    fireEvent.change(screen.getByRole("textbox", { name: "Office label" }), { target: { value: " Bellandur campus " } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(api.createOffice).toHaveBeenCalledWith({
      company: "Acme Pvt Ltd", label: "Bellandur campus", lat: 12.93, lng: 77.68, placeId: BLR.id,
    }));
    expect(await screen.findByText(/Saved Acme Pvt Ltd · Bellandur campus \(Bengaluru\)/)).toBeInTheDocument();
    expect(api.getOfficeCandidates).toHaveBeenCalledTimes(2);
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
