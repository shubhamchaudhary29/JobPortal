import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import Stepper from "../Stepper";
import { getMyApplications } from "../../services/application-service";
import { getMyProfile } from "../../services/user-service";

const statusStyles = {
  APPLIED: { className: "bg-blue-50 text-blue-700 border-blue-100", label: "Applied" },
  IN_REVIEW: { className: "bg-amber-50 text-amber-700 border-amber-100", label: "In Review" },
  SHORTLISTED: { className: "bg-purple-50 text-purple-700 border-purple-100", label: "Shortlisted" },
  ACCEPTED: { className: "bg-green-50 text-green-700 border-green-100", label: "Accepted" },
  REJECTED: { className: "bg-red-50 text-red-700 border-red-100", label: "Rejected" },
};

function StatusBadge({ status }) {
  const value = statusStyles[status] || statusStyles.APPLIED;
  return <span className={`inline-flex rounded-full border px-2.5 py-1 text-xs font-bold uppercase tracking-wider ${value.className}`}>{value.label}</span>;
}

function formatDate(value) {
  if (!value) return "";
  return new Date(value).toLocaleDateString("en-US", { year: "numeric", month: "short", day: "numeric" });
}

export default function ApplicationActivity() {
  const [summary, setSummary] = useState(null);
  const [applications, setApplications] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const [profile, values] = await Promise.all([getMyProfile(), getMyApplications()]);
      setSummary(profile);
      setApplications(Array.isArray(values) ? values : []);
    } catch {
      setError("Could not load your application activity.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  if (loading) return <div aria-label="Loading application activity" className="h-48 animate-pulse rounded-3xl border border-slate-200 bg-white" />;

  if (error) return (
    <section className="rounded-3xl border border-red-200 bg-white p-6 text-center">
      <p role="alert" className="font-semibold text-red-700">{error}</p>
      <button type="button" onClick={load} className="mt-4 rounded-xl bg-indigo-600 px-5 py-2 font-bold text-white">Retry application activity</button>
    </section>
  );

  const stats = [
    ["Total Applications", summary?.totalApplications ?? 0, "💼", "border-indigo-400", "text-indigo-700"],
    ["Accepted", summary?.acceptedApplications ?? 0, "✅", "border-green-400", "text-green-700"],
    ["Rejected", summary?.rejectedApplications ?? 0, "❌", "border-red-400", "text-red-700"],
    ["In Progress", summary?.pendingApplications ?? 0, "⏳", "border-amber-400", "text-amber-700"],
  ];
  const recent = [...applications].sort((left, right) => new Date(right.appliedAt) - new Date(left.appliedAt)).slice(0, 5);

  return (
    <section aria-labelledby="application-overview-heading" className="space-y-6">
      <div>
        <h2 id="application-overview-heading" className="mb-4 text-lg font-extrabold text-slate-800">Application Overview</h2>
        <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
          {stats.map(([label, value, icon, accent, text]) => (
            <div key={label} aria-label={`${label}: ${value}`} className={`rounded-2xl border border-l-4 border-slate-200 bg-white p-5 shadow-sm ${accent}`}>
              <div className="text-2xl" aria-hidden="true">{icon}</div>
              <div className={`text-4xl font-extrabold ${text}`}>{value}</div>
              <div className="mt-1 text-xs font-semibold text-slate-500">{label}</div>
            </div>
          ))}
        </div>
      </div>

      <div>
        <div className="mb-4 flex items-center justify-between">
          <h2 className="text-lg font-extrabold text-slate-800">Recent Applications</h2>
          {applications.length > 0 && <Link to="/my-applications" className="text-sm font-bold text-indigo-600 hover:underline">View All Applications →</Link>}
        </div>
        {recent.length === 0 ? (
          <div className="rounded-3xl border border-dashed border-slate-300 bg-white p-10 text-center">
            <h3 className="font-bold text-slate-700">No applications yet</h3>
            <p className="mt-2 text-sm text-slate-500">Start exploring opportunities and your application progress will appear here.</p>
            <Link to="/jobs" className="mt-5 inline-block rounded-xl bg-indigo-600 px-6 py-2.5 text-sm font-bold text-white">Browse Jobs</Link>
          </div>
        ) : (
          <div className="overflow-hidden rounded-3xl border border-slate-200 bg-white shadow-sm">
            {recent.map((application, index) => (
              <article key={application.applicationId} className={`p-5 sm:p-6 ${index < recent.length - 1 ? "border-b border-slate-100" : ""}`}>
                <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                  <div className="min-w-0 flex-1">
                    <Link to={`/jobs/${application.jobId}`} className="block truncate text-left font-bold text-slate-900 hover:text-indigo-600">{application.jobTitle}</Link>
                    <p className="mt-0.5 text-sm font-medium text-slate-500">{application.jobCompany}{application.jobLocation && <span> · 📍 {application.jobLocation}</span>}</p>
                  </div>
                  <div className="flex flex-shrink-0 items-center gap-3">
                    <StatusBadge status={application.status} />
                    <time dateTime={application.appliedAt} className="whitespace-nowrap text-xs font-semibold text-slate-400">{formatDate(application.appliedAt)}</time>
                  </div>
                </div>
                <div className="mt-3"><Stepper status={application.status} /></div>
              </article>
            ))}
            {applications.length > 5 && (
              <div className="border-t border-slate-100 bg-slate-50 px-6 py-4 text-center">
                <Link to="/my-applications" className="text-sm font-bold text-indigo-600 hover:underline">View all {applications.length} applications →</Link>
              </div>
            )}
          </div>
        )}
      </div>
    </section>
  );
}
