import { describe, expect, it } from "vitest";
import {
  MAX_RING_COMPANIES, ringCapacity, ringLayout, ringOutline, ringRadiusPx, ringSlots,
} from "./centreRing";

const BENGALURU = [77.59369, 12.97194];

/** Independent check: Web Mercator world pixels for MapLibre's 512-px tiles (not the layout's own formula). */
function project([lng, lat], zoom) {
  const size = 512 * 2 ** zoom;
  const sin = Math.sin((lat * Math.PI) / 180);
  return [((lng + 180) / 360) * size, (0.5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI)) * size];
}

const pixelDistance = (a, b, zoom) => {
  const [ax, ay] = project(a, zoom);
  const [bx, by] = project(b, zoom);
  return Math.hypot(ax - bx, ay - by);
};

describe("centre ring layout", () => {
  it("fills rings of 10, 16, 22 … at 44 + 30·ring px", () => {
    expect([0, 1, 2, 3].map(ringCapacity)).toEqual([10, 16, 22, 28]);
    expect([0, 1, 2].map(ringRadiusPx)).toEqual([44, 74, 104]);
    expect(ringSlots(10)).toEqual([[0, 10]]);
    expect(ringSlots(11)).toEqual([[0, 10], [1, 1]]);
    expect(ringSlots(40)).toEqual([[0, 10], [1, 16], [2, 14]]);
    expect(ringSlots(MAX_RING_COMPANIES).reduce((sum, [, slots]) => sum + slots, 0)).toBe(60);
  });

  it.each([12, 16])("puts every marker within 1 px of its ring radius at zoom %i", (zoom) => {
    const positions = ringLayout(BENGALURU, 40, zoom);
    expect(positions).toHaveLength(40);
    const expected = [...Array(10).fill(44), ...Array(16).fill(74), ...Array(14).fill(104)];
    positions.forEach((position, index) => {
      expect(Math.abs(pixelDistance(BENGALURU, position, zoom) - expected[index])).toBeLessThan(1);
    });
  });

  it("spaces a ring evenly and turns each ring so they do not line up", () => {
    const zoom = 14;
    const [cx, cy] = project(BENGALURU, zoom);
    const bearing = (position) => {
      const [x, y] = project(position, zoom);
      return Math.atan2(x - cx, cy - y);
    };
    const positions = ringLayout(BENGALURU, 26, zoom);
    expect(bearing(positions[0])).toBeCloseTo(0, 2);
    expect(bearing(positions[1])).toBeCloseTo((2 * Math.PI) / 10, 2);
    expect(bearing(positions[10])).toBeCloseTo(Math.PI / 16, 2);
  });

  it("is deterministic, capped at 60 and empty for nothing", () => {
    expect(ringLayout(BENGALURU, 25, 13)).toEqual(ringLayout(BENGALURU, 25, 13));
    expect(ringLayout(BENGALURU, 500, 13)).toHaveLength(60);
    expect(ringLayout(BENGALURU, 0, 13)).toEqual([]);
    expect(ringLayout(BENGALURU, -3, 13)).toEqual([]);
    expect(ringLayout([Number.NaN, 12], 5, 13)).toEqual([]);
  });

  it("draws a closed outline just outside the outermost ring", () => {
    const outline = ringOutline(BENGALURU, 11, 15);
    expect(outline.type).toBe("LineString");
    expect(outline.coordinates[0]).toEqual(outline.coordinates[outline.coordinates.length - 1]);
    outline.coordinates.forEach((point) => {
      const distance = pixelDistance(BENGALURU, point, 15);
      expect(distance).toBeGreaterThan(74);
      expect(distance).toBeLessThan(74 + 20);
    });
    expect(ringOutline(BENGALURU, 0, 15)).toBeNull();
  });
});
