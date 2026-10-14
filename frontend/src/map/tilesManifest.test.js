import { describe, expect, it, vi } from "vitest";
import { loadTilesManifest } from "./tilesManifest";

const valid = {
  pmtiles: "india-20261014-z11.pmtiles", maxzoom: 11, bbox: [66, 5, 98.5, 37.5], assets: "assets/028c18f713ba",
  flavor: "light", sourceBuild: "20261014.pmtiles", builtAt: "2026-10-14T16:25:31Z",
  attribution: "© OpenStreetMap contributors · Protomaps · GeoNames",
};
const respond = (body, ok = true) => vi.fn().mockResolvedValue({ ok, json: () => Promise.resolve(body) });

describe("loadTilesManifest", () => {
  it("returns a valid manifest and asks the browser to revalidate", async () => {
    const fetchImpl = respond(valid);
    const manifest = await loadTilesManifest(fetchImpl);
    expect(manifest).toMatchObject({ pmtiles: valid.pmtiles, maxzoom: 11, assets: valid.assets });
    expect(fetchImpl).toHaveBeenCalledWith("/tiles/manifest.json", { cache: "no-cache" });
  });

  it("returns null for 404, network errors and invalid JSON", async () => {
    await expect(loadTilesManifest(respond(valid, false))).resolves.toBeNull();
    await expect(loadTilesManifest(vi.fn().mockRejectedValue(new TypeError("offline")))).resolves.toBeNull();
    await expect(loadTilesManifest(vi.fn().mockResolvedValue({ ok: true, json: () => Promise.reject(new SyntaxError("x")) })))
      .resolves.toBeNull();
  });

  it.each([
    ["path traversal", { pmtiles: "../x.pmtiles" }],
    ["nested path", { pmtiles: "a/b.pmtiles" }],
    ["wrong extension", { pmtiles: "india.mbtiles" }],
    ["absolute URL", { pmtiles: "https://evil.example/x.pmtiles" }],
    ["bad assets", { assets: "../assets" }],
    ["assets with a slash", { assets: "assets/a/b" }],
    ["zoom out of range", { maxzoom: 22 }],
    ["short bbox", { bbox: [1, 2, 3] }],
    ["missing attribution", { attribution: undefined }],
  ])("rejects %s", async (_label, override) => {
    await expect(loadTilesManifest(respond({ ...valid, ...override }))).resolves.toBeNull();
  });
});
