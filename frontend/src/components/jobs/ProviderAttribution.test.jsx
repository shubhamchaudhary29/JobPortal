// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import ProviderAttribution from "./ProviderAttribution";

describe("ProviderAttribution", () => {
  afterEach(cleanup);

  it("links aggregator credits to the provider's Indian site and ignores other sources", () => {
    render(<ProviderAttribution source="Jooble" />);
    const jooble = screen.getByRole("link", { name: "Jobs by Jooble" });
    expect(jooble).toHaveAttribute("href", "https://in.jooble.org");
    expect(jooble).toHaveAttribute("rel", "noopener noreferrer");
    cleanup();
    render(<ProviderAttribution source="adzuna" />);
    const adzuna = screen.getByRole("link", { name: "Jobs by Adzuna" });
    expect(adzuna).toHaveAttribute("href", "https://www.adzuna.in");
    expect(adzuna).toHaveClass("min-w-[116px]", "min-h-[23px]");
    cleanup();
    const { container } = render(<ProviderAttribution source="lever" />);
    expect(container).toBeEmptyDOMElement();
  });
});
