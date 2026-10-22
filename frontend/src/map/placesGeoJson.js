const finite = (item) => Number.isFinite(item?.lat) && Number.isFinite(item?.lng);

/**
 * Snapshot places, pinned offices and localities as one GeoJSON FeatureCollection for the clustered "places" source.
 * A city carries only its jobs that are neither at a pinned office nor at a locality
 * ({@code jobs - officeJobs - localityJobs}, its "office not listed" jobs; skipped when 0 or less), and each office and
 * locality carries its own, so cluster sums still equal the city totals. Items with non-finite coordinates are skipped.
 */
export function snapshotToGeoJson(snapshot) {
  const places = Array.isArray(snapshot?.places) ? snapshot.places : [];
  const offices = Array.isArray(snapshot?.offices) ? snapshot.offices : [];
  const localities = Array.isArray(snapshot?.localities) ? snapshot.localities : [];
  const cities = places
    .filter(finite)
    .map((place) => ({ ...place, remainder: place.jobs - (place.officeJobs || 0) - (place.localityJobs || 0) }))
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
  const areas = localities
    .filter((locality) => finite(locality) && locality.jobs > 0)
    .map((locality) => ({
      type: "Feature",
      geometry: { type: "Point", coordinates: [locality.lng, locality.lat] },
      properties: { id: locality.id, kind: "locality", placeId: locality.placeId, name: locality.name, jobs: locality.jobs },
    }));
  return { type: "FeatureCollection", features: [...cities, ...pins, ...areas] };
}
