import { beforeEach, describe, expect, it, vi } from "vitest";
import axios from "axios";
import { clearAuthSession, getSession, setAuthSession } from "../auth/auth-store";

const { apiInstance, authPost, authInstance } = vi.hoisted(() => {
  const instance = vi.fn();
  instance.interceptors = { request: { use: vi.fn() }, response: { use: vi.fn() } };
  const post = vi.fn();
  return { apiInstance: instance, authPost: post, authInstance: { post } };
});

vi.mock("axios", () => ({
  default: { create: vi.fn()
    .mockReturnValueOnce(apiInstance)
    .mockReturnValueOnce(authInstance) },
}));

describe("refresh coordination", () => {
  beforeEach(() => { authPost.mockReset(); apiInstance.mockReset(); clearAuthSession(); });

  it("uses one cookie-based request for concurrent refreshes", async () => {
    authPost.mockResolvedValue({ data: { accessToken: "new", role: "USER", email: "user@example.test" } });
    const { refreshAccessToken } = await import("./helper");
    const [first, second] = await Promise.all([refreshAccessToken(), refreshAccessToken()]);
    expect(first).toBe("new");
    expect(second).toBe("new");
    expect(authPost).toHaveBeenCalledTimes(1);
    expect(authPost).toHaveBeenCalledWith("/auth/refresh");
  });

  it("configures both API clients to send credential cookies", async () => {
    await import("./helper");
    expect(axios.create).toHaveBeenCalledTimes(2);
    expect(axios.create).toHaveBeenNthCalledWith(1, { baseURL: "", withCredentials: true });
    expect(axios.create).toHaveBeenNthCalledWith(2, { baseURL: "", withCredentials: true });
  });

  it("clears memory state when refresh fails", async () => {
    setAuthSession({ accessToken: "old", role: "USER", email: "user@example.test" });
    authPost.mockRejectedValue(new Error("refresh rejected"));
    const { refreshAccessToken } = await import("./helper");
    await expect(refreshAccessToken()).rejects.toThrow("refresh rejected");
    expect(getSession().accessToken).toBeNull();
  });

  it("retries one non-auth request once after a 401", async () => {
    authPost.mockResolvedValue({ data: { accessToken: "rotated", role: "USER", email: "user@example.test" } });
    apiInstance.mockResolvedValue({ data: "ok" });
    await import("./helper");
    const reject401 = apiInstance.interceptors.response.use.mock.calls[0][1];
    const config = { url: "/users/me", headers: {} };
    await expect(reject401({ response: { status: 401 }, config })).resolves.toEqual({ data: "ok" });
    expect(config).toMatchObject({ _retried: true, headers: { Authorization: "Bearer rotated" } });
    expect(apiInstance).toHaveBeenCalledTimes(1);
  });

  it("does not refresh auth endpoints or retry a request twice", async () => {
    await import("./helper");
    const reject401 = apiInstance.interceptors.response.use.mock.calls[0][1];
    const authError = { response: { status: 401 }, config: { url: "/auth/login", headers: {} } };
    const retriedError = { response: { status: 401 }, config: { url: "/users/me", headers: {}, _retried: true } };
    await expect(reject401(authError)).rejects.toBe(authError);
    await expect(reject401(retriedError)).rejects.toBe(retriedError);
    expect(authPost).not.toHaveBeenCalled();
  });
});
