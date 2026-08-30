import { useCallback, useEffect, useState } from "react";
import Header from "../components/Header";
import Footer from "../components/Footer";
import ResumePanel from "../components/candidate/ResumePanel";
import BasicProfileForm from "../components/candidate/BasicProfileForm";
import SkillsEditor from "../components/candidate/SkillsEditor";
import CollectionEditor from "../components/candidate/CollectionEditor";
import QualityReport from "../components/candidate/QualityReport";
import {
  candidateProfileError, deleteCandidateResume, getCandidateProfile, reparseCandidateResume,
  updateCandidateProfile, uploadCandidateResume,
} from "../services/candidate-profile-service";

const educationFields = [
  { key: "institution", label: "Institution" }, { key: "degree", label: "Degree" },
  { key: "fieldOfStudy", label: "Field of study" }, { key: "grade", label: "Grade / CGPA" },
  { key: "startDate", label: "Start (YYYY or YYYY-MM)" }, { key: "endDate", label: "End (YYYY or YYYY-MM)" },
  { key: "description", label: "Description", type: "textarea", wide: true, maxLength: 3000 },
];
const experienceFields = [
  { key: "organization", label: "Organization" }, { key: "title", label: "Title" },
  { key: "employmentType", label: "Employment type" }, { key: "location", label: "Location" },
  { key: "startDate", label: "Start (YYYY or YYYY-MM)" }, { key: "endDate", label: "End (leave blank if current)" },
  { key: "currentlyWorking", label: "I currently work here", type: "checkbox" },
  { key: "technologies", label: "Technologies (comma-separated)", type: "list" },
  { key: "description", label: "Responsibilities and impact", type: "textarea", wide: true, maxLength: 6000 },
];
const projectFields = [
  { key: "name", label: "Project name" }, { key: "url", label: "Project or repository URL", type: "url" },
  { key: "startDate", label: "Start (YYYY or YYYY-MM)" }, { key: "endDate", label: "End (YYYY or YYYY-MM)" },
  { key: "technologies", label: "Technologies (comma-separated)", type: "list", wide: true },
  { key: "description", label: "Description and outcomes", type: "textarea", wide: true, maxLength: 6000 },
];
const certificationFields = [
  { key: "name", label: "Certification name" }, { key: "issuer", label: "Issuer" },
  { key: "issueDate", label: "Issue date (YYYY or YYYY-MM)" }, { key: "credentialUrl", label: "Credential URL", type: "url" },
];

export default function MyProfile() {
  const [profile, setProfile] = useState(null);
  const [draft, setDraft] = useState(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [saveError, setSaveError] = useState("");
  const [resumeError, setResumeError] = useState("");
  const [saving, setSaving] = useState(false);
  const [resumeBusy, setResumeBusy] = useState(false);
  const [progress, setProgress] = useState(0);
  const [notice, setNotice] = useState("");

  const load = useCallback(async () => {
    setLoading(true); setLoadError("");
    try { const value = await getCandidateProfile(); setProfile(value); setDraft(editable(value)); }
    catch (error) { setLoadError(candidateProfileError(error, "Could not load your candidate profile.")); }
    finally { setLoading(false); }
  }, []);
  useEffect(() => { load(); }, [load]);

  const save = async (event) => {
    event.preventDefault(); setSaving(true); setSaveError(""); setNotice("");
    try {
      const value = await updateCandidateProfile(requestFor(draft));
      setProfile(value); setDraft(editable(value)); setNotice("Profile changes saved.");
    } catch (error) { setSaveError(candidateProfileError(error, "Could not save the profile.")); }
    finally { setSaving(false); }
  };

  const upload = async (file) => {
    setResumeError(""); setNotice("");
    const extension = file.name.toLowerCase().split(".").pop();
    if (!["pdf", "docx"].includes(extension)) { setResumeError("Choose a PDF or DOCX resume."); return; }
    if (file.size === 0) { setResumeError("The resume file is empty."); return; }
    if (file.size > 5 * 1024 * 1024) { setResumeError("The resume is larger than the 5 MB limit."); return; }
    setResumeBusy(true); setProgress(0);
    try {
      const value = await uploadCandidateResume(file, setProgress);
      setProfile(value); setDraft(editable(value)); setNotice("Resume uploaded and profile parsing completed.");
    } catch (error) { setResumeError(candidateProfileError(error, "Resume upload failed.")); }
    finally { setResumeBusy(false); setProgress(0); }
  };

  const reparse = async () => {
    setResumeBusy(true); setResumeError(""); setNotice("");
    try { const value = await reparseCandidateResume(); setProfile(value); setDraft(editable(value)); setNotice("Resume parsing completed."); }
    catch (error) { setResumeError(candidateProfileError(error, "Resume parsing failed.")); }
    finally { setResumeBusy(false); }
  };

  const removeResume = async () => {
    if (!window.confirm("Delete the stored resume? Your editable structured profile will be retained.")) return;
    setResumeBusy(true); setResumeError(""); setNotice("");
    try {
      await deleteCandidateResume(); const value = await getCandidateProfile();
      setProfile(value); setDraft(editable(value)); setNotice("Stored resume deleted; profile data was retained.");
    } catch (error) { setResumeError(candidateProfileError(error, "Could not delete the resume.")); }
    finally { setResumeBusy(false); }
  };

  return (
    <div className="min-h-screen bg-slate-50 flex flex-col"><Header />
      <main className="flex-1 max-w-6xl w-full mx-auto px-4 py-8">
        <div className="mb-7"><p className="text-sm font-bold uppercase tracking-widest text-indigo-600">Candidate intelligence</p><h1 className="text-3xl sm:text-4xl font-extrabold text-slate-900 mt-1">Profile and resume</h1><p className="text-slate-500 mt-2">Review every parsed field, make corrections, and improve your resume using transparent heuristic feedback.</p></div>
        {loading && <div role="status" className="bg-white rounded-3xl border border-slate-200 p-10 animate-pulse text-slate-500">Loading candidate profile…</div>}
        {!loading && loadError && <div role="alert" className="bg-white rounded-3xl border border-red-200 p-10 text-center"><p className="font-bold text-red-700">{loadError}</p><button type="button" onClick={load} className="mt-5 px-5 py-2 rounded-xl bg-indigo-600 text-white font-bold">Retry</button></div>}
        {!loading && !loadError && profile && draft && (
          <form onSubmit={save} className="space-y-6">
            {notice && <p role="status" className="rounded-xl bg-emerald-50 border border-emerald-200 p-4 text-emerald-800 font-semibold">{notice}</p>}
            <ResumePanel resume={profile.resume} warnings={profile.parsingWarnings} busy={resumeBusy} progress={progress} error={resumeError} onUpload={upload} onDelete={removeResume} onReparse={reparse} />
            <BasicProfileForm draft={draft} setDraft={setDraft} />
            <SkillsEditor skills={draft.skills} onChange={(skills) => setDraft((value) => ({ ...value, skills }))} />
            <CollectionEditor title="Education" description="Add all relevant qualifications." items={draft.education} fields={educationFields}
              emptyItem={{ institution: "", degree: "", fieldOfStudy: "", startDate: "", endDate: "", grade: "", description: "" }} onChange={(education) => setDraft((value) => ({ ...value, education }))} />
            <CollectionEditor title="Experience" description="Include jobs, internships, and measurable impact." items={draft.experience} fields={experienceFields}
              emptyItem={{ organization: "", title: "", employmentType: "", location: "", startDate: "", endDate: "", currentlyWorking: false, description: "", technologies: [] }} onChange={(experience) => setDraft((value) => ({ ...value, experience }))} />
            <CollectionEditor title="Projects" description="Projects can demonstrate practical work for students and experienced candidates." items={draft.projects} fields={projectFields}
              emptyItem={{ name: "", description: "", technologies: [], url: "", startDate: "", endDate: "" }} onChange={(projects) => setDraft((value) => ({ ...value, projects }))} />
            <CollectionEditor title="Certifications" description="Add relevant professional certifications and credentials." items={draft.certifications} fields={certificationFields}
              emptyItem={{ name: "", issuer: "", issueDate: "", credentialUrl: "" }} onChange={(certifications) => setDraft((value) => ({ ...value, certifications }))} />
            {saveError && <p role="alert" className="rounded-xl bg-red-50 border border-red-200 p-4 text-red-800 font-semibold">{saveError}</p>}
            <div className="sticky bottom-4 flex justify-end"><button type="submit" disabled={saving || resumeBusy} className="px-7 py-3 rounded-xl bg-indigo-600 text-white font-extrabold shadow-lg disabled:opacity-50">{saving ? "Saving…" : "Save profile"}</button></div>
            <QualityReport quality={profile.quality} />
          </form>
        )}
      </main><Footer /></div>
  );
}

function editable(profile) {
  return {
    fullName: profile.fullName || "", email: profile.email || "", phone: profile.phone || "", location: profile.location || "",
    professionalSummary: profile.professionalSummary || "", skills: profile.skills || [], education: profile.education || [],
    experience: profile.experience || [], projects: profile.projects || [], certifications: profile.certifications || [],
    links: profile.links || { linkedIn: "", github: "", portfolio: "", website: "", other: [] },
    preferences: profile.preferences || { preferredJobTitles: [], preferredLocations: [], remotePreference: "", employmentTypes: [], minimumSalary: null },
  };
}

function requestFor(draft) {
  return {
    fullName: draft.fullName, phone: draft.phone, location: draft.location, professionalSummary: draft.professionalSummary,
    skills: draft.skills, education: draft.education, experience: draft.experience, projects: draft.projects,
    certifications: draft.certifications, links: draft.links, preferences: draft.preferences,
  };
}
