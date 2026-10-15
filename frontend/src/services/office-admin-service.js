import apiClient from "./helper";
import { apiRoutes } from "./api-routes";

export const listOffices = async (params = {}) =>
  (await apiClient.get(apiRoutes.adminOffices.collection, { params })).data;

export const getOfficeCandidates = async (limit = 50) =>
  (await apiClient.get(apiRoutes.adminOffices.candidates, { params: { limit } })).data;

export const createOffice = async (office) =>
  (await apiClient.post(apiRoutes.adminOffices.collection, office)).data;

export const updateOffice = async (id, changes) =>
  (await apiClient.patch(apiRoutes.adminOffices.byId(id), changes)).data;

export const getOfficeAudit = async (id, params = { page: 0, size: 20 }) =>
  (await apiClient.get(apiRoutes.adminOffices.audit(id), { params })).data;

/** The server's 400 message ("Pin is far from Bengaluru …") is safe and specific, so it is shown as-is. */
export const officeError = (error, fallback = "The office request failed.") => {
  const status = error?.response?.status;
  const problem = error?.response?.data;
  if (status === 400 && typeof problem?.detail === "string" && problem.detail) return problem.detail;
  if (status === 400) return "Check the company, label and pin and try again.";
  if (status === 401) return "Your session has expired. Sign in again.";
  if (status === 403) return "Administrator access is required.";
  if (status === 404) return "That office no longer exists.";
  return fallback;
};
