import apiClient from "./helper";
import { apiRoutes } from "./api-routes";

export const getAllJobs = async (params = {}) => {
  const response = await apiClient.get(apiRoutes.jobs.collection, { params });
  return response.data;
};

export const getJobById = async (jobId) => {
  const response = await apiClient.get(apiRoutes.jobs.byId(jobId));
  return response.data;
};

export const getMatchedJobs = async (params = {}) => {
  const response = await apiClient.get(apiRoutes.jobs.matched, { params });
  return response.data;
};

export const getJobMatch = async (jobId) => {
  const response = await apiClient.get(apiRoutes.jobs.match(jobId));
  return response.data;
};

export const createJob = async (jobData) => {
  const response = await apiClient.post(apiRoutes.jobs.collection, jobData);
  return response.data;
};

export const getMyJobs = async (params = {}) => {
  const response = await apiClient.get(apiRoutes.jobs.mine, { params });
  return response.data.content;
};
