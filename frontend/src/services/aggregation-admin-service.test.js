import { beforeEach, describe, expect, it, vi } from "vitest";

const { get, post } = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }));
vi.mock("./helper", () => ({ default: { get, post } }));

describe("aggregation ADMIN API", () => {
  beforeEach(() => { get.mockReset(); post.mockReset(); });

  it("uses bounded status, history, detail, and conflict routes", async () => {
    get.mockResolvedValue({ data: { content: [] } });
    const service = await import("./aggregation-admin-service");

    await service.getAggregationStatus({ provider: "lever" });
    await service.getSyncHistory({ page: 2, size: 20 });
    await service.getSyncRun("run/1");
    await service.getAggregationConflicts({ status: "OPEN", page: 0, size: 20 });

    expect(get).toHaveBeenNthCalledWith(1, "/api/v1/admin/ingestion/status", { params: { provider: "lever" } });
    expect(get).toHaveBeenNthCalledWith(2, "/api/v1/admin/ingestion/history", { params: { page: 2, size: 20 } });
    expect(get).toHaveBeenNthCalledWith(3, "/api/v1/admin/ingestion/history/run%2F1");
    expect(get).toHaveBeenNthCalledWith(4, "/api/v1/admin/ingestion/conflicts", { params: { status: "OPEN", page: 0, size: 20 } });
  });

  it("supports provider-wide, employer-specific, and reference-safe resolution posts", async () => {
    post.mockResolvedValue({ data: { outcome: "COMPLETED" } });
    const service = await import("./aggregation-admin-service");

    await service.startAggregationSync("greenhouse", " board ");
    await service.startAggregationSync("adzuna");
    await service.resolveAggregationConflict("conflict/1", "canonical", "duplicate");

    expect(post).toHaveBeenNthCalledWith(1, "/api/v1/admin/ingestion/greenhouse/sync", null, { params: { employer: "board" } });
    expect(post).toHaveBeenNthCalledWith(2, "/api/v1/admin/ingestion/adzuna/sync", null, { params: {} });
    expect(post).toHaveBeenNthCalledWith(3, "/api/v1/admin/ingestion/conflicts/conflict%2F1/resolution", { canonicalJobId: "canonical", duplicateJobId: "duplicate" });
  });

  it("maps authentication, lock, and lease-loss responses to safe messages", async () => {
    const { aggregationAdminError } = await import("./aggregation-admin-service");
    expect(aggregationAdminError({ response: { status: 401 } })).toContain("expired");
    expect(aggregationAdminError({ response: { status: 403 } })).toContain("Administrator");
    expect(aggregationAdminError({ response: { status: 409, data: { status: "LOCKED" } } })).toContain("already running");
    expect(aggregationAdminError({ response: { status: 503, data: { status: "LEASE_LOST" } } })).toContain("lost its lease");
    expect(aggregationAdminError({ response: { status: 500, data: { detail: "secret raw failure" } } }, "Safe fallback"))
      .toBe("Safe fallback");
  });
});
