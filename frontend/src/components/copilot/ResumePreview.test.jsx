// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import ResumePreview from "./ResumePreview";

afterEach(cleanup);
describe("ATS resume preview", () => {
  it("renders selectable single-column text in configured order without missing skills", () => {
    render(<ResumePreview content={{ fullName: "Candidate Name", email: "candidate@example.test", summary: "Backend engineer",
      skills: ["Java", "Docker"], experience: [{ organization: "Acme", title: "Engineer", description: "Reduced latency by 20%", technologies: ["Java"] }],
      projects: [], education: [], certifications: [], links: {}, sectionOrder: ["skills", "experience", "summary"] }} />);
    const preview = screen.getByLabelText("ATS-friendly tailored resume preview");
    expect(preview).toHaveTextContent("Candidate Name"); expect(preview).toHaveTextContent("Reduced latency by 20%");
    expect(preview).not.toHaveTextContent("Kafka");
    expect(preview.textContent.indexOf("Skills")).toBeLessThan(preview.textContent.indexOf("Experience"));
  });
});
