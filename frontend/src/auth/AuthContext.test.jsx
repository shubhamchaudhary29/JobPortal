// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { act, cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

vi.mock("../services/user-service", () => ({ restoreSession: vi.fn() }));

import { AuthProvider } from "./AuthContext";
import { useEffect } from "react";
import { useAuth } from "./auth-context";
import { clearAuthSession, setAuthSession } from "./auth-store";
import { restoreSession } from "../services/user-service";

function Probe() {
  const { accessToken } = useAuth();
  return <p>{accessToken ? "signed in" : "signed out"}</p>;
}

describe("AuthProvider", () => {
  afterEach(() => { cleanup(); clearAuthSession(); });

  it("shows a session set between the first render and the store subscription", () => {
    // Another tab's broadcast lands while React mounts. Child effects run before the provider's own effects, so this
    // store change happens after the provider's first render but before a useEffect-based subscription would exist.
    function BroadcastDuringMount() {
      useEffect(() => { setAuthSession({ accessToken: "from-other-tab", role: "USER", email: "u@example.test" }); }, []);
      return null;
    }
    restoreSession.mockImplementation(() => {});
    render(<AuthProvider><BroadcastDuringMount /><Probe /></AuthProvider>);
    expect(screen.getByText("signed in")).toBeInTheDocument();
  });

  it("follows later store changes", () => {
    restoreSession.mockImplementation(() => {});
    render(<AuthProvider><Probe /></AuthProvider>);
    act(() => setAuthSession({ accessToken: "t", role: "USER", email: "u@example.test" }));
    expect(screen.getByText("signed in")).toBeInTheDocument();
    act(() => clearAuthSession());
    expect(screen.getByText("signed out")).toBeInTheDocument();
  });
});
