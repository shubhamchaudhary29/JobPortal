// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";

const { getAllJobs } = vi.hoisted(() => ({ getAllJobs: vi.fn() }));
vi.mock("../services/job-service", () => ({ getAllJobs }));
vi.mock("../components/Header", () => ({ default: () => <header>Header</header> }));

import Jobs from "./Jobs";

const page = { content: [], page: 0, totalElements: 0, totalPages: 0, first: true, last: true };

describe("Jobs page map entry point", () => {
  beforeEach(() => getAllJobs.mockReset().mockResolvedValue(page));
  afterEach(cleanup);

  it("offers View on map", async () => {
    render(<MemoryRouter initialEntries={["/jobs"]}><Jobs /></MemoryRouter>);
    expect(screen.getByRole("link", { name: /View on map/ })).toHaveAttribute("href", "/jobs/map");
    await screen.findByText("No jobs found");
  });

  it("carries the role search to the map as q", async () => {
    render(<MemoryRouter initialEntries={["/jobs?role=Java%20Developer"]}><Jobs /></MemoryRouter>);
    expect(screen.getByRole("link", { name: /View on map/ })).toHaveAttribute("href", "/jobs/map?q=Java+Developer");
    await screen.findByText("No jobs found");
  });
});
