import { useEffect, useRef, useState } from "react";
// maplibre-gl 6 is ESM with named exports only (no default export).
import * as maplibregl from "maplibre-gl";
// Imported here, not globally, so MapLibre's CSS ships only in the lazy map chunk.
import "maplibre-gl/dist/maplibre-gl.css";
import { buildBaseStyle, buildFallbackStyle, FALLBACK_ATTRIBUTION } from "./baseStyle";
import { registerPmtiles } from "./registerPmtiles";
import { loadTilesManifest } from "./tilesManifest";

const INDIA_CENTER = [78.9, 22.6];
const INDIA_BBOX = [66.0, 5.0, 98.5, 37.5];
const BOUNDS_PADDING_DEGREES = 2;
const FILL = { position: "absolute", inset: 0 };

function paddedBounds([west, south, east, north]) {
  const pad = BOUNDS_PADDING_DEGREES;
  return [[Math.max(-180, west - pad), Math.max(-85, south - pad)], [Math.min(180, east + pad), Math.min(85, north + pad)]];
}

/**
 * Self-hosted India base map. Falls back to a plain background (with a small notice) when the tiles volume is
 * missing, so overlays drawn in onReady still work.
 */
/**
 * {@code hash}: keep the view in the URL fragment (#zoom/lat/lng), MapLibre's built-in option.
 * {@code onReady(map, { hasGlyphs })}: hasGlyphs is false for the fallback style, which has no fonts, so callers must
 * only add text/symbol layers when it is true.
 */
export default function BaseMap({ onReady, className = "", hash = false }) {
  const container = useRef(null);
  const onReadyRef = useRef(onReady);
  const [unavailable, setUnavailable] = useState(false);

  useEffect(() => {
    onReadyRef.current = onReady;
  }, [onReady]);

  useEffect(() => {
    let map = null;
    let cancelled = false;
    registerPmtiles();
    loadTilesManifest().then((manifest) => {
      if (cancelled || !container.current) return;
      setUnavailable(!manifest);
      const style = manifest ? buildBaseStyle(manifest) : buildFallbackStyle();
      map = new maplibregl.Map({
        container: container.current,
        style,
        center: INDIA_CENTER,
        zoom: 4,
        minZoom: 3,
        // MapLibre over-zooms vector tiles past the archive's maxzoom.
        maxZoom: 14,
        maxBounds: paddedBounds(manifest ? manifest.bbox : INDIA_BBOX),
        dragRotate: false,
        pitchWithRotate: false,
        touchPitch: false,
        attributionControl: false,
        hash,
      });
      map.touchZoomRotate.disableRotation();
      map.keyboard.disableRotation();
      map.addControl(new maplibregl.NavigationControl({ showCompass: false, visualizePitch: false }), "top-right");
      // With tiles, the source carries the manifest attribution; without them nothing would credit the data.
      map.addControl(new maplibregl.AttributionControl(manifest ? { compact: true }
        : { compact: true, customAttribution: FALLBACK_ATTRIBUTION }), "bottom-right");
      const hasGlyphs = Boolean(style.glyphs);
      map.on("load", () => onReadyRef.current?.(map, { hasGlyphs }));
    });
    return () => {
      cancelled = true;
      map?.remove();
    };
    // The map is created once; changing hash later would need a new map, which callers never do.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <div className={`relative ${className}`}>
      {/* Inline style on purpose: maplibre-gl.css sets .maplibregl-map { position: relative }, which would override a
          Tailwind "absolute" class and collapse the map to zero height. */}
      <div ref={container} style={FILL} data-testid="base-map" />
      {unavailable && (
        <p role="status"
          className="pointer-events-none absolute left-3 top-3 rounded-2xl bg-white/90 px-3 py-2 text-xs text-slate-600 shadow-sm ring-1 ring-slate-200">
          Map background unavailable — markers still work
        </p>
      )}
    </div>
  );
}
