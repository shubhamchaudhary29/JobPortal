import { beforeEach, describe, expect, it, vi } from "vitest";

const { apiPost, refreshAccessToken } = vi.hoisted(() => ({
  apiPost: vi.fn(),
  refreshAccessToken: vi.fn(),
}));

vi.mock("./helper", () => ({
  default: { post: apiPost, get: vi.fn(), put: vi.fn() },
  refreshAccessToken,
}));

describe("session lifecycle", () => {
  beforeEach(() => {
    vi.resetModules();
    apiPost.mockReset();
    refreshAccessToken.mockReset();
  });

  it("restores a page-reload session through the refresh cookie endpoint", async () => {
    refreshAccessToken.mockResolvedValue("restored-token");
    const { restoreSession } = await import("./user-service");
    await restoreSession();
    expect(refreshAccessToken).toHaveBeenCalledTimes(1);
  });

  it("always clears memory state when logout completes or fails", async () => {
    apiPost.mockRejectedValue(new Error("network unavailable"));
    const { logout } = await import("./user-service");
    const { getSession, setAuthSession } = await import("../auth/auth-store");
    setAuthSession({ accessToken: "access", role: "USER", email: "user@example.test" });
    await expect(logout()).rejects.toThrow("network unavailable");
    expect(apiPost).toHaveBeenCalledWith("/auth/logout");
    expect(getSession().accessToken).toBeNull();
  });
});
