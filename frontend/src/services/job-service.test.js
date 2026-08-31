import { describe, expect, it, vi } from "vitest";

const { apiGet } = vi.hoisted(() => ({ apiGet: vi.fn() }));
vi.mock("./helper", () => ({ default: { get: apiGet, post: vi.fn() } }));

describe("paginated job API", () => {
  it("requests a selected page and preserves pagination metadata", async () => {
    const page = { content: [{ id: "job-1" }], page: 2, size: 20, totalElements: 45, totalPages: 3, first: false, last: true };
    apiGet.mockResolvedValue({ data: page });
    const { getAllJobs } = await import("./job-service");
    await expect(getAllJobs({ page: 2, size: 20, q: "java" })).resolves.toEqual(page);
    expect(apiGet).toHaveBeenCalledWith("/api/v1/jobs", { params: { page: 2, size: 20, q: "java" } });
  });

  it("uses candidate-derived matching routes without a candidate identifier", async () => {
    apiGet.mockResolvedValue({ data: { content: [] } });
    const { getMatchedJobs, getJobMatch } = await import("./job-service");
    await getMatchedJobs({ page: 1, minMatch: 75 });
    expect(apiGet).toHaveBeenCalledWith("/api/v1/jobs/matched", { params: { page: 1, minMatch: 75 } });
    apiGet.mockResolvedValue({ data: { jobId: "job/a" } });
    await getJobMatch("job/a");
    expect(apiGet).toHaveBeenCalledWith("/api/v1/jobs/job%2Fa/match");
  });
});
