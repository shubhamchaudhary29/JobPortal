import { useRef } from "react";
import { downloadCandidateResume } from "../../services/candidate-profile-service";

const stateStyle = {
  PARSED: "bg-emerald-100 text-emerald-800",
  PARTIALLY_PARSED: "bg-amber-100 text-amber-800",
  OCR_REQUIRED: "bg-orange-100 text-orange-800",
  FAILED: "bg-red-100 text-red-800",
  PROCESSING: "bg-blue-100 text-blue-800",
  NOT_UPLOADED: "bg-slate-100 text-slate-700",
};

export default function ResumePanel({ resume, warnings = [], busy, progress, error, onUpload, onDelete, onReparse }) {
  const input = useRef(null);
  const status = resume?.parsingStatus || "NOT_UPLOADED";
  const uploaded = Boolean(resume?.filename);

  const chooseFile = (event) => {
    const file = event.target.files?.[0];
    if (file) onUpload(file);
    event.target.value = "";
  };

  return (
    <section className="bg-white rounded-3xl border border-slate-200 shadow-sm p-6" aria-labelledby="resume-heading">
      <div className="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-4">
        <div>
          <h2 id="resume-heading" className="text-xl font-extrabold text-slate-900">Resume</h2>
          <p className="text-sm text-slate-500 mt-1">Private PDF or DOCX, up to 5 MB. Parsing runs without a paid AI service.</p>
        </div>
        <span className={`self-start px-3 py-1 rounded-full text-xs font-bold ${stateStyle[status] || stateStyle.NOT_UPLOADED}`}>
          {status.replaceAll("_", " ")}
        </span>
      </div>

      {uploaded ? (
        <div className="mt-5 rounded-2xl bg-slate-50 border border-slate-200 p-4">
          <p className="font-bold text-slate-800 break-all">{resume.filename}</p>
          <p className="text-xs text-slate-500 mt-1">
            {Math.max(1, Math.round((resume.size || 0) / 1024))} KB
            {resume.uploadedAt ? ` · Uploaded ${new Date(resume.uploadedAt).toLocaleString()}` : ""}
          </p>
        </div>
      ) : (
        <div className="mt-5 rounded-2xl border-2 border-dashed border-slate-300 p-7 text-center text-slate-500">
          No resume uploaded. Your editable profile is still available below.
        </div>
      )}

      {(status === "FAILED" || status === "OCR_REQUIRED") && (
        <div role="alert" className="mt-4 rounded-xl bg-orange-50 border border-orange-200 p-4 text-sm text-orange-900">
          {resume?.errorMessage || (status === "OCR_REQUIRED"
            ? "Too little selectable text was found. Upload a text-based document; OCR is not included."
            : "Parsing failed. Replace the file or retry after checking that it is valid and unencrypted.")}
        </div>
      )}
      {warnings.length > 0 && status !== "OCR_REQUIRED" && (
        <ul className="mt-4 text-sm text-amber-800 bg-amber-50 border border-amber-200 rounded-xl p-4 list-disc pl-9">
          {warnings.map((warning) => <li key={warning}>{warning}</li>)}
        </ul>
      )}
      {error && <p role="alert" className="mt-4 text-sm font-semibold text-red-700">{error}</p>}
      {busy && (
        <div className="mt-4" role="status" aria-live="polite">
          <div className="h-2 bg-slate-200 rounded-full overflow-hidden"><div className="h-full bg-indigo-600" style={{ width: `${progress || 20}%` }} /></div>
          <p className="text-xs text-slate-500 mt-1">Processing resume… {progress ? `${progress}% uploaded` : ""}</p>
        </div>
      )}

      <div className="mt-5 flex flex-wrap gap-2">
        <input ref={input} className="sr-only" type="file" accept=".pdf,.docx,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document" onChange={chooseFile} />
        <button type="button" disabled={busy} onClick={() => input.current?.click()} className="px-4 py-2 rounded-xl bg-indigo-600 text-white text-sm font-bold disabled:opacity-50">
          {uploaded ? "Replace resume" : "Upload resume"}
        </button>
        {uploaded && <button type="button" disabled={busy} onClick={() => downloadCandidateResume(resume.filename)} className="px-4 py-2 rounded-xl border border-slate-300 text-sm font-bold text-slate-700">Download</button>}
        {uploaded && <button type="button" disabled={busy} onClick={onReparse} className="px-4 py-2 rounded-xl border border-indigo-200 text-sm font-bold text-indigo-700">Retry parsing</button>}
        {uploaded && <button type="button" disabled={busy} onClick={onDelete} className="px-4 py-2 rounded-xl border border-red-200 text-sm font-bold text-red-700">Delete resume</button>}
      </div>
    </section>
  );
}
