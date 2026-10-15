// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// jsdom has no WebGL, so MapLibre itself is mocked.
const maplibre = vi.hoisted(() => {
  const instances = [];
  class Map {
    constructor(options) {
      this.options = options;
      this.remove = vi.fn();
      this.addControl = vi.fn();
      this.on = vi.fn();
      this.touchZoomRotate = { disableRotation: vi.fn() };
      this.keyboard = { disableRotation: vi.fn() };
      instances.push(this);
    }
  }
  return { instances, Map, addProtocol: vi.fn(), setWorkerUrl: vi.fn(), NavigationControl: vi.fn(),
    AttributionControl: vi.fn() };
});
vi.mock("maplibre-gl", () => maplibre);
vi.mock("maplibre-gl/dist/maplibre-gl.css", () => ({}));
vi.mock("maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url", () => ({ default: "/assets/maplibre-worker.js" }));
const manifest = vi.hoisted(() => ({ loadTilesManifest: vi.fn() }));
vi.mock("./tilesManifest", async (original) => ({ ...(await original()), loadTilesManifest: manifest.loadTilesManifest }));

import BaseMap from "./BaseMap";

const valid = {
  pmtiles: "india-20261014-z11.pmtiles", maxzoom: 11, bbox: [66, 5, 98.5, 37.5], assets: "assets/028c18f713ba",
  attribution: "© OpenStreetMap contributors · Protomaps · GeoNames",
};

describe("BaseMap", () => {
  beforeEach(() => {
    maplibre.instances.length = 0;
    manifest.loadTilesManifest.mockReset();
  });
  afterEach(cleanup);

  it("creates one map with the self-hosted style and removes it on unmount", async () => {
    manifest.loadTilesManifest.mockResolvedValue(valid);
    const { unmount } = render(<BaseMap />);

    await waitFor(() => expect(maplibre.instances).toHaveLength(1));
    const [map] = maplibre.instances;
    expect(map.options.style.sources.protomaps.url).toBe(`pmtiles://${window.location.origin}/tiles/india-20261014-z11.pmtiles`);
    expect(map.options.maxZoom).toBe(14);
    expect(map.options.dragRotate).toBe(false);
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    expect(map.options.style.glyphs).toBeTruthy();

    unmount();
    expect(map.remove).toHaveBeenCalledTimes(1);
  });

  it("registers the pmtiles protocol once across mounts", async () => {
    manifest.loadTilesManifest.mockResolvedValue(valid);
    const first = render(<BaseMap />);
    await waitFor(() => expect(maplibre.instances).toHaveLength(1));
    first.unmount();
    render(<BaseMap />);
    await waitFor(() => expect(maplibre.instances).toHaveLength(2));

    expect(maplibre.addProtocol).toHaveBeenCalledTimes(1);
    expect(maplibre.setWorkerUrl).toHaveBeenCalledTimes(1);
    expect(maplibre.setWorkerUrl).toHaveBeenCalledWith("/assets/maplibre-worker.js");
    expect(maplibre.addProtocol).toHaveBeenCalledWith("pmtiles", expect.any(Function));
  });

  it("falls back to a plain background with a notice when the manifest is unavailable", async () => {
    manifest.loadTilesManifest.mockResolvedValue(null);
    render(<BaseMap />);

    expect(await screen.findByRole("status")).toHaveTextContent("Map background unavailable — markers still work");
    const [map] = maplibre.instances;
    expect(map.options.style.layers).toHaveLength(1);
    expect(maplibre.AttributionControl).toHaveBeenCalledWith(expect.objectContaining({
      customAttribution: "© OpenStreetMap contributors · GeoNames",
    }));
  });

  it("tells onReady whether the style has glyphs for text layers", async () => {
    const onReady = vi.fn();
    manifest.loadTilesManifest.mockResolvedValue(valid);
    render(<BaseMap onReady={onReady} />);
    await waitFor(() => expect(maplibre.instances).toHaveLength(1));
    const load = (map) => map.on.mock.calls.find(([event]) => event === "load")[1];
    load(maplibre.instances[0])();
    expect(onReady).toHaveBeenLastCalledWith(maplibre.instances[0], { hasGlyphs: true });
    cleanup();

    manifest.loadTilesManifest.mockResolvedValue(null);
    render(<BaseMap onReady={onReady} />);
    await waitFor(() => expect(maplibre.instances).toHaveLength(2));
    expect(maplibre.instances[1].options.style.glyphs).toBeUndefined();
    load(maplibre.instances[1])();
    expect(onReady).toHaveBeenLastCalledWith(maplibre.instances[1], { hasGlyphs: false });
  });

  it("does not create a map if unmounted before the manifest arrives", async () => {
    let resolve;
    manifest.loadTilesManifest.mockReturnValue(new Promise((done) => { resolve = done; }));
    const { unmount } = render(<BaseMap />);
    unmount();
    resolve(valid);
    await Promise.resolve();
    expect(maplibre.instances).toHaveLength(0);
  });
});
