import { beforeEach, describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => ({ get: vi.fn(), put: vi.fn(), post: vi.fn(), delete: vi.fn() }));
vi.mock("./helper", () => ({ default: api }));

import {
  candidateProfileError, deleteCandidateResume, getCandidateProfile, reparseCandidateResume,
  updateCandidateProfile, uploadCandidateResume,
} from "./candidate-profile-service";

describe("candidate profile API", () => {
  beforeEach(() => Object.values(api).forEach((mock) => mock.mockReset()));

  it("uses only self-service candidate routes", async () => {
    api.get.mockResolvedValue({ data: { fullName: "Candidate" } });
    api.put.mockResolvedValue({ data: { fullName: "Updated" } });
    api.post.mockResolvedValue({ data: { resume: { parsingStatus: "PARSED" } } });
    api.delete.mockResolvedValue({});
    await expect(getCandidateProfile()).resolves.toMatchObject({ fullName: "Candidate" });
    await expect(updateCandidateProfile({ fullName: "Updated" })).resolves.toMatchObject({ fullName: "Updated" });
    await expect(reparseCandidateResume()).resolves.toMatchObject({ resume: { parsingStatus: "PARSED" } });
    await deleteCandidateResume();
    expect(api.get).toHaveBeenCalledWith("/api/v1/candidate-profile");
    expect(api.put).toHaveBeenCalledWith("/api/v1/candidate-profile", { fullName: "Updated" });
    expect(api.post).toHaveBeenCalledWith("/api/v1/candidate-profile/resume/reparse");
    expect(api.delete).toHaveBeenCalledWith("/api/v1/candidate-profile/resume");
  });

  it("uploads multipart data and reports progress", async () => {
    api.post.mockImplementation((_url, _form, config) => {
      config.onUploadProgress({ loaded: 50, total: 100 });
      return Promise.resolve({ data: { ok: true } });
    });
    const progress = vi.fn();
    const file = new File(["resume"], "resume.pdf", { type: "application/pdf" });
    await uploadCandidateResume(file, progress);
    const [url, form, config] = api.post.mock.calls[0];
    expect(url).toBe("/api/v1/candidate-profile/resume");
    expect(form.get("file")).toBe(file);
    expect(config.headers).toEqual({ "Content-Type": "multipart/form-data" });
    expect(progress).toHaveBeenCalledWith(50);
  });

  it("maps safe validation, file, parsing, role, and session errors", () => {
    expect(candidateProfileError({ response: { status: 413 } })).toContain("5 MB");
    expect(candidateProfileError({ response: { status: 415, data: { detail: "PDF or DOCX only" } } })).toBe("PDF or DOCX only");
    expect(candidateProfileError({ response: { status: 422, data: { detail: "Corrupted" } } })).toBe("Corrupted");
    expect(candidateProfileError({ response: { status: 403 } })).toContain("Candidate access");
    expect(candidateProfileError({ response: { status: 401 } })).toContain("session expired");
  });
});
