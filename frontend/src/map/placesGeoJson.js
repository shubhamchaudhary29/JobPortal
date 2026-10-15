const finite = (item) => Number.isFinite(item?.lat) && Number.isFinite(item?.lng);

/**
 * Snapshot places and pinned offices as one GeoJSON FeatureCollection for the clustered "places" source. A city
 * carries only its jobs that are not at a pinned office ({@code jobs - officeJobs}; skipped when 0) and each office
 * carries its own, so cluster sums still equal the city totals. Items with non-finite coordinates are skipped.
 */
export function snapshotToGeoJson(snapshot) {
  const places = Array.isArray(snapshot?.places) ? snapshot.places : [];
  const offices = Array.isArray(snapshot?.offices) ? snapshot.offices : [];
  const cities = places
    .filter(finite)
    .map((place) => ({ ...place, remainder: place.jobs - (place.officeJobs || 0) }))
    .filter((place) => place.remainder > 0)
    .map((place) => ({
      type: "Feature",
      geometry: { type: "Point", coordinates: [place.lng, place.lat] },
      properties: { id: place.id, kind: "city", name: place.name, state: place.state, jobs: place.remainder },
    }));
  const pins = offices
    .filter((office) => finite(office) && office.jobs > 0)
    .map((office) => ({
      type: "Feature",
      geometry: { type: "Point", coordinates: [office.lng, office.lat] },
      properties: { id: office.id, kind: "office", placeId: office.placeId, name: office.company, label: office.label,
        jobs: office.jobs },
    }));
  return { type: "FeatureCollection", features: [...cities, ...pins] };
}
