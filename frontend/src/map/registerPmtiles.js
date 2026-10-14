// maplibre-gl 6 is ESM with named exports only (no default export).
import * as maplibregl from "maplibre-gl";
// maplibre-gl 6 ships its web worker as a separate module and locates it relative to its own file at runtime,
// which Vite cannot see; ?worker&url makes Vite bundle the worker and hand us its URL.
import workerUrl from "maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url";
import { Protocol } from "pmtiles";

let registered = false;

/**
 * One-time MapLibre setup per page load (React StrictMode runs effects twice): the bundled worker URL and the
 * pmtiles:// protocol.
 */
export function registerPmtiles() {
  if (registered) return false;
  maplibregl.setWorkerUrl(workerUrl);
  maplibregl.addProtocol("pmtiles", new Protocol().tile);
  registered = true;
  return true;
}
