import { useEffect, useState } from "react";
import Header from "../components/Header";
import JobCard from "../components/jobs/JobCard";
import { getMatchedJobs } from "../services/job-service";

const emptyFilters = { minMatch: "0", workMode: "", employmentType: "", role: "", location: "", sort: "matchScore" };

export default function MatchedJobs() {
  const [filters, setFilters] = useState(emptyFilters);
  const [applied, setApplied] = useState(emptyFilters);
  const [page, setPage] = useState(0);
  const [data, setData] = useState(null);
  const [error, setError] = useState("");
  const [retry, setRetry] = useState(0);
  const [loadedKey, setLoadedKey] = useState("");
  const requestKey = `${page}:${JSON.stringify(applied)}:${retry}`;
  const loading = loadedKey !== requestKey;
  const limitedFeed = !loading && !error && data?.content?.length > 0
    && data.content.every(({ match }) => match?.confidence === "LOW" || match?.matchLevel === "LOW_DATA");

  useEffect(() => {
    let cancelled = false;
    const params = { page, size: 12, minMatch: Number(applied.minMatch), sort: applied.sort };
    for (const key of ["workMode", "employmentType", "role", "location"]) {
      if (applied[key]) params[key] = applied[key];
    }
    getMatchedJobs(params).then((response) => {
      if (!cancelled) {
        setData(response);
        setError("");
        setLoadedKey(requestKey);
      }
    }).catch((failure) => {
      if (cancelled) return;
      if (failure?.response?.status === 404) setError("Create or complete your candidate profile to see personalized matches.");
      else if (failure?.response?.status === 401 || failure?.response?.status === 403) setError("Your candidate session is no longer authorized. Please sign in again.");
      else setError("We couldn't calculate your job matches right now. Please try again.");
      setLoadedKey(requestKey);
    });
    return () => { cancelled = true; };
  }, [applied, page, requestKey]);

  const update = (event) => setFilters((value) => ({ ...value, [event.target.name]: event.target.value }));
  const apply = (event) => {
    event.preventDefault();
    setPage(0);
    setApplied(filters);
  };
  const clear = () => {
    setFilters(emptyFilters);
    setApplied(emptyFilters);
    setPage(0);
  };

  return (
    <div className="min-h-screen bg-slate-50">
      <Header />
      <main className="mx-auto max-w-7xl px-4 py-10 sm:px-6 lg:px-8">
        <div className="mb-8">
          <p className="text-sm font-bold uppercase tracking-wide text-indigo-600">Personalized for your candidate profile</p>
          <h1 className="mt-1 text-3xl font-extrabold text-slate-900">Best Matches for You</h1>
          <p className="mt-2 max-w-2xl text-sm text-slate-500">Scores compare your structured profile with each job. They are not hiring probabilities or official ATS scores.</p>
        </div>

        <form onSubmit={apply} className="mb-8 grid gap-3 rounded-2xl border border-slate-200 bg-white p-5 sm:grid-cols-2 lg:grid-cols-7" aria-label="Match filters">
          <label className="text-xs font-bold text-slate-600">Minimum match
            <select name="minMatch" value={filters.minMatch} onChange={update} className="mt-1 block w-full rounded-lg border border-slate-200 p-2 text-sm">
              <option value="0">Any score</option><option value="40">40%+</option><option value="60">60%+</option><option value="75">75%+</option><option value="90">90%+</option>
            </select>
          </label>
          <label className="text-xs font-bold text-slate-600">Work mode
            <select name="workMode" value={filters.workMode} onChange={update} className="mt-1 block w-full rounded-lg border border-slate-200 p-2 text-sm">
              <option value="">Any</option><option value="REMOTE">Remote</option><option value="HYBRID">Hybrid</option><option value="ONSITE">Onsite</option>
            </select>
          </label>
          <label className="text-xs font-bold text-slate-600">Employment
            <select name="employmentType" value={filters.employmentType} onChange={update} className="mt-1 block w-full rounded-lg border border-slate-200 p-2 text-sm">
              <option value="">Any</option><option value="FULL_TIME">Full-time</option><option value="INTERNSHIP">Internship</option><option value="CONTRACT">Contract</option><option value="PART_TIME">Part-time</option>
            </select>
          </label>
          <label className="text-xs font-bold text-slate-600">Role
            <select name="role" value={filters.role} onChange={update} className="mt-1 block w-full rounded-lg border border-slate-200 p-2 text-sm">
              <option value="">Any</option><option value="Backend Engineer">Backend</option><option value="Frontend Engineer">Frontend</option><option value="Full Stack Engineer">Full stack</option><option value="Software Engineer">Software engineering</option><option value="DevOps Engineer">DevOps</option><option value="Cloud Engineer">Cloud</option><option value="Data Engineer">Data engineering</option><option value="Data Scientist">Data science</option><option value="ML Engineer">ML / AI</option><option value="Security Engineer">Security</option><option value="QA Engineer">QA</option><option value="Mobile Engineer">Mobile</option>
            </select>
          </label>
          <label className="text-xs font-bold text-slate-600">Location
            <input name="location" value={filters.location} onChange={update} maxLength={100} placeholder="e.g. Pune" className="mt-1 block w-full rounded-lg border border-slate-200 p-2 text-sm" />
          </label>
          <label className="text-xs font-bold text-slate-600">Sort
            <select name="sort" value={filters.sort} onChange={update} className="mt-1 block w-full rounded-lg border border-slate-200 p-2 text-sm">
              <option value="matchScore">Best match</option><option value="newest">Newest</option><option value="oldest">Oldest</option>
            </select>
          </label>
          <div className="flex items-end gap-2"><button className="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-bold text-white">Apply</button><button type="button" onClick={clear} className="rounded-lg border border-slate-200 px-3 py-2 text-sm font-semibold text-slate-600">Clear</button></div>
        </form>

        {loading ? (
          <div className="grid gap-6 md:grid-cols-2 lg:grid-cols-3" aria-label="Loading matches">{[1, 2, 3, 4, 5, 6].map((value) => <div key={value} className="h-64 animate-pulse rounded-3xl bg-slate-200" />)}</div>
        ) : error ? (
          <div className="rounded-3xl border border-red-100 bg-white py-20 text-center"><p className="font-medium text-slate-600">{error}</p><button onClick={() => setRetry((value) => value + 1)} className="mt-5 rounded-lg bg-indigo-600 px-6 py-2 font-semibold text-white">Retry</button></div>
        ) : data.content.length === 0 ? (
          <div className="rounded-3xl border border-dashed border-slate-300 bg-white py-20 text-center"><h2 className="text-xl font-bold text-slate-700">No personalized matches found</h2><p className="mx-auto mt-2 max-w-md text-slate-500">Try broader filters, or add skills and job preferences to your profile for better matching.</p><button onClick={clear} className="mt-5 rounded-lg bg-indigo-50 px-5 py-2 font-semibold text-indigo-700">Clear filters</button></div>
        ) : (
          <>
            {limitedFeed && <div className="mb-5 rounded-xl bg-amber-50 p-4 text-sm font-medium text-amber-800"><strong>Limited profile data.</strong> Add skills and preferred roles to improve match accuracy.</div>}
            <p className="mb-5 text-sm font-semibold text-slate-500">{data.totalElements} compatible {data.totalElements === 1 ? "job" : "jobs"}</p>
            <div className="grid gap-6 md:grid-cols-2 lg:grid-cols-3">{data.content.map(({ job, match }) => <JobCard key={job.id} job={job} match={match} />)}</div>
            {data.totalPages > 1 && <nav className="mt-10 flex items-center justify-center gap-4" aria-label="Personalized job pages"><button disabled={data.first} onClick={() => setPage((value) => value - 1)} className="rounded-xl border bg-white px-5 py-2.5 font-semibold disabled:opacity-40">Previous</button><span className="text-sm font-semibold text-slate-600">Page {data.page + 1} of {data.totalPages}</span><button disabled={data.last} onClick={() => setPage((value) => value + 1)} className="rounded-xl border bg-white px-5 py-2.5 font-semibold disabled:opacity-40">Next</button></nav>}
          </>
        )}
      </main>
    </div>
  );
}
