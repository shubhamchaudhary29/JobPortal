let session = { accessToken: null, role: null, email: null, initialized: false };
const listeners = new Set();

export const getSession = () => session;
export const getAccessToken = () => session.accessToken;
export const subscribeAuth = (listener) => {
  listeners.add(listener);
  return () => listeners.delete(listener);
};

export const setAuthSession = ({ accessToken, role, email }) => {
  session = { accessToken, role, email, initialized: true };
  listeners.forEach((listener) => listener());
};

export const clearAuthSession = () => {
  session = { accessToken: null, role: null, email: null, initialized: true };
  listeners.forEach((listener) => listener());
};
