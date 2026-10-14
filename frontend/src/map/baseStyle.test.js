import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { layers, namedFlavor } from "@protomaps/basemaps";
import { buildBaseStyle, buildFallbackStyle, isHiddenLayer } from "./baseStyle";

const manifest = {
  pmtiles: "india-20261014-z11.pmtiles", maxzoom: 11, bbox: [66, 5, 98.5, 37.5],
  assets: "assets/028c18f713ba", attribution: "© OpenStreetMap contributors · Protomaps · GeoNames",
};
const ORIGIN = "https://jobs.example";
const style = buildBaseStyle(manifest, ORIGIN);
const ids = style.layers.map((layer) => layer.id);

/** Every font named under any "text-font" key, including ones nested in label "format" expressions. */
function fontNames(value, found = new Set(), key = null) {
  if (key === "text-font") collectStrings(value, found);
  if (Array.isArray(value)) value.forEach((item) => fontNames(item, found));
  else if (value && typeof value === "object") Object.entries(value).forEach(([name, item]) => fontNames(item, found, name));
  return found;
}

function collectStrings(value, found) {
  if (typeof value === "string" && value.startsWith("Noto")) found.add(value);
  else if (Array.isArray(value)) value.forEach((item) => collectStrings(item, found));
}

describe("buildBaseStyle", () => {
  it("references no external URL: every URL is on the page's own origin", () => {
    const urls = JSON.stringify(style).match(/https?:\/\/[^"\s/]+/g) ?? [];
    expect(urls.length).toBeGreaterThan(0);
    expect(new Set(urls)).toEqual(new Set([ORIGIN]));
  });

  it("draws no boundary lines and no country or region labels", () => {
    expect(style.layers.some((layer) => layer["source-layer"] === "boundaries")).toBe(false);
    for (const removed of ["boundaries", "boundaries_country", "places_country", "places_region"]) {
      expect(ids).not.toContain(removed);
    }
    const generated = layers("protomaps", namedFlavor("light"), { lang: "en" });
    expect(generated.filter(isHiddenLayer).map((layer) => layer.id).sort())
      .toEqual(["boundaries", "boundaries_country", "places_country", "places_region"]);
  });

  it("keeps city labels, water and roads", () => {
    expect(ids).toContain("places_locality");
    expect(ids).toContain("water");
    expect(ids.some((id) => id.startsWith("roads_"))).toBe(true);
    expect(style.layers.find((layer) => layer.id === "places_locality").filter).toEqual(["==", "kind", "locality"]);
  });

  it("uses the manifest for the tile source, glyphs and sprite", () => {
    expect(style.sources.protomaps).toEqual({
      type: "vector", url: "pmtiles://https://jobs.example/tiles/india-20261014-z11.pmtiles",
      attribution: manifest.attribution,
    });
    expect(style.glyphs).toBe("https://jobs.example/tiles/assets/028c18f713ba/fonts/{fontstack}/{range}.pbf");
    expect(style.sprite).toBe("https://jobs.example/tiles/assets/028c18f713ba/sprites/light");
  });

  it("only uses fonts that scripts/build-tiles.sh downloads", () => {
    const script = readFileSync(new URL("../../scripts/build-tiles.sh", import.meta.url), "utf8");
    const downloaded = [...script.match(/^FONTSTACKS=\((.*)\)$/m)[1].matchAll(/"([^"]+)"/g)].map((match) => match[1]);
    const used = fontNames(style.layers);
    expect([...used]).toContain("Noto Sans Devanagari Regular v1");
    expect(downloaded.sort()).toEqual([...used].sort());
  });
});

describe("buildFallbackStyle", () => {
  it("is a single background with no sources or glyphs", () => {
    const fallback = buildFallbackStyle();
    expect(fallback.layers).toHaveLength(1);
    expect(fallback.layers[0].type).toBe("background");
    expect(fallback.sources).toEqual({});
    expect(fallback.glyphs).toBeUndefined();
  });
});
