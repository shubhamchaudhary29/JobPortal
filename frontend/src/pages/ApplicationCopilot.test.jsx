// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import ApplicationCopilot from "./ApplicationCopilot";

const api = vi.hoisted(() => ({
  getApplicationReadiness: vi.fn(), getTailoringPlan: vi.fn(), getResumeVersions: vi.fn(), getCoverLetters: vi.fn(),
  getWorkspaceJob: vi.fn(), createResumeVersion: vi.fn(), updateResumeVersion: vi.fn(), exportResumeVersion: vi.fn(),
  createCoverLetter: vi.fn(), updateCoverLetter: vi.fn(), updateWorkspaceJob: vi.fn(),
}));
vi.mock("../services/application-copilot-service", () => api);
vi.mock("../components/Header", () => ({ default: () => <header>Header</header> }));

const keywordAnalysis = {
  strong: [{ keyword: "Java", importance: "REQUIRED", evidence: [{ sourceField: "experience.description" }] }],
  supported: [{ keyword: "Docker", importance: "PREFERRED", evidence: [{ sourceField: "projects.description" }] }],
  underrepresented: [{ keyword: "AWS", importance: "PREFERRED", evidence: [{ sourceField: "skills" }] }],
  missing: [{ keyword: "Kafka", importance: "REQUIRED", evidence: [] }],
};
const ready = { job: { jobId: "job-1", title: "Backend Engineer", company: "Example Corp" }, matchScore: 88, matchLevel: "STRONG",
  readiness: { readinessScore: 71, readinessLevel: "NEARLY_READY", evidenceCoverage: 50, active: true,
    disclaimer: "Not an official ATS score", blockers: ["Kafka is missing"], recommendations: ["Add truthful detail"] }, keywordAnalysis };
const plan = { plan: { actions: [{ type: "MISSING_REQUIREMENT", subject: "Kafka", rationale: "Missing from your profile. It will not be added.", evidence: [] }] } };
const resume = { id: "resume-1", jobId: "job-1", versionNumber: 1, title: "Backend resume", staleness: "OUTDATED",
  stalenessMessage: "Base profile changed since this version was created.", active: true, tailoringActions: plan.plan.actions,
  content: { fullName: "Candidate Name", email: "candidate@example.test", summary: "Backend engineer", skills: ["Java", "Docker"],
    experience: [{ organization: "Acme", title: "Engineer", description: "Improved API performance", technologies: ["Java"] }],
    projects: [{ name: "JobPortal", description: "Containerized the service", technologies: ["Docker"] }], education: [], certifications: [], links: {},
    sectionOrder: ["summary", "skills", "experience", "projects"] } };
const letter = { id: "letter-1", versionNumber: 1, title: "Cover letter", content: "Dear Hiring Team\n\nJava evidence", staleness: "CURRENT", active: true };
const workspace = { jobId: "job-1", stage: "PREPARING", notes: "Initial note", appliedExternally: false, recruiterStatus: null };

function renderPage() {
  return render(<MemoryRouter initialEntries={["/jobs/job-1/prepare"]}><Routes><Route path="/jobs/:jobId/prepare" element={<ApplicationCopilot />} /></Routes></MemoryRouter>);
}

describe("Application Copilot workspace", () => {
  beforeEach(() => {
    Object.values(api).forEach((mock) => mock.mockReset());
    api.getApplicationReadiness.mockResolvedValue(ready); api.getTailoringPlan.mockResolvedValue(plan);
    api.getResumeVersions.mockResolvedValue({ content: [] }); api.getCoverLetters.mockResolvedValue({ content: [] });
    api.getWorkspaceJob.mockRejectedValue({ response: { status: 404 } });
  });
  afterEach(cleanup);

  it("keeps Match Score and Application Readiness distinct and explains truthful keyword gaps", async () => {
    renderPage();
    expect(await screen.findByText("Backend Engineer")).toBeInTheDocument();
    expect(screen.getByText("88%")).toBeInTheDocument(); expect(screen.getByText("71%")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Keyword Analysis" }));
    expect(screen.getByText("Strong evidence")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Missing from your profile" })).toBeInTheDocument();
    expect(screen.getByText(/It will not be added/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Update your profile/ })).toHaveAttribute("href", "/my-profile");
  });

  it("edits, previews, saves, and exports a stale tailored resume version", async () => {
    api.getResumeVersions.mockResolvedValue({ content: [resume] });
    api.updateResumeVersion.mockImplementation(async (_id, value) => ({ ...resume, content: { ...resume.content, summary: value.summary } }));
    renderPage(); await screen.findByText("Backend Engineer");
    fireEvent.click(screen.getByRole("button", { name: "Tailored Resume" }));
    expect(screen.getByText(/Base profile changed/)).toBeInTheDocument();
    expect(screen.getByLabelText("ATS-friendly tailored resume preview")).toHaveTextContent("Improved API performance");
    fireEvent.change(screen.getByLabelText("Tailored summary"), { target: { value: "Edited truthful summary" } });
    fireEvent.click(screen.getByRole("button", { name: "Save edits" }));
    await waitFor(() => expect(api.updateResumeVersion).toHaveBeenCalledWith("resume-1", expect.objectContaining({ summary: "Edited truthful summary", skillOrder: ["Java", "Docker"] })));
    fireEvent.click(screen.getByRole("button", { name: "Export DOCX" }));
    expect(api.exportResumeVersion).toHaveBeenCalledWith("resume-1", "tailored-resume-v1.docx");
  });

  it("generates, edits, saves, and copies a separate cover-letter version", async () => {
    api.createCoverLetter.mockResolvedValue(letter); api.updateCoverLetter.mockResolvedValue({ ...letter, content: "Edited draft" });
    Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText: vi.fn() } });
    renderPage(); await screen.findByText("Backend Engineer");
    fireEvent.click(screen.getByRole("button", { name: "Cover Letter" }));
    fireEvent.click(screen.getByRole("button", { name: "Generate draft" }));
    expect(await screen.findByLabelText("Cover letter draft")).toHaveValue(letter.content);
    fireEvent.change(screen.getByLabelText("Cover letter draft"), { target: { value: "Edited draft" } });
    fireEvent.click(screen.getByRole("button", { name: "Save draft" }));
    await waitFor(() => expect(api.updateCoverLetter).toHaveBeenCalledWith("letter-1", { title: "Cover letter", content: "Edited draft" }));
    fireEvent.click(screen.getByRole("button", { name: "Copy content" }));
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith("Edited draft");
  });

  it("tracks personal stage, private notes, external applications, and follow-up separately", async () => {
    api.getWorkspaceJob.mockResolvedValue(workspace); api.updateWorkspaceJob.mockResolvedValue({ ...workspace, stage: "APPLIED", appliedExternally: true });
    renderPage(); await screen.findByText("Backend Engineer");
    fireEvent.click(screen.getByRole("button", { name: "Tracking" }));
    fireEvent.change(screen.getByLabelText("Personal stage"), { target: { value: "APPLIED" } });
    fireEvent.change(screen.getByLabelText("Private application notes"), { target: { value: "Follow up with hiring team" } });
    fireEvent.change(screen.getByLabelText("Follow-up date"), { target: { value: "2026-09-10T10:00" } });
    fireEvent.click(screen.getByLabelText("Applied externally"));
    fireEvent.click(screen.getByRole("button", { name: "Save tracking" }));
    await waitFor(() => expect(api.updateWorkspaceJob).toHaveBeenCalledWith("job-1", expect.objectContaining({ stage: "APPLIED", notes: "Follow up with hiring team", appliedExternally: true })));
  });

  it("renders recoverable load failures", async () => {
    api.getApplicationReadiness.mockRejectedValue(new Error("network"));
    renderPage();
    expect(await screen.findByText("Application Copilot unavailable")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Retry" })).toBeInTheDocument();
  });
});
