import apiClient from "./helper";
import { apiRoutes } from "./api-routes";

export const getCandidateProfile = async () => (await apiClient.get(apiRoutes.candidateProfile.root)).data;
export const updateCandidateProfile = async (profile) => (await apiClient.put(apiRoutes.candidateProfile.root, profile)).data;

export const uploadCandidateResume = async (file, onProgress) => {
  const form = new FormData();
  form.append("file", file);
  const response = await apiClient.post(apiRoutes.candidateProfile.resume, form, {
    headers: { "Content-Type": "multipart/form-data" },
    onUploadProgress: (event) => {
      if (event.total && onProgress) onProgress(Math.round((event.loaded * 100) / event.total));
    },
  });
  return response.data;
};

export const reparseCandidateResume = async () => (await apiClient.post(apiRoutes.candidateProfile.reparse)).data;
export const deleteCandidateResume = async () => apiClient.delete(apiRoutes.candidateProfile.resume);

export const downloadCandidateResume = async (filename = "resume") => {
  const response = await apiClient.get(apiRoutes.candidateProfile.resume, { responseType: "blob" });
  const href = URL.createObjectURL(response.data);
  const anchor = document.createElement("a");
  anchor.href = href; anchor.download = filename; anchor.click();
  URL.revokeObjectURL(href);
};

export const candidateProfileError = (error, fallback = "Request failed. Please try again.") => {
  const status = error?.response?.status;
  const problem = error?.response?.data;
  if (status === 401) return "Your session expired. Sign in again to continue.";
  if (status === 403) return "Candidate access is required for this profile.";
  if (status === 413) return "The resume is larger than the 5 MB limit.";
  if (status === 415) return problem?.detail || "Upload a valid PDF or DOCX resume.";
  if (status === 422) return problem?.detail || "The resume could not be parsed.";
  if (status === 400) return problem?.detail || "Check the highlighted profile values.";
  return fallback;
};
