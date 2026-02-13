import axios from "axios";
import { logout } from "./user-service";

const BaseURL = "http://localhost:8080";

export const apiClient = axios.create({
  baseURL: BaseURL,
  headers: {
    "Content-Type": "application/json",
  },
});

apiClient.interceptors.request.use((config) => {
    const token = localStorage.getItem("token");
    if (token) config.headers.Authorization = `Bearer ${token}`;
    return config;
}, (error) => Promise.reject(error));

apiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response && error.response.status === 401) {
      logout(); 
    }
    return Promise.reject(error);
  }
);

export default apiClient;