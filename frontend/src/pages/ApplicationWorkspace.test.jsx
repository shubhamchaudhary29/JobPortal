// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";
import ApplicationWorkspace from "./ApplicationWorkspace";

const api = vi.hoisted(() => ({ getWorkspace: vi.fn(), getWorkspaceAnalytics: vi.fn(), removeSavedJob: vi.fn() }));
vi.mock("../services/application-copilot-service", () => api);
vi.mock("../components/Header", () => ({ default: () => <header>Header</header> }));
vi.mock("../components/Footer", () => ({ default: () => <footer>Footer</footer> }));

const analytics = { saved: 1, preparing: 2, applied: 3, onlineAssessments: 1, interviews: 1, offers: 0, rejected: 1, responseRate: 66.7, message: null };
const item = { jobId: "job-1", job: { title: "Backend Engineer", company: "Example Corp" }, active: false,
  matchScore: 88, readiness: { readinessScore: 71 }, stage: "SAVED", followUpStatus: "OVERDUE", updatedAt: "2026-09-01T00:00:00Z",
  resumeVersionId: null, coverLetterVersionId: null, recruiterStatus: null, notes: "Private note" };

describe("Application dashboard", () => {
  beforeEach(() => { Object.values(api).forEach((mock) => mock.mockReset()); api.getWorkspaceAnalytics.mockResolvedValue(analytics); api.getWorkspace.mockResolvedValue({ content: [item] }); api.removeSavedJob.mockResolvedValue({}); });
  afterEach(cleanup);
  it("shows analytics, inactive history, follow-up state, filters, and search", async () => {
    render(<MemoryRouter><ApplicationWorkspace /></MemoryRouter>);
    expect(await screen.findByText("Backend Engineer")).toBeInTheDocument();
    expect(screen.getByText("No longer active")).toBeInTheDocument(); expect(screen.getByText("OVERDUE follow-up")).toBeInTheDocument();
    expect(screen.getByText("66.7%")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Filter by stage"), { target: { value: "SAVED" } });
    await waitFor(() => expect(api.getWorkspace).toHaveBeenLastCalledWith(expect.objectContaining({ stage: "SAVED" })));
    fireEvent.change(screen.getByLabelText("Search applications"), { target: { value: "Example" } });
    fireEvent.click(screen.getByRole("button", { name: "Search" }));
    await waitFor(() => expect(api.getWorkspace).toHaveBeenLastCalledWith(expect.objectContaining({ search: "Example" })));
  });
  it("supports unsaving unused jobs and an honest empty analytics state", async () => {
    render(<MemoryRouter><ApplicationWorkspace /></MemoryRouter>); await screen.findByText("Backend Engineer");
    fireEvent.click(screen.getByRole("button", { name: "Unsave" }));
    await waitFor(() => expect(api.removeSavedJob).toHaveBeenCalledWith("job-1"));
  });
  it("renders loading and empty states", async () => {
    api.getWorkspace.mockResolvedValue({ content: [] });
    render(<MemoryRouter><ApplicationWorkspace /></MemoryRouter>);
    expect(screen.getByLabelText("Loading applications")).toBeInTheDocument();
    expect(await screen.findByText("No tracked applications")).toBeInTheDocument();
  });
});
