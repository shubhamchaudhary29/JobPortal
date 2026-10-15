import { describe, expect, it } from "vitest";
import { layers, namedFlavor } from "@protomaps/basemaps";
import { dimmingPaint, jobLayers, LAYERS, PLACE_ZOOM, placesSource, selectedFilter } from "./jobLayers";

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
    expect(symbols.map((layer) => layer.id)).toEqual([LAYERS.clusterCounts, LAYERS.pointCounts, LAYERS.names, LAYERS.officeNames]);
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

  it("dims markers for the Remote and India tabs", () => {
    const dimmed = Object.fromEntries(dimmingPaint(true).map(([layer, property, value]) => [`${layer}/${property}`, value]));
    const normal = Object.fromEntries(dimmingPaint(false).map(([layer, property, value]) => [`${layer}/${property}`, value]));
    expect(dimmed[`${LAYERS.points}/circle-opacity`]).toBeLessThan(normal[`${LAYERS.points}/circle-opacity`]);
  });
});
