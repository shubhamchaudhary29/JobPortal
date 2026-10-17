import axios from "axios";
import { clearAuthSession, getAccessToken, setAuthSession } from "../auth/auth-store";
import { API_V1, apiRoutes } from "./api-routes";

const BaseURL = import.meta.env.VITE_API_BASE_URL || "";
export const apiClient = axios.create({ baseURL: BaseURL, withCredentials: true });
const authClient = axios.create({ baseURL: BaseURL, withCredentials: true });

// Refresh coordination across tabs (GEO-7F). Every tab refreshes on load with the same HttpOnly cookie, and the server
// rotates that cookie on each refresh. A Web Lock makes tabs refresh one at a time, so a waiting tab sends the cookie
// the previous tab received; a BroadcastChannel hands the new access token to waiting tabs so they skip the request.
// The access token stays in memory only: never localStorage or sessionStorage.
export const REFRESH_LOCK = "jobportal-auth-refresh";
export const AUTH_CHANNEL = "jobportal-auth";
const MAX_RATE_LIMIT_RETRIES = 2;
// The server rotates the refresh cookie on every refresh. If the page unloads mid-request (a reload, or a click while
// the page is still loading), a normal request is cancelled and the browser drops the response with the new cookie,
// leaving only the already-rotated one: the user is logged out. A keepalive fetch outlives the page, so the browser
// still stores the new cookie.
const REFRESH_REQUEST = { adapter: "fetch", fetchOptions: { keepalive: true } };
// A tab that unloads mid-refresh releases the lock before its keepalive response has stored the new cookie, so the
// next tab can still send the rotated one (401, within the server's reuse grace, nothing revoked). A tab that had to
// wait for another tab's lock and got no broadcast retries such a 401 once after this delay.
const INTERRUPTED_REFRESH_RETRY_MS = 1000;
const DEFAULT_RETRY_AFTER_SECONDS = 2;

let refreshPromise = null;
// Incremented whenever another tab hands us a session, so a caller that waited for the lock can tell it is done.
let receivedSessions = 0;

const channel = typeof BroadcastChannel === "function" ? new BroadcastChannel(AUTH_CHANNEL) : null;
channel?.addEventListener("message", ({ data }) => {
  if (data?.type === "refreshed" && data.session?.accessToken) {
    receivedSessions += 1;
    setAuthSession(data.session);
  } else if (data?.type === "logout") {
    clearAuthSession();
  }
});

const broadcast = (message) => {
  try { channel?.postMessage(message); } catch { /* a closed channel only affects other tabs */ }
};

/** Tells other tabs this one logged out, so they clear their in-memory session too. */
export const broadcastLogout = () => broadcast({ type: "logout" });

/** 401/403 on refresh: the session is really gone. Anything else (429, network, 5xx) is transient. */
export const isSessionRejected = (error) => [401, 403].includes(error?.response?.status);

const retryAfterMs = (error) => {
  const seconds = Number.parseInt(error?.response?.headers?.["retry-after"], 10);
  const value = Number.isFinite(seconds) ? seconds : DEFAULT_RETRY_AFTER_SECONDS;
  return Math.min(10, Math.max(1, value)) * 1000;
};

const sleep = (ms) => new Promise((resolve) => { setTimeout(resolve, ms); });

async function postRefresh() {
  for (let attempt = 0; ; attempt += 1) {
    try {
      const { data } = await authClient.post(apiRoutes.auth.refreshSession, undefined, REFRESH_REQUEST);
      return data;
    } catch (error) {
      if (error?.response?.status !== 429 || attempt >= MAX_RATE_LIMIT_RETRIES) throw error;
      await sleep(retryAfterMs(error));
    }
  }
}

const receivedSince = (receivedBefore) => receivedSessions !== receivedBefore && getAccessToken();

async function refreshOnce(receivedBefore, waitedForAnotherTab) {
  // Another tab refreshed while we waited for the lock: use its token, and keep our (now stale) cookie unused.
  if (receivedSince(receivedBefore)) return getAccessToken();
  let data;
  try {
    data = await postRefresh();
  } catch (error) {
    if (!waitedForAnotherTab || error?.response?.status !== 401) throw error;
    await sleep(INTERRUPTED_REFRESH_RETRY_MS);
    if (receivedSince(receivedBefore)) return getAccessToken();
    data = await postRefresh();
  }
  const session = { accessToken: data.accessToken, role: data.role, email: data.email };
  setAuthSession(session);
  broadcast({ type: "refreshed", session });
  return session.accessToken;
}

/** Runs the refresh under the cross-tab lock, noting whether another tab held it first. */
function withRefreshLock(receivedBefore) {
  const locks = typeof navigator !== "undefined" ? navigator.locks : undefined;
  if (!locks?.request) return refreshOnce(receivedBefore, false);
  return locks.request(REFRESH_LOCK, { ifAvailable: true }, (lock) => (lock
    ? refreshOnce(receivedBefore, false)
    : locks.request(REFRESH_LOCK, () => refreshOnce(receivedBefore, true))));
}

export const refreshAccessToken = () => {
  if (!refreshPromise) {
    refreshPromise = withRefreshLock(receivedSessions)
      .catch((error) => {
        if (isSessionRejected(error)) clearAuthSession();
        throw error;
      })
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
      let token;
      try {
        token = await refreshAccessToken();
      } catch (refreshError) {
        // Session gone: surface the original 401. Otherwise (429, network, 5xx) surface the refresh error so the UI
        // can explain it (safeApiMessage, map "rateLimited") instead of looking like a silent logout.
        return Promise.reject(isSessionRejected(refreshError) ? error : refreshError);
      }
      original.headers.Authorization = `Bearer ${token}`;
      return apiClient(original);
    }
    return Promise.reject(error);
  }
);

export default apiClient;

export const RATE_LIMIT_MESSAGE = "Too many requests, slow down and try again shortly.";

/** A 429 from nginx's limit_req or the backend's login limiter (both send a ProblemDetail body). */
export const isRateLimited = (error) => error?.response?.status === 429;

export const safeApiMessage = (error, fallback) => {
  const problem = error?.response?.data;
  if (isRateLimited(error)) return RATE_LIMIT_MESSAGE;
  if (error?.response?.status === 400 && problem?.code === "VALIDATION_ERROR") {
    return problem.detail || fallback;
  }
  return fallback;
};
