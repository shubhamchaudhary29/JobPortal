import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import Header from "../components/Header";
import Footer from "../components/Footer";
import { getAggregationProviders } from "../services/aggregation-admin-service";
import {
  createRegistryEntry,
  getRegistryAudit,
  listRegistry,
  registryError,
  updateRegistryEntry,
  validateRegistryEntry,
} from "../services/employer-registry-service";

const EMPTY_PAGE = { content: [], page: 0, totalPages: 0, totalElements: 0, first: true, last: true };
const EMPTY_FORM = { provider: "", boardId: "", company: "", enabled: true };
const VALIDATION_CLASS = {
  ACTIVE: "bg-emerald-100 text-emerald-800",
  EMPTY: "bg-amber-100 text-amber-800",
  MALFORMED: "bg-red-100 text-red-800",
  INVALID: "bg-red-100 text-red-800",
  UNREACHABLE: "bg-orange-100 text-orange-800",
};

function dateTime(value) {
  if (!value) return "—";
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? "—" : parsed.toLocaleString();
}

export default function EmployerRegistryAdmin() {
  const [providers, setProviders] = useState([]);
  const [filters, setFilters] = useState({ provider: "", enabled: "", q: "" });
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);
  const [entries, setEntries] = useState(EMPTY_PAGE);
  const [loading, setLoading] = useState(true);
  const [pageError, setPageError] = useState("");
  const [notice, setNotice] = useState("");
  const [form, setForm] = useState(EMPTY_FORM);
  const [busy, setBusy] = useState("");
  const [editing, setEditing] = useState(null);
  const [validations, setValidations] = useState({});
  const [history, setHistory] = useState(null);

  useEffect(() => {
    getAggregationProviders()
      .then((list) => {
        const boards = (Array.isArray(list) ? list : []).filter((item) => item.employerScoped);
        setProviders(boards);
        setForm((current) => (current.provider ? current : { ...current, provider: boards[0]?.name || "" }));
      })
      .catch(() => setProviders([]));
  }, []);

  const load = useCallback(async () => {
    setLoading(true);
    setPageError("");
    const params = { page, size: 20 };
    if (filters.provider) params.provider = filters.provider;
    if (filters.enabled) params.enabled = filters.enabled;
    if (filters.q) params.q = filters.q;
    try {
      setEntries(await listRegistry(params));
    } catch (error) {
      setPageError(registryError(error, "Could not load the employer registry."));
    } finally {
      setLoading(false);
    }
  }, [filters, page]);

  useEffect(() => { load(); }, [load]);

  const changeFilter = (key, value) => {
    setPage(0);
    setFilters((current) => ({ ...current, [key]: value }));
  };

  const act = async (name, action, success) => {
    if (busy) return;
    setBusy(name);
    setNotice("");
    try {
      const result = await action();
      await success(result);
    } catch (error) {
      setNotice(registryError(error));
    } finally {
      setBusy("");
    }
  };

  const addEntry = (event) => {
    event.preventDefault();
    act("create", () => createRegistryEntry({ ...form, boardId: form.boardId.trim(), company: form.company.trim() }), async (created) => {
      setForm((current) => ({ ...EMPTY_FORM, provider: current.provider }));
      setNotice(`Added ${created.company} (${created.provider} · ${created.boardId}).`);
      await load();
    });
  };

  const saveEdit = (event) => {
    event.preventDefault();
    const changes = { company: editing.company.trim() };
    if (!editing.enabled) changes.disabledReason = editing.disabledReason?.trim() || "";
    act(`edit-${editing.id}`, () => updateRegistryEntry(editing.id, changes), async (updated) => {
      setEditing(null);
      setNotice(`Saved ${updated.company}.`);
      await load();
    });
  };

  const toggle = (entry) => {
    const reason = entry.enabled ? (editing?.id === entry.id ? editing.disabledReason : "") : undefined;
    const changes = entry.enabled ? { enabled: false, disabledReason: reason?.trim() || "Disabled by administrator" } : { enabled: true };
    act(`toggle-${entry.id}`, () => updateRegistryEntry(entry.id, changes), async (updated) => {
      setNotice(`${updated.company} is now ${updated.enabled ? "enabled" : "disabled"}.`);
      await load();
    });
  };

  const validate = (entry) => act(`validate-${entry.id}`, () => validateRegistryEntry(entry.id), async (result) => {
    setValidations((current) => ({ ...current, [entry.id]: result }));
  });

  const showHistory = (entry) => act(`history-${entry.id}`, () => getRegistryAudit(entry.id), async (audit) => {
    setHistory({ entry, records: audit.content || [] });
  });

  return (
    <div className="min-h-screen bg-slate-50 flex flex-col">
      <Header />
      <main className="flex-1 w-full max-w-7xl mx-auto px-4 py-8 space-y-6">
        <div className="flex flex-wrap items-end justify-between gap-3">
          <div>
            <p className="text-xs font-bold uppercase tracking-widest text-indigo-600">Administrator</p>
            <h1 className="text-3xl font-extrabold text-slate-900">Employer registry</h1>
            <p className="mt-1 text-slate-600">ATS boards that scheduled ingestion reads. Changes apply on the next run and are audited.</p>
          </div>
          <Link to="/admin/aggregation" className="font-bold text-indigo-700">← Aggregation operations</Link>
        </div>

        {notice && <div role="status" className="rounded-xl border border-indigo-200 bg-indigo-50 p-4 text-indigo-900">{notice}</div>}

        <section aria-labelledby="add-heading" className="rounded-2xl border bg-white p-6">
          <h2 id="add-heading" className="text-xl font-extrabold">Add a board</h2>
          <form onSubmit={addEntry} className="mt-4 grid gap-4 md:grid-cols-[1fr_1fr_2fr_auto_auto] md:items-end">
            <label className="text-sm font-bold text-slate-700">Provider
              <select aria-label="New board provider" value={form.provider} onChange={(event) => setForm({ ...form, provider: event.target.value })} className="mt-1 block w-full rounded-lg border p-2.5">
                {providers.map((item) => <option key={item.name} value={item.name}>{item.displayName}</option>)}
              </select>
            </label>
            <label className="text-sm font-bold text-slate-700">Board ID
              <input aria-label="New board ID" required maxLength={100} pattern="[A-Za-z0-9_-]+" value={form.boardId} onChange={(event) => setForm({ ...form, boardId: event.target.value })} className="mt-1 block w-full rounded-lg border p-2.5" />
            </label>
            <label className="text-sm font-bold text-slate-700">Company
              <input aria-label="New board company" required maxLength={200} value={form.company} onChange={(event) => setForm({ ...form, company: event.target.value })} className="mt-1 block w-full rounded-lg border p-2.5" />
            </label>
            <label className="flex items-center gap-2 text-sm font-bold text-slate-700">
              <input type="checkbox" checked={form.enabled} onChange={(event) => setForm({ ...form, enabled: event.target.checked })} /> Enabled
            </label>
            <button disabled={Boolean(busy) || !form.provider} className="rounded-lg bg-indigo-600 px-5 py-2.5 font-bold text-white disabled:opacity-50">{busy === "create" ? "Adding…" : "Add board"}</button>
          </form>
        </section>

        <section aria-labelledby="entries-heading" className="rounded-2xl border bg-white p-6 overflow-x-auto">
          <h2 id="entries-heading" className="text-xl font-extrabold">Boards</h2>
          <div className="mt-4 grid gap-3 md:grid-cols-[1fr_1fr_2fr]">
            <label className="text-sm font-bold text-slate-700">Filter provider
              <select aria-label="Filter provider" value={filters.provider} onChange={(event) => changeFilter("provider", event.target.value)} className="mt-1 block w-full rounded-lg border p-2.5">
                <option value="">All providers</option>
                {providers.map((item) => <option key={item.name} value={item.name}>{item.displayName}</option>)}
              </select>
            </label>
            <label className="text-sm font-bold text-slate-700">Filter status
              <select aria-label="Filter status" value={filters.enabled} onChange={(event) => changeFilter("enabled", event.target.value)} className="mt-1 block w-full rounded-lg border p-2.5">
                <option value="">Enabled and disabled</option>
                <option value="true">Enabled</option>
                <option value="false">Disabled</option>
              </select>
            </label>
            <form onSubmit={(event) => { event.preventDefault(); changeFilter("q", search.trim()); }} className="flex items-end gap-2">
              <label className="flex-1 text-sm font-bold text-slate-700">Search
                <input aria-label="Search company or board" maxLength={100} value={search} onChange={(event) => setSearch(event.target.value)} className="mt-1 block w-full rounded-lg border p-2.5" />
              </label>
              <button className="rounded-lg border px-4 py-2.5 font-bold">Search</button>
            </form>
          </div>

          {loading && <p role="status" className="mt-4 text-slate-600">Loading the employer registry…</p>}
          {pageError && !loading && (
            <div role="alert" className="mt-4 rounded-xl border border-red-200 bg-red-50 p-4 text-red-800">
              <p>{pageError}</p>
              <button onClick={load} className="mt-2 rounded-lg bg-red-700 px-3 py-1.5 font-bold text-white">Retry</button>
            </div>
          )}
          {!loading && !pageError && entries.content.length === 0 && <p className="mt-4 text-slate-500">No registry entries match these filters.</p>}
          {!loading && !pageError && entries.content.length > 0 && (
            <table className="mt-4 w-full text-left text-sm">
              <caption className="sr-only">Employer registry entries</caption>
              <thead><tr className="border-b"><th className="py-2">Provider</th><th>Board</th><th>Company</th><th>Status</th><th>Last change</th><th>Actions</th></tr></thead>
              <tbody>
                {entries.content.map((entry) => {
                  const validation = validations[entry.id];
                  const editingThis = editing?.id === entry.id;
                  return (
                    <tr key={entry.id} className="border-b align-top last:border-0">
                      <td className="py-3 font-bold">{entry.provider}</td>
                      <td>{entry.boardId}</td>
                      <td>
                        {editingThis ? (
                          <form onSubmit={saveEdit} className="space-y-2">
                            <input aria-label={`Company for ${entry.boardId}`} required maxLength={200} value={editing.company} onChange={(event) => setEditing({ ...editing, company: event.target.value })} className="block w-full rounded border p-1.5" />
                            {!entry.enabled && <input aria-label={`Disabled reason for ${entry.boardId}`} maxLength={300} value={editing.disabledReason || ""} onChange={(event) => setEditing({ ...editing, disabledReason: event.target.value })} className="block w-full rounded border p-1.5" />}
                            <div className="flex gap-2"><button disabled={Boolean(busy)} className="rounded bg-slate-900 px-3 py-1 font-bold text-white disabled:opacity-50">Save</button><button type="button" onClick={() => setEditing(null)} className="rounded border px-3 py-1">Cancel</button></div>
                          </form>
                        ) : entry.company}
                        {validation && <p role="status" className={`mt-2 inline-block rounded-full px-2 py-1 text-xs font-bold ${VALIDATION_CLASS[validation.classification] || "bg-slate-100"}`}>Validation: {validation.classification} · {validation.count} listings</p>}
                      </td>
                      <td>{entry.enabled ? <span className="font-bold text-emerald-700">Enabled</span> : <span><span className="font-bold text-slate-500">Disabled</span>{entry.disabledReason && <span className="block text-xs text-slate-500">{entry.disabledReason}</span>}</span>}</td>
                      <td className="text-xs text-slate-500">{entry.updatedBy || "—"}<br />{dateTime(entry.updatedAt)}</td>
                      <td className="space-x-2 whitespace-nowrap">
                        <button disabled={Boolean(busy)} onClick={() => setEditing({ id: entry.id, company: entry.company, enabled: entry.enabled, disabledReason: entry.disabledReason || "" })} className="font-bold text-indigo-700 disabled:opacity-40">Edit</button>
                        <button disabled={Boolean(busy)} onClick={() => toggle(entry)} className="font-bold text-indigo-700 disabled:opacity-40">{entry.enabled ? "Disable" : "Enable"}</button>
                        <button disabled={Boolean(busy)} onClick={() => validate(entry)} className="font-bold text-indigo-700 disabled:opacity-40">{busy === `validate-${entry.id}` ? "Validating…" : "Validate"}</button>
                        <button disabled={Boolean(busy)} onClick={() => showHistory(entry)} className="font-bold text-indigo-700 disabled:opacity-40">History</button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
          {entries.totalPages > 1 && (
            <nav aria-label="Registry pagination" className="mt-4 flex items-center justify-end gap-3">
              <button className="rounded-lg border px-3 py-1.5 disabled:opacity-40" disabled={entries.first} onClick={() => setPage(entries.page - 1)}>Previous</button>
              <span className="text-sm text-slate-600">Page {entries.page + 1} of {entries.totalPages}</span>
              <button className="rounded-lg border px-3 py-1.5 disabled:opacity-40" disabled={entries.last} onClick={() => setPage(entries.page + 1)}>Next</button>
            </nav>
          )}
        </section>

        {history && (
          <section aria-labelledby="history-heading" className="rounded-2xl border border-indigo-200 bg-indigo-50 p-6">
            <div className="flex justify-between gap-3"><h2 id="history-heading" className="text-xl font-extrabold">History · {history.entry.company}</h2><button onClick={() => setHistory(null)} className="font-bold text-indigo-700">Close</button></div>
            {history.records.length === 0 ? <p className="mt-3 text-slate-600">No recorded changes.</p> : (
              <ul className="mt-3 space-y-2 text-sm">
                {history.records.map((record) => <li key={record.id}><strong>{record.action}</strong> by {record.actor} · {dateTime(record.at)}</li>)}
              </ul>
            )}
          </section>
        )}
      </main>
      <Footer />
    </div>
  );
}
