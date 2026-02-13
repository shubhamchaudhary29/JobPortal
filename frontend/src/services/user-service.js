import apiClient from "./helper";

export const loginUser = async (email, password) => {
  const response = await apiClient.post("/auth/login", { 
    email, 
    password 
  });

  if (response.data.token) {
    localStorage.setItem("token", response.data.token);
  }

  return response.data;
};

export const logout = () => {
  localStorage.removeItem("token");
  window.location.href = "/login";
};

export const signUpUser = async (userData) => {
  const response = await apiClient.post("/auth/register", userData);
  return response.data;
};