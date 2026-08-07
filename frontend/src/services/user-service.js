import apiClient, { refreshAccessToken } from "./helper";
import { clearAuthSession, getSession, setAuthSession } from "../auth/auth-store";

export const loginUser = async (email, password) => {
  const { data } = await apiClient.post("/auth/login", { email, password });
  setAuthSession(data);
  return data;
};

export const restoreSession = async () => {
  if (getSession().initialized) return getSession();
  try { await refreshAccessToken(); } catch { clearAuthSession(); }
  return getSession();
};

export const logout = async () => {
  try { await apiClient.post("/auth/logout"); } finally { clearAuthSession(); }
};

export const signUpUser = async (userData, recruiter = false) => {
  const path = recruiter ? "/auth/register/recruiter" : "/auth/register";
  const { fullName, email, password } = userData;
  const response = await apiClient.post(path, { fullName, email, password });
  return response.data;
};

export const getMyProfile = async () => (await apiClient.get("/users/me")).data;
export const updateMyProfile = async (profileData) => (await apiClient.put("/users/me", profileData)).data;
