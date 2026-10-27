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
    expect(apiRoutes.adminAggregation.providers).toBe("/api/v1/admin/ingestion/providers");
    expect(apiRoutes.adminAggregation.registryValidation("a b")).toBe("/api/v1/admin/ingestion/registry/a%20b/validation");
    expect(apiRoutes.candidateProfile.resumeStatus).toBe("/api/v1/candidate-profile/resume/status");
  });

  it("routes the map endpoints and encodes place ids", () => {
    expect(apiRoutes.jobs.mapSnapshot).toBe("/api/v1/jobs/map/snapshot");
    expect(apiRoutes.jobs.mapPlaceJobs("1277333")).toBe("/api/v1/jobs/map/places/1277333/jobs");
    expect(apiRoutes.jobs.mapPlaceJobs("a/b c")).toBe("/api/v1/jobs/map/places/a%2Fb%20c/jobs");
    expect(apiRoutes.jobs.mapPlaceCompanies("1277333")).toBe("/api/v1/jobs/map/places/1277333/companies");
    expect(apiRoutes.jobs.mapPlaceCompanies("a/b")).toBe("/api/v1/jobs/map/places/a%2Fb/companies");
    expect(apiRoutes.jobs.mapNearby).toBe("/api/v1/jobs/map/nearby");
    expect(apiRoutes.jobs.mapUnplaced).toBe("/api/v1/jobs/map/unplaced");
    expect(apiRoutes.jobs.mapOfficeJobs("a/b")).toBe("/api/v1/jobs/map/offices/a%2Fb/jobs");
    expect(apiRoutes.jobs.mapLocalityJobs("osm-n377219489")).toBe("/api/v1/jobs/map/localities/osm-n377219489/jobs");
    expect(apiRoutes.jobs.mapLocalityJobs("a/b")).toBe("/api/v1/jobs/map/localities/a%2Fb/jobs");
    expect(apiRoutes.adminOffices.collection).toBe("/api/v1/admin/geo/offices");
    expect(apiRoutes.adminOffices.candidates).toBe("/api/v1/admin/geo/offices/candidates");
    expect(apiRoutes.adminOffices.byId("o 1")).toBe("/api/v1/admin/geo/offices/o%201");
    expect(apiRoutes.adminOffices.audit("o1")).toBe("/api/v1/admin/geo/offices/o1/audit");
    expect(apiRoutes.adminOffices.osmReport).toBe("/api/v1/admin/geo/offices/osm-report");
    expect(apiRoutes.adminOffices.osmPick("osm-n1")).toBe("/api/v1/admin/geo/offices/osm/osm-n1/pick");
    expect(apiRoutes.adminOffices.osmReject("osm-w2")).toBe("/api/v1/admin/geo/offices/osm/osm-w2/reject");
  });
});
