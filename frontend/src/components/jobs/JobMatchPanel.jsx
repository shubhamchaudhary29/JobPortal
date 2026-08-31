const breakdown = [
  ["Skills", "skillScore"],
  ["Experience", "experienceScore"],
  ["Role", "titleScore"],
  ["Education", "educationScore"],
  ["Location", "locationScore"],
  ["Employment", "employmentTypeScore"],
];

export default function JobMatchPanel({ match, loading, error, onRetry }) {
  if (loading) return <section className="mt-6 rounded-3xl border border-slate-200 bg-white p-8" aria-label="Loading your match"><div className="h-28 animate-pulse rounded-2xl bg-slate-100" /></section>;
  if (error) return (
    <section className="mt-6 rounded-3xl border border-red-100 bg-white p-8">
      <h2 className="text-lg font-bold text-slate-900">Your match is unavailable</h2>
      <p className="mt-2 text-sm text-slate-600">{error}</p>
      {onRetry && <button onClick={onRetry} className="mt-4 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white">Retry</button>}
    </section>
  );
  if (!match) return null;
  const limited = match.matchLevel === "LOW_DATA" || match.confidence === "LOW";

  return (
    <section className="mt-6 rounded-3xl border border-indigo-100 bg-white p-8" aria-labelledby="match-heading">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <p className="text-sm font-bold uppercase tracking-wide text-indigo-600">Profile-to-job compatibility</p>
          <h2 id="match-heading" className="mt-1 text-2xl font-extrabold text-slate-900">
            {limited ? "Limited profile data" : `Your match: ${Math.round(match.overallScore)}%`}
          </h2>
          <p className="mt-2 text-sm text-slate-500">This is compatibility guidance, not a hiring or ATS acceptance probability.</p>
        </div>
        {!limited && <span className="rounded-full bg-indigo-50 px-4 py-2 text-sm font-bold text-indigo-700">{match.matchLevel.replaceAll("_", " ")}</span>}
      </div>

      {limited && <p className="mt-5 rounded-xl bg-amber-50 p-4 text-sm font-medium text-amber-800">Add skills and job preferences to improve matching accuracy.</p>}

      <div className="mt-7 grid gap-6 md:grid-cols-2">
        <div>
          <h3 className="font-bold text-slate-900">Matched skills</h3>
          {match.matchedSkills?.length ? <div className="mt-3 flex flex-wrap gap-2">{match.matchedSkills.map((skill) => <span key={skill} className="rounded-lg bg-emerald-50 px-2.5 py-1.5 text-sm font-semibold text-emerald-700">✓ {skill}</span>)}</div> : <p className="mt-2 text-sm text-slate-500">No structured skill overlap was available.</p>}
        </div>
        <div>
          <h3 className="font-bold text-slate-900">Gaps to review</h3>
          {match.missingSkills?.length ? <div className="mt-3 flex flex-wrap gap-2">{match.missingSkills.map((skill) => <span key={skill} className="rounded-lg bg-orange-50 px-2.5 py-1.5 text-sm font-semibold text-orange-700">{skill}</span>)}</div> : <p className="mt-2 text-sm text-slate-500">No required skill gaps were identified.</p>}
        </div>
      </div>

      {match.explanation?.length > 0 && <div className="mt-7"><h3 className="font-bold text-slate-900">Why this matches</h3><ul className="mt-3 space-y-2 text-sm text-slate-600">{match.explanation.map((reason) => <li key={reason} className="flex gap-2"><span aria-hidden="true">•</span><span>{reason}</span></li>)}</ul></div>}

      <div className="mt-7 grid grid-cols-2 gap-3 sm:grid-cols-3">
        {breakdown.filter(([, key]) => match[key] != null).map(([label, key]) => (
          <div key={key} className="rounded-xl bg-slate-50 p-3"><p className="text-xs font-semibold text-slate-500">{label}</p><p className="mt-1 font-extrabold text-slate-800">{Math.round(match[key])}%</p></div>
        ))}
      </div>
    </section>
  );
}
