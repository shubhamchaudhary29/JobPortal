import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { getApplicationReadiness, updateWorkspaceJob } from "../../services/application-copilot-service";

export default function ApplicationReadinessCard({ jobId, onPrepare }) {
  const [data, setData] = useState(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [saved, setSaved] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState("");

  useEffect(() => {
    let cancelled = false;
    getApplicationReadiness(jobId).then((value) => {
      if (!cancelled) { setData(value); setError(""); }
    }).catch(() => { if (!cancelled) setError("Application readiness is unavailable right now."); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [jobId]);

  if (loading) return <section className="mt-6 rounded-3xl border border-indigo-100 bg-white p-6 animate-pulse" aria-label="Loading application readiness"><div className="h-20 bg-slate-100 rounded-xl" /></section>;
  if (error) return <section className="mt-6 rounded-3xl border border-amber-200 bg-amber-50 p-6"><p>{error}</p><button onClick={onPrepare} className="mt-3 font-bold text-indigo-700">Open Application Copilot</button></section>;

  const missing = data?.keywordAnalysis?.missing || [];
  return (
    <section className="mt-6 rounded-3xl border border-indigo-100 bg-gradient-to-br from-white to-indigo-50 p-6" aria-labelledby="readiness-heading">
      <div className="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-4">
        <div>
          <p className="text-xs font-bold uppercase tracking-widest text-indigo-600">Application Copilot</p>
          <h2 id="readiness-heading" className="mt-1 text-xl font-extrabold text-slate-900">Application Readiness: {data.readiness.readinessScore}%</h2>
          <p className="text-sm text-slate-600 mt-1">{data.readiness.readinessLevel.replaceAll("_", " ")} · Match Score {data.matchScore}%</p>
        </div>
        <div className="flex gap-2">
          <button onClick={async () => {
            setSaving(true);
            setSaveError("");
            try {
              await updateWorkspaceJob(jobId, { stage: "SAVED" });
              setSaved(true);
            } catch {
              setSaveError("Could not save this job. Please try again.");
            } finally {
              setSaving(false);
            }
          }} disabled={saving || saved} className="rounded-xl border border-indigo-200 bg-white px-4 py-3 font-bold text-indigo-700 disabled:text-slate-400">{saved ? "Saved" : saving ? "Saving…" : "Save job"}</button>
          <button onClick={onPrepare} disabled={!data.readiness.active} className="rounded-xl bg-indigo-600 px-5 py-3 font-bold text-white disabled:bg-slate-300">
            {data.readiness.active ? "Prepare Application" : "No longer active"}
          </button>
        </div>
      </div>
      {saveError && <p role="alert" className="mt-3 text-sm font-medium text-red-700">{saveError}</p>}
      {missing.length > 0 && <div className="mt-4 rounded-xl border border-amber-200 bg-amber-50 p-4">
        <p className="font-bold text-amber-900">Important missing requirements</p>
        <p className="text-sm text-amber-800 mt-1">{missing.slice(0, 4).map((item) => item.keyword).join(", ")} — missing from your profile and never added automatically.</p>
        <Link to="/my-profile" className="text-sm font-bold text-indigo-700 mt-2 inline-block">I actually know one of these — edit my profile</Link>
      </div>}
      {data.readiness.recommendations?.[0] && <p className="mt-4 text-sm text-slate-600">Next: {data.readiness.recommendations[0]}</p>}
      <p className="mt-3 text-xs text-slate-500">Not an official ATS score or interview probability.</p>
    </section>
  );
}
