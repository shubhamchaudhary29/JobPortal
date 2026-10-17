// Cross-tab refresh coordination and transient-error handling (GEO-7F). Each test loads a fresh helper module with
// its own mocked axios clients, navigator.locks and BroadcastChannel.
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const { authPost, apiInstance } = vi.hoisted(() => {
  const instance = vi.fn();
  instance.interceptors = { request: { use: vi.fn() }, response: { use: vi.fn() } };
  return { authPost: vi.fn(), apiInstance: instance };
});

vi.mock("axios", () => {
  let calls = 0;
  return { default: { create: vi.fn(() => (calls++ % 2 === 0 ? apiInstance : { post: authPost })) } };
});

/** In-memory BroadcastChannel: delivers to every other open channel with the same name, like the browser. */
class FakeChannel {
  static open = [];
  constructor(name) { this.name = name; this.listeners = []; FakeChannel.open.push(this); }
  addEventListener(type, listener) { if (type === "message") this.listeners.push(listener); }
  postMessage(data) {
    FakeChannel.open.filter((other) => other !== this && other.name === this.name)
      .forEach((other) => other.listeners.forEach((listener) => listener({ data })));
  }
}

/** Exclusive Web Locks: callbacks for one name run one after another; ifAvailable gets null while it is held. */
function fakeLocks() {
  const tails = new Map();
  const held = new Map();
  return {
    request: vi.fn((name, options, maybeCallback) => {
      const callback = maybeCallback || options;
      if (maybeCallback && options?.ifAvailable && held.get(name)) return Promise.resolve().then(() => callback(null));
      const previous = tails.get(name) || Promise.resolve();
      const result = previous.then(async () => {
        held.set(name, true);
        try { return await callback({ name }); } finally { held.set(name, false); }
      });
      tails.set(name, result.catch(() => {}));
      return result;
    }),
  };
}

const session = (token) => ({ accessToken: token, role: "USER", email: "user@example.test" });
const ok = (token) => ({ data: session(token) });
const httpError = (status, headers = {}) => Object.assign(new Error(`HTTP ${status}`), { response: { status, headers } });

async function load({ locks = fakeLocks() } = {}) {
  vi.resetModules();
  vi.stubGlobal("navigator", locks ? { locks } : {});
  vi.stubGlobal("BroadcastChannel", FakeChannel);
  const helper = await import("./helper");
  const store = await import("../auth/auth-store");
  return { helper, store, locks };
}

describe("refreshAccessToken across tabs", () => {
  let setItem;

  beforeEach(() => {
    FakeChannel.open = [];
    authPost.mockReset();
    apiInstance.mockReset();
    apiInstance.interceptors.response.use.mockReset();
    setItem = vi.fn();
    vi.stubGlobal("localStorage", { setItem, getItem: vi.fn(), removeItem: vi.fn() });
    vi.stubGlobal("sessionStorage", { setItem, getItem: vi.fn(), removeItem: vi.fn() });
  });

  afterEach(() => {
    // No auth path may ever write the token to web storage.
    expect(setItem).not.toHaveBeenCalled();
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it("waits Retry-After on a 429, retries and keeps the session", async () => {
    vi.useFakeTimers();
    const { helper, store } = await load();
    store.setAuthSession(session("old"));
    authPost.mockRejectedValueOnce(httpError(429, { "retry-after": "3" })).mockResolvedValueOnce(ok("new"));

    const pending = helper.refreshAccessToken();
    await vi.advanceTimersByTimeAsync(2999);
    expect(authPost).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    await expect(pending).resolves.toBe("new");
    expect(authPost).toHaveBeenCalledTimes(2);
    expect(store.getAccessToken()).toBe("new");
  });

  it("gives up after two 429 retries without clearing the session", async () => {
    vi.useFakeTimers();
    const { helper, store } = await load();
    store.setAuthSession(session("old"));
    authPost.mockRejectedValue(httpError(429, { "retry-after": "60" }));

    const pending = helper.refreshAccessToken();
    const outcome = expect(pending).rejects.toMatchObject({ response: { status: 429 } });
    await vi.advanceTimersByTimeAsync(20_000); // Retry-After is clamped to 10 s
    await outcome;
    expect(authPost).toHaveBeenCalledTimes(3);
    expect(store.getAccessToken()).toBe("old");
  });

  it("clears the session on 401", async () => {
    const { helper, store } = await load();
    store.setAuthSession(session("old"));
    authPost.mockRejectedValue(httpError(401));
    await expect(helper.refreshAccessToken()).rejects.toMatchObject({ response: { status: 401 } });
    expect(store.getSession()).toMatchObject({ accessToken: null, initialized: true });
  });

  it("keeps the session on a network error or 5xx", async () => {
    const { helper, store } = await load();
    store.setAuthSession(session("old"));
    authPost.mockRejectedValueOnce(Object.assign(new Error("Network Error"), { code: "ERR_NETWORK" }));
    await expect(helper.refreshAccessToken()).rejects.toThrow("Network Error");
    authPost.mockRejectedValueOnce(httpError(502));
    await expect(helper.refreshAccessToken()).rejects.toMatchObject({ response: { status: 502 } });
    expect(store.getAccessToken()).toBe("old");
  });

  it("sends one request for concurrent calls in one tab", async () => {
    const { helper } = await load();
    authPost.mockResolvedValue(ok("new"));
    const [first, second] = await Promise.all([helper.refreshAccessToken(), helper.refreshAccessToken()]);
    expect([first, second]).toEqual(["new", "new"]);
    expect(authPost).toHaveBeenCalledTimes(1);
  });

  it("deduplicates in one tab without Web Locks too", async () => {
    const { helper } = await load({ locks: null });
    authPost.mockResolvedValue(ok("new"));
    await Promise.all([helper.refreshAccessToken(), helper.refreshAccessToken()]);
    expect(authPost).toHaveBeenCalledTimes(1);
  });

  it("uses a token another tab broadcast while this tab waited for the lock", async () => {
    const { helper, store, locks } = await load();
    const otherTab = new FakeChannel("jobportal-auth");
    let releaseOtherTab;
    // The other tab holds the refresh lock...
    locks.request("jobportal-auth-refresh", () => new Promise((resolve) => { releaseOtherTab = resolve; }));
    const pending = helper.refreshAccessToken();
    // ...refreshes, broadcasts its new token, then releases the lock.
    await vi.waitFor(() => expect(releaseOtherTab).toBeTypeOf("function"));
    otherTab.postMessage({ type: "refreshed", session: session("from-other-tab") });
    releaseOtherTab();

    await expect(pending).resolves.toBe("from-other-tab");
    expect(authPost).not.toHaveBeenCalled();
    expect(store.getAccessToken()).toBe("from-other-tab");
  });

  it("retries a 401 once after waiting for a tab that unloaded mid-refresh", async () => {
    vi.useFakeTimers();
    const { helper, store, locks } = await load();
    let releaseOtherTab;
    locks.request("jobportal-auth-refresh", () => new Promise((resolve) => { releaseOtherTab = resolve; }));
    authPost.mockRejectedValueOnce(httpError(401)).mockResolvedValueOnce(ok("rotated-cookie-landed"));
    await vi.waitFor(() => expect(releaseOtherTab).toBeTypeOf("function")); // the other tab holds the lock
    const pending = helper.refreshAccessToken();
    releaseOtherTab(); // the other tab unloads: lock released, no broadcast

    await vi.advanceTimersByTimeAsync(1000);
    await expect(pending).resolves.toBe("rotated-cookie-landed");
    expect(authPost).toHaveBeenCalledTimes(2);
    expect(store.getAccessToken()).toBe("rotated-cookie-landed");
  });

  it("does not retry a 401 when no other tab held the lock", async () => {
    const { helper } = await load();
    authPost.mockRejectedValue(httpError(401));
    await expect(helper.refreshAccessToken()).rejects.toMatchObject({ response: { status: 401 } });
    expect(authPost).toHaveBeenCalledTimes(1);
  });

  it("broadcasts a refreshed token to other tabs", async () => {
    const { helper } = await load();
    const otherTab = new FakeChannel("jobportal-auth");
    const received = [];
    otherTab.addEventListener("message", ({ data }) => received.push(data));
    authPost.mockResolvedValue(ok("new"));
    await helper.refreshAccessToken();
    expect(received).toEqual([{ type: "refreshed", session: session("new") }]);
  });

  it("clears this tab's session when another tab logs out", async () => {
    const { store } = await load();
    store.setAuthSession(session("live"));
    new FakeChannel("jobportal-auth").postMessage({ type: "logout" });
    expect(store.getSession()).toMatchObject({ accessToken: null, initialized: true });
  });

  it("rejects the original request with the refresh error when refresh is rate limited", async () => {
    const { helper } = await load();
    const onRejected = apiInstance.interceptors.response.use.mock.calls[0][1];
    const original = { response: { status: 401 }, config: { url: "/api/v1/users/me", headers: {} } };
    const limited = httpError(429, { "retry-after": "1" });
    authPost.mockRejectedValue(limited);
    vi.useFakeTimers();
    const outcome = expect(onRejected(original)).rejects.toBe(limited);
    await vi.advanceTimersByTimeAsync(5_000);
    await outcome;
    expect(helper.safeApiMessage(limited, "fallback")).toBe(helper.RATE_LIMIT_MESSAGE);
  });
});
