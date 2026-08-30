// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => ({
  getCandidateProfile: vi.fn(), updateCandidateProfile: vi.fn(), uploadCandidateResume: vi.fn(),
  reparseCandidateResume: vi.fn(), deleteCandidateResume: vi.fn(), downloadCandidateResume: vi.fn(),
  candidateProfileError: vi.fn(),
}));
vi.mock("../services/candidate-profile-service", () => api);
vi.mock("../components/Header", () => ({ default: () => <div data-testid="header" /> }));
vi.mock("../components/Footer", () => ({ default: () => <div data-testid="footer" /> }));

import MyProfile from "./MyProfile";

const quality = {
  qualityScore: 72, scoreLabel: "Resume Quality Score",
  explanation: "Internal heuristic; not an official ATS score.", strengths: ["Skills are present."],
  issues: [{ severity: "MEDIUM", category: "CONTENT", message: "Descriptions are brief.", recommendation: "Add outcomes." }],
};
const base = {
  userId: "u1", fullName: "Test Candidate", email: "candidate@example.test", phone: "", location: "",
  professionalSummary: "", skills: [], education: [], experience: [], projects: [], certifications: [],
  links: { linkedIn: null, github: null, portfolio: null, website: null, other: [] },
  preferences: { preferredJobTitles: [], preferredLocations: [], remotePreference: null, employmentTypes: [], minimumSalary: null },
  resume: { filename: null, size: 0, parsingStatus: "NOT_UPLOADED", uploadedAt: null },
  parsingWarnings: [], quality,
};

describe("candidate profile page", () => {
  beforeEach(() => {
    Object.values(api).forEach((mock) => mock.mockReset());
    api.candidateProfileError.mockImplementation((error, fallback) => error?.safe || fallback);
    api.getCandidateProfile.mockResolvedValue(base);
    api.updateCandidateProfile.mockImplementation((payload) => Promise.resolve({ ...base, ...payload }));
  });
  afterEach(() => cleanup());

  it("renders loading, empty resume/profile collections, and heuristic quality report", async () => {
    render(<MyProfile />);
    expect(screen.getByRole("status")).toHaveTextContent("Loading candidate profile");
    expect(await screen.findByText("No resume uploaded. Your editable profile is still available below.")).toBeInTheDocument();
    expect(screen.getByText("No skills added yet.")).toBeInTheDocument();
    expect(screen.getByText("No education added yet.")).toBeInTheDocument();
    expect(screen.getByText("Resume Quality Score")).toBeInTheDocument();
    expect(screen.getByText(/not an official ATS score/i)).toBeInTheDocument();
    expect(screen.getByText("Descriptions are brief.")).toBeInTheDocument();
  });

  it("edits profile, adds/removes skills, prevents duplicates, and submits no ownership metadata", async () => {
    render(<MyProfile />); await screen.findByDisplayValue("Test Candidate");
    fireEvent.change(screen.getByLabelText("Phone"), { target: { value: "+91 98765 43210" } });
    const skill = screen.getByLabelText("New skill");
    fireEvent.change(skill, { target: { value: "Java" } }); fireEvent.click(screen.getByRole("button", { name: "Add skill" }));
    fireEvent.change(skill, { target: { value: "java" } }); fireEvent.click(screen.getByRole("button", { name: "Add skill" }));
    expect(screen.getByRole("alert")).toHaveTextContent("already listed");
    expect(screen.getByRole("button", { name: "Remove Java" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Save profile" }));
    await waitFor(() => expect(api.updateCandidateProfile).toHaveBeenCalledTimes(1));
    const payload = api.updateCandidateProfile.mock.calls[0][0];
    expect(payload.phone).toBe("+91 98765 43210");
    expect(payload.skills.map((item) => item.name)).toEqual(["Java"]);
    expect(payload).not.toHaveProperty("userId"); expect(payload).not.toHaveProperty("email"); expect(payload).not.toHaveProperty("resume");
    fireEvent.click(screen.getByRole("button", { name: "Remove Java" }));
    expect(screen.getByText("No skills added yet.")).toBeInTheDocument();
  });

  it("uploads a resume, updates progress state, and renders parsed fields", async () => {
    const parsed = { ...base, skills: [{ name: "Spring Boot", source: "RESUME_PARSER" }],
      resume: { filename: "resume.pdf", size: 2048, parsingStatus: "PARTIALLY_PARSED", uploadedAt: "2026-08-30T10:00:00Z" },
      parsingWarnings: ["Review the detected location."] };
    api.uploadCandidateResume.mockImplementation((_file, onProgress) => { onProgress(60); return Promise.resolve(parsed); });
    render(<MyProfile />); await screen.findByText("No resume uploaded. Your editable profile is still available below.");
    const file = new File(["pdf content"], "resume.pdf", { type: "application/pdf" });
    fireEvent.change(document.querySelector('input[type="file"]'), { target: { files: [file] } });
    expect(await screen.findByText("resume.pdf")).toBeInTheDocument();
    expect(screen.getByText("Spring Boot")).toBeInTheDocument();
    expect(screen.getByText("Review the detected location.")).toBeInTheDocument();
    expect(screen.getByText("Resume uploaded and profile parsing completed.")).toBeInTheDocument();
  });

  it("shows client and server upload failures plus OCR parsing guidance", async () => {
    render(<MyProfile />); await screen.findByText("No resume uploaded. Your editable profile is still available below.");
    const input = document.querySelector('input[type="file"]');
    fireEvent.change(input, { target: { files: [new File(["text"], "resume.txt", { type: "text/plain" })] } });
    expect(screen.getByRole("alert")).toHaveTextContent("Choose a PDF or DOCX");
    api.uploadCandidateResume.mockRejectedValue({ safe: "The PDF is corrupted." });
    fireEvent.change(input, { target: { files: [new File(["pdf"], "resume.pdf", { type: "application/pdf" })] } });
    expect(await screen.findByText("The PDF is corrupted.")).toBeInTheDocument();
    cleanup();

    api.getCandidateProfile.mockResolvedValue({ ...base, resume: { filename: "scan.pdf", size: 1000, parsingStatus: "OCR_REQUIRED" }, parsingWarnings: ["OCR required"] });
    render(<MyProfile />);
    expect(await screen.findByText(/Too little selectable text/)).toBeInTheDocument();
  });

  it("renders load failures with retry and supports privacy-preserving resume deletion", async () => {
    api.getCandidateProfile.mockRejectedValueOnce({ safe: "Network unavailable." }).mockResolvedValueOnce({ ...base,
      resume: { filename: "resume.pdf", size: 1000, parsingStatus: "PARSED" } });
    vi.spyOn(window, "confirm").mockReturnValue(true);
    api.deleteCandidateResume.mockResolvedValue({});
    render(<MyProfile />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Network unavailable");
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    expect(await screen.findByText("resume.pdf")).toBeInTheDocument();
    api.getCandidateProfile.mockResolvedValue(base);
    fireEvent.click(screen.getByRole("button", { name: "Delete resume" }));
    expect(await screen.findByText(/profile data was retained/i)).toBeInTheDocument();
    expect(api.deleteCandidateResume).toHaveBeenCalledTimes(1);
  });
});
