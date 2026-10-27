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

  it("shows the office, else the locality, else the site the listing names, else that the office is not listed", () => {
    renderCard({ job: { ...job, places: [{ ...job.places[0], officeId: "o1", officeLabel: "Bellandur", localityName: "Whitefield" }] } });
    expect(screen.getByText("📍 Bellandur")).toHaveAttribute("title", "Bengaluru, Karnataka");
    cleanup();
    renderCard({ job: { ...job, places: [{ ...job.places[0], localityId: "osm-n1", localityName: "Whitefield" }] } });
    expect(screen.getByText("📍 Whitefield")).toBeInTheDocument();
    cleanup();
    renderCard({ job });
    expect(screen.getByText("📍 Bengaluru · Office not listed")).toHaveAttribute("title", "Bengaluru, Karnataka");
    cleanup();
    renderCard({ job: { ...job, location: "Bengaluru- We Work", places: [{ ...job.places[0], precision: "city", siteLabel: "We Work" }] } });
    expect(screen.getByText("📍 Bengaluru · We Work")).toHaveAttribute("title",
      "Office named in the listing; its exact address isn't mapped yet");
    expect(screen.queryByText(/Office not listed/)).not.toBeInTheDocument();
    cleanup();
    renderCard({ job: { ...job, places: [{ ...job.places[0], precision: "office", officeId: "o1", officeLabel: "Bellandur",
      siteLabel: "We Work" }] } });
    expect(screen.getByText("📍 Bellandur")).toBeInTheDocument();
    expect(screen.queryByText(/We Work/)).not.toBeInTheDocument();
    cleanup();
    renderCard({ job: { ...job, location: "Remote", places: [] } });
    expect(screen.getByText("📍 Remote")).toBeInTheDocument();
    expect(screen.queryByText(/Office not listed/)).not.toBeInTheDocument();
  });

  it("falls back gracefully without salary, type or source", () => {
    renderCard({ job: { id: "job-2", title: "Analyst", company: "Globex", location: "Remote", createdAt: "2026-10-05T09:00:00" } });
    expect(screen.getByText("💰 Not disclosed")).toBeInTheDocument();
    expect(screen.getByText(/Posted 5 Oct 2026/)).toBeInTheDocument();
    expect(screen.queryByTestId("provider-attribution")).not.toBeInTheDocument();
  });
});
