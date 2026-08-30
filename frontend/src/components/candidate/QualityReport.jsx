const severityClass = { HIGH: "bg-red-100 text-red-800", MEDIUM: "bg-amber-100 text-amber-800", LOW: "bg-blue-100 text-blue-800" };

export default function QualityReport({ quality }) {
  if (!quality) return null;
  return (
    <section className="bg-slate-900 text-white rounded-3xl shadow-sm p-6" aria-labelledby="quality-heading">
      <div className="flex flex-col sm:flex-row sm:items-center gap-5">
        <div className="w-24 h-24 shrink-0 rounded-full border-8 border-indigo-400 flex items-center justify-center text-3xl font-extrabold" aria-label={`${quality.qualityScore} out of 100`}>
          {quality.qualityScore}
        </div>
        <div><h2 id="quality-heading" className="text-2xl font-extrabold">{quality.scoreLabel || "Resume Quality Score"}</h2><p className="text-slate-300 text-sm mt-2 max-w-2xl">{quality.explanation}</p></div>
      </div>
      {quality.strengths?.length > 0 && <div className="mt-6"><h3 className="font-bold text-emerald-300">Strengths</h3><ul className="mt-2 grid sm:grid-cols-2 gap-2 text-sm text-slate-200">{quality.strengths.map((strength) => <li key={strength}>✓ {strength}</li>)}</ul></div>}
      <div className="mt-6"><h3 className="font-bold">Issues and recommendations</h3>
        {quality.issues?.length === 0 ? <p className="mt-2 text-sm text-emerald-300">No heuristic issues were detected.</p> : (
          <div className="space-y-3 mt-3">{quality.issues.map((issue, index) => (
            <article key={`${issue.category}-${index}`} className="bg-white/10 rounded-xl p-4">
              <div className="flex gap-2 items-center"><span className={`text-xs font-bold px-2 py-1 rounded-full ${severityClass[issue.severity]}`}>{issue.severity}</span><span className="text-xs text-slate-300 font-bold">{issue.category}</span></div>
              <p className="mt-2 font-semibold">{issue.message}</p><p className="mt-1 text-sm text-slate-300">{issue.recommendation}</p>
            </article>
          ))}</div>
        )}
      </div>
    </section>
  );
}
