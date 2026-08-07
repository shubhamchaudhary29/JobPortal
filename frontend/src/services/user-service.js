import apiClient, { refreshAccessToken } from "./helper";
import { clearAuthSession, getSession, setAuthSession } from "../auth/auth-store";
import { apiRoutes } from "./api-routes";

export const loginUser = async (email, password) => {
  const { data } = await apiClient.post(apiRoutes.auth.sessions, { email, password });
  setAuthSession(data);
  return data;
};

export const restoreSession = async () => {
  if (getSession().initialized) return getSession();
  try { await refreshAccessToken(); } catch { clearAuthSession(); }
  return getSession();
};

export const logout = async () => {
  try { await apiClient.delete(apiRoutes.auth.currentSession); } finally { clearAuthSession(); }
};

export const signUpUser = async (userData, recruiter = false) => {
  const path = recruiter ? apiRoutes.auth.recruiterRegistrations : apiRoutes.auth.candidateRegistrations;
  const { fullName, email, password } = userData;
  const response = await apiClient.post(path, { fullName, email, password });
  return response.data;
};

export const getMyProfile = async () => (await apiClient.get(apiRoutes.users.me)).data;
export const updateMyProfile = async (profileData) => (await apiClient.put(apiRoutes.users.me, profileData)).data;
