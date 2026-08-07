import { beforeEach, describe, expect, it } from "vitest";
import { clearAuthSession, getAccessToken, getSession, setAuthSession } from "./auth-store";

describe("in-memory authentication state", () => {
  beforeEach(() => clearAuthSession());

  it("stores access state only in module memory", () => {
    setAuthSession({ accessToken: "access", role: "USER", email: "candidate@example.test" });
    expect(getAccessToken()).toBe("access");
    expect(getSession()).toMatchObject({ role: "USER", initialized: true });
  });

  it("clears all authentication state on logout or refresh failure", () => {
    setAuthSession({ accessToken: "access", role: "RECRUITER", email: "recruiter@example.test" });
    clearAuthSession();
    expect(getSession()).toEqual({ accessToken: null, role: null, email: null, initialized: true });
  });
});
