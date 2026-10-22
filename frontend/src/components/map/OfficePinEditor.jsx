import { useCallback, useEffect, useRef, useState } from "react";
// maplibre-gl 6 is ESM with named exports only (no default export).
import * as maplibregl from "maplibre-gl";
import BaseMap from "../../map/BaseMap";

const INDIGO_600 = "#4f46e5";
const SLATE_500 = "#64748b";
const CITY_ZOOM = 12;
const COUNTRY_ZOOM = 4;

/**
 * Click-to-pin map for the office editor: a click drops (or moves) the pin and the pin can be dragged. Calls
 * {@code onPin(lat, lng)} for every change. {@code center} ({@code {lat, lng}} or null for all of India) is applied
 * whenever it changes, so opening another office recentres the map. {@code suggestions} ({@code [{osmRef, name, lat,
 * lng}]}, GEO-8D) are OpenStreetMap matches drawn as grey markers; the editor lists the same matches with their actions.
 */
export default function OfficePinEditor({ center, pin, onPin, suggestions = [], className = "" }) {
  const [map, setMap] = useState(null);
  const marker = useRef(null);
  const onPinRef = useRef(onPin);

  useEffect(() => {
    onPinRef.current = onPin;
  });

  const handleReady = useCallback((ready) => setMap(ready), []);

  useEffect(() => {
    if (!map) return undefined;
    const click = (event) => onPinRef.current?.(event.lngLat.lat, event.lngLat.lng);
    map.on("click", click);
    map.getCanvas().style.cursor = "crosshair";
    return () => map.off("click", click);
  }, [map]);

  const centerLat = center?.lat;
  const centerLng = center?.lng;
  useEffect(() => {
    if (!map) return;
    if (Number.isFinite(centerLat) && Number.isFinite(centerLng)) map.jumpTo({ center: [centerLng, centerLat], zoom: CITY_ZOOM });
    else map.jumpTo({ center: [78.9, 22.6], zoom: COUNTRY_ZOOM });
  }, [map, centerLat, centerLng]);

  const pinLat = pin?.lat;
  const pinLng = pin?.lng;
  useEffect(() => {
    if (!map) return;
    if (!Number.isFinite(pinLat) || !Number.isFinite(pinLng)) {
      marker.current?.remove();
      marker.current = null;
      return;
    }
    if (!marker.current) {
      const created = new maplibregl.Marker({ draggable: true, color: INDIGO_600 });
      created.on("dragend", () => {
        const position = created.getLngLat();
        onPinRef.current?.(position.lat, position.lng);
      });
      marker.current = created;
      created.setLngLat([pinLng, pinLat]).addTo(map);
    } else {
      marker.current.setLngLat([pinLng, pinLat]);
    }
  }, [map, pinLat, pinLng]);

  useEffect(() => {
    if (!map) return undefined;
    const drawn = suggestions.filter((item) => Number.isFinite(item.lat) && Number.isFinite(item.lng)).map((item) => {
      const created = new maplibregl.Marker({ color: SLATE_500 }).setLngLat([item.lng, item.lat]);
      // Plain text only: the name comes from OpenStreetMap.
      created.getElement?.()?.setAttribute("title", `OpenStreetMap: ${item.name}`);
      return created.addTo(map);
    });
    return () => drawn.forEach((created) => created.remove());
  }, [map, suggestions]);

  useEffect(() => () => {
    marker.current?.remove();
    marker.current = null;
  }, []);

  return (
    <section aria-label="Office location map. Click to drop the pin, then drag it to adjust." className={`relative overflow-hidden ${className}`}>
      <BaseMap onReady={handleReady} className="h-full w-full" />
    </section>
  );
}
