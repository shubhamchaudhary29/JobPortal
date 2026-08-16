import { describe, expect, it } from "vitest";
import { API_V1, apiRoutes } from "./api-routes";

describe("versioned API routes", () => {
  it("centralizes public HTTP routes under /api/v1", () => {
    expect(API_V1).toBe("/api/v1");
    expect(apiRoutes.auth.sessions).toBe("/api/v1/auth/sessions");
    expect(apiRoutes.jobs.byId("job id")).toBe("/api/v1/jobs/job%20id");
    expect(apiRoutes.applications.resume("a/1")).toBe("/api/v1/applications/a%2F1/resume");
    expect(apiRoutes.adminAggregation.historyById("run/1")).toBe("/api/v1/admin/ingestion/history/run%2F1");
    expect(apiRoutes.adminAggregation.sync("greenhouse")).toBe("/api/v1/admin/ingestion/greenhouse/sync");
  });
});
