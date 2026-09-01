import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import Header from "../components/Header";
import KeywordGroups from "../components/copilot/KeywordGroups";
import ResumePreview from "../components/copilot/ResumePreview";
import {
  createCoverLetter, createResumeVersion, exportResumeVersion, getApplicationReadiness,
  getCoverLetters, getResumeVersions, getTailoringPlan, getWorkspaceJob,
  updateCoverLetter, updateResumeVersion, updateWorkspaceJob,
} from "../services/application-copilot-service";

const tabs = ["Overview", "Keyword Analysis", "Tailored Resume", "Cover Letter", "Tracking"];
const stages = ["SAVED", "PREPARING", "APPLIED", "OA", "INTERVIEW", "OFFER", "REJECTED", "WITHDRAWN"];
const dateTimeLocal = (value) => value ? new Date(value).toISOString().slice(0, 16) : "";
const problem = (error, fallback) => error?.response?.data?.detail || fallback;

export default function ApplicationCopilot() {
  const { jobId } = useParams();
  const [tab, setTab] = useState("Overview");
  const [readiness, setReadiness] = useState(null);
  const [plan, setPlan] = useState(null);
  const [resume, setResume] = useState(null);
  const [resumeDraft, setResumeDraft] = useState(null);
  const [cover, setCover] = useState(null);
  const [coverDraft, setCoverDraft] = useState("");
  const [workspace, setWorkspace] = useState(null);
  const [tracking, setTracking] = useState({ stage: "SAVED", notes: "", followUpAt: "", appliedExternally: false });
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const load = async () => {
    setLoading(true); setError("");
    try {
      const [ready, tailoring, resumes, letters, workspaceResult] = await Promise.all([
        getApplicationReadiness(jobId), getTailoringPlan(jobId), getResumeVersions(jobId), getCoverLetters(jobId),
        getWorkspaceJob(jobId).catch((failure) => failure?.response?.status === 404 ? null : Promise.reject(failure)),
      ]);
      setReadiness(ready); setPlan(tailoring);
      setResume(resumes.content?.[0] || null); setCover(letters.content?.[0] || null); setWorkspace(workspaceResult);
    } catch (failure) { setError(problem(failure, "Application Copilot could not load.")); }
    finally { setLoading(false); }
  };

  useEffect(() => { load(); }, [jobId]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { if (resume) setResumeDraft(structuredClone(resume)); }, [resume]);
  useEffect(() => { setCoverDraft(cover?.content || ""); }, [cover]);
  useEffect(() => {
    if (workspace) setTracking({ stage: workspace.stage || "SAVED", notes: workspace.notes || "",
      followUpAt: dateTimeLocal(workspace.followUpAt), appliedExternally: Boolean(workspace.appliedExternally) });
  }, [workspace]);

  const run = async (name, action, success) => {
    setBusy(name); setError(""); setNotice("");
    try { const value = await action(); success(value); }
    catch (failure) { setError(problem(failure, "The requested action could not be completed.")); }
    finally { setBusy(""); }
  };

  const generateResume = () => run("resume-create", () => createResumeVersion(jobId), (value) => {
    setResume(value); setWorkspace((current) => ({ ...(current || {}), resumeVersionId: value.id, stage: current?.stage === "SAVED" ? "PREPARING" : current?.stage || "PREPARING" }));
    setNotice("A new resume version was created. Earlier versions were preserved.");
  });
  const saveResume = () => run("resume-save", () => updateResumeVersion(resume.id, {
    title: resumeDraft.title, summary: resumeDraft.content.summary, skillOrder: resumeDraft.content.skills,
    experienceDescriptions: resumeDraft.content.experience.map((item) => item.description || ""),
    projectDescriptions: resumeDraft.content.projects.map((item) => item.description || ""),
    sectionOrder: resumeDraft.content.sectionOrder,
  }), (value) => { setResume(value); setNotice("Tailored resume edits saved without changing your base profile."); });
  const generateCover = () => run("cover-create", () => createCoverLetter(jobId), (value) => {
    setCover(value); setCoverDraft(value.content || ""); setWorkspace((current) => ({ ...(current || {}), coverLetterVersionId: value.id, stage: current?.stage === "SAVED" ? "PREPARING" : current?.stage || "PREPARING" }));
    setNotice("A new cover-letter version was created. Earlier drafts were preserved.");
  });
  const saveCover = () => run("cover-save", () => updateCoverLetter(cover.id, { title: cover.title, content: coverDraft }),
    (value) => { setCover(value); setNotice("Cover-letter edits saved."); });
  const saveTracking = () => run("tracking", () => updateWorkspaceJob(jobId, {
    stage: tracking.stage, notes: tracking.notes, followUpAt: tracking.followUpAt ? new Date(tracking.followUpAt).toISOString() : null,
    appliedExternally: tracking.appliedExternally,
  }), (value) => { setWorkspace(value); setNotice("Application tracking updated."); });
  const startPackage = () => run("package", () => updateWorkspaceJob(jobId, { stage: "PREPARING" }),
    (value) => { setWorkspace(value); setTab("Keyword Analysis"); setNotice("Application package started."); });

  const moveSkill = (index, direction) => setResumeDraft((current) => {
    const next = structuredClone(current); const target = index + direction;
    if (target < 0 || target >= next.content.skills.length) return current;
    [next.content.skills[index], next.content.skills[target]] = [next.content.skills[target], next.content.skills[index]];
    return next;
  });
  const setDescription = (section, index, value) => setResumeDraft((current) => {
    const next = structuredClone(current); next.content[section][index].description = value; return next;
  });
  const blockers = readiness?.readiness?.blockers || [];
  const active = readiness?.readiness?.active !== false;

  if (loading) return <div className="min-h-screen bg-slate-50"><Header /><main className="max-w-6xl mx-auto p-8"><div className="h-64 rounded-3xl bg-white animate-pulse" aria-label="Loading Application Copilot" /></main></div>;
  if (error && !readiness) return <div className="min-h-screen bg-slate-50"><Header /><main className="max-w-3xl mx-auto p-8 text-center"><h1 className="text-2xl font-bold">Application Copilot unavailable</h1><p className="mt-3 text-slate-600">{error}</p><button onClick={load} className="mt-5 rounded-xl bg-indigo-600 px-5 py-3 text-white font-bold">Retry</button></main></div>;

  return <div className="min-h-screen bg-slate-50"><Header /><main className="max-w-6xl mx-auto px-4 py-8">
    <div className="flex flex-col lg:flex-row lg:items-start lg:justify-between gap-4">
      <div><p className="text-sm font-bold uppercase tracking-widest text-indigo-600">Application Copilot</p><h1 className="text-3xl font-extrabold text-slate-900 mt-1">{readiness.job.title}</h1><p className="text-slate-500">{readiness.job.company} · Evidence-based preparation</p></div>
      <div className="flex gap-3"><Link to="/application-workspace" className="rounded-xl border border-slate-300 bg-white px-4 py-3 font-bold">Dashboard</Link>{!workspace && <button onClick={startPackage} disabled={busy || !active} className="rounded-xl bg-indigo-600 px-5 py-3 text-white font-bold disabled:bg-slate-300">Start Application Package</button>}</div>
    </div>
    {!active && <div className="mt-6 rounded-2xl border border-rose-200 bg-rose-50 p-4 font-bold text-rose-800">No longer active. Your saved history remains available, but new generation is disabled.</div>}
    {notice && <div role="status" className="mt-5 rounded-xl border border-emerald-200 bg-emerald-50 p-3 text-emerald-800">{notice}</div>}
    {error && <div role="alert" className="mt-5 rounded-xl border border-rose-200 bg-rose-50 p-3 text-rose-800">{error}</div>}
    <nav className="mt-7 flex gap-2 overflow-x-auto" aria-label="Application Copilot sections">{tabs.map((value) => <button key={value} onClick={() => setTab(value)} className={`whitespace-nowrap rounded-xl px-4 py-2 font-bold ${tab === value ? "bg-indigo-600 text-white" : "bg-white border border-slate-200 text-slate-600"}`}>{value}</button>)}</nav>

    {tab === "Overview" && <div className="mt-6 grid gap-6 lg:grid-cols-3">
      <section className="lg:col-span-2 rounded-3xl border border-slate-200 bg-white p-6"><div className="grid sm:grid-cols-2 gap-4"><Score title="Match Score" value={readiness.matchScore} detail={readiness.matchLevel} /><Score title="Application Readiness" value={readiness.readiness.readinessScore} detail={readiness.readiness.readinessLevel} /></div><p className="mt-5 text-xs text-slate-500">{readiness.readiness.disclaimer}</p></section>
      <section className="rounded-3xl border border-slate-200 bg-white p-6"><h2 className="font-extrabold text-slate-900">Evidence coverage</h2><p className="text-4xl font-extrabold text-indigo-600 mt-3">{readiness.readiness.evidenceCoverage}%</p><p className="text-sm text-slate-500 mt-1">Strong or supported job keywords</p></section>
      <section className="lg:col-span-3 rounded-3xl border border-slate-200 bg-white p-6"><h2 className="text-xl font-extrabold">Blockers and recommendations</h2>{blockers.length === 0 ? <p className="mt-3 text-emerald-700">No major blockers identified.</p> : <ul className="mt-3 list-disc pl-5 text-rose-700 space-y-1">{blockers.map((value) => <li key={value}>{value}</li>)}</ul>}<ul className="mt-4 list-disc pl-5 text-slate-600 space-y-1">{readiness.readiness.recommendations.map((value) => <li key={value}>{value}</li>)}</ul></section>
    </div>}

    {tab === "Keyword Analysis" && <div className="mt-6"><KeywordGroups analysis={readiness.keywordAnalysis} /><section className="mt-6 rounded-3xl border border-slate-200 bg-white p-6"><h2 className="text-xl font-extrabold">Tailoring plan</h2><div className="mt-4 space-y-3">{plan.plan.actions.map((action, index) => <div key={`${action.type}-${index}`} className="rounded-xl border border-slate-200 p-4"><p className="font-bold">{action.type.replaceAll("_", " ")}: {action.subject}</p><p className="text-sm text-slate-600 mt-1">{action.rationale}</p></div>)}</div></section></div>}

    {tab === "Tailored Resume" && <div className="mt-6 space-y-6">
      <section className="rounded-3xl border border-slate-200 bg-white p-6 flex flex-wrap items-center justify-between gap-3"><div><h2 className="text-xl font-extrabold">Tailored Resume</h2><p className="text-sm text-slate-500">Every generation creates a separate snapshot. Your base profile is never modified.</p></div><button onClick={generateResume} disabled={busy || !active} className="rounded-xl bg-indigo-600 px-5 py-3 text-white font-bold disabled:bg-slate-300">{resume ? "Regenerate as new version" : "Generate resume"}</button></section>
      {resume?.staleness === "OUTDATED" && <div className="rounded-xl border border-amber-200 bg-amber-50 p-4 font-bold text-amber-900">{resume.stalenessMessage} Regenerate to create a new snapshot.</div>}
      {resumeDraft && <div className="grid gap-6 xl:grid-cols-[380px_1fr]"><section className="rounded-3xl border border-slate-200 bg-white p-6 h-fit"><label className="font-bold block">Summary<textarea aria-label="Tailored summary" value={resumeDraft.content.summary || ""} onChange={(event) => setResumeDraft((current) => ({ ...current, content: { ...current.content, summary: event.target.value } }))} maxLength={3000} className="mt-2 w-full min-h-36 rounded-xl border border-slate-300 p-3 font-normal" /></label><h3 className="font-bold mt-5">Skill ordering</h3><div className="mt-2 space-y-2">{resumeDraft.content.skills.map((skill, index) => <div key={skill} className="flex items-center justify-between rounded-lg bg-slate-50 px-3 py-2"><span>{skill}</span><span><button aria-label={`Move ${skill} up`} onClick={() => moveSkill(index, -1)} className="px-2">↑</button><button aria-label={`Move ${skill} down`} onClick={() => moveSkill(index, 1)} className="px-2">↓</button></span></div>)}</div><h3 className="font-bold mt-5">Experience descriptions</h3>{resumeDraft.content.experience.map((item, index) => <label key={`${item.organization}-${index}`} className="block text-sm font-bold mt-3">{item.title}<textarea aria-label={`Experience description ${index + 1}`} value={item.description || ""} onChange={(event) => setDescription("experience", index, event.target.value)} maxLength={6000} className="mt-1 w-full min-h-24 rounded-xl border border-slate-300 p-2 font-normal" /></label>)}<h3 className="font-bold mt-5">Project descriptions</h3>{resumeDraft.content.projects.map((item, index) => <label key={`${item.name}-${index}`} className="block text-sm font-bold mt-3">{item.name}<textarea aria-label={`Project description ${index + 1}`} value={item.description || ""} onChange={(event) => setDescription("projects", index, event.target.value)} maxLength={6000} className="mt-1 w-full min-h-24 rounded-xl border border-slate-300 p-2 font-normal" /></label>)}<div className="mt-5 flex gap-2"><button onClick={saveResume} disabled={busy} className="rounded-xl bg-slate-900 px-4 py-2 text-white font-bold">Save edits</button><button onClick={() => exportResumeVersion(resume.id, `tailored-resume-v${resume.versionNumber}.docx`)} className="rounded-xl border border-slate-300 px-4 py-2 font-bold">Export DOCX</button></div><div className="mt-6 border-t pt-4"><h3 className="font-bold">What changed</h3><ul className="mt-2 text-sm text-slate-600 space-y-1">{resume.tailoringActions.slice(0, 8).map((item, index) => <li key={index}>• {item.subject}: {item.rationale}</li>)}</ul></div></section><ResumePreview content={resumeDraft.content} /></div>}
    </div>}

    {tab === "Cover Letter" && <div className="mt-6 rounded-3xl border border-slate-200 bg-white p-6"><div className="flex flex-wrap justify-between gap-3"><div><h2 className="text-xl font-extrabold">Cover Letter</h2><p className="text-sm text-slate-500">Generated only from the job and your documented evidence.</p></div><button onClick={generateCover} disabled={busy || !active} className="rounded-xl bg-indigo-600 px-5 py-3 text-white font-bold disabled:bg-slate-300">{cover ? "Regenerate as new version" : "Generate draft"}</button></div>{cover?.staleness === "OUTDATED" && <p className="mt-4 rounded-xl bg-amber-50 p-3 text-amber-900 font-bold">{cover.stalenessMessage}</p>}{cover && <><textarea aria-label="Cover letter draft" value={coverDraft} onChange={(event) => setCoverDraft(event.target.value)} maxLength={12000} className="mt-5 min-h-[420px] w-full rounded-xl border border-slate-300 p-4 leading-relaxed" /><div className="mt-3 flex gap-2"><button onClick={saveCover} disabled={busy} className="rounded-xl bg-slate-900 px-4 py-2 text-white font-bold">Save draft</button><button onClick={() => navigator.clipboard?.writeText(coverDraft)} className="rounded-xl border border-slate-300 px-4 py-2 font-bold">Copy content</button></div></>}</div>}

    {tab === "Tracking" && <section className="mt-6 rounded-3xl border border-slate-200 bg-white p-6"><h2 className="text-xl font-extrabold">Application Tracking</h2>{workspace?.recruiterStatus && <p className="mt-3 rounded-xl bg-blue-50 p-3 text-blue-900">Portal recruiter status: <strong>{workspace.recruiterStatus}</strong>. This remains authoritative and separate from your personal stage.</p>}<div className="mt-5 grid gap-5 md:grid-cols-2"><label className="font-bold">Personal stage<select value={tracking.stage} onChange={(event) => setTracking((value) => ({ ...value, stage: event.target.value }))} className="mt-2 w-full rounded-xl border border-slate-300 p-3 font-normal">{stages.map((value) => <option key={value}>{value}</option>)}</select></label><label className="font-bold">Follow-up date<input type="datetime-local" value={tracking.followUpAt} onChange={(event) => setTracking((value) => ({ ...value, followUpAt: event.target.value }))} className="mt-2 w-full rounded-xl border border-slate-300 p-3 font-normal" /></label><label className="md:col-span-2 font-bold">Private notes<textarea aria-label="Private application notes" value={tracking.notes} onChange={(event) => setTracking((value) => ({ ...value, notes: event.target.value }))} maxLength={5000} className="mt-2 min-h-40 w-full rounded-xl border border-slate-300 p-3 font-normal" /></label><label className="md:col-span-2 flex items-center gap-2"><input type="checkbox" checked={tracking.appliedExternally} disabled={Boolean(workspace?.recruiterStatus)} onChange={(event) => setTracking((value) => ({ ...value, appliedExternally: event.target.checked }))} /> Applied externally</label></div><button onClick={saveTracking} disabled={busy} className="mt-5 rounded-xl bg-indigo-600 px-5 py-3 text-white font-bold">Save tracking</button></section>}
  </main></div>;
}

function Score({ title, value, detail }) {
  return <div className="rounded-2xl bg-slate-50 p-5"><h2 className="font-bold text-slate-600">{title}</h2><p className="text-4xl font-extrabold text-indigo-600 mt-2">{value}%</p><p className="text-xs text-slate-500 mt-1">{detail?.replaceAll("_", " ")}</p></div>;
}
