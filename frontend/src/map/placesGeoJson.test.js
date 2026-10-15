import { describe, expect, it } from "vitest";
import { snapshotToGeoJson } from "./placesGeoJson";

describe("snapshotToGeoJson", () => {
  it("maps places to [lng, lat] points with id, name, state and jobs", () => {
    const geojson = snapshotToGeoJson({
      places: [{ id: "1277333", name: "Bengaluru", state: "Karnataka", lat: 12.97, lng: 77.59, jobs: 214 }],
    });
    expect(geojson).toEqual({
      type: "FeatureCollection",
      features: [{
        type: "Feature",
        geometry: { type: "Point", coordinates: [77.59, 12.97] },
        properties: { id: "1277333", name: "Bengaluru", state: "Karnataka", jobs: 214 },
      }],
    });
  });

  it("skips places with non-finite coordinates and tolerates a missing snapshot", () => {
    const geojson = snapshotToGeoJson({
      places: [
        { id: "a", name: "A", lat: Number.NaN, lng: 77, jobs: 1 },
        { id: "b", name: "B", lat: 12, lng: null, jobs: 1 },
        { id: "c", name: "C", lat: 12, lng: Infinity, jobs: 1 },
        { id: "d", name: "D", state: "Goa", lat: 15.5, lng: 73.8, jobs: 3 },
      ],
    });
    expect(geojson.features.map((feature) => feature.properties.id)).toEqual(["d"]);
    expect(snapshotToGeoJson(null).features).toEqual([]);
  });
});
