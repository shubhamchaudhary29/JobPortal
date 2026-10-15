// @vitest-environment jsdom
import { describe, expect, it } from "vitest";
import { formatViewHash, parseViewHash, replaceHash } from "./viewHash";

describe("map view hash", () => {
  it("reads MapLibre's #zoom/lat/lng format", () => {
    expect(parseViewHash("#9.5/12.97/77.59")).toEqual({ zoom: 9.5, center: [77.59, 12.97] });
    expect(parseViewHash("#10/12.9719/77.5937/0/0")).toEqual({ zoom: 10, center: [77.5937, 12.9719] });
    expect(parseViewHash("")).toBeNull();
    expect(parseViewHash("#top")).toBeNull();
    expect(parseViewHash("#5/95/10")).toBeNull();
  });

  it("writes the view with MapLibre's precision", () => {
    expect(formatViewHash({ zoom: 9.9234, lat: 28.600213, lng: 77.2 })).toBe("#9.92/28.6002/77.2000");
  });

  it("replaces only the fragment, keeping path and query without a history entry", () => {
    window.history.replaceState(null, "", "/jobs/map?place=1#4/20/78");
    const length = window.history.length;
    replaceHash("#6/12.9700/77.5900");
    const { pathname, search, hash } = window.location;
    expect(`${pathname}${search}${hash}`).toBe("/jobs/map?place=1#6/12.9700/77.5900");
    replaceHash("");
    expect(window.location.hash).toBe("");
    expect(window.location.search).toBe("?place=1");
    expect(window.history.length).toBe(length);
  });
});
