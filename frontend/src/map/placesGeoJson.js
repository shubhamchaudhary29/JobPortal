/** Snapshot places as a GeoJSON FeatureCollection for the clustered "places" source; skips bad coordinates. */
export function snapshotToGeoJson(snapshot) {
  const places = Array.isArray(snapshot?.places) ? snapshot.places : [];
  return {
    type: "FeatureCollection",
    features: places
      .filter((place) => Number.isFinite(place?.lat) && Number.isFinite(place?.lng))
      .map((place) => ({
        type: "Feature",
        geometry: { type: "Point", coordinates: [place.lng, place.lat] },
        properties: { id: place.id, name: place.name, state: place.state, jobs: place.jobs },
      })),
  };
}
