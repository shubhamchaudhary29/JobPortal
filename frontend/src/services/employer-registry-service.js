import apiClient from "./helper";
import { apiRoutes } from "./api-routes";

export const listRegistry = async (params = {}) =>
  (await apiClient.get(apiRoutes.adminAggregation.registry, { params })).data;

export const createRegistryEntry = async (entry) =>
  (await apiClient.post(apiRoutes.adminAggregation.registry, entry)).data;

export const updateRegistryEntry = async (id, changes) =>
  (await apiClient.patch(apiRoutes.adminAggregation.registryEntry(id), changes)).data;

export const validateRegistryEntry = async (id) =>
  (await apiClient.post(apiRoutes.adminAggregation.registryValidation(id))).data;

export const getRegistryAudit = async (id, params = { page: 0, size: 20 }) =>
  (await apiClient.get(apiRoutes.adminAggregation.registryAudit(id), { params })).data;

export const registryError = (error, fallback = "The registry request failed.") => {
  const status = error?.response?.status;
  if (status === 401) return "Your session has expired. Sign in again.";
  if (status === 403) return "Administrator access is required.";
  if (status === 409) return "That board already exists, or the company already has an enabled board.";
  if (status === 404) return "That registry entry no longer exists.";
  if (status === 400) return "Check the provider, board ID, and company and try again.";
  return fallback;
};
