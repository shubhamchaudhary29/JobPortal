import apiClient from "./helper";
import { apiRoutes } from "./api-routes";


export const applyForJob = async (jobId, file) => {
  const formData = new FormData();
  formData.append("file", file);
  const response = await apiClient.post(apiRoutes.jobs.applications(jobId), formData, {
    headers: {
      "Content-Type": "multipart/form-data",
    },
  });
  
  return response.data;
};
export const getApplicationsForJob = async (jobId) => {
  const response = await apiClient.get(apiRoutes.jobs.applications(jobId));
  return response.data.content;
};

export const downloadResume = async (applicationId) => {
  const response = await apiClient.get(apiRoutes.applications.resume(applicationId), {
    responseType: "blob",
  });

  // Create a temporary download link
  const blob = new Blob([response.data], { type: "application/pdf" });
  const url = window.URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = `resume_${applicationId}.pdf`;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  window.URL.revokeObjectURL(url);
};

export const hasUserApplied = async (jobId) => {
  const response = await apiClient.get(apiRoutes.jobs.applicationStatus(jobId));
  return response.data.applied;
};

// Candidate fetches their own applications with job details
export const getMyApplications = async () => {
  const response = await apiClient.get(apiRoutes.applications.collection);
  return response.data.content;
};

// Recruiter updates status of an application
export const updateApplicationStatus = async (applicationId, status) => {
  const response = await apiClient.patch(apiRoutes.applications.status(applicationId), { status });
  return response.data;
};
