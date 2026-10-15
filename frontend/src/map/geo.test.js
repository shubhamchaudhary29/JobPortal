import { describe, expect, it } from "vitest";
import { circleBounds, circleRing, haversineKm, radiusForView, roundToGrid } from "./geo";

describe("roundToGrid", () => {
  it("rounds to the backend's 0.05° grid with two decimals", () => {
    expect(roundToGrid(12.9716)).toBe(12.95);
    expect(roundToGrid(77.5946)).toBe(77.6);
    expect(roundToGrid(28.6139)).toBe(28.6);
    expect(roundToGrid(77.209)).toBe(77.2);
    expect(roundToGrid(0.025)).toBe(0.05);
  });

  it("rounds negative values half up like Java's Math.round", () => {
    expect(roundToGrid(-12.9716)).toBe(-12.95);
    expect(roundToGrid(-0.025)).toBe(0);
    expect(roundToGrid(-33.8688)).toBe(-33.85);
  });
});

describe("haversineKm", () => {
  it("measures Delhi to Mumbai at about 1,150 km", () => {
    const km = haversineKm({ lat: 28.6139, lng: 77.209 }, { lat: 19.076, lng: 72.8777 });
    expect(Math.abs(km - 1150)).toBeLessThanOrEqual(10);
    expect(haversineKm({ lat: 12.97, lng: 77.59 }, { lat: 12.97, lng: 77.59 })).toBe(0);
  });
});

describe("radiusForView", () => {
  it("uses half the shorter side of the bounds", () => {
    // ~0.5° of latitude ≈ 55.6 km tall, much wider than tall.
    expect(radiusForView([[77.0, 12.75], [78.5, 13.25]])).toBe(28);
  });

  it("clamps to 1–100 km and accepts LngLatBounds-like objects", () => {
    expect(radiusForView([[70, 10], [90, 30]])).toBe(100);
    expect(radiusForView([[77.59, 12.97], [77.591, 12.971]])).toBe(1);
    const bounds = { getWest: () => 77, getSouth: () => 12.75, getEast: () => 78.5, getNorth: () => 13.25 };
    expect(radiusForView(bounds)).toBe(28);
  });
});

describe("search-area circle", () => {
  it("draws a closed ring at the radius and bounds around it", () => {
    const center = { lat: 28.6, lng: 77.2 };
    const ring = circleRing(center, 25);
    expect(ring[0]).toEqual(ring[ring.length - 1]);
    for (const [lng, lat] of ring) expect(haversineKm(center, { lat, lng })).toBeCloseTo(25, 3);
    const [[west, south], [east, north]] = circleBounds(center, 25);
    expect(west).toBeLessThan(77.2);
    expect(east).toBeGreaterThan(77.2);
    expect(north - south).toBeCloseTo(50 / 111.2, 1);
  });
});
