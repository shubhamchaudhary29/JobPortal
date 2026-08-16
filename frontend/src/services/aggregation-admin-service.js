import apiClient from "./helper";
import { apiRoutes } from "./api-routes";

export const getAggregationStatus = async (filters = {}) =>
  (await apiClient.get(apiRoutes.adminAggregation.status, { params: filters })).data;

export const getSyncHistory = async (params = {}) =>
  (await apiClient.get(apiRoutes.adminAggregation.history, { params })).data;

export const getSyncRun = async (runId) =>
  (await apiClient.get(apiRoutes.adminAggregation.historyById(runId))).data;

export const getAggregationConflicts = async (params = {}) =>
  (await apiClient.get(apiRoutes.adminAggregation.conflicts, { params })).data;

export const startAggregationSync = async (provider, employer) => {
  const params = employer?.trim() ? { employer: employer.trim() } : {};
  return (await apiClient.post(apiRoutes.adminAggregation.sync(provider), null, { params })).data;
};

export const resolveAggregationConflict = async (conflictId, canonicalJobId, duplicateJobId) =>
  (await apiClient.post(apiRoutes.adminAggregation.conflictResolution(conflictId), {
    canonicalJobId,
    duplicateJobId,
  })).data;

export const aggregationAdminError = (error, fallback = "The aggregation request failed.") => {
  const status = error?.response?.status;
  const outcome = error?.response?.data?.status;
  if (status === 401) return "Your session has expired. Sign in again.";
  if (status === 403) return "Administrator access is required.";
  if (status === 409 || outcome === "LOCKED") return "A synchronization or reconciliation is already running.";
  if (status === 503 || outcome === "LEASE_LOST") return "The synchronization lost its lease and stopped safely.";
  return fallback;
};
