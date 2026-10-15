import Header from "../Header";

/** Suspense fallback for /jobs/map in the entry bundle: same layout as the page, no map code. */
export default function MapJobsSkeleton() {
  return (
    <div className="flex h-[100dvh] flex-col bg-slate-50">
      <Header />
      <main aria-busy="true" className="relative min-h-0 flex-1 lg:grid lg:grid-cols-[420px_minmax(0,1fr)] lg:gap-4 lg:p-4">
        <p className="sr-only" role="status">Loading map…</p>
        <div className="hidden space-y-3 rounded-3xl bg-white p-4 ring-1 ring-slate-200 lg:block">
          <div className="h-10 animate-pulse rounded-full bg-slate-200" />
          <div className="h-10 animate-pulse rounded-2xl bg-slate-200" />
          {[1, 2, 3, 4, 5].map((n) => <div key={n} className="h-16 animate-pulse rounded-2xl bg-slate-200" />)}
        </div>
        <div className="absolute inset-0 animate-pulse bg-slate-100 lg:static lg:rounded-3xl lg:ring-1 lg:ring-slate-200" />
      </main>
    </div>
  );
}
