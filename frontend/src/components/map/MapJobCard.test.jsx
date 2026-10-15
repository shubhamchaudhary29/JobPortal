// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { MemoryRouter } from "react-router-dom";
import MapJobCard from "./MapJobCard";

const job = {
  id: "job-1", title: "Backend Engineer", company: "Acme", location: "Bengaluru, Karnataka", employmentType: "FULL_TIME",
  salaryMin: 800000, salaryMax: 1200000, source: "adzuna", createdAt: "2026-10-01T09:00:00", publishedAt: "2026-10-10T09:00:00",
  places: [{ id: "1277333", name: "Bengaluru", distanceKm: 3.24 }],
};

const renderCard = (props) => render(<MemoryRouter><MapJobCard {...props} /></MemoryRouter>);

describe("MapJobCard", () => {
  afterEach(cleanup);

  it("shows salary range, employment type, distance and the posted date", () => {
    renderCard({ job, distanceKm: 3.24 });
    expect(screen.getByText("💰 ₹8–12 LPA")).toBeInTheDocument();
    expect(screen.getByText("Full time")).toBeInTheDocument();
    expect(screen.getByText("3.2 km away")).toBeInTheDocument();
    expect(screen.getByText(/Posted 10 Oct 2026/)).toBeInTheDocument();
  });

  it("is a single link to the job with the attribution as a separate link", () => {
    const { container } = renderCard({ job });
    expect(screen.getByRole("link", { name: /Backend Engineer/ })).toHaveAttribute("href", "/jobs/job-1");
    expect(screen.getByRole("link", { name: "Jobs by Adzuna" })).toHaveAttribute("href", "https://www.adzuna.in");
    expect(screen.getAllByRole("link")).toHaveLength(2);
    expect(container.querySelector("a a")).toBeNull();
    expect(screen.queryByText(/away/)).not.toBeInTheDocument();
  });

  it("falls back gracefully without salary, type or source", () => {
    renderCard({ job: { id: "job-2", title: "Analyst", company: "Globex", location: "Remote", createdAt: "2026-10-05T09:00:00" } });
    expect(screen.getByText("💰 Not disclosed")).toBeInTheDocument();
    expect(screen.getByText(/Posted 5 Oct 2026/)).toBeInTheDocument();
    expect(screen.queryByTestId("provider-attribution")).not.toBeInTheDocument();
  });
});
