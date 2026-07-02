import apiClient from "./helper";


export const applyForJob = async (jobId, file) => {
  const formData = new FormData();
  formData.append("file", file);
  formData.append("jobId", jobId);

  const response = await apiClient.post("/applications/apply", formData, {
    headers: {
      "Content-Type": "multipart/form-data",
    },
  });
  
  return response.data;
};
export const getApplicationsForJob = async (jobId) => {
  const response = await apiClient.get(`/applications/${jobId}`);
  return response.data;
};

export const downloadResume = async (applicationId) => {
  const response = await apiClient.get(`/applications/download/${applicationId}`, {
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
  const response = await apiClient.get(`/applications/status/${jobId}`);
  return response.data; 
};

// Candidate fetches their own applications with job details
export const getMyApplications = async () => {
  const response = await apiClient.get("/applications/my");
  return response.data;
};

// Recruiter updates status of an application
export const updateApplicationStatus = async (applicationId, status) => {
  const response = await apiClient.patch(`/applications/${applicationId}/status`, { status });
  return response.data;
};