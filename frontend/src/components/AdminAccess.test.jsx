// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { AuthContext } from "../auth/auth-context";
import Header from "./Header";
import ProtectedRoute from "./ProtectedRoute";

vi.mock("../services/user-service", () => ({ logout: vi.fn() }));
vi.mock("./UnreadBadge", () => ({ default: () => null }));

const session = (role, token = "token") => ({ accessToken: token, role, email: "actor@test", initialized: true });

function protectedAdmin(value) {
  return render(
    <AuthContext.Provider value={value}>
      <MemoryRouter initialEntries={["/admin/aggregation"]}>
        <Routes>
          <Route path="/admin/aggregation" element={<ProtectedRoute requiredRole="ADMIN"><p>Admin operations</p></ProtectedRoute>} />
          <Route path="/login" element={<p>Login destination</p>} />
          <Route path="/my-profile" element={<p>Candidate destination</p>} />
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

describe("ADMIN route and navigation", () => {
  afterEach(() => cleanup());

  it("redirects unauthenticated and forbidden users but permits ADMIN", () => {
    protectedAdmin(session(null, null));
    expect(screen.getByText("Login destination")).toBeInTheDocument();
    cleanup();
    protectedAdmin(session("USER"));
    expect(screen.getByText("Candidate destination")).toBeInTheDocument();
    cleanup();
    protectedAdmin(session("ADMIN"));
    expect(screen.getByText("Admin operations")).toBeInTheDocument();
  });

  it("shows aggregation navigation only to ADMIN", () => {
    const renderHeader = (value) => render(<AuthContext.Provider value={value}><MemoryRouter><Header /></MemoryRouter></AuthContext.Provider>);
    renderHeader(session("USER"));
    fireEvent.click(screen.getByRole("button"));
    expect(screen.queryByText("Aggregation Operations")).not.toBeInTheDocument();
    cleanup();
    renderHeader(session("ADMIN"));
    fireEvent.click(screen.getByRole("button"));
    expect(screen.getByText(/Aggregation Operations/)).toBeInTheDocument();
  });
});
