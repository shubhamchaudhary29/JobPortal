import { beforeEach, describe, expect, it, vi } from "vitest";

const { get, post, patch } = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), patch: vi.fn() }));
vi.mock("./helper", () => ({ default: { get, post, patch } }));

describe("employer registry ADMIN API", () => {
  beforeEach(() => { get.mockReset(); post.mockReset(); patch.mockReset(); });

  it("uses the ADMIN registry routes", async () => {
    get.mockResolvedValue({ data: { content: [] } });
    post.mockResolvedValue({ data: {} });
    patch.mockResolvedValue({ data: {} });
    const service = await import("./employer-registry-service");

    await service.listRegistry({ provider: "lever", page: 0, size: 20 });
    await service.createRegistryEntry({ provider: "lever", boardId: "cred", company: "CRED", enabled: true });
    await service.updateRegistryEntry("id/1", { enabled: false, disabledReason: "moved" });
    await service.validateRegistryEntry("id/1");
    await service.getRegistryAudit("id/1");

    expect(get).toHaveBeenNthCalledWith(1, "/api/v1/admin/ingestion/registry", { params: { provider: "lever", page: 0, size: 20 } });
    expect(post).toHaveBeenNthCalledWith(1, "/api/v1/admin/ingestion/registry", { provider: "lever", boardId: "cred", company: "CRED", enabled: true });
    expect(patch).toHaveBeenCalledWith("/api/v1/admin/ingestion/registry/id%2F1", { enabled: false, disabledReason: "moved" });
    expect(post).toHaveBeenNthCalledWith(2, "/api/v1/admin/ingestion/registry/id%2F1/validation");
    expect(get).toHaveBeenNthCalledWith(2, "/api/v1/admin/ingestion/registry/id%2F1/audit", { params: { page: 0, size: 20 } });
  });

  it("maps errors to safe messages", async () => {
    const { registryError } = await import("./employer-registry-service");
    expect(registryError({ response: { status: 409 } })).toContain("already");
    expect(registryError({ response: { status: 403 } })).toContain("Administrator");
    expect(registryError({ response: { status: 400 } })).toContain("board ID");
    expect(registryError({ response: { status: 500, data: { detail: "raw" } } }, "Safe")).toBe("Safe");
  });
});
