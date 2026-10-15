import { formatCount } from "../../map/format";

const number = new Intl.NumberFormat("en-IN");

export default function TopPlaces({ snapshot, places, onSelect }) {
  return (
    <div>
      <p className="text-sm font-medium text-slate-500">
        {number.format(snapshot.placedJobs ?? 0)} jobs across {number.format(snapshot.places?.length ?? 0)} cities
      </p>
      <h2 className="mt-4 text-sm font-bold uppercase tracking-wide text-slate-400">Top places</h2>
      {places.length === 0 ? (
        <p className="mt-3 rounded-2xl border border-dashed border-slate-300 bg-white px-4 py-6 text-center text-sm text-slate-500">
          No city has openings on the map yet. Try the Remote or Anywhere in India tabs.
        </p>
      ) : (
        <ol className="mt-3 space-y-2">
          {places.map((place) => (
            <li key={place.id}>
              <button type="button" onClick={() => onSelect(place.id)}
                aria-label={`${place.name}${place.state ? `, ${place.state}` : ""}, ${formatCount(place.jobs)} jobs`}
                className="flex w-full items-center justify-between gap-3 rounded-2xl border border-slate-100 bg-white px-4 py-3 text-left shadow-sm transition hover:border-indigo-100 hover:shadow-md focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500">
                <span className="min-w-0">
                  <span className="block truncate font-semibold text-slate-900">{place.name}</span>
                  {place.state && <span className="block truncate text-xs text-slate-500">{place.state}</span>}
                </span>
                <span className="shrink-0 rounded-full bg-indigo-50 px-2.5 py-1 text-xs font-bold text-indigo-700">
                  {formatCount(place.jobs)} jobs
                </span>
              </button>
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}
