import { beforeEach, describe, expect, it, vi } from "vitest";

const { apiPost, apiDelete, refreshAccessToken, broadcastLogout } = vi.hoisted(() => ({
  apiPost: vi.fn(),
  apiDelete: vi.fn(),
  refreshAccessToken: vi.fn(),
  broadcastLogout: vi.fn(),
}));

vi.mock("./helper", () => ({
  default: { post: apiPost, delete: apiDelete, get: vi.fn(), put: vi.fn() },
  refreshAccessToken,
  broadcastLogout,
  isSessionRejected: (error) => [401, 403].includes(error?.response?.status),
}));

const httpError = (status) => Object.assign(new Error(`HTTP ${status}`), { response: { status } });

describe("session lifecycle", () => {
  beforeEach(() => {
    vi.resetModules();
    apiPost.mockReset();
    apiDelete.mockReset();
    refreshAccessToken.mockReset();
    broadcastLogout.mockReset();
  });

  it("restores a page-reload session through the refresh cookie endpoint", async () => {
    refreshAccessToken.mockResolvedValue("restored-token");
    const { restoreSession } = await import("./user-service");
    await restoreSession();
    expect(refreshAccessToken).toHaveBeenCalledTimes(1);
  });

  it("always clears memory state when logout completes or fails", async () => {
    apiDelete.mockRejectedValue(new Error("network unavailable"));
    const { logout } = await import("./user-service");
    const { getSession, setAuthSession } = await import("../auth/auth-store");
    setAuthSession({ accessToken: "access", role: "USER", email: "user@example.test" });
    await expect(logout()).rejects.toThrow("network unavailable");
    expect(apiDelete).toHaveBeenCalledWith("/api/v1/auth/sessions/current");
    expect(getSession().accessToken).toBeNull();
    expect(broadcastLogout).toHaveBeenCalledTimes(1);
  });

  it("clears the session when restoring is rejected with 401 or 403", async () => {
    refreshAccessToken.mockRejectedValue(httpError(401));
    const { restoreSession } = await import("./user-service");
    await expect(restoreSession()).resolves.toMatchObject({ accessToken: null, initialized: true, unknown: false });
  });

  it("marks the session unknown, not logged out, on a transient restore failure, and Retry restores it", async () => {
    refreshAccessToken.mockRejectedValueOnce(httpError(429));
    const { restoreSession } = await import("./user-service");
    const { setAuthSession } = await import("../auth/auth-store");
    await expect(restoreSession()).resolves.toMatchObject({ accessToken: null, initialized: true, unknown: true });

    refreshAccessToken.mockImplementationOnce(async () => {
      setAuthSession({ accessToken: "restored", role: "USER", email: "user@example.test" });
      return "restored";
    });
    await expect(restoreSession()).resolves.toMatchObject({ accessToken: "restored", unknown: false });
  });
});
