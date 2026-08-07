import { useEffect, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import Header from "../components/Header";
import { getAllJobs } from "../services/job-service";

export default function Jobs() {
  const [jobs, setJobs] = useState([]);
  const [error, setError] = useState("");
  const [retryKey, setRetryKey] = useState(0);
  const [loadedKey, setLoadedKey] = useState("");
  const [pageData, setPageData] = useState({ page: 0, totalElements: 0, totalPages: 0, first: true, last: true });
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();

  const roleQuery = searchParams.get("role")?.toLowerCase() || "";
  const locationQuery = searchParams.get("location") || "";
  const page = Math.max(0, Number.parseInt(searchParams.get("page") || "0", 10) || 0);
  const requestKey = `${page}:${roleQuery}:${locationQuery}:${retryKey}`;
  const loading = loadedKey !== requestKey;

  useEffect(() => {
    let cancelled = false;
    getAllJobs({ page, size: 20, sort: "createdAt,desc", q: roleQuery || undefined, location: locationQuery || undefined })
      .then((data) => {
        if (cancelled) return;
        setJobs(data.content);
        setPageData(data);
        setError("");
        setLoadedKey(requestKey);
      })
      .catch(() => {
        if (cancelled) return;
        setError("We couldn't load jobs right now. Please try again.");
        setLoadedKey(requestKey);
      });
    return () => { cancelled = true; };
  }, [locationQuery, page, requestKey, roleQuery]);

  const changePage = (nextPage) => {
    const params = new URLSearchParams(searchParams);
    if (nextPage === 0) params.delete("page"); else params.set("page", String(nextPage));
    navigate({ pathname: "/jobs", search: params.toString() });
  };

  return (
    <div className="min-h-screen bg-slate-50">
      <Header />

      <main className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-10">
        <div className="mb-10">
          <h1 className="text-3xl font-extrabold text-slate-900">
            {roleQuery || locationQuery ? "Search Results" : "Explore Opportunities"}
          </h1>
          <p className="text-slate-500 mt-2 font-medium">
            {pageData.totalElements} {pageData.totalElements === 1 ? 'job' : 'jobs'} found based on your preferences.
          </p>
        </div>

        {loading ? (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
            {[1, 2, 3, 4, 5, 6].map((n) => (
              <div key={n} className="h-56 bg-slate-200 rounded-2xl animate-pulse"></div>
            ))}
          </div>
        ) : error ? (
          <div className="text-center py-20 bg-white rounded-3xl border border-red-100">
            <p className="text-slate-600 font-medium">{error}</p>
            <button onClick={() => setRetryKey((value) => value + 1)} className="mt-5 bg-indigo-600 text-white font-semibold px-6 py-2 rounded-lg">Retry</button>
          </div>
        ) : jobs.length === 0 ? (
          <div className="text-center py-24 bg-white rounded-3xl border border-dashed border-slate-300">
            <div className="text-5xl mb-4">🕵️‍♂️</div>
            <h3 className="text-xl font-bold text-slate-700 mb-2">No jobs found</h3>
            <p className="text-slate-500 max-w-sm mx-auto">We couldn't find any matches for your current filters.</p>
            <button onClick={() => navigate('/jobs')} className="mt-6 bg-indigo-50 text-indigo-600 font-semibold px-6 py-2 rounded-lg hover:bg-indigo-100 transition">
              Clear all filters
            </button>
          </div>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
            {jobs.map((job) => (
              <div
                key={job._id || job.id}
                onClick={() => navigate(`/jobs/${job._id || job.id}`)}
                className="group bg-white rounded-3xl p-6 border border-slate-100 shadow-sm hover:shadow-xl hover:border-indigo-100 hover:-translate-y-1 transition-all duration-300 cursor-pointer flex flex-col justify-between"
              >
                <div>
                  <div className="flex justify-between items-start mb-5">
                    <div className="w-14 h-14 rounded-2xl bg-gradient-to-br from-indigo-50 to-purple-50 flex items-center justify-center text-indigo-600 font-bold text-2xl shadow-inner">
                      {job.company?.charAt(0)}
                    </div>
                    <span className="bg-green-50 text-green-600 text-xs px-3 py-1.5 rounded-full font-bold uppercase tracking-wide">
                      Actively Hiring
                    </span>
                  </div>
                  
                  <h3 className="text-xl font-bold text-slate-900 group-hover:text-indigo-600 transition-colors line-clamp-1">
                    {job.title}
                  </h3>
                  <p className="text-slate-500 font-medium mt-1">{job.company}</p>
                  
                  <div className="mt-5 flex flex-wrap gap-2">
                    <span className="inline-flex items-center text-xs font-semibold text-slate-600 bg-slate-100 px-2.5 py-1.5 rounded-lg">
                      📍 {job.location}
                    </span>
                    <span className="inline-flex items-center text-xs font-semibold text-slate-600 bg-slate-100 px-2.5 py-1.5 rounded-lg">
                      💼 {job.experience}+ Yrs
                    </span>
                    <span className="inline-flex items-center text-xs font-semibold text-slate-600 bg-slate-100 px-2.5 py-1.5 rounded-lg">
                      💰 ₹{job.salary ? (job.salary / 100000).toFixed(1) + ' LPA' : 'Not Disclosed'}
                    </span>
                  </div>
                </div>

                <div className="mt-6 pt-5 border-t border-slate-50 flex items-center justify-between">
                   <span className="text-sm font-medium text-slate-400">Apply instantly</span>
                   <span className="text-indigo-600 text-sm font-bold group-hover:translate-x-2 transition-transform flex items-center gap-1">
                     View Role <span className="text-lg">→</span>
                   </span>
                </div>
              </div>
            ))}
          </div>
        )}

        {!loading && !error && pageData.totalPages > 1 && (
          <nav className="mt-10 flex items-center justify-center gap-4" aria-label="Job results pages">
            <button disabled={pageData.first} onClick={() => changePage(page - 1)} className="px-5 py-2.5 rounded-xl border bg-white font-semibold disabled:opacity-40">Previous</button>
            <span className="text-sm font-semibold text-slate-600">Page {pageData.page + 1} of {pageData.totalPages}</span>
            <button disabled={pageData.last} onClick={() => changePage(page + 1)} className="px-5 py-2.5 rounded-xl border bg-white font-semibold disabled:opacity-40">Next</button>
          </nav>
        )}
      </main>
    </div>
  );
}
