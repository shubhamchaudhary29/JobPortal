// Temporary GEO-4 preview; replaced by the GEO-5 map page. Unlisted: not linked from the header or any page.
import { useCallback, useState } from "react";
import BaseMap from "../map/BaseMap";

const SNAPSHOT_URL = "/api/v1/jobs/map/snapshot";

function toGeoJson(places) {
  return {
    type: "FeatureCollection",
    features: places.map((place) => ({
      type: "Feature",
      geometry: { type: "Point", coordinates: [place.lng, place.lat] },
      properties: { id: place.id, name: place.name, jobs: place.jobs },
    })),
  };
}

export default function MapPreview() {
  const [status, setStatus] = useState("Loading places…");

  const drawSnapshot = useCallback(async (map) => {
    try {
      const response = await fetch(SNAPSHOT_URL);
      if (!response.ok) throw new Error(`snapshot ${response.status}`);
      const snapshot = await response.json();
      map.addSource("places", { type: "geojson", data: toGeoJson(snapshot.places) });
      map.addLayer({
        id: "places-circles",
        type: "circle",
        source: "places",
        paint: {
          "circle-color": "#4f46e5", // indigo-600
          "circle-opacity": 0.75,
          "circle-stroke-color": "#ffffff",
          "circle-stroke-width": 1.5,
          "circle-radius": ["interpolate", ["linear"], ["sqrt", ["get", "jobs"]], 1, 6, 30, 28],
        },
      });
      if (map.getStyle().glyphs) {
        map.addLayer({
          id: "places-counts",
          type: "symbol",
          source: "places",
          layout: { "text-field": ["to-string", ["get", "jobs"]], "text-font": ["Noto Sans Medium"], "text-size": 11 },
          paint: { "text-color": "#ffffff" },
        });
      }
      setStatus(`${snapshot.places.length} places · ${snapshot.placedJobs} jobs`);
    } catch {
      setStatus("Could not load places");
    }
  }, []);

  return (
    <main className="flex h-screen flex-col bg-slate-50">
      <header className="flex items-center justify-between px-4 py-3">
        <h1 className="text-lg font-semibold text-slate-900">Map preview</h1>
        <p className="text-sm text-slate-500" role="status">{status}</p>
      </header>
      <BaseMap onReady={drawSnapshot} hash className="m-4 mt-0 flex-1 overflow-hidden rounded-3xl ring-1 ring-slate-200" />
    </main>
  );
}
