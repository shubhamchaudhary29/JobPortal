// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter, useLocation } from "react-router-dom";
import { AuthContext } from "../auth/auth-context";
import Header from "../components/Header";

// Pages are stubbed: these tests are about which page a URL reaches.
vi.mock("../pages/MapJobs", () => ({ default: () => <p>Map page</p> }));
vi.mock("../pages/OfficeAdmin", () => ({ default: () => <p>Office pins page</p> }));
vi.mock("../pages/JobDetails", () => ({ default: () => <p>Job details page</p> }));
vi.mock("../pages/Jobs", () => ({ default: () => <p>Jobs page</p> }));
vi.mock("../pages/Home", () => ({ default: () => <p>Home page</p> }));
vi.mock("../pages/ChatList", () => ({ default: () => null }));
vi.mock("../pages/ChatRoom", () => ({ default: () => null }));
vi.mock("../services/user-service", () => ({ logout: vi.fn() }));
vi.mock("../components/UnreadBadge", () => ({ default: () => null }));

import AppRoutes from "./appRoute";

function Path() {
  return <output data-testid="path">{useLocation().pathname}</output>;
}

const renderAt = (url) => render(<MemoryRouter initialEntries={[url]}><AppRoutes /><Path /></MemoryRouter>);

describe("map routes", () => {
  afterEach(cleanup);

  it("serves /jobs/map with the map page, not job details", async () => {
    renderAt("/jobs/map");
    expect(await screen.findByText("Map page")).toBeInTheDocument();
    expect(screen.queryByText("Job details page")).not.toBeInTheDocument();
  });

  it("still serves job details for other ids", () => {
    renderAt("/jobs/665f1c2e9b1e8a0012345678");
    expect(screen.getByText("Job details page")).toBeInTheDocument();
  });

  it("redirects the old preview route to the map page", async () => {
    renderAt("/map-preview");
    expect(await screen.findByText("Map page")).toBeInTheDocument();
    expect(screen.getByTestId("path")).toHaveTextContent("/jobs/map");
  });

  it("lazy-loads the office pin editor for ADMIN only", async () => {
    const renderAs = (role) => render(
      <AuthContext.Provider value={{ accessToken: role ? "token" : null, role, initialized: true }}>
        <MemoryRouter initialEntries={["/admin/offices"]}><AppRoutes /><Path /></MemoryRouter>
      </AuthContext.Provider>,
    );
    renderAs("ADMIN");
    expect(await screen.findByText("Office pins page")).toBeInTheDocument();
    cleanup();
    renderAs(null);
    expect(screen.getByTestId("path")).toHaveTextContent("/login");
    cleanup();
    renderAs("USER");
    expect(screen.queryByText("Office pins page")).not.toBeInTheDocument();
  });

  it("leaves the homepage unchanged", () => {
    renderAt("/");
    expect(screen.getByText("Home page")).toBeInTheDocument();
  });

  it("links to the map from the header next to Browse Jobs", () => {
    render(
      <AuthContext.Provider value={{ accessToken: null, role: null, initialized: true }}>
        <MemoryRouter><Header /></MemoryRouter>
      </AuthContext.Provider>,
    );
    const map = screen.getByRole("link", { name: "Map" });
    expect(map).toHaveAttribute("href", "/jobs/map");
    expect(map.className).toBe(screen.getByRole("link", { name: "Browse Jobs" }).className);
  });
});
