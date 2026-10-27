// @vitest-environment jsdom
import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const { getCentreCompanies } = vi.hoisted(() => ({ getCentreCompanies: vi.fn() }));
vi.mock("../services/map-service", async (importOriginal) => ({ ...(await importOriginal()), getCentreCompanies }));

import { focusCity, RING_FETCH_DEBOUNCE_MS, ringGeoJson, useCentreCompanies } from "./useCentreCompanies";

const BENGALURU = { id: "1277333", name: "Bengaluru", lat: 12.97, lng: 77.59, jobs: 10, officeJobs: 2, localityJobs: 3 };
const MYSURU = { id: "1262321", name: "Mysuru", lat: 12.3, lng: 76.65, jobs: 4, officeJobs: 0, localityJobs: 0 };
const ALL_LISTED = { id: "1", name: "Listed", lat: 12.98, lng: 77.6, jobs: 3, officeJobs: 3, localityJobs: 0 };
const snapshot = { version: "v1", places: [BENGALURU, MYSURU, ALL_LISTED] };
const centre = (companies) => ({ placeId: BENGALURU.id, placeName: "Bengaluru", centreJobs: 5, companies });

/** A MapLibre stand-in: a view, event handlers, and a move that fires moveend like the real map. */
function fakeMap({ zoom = 13, center = [77.59, 12.97], span = 0.2 } = {}) {
  const handlers = {};
  const state = { zoom, center };
  return {
    state,
    getZoom: () => state.zoom,
    getCenter: () => ({ lng: state.center[0], lat: state.center[1] }),
    getBounds: () => ({ getWest: () => state.center[0] - span, getSouth: () => state.center[1] - span,
      getEast: () => state.center[0] + span, getNorth: () => state.center[1] + span }),
    on: vi.fn((type, handler) => { (handlers[type] ??= new Set()).add(handler); }),
    off: vi.fn((type, handler) => handlers[type]?.delete(handler)),
    move(next) {
      Object.assign(state, next);
      ["zoomend", "moveend"].forEach((type) => handlers[type]?.forEach((handler) => handler()));
    },
    listeners: (type) => handlers[type]?.size ?? 0,
  };
}

const markers = (geojson) => geojson.features.filter((feature) => feature.geometry.type === "Point");

describe("focusCity", () => {
  const view = (zoom, center = [77.59, 12.97], bounds = [77.39, 12.77, 77.79, 13.17]) => ({ zoom, center, bounds });

  it("has no focus below ring zoom", () => {
    expect(focusCity(snapshot, null, view(11.9))).toBeNull();
    expect(focusCity(snapshot, null, null)).toBeNull();
  });

  it("prefers the selected city with centre jobs, else the nearest in-view city with centre jobs", () => {
    expect(focusCity(snapshot, { id: MYSURU.id }, view(12))).toBe(MYSURU);
    expect(focusCity(snapshot, null, view(12, [77.6, 12.98]))).toBe(BENGALURU);
    // A city whose jobs are all at offices has no centre to ring.
    expect(focusCity(snapshot, { id: ALL_LISTED.id }, view(12, [77.6, 12.98]))).toBe(BENGALURU);
    expect(focusCity(snapshot, null, view(12, [80, 20], [79.9, 19.9, 80.1, 20.1]))).toBeNull();
  });
});

describe("ringGeoJson", () => {
  it("adds one marker per company, in order, with its initial, plus an outline", () => {
    const geojson = ringGeoJson(BENGALURU, [{ company: "wonka", jobs: 3 }, { company: "Écoles", jobs: 1 }], 13);
    expect(geojson.features[0].geometry.type).toBe("LineString");
    expect(markers(geojson).map((feature) => feature.properties)).toEqual([
      { id: "1277333|wonka", placeId: "1277333", company: "wonka", jobs: 3, initial: "W" },
      { id: "1277333|Écoles", placeId: "1277333", company: "Écoles", jobs: 1, initial: "É" },
    ]);
    expect(ringGeoJson(BENGALURU, [], 13).features).toEqual([]);
    expect(ringGeoJson(null, [{ company: "A", jobs: 1 }], 13).features).toEqual([]);
  });
});

describe("useCentreCompanies", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    getCentreCompanies.mockReset();
  });
  afterEach(() => vi.useRealTimers());

  it("fetches the focus city's companies after the debounce and lays them out", async () => {
    getCentreCompanies.mockResolvedValue(centre([{ company: "Wonka", jobs: 3 }, { company: "Hooli", jobs: 2 }]));
    const map = fakeMap();
    const { result } = renderHook(() => useCentreCompanies({ map, snapshot, selected: null }));

    expect(result.current.focus).toBe(BENGALURU);
    expect(result.current.visible).toBe(false);
    expect(getCentreCompanies).not.toHaveBeenCalled();
    await act(async () => { await vi.advanceTimersByTimeAsync(RING_FETCH_DEBOUNCE_MS); });
    expect(getCentreCompanies).toHaveBeenCalledWith(BENGALURU.id, { limit: 40, signal: expect.any(AbortSignal) });
    expect(result.current.visible).toBe(true);
    expect(markers(result.current.geojson).map((feature) => feature.properties.company)).toEqual(["Wonka", "Hooli"]);
  });

  it("hides below ring zoom and re-lays out on zoom without refetching", async () => {
    getCentreCompanies.mockResolvedValue(centre([{ company: "Wonka", jobs: 3 }]));
    const map = fakeMap({ zoom: 13 });
    const { result } = renderHook(() => useCentreCompanies({ map, snapshot, selected: null }));
    await act(async () => { await vi.advanceTimersByTimeAsync(RING_FETCH_DEBOUNCE_MS); });
    const at13 = markers(result.current.geojson)[0].geometry.coordinates;

    act(() => map.move({ zoom: 15 }));
    const at15 = markers(result.current.geojson)[0].geometry.coordinates;
    expect(Math.abs(at15[1] - BENGALURU.lat)).toBeCloseTo(Math.abs(at13[1] - BENGALURU.lat) / 4, 8);

    act(() => map.move({ zoom: 11 }));
    expect(result.current.visible).toBe(false);
    expect(result.current.focus).toBeNull();
    act(() => map.move({ zoom: 13 }));
    await act(async () => { await vi.advanceTimersByTimeAsync(RING_FETCH_DEBOUNCE_MS); });
    expect(result.current.visible).toBe(true);
    expect(getCentreCompanies).toHaveBeenCalledTimes(1);
  });

  it("aborts the pending fetch when the focus city changes and memoizes per city and snapshot version", async () => {
    let firstSignal;
    getCentreCompanies.mockImplementation((placeId, { signal }) => {
      if (placeId === BENGALURU.id) {
        firstSignal = signal;
        return new Promise(() => {});
      }
      return Promise.resolve({ ...centre([{ company: "Infosys", jobs: 4 }]), placeId });
    });
    const map = fakeMap();
    const { result, rerender } = renderHook(({ selected, data }) => useCentreCompanies({ map, snapshot: data, selected }),
      { initialProps: { selected: null, data: snapshot } });
    await act(async () => { await vi.advanceTimersByTimeAsync(RING_FETCH_DEBOUNCE_MS); });
    expect(firstSignal.aborted).toBe(false);

    rerender({ selected: { id: MYSURU.id }, data: snapshot });
    expect(firstSignal.aborted).toBe(true);
    await act(async () => { await vi.advanceTimersByTimeAsync(RING_FETCH_DEBOUNCE_MS); });
    expect(result.current.focus).toBe(MYSURU);
    expect(markers(result.current.geojson)[0].properties.company).toBe("Infosys");

    rerender({ selected: { id: MYSURU.id }, data: { ...snapshot } });
    await act(async () => { await vi.advanceTimersByTimeAsync(RING_FETCH_DEBOUNCE_MS); });
    expect(getCentreCompanies).toHaveBeenCalledTimes(2);
    rerender({ selected: { id: MYSURU.id }, data: { ...snapshot, version: "v2" } });
    await act(async () => { await vi.advanceTimersByTimeAsync(RING_FETCH_DEBOUNCE_MS); });
    expect(getCentreCompanies).toHaveBeenCalledTimes(3);
  });

  it("debounces quick focus changes into one request", async () => {
    getCentreCompanies.mockResolvedValue(centre([{ company: "Wonka", jobs: 3 }]));
    const map = fakeMap();
    const { rerender } = renderHook(({ selected }) => useCentreCompanies({ map, snapshot, selected }),
      { initialProps: { selected: null } });
    await act(async () => { await vi.advanceTimersByTimeAsync(RING_FETCH_DEBOUNCE_MS - 50); });
    rerender({ selected: { id: MYSURU.id } });
    await act(async () => { await vi.advanceTimersByTimeAsync(RING_FETCH_DEBOUNCE_MS); });
    expect(getCentreCompanies).toHaveBeenCalledTimes(1);
    expect(getCentreCompanies).toHaveBeenCalledWith(MYSURU.id, expect.anything());
  });

  it("draws nothing after a failed fetch and listens to the map only while mounted", async () => {
    getCentreCompanies.mockRejectedValue(Object.assign(new Error("Map request failed"), { kind: "error" }));
    const map = fakeMap();
    const { result, unmount } = renderHook(() => useCentreCompanies({ map, snapshot, selected: null }));
    await act(async () => { await vi.advanceTimersByTimeAsync(RING_FETCH_DEBOUNCE_MS); });
    expect(result.current.visible).toBe(false);
    expect(map.listeners("moveend")).toBe(1);
    unmount();
    expect(map.listeners("moveend")).toBe(0);
    expect(map.listeners("zoomend")).toBe(0);
  });

  it("does nothing without a map", () => {
    const { result } = renderHook(() => useCentreCompanies({ map: null, snapshot, selected: null }));
    expect(result.current).toMatchObject({ focus: null, visible: false });
  });
});
