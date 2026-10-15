export const MAP_TABS = Object.freeze([
  { id: "map", label: "On map" },
  { id: "remote", label: "Remote" },
  { id: "india", label: "Anywhere in India" },
]);

/** Job counts per tab: placed jobs, REMOTE, and STATE + COUNTRY (the "india" list). */
export function tabCounts(snapshot) {
  if (!snapshot) return {};
  const unplaced = snapshot.unplaced ?? {};
  return {
    map: snapshot.placedJobs ?? 0,
    remote: unplaced.REMOTE ?? 0,
    india: (unplaced.STATE ?? 0) + (unplaced.COUNTRY ?? 0),
  };
}

/** Places with the most jobs, biggest first. */
export function topPlaces(snapshot, count) {
  return [...(snapshot?.places ?? [])].sort((a, b) => b.jobs - a.jobs || a.name.localeCompare(b.name)).slice(0, count);
}
