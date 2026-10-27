import { describe, expect, it } from "vitest";
import { layers, namedFlavor } from "@protomaps/basemaps";
import {
  CENTRE_RING_SOURCE, centreRingLayers, dimmingPaint, jobLayers, LAYERS, PLACE_ZOOM, PLACES_SOURCE, placesSource,
  RING_LAYERS, RING_ZOOM, selectedFilter,
} from "./jobLayers";

const baseFonts = new Set(layers("protomaps", namedFlavor("light"), { lang: "en" })
  .flatMap((layer) => JSON.stringify(layer.layout?.["text-font"] ?? []).match(/Noto Sans[^"]*/g) ?? []));

describe("job map layers", () => {
  it("clusters places and sums their jobs", () => {
    const source = placesSource({ type: "FeatureCollection", features: [] });
    expect(source).toMatchObject({ type: "geojson", cluster: true, clusterRadius: 50, clusterMaxZoom: 9 });
    expect(source.clusterProperties).toEqual({ jobs: ["+", ["get", "jobs"]] });
    expect(PLACE_ZOOM).toBeGreaterThan(source.clusterMaxZoom);
  });

  it("adds text layers only when the style has glyphs, using fonts the base style ships", () => {
    const plain = jobLayers({ hasGlyphs: false });
    expect(plain.some((layer) => layer.type === "symbol")).toBe(false);
    expect(plain.map((layer) => layer.id)).toEqual(expect.arrayContaining([LAYERS.clusters, LAYERS.points, LAYERS.selected]));

    const withText = jobLayers({ hasGlyphs: true });
    const symbols = withText.filter((layer) => layer.type === "symbol");
    expect(symbols.map((layer) => layer.id)).toEqual([LAYERS.clusterCounts, LAYERS.pointCounts, LAYERS.names,
      LAYERS.localityNames, LAYERS.officeNames]);
    symbols.forEach((layer) => layer.layout["text-font"].forEach((font) => expect(baseFonts).toContain(font)));
    expect(withText.find((layer) => layer.id === LAYERS.names).minzoom).toBe(7);
  });

  it("uses the indigo-600 brand colour and filters the selected ring by place id", () => {
    const clusters = jobLayers({ hasGlyphs: false }).find((layer) => layer.id === LAYERS.clusters);
    expect(clusters.paint["circle-color"]).toBe("#4f46e5");
    expect(selectedFilter("1277333")).toEqual(["all", ["!", ["has", "point_count"]], ["==", ["get", "id"], "1277333"]]);
  });

  it("draws offices as their own unclustered layer and keeps them out of the city layers", () => {
    const all = jobLayers({ hasGlyphs: true });
    const offices = all.find((layer) => layer.id === LAYERS.offices);
    expect(offices.filter).toEqual(["all", ["!", ["has", "point_count"]], ["==", ["get", "kind"], "office"]]);
    expect(offices.paint["circle-color"]).toBe("#ffffff");
    expect(offices.paint["circle-stroke-color"]).toBe("#4f46e5");
    for (const id of [LAYERS.points, LAYERS.pointCounts, LAYERS.names]) {
      expect(JSON.stringify(all.find((layer) => layer.id === id).filter)).toContain('["!",["==",["get","kind"],"office"]]');
    }
    expect(all.find((layer) => layer.id === LAYERS.officeNames).layout["text-field"]).toEqual(["get", "name"]);
  });

  it("draws localities between city bubbles and office pins, labelled with name and count at locality zoom", () => {
    const all = jobLayers({ hasGlyphs: true });
    const ids = all.map((layer) => layer.id);
    const localities = all.find((layer) => layer.id === LAYERS.localities);
    expect(localities.filter).toEqual(["all", ["!", ["has", "point_count"]], ["==", ["get", "kind"], "locality"]]);
    expect(localities.paint["circle-color"]).toBe("#818cf8");
    expect(localities.paint["circle-stroke-color"]).toBe("#ffffff");
    expect(ids.indexOf(LAYERS.points)).toBeLessThan(ids.indexOf(LAYERS.localities));
    expect(ids.indexOf(LAYERS.localities)).toBeLessThan(ids.indexOf(LAYERS.offices));
    // Max radius: city 30 > locality 20 > office 14.
    const maxRadius = (id) => all.find((layer) => layer.id === id).paint["circle-radius"].at(-1);
    expect(maxRadius(LAYERS.points)).toBeGreaterThan(maxRadius(LAYERS.localities));
    expect(maxRadius(LAYERS.localities)).toBeGreaterThan(maxRadius(LAYERS.offices));
    for (const id of [LAYERS.points, LAYERS.pointCounts, LAYERS.names]) {
      expect(JSON.stringify(all.find((layer) => layer.id === id).filter)).toContain('["!",["==",["get","kind"],"locality"]]');
    }
    const names = all.find((layer) => layer.id === LAYERS.localityNames);
    expect(names.minzoom).toBe(PLACE_ZOOM);
    expect(names.layout["text-field"].slice(0, 3)).toEqual(["concat", ["get", "name"], " · "]);
  });

  it("draws the centre-company ring from its own source at street zoom, unlike offices and localities", () => {
    expect(RING_ZOOM).toBe(PLACE_ZOOM + 2);
    const { circles, labels } = centreRingLayers({ hasGlyphs: true });
    const all = [...circles, ...labels];
    expect(all.map((layer) => layer.id)).toEqual([RING_LAYERS.line, RING_LAYERS.companies, RING_LAYERS.initials,
      RING_LAYERS.names]);
    all.forEach((layer) => {
      expect(layer.source).toBe(CENTRE_RING_SOURCE);
      expect(layer.minzoom).toBeGreaterThanOrEqual(RING_ZOOM);
    });
    expect(CENTRE_RING_SOURCE).not.toBe(PLACES_SOURCE);
    const line = all.find((layer) => layer.id === RING_LAYERS.line);
    expect(line.paint["line-color"]).toBe("#94a3b8");
    expect(line.paint["line-dasharray"]).toEqual([2, 2]);
    const companies = all.find((layer) => layer.id === RING_LAYERS.companies);
    expect(companies.paint).toMatchObject({ "circle-color": "#f1f5f9", "circle-stroke-color": "#94a3b8",
      "circle-stroke-width": 1.5, "circle-radius": 11 });
    const placeLayers = jobLayers({ hasGlyphs: false });
    for (const id of [LAYERS.offices, LAYERS.localities, LAYERS.points]) {
      const layer = placeLayers.find((candidate) => candidate.id === id);
      expect(layer.paint["circle-color"]).not.toBe(companies.paint["circle-color"]);
      expect(layer.paint["circle-stroke-color"]).not.toBe(companies.paint["circle-stroke-color"]);
    }
    expect(all.find((layer) => layer.id === RING_LAYERS.names)).toMatchObject({ minzoom: 14 });
    expect(all.find((layer) => layer.id === RING_LAYERS.names).layout["text-optional"]).toBe(true);
    labels.forEach((layer) => layer.layout["text-font"].forEach((font) => expect(baseFonts).toContain(font)));
    expect(centreRingLayers({ hasGlyphs: false }).labels).toEqual([]);
  });

  it("dims markers for the Remote and India tabs", () => {
    const dimmed = Object.fromEntries(dimmingPaint(true).map(([layer, property, value]) => [`${layer}/${property}`, value]));
    const normal = Object.fromEntries(dimmingPaint(false).map(([layer, property, value]) => [`${layer}/${property}`, value]));
    expect(dimmed[`${LAYERS.points}/circle-opacity`]).toBeLessThan(normal[`${LAYERS.points}/circle-opacity`]);
    expect(dimmed[`${LAYERS.localities}/circle-opacity`]).toBeLessThan(normal[`${LAYERS.localities}/circle-opacity`]);
    expect(dimmed[`${RING_LAYERS.companies}/circle-opacity`]).toBeLessThan(normal[`${RING_LAYERS.companies}/circle-opacity`]);
    for (const id of Object.values(RING_LAYERS)) {
      expect(Object.keys(normal).some((key) => key.startsWith(`${id}/`))).toBe(true);
    }
  });
});
