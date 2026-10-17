// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";

const { loginUser } = vi.hoisted(() => ({ loginUser: vi.fn() }));
vi.mock("../services/user-service", () => ({ loginUser }));

import Login from "./Login";

function submit() {
  render(<MemoryRouter><Login /></MemoryRouter>);
  fireEvent.change(screen.getByPlaceholderText("Enter your email"), { target: { name: "email", value: "user@example.test" } });
  fireEvent.change(screen.getByPlaceholderText("Enter your password"), { target: { name: "password", value: "secret" } });
  fireEvent.click(screen.getByRole("button", { name: "Login" }));
}

describe("Login page", () => {
  beforeEach(() => { loginUser.mockReset(); });
  afterEach(cleanup);

  it("shows a friendly message when login is rate limited", async () => {
    loginUser.mockRejectedValue({ response: { status: 429, data: { code: "RATE_LIMITED" } } });
    submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("Too many requests, slow down and try again shortly.");
  });

  it("shows invalid credentials inline", async () => {
    loginUser.mockRejectedValue({ response: { status: 401, data: { code: "INVALID_CREDENTIALS" } } });
    submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("Invalid email or password.");
  });
});
