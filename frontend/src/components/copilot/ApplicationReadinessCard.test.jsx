// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";
import ApplicationReadinessCard from "./ApplicationReadinessCard";

const services = vi.hoisted(() => ({ getApplicationReadiness: vi.fn(), updateWorkspaceJob: vi.fn() }));
vi.mock("../../services/application-copilot-service", () => services);

describe("Application readiness card", () => {
  beforeEach(() => { Object.values(services).forEach((mock) => mock.mockReset()); services.updateWorkspaceJob.mockResolvedValue({ stage: "SAVED" }); });
  afterEach(cleanup);
  it("shows distinct match/readiness and truthful missing-skill guidance", async () => {
    services.getApplicationReadiness.mockResolvedValue({ matchScore: 88, readiness: { readinessScore: 71, readinessLevel: "NEARLY_READY", active: true, recommendations: ["Add evidence"] }, keywordAnalysis: { missing: [{ keyword: "Kafka" }] } });
    render(<MemoryRouter><ApplicationReadinessCard jobId="job-1" onPrepare={vi.fn()} /></MemoryRouter>);
    expect(await screen.findByText("Application Readiness: 71%")).toBeInTheDocument();
    expect(screen.getByText(/Match Score 88%/)).toBeInTheDocument();
    expect(screen.getByText(/Kafka — missing from your profile and never added automatically/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /edit my profile/i })).toHaveAttribute("href", "/my-profile");
    fireEvent.click(screen.getByRole("button", { name: "Save job" }));
    await waitFor(() => expect(services.updateWorkspaceJob).toHaveBeenCalledWith("job-1", { stage: "SAVED" }));
    expect(screen.getByRole("button", { name: "Saved" })).toBeDisabled();
  });
  it("disables preparation for inactive jobs", async () => {
    services.getApplicationReadiness.mockResolvedValue({ matchScore: 50, readiness: { readinessScore: 0, readinessLevel: "INACTIVE", active: false, recommendations: [] }, keywordAnalysis: { missing: [] } });
    render(<MemoryRouter><ApplicationReadinessCard jobId="job-1" onPrepare={vi.fn()} /></MemoryRouter>);
    expect(await screen.findByRole("button", { name: "No longer active" })).toBeDisabled();
  });
  it("reports workspace save failures and allows retry", async () => {
    services.getApplicationReadiness.mockResolvedValue({ matchScore: 50, readiness: { readinessScore: 60, readinessLevel: "NEEDS_WORK", active: true, recommendations: [] }, keywordAnalysis: { missing: [] } });
    services.updateWorkspaceJob.mockRejectedValueOnce(new Error("network"));
    render(<MemoryRouter><ApplicationReadinessCard jobId="job-1" onPrepare={vi.fn()} /></MemoryRouter>);
    fireEvent.click(await screen.findByRole("button", { name: "Save job" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Could not save this job. Please try again.");
    expect(screen.getByRole("button", { name: "Save job" })).toBeEnabled();
  });
});
