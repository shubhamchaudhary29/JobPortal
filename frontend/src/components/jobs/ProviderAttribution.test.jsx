// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import ProviderAttribution from "./ProviderAttribution";

describe("ProviderAttribution", () => {
  afterEach(cleanup);

  it("links to the aggregator on detail pages and ignores unknown sources", () => {
    render(<ProviderAttribution source="Jooble" linked />);
    const link = screen.getByRole("link", { name: "Jobs by Jooble" });
    expect(link).toHaveAttribute("href", "https://in.jooble.org");
    expect(link).toHaveAttribute("rel", "noopener noreferrer");
    cleanup();
    render(<ProviderAttribution source="jooble" />);
    expect(screen.getByTestId("provider-attribution")).toHaveTextContent("Jobs by Jooble");
    cleanup();
    const { container } = render(<ProviderAttribution source="lever" />);
    expect(container).toBeEmptyDOMElement();
  });
});
