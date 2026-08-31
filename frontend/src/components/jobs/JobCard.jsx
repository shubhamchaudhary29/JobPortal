import { Link } from "react-router-dom";
import MatchBadge from "./MatchBadge";

export default function JobCard({ job, match }) {
  const id = job._id || job.id;
  const matched = match?.matchedSkills?.slice(0, 3) || [];
  const missing = match?.missingSkills?.[0];

  return (
    <Link
      to={`/jobs/${id}`}
      className="group flex flex-col justify-between rounded-3xl border border-slate-100 bg-white p-6 shadow-sm transition-all duration-300 hover:-translate-y-1 hover:border-indigo-100 hover:shadow-xl"
    >
      <div>
        <div className="mb-5 flex items-start justify-between gap-3">
          <div className="flex h-14 w-14 shrink-0 items-center justify-center rounded-2xl bg-gradient-to-br from-indigo-50 to-purple-50 text-2xl font-bold text-indigo-600 shadow-inner">
            {job.company?.charAt(0) || "J"}
          </div>
          {match ? <MatchBadge match={match} /> : (
            <span className="rounded-full bg-green-50 px-3 py-1.5 text-xs font-bold uppercase tracking-wide text-green-600">
              Actively Hiring
            </span>
          )}
        </div>

        <h3 className="line-clamp-1 text-xl font-bold text-slate-900 transition-colors group-hover:text-indigo-600">{job.title}</h3>
        <p className="mt-1 font-medium text-slate-500">{job.company}</p>

        <div className="mt-5 flex flex-wrap gap-2">
          <span className="inline-flex rounded-lg bg-slate-100 px-2.5 py-1.5 text-xs font-semibold text-slate-600">📍 {job.location}</span>
          <span className="inline-flex rounded-lg bg-slate-100 px-2.5 py-1.5 text-xs font-semibold text-slate-600">💼 {job.experience}+ Yrs</span>
          <span className="inline-flex rounded-lg bg-slate-100 px-2.5 py-1.5 text-xs font-semibold text-slate-600">
            💰 ₹{job.salary ? `${(job.salary / 100000).toFixed(1)} LPA` : "Not Disclosed"}
          </span>
        </div>

        {matched.length > 0 && (
          <div className="mt-4 flex flex-wrap gap-2" aria-label="Matched skills">
            {matched.map((skill) => <span key={skill} className="rounded-md bg-indigo-50 px-2 py-1 text-xs font-semibold text-indigo-700">✓ {skill}</span>)}
          </div>
        )}
        {missing && <p className="mt-3 text-xs font-medium text-slate-500">Gap to review: {missing}</p>}
      </div>

      <div className="mt-6 flex items-center justify-between border-t border-slate-50 pt-5">
        <span className="text-sm font-medium text-slate-400">Apply instantly</span>
        <span className="flex items-center gap-1 text-sm font-bold text-indigo-600 transition-transform group-hover:translate-x-2">View Role <span className="text-lg">→</span></span>
      </div>
    </Link>
  );
}
