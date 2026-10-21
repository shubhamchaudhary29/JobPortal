import { useCallback, useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import Header from "../components/Header";
import Footer from "../components/Footer";
import OfficePinEditor from "../components/map/OfficePinEditor";
import { formatCount } from "../map/format";
import { getMapSnapshot } from "../services/map-service";
import {
  createOffice,
  getOfficeAudit,
  getOfficeCandidates,
  getOsmReport,
  listOffices,
  officeError,
  pickOsmOffice,
  rejectOsmOffice,
  updateOffice,
} from "../services/office-admin-service";

const EMPTY_PAGE = { content: [], page: 0, totalPages: 0, totalElements: 0, first: true, last: true };
const COORDINATE_DIGITS = 5;

function dateTime(value) {
  if (!value) return "—";
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? "—" : parsed.toLocaleString();
}

const coordinates = (pin) => `${pin.lat.toFixed(COORDINATE_DIGITS)}, ${pin.lng.toFixed(COORDINATE_DIGITS)}`;
const NO_SUGGESTIONS = Object.freeze([]);
const OSM_CAVEAT = "OpenStreetMap suggestions come from volunteer map data and can be wrong. An admin pin always wins, and "
  + "a disabled pin keeps OpenStreetMap off for that company and city.";
const VIEWS = [["pins", "Pins"], ["osm", "OpenStreetMap matches"]];
const matchCount = (count) => `${count} OpenStreetMap ${count === 1 ? "match" : "matches"}`;

function OsmLink({ url, children }) {
  return (
    <a href={url} target="_blank" rel="noopener noreferrer" className="font-bold text-indigo-700 underline-offset-2 hover:underline">
      {children}
    </a>
  );
}
const round = (value) => Number(value.toFixed(6));

/**
 * ADMIN office pins: a worklist of (company, city) pairs with the most jobs and no office, a click-to-pin editor and
 * the existing offices. Pins apply to every provider's jobs for that company and city at the next snapshot refresh.
 * GEO-8D adds OpenStreetMap matches: suggested in the worklist and editor ("Use this office" / "Not this company") and
 * listed with the jobs they would move in the "OpenStreetMap matches" tab.
 */
export default function OfficeAdmin() {
  const [candidates, setCandidates] = useState([]);
  const [candidatesError, setCandidatesError] = useState("");
  const [cities, setCities] = useState(new Map());
  const [offices, setOffices] = useState(EMPTY_PAGE);
  const [officesError, setOfficesError] = useState("");
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState("");
  const [query, setQuery] = useState("");
  const [editor, setEditor] = useState(null);
  const [saveError, setSaveError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState("");
  const [history, setHistory] = useState(null);
  const [view, setView] = useState("pins");
  const [report, setReport] = useState(null);
  const [reportError, setReportError] = useState("");

  const loadCandidates = useCallback(async () => {
    setCandidatesError("");
    try {
      setCandidates(await getOfficeCandidates(50));
    } catch (error) {
      setCandidatesError(officeError(error, "Could not load the worklist."));
    }
  }, []);

  const loadOffices = useCallback(async () => {
    setLoading(true);
    setOfficesError("");
    const params = { page, size: 20 };
    if (query) params.q = query;
    try {
      setOffices(await listOffices(params));
    } catch (error) {
      setOfficesError(officeError(error, "Could not load offices."));
    } finally {
      setLoading(false);
    }
  }, [page, query]);

  const loadReport = useCallback(async () => {
    setReportError("");
    try {
      setReport(await getOsmReport());
    } catch (error) {
      setReportError(officeError(error, "Could not load the OpenStreetMap matches."));
    }
  }, []);

  useEffect(() => { loadCandidates(); }, [loadCandidates]);
  useEffect(() => { if (view === "osm") loadReport(); }, [view, loadReport]);
  useEffect(() => { loadOffices(); }, [loadOffices]);
  useEffect(() => {
    // City centres for the worklist come from the public snapshot; without it the editor starts on all of India.
    getMapSnapshot()
      .then((snapshot) => setCities(new Map((snapshot?.places ?? []).map((place) => [place.id, place]))))
      .catch(() => setCities(new Map()));
  }, []);

  const center = useMemo(() => {
    if (!editor) return null;
    if (editor.mode === "edit") return { lat: editor.original.lat, lng: editor.original.lng };
    const city = cities.get(editor.placeId);
    return city ? { lat: city.lat, lng: city.lng } : null;
  }, [editor, cities]);

  const startCandidate = (candidate) => {
    setSaveError("");
    setEditor({ mode: "create", company: candidate.company, label: "", placeId: candidate.placeId,
      placeName: candidate.placeName, pin: null, candidate });
  };

  const startNew = () => {
    setSaveError("");
    setEditor({ mode: "create", company: "", label: "", placeId: null, placeName: null, pin: null });
  };

  const startEdit = (office) => {
    setSaveError("");
    setEditor({ mode: "edit", id: office.id, company: office.company, label: office.label, placeId: office.placeId,
      placeName: office.placeName, pin: { lat: office.lat, lng: office.lng }, original: office });
  };

  const setPin = useCallback((lat, lng) => {
    setEditor((current) => (current ? { ...current, pin: { lat: round(lat), lng: round(lng) } } : current));
  }, []);

  const save = async (event) => {
    event.preventDefault();
    if (!editor?.pin || busy) return;
    setBusy("save");
    setSaveError("");
    try {
      const body = { label: editor.label.trim(), lat: editor.pin.lat, lng: editor.pin.lng };
      const saved = editor.mode === "create"
        ? await createOffice({ company: editor.company.trim(), ...body, ...(editor.placeId ? { placeId: editor.placeId } : {}) })
        : await updateOffice(editor.id, body);
      setEditor(null);
      setNotice(`Saved ${saved.company} · ${saved.label}${saved.placeName ? ` (${saved.placeName})` : ""}. The map updates within a minute.`);
      await Promise.all([loadOffices(), loadCandidates()]);
    } catch (error) {
      setSaveError(officeError(error));
    } finally {
      setBusy("");
    }
  };

  const toggle = async (office) => {
    if (busy) return;
    setBusy(`toggle-${office.id}`);
    setNotice("");
    try {
      const updated = await updateOffice(office.id, { enabled: !office.enabled });
      setNotice(`${updated.company} · ${updated.label} is now ${updated.enabled ? "enabled" : "disabled"}.`);
      await Promise.all([loadOffices(), loadCandidates()]);
    } catch (error) {
      setNotice(officeError(error));
    } finally {
      setBusy("");
    }
  };

  const showHistory = async (office) => {
    if (busy) return;
    setBusy(`history-${office.id}`);
    try {
      const audit = await getOfficeAudit(office.id);
      setHistory({ office, records: audit.content || [] });
    } catch (error) {
      setNotice(officeError(error));
    } finally {
      setBusy("");
    }
  };

  const pickOsm = async (match) => {
    const candidate = editor?.candidate;
    if (!candidate || busy) return;
    setBusy(`pick-${match.osmRef}`);
    setSaveError("");
    try {
      const label = editor.label.trim();
      const saved = await pickOsmOffice(match.osmRef, { companyKey: candidate.companyKey, placeId: candidate.placeId,
        ...(label ? { label } : {}) });
      setEditor(null);
      setNotice(`Saved ${saved.company} · ${saved.label} (${saved.placeName}) from OpenStreetMap. The map updates within a minute.`);
      await Promise.all([loadOffices(), loadCandidates(), ...(report ? [loadReport()] : [])]);
    } catch (error) {
      setSaveError(officeError(error));
    } finally {
      setBusy("");
    }
  };

  const rejectOsm = async (pair, osmRef, name) => {
    if (busy) return;
    setBusy(`reject-${osmRef}`);
    setNotice("");
    try {
      await rejectOsmOffice(osmRef, { companyKey: pair.companyKey, placeId: pair.placeId });
      setNotice(`${name} will not be used for ${pair.company} in ${pair.placeName}.`);
      setEditor((current) => (current?.candidate ? { ...current, candidate: { ...current.candidate,
        osmCandidates: current.candidate.osmCandidates.filter((item) => item.osmRef !== osmRef) } } : current));
      await Promise.all([loadCandidates(), ...(report ? [loadReport()] : [])]);
    } catch (error) {
      setNotice(officeError(error));
    } finally {
      setBusy("");
    }
  };

  const suggestions = editor?.candidate?.osmCandidates ?? NO_SUGGESTIONS;
  const canSave = editor?.pin && editor.label.trim().length >= 2 && editor.company.trim() && !busy;

  return (
    <div className="min-h-screen bg-slate-50 flex flex-col">
      <Header />
      <main className="flex-1 w-full max-w-7xl mx-auto px-4 py-8 space-y-6">
        <div className="flex flex-wrap items-end justify-between gap-3">
          <div>
            <p className="text-xs font-bold uppercase tracking-widest text-indigo-600">Administrator</p>
            <h1 className="text-3xl font-extrabold text-slate-900">Office pins</h1>
            <p className="mt-1 text-slate-600">Pin a company's office once; its jobs in that city move from the city centre to the pin. Changes are audited.</p>
          </div>
          <Link to="/admin/aggregation" className="font-bold text-indigo-700">← Aggregation operations</Link>
        </div>

        {notice && <div role="status" className="rounded-xl border border-indigo-200 bg-indigo-50 p-4 text-indigo-900">{notice}</div>}

        <div role="tablist" aria-label="Office views" className="flex gap-2">
          {VIEWS.map(([id, label]) => (
            <button key={id} type="button" role="tab" id={`office-view-${id}`} aria-selected={view === id}
              aria-controls={`office-panel-${id}`} onClick={() => setView(id)}
              className={`rounded-full px-4 py-2 text-sm font-bold ${view === id ? "bg-indigo-600 text-white" : "bg-white text-slate-700 ring-1 ring-slate-200"}`}>
              {label}
            </button>
          ))}
        </div>

        {view === "osm" && (
          <section id="office-panel-osm" role="tabpanel" aria-labelledby="office-view-osm" className="rounded-2xl border bg-white p-6 overflow-x-auto">
            <h2 className="text-xl font-extrabold">OpenStreetMap matches</h2>
            <p className="mt-1 text-sm text-slate-600">{OSM_CAVEAT}</p>
            {reportError ? (
              <div role="alert" className="mt-4 rounded-xl border border-red-200 bg-red-50 p-4 text-red-800">
                <p>{reportError}</p>
                <button type="button" onClick={loadReport} className="mt-2 rounded-lg bg-red-700 px-3 py-1.5 font-bold text-white">Retry</button>
              </div>
            ) : !report ? (
              <p role="status" className="mt-4 text-slate-600">Loading matches…</p>
            ) : (
              <>
                <p className="mt-4 rounded-xl bg-slate-50 p-3 text-sm text-slate-700">
                  {report.autoApply
                    ? "Auto-apply is on: these matches show as offices on the map."
                    : "Auto-apply is off: nothing below is on the map yet. Review it, then set GEO_OSM_OFFICE_AUTO_APPLY=true."}
                  {" "}{formatCount(report.jobsMovedByAutoApply)} jobs {report.autoApply ? "are" : "would be"} placed at an
                  OpenStreetMap office. Company and city pairs without a pin: {formatCount(report.oneMatch.pairs)} with one match,
                  {" "}{formatCount(report.severalMatches.pairs)} with several (never applied), {formatCount(report.noMatch.pairs)} with none.
                </p>
                {report.topMatches.length === 0 ? <p className="mt-4 text-slate-500">No matches to review.</p> : (
                  <table className="mt-4 w-full text-left text-sm">
                    <caption className="sr-only">OpenStreetMap office matches by jobs</caption>
                    <thead><tr className="border-b"><th className="py-2">Company</th><th>City</th><th>OpenStreetMap object</th><th className="text-right">Jobs</th><th><span className="sr-only">Action</span></th></tr></thead>
                    <tbody>
                      {report.topMatches.map((match) => (
                        <tr key={`${match.companyKey}-${match.placeId}`} className="border-b last:border-0">
                          <td className="py-2.5 font-bold">{match.company}</td>
                          <td>{match.placeName}</td>
                          <td><OsmLink url={match.url}>{match.osmName}</OsmLink></td>
                          <td className="text-right">{formatCount(match.jobs)}</td>
                          <td className="pl-3 text-right">
                            <button type="button" disabled={Boolean(busy)} onClick={() => rejectOsm(match, match.osmRef, match.osmName)}
                              aria-label={`Reject ${match.osmName} for ${match.company} in ${match.placeName}`}
                              className="font-bold text-red-700 disabled:opacity-40">Reject</button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </>
            )}
          </section>
        )}

        {view === "pins" && (
        <div id="office-panel-pins" role="tabpanel" aria-labelledby="office-view-pins" className="space-y-6">
        <div className="grid gap-6 lg:grid-cols-[minmax(0,2fr)_minmax(0,3fr)]">
          <section aria-labelledby="worklist-heading" className="rounded-2xl border bg-white p-6">
            <div className="flex items-center justify-between gap-3">
              <h2 id="worklist-heading" className="text-xl font-extrabold">Worklist</h2>
              <button type="button" onClick={startNew} className="text-sm font-bold text-indigo-700">New office</button>
            </div>
            <p className="mt-1 text-sm text-slate-500">Companies with the most jobs in a city and no office there.</p>
            {candidatesError ? (
              <div role="alert" className="mt-4 rounded-xl border border-red-200 bg-red-50 p-4 text-red-800">
                <p>{candidatesError}</p>
                <button type="button" onClick={loadCandidates} className="mt-2 rounded-lg bg-red-700 px-3 py-1.5 font-bold text-white">Retry</button>
              </div>
            ) : candidates.length === 0 ? (
              <p className="mt-4 text-slate-500">Nothing to pin right now.</p>
            ) : (
              <div className="mt-4 max-h-[32rem] overflow-y-auto">
                <table className="w-full text-left text-sm">
                  <caption className="sr-only">Office pin candidates</caption>
                  <thead><tr className="border-b"><th className="py-2">Company</th><th>City</th><th className="text-right">Jobs</th><th><span className="sr-only">Action</span></th></tr></thead>
                  <tbody>
                    {candidates.map((candidate) => (
                      <tr key={`${candidate.companyKey}-${candidate.placeId}`} className="border-b last:border-0">
                        <td className="py-2.5">
                          <span className="font-bold">{candidate.company}</span>
                          {candidate.osmCandidates?.length > 0 && (
                            <span className="block text-xs text-slate-500">
                              {matchCount(candidate.osmCandidates.length)}
                              {candidate.autoApplied && <span className="ml-1 rounded bg-indigo-50 px-1.5 py-0.5 font-bold text-indigo-700">Auto-applied</span>}
                            </span>
                          )}
                        </td>
                        <td>{candidate.placeName}</td>
                        <td className="text-right">{formatCount(candidate.jobs)}</td>
                        <td className="pl-3 text-right">
                          <button type="button" onClick={() => startCandidate(candidate)}
                            aria-label={`Pin office for ${candidate.company} in ${candidate.placeName}`}
                            className="whitespace-nowrap font-bold text-indigo-700">Pin office</button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          <section aria-labelledby="editor-heading" className="rounded-2xl border bg-white p-6">
            <h2 id="editor-heading" className="text-xl font-extrabold">
              {!editor ? "Editor" : editor.mode === "create" ? "Pin a new office" : `Edit ${editor.company} · ${editor.original.label}`}
            </h2>
            {!editor ? (
              <p className="mt-2 text-slate-500">Choose "Pin office" in the worklist, "New office", or "Edit" on an existing office.</p>
            ) : (
              <form onSubmit={save} className="mt-4 space-y-4">
                <div className="grid gap-3 sm:grid-cols-2">
                  <label className="text-sm font-bold text-slate-700">Company
                    <input aria-label="Office company" required maxLength={200} value={editor.company} readOnly={editor.mode === "edit"}
                      onChange={(event) => setEditor({ ...editor, company: event.target.value })}
                      className="mt-1 block w-full rounded-lg border p-2.5 read-only:bg-slate-50" />
                  </label>
                  <label className="text-sm font-bold text-slate-700">Label
                    <input aria-label="Office label" required minLength={2} maxLength={80} placeholder="e.g. Bellandur campus" value={editor.label}
                      onChange={(event) => setEditor({ ...editor, label: event.target.value })}
                      className="mt-1 block w-full rounded-lg border p-2.5" />
                  </label>
                </div>
                <p className="text-sm text-slate-600">
                  City: <strong>{editor.placeName || "nearest large city to the pin"}</strong>
                  {" · "}Pin: <strong data-testid="pin-coordinates">{editor.pin ? coordinates(editor.pin) : "click the map to drop it"}</strong>
                </p>
                <OfficePinEditor center={center} pin={editor.pin} onPin={setPin} suggestions={suggestions}
                  className="h-80 rounded-2xl ring-1 ring-slate-200 bg-slate-100" />
                {suggestions.length > 0 && (
                  <div className="rounded-xl border border-slate-200 bg-slate-50 p-4">
                    <h3 className="text-sm font-extrabold text-slate-900">{matchCount(suggestions.length)} (grey markers)</h3>
                    <p className="mt-1 text-xs text-slate-600">{OSM_CAVEAT}</p>
                    <ul className="mt-3 space-y-2 text-sm" aria-label="OpenStreetMap matches">
                      {suggestions.map((match) => (
                        <li key={match.osmRef} className="flex flex-wrap items-center justify-between gap-2 rounded-lg bg-white p-2.5 ring-1 ring-slate-200">
                          <span>
                            <OsmLink url={match.url}>{match.name}</OsmLink>
                            <span className="block text-xs text-slate-500">{match.distanceKm} km from the city centre</span>
                          </span>
                          <span className="flex gap-2">
                            <button type="button" disabled={Boolean(busy)} onClick={() => pickOsm(match)}
                              aria-label={`Use ${match.name} as the office`}
                              className="rounded-lg bg-indigo-600 px-3 py-1.5 font-bold text-white disabled:opacity-50">Use this office</button>
                            <button type="button" disabled={Boolean(busy)}
                              onClick={() => rejectOsm(editor.candidate, match.osmRef, match.name)}
                              aria-label={`${match.name} is not this company`}
                              className="rounded-lg border px-3 py-1.5 font-bold text-slate-700 disabled:opacity-50">Not this company</button>
                          </span>
                        </li>
                      ))}
                    </ul>
                  </div>
                )}
                {saveError && <p role="alert" className="rounded-xl border border-red-200 bg-red-50 p-3 text-sm text-red-800">{saveError}</p>}
                <div className="flex gap-2">
                  <button disabled={!canSave} className="rounded-lg bg-indigo-600 px-5 py-2.5 font-bold text-white disabled:opacity-50">
                    {busy === "save" ? "Saving…" : "Save"}
                  </button>
                  <button type="button" onClick={() => setEditor(null)} className="rounded-lg border px-4 py-2.5 font-bold">Cancel</button>
                </div>
              </form>
            )}
          </section>
        </div>

        <section aria-labelledby="offices-heading" className="rounded-2xl border bg-white p-6 overflow-x-auto">
          <div className="flex flex-wrap items-end justify-between gap-3">
            <h2 id="offices-heading" className="text-xl font-extrabold">Offices</h2>
            <form onSubmit={(event) => { event.preventDefault(); setPage(0); setQuery(search.trim()); }} className="flex items-end gap-2">
              <label className="text-sm font-bold text-slate-700">Search
                <input aria-label="Search offices" maxLength={100} value={search} onChange={(event) => setSearch(event.target.value)}
                  className="mt-1 block w-full rounded-lg border p-2.5" />
              </label>
              <button className="rounded-lg border px-4 py-2.5 font-bold">Search</button>
            </form>
          </div>
          {loading && <p role="status" className="mt-4 text-slate-600">Loading offices…</p>}
          {officesError && !loading && (
            <div role="alert" className="mt-4 rounded-xl border border-red-200 bg-red-50 p-4 text-red-800">
              <p>{officesError}</p>
              <button type="button" onClick={loadOffices} className="mt-2 rounded-lg bg-red-700 px-3 py-1.5 font-bold text-white">Retry</button>
            </div>
          )}
          {!loading && !officesError && offices.content.length === 0 && <p className="mt-4 text-slate-500">No offices yet.</p>}
          {!loading && !officesError && offices.content.length > 0 && (
            <table className="mt-4 w-full text-left text-sm">
              <caption className="sr-only">Pinned offices</caption>
              <thead><tr className="border-b"><th className="py-2">Company</th><th>Office</th><th>City</th><th>Pin</th><th>Status</th><th>Last change</th><th>Actions</th></tr></thead>
              <tbody>
                {offices.content.map((office) => (
                  <tr key={office.id} className="border-b align-top last:border-0">
                    <td className="py-3 font-bold">{office.company}</td>
                    <td>{office.label}</td>
                    <td>{office.placeName || office.placeId}</td>
                    <td className="font-mono text-xs">{coordinates(office)}</td>
                    <td>{office.enabled ? <span className="font-bold text-emerald-700">Enabled</span> : <span className="font-bold text-slate-500">Disabled</span>}</td>
                    <td className="text-xs text-slate-500">{office.updatedBy || "—"}<br />{dateTime(office.updatedAt)}</td>
                    <td className="space-x-2 whitespace-nowrap">
                      <button type="button" disabled={Boolean(busy)} onClick={() => startEdit(office)} className="font-bold text-indigo-700 disabled:opacity-40">Edit</button>
                      <button type="button" disabled={Boolean(busy)} onClick={() => toggle(office)}
                        aria-label={`${office.enabled ? "Disable" : "Enable"} ${office.company} ${office.label}`}
                        className="font-bold text-indigo-700 disabled:opacity-40">{office.enabled ? "Disable" : "Enable"}</button>
                      <button type="button" disabled={Boolean(busy)} onClick={() => showHistory(office)} className="font-bold text-indigo-700 disabled:opacity-40">History</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {offices.totalPages > 1 && (
            <nav aria-label="Office pagination" className="mt-4 flex items-center justify-end gap-3">
              <button className="rounded-lg border px-3 py-1.5 disabled:opacity-40" disabled={offices.first} onClick={() => setPage(offices.page - 1)}>Previous</button>
              <span className="text-sm text-slate-600">Page {offices.page + 1} of {offices.totalPages}</span>
              <button className="rounded-lg border px-3 py-1.5 disabled:opacity-40" disabled={offices.last} onClick={() => setPage(offices.page + 1)}>Next</button>
            </nav>
          )}
        </section>

        </div>
        )}

        {history && (
          <section aria-labelledby="history-heading" className="rounded-2xl border border-indigo-200 bg-indigo-50 p-6">
            <div className="flex justify-between gap-3">
              <h2 id="history-heading" className="text-xl font-extrabold">History · {history.office.company} · {history.office.label}</h2>
              <button type="button" onClick={() => setHistory(null)} className="font-bold text-indigo-700">Close</button>
            </div>
            {history.records.length === 0 ? <p className="mt-3 text-slate-600">No recorded changes.</p> : (
              <ul className="mt-3 space-y-2 text-sm">
                {history.records.map((record) => (
                  <li key={record.id}>
                    <strong>{record.action}</strong> by {record.actor} · {dateTime(record.at)}
                    {record.after && (
                      <span className="block text-xs text-slate-600">
                        {record.after.label} · {Number(record.after.lat).toFixed(COORDINATE_DIGITS)}, {Number(record.after.lng).toFixed(COORDINATE_DIGITS)}
                        {record.after.enabled === false ? " · disabled" : ""}
                      </span>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </section>
        )}
      </main>
      <Footer />
    </div>
  );
}
