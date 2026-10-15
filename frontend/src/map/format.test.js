import { describe, expect, it } from "vitest";
import { formatCount, formatDistance, formatSalary } from "./format";

describe("formatCount", () => {
  it("keeps small counts and compacts thousands", () => {
    expect(formatCount(0)).toBe("0");
    expect(formatCount(999)).toBe("999");
    expect(formatCount(1000)).toBe("1k");
    expect(formatCount(1234)).toBe("1.2k");
    expect(formatCount(12345)).toBe("12k");
    expect(formatCount(undefined)).toBe("0");
  });
});

describe("formatSalary", () => {
  it("shows annual INR in lakhs per annum", () => {
    expect(formatSalary(800000, 1200000)).toBe("₹8–12 LPA");
    expect(formatSalary(850000, 1250000)).toBe("₹8.5–12.5 LPA");
    expect(formatSalary(1200000, 1200000)).toBe("₹12 LPA");
    expect(formatSalary(1200000, null)).toBe("₹12+ LPA");
    expect(formatSalary(null, 1500000)).toBe("Up to ₹15 LPA");
    expect(formatSalary(1200000, 800000)).toBe("₹8–12 LPA");
  });

  it("says Not disclosed without a salary and shows small amounts in rupees", () => {
    expect(formatSalary(null, null)).toBe("Not disclosed");
    expect(formatSalary(0, 0)).toBe("Not disclosed");
    expect(formatSalary(25000, 30000)).toBe("₹25,000–30,000");
  });
});

describe("formatDistance", () => {
  it("formats near, mid and far distances", () => {
    expect(formatDistance(0)).toBe("< 1 km");
    expect(formatDistance(0.4)).toBe("< 1 km");
    expect(formatDistance(3.24)).toBe("3.2 km");
    expect(formatDistance(42.4)).toBe("42 km");
    expect(formatDistance(null)).toBe("");
  });
});
