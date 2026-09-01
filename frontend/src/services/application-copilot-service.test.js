// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }));
vi.mock("./helper", () => ({ default: api }));

describe("Application Copilot API", () => {
  beforeEach(() => { Object.values(api).forEach((mock) => mock.mockReset()); });

  it("uses candidate-derived analysis and version routes", async () => {
    api.get.mockResolvedValue({ data: { readinessScore: 70 } });
    api.post.mockResolvedValue({ data: { id: "v1" } });
    const service = await import("./application-copilot-service");
    await service.getApplicationReadiness("job/a");
    expect(api.get).toHaveBeenCalledWith("/api/v1/jobs/job%2Fa/application-readiness");
    await service.getTailoringPlan("job/a");
    expect(api.get).toHaveBeenCalledWith("/api/v1/jobs/job%2Fa/tailoring-plan");
    await service.createResumeVersion("job/a");
    expect(api.post).toHaveBeenCalledWith("/api/v1/jobs/job%2Fa/resume-versions", {});
    await service.createCoverLetter("job/a");
    expect(api.post).toHaveBeenCalledWith("/api/v1/jobs/job%2Fa/cover-letters", {});
  });

  it("updates only authenticated candidate resources and workspace fields", async () => {
    api.put.mockResolvedValue({ data: { id: "v1" } });
    api.delete.mockResolvedValue({});
    const service = await import("./application-copilot-service");
    await service.updateResumeVersion("v/1", { summary: "Edited" });
    expect(api.put).toHaveBeenCalledWith("/api/v1/resume-versions/v%2F1", { summary: "Edited" });
    await service.updateCoverLetter("c/1", { content: "Draft" });
    expect(api.put).toHaveBeenCalledWith("/api/v1/cover-letters/c%2F1", { content: "Draft" });
    await service.updateWorkspaceJob("job/a", { stage: "APPLIED" });
    expect(api.put).toHaveBeenCalledWith("/api/v1/application-workspace/job%2Fa", { stage: "APPLIED" });
    await service.removeSavedJob("job/a");
    expect(api.delete).toHaveBeenCalledWith("/api/v1/application-workspace/job%2Fa");
  });

  it("exports DOCX through a private blob response", async () => {
    api.get.mockResolvedValue({ data: new Blob(["docx"]) });
    URL.createObjectURL = vi.fn(() => "blob:test"); URL.revokeObjectURL = vi.fn();
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
    const { exportResumeVersion } = await import("./application-copilot-service");
    await exportResumeVersion("v1", "tailored.docx");
    expect(api.get).toHaveBeenCalledWith("/api/v1/resume-versions/v1/export", { responseType: "blob" });
    expect(click).toHaveBeenCalled(); expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:test");
    click.mockRestore();
  });
});
