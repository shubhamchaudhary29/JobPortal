import apiClient, { broadcastLogout, isSessionRejected, refreshAccessToken } from "./helper";
import { clearAuthSession, getSession, markAuthUnknown, setAuthSession } from "../auth/auth-store";
import { apiRoutes } from "./api-routes";

export const loginUser = async (email, password) => {
  const { data } = await apiClient.post(apiRoutes.auth.sessions, { email, password });
  setAuthSession(data);
  return data;
};

/** Restores the session from the refresh cookie on load; also the Retry for an unconfirmed (unknown) session. */
export const restoreSession = async () => {
  const current = getSession();
  if (current.initialized && !current.unknown) return current;
  try {
    await refreshAccessToken();
  } catch (error) {
    // Only a 401/403 means there is no session. A 429, network error or 5xx must not log the user out.
    if (isSessionRejected(error)) clearAuthSession(); else markAuthUnknown();
  }
  return getSession();
};

export const logout = async () => {
  try {
    await apiClient.delete(apiRoutes.auth.currentSession);
  } finally {
    clearAuthSession();
    broadcastLogout();
  }
};

export const signUpUser = async (userData, recruiter = false) => {
  const path = recruiter ? apiRoutes.auth.recruiterRegistrations : apiRoutes.auth.candidateRegistrations;
  const { fullName, email, password } = userData;
  const response = await apiClient.post(path, { fullName, email, password });
  return response.data;
};

export const getMyProfile = async () => (await apiClient.get(apiRoutes.users.me)).data;
export const updateMyProfile = async (profileData) => (await apiClient.put(apiRoutes.users.me, profileData)).data;
