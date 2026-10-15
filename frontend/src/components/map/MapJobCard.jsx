import { Link } from "react-router-dom";
import ProviderAttribution from "../jobs/ProviderAttribution";
import { formatDistance, formatSalary } from "../../map/format";

const postedDate = new Intl.DateTimeFormat("en-IN", { day: "numeric", month: "short", year: "numeric" });

function posted(job) {
  const value = job.publishedAt || job.createdAt;
  if (!value) return "";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "" : postedDate.format(date);
}

/** "FULL_TIME" / "full-time" → "Full time". */
function employmentLabel(type) {
  const words = String(type).replace(/[_-]+/g, " ").trim().toLowerCase();
  return words ? words.charAt(0).toUpperCase() + words.slice(1) : "";
}

const chip = "inline-flex items-center rounded-lg bg-slate-100 px-2 py-1 text-xs font-semibold text-slate-600";

/** Compact job card for the map panel. Same structure as JobCard: one link, attribution as a sibling link. */
export default function MapJobCard({ job, distanceKm }) {
  const date = posted(job);
  const distance = formatDistance(distanceKm);
  const type = job.employmentType ? employmentLabel(job.employmentType) : "";
  const officeLabel = job.places?.[0]?.officeLabel;

  return (
    <article className="group rounded-2xl border border-slate-100 bg-white p-4 shadow-sm transition-all duration-200 hover:border-indigo-100 hover:shadow-md">
      <Link to={`/jobs/${job.id}`} className="flex gap-3 rounded-xl focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500">
        <div aria-hidden="true"
          className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-indigo-50 to-purple-50 text-lg font-bold text-indigo-600 shadow-inner">
          {job.company?.charAt(0) || "J"}
        </div>
        <div className="min-w-0 flex-1">
          <h3 className="line-clamp-2 font-bold text-slate-900 transition-colors group-hover:text-indigo-600">{job.title}</h3>
          {job.company && <p className="mt-0.5 truncate text-sm font-medium text-slate-500">{job.company}</p>}
          <div className="mt-3 flex flex-wrap gap-1.5">
            {officeLabel ? <span className={`${chip} bg-indigo-50 text-indigo-700`} title={job.location || undefined}>📍 {officeLabel}</span>
              : job.location && <span className={chip}>📍 {job.location}</span>}
            <span className={chip}>💰 {formatSalary(job.salaryMin, job.salaryMax)}</span>
            {type && <span className={chip}>{type}</span>}
            {distance && <span className="inline-flex items-center rounded-lg bg-indigo-50 px-2 py-1 text-xs font-semibold text-indigo-700">{distance} away</span>}
          </div>
          {date && <p className="mt-2 text-xs font-medium text-slate-400">Posted {date}</p>}
        </div>
      </Link>
      <ProviderAttribution source={job.source} className="ml-14 mt-1" />
    </article>
  );
}
