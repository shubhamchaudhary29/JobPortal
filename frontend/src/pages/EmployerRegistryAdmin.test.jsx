// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";

const registry = vi.hoisted(() => ({
  listRegistry: vi.fn(), createRegistryEntry: vi.fn(), updateRegistryEntry: vi.fn(),
  validateRegistryEntry: vi.fn(), getRegistryAudit: vi.fn(), registryError: vi.fn(),
}));
const aggregation = vi.hoisted(() => ({ getAggregationProviders: vi.fn() }));
vi.mock("../services/employer-registry-service", () => registry);
vi.mock("../services/aggregation-admin-service", () => aggregation);
vi.mock("../components/Header", () => ({ default: () => <div data-testid="header" /> }));
vi.mock("../components/Footer", () => ({ default: () => <div data-testid="footer" /> }));

import EmployerRegistryAdmin from "./EmployerRegistryAdmin";

const entry = (overrides = {}) => ({
  id: "entry-1", provider: "greenhouse", boardId: "groww", company: "Groww", enabled: true, disabledReason: null,
  origin: "PROPERTIES", updatedBy: "properties-import", updatedAt: "2026-10-13T00:00:00Z", ...overrides,
});
const page = (content, overrides = {}) => ({
  content, page: 0, size: 20, totalElements: content.length, totalPages: content.length ? 1 : 0, first: true, last: true, ...overrides,
});
const providers = [
  { name: "adzuna", displayName: "Adzuna", employerScoped: false, available: true },
  { name: "greenhouse", displayName: "Greenhouse", employerScoped: true, available: true },
  { name: "lever", displayName: "Lever", employerScoped: true, available: true },
];

function renderPage() {
  return render(<MemoryRouter><EmployerRegistryAdmin /></MemoryRouter>);
}

describe("employer registry ADMIN page", () => {
  beforeEach(() => {
    Object.values(registry).forEach((mock) => mock.mockReset());
    aggregation.getAggregationProviders.mockReset();
    aggregation.getAggregationProviders.mockResolvedValue(providers);
    registry.registryError.mockImplementation((error, fallback) => error?.safe || fallback || "Request failed");
    registry.listRegistry.mockResolvedValue(page([entry(), entry({ id: "entry-2", provider: "lever", boardId: "cred", company: "CRED", enabled: false, disabledReason: "board moved" })]));
  });
  afterEach(cleanup);

  it("lists entries, filters by provider, status, and text, and pages", async () => {
    registry.listRegistry.mockResolvedValueOnce(page([entry()], { totalPages: 2, totalElements: 21, last: false }));
    renderPage();
    expect(screen.getByRole("status")).toHaveTextContent("Loading the employer registry");
    expect(await screen.findByText("Groww")).toBeInTheDocument();
    expect(within(screen.getByLabelText("Filter provider")).getAllByRole("option").map((option) => option.textContent))
      .toEqual(["All providers", "Greenhouse", "Lever"]);
    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    await waitFor(() => expect(registry.listRegistry).toHaveBeenLastCalledWith({ page: 1, size: 20 }));
    fireEvent.change(screen.getByLabelText("Filter provider"), { target: { value: "lever" } });
    await waitFor(() => expect(registry.listRegistry).toHaveBeenLastCalledWith({ page: 0, size: 20, provider: "lever" }));
    fireEvent.change(screen.getByLabelText("Filter status"), { target: { value: "false" } });
    fireEvent.change(screen.getByLabelText("Search company or board"), { target: { value: " cred " } });
    fireEvent.click(screen.getByRole("button", { name: "Search" }));
    await waitFor(() => expect(registry.listRegistry).toHaveBeenLastCalledWith({ page: 0, size: 20, provider: "lever", enabled: "false", q: "cred" }));
    expect(await screen.findByText("board moved")).toBeInTheDocument();
  });

  it("shows empty and error states with retry", async () => {
    registry.listRegistry.mockResolvedValueOnce(page([]));
    renderPage();
    expect(await screen.findByText("No registry entries match these filters.")).toBeInTheDocument();
    cleanup();
    registry.listRegistry.mockRejectedValueOnce({ safe: "Administrator access is required." });
    renderPage();
    expect(await screen.findByRole("alert")).toHaveTextContent("Administrator access is required.");
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    expect(await screen.findByText("Groww")).toBeInTheDocument();
  });

  it("adds a board once even when submitted twice and reports conflicts", async () => {
    let finish;
    registry.createRegistryEntry.mockReturnValueOnce(new Promise((resolve) => { finish = resolve; }));
    renderPage();
    await screen.findByText("Groww");
    await within(screen.getByLabelText("New board provider")).findByRole("option", { name: "Lever" });
    fireEvent.change(screen.getByLabelText("New board provider"), { target: { value: "lever" } });
    fireEvent.change(screen.getByLabelText("New board ID"), { target: { value: " paytm " } });
    fireEvent.change(screen.getByLabelText("New board company"), { target: { value: "Paytm" } });
    const add = screen.getByRole("button", { name: "Add board" });
    fireEvent.click(add);
    fireEvent.click(add);
    expect(registry.createRegistryEntry).toHaveBeenCalledTimes(1);
    expect(registry.createRegistryEntry).toHaveBeenCalledWith({ provider: "lever", boardId: "paytm", company: "Paytm", enabled: true });
    await act(async () => finish({ id: "new", provider: "lever", boardId: "paytm", company: "Paytm" }));
    expect(await screen.findByText(/Added Paytm \(lever · paytm\)/)).toBeInTheDocument();

    registry.createRegistryEntry.mockRejectedValueOnce({ safe: "That board already exists, or the company already has an enabled board." });
    fireEvent.change(screen.getByLabelText("New board ID"), { target: { value: "groww" } });
    fireEvent.change(screen.getByLabelText("New board company"), { target: { value: "Groww" } });
    fireEvent.click(screen.getByRole("button", { name: "Add board" }));
    expect(await screen.findByText(/already has an enabled board/)).toBeInTheDocument();
  });

  it("edits, disables with a reason, and enables entries", async () => {
    registry.updateRegistryEntry.mockImplementation(async (id, changes) => ({ ...entry({ id }), ...changes, enabled: changes.enabled ?? true }));
    renderPage();
    await screen.findByText("Groww");
    const growwRow = screen.getByText("Groww").closest("tr");
    fireEvent.click(within(growwRow).getByRole("button", { name: "Edit" }));
    fireEvent.change(screen.getByLabelText("Company for groww"), { target: { value: "Groww Invest" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(registry.updateRegistryEntry).toHaveBeenCalledWith("entry-1", { company: "Groww Invest" }));

    const disable = await waitFor(() => {
      const button = within(screen.getByText("Groww").closest("tr")).getByRole("button", { name: "Disable" });
      expect(button).toBeEnabled();
      return button;
    });
    fireEvent.click(disable);
    await waitFor(() => expect(registry.updateRegistryEntry).toHaveBeenCalledWith("entry-1", { enabled: false, disabledReason: "Disabled by administrator" }));
    const enable = await waitFor(() => {
      const button = within(screen.getByText("CRED").closest("tr")).getByRole("button", { name: "Enable" });
      expect(button).toBeEnabled();
      return button;
    });
    fireEvent.click(enable);
    await waitFor(() => expect(registry.updateRegistryEntry).toHaveBeenCalledWith("entry-2", { enabled: true }));
  });

  it("shows validation results and audit history", async () => {
    registry.validateRegistryEntry.mockResolvedValue({ id: "entry-1", classification: "ACTIVE", count: 10 });
    registry.getRegistryAudit.mockResolvedValue({ content: [{ id: "a1", action: "VALIDATE", actor: "admin@example.com", at: "2026-10-13T00:00:00Z" }] });
    renderPage();
    await screen.findByText("Groww");
    const row = screen.getByText("Groww").closest("tr");
    fireEvent.click(within(row).getByRole("button", { name: "Validate" }));
    expect(await screen.findByText("Validation: ACTIVE · 10 listings")).toBeInTheDocument();
    fireEvent.click(within(row).getByRole("button", { name: "History" }));
    expect(await screen.findByText("History · Groww")).toBeInTheDocument();
    expect(screen.getByText("VALIDATE")).toBeInTheDocument();
    expect(registry.getRegistryAudit).toHaveBeenCalledWith("entry-1");
  });
});
