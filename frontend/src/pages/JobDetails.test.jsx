// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { AuthContext } from "../auth/auth-context";
import JobDetails from "./JobDetails";

const mocks = vi.hoisted(() => ({ getJobById: vi.fn(), getJobMatch: vi.fn(), hasUserApplied: vi.fn(), getApplicationReadiness: vi.fn() }));
vi.mock("../services/job-service", () => ({ getJobById: mocks.getJobById, getJobMatch: mocks.getJobMatch }));
vi.mock("../services/application-service", () => ({ hasUserApplied: mocks.hasUserApplied }));
vi.mock("../services/application-copilot-service", () => ({ getApplicationReadiness: mocks.getApplicationReadiness, updateWorkspaceJob: vi.fn() }));
vi.mock("../components/Header", () => ({ default: () => <header>Header</header> }));
vi.mock("../components/ApplyJobModal", () => ({ default: () => null }));

const job = { id: "job-1", title: "Backend Engineer", company: "Acme", location: "Remote", experience: 2, salary: 1000000, description: "Build services." };
const session = (role, token = "token") => ({ role, accessToken: token, initialized: true });

function renderPage(auth) {
  return render(<AuthContext.Provider value={auth}><MemoryRouter initialEntries={["/jobs/job-1"]}><Routes><Route path="/jobs/:jobId" element={<JobDetails />} /></Routes></MemoryRouter></AuthContext.Provider>);
}

describe("Job detail matching", () => {
  beforeEach(() => {
    Object.values(mocks).forEach((mock) => mock.mockReset());
    mocks.getJobById.mockResolvedValue(job);
    mocks.hasUserApplied.mockResolvedValue(false);
    mocks.getApplicationReadiness.mockResolvedValue({ matchScore: 87, readiness: { readinessScore: 72, readinessLevel: "NEARLY_READY", active: true, recommendations: ["Add detail"] }, keywordAnalysis: { missing: [{ keyword: "Kafka" }] } });
  });
  afterEach(cleanup);

  it("shows evidence-backed explanation, matched and missing skills to candidates", async () => {
    mocks.getJobMatch.mockResolvedValue({ overallScore: 87, matchLevel: "STRONG", confidence: "HIGH", matchedSkills: ["Java", "Spring Boot"], missingSkills: ["Kafka"], skillScore: 80, titleScore: 100, normalizedWeights: { skills: 40, title: 15 }, explanation: ["The role aligns with your preferred role family."] });
    renderPage(session("USER"));
    expect(await screen.findByText("Your match: 87%")).toBeInTheDocument();
    expect(screen.getByText(/Spring Boot/)).toBeInTheDocument();
    expect(screen.getByText("Kafka")).toBeInTheDocument();
    expect(screen.getByText("The role aligns with your preferred role family.")).toBeInTheDocument();
    expect(screen.getByText("32.0 / 40.0 points")).toBeInTheDocument();
    expect(await screen.findByRole("button", { name: "Prepare Application" })).toBeInTheDocument();
  });

  it("shows sparse-profile guidance without a misleading percentage", async () => {
    mocks.getJobMatch.mockResolvedValue({ overallScore: 95, matchLevel: "LOW_DATA", confidence: "LOW", matchedSkills: [], missingSkills: [], explanation: [] });
    renderPage(session("USER"));
    expect(await screen.findByRole("heading", { name: "Limited profile data" })).toBeInTheDocument();
    expect(screen.queryByText("Your match: 95%")).not.toBeInTheDocument();
    expect(screen.getByText(/Add skills and job preferences/)).toBeInTheDocument();
  });

  it("never requests private matching for anonymous users or non-candidate roles", async () => {
    renderPage(session(null, null));
    await screen.findByText("Backend Engineer");
    expect(mocks.getJobMatch).not.toHaveBeenCalled();
    cleanup();
    renderPage(session("RECRUITER"));
    await screen.findByText("Backend Engineer");
    expect(mocks.getJobMatch).not.toHaveBeenCalled();
  });
});
