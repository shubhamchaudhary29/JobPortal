import MapJobCard from "./MapJobCard";

export function SkeletonCards({ count = 4 }) {
  return (
    <div className="space-y-3" aria-hidden="true">
      {Array.from({ length: count }, (_, index) => <div key={index} className="h-28 animate-pulse rounded-2xl bg-slate-200" />)}
    </div>
  );
}

export function PanelError({ message, onRetry }) {
  return (
    <div role="alert" className="rounded-3xl border border-red-100 bg-white px-5 py-8 text-center">
      <p className="font-medium text-slate-600">{message}</p>
      <button type="button" onClick={onRetry}
        className="mt-4 rounded-lg bg-indigo-600 px-5 py-2 text-sm font-semibold text-white hover:bg-indigo-700">
        Retry
      </button>
    </div>
  );
}

const errorMessage = (error) => error?.kind === "unavailable"
  ? "Job lists are still warming up. Please try again in a few seconds."
  : "We couldn't load these jobs right now.";

/** Cards for a {@link useJobList} result, with skeletons, errors, an empty state and "Load more". */
export default function MapJobList({ list, empty, distanceOf, label }) {
  if (list.status === "idle") return null;
  if (list.status === "loading") return <SkeletonCards />;
  if (list.status === "error" && list.items.length === 0) {
    return <PanelError message={errorMessage(list.error)} onRetry={list.retry} />;
  }
  if (list.items.length === 0) return empty;

  return (
    <div>
      <ul className="space-y-3" aria-label={label}>
        {list.items.map((job) => (
          <li key={job.id}><MapJobCard job={job} distanceKm={distanceOf?.(job)} /></li>
        ))}
      </ul>
      {list.status === "error" && (
        <div className="mt-4"><PanelError message={errorMessage(list.error)} onRetry={list.retry} /></div>
      )}
      {list.hasNext && list.status !== "error" && (
        <button type="button" onClick={list.loadMore} disabled={list.status === "loadingMore"}
          className="mt-4 w-full rounded-xl border border-slate-200 bg-white py-2.5 text-sm font-semibold text-indigo-600 hover:bg-indigo-50 disabled:opacity-60">
          {list.status === "loadingMore" ? "Loading…" : "Load more"}
        </button>
      )}
    </div>
  );
}
