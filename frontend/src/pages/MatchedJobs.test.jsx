// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";
import MatchedJobs from "./MatchedJobs";

const { getMatchedJobs } = vi.hoisted(() => ({ getMatchedJobs: vi.fn() }));
vi.mock("../services/job-service", () => ({ getMatchedJobs }));
vi.mock("../components/Header", () => ({ default: () => <header>Header</header> }));

const matched = (id, score) => ({
  job: { id, title: `Job ${id}`, company: "Acme", location: "Pune", experience: 1, salary: 0 },
  match: { overallScore: score, matchLevel: "STRONG", confidence: "HIGH", matchedSkills: ["Java"], missingSkills: [] },
});

describe("Best Matches feed", () => {
  beforeEach(() => getMatchedJobs.mockReset());
  afterEach(cleanup);

  it("shows loading then preserves backend personalized ranking", async () => {
    let resolve;
    getMatchedJobs.mockReturnValue(new Promise((done) => { resolve = done; }));
    render(<MemoryRouter><MatchedJobs /></MemoryRouter>);
    expect(screen.getByLabelText("Loading matches")).toBeInTheDocument();
    resolve({ content: [matched("high", 90), matched("lower", 75)], page: 0, totalElements: 2, totalPages: 1, first: true, last: true });
    await screen.findByText("Job high");
    const headings = screen.getAllByRole("heading", { level: 3 });
    expect(headings.map((value) => value.textContent)).toEqual(["Job high", "Job lower"]);
  });

  it("submits filters", async () => {
    getMatchedJobs
      .mockResolvedValueOnce({ content: [matched("one", 80)], page: 0, totalElements: 1, totalPages: 1, first: true, last: true })
      .mockResolvedValueOnce({ content: [matched("filtered", 90)], page: 0, totalElements: 1, totalPages: 1, first: true, last: true });
    render(<MemoryRouter><MatchedJobs /></MemoryRouter>);
    await screen.findByText("Job one");
    fireEvent.change(screen.getByLabelText("Minimum match"), { target: { value: "75" } });
    fireEvent.change(screen.getByLabelText("Work mode"), { target: { value: "REMOTE" } });
    fireEvent.click(screen.getByRole("button", { name: "Apply" }));
    await screen.findByText("Job filtered");
    expect(getMatchedJobs).toHaveBeenLastCalledWith(expect.objectContaining({ minMatch: 75, workMode: "REMOTE", page: 0 }));
  });

  it("requests the next personalized page", async () => {
    getMatchedJobs
      .mockResolvedValueOnce({ content: [matched("one", 80)], page: 0, totalElements: 13, totalPages: 2, first: true, last: false })
      .mockResolvedValueOnce({ content: [matched("page-two", 78)], page: 1, totalElements: 13, totalPages: 2, first: false, last: true });
    render(<MemoryRouter><MatchedJobs /></MemoryRouter>);
    await screen.findByText("Job one");
    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    await screen.findByText("Job page-two");
    expect(getMatchedJobs).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 }));
  });

  it("renders empty and recoverable error states", async () => {
    getMatchedJobs.mockResolvedValueOnce({ content: [], page: 0, totalElements: 0, totalPages: 0, first: true, last: true });
    const view = render(<MemoryRouter><MatchedJobs /></MemoryRouter>);
    expect(await screen.findByText("No personalized matches found")).toBeInTheDocument();
    view.unmount();
    getMatchedJobs.mockRejectedValueOnce(new Error("network"));
    render(<MemoryRouter><MatchedJobs /></MemoryRouter>);
    expect(await screen.findByText(/couldn't calculate your job matches/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Retry" })).toBeInTheDocument();
  });

  it("shows sparse-profile guidance when every result has low confidence", async () => {
    const lowData = matched("sparse", 90);
    lowData.match.confidence = "LOW";
    lowData.match.matchLevel = "LOW_DATA";
    getMatchedJobs.mockResolvedValue({ content: [lowData], page: 0, totalElements: 1, totalPages: 1, first: true, last: true });
    render(<MemoryRouter><MatchedJobs /></MemoryRouter>);
    expect(await screen.findByText("Limited profile data.")).toBeInTheDocument();
    expect(screen.getByText(/Add skills and preferred roles/)).toBeInTheDocument();
  });
});
