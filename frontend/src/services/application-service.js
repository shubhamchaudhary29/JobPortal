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

export const getResumeUrl = (applicationId) => {
  return `http://localhost:8080/applications/download/${applicationId}`;
};

export const hasUserApplied = async (jobId) => {
  const response = await apiClient.get(`/applications/status/${jobId}`);
  return response.data; 
};