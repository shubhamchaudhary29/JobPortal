import apiClient from "./helper";

export const getAllJobs = async () => {
  const response = await apiClient.get("/jobs");
  return response.data;
};

export const getJobById = async (jobId) => {
  const response = await apiClient.get(`/jobs/${jobId}`);
  return response.data;
};

export const createJob = async (jobData) => {
  const response = await apiClient.post("/jobs/create", jobData);
  return response.data;
};

export const getMyJobs = async () => {
  const response = await apiClient.get("/jobs/myjobs");
  return response.data;
};