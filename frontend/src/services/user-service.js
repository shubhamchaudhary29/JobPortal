import apiClient from "./helper";

const decodeRoleFromToken = (token) => {
  try {
    if (!token) return "";
    const parts = token.split('.');
    if (parts.length !== 3) return "";
    let base64Url = parts[1];
    let base64 = base64Url.replace(/-/g, '+').replace(/_/g, '/');
    
    // Add padding if required for atob
    while (base64.length % 4) {
      base64 += '=';
    }

    const jsonPayload = decodeURIComponent(
      atob(base64)
        .split('')
        .map(c => '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2))
        .join('')
    );
    const payload = JSON.parse(jsonPayload);
    return payload.role || "";
  } catch (error) {
    console.error("Error decoding token:", error);
    return "";
  }
};

export const loginUser = async (email, password) => {
  const response = await apiClient.post("/auth/login", { 
    email, 
    password 
  });

  if (response.data.token) {
    localStorage.setItem("token", response.data.token);
    const role = decodeRoleFromToken(response.data.token);
    localStorage.setItem("role", role);
  }

  return response.data;
};

export const logout = () => {
  localStorage.removeItem("token");
  localStorage.removeItem("role");
  window.location.href = "/login";
};

export const signUpUser = async (userData) => {
  const response = await apiClient.post("/auth/register", userData);
  return response.data;
};

// Get current user profile with application stats
export const getMyProfile = async () => {
  const response = await apiClient.get("/users/me");
  return response.data;
};

// Update current user's editable profile fields
export const updateMyProfile = async (profileData) => {
  const response = await apiClient.put("/users/me", profileData);
  return response.data;
};