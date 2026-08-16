// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => ({
  getAggregationStatus: vi.fn(), getSyncHistory: vi.fn(), getSyncRun: vi.fn(),
  getAggregationConflicts: vi.fn(), startAggregationSync: vi.fn(),
  resolveAggregationConflict: vi.fn(), aggregationAdminError: vi.fn(),
}));
vi.mock("../services/aggregation-admin-service", () => api);
vi.mock("../components/Header", () => ({ default: () => <div data-testid="header" /> }));
vi.mock("../components/Footer", () => ({ default: () => <div data-testid="footer" /> }));

import AggregationAdmin from "./AggregationAdmin";

const run = (outcome = "COMPLETED") => ({
  runId: `run-${outcome}`, provider: "lever", employer: "board", trigger: "MANUAL",
  startedAt: "2026-08-16T12:00:00Z", completedAt: "2026-08-16T12:00:01Z", outcome,
  inserted: 1, updated: 2, unchanged: 3, rejected: 0, failedItems: 0,
  failedBatches: 0, failedEmployers: 0, retries: 1, failureDetail: null,
});
const page = (content = [], overrides = {}) => ({
  content, page: 0, size: 20, totalElements: content.length, totalPages: content.length ? 1 : 0,
  first: true, last: true, ...overrides,
});
const status = {
  activeImportedJobs: 7, inactiveImportedJobs: 2, latestRuns: [run()],
  providerCompanyCounts: [{ provider: "lever", employer: "board", company: "Acme", activeListings: 4, inactiveListings: 1 }],
};
const conflict = {
  id: "conflict-1", type: "IDENTITY_FINGERPRINT", status: "OPEN", jobIds: ["job-a", "job-b"],
  occurrences: 2, lastObservedAt: "2026-08-16T12:00:00Z",
};

function successfulLoad(history = page([run()]), conflicts = page([conflict])) {
  api.getAggregationStatus.mockResolvedValue(status);
  api.getSyncHistory.mockResolvedValue(history);
  api.getAggregationConflicts.mockResolvedValue(conflicts);
  api.getSyncRun.mockResolvedValue({ ...run(), failureDetail: "bounded failure" });
}

describe("aggregation ADMIN page", () => {
  beforeEach(() => {
    Object.values(api).forEach((mock) => mock.mockReset());
    api.aggregationAdminError.mockImplementation((error, fallback) => error?.safe || fallback || "Request failed");
  });
  afterEach(() => cleanup());

  it("renders loading, health/counts/history/conflicts, detail, and pagination", async () => {
    successfulLoad(page([run()], { totalPages: 2, last: false }));
    render(<AggregationAdmin />);
    expect(screen.getByRole("status")).toHaveTextContent("Loading aggregation operations");
    expect(await screen.findByText("Active imported jobs")).toBeInTheDocument();
    expect(screen.getByText("Acme")).toBeInTheDocument();
    expect(screen.getByText("Open identity conflicts")).toBeInTheDocument();

    fireEvent.click(screen.getAllByRole("button", { name: "View detail" })[0]);
    expect(await screen.findByText("bounded failure")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    await waitFor(() => expect(api.getSyncHistory).toHaveBeenCalledWith({ page: 1, size: 20 }));
  });

  it("shows explicit empty and load-error states with retry", async () => {
    successfulLoad(page(), page());
    api.getAggregationStatus.mockResolvedValue({
      activeImportedJobs: 0, inactiveImportedJobs: 0, latestRuns: [], providerCompanyCounts: [],
    });
    render(<AggregationAdmin />);
    expect(await screen.findByText("No synchronization runs have been recorded.")).toBeInTheDocument();
    expect(screen.getByText("No history matches this page.")).toBeInTheDocument();
    expect(screen.getByText("No open conflicts require review.")).toBeInTheDocument();
    cleanup();

    api.getAggregationStatus.mockRejectedValue({ safe: "Administrator access is required." });
    render(<AggregationAdmin />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Administrator access is required");
    expect(screen.getByRole("button", { name: "Retry" })).toBeInTheDocument();
  });

  it.each([
    ["PARTIAL", "Synchronization PARTIAL"],
    ["FAILED", "Synchronization FAILED"],
  ])("surfaces a %s structured sync outcome", async (outcome, expected) => {
    successfulLoad();
    api.startAggregationSync.mockResolvedValue({ outcome, runId: `run-${outcome}` });
    render(<AggregationAdmin />);
    await screen.findByText("Manual synchronization");
    fireEvent.click(screen.getByRole("button", { name: "Start sync" }));
    expect(await screen.findByText(new RegExp(expected))).toBeInTheDocument();
  });

  it.each([
    ["A synchronization or reconciliation is already running.", "already running"],
    ["The synchronization lost its lease and stopped safely.", "lost its lease"],
  ])("surfaces safe operational failure states", async (safe, expected) => {
    successfulLoad();
    api.startAggregationSync.mockRejectedValue({ safe });
    render(<AggregationAdmin />);
    await screen.findByText("Manual synchronization");
    fireEvent.click(screen.getByRole("button", { name: "Start sync" }));
    expect(await screen.findByText(new RegExp(expected))).toBeInTheDocument();
  });

  it("prevents duplicate sync submission and supports employer-specific scope", async () => {
    successfulLoad();
    let resolveSync;
    api.startAggregationSync.mockReturnValue(new Promise((resolve) => { resolveSync = resolve; }));
    render(<AggregationAdmin />);
    await screen.findByText("Manual synchronization");
    fireEvent.change(screen.getByLabelText("Provider"), { target: { value: "greenhouse" } });
    fireEvent.change(screen.getByLabelText("Employer board"), { target: { value: "board" } });
    const button = screen.getByRole("button", { name: "Start sync" });
    fireEvent.click(button);
    fireEvent.click(button);
    expect(api.startAggregationSync).toHaveBeenCalledTimes(1);
    expect(api.startAggregationSync).toHaveBeenCalledWith("greenhouse", "board");
    expect(screen.getByRole("button", { name: "Synchronizing…" })).toBeDisabled();
    await act(async () => resolveSync({ outcome: "COMPLETED", runId: "run" }));
  });

  it("requires distinct conflict choices and resolves only once", async () => {
    successfulLoad();
    let finishResolution;
    api.resolveAggregationConflict.mockReturnValue(new Promise((resolve) => { finishResolution = resolve; }));
    render(<AggregationAdmin />);
    await screen.findByText("IDENTITY_FINGERPRINT");
    fireEvent.click(screen.getByRole("button", { name: "Resolve safely" }));
    expect(await screen.findByText(/Choose two distinct jobs/)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Canonical job for conflict-1"), { target: { value: "job-a" } });
    fireEvent.change(screen.getByLabelText("Duplicate job for conflict-1"), { target: { value: "job-b" } });
    const resolveButton = screen.getByRole("button", { name: "Resolve safely" });
    fireEvent.click(resolveButton);
    fireEvent.click(resolveButton);
    expect(api.resolveAggregationConflict).toHaveBeenCalledTimes(1);
    expect(api.resolveAggregationConflict).toHaveBeenCalledWith("conflict-1", "job-a", "job-b");
    await act(async () => finishResolution({ status: "RESOLVED" }));
  });
});
