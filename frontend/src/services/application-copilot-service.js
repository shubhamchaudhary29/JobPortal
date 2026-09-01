import apiClient from "./helper";
import { apiRoutes } from "./api-routes";

export const getApplicationReadiness = async (jobId) =>
  (await apiClient.get(apiRoutes.jobs.readiness(jobId))).data;

export const getTailoringPlan = async (jobId) =>
  (await apiClient.get(apiRoutes.jobs.tailoringPlan(jobId))).data;

export const createResumeVersion = async (jobId, title) =>
  (await apiClient.post(apiRoutes.jobs.resumeVersions(jobId), title ? { title } : {})).data;

export const getResumeVersions = async (jobId, params = {}) =>
  (await apiClient.get(apiRoutes.jobs.resumeVersions(jobId), { params })).data;

export const updateResumeVersion = async (id, value) =>
  (await apiClient.put(apiRoutes.copilot.resumeVersion(id), value)).data;

export const exportResumeVersion = async (id, filename = "tailored-resume.docx") => {
  const response = await apiClient.get(apiRoutes.copilot.resumeExport(id), { responseType: "blob" });
  const url = URL.createObjectURL(response.data);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
};

export const createCoverLetter = async (jobId, title) =>
  (await apiClient.post(apiRoutes.jobs.coverLetters(jobId), title ? { title } : {})).data;

export const getCoverLetters = async (jobId, params = {}) =>
  (await apiClient.get(apiRoutes.jobs.coverLetters(jobId), { params })).data;

export const updateCoverLetter = async (id, value) =>
  (await apiClient.put(apiRoutes.copilot.coverLetter(id), value)).data;

export const getWorkspace = async (params = {}) =>
  (await apiClient.get(apiRoutes.copilot.workspace, { params })).data;

export const getWorkspaceJob = async (jobId) =>
  (await apiClient.get(apiRoutes.copilot.workspaceJob(jobId))).data;

export const updateWorkspaceJob = async (jobId, value = {}) =>
  (await apiClient.put(apiRoutes.copilot.workspaceJob(jobId), value)).data;

export const removeSavedJob = async (jobId) => apiClient.delete(apiRoutes.copilot.workspaceJob(jobId));

export const getWorkspaceAnalytics = async () =>
  (await apiClient.get(apiRoutes.copilot.analytics)).data;
