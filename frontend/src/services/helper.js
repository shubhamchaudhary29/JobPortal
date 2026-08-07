import axios from "axios";
import { clearAuthSession, getAccessToken, setAuthSession } from "../auth/auth-store";
import { API_V1, apiRoutes } from "./api-routes";

const BaseURL = import.meta.env.VITE_API_BASE_URL || "";
export const apiClient = axios.create({ baseURL: BaseURL, withCredentials: true });
const authClient = axios.create({ baseURL: BaseURL, withCredentials: true });
let refreshPromise = null;

export const refreshAccessToken = () => {
  if (!refreshPromise) {
    refreshPromise = authClient.post(apiRoutes.auth.refreshSession)
      .then(({ data }) => { setAuthSession(data); return data.accessToken; })
      .catch((error) => { clearAuthSession(); throw error; })
      .finally(() => { refreshPromise = null; });
  }
  return refreshPromise;
};

apiClient.interceptors.request.use((config) => {
  const token = getAccessToken();
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

apiClient.interceptors.response.use(
  (response) => response,
  async (error) => {
    const original = error.config;
    const isAuthEndpoint = original?.url?.startsWith(`${API_V1}/auth/`);
    if (error.response?.status === 401 && original && !original._retried && !isAuthEndpoint) {
      original._retried = true;
      const token = await refreshAccessToken();
      original.headers.Authorization = `Bearer ${token}`;
      return apiClient(original);
    }
    return Promise.reject(error);
  }
);

export default apiClient;

export const safeApiMessage = (error, fallback) => {
  const problem = error?.response?.data;
  if (error?.response?.status === 400 && problem?.code === "VALIDATION_ERROR") {
    return problem.detail || fallback;
  }
  return fallback;
};
