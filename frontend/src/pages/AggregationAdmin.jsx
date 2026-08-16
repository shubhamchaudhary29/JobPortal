import { useCallback, useEffect, useState } from "react";
import Header from "../components/Header";
import Footer from "../components/Footer";
import {
  aggregationAdminError,
  getAggregationConflicts,
  getAggregationStatus,
  getSyncHistory,
  getSyncRun,
  resolveAggregationConflict,
  startAggregationSync,
} from "../services/aggregation-admin-service";

const EMPTY_PAGE = { content: [], page: 0, totalPages: 0, totalElements: 0, first: true, last: true };
const OUTCOME_CLASS = {
  COMPLETED: "bg-emerald-100 text-emerald-800",
  PARTIAL: "bg-amber-100 text-amber-800",
  FAILED: "bg-red-100 text-red-800",
  LOCKED: "bg-violet-100 text-violet-800",
  LEASE_LOST: "bg-orange-100 text-orange-800",
  RUNNING: "bg-blue-100 text-blue-800",
};

function Outcome({ value }) {
  return <span className={`rounded-full px-2 py-1 text-xs font-bold ${OUTCOME_CLASS[value] || "bg-slate-100 text-slate-700"}`}>{value || "UNKNOWN"}</span>;
}

function dateTime(value) {
  if (!value) return "—";
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? "—" : parsed.toLocaleString();
}

function PageButtons({ page, label, onChange }) {
  if (!page || page.totalPages <= 1) return null;
  return (
    <nav aria-label={`${label} pagination`} className="mt-4 flex items-center justify-end gap-3">
      <button className="rounded-lg border px-3 py-1.5 disabled:opacity-40" disabled={page.first} onClick={() => onChange(page.page - 1)}>Previous</button>
      <span className="text-sm text-slate-600">Page {page.page + 1} of {page.totalPages}</span>
      <button className="rounded-lg border px-3 py-1.5 disabled:opacity-40" disabled={page.last} onClick={() => onChange(page.page + 1)}>Next</button>
    </nav>
  );
}

export default function AggregationAdmin() {
  const [status, setStatus] = useState(null);
  const [history, setHistory] = useState(EMPTY_PAGE);
  const [conflicts, setConflicts] = useState(EMPTY_PAGE);
  const [historyPage, setHistoryPage] = useState(0);
  const [conflictPage, setConflictPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [pageError, setPageError] = useState("");
  const [notice, setNotice] = useState("");
  const [selectedRun, setSelectedRun] = useState(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [provider, setProvider] = useState("adzuna");
  const [employer, setEmployer] = useState("");
  const [syncing, setSyncing] = useState(false);
  const [resolving, setResolving] = useState(null);
  const [choices, setChoices] = useState({});

  const load = useCallback(async () => {
    setLoading(true);
    setPageError("");
    try {
      const [nextStatus, nextHistory, nextConflicts] = await Promise.all([
        getAggregationStatus(),
        getSyncHistory({ page: historyPage, size: 20 }),
        getAggregationConflicts({ status: "OPEN", page: conflictPage, size: 20 }),
      ]);
      setStatus(nextStatus);
      setHistory(nextHistory);
      setConflicts(nextConflicts);
    } catch (error) {
      setPageError(aggregationAdminError(error, "Could not load aggregation operations."));
    } finally {
      setLoading(false);
    }
  }, [conflictPage, historyPage]);

  useEffect(() => { load(); }, [load]);

  const startSync = async (event) => {
    event.preventDefault();
    if (syncing) return;
    setSyncing(true);
    setNotice("");
    try {
      const result = await startAggregationSync(provider, provider === "adzuna" ? null : employer);
      setNotice(`Synchronization ${result.outcome || "COMPLETED"}${result.runId ? ` · run ${result.runId}` : ""}`);
      await load();
    } catch (error) {
      setNotice(aggregationAdminError(error));
    } finally {
      setSyncing(false);
    }
  };

  const showRun = async (runId) => {
    setDetailLoading(true);
    setNotice("");
    try { setSelectedRun(await getSyncRun(runId)); }
    catch (error) { setNotice(aggregationAdminError(error, "Could not load the run detail.")); }
    finally { setDetailLoading(false); }
  };

  const updateChoice = (conflictId, key, value) => {
    setChoices((current) => ({ ...current, [conflictId]: { ...current[conflictId], [key]: value } }));
  };

  const resolve = async (conflict) => {
    if (resolving) return;
    const selected = choices[conflict.id] || {};
    if (!selected.canonical || !selected.duplicate || selected.canonical === selected.duplicate) {
      setNotice("Choose two distinct jobs before resolving a conflict.");
      return;
    }
    setResolving(conflict.id);
    setNotice("");
    try {
      await resolveAggregationConflict(conflict.id, selected.canonical, selected.duplicate);
      setNotice(`Conflict ${conflict.id} resolved.`);
      await load();
    } catch (error) {
      setNotice(aggregationAdminError(error, "The conflict could not be resolved safely."));
    } finally {
      setResolving(null);
    }
  };

  return (
    <div className="min-h-screen bg-slate-50 flex flex-col">
      <Header />
      <main className="flex-1 w-full max-w-7xl mx-auto px-4 py-8 space-y-8">
        <div>
          <p className="text-xs font-bold uppercase tracking-widest text-indigo-600">Administrator</p>
          <h1 className="text-3xl font-extrabold text-slate-900">Aggregation operations</h1>
          <p className="mt-1 text-slate-600">Monitor provider health, run bounded synchronization, and reconcile retained conflicts.</p>
        </div>

        {loading && <div role="status" className="rounded-2xl border bg-white p-6 text-slate-600">Loading aggregation operations…</div>}
        {pageError && !loading && (
          <div role="alert" className="rounded-2xl border border-red-200 bg-red-50 p-5 text-red-800">
            <p>{pageError}</p>
            <button onClick={load} className="mt-3 rounded-lg bg-red-700 px-4 py-2 font-bold text-white">Retry</button>
          </div>
        )}
        {notice && <div role="status" className="rounded-xl border border-indigo-200 bg-indigo-50 p-4 text-indigo-900">{notice}</div>}

        {!loading && !pageError && (
          <>
            <section aria-labelledby="summary-heading" className="grid gap-4 md:grid-cols-3">
              <h2 id="summary-heading" className="sr-only">Imported listing summary</h2>
              <div className="rounded-2xl border bg-white p-5"><p className="text-sm text-slate-500">Active imported jobs</p><p className="text-3xl font-extrabold">{status?.activeImportedJobs ?? 0}</p></div>
              <div className="rounded-2xl border bg-white p-5"><p className="text-sm text-slate-500">Inactive imported jobs</p><p className="text-3xl font-extrabold">{status?.inactiveImportedJobs ?? 0}</p></div>
              <div className="rounded-2xl border bg-white p-5"><p className="text-sm text-slate-500">Open conflicts</p><p className="text-3xl font-extrabold">{conflicts.totalElements ?? 0}</p></div>
            </section>

            <section aria-labelledby="sync-heading" className="rounded-2xl border bg-white p-6">
              <h2 id="sync-heading" className="text-xl font-extrabold">Manual synchronization</h2>
              <form onSubmit={startSync} className="mt-4 grid gap-4 md:grid-cols-[1fr_2fr_auto] md:items-end">
                <label className="text-sm font-bold text-slate-700">Provider
                  <select aria-label="Provider" value={provider} onChange={(event) => { setProvider(event.target.value); setEmployer(""); }} className="mt-1 block w-full rounded-lg border p-2.5">
                    <option value="adzuna">Adzuna</option><option value="greenhouse">Greenhouse</option><option value="lever">Lever</option>
                  </select>
                </label>
                <label className="text-sm font-bold text-slate-700">Employer board (optional)
                  <input aria-label="Employer board" value={employer} onChange={(event) => setEmployer(event.target.value)} disabled={provider === "adzuna"} maxLength={100} className="mt-1 block w-full rounded-lg border p-2.5 disabled:bg-slate-100" placeholder="Provider-wide when empty" />
                </label>
                <button disabled={syncing} className="rounded-lg bg-indigo-600 px-5 py-2.5 font-bold text-white disabled:opacity-50">{syncing ? "Synchronizing…" : "Start sync"}</button>
              </form>
            </section>

            <section aria-labelledby="counts-heading" className="rounded-2xl border bg-white p-6 overflow-x-auto">
              <h2 id="counts-heading" className="text-xl font-extrabold">Provider and company counts</h2>
              {(status?.providerCompanyCounts || []).length === 0 ? <p className="mt-4 text-slate-500">No imported provider listings yet.</p> : (
                <table className="mt-4 w-full text-left text-sm"><caption className="sr-only">Imported source listing counts</caption><thead><tr className="border-b"><th className="py-2">Provider</th><th>Board</th><th>Company</th><th>Active</th><th>Inactive</th></tr></thead><tbody>
                  {status.providerCompanyCounts.map((row) => <tr className="border-b last:border-0" key={`${row.provider}:${row.employer}:${row.company}`}><td className="py-2 font-bold">{row.provider}</td><td>{row.employer || "—"}</td><td>{row.company || "—"}</td><td>{row.activeListings}</td><td>{row.inactiveListings}</td></tr>)}
                </tbody></table>
              )}
            </section>

            <section aria-labelledby="latest-heading" className="rounded-2xl border bg-white p-6">
              <h2 id="latest-heading" className="text-xl font-extrabold">Latest provider health</h2>
              {(status?.latestRuns || []).length === 0 ? <p className="mt-4 text-slate-500">No synchronization runs have been recorded.</p> : (
                <div className="mt-4 grid gap-3 md:grid-cols-2 lg:grid-cols-3">{status.latestRuns.map((run) => <button key={run.runId} onClick={() => showRun(run.runId)} className="rounded-xl border p-4 text-left hover:border-indigo-400"><div className="flex justify-between gap-2"><strong>{run.provider}{run.employer ? ` · ${run.employer}` : ""}</strong><Outcome value={run.outcome} /></div><p className="mt-2 text-xs text-slate-500">{dateTime(run.startedAt)}</p></button>)}</div>
              )}
            </section>

            <section aria-labelledby="history-heading" className="rounded-2xl border bg-white p-6 overflow-x-auto">
              <h2 id="history-heading" className="text-xl font-extrabold">Synchronization history</h2>
              {history.content.length === 0 ? <p className="mt-4 text-slate-500">No history matches this page.</p> : (
                <table className="mt-4 w-full text-left text-sm"><caption className="sr-only">Paginated synchronization history</caption><thead><tr className="border-b"><th className="py-2">Started</th><th>Scope</th><th>Trigger</th><th>Outcome</th><th>Counts</th><th></th></tr></thead><tbody>
                  {history.content.map((run) => <tr key={run.runId} className="border-b last:border-0"><td className="py-3">{dateTime(run.startedAt)}</td><td>{run.provider}{run.employer ? ` / ${run.employer}` : ""}</td><td>{run.trigger}</td><td><Outcome value={run.outcome} /></td><td>{run.inserted} new · {run.updated} updated · {run.rejected} rejected</td><td><button className="font-bold text-indigo-700" onClick={() => showRun(run.runId)}>View detail</button></td></tr>)}
                </tbody></table>
              )}
              <PageButtons page={history} label="History" onChange={setHistoryPage} />
            </section>

            {(detailLoading || selectedRun) && <section aria-labelledby="detail-heading" className="rounded-2xl border border-indigo-200 bg-indigo-50 p-6"><h2 id="detail-heading" className="text-xl font-extrabold">Run detail</h2>{detailLoading ? <p role="status">Loading run detail…</p> : <div className="mt-3 grid gap-2 text-sm md:grid-cols-3"><p><strong>Run:</strong> {selectedRun.runId}</p><p><strong>Outcome:</strong> {selectedRun.outcome}</p><p><strong>Retries:</strong> {selectedRun.retries}</p><p><strong>Failures:</strong> {selectedRun.failedItems + selectedRun.failedBatches + selectedRun.failedEmployers}</p><p className="md:col-span-2"><strong>Failure:</strong> {selectedRun.failureDetail || "None"}</p></div>}</section>}

            <section aria-labelledby="conflicts-heading" className="rounded-2xl border bg-white p-6">
              <h2 id="conflicts-heading" className="text-xl font-extrabold">Open identity conflicts</h2>
              {conflicts.content.length === 0 ? <p className="mt-4 text-slate-500">No open conflicts require review.</p> : <div className="mt-4 space-y-4">{conflicts.content.map((conflict) => {
                const jobIds = Array.from(conflict.jobIds || []);
                return <article key={conflict.id} className="rounded-xl border p-4"><div className="flex flex-wrap justify-between gap-2"><div><h3 className="font-bold">{conflict.type}</h3><p className="text-xs text-slate-500">Observed {conflict.occurrences} time(s) · {dateTime(conflict.lastObservedAt)}</p></div><Outcome value={conflict.status} /></div><div className="mt-3 grid gap-3 md:grid-cols-2"><label className="text-sm font-bold">Canonical job<select aria-label={`Canonical job for ${conflict.id}`} className="mt-1 block w-full rounded-lg border p-2" value={choices[conflict.id]?.canonical || ""} onChange={(event) => updateChoice(conflict.id, "canonical", event.target.value)}><option value="">Choose canonical</option>{jobIds.map((id) => <option key={id} value={id}>{id}</option>)}</select></label><label className="text-sm font-bold">Duplicate job<select aria-label={`Duplicate job for ${conflict.id}`} className="mt-1 block w-full rounded-lg border p-2" value={choices[conflict.id]?.duplicate || ""} onChange={(event) => updateChoice(conflict.id, "duplicate", event.target.value)}><option value="">Choose duplicate</option>{jobIds.map((id) => <option key={id} value={id}>{id}</option>)}</select></label></div><button disabled={Boolean(resolving)} onClick={() => resolve(conflict)} className="mt-3 rounded-lg bg-slate-900 px-4 py-2 font-bold text-white disabled:opacity-50">{resolving === conflict.id ? "Resolving…" : "Resolve safely"}</button></article>;
              })}</div>}
              <PageButtons page={conflicts} label="Conflicts" onChange={setConflictPage} />
            </section>
          </>
        )}
      </main>
      <Footer />
    </div>
  );
}
