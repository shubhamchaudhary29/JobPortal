import axios from "axios";
import { clearAuthSession, getAccessToken, setAuthSession } from "../auth/auth-store";

const BaseURL = "";
export const apiClient = axios.create({ baseURL: BaseURL, withCredentials: true });
const authClient = axios.create({ baseURL: BaseURL, withCredentials: true });
let refreshPromise = null;

export const refreshAccessToken = () => {
  if (!refreshPromise) {
    refreshPromise = authClient.post("/auth/refresh")
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
    const isAuthEndpoint = original?.url?.startsWith("/auth/");
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
