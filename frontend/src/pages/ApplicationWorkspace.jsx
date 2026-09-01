import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import Header from "../components/Header";
import Footer from "../components/Footer";
import { getWorkspace, getWorkspaceAnalytics, removeSavedJob } from "../services/application-copilot-service";

const stages = ["", "SAVED", "PREPARING", "APPLIED", "OA", "INTERVIEW", "OFFER", "REJECTED", "WITHDRAWN"];

export default function ApplicationWorkspace() {
  const [data, setData] = useState(null);
  const [analytics, setAnalytics] = useState(null);
  const [stage, setStage] = useState("");
  const [search, setSearch] = useState("");
  const [query, setQuery] = useState("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const load = async () => {
    setLoading(true); setError("");
    try {
      const [workspace, metrics] = await Promise.all([
        getWorkspace({ stage: stage || undefined, search: query || undefined, size: 50 }), getWorkspaceAnalytics(),
      ]);
      setData(workspace); setAnalytics(metrics);
    } catch { setError("Application workspace could not be loaded."); }
    finally { setLoading(false); }
  };
  useEffect(() => { load(); }, [stage, query]); // eslint-disable-line react-hooks/exhaustive-deps

  const unsave = async (jobId) => {
    try { await removeSavedJob(jobId); await load(); }
    catch (failure) { setError(failure?.response?.data?.detail || "Only an unused saved job can be removed."); }
  };

  return <div className="min-h-screen bg-slate-50 flex flex-col"><Header /><main className="flex-1 max-w-7xl w-full mx-auto px-4 py-10">
    <div><p className="text-sm font-bold uppercase tracking-widest text-indigo-600">Candidate workspace</p><h1 className="text-3xl font-extrabold text-slate-900 mt-1">Application Dashboard</h1><p className="text-slate-500 mt-2">Prepare, track, and follow up without changing recruiter-controlled application statuses.</p></div>
    {analytics && <section className="mt-7 grid grid-cols-2 md:grid-cols-4 lg:grid-cols-8 gap-3" aria-label="Application analytics">{[
      ["Saved", analytics.saved], ["Preparing", analytics.preparing], ["Applied", analytics.applied], ["OA", analytics.onlineAssessments],
      ["Interviews", analytics.interviews], ["Offers", analytics.offers], ["Rejected", analytics.rejected], ["Response rate", analytics.responseRate == null ? "—" : `${analytics.responseRate}%`],
    ].map(([label, value]) => <div key={label} className="rounded-2xl border border-slate-200 bg-white p-4"><p className="text-xs font-bold uppercase text-slate-500">{label}</p><p className="text-2xl font-extrabold text-slate-900 mt-1">{value}</p></div>)}{analytics.message && <p className="col-span-full text-xs text-slate-500">{analytics.message}</p>}</section>}
    <form onSubmit={(event) => { event.preventDefault(); setQuery(search.trim()); }} className="mt-7 flex flex-col sm:flex-row gap-3"><input aria-label="Search applications" value={search} onChange={(event) => setSearch(event.target.value)} maxLength={100} placeholder="Search company or job" className="flex-1 rounded-xl border border-slate-300 bg-white px-4 py-3" /><select aria-label="Filter by stage" value={stage} onChange={(event) => setStage(event.target.value)} className="rounded-xl border border-slate-300 bg-white px-4 py-3">{stages.map((value) => <option key={value} value={value}>{value || "All stages"}</option>)}</select><button className="rounded-xl bg-slate-900 px-5 py-3 text-white font-bold">Search</button></form>
    {error && <p role="alert" className="mt-5 rounded-xl border border-rose-200 bg-rose-50 p-3 text-rose-800">{error}</p>}
    {loading ? <div className="mt-6 h-56 rounded-3xl bg-white animate-pulse" aria-label="Loading applications" /> : data?.content?.length === 0 ? <section className="mt-6 rounded-3xl border border-dashed border-slate-300 bg-white p-16 text-center"><h2 className="text-xl font-bold">No tracked applications</h2><p className="mt-2 text-slate-500">Save a job or start an application package from job details.</p><Link to="/jobs/matches" className="mt-5 inline-block rounded-xl bg-indigo-600 px-5 py-3 text-white font-bold">Browse matches</Link></section> : <section className="mt-6 grid gap-5 lg:grid-cols-2">{data.content.map((item) => <article key={item.jobId} className="rounded-3xl border border-slate-200 bg-white p-6"><div className="flex justify-between gap-4"><div><div className="flex flex-wrap gap-2"><span className="rounded-full bg-indigo-50 px-3 py-1 text-xs font-bold text-indigo-700">{item.stage}</span>{!item.active && <span className="rounded-full bg-rose-50 px-3 py-1 text-xs font-bold text-rose-700">No longer active</span>}{item.followUpStatus !== "NONE" && <span className={`rounded-full px-3 py-1 text-xs font-bold ${item.followUpStatus === "OVERDUE" ? "bg-amber-100 text-amber-900" : "bg-slate-100 text-slate-700"}`}>{item.followUpStatus} follow-up</span>}</div><h2 className="text-xl font-extrabold text-slate-900 mt-3">{item.job?.title || "Historical job"}</h2><p className="text-slate-500">{item.job?.company}</p></div><div className="text-right text-sm"><p><strong>{item.matchScore ?? "—"}%</strong> match</p><p><strong>{item.readiness?.readinessScore ?? "—"}%</strong> ready</p></div></div><div className="mt-5 grid grid-cols-2 gap-2 text-sm text-slate-600"><p>Resume: {item.resumeVersionId ? "Ready" : "Not created"}</p><p>Cover letter: {item.coverLetterVersionId ? "Ready" : "Not created"}</p><p>Updated: {item.updatedAt ? new Date(item.updatedAt).toLocaleDateString() : "—"}</p><p>Recruiter: {item.recruiterStatus || "Not in portal"}</p></div>{item.notes && <p className="mt-4 rounded-xl bg-slate-50 p-3 text-sm text-slate-600 line-clamp-2">{item.notes}</p>}<div className="mt-5 flex gap-2"><Link to={`/jobs/${item.jobId}/prepare`} className="rounded-xl bg-indigo-600 px-4 py-2 text-white font-bold">Open workspace</Link>{item.stage === "SAVED" && !item.resumeVersionId && !item.coverLetterVersionId && !item.recruiterStatus && <button onClick={() => unsave(item.jobId)} className="rounded-xl border border-slate-300 px-4 py-2 font-bold text-slate-600">Unsave</button>}</div></article>)}</section>}
  </main><Footer /></div>;
}
