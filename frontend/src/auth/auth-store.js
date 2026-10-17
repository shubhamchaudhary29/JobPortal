// unknown: the session could not be confirmed (refresh hit a 429, network error or 5xx). The UI treats it as logged
// out, but nothing was revoked and the refresh cookie is still valid, so a later retry can restore it (GEO-7F).
let session = { accessToken: null, role: null, email: null, initialized: false, unknown: false };
const listeners = new Set();

export const getSession = () => session;
export const getAccessToken = () => session.accessToken;
export const subscribeAuth = (listener) => {
  listeners.add(listener);
  return () => listeners.delete(listener);
};

export const setAuthSession = ({ accessToken, role, email }) => {
  session = { accessToken, role, email, initialized: true, unknown: false };
  listeners.forEach((listener) => listener());
};

export const clearAuthSession = () => {
  session = { accessToken: null, role: null, email: null, initialized: true, unknown: false };
  listeners.forEach((listener) => listener());
};

export const markAuthUnknown = () => {
  session = { accessToken: null, role: null, email: null, initialized: true, unknown: true };
  listeners.forEach((listener) => listener());
};
