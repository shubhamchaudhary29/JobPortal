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
        properties: { id: "1277333", kind: "city", name: "Bengaluru", state: "Karnataka", jobs: 214 },
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

  const snapshot = {
    places: [
      { id: "blr", name: "Bengaluru", state: "Karnataka", lat: 12.97, lng: 77.59, jobs: 214, officeJobs: 30 },
      { id: "pune", name: "Pune", state: "Maharashtra", lat: 18.52, lng: 73.86, jobs: 40, officeJobs: 0 },
      { id: "goa", name: "Panaji", state: "Goa", lat: 15.49, lng: 73.83, jobs: 5, officeJobs: 5 },
    ],
    offices: [
      { id: "o1", company: "Acme", label: "Bellandur", placeId: "blr", lat: 12.93, lng: 77.68, jobs: 25 },
      { id: "o2", company: "Globex", label: "Whitefield", placeId: "blr", lat: 12.97, lng: 77.75, jobs: 5 },
      { id: "o3", company: "Initech", label: "Patto", placeId: "goa", lat: 15.5, lng: 73.83, jobs: 5 },
    ],
  };

  it("moves office jobs from the city to office features", () => {
    const features = snapshotToGeoJson(snapshot).features;
    const byId = Object.fromEntries(features.map((feature) => [feature.properties.id, feature]));
    expect(byId.blr.properties.jobs).toBe(184);
    expect(byId.pune.properties.jobs).toBe(40);
    expect(byId.goa).toBeUndefined();
    expect(byId.o1).toEqual({
      type: "Feature",
      geometry: { type: "Point", coordinates: [77.68, 12.93] },
      properties: { id: "o1", kind: "office", placeId: "blr", name: "Acme", label: "Bellandur", jobs: 25 },
    });
  });

  it("keeps the sum of emitted jobs equal to the sum of city jobs, so clusters still add up", () => {
    const emitted = snapshotToGeoJson(snapshot).features.reduce((sum, feature) => sum + feature.properties.jobs, 0);
    expect(emitted).toBe(snapshot.places.reduce((sum, place) => sum + place.jobs, 0));
  });
});
