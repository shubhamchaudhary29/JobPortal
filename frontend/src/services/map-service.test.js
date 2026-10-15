import { beforeEach, describe, expect, it, vi } from "vitest";

const { apiGet } = vi.hoisted(() => ({ apiGet: vi.fn() }));
vi.mock("./helper", () => ({ default: { get: apiGet } }));

import { getMapSnapshot, getNearbyJobs, getPlaceJobs, getUnplacedJobs, isAbortError } from "./map-service";

const slice = { content: [{ id: "job-1" }], page: 0, size: 20, hasNext: false, totalHint: 1 };
const httpError = (status, headers = {}) => Object.assign(new Error(`HTTP ${status}`), { response: { status, headers } });

describe("map service", () => {
  beforeEach(() => {
    apiGet.mockReset();
    apiGet.mockResolvedValue({ data: slice });
  });

  it("loads the snapshot with the caller's signal", async () => {
    const signal = new AbortController().signal;
    apiGet.mockResolvedValue({ data: { places: [] } });
    await expect(getMapSnapshot({ signal })).resolves.toEqual({ places: [] });
    expect(apiGet).toHaveBeenCalledWith("/api/v1/jobs/map/snapshot", { params: {}, signal });
  });

  it("requests one place's jobs with paging and an encoded id, omitting an empty q", async () => {
    const signal = new AbortController().signal;
    await expect(getPlaceJobs("12/34", { q: "  ", page: 2, signal })).resolves.toEqual(slice);
    expect(apiGet).toHaveBeenCalledWith("/api/v1/jobs/map/places/12%2F34/jobs", { params: { page: 2, size: 20 }, signal });

    await getPlaceJobs("1277333", { q: " java " });
    expect(apiGet).toHaveBeenLastCalledWith("/api/v1/jobs/map/places/1277333/jobs",
      { params: { q: "java", page: 0, size: 20 }, signal: undefined });
  });

  it("rounds coordinates to the 0.05° grid before they leave the browser", async () => {
    await getNearbyJobs({ lat: 12.9716, lng: 77.5946, radiusKm: 25, q: "" });
    expect(apiGet).toHaveBeenCalledWith("/api/v1/jobs/map/nearby",
      { params: { lat: 12.95, lng: 77.6, radiusKm: 25, page: 0, size: 20 }, signal: undefined });
    const [, { params }] = apiGet.mock.calls[0];
    expect(JSON.stringify(params)).not.toContain("12.9716");
  });

  it("requests unplaced jobs by kind", async () => {
    await getUnplacedJobs({ kind: "india", q: "java", page: 1, size: 10 });
    expect(apiGet).toHaveBeenCalledWith("/api/v1/jobs/map/unplaced",
      { params: { kind: "india", q: "java", page: 1, size: 10 }, signal: undefined });
  });

  it("turns 503 into an unavailable error carrying Retry-After", async () => {
    apiGet.mockRejectedValue(httpError(503, { "retry-after": "5" }));
    await expect(getPlaceJobs("1")).rejects.toMatchObject({ kind: "unavailable", retryAfterSeconds: 5 });
    apiGet.mockRejectedValue(httpError(503));
    await expect(getMapSnapshot()).rejects.toMatchObject({ kind: "unavailable", retryAfterSeconds: 10 });
  });

  it("turns other failures into a generic error with the status", async () => {
    apiGet.mockRejectedValue(httpError(404));
    await expect(getPlaceJobs("missing")).rejects.toMatchObject({ kind: "error", status: 404 });
    apiGet.mockRejectedValue(new Error("Network Error"));
    await expect(getUnplacedJobs({ kind: "remote" })).rejects.toMatchObject({ kind: "error" });
  });

  it("rethrows aborts unchanged so callers can ignore them", async () => {
    const canceled = Object.assign(new Error("canceled"), { name: "CanceledError", code: "ERR_CANCELED" });
    apiGet.mockRejectedValue(canceled);
    const failure = await getNearbyJobs({ lat: 28.6, lng: 77.2 }).catch((error) => error);
    expect(failure).toBe(canceled);
    expect(isAbortError(failure)).toBe(true);
    expect(isAbortError(new DOMException("aborted", "AbortError"))).toBe(true);
    expect(isAbortError({ kind: "error" })).toBe(false);
  });
});
