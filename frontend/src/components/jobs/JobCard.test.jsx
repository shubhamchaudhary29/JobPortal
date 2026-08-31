// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { MemoryRouter } from "react-router-dom";
import JobCard from "./JobCard";

const job = { id: "job-1", title: "Backend Engineer", company: "Acme", location: "Pune", experience: 2, salary: 1200000 };

describe("JobCard matching presentation", () => {
  afterEach(cleanup);

  it("keeps normal public cards free of personalized match information", () => {
    render(<MemoryRouter><JobCard job={job} /></MemoryRouter>);
    expect(screen.getByText("Actively Hiring")).toBeInTheDocument();
    expect(screen.queryByText(/% Match/)).not.toBeInTheDocument();
  });

  it("shows a candidate match badge, concise matches, and one gap", () => {
    const match = { overallScore: 91, matchLevel: "EXCELLENT", confidence: "HIGH", matchedSkills: ["Java", "Spring Boot", "Docker", "SQL"], missingSkills: ["Kafka"] };
    render(<MemoryRouter><JobCard job={job} match={match} /></MemoryRouter>);
    expect(screen.getByText(/91% Match/)).toBeInTheDocument();
    expect(screen.getByLabelText("Matched skills").children).toHaveLength(3);
    expect(screen.getByText("Gap to review: Kafka")).toBeInTheDocument();
  });

  it("does not overstate a sparse-profile score", () => {
    render(<MemoryRouter><JobCard job={job} match={{ overallScore: 95, matchLevel: "LOW_DATA", confidence: "LOW" }} /></MemoryRouter>);
    expect(screen.getByText("Limited profile data")).toBeInTheDocument();
    expect(screen.queryByText(/95%/)).not.toBeInTheDocument();
  });
});
