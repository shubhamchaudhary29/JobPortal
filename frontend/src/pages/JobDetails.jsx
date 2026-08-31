import { useEffect, useState } from "react";
import { useParams, useNavigate } from "react-router-dom";
import Header from "../components/Header";
import ApplyJobModal from "../components/ApplyJobModal";
import JobMatchPanel from "../components/jobs/JobMatchPanel";
import { useAuth } from "../auth/auth-context";
import { getJobById, getJobMatch } from "../services/job-service";
import { hasUserApplied } from "../services/application-service";

const formatDescription = (text) => {
  if (!text) return [];

  let cleaned = text;

  // Convert paragraph boundaries and breaks to newlines to preserve spacing
  cleaned = cleaned
    .replace(/<br\s*\/?>/gi, "\n")
    .replace(/<\/p>/gi, "\n\n")
    .replace(/<p>/gi, "");

  // Decode HTML entities so that encoded tags (e.g. &lt;strong&gt;) become real tags
  cleaned = cleaned
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&nbsp;/g, " ")
    .replace(/&#39;/g, "'")
    .replace(/&quot;/g, '"');

  // Strip remaining HTML tags
  cleaned = cleaned.replace(/<[^>]*>/g, "");

  // Decode HTML entities again in case some were nested
  cleaned = cleaned
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&nbsp;/g, " ")
    .replace(/&#39;/g, "'")
    .replace(/&quot;/g, '"');

  // Split on double newlines and filter empty lines
  return cleaned
    .split(/\n\s*\n/)
    .map(p => p.trim())
    .filter(p => p.length > 0);
};

export default function JobDetails() {
  const { jobId } = useParams();
  const navigate = useNavigate();

  const [job, setJob] = useState(null);
  const [loading, setLoading] = useState(true);
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [hasApplied, setHasApplied] = useState(false);
  const [match, setMatch] = useState(null);
  const [matchError, setMatchError] = useState("");
  const [matchRetry, setMatchRetry] = useState(0);
  const [loadedMatchKey, setLoadedMatchKey] = useState("");

  const { accessToken, role } = useAuth();
  const isLoggedIn = Boolean(accessToken);
  const isCandidate = isLoggedIn && role === "USER";
  const matchRequestKey = `${jobId}:${matchRetry}`;
  const matchLoading = isCandidate && loadedMatchKey !== matchRequestKey;

  useEffect(() => {
    if (jobId) {
      getJobById(jobId)
        .then((data) => {
          setJob(data);
          setLoading(false);
        })
        .catch(() => setLoading(false));

      if (isCandidate) {
        hasUserApplied(jobId)
          .then((status) => setHasApplied(status))
          .catch((err) => console.error(err));
      }
    }
  }, [jobId, isCandidate]);

  useEffect(() => {
    if (!jobId || !isCandidate) {
      return;
    }
    let cancelled = false;
    getJobMatch(jobId).then((response) => {
      if (!cancelled) {
        setMatch(response);
        setMatchError("");
      }
    }).catch((failure) => {
      if (cancelled) return;
      if (failure?.response?.status === 404) setMatchError("This job is no longer available for matching.");
      else setMatchError("We couldn't calculate this match right now.");
    }).finally(() => {
      if (!cancelled) setLoadedMatchKey(matchRequestKey);
    });
    return () => { cancelled = true; };
  }, [isCandidate, jobId, matchRequestKey]);

  if (loading) return <div className="min-h-screen bg-slate-50 flex items-center justify-center">Loading...</div>;
  if (!job) return <div className="min-h-screen bg-slate-50 flex items-center justify-center">Job not found</div>;

  return (
    <div className="min-h-screen bg-slate-50">
      <Header />
      
      <ApplyJobModal 
        isOpen={isModalOpen} 
        onClose={() => setIsModalOpen(false)} 
        onSuccess={() => { setHasApplied(true); setIsModalOpen(false); }}
        jobId={job._id || job.id} 
        jobTitle={job.title}
      />

      <main className="max-w-4xl mx-auto px-4 py-10">
        <button onClick={() => navigate(-1)} className="mb-6 text-slate-500 hover:text-indigo-600 flex items-center gap-2 transition-colors">
          &larr; Back to jobs
        </button>

        <div className="bg-white rounded-3xl shadow-sm border border-slate-200 overflow-hidden">
          <div className="p-8 border-b border-slate-100 flex flex-col md:flex-row justify-between gap-6">
            <div className="flex gap-6">
              <div className="w-20 h-20 rounded-2xl bg-indigo-50 flex items-center justify-center text-3xl font-bold text-indigo-600">
                {job.company?.charAt(0)}
              </div>
              <div>
                <h1 className="text-2xl font-bold text-slate-900">{job.title}</h1>
                <p className="text-slate-500 font-medium text-lg">{job.company}</p>
                <div className="flex gap-4 mt-3 text-sm text-slate-500">
                  <span className="flex items-center gap-1">📍 {job.location}</span>
                  <span className="flex items-center gap-1">💼 {job.experience}+ Years</span>
                  <span className="flex items-center gap-1">💰 ₹{job.salary?.toLocaleString()}</span>
                </div>
              </div>
            </div>

            <div className="flex flex-col justify-center min-w-[150px]">
              {!isLoggedIn ? (
                <button 
                  onClick={() => navigate("/login")} 
                  className="w-full bg-slate-900 text-white py-3 rounded-xl font-semibold hover:bg-slate-800 transition shadow-lg shadow-slate-200"
                >
                  Login to Apply
                </button>
              ) : job.sourceUrl ? (
                <a 
                  href={job.sourceUrl} 
                  target="_blank" 
                  rel="noopener noreferrer"
                  className="w-full bg-indigo-600 text-white py-3 rounded-xl font-semibold hover:bg-indigo-700 transition shadow-lg shadow-indigo-200 text-center block"
                >
                  Apply Now ↗
                </a>
              ) : (
                <button 
                  onClick={() => !hasApplied && setIsModalOpen(true)}
                  disabled={hasApplied}
                  className={`w-full py-3 rounded-xl font-semibold transition shadow-lg
                    ${hasApplied 
                      ? "bg-green-100 text-green-700 cursor-default border border-green-200" 
                      : "bg-indigo-600 text-white hover:bg-indigo-700 shadow-indigo-200"
                    }`}
                >
                  {hasApplied ? "✓ Application Sent" : "Apply Now"}
                </button>
              )}
            </div>
          </div>

          <div className="p-8">
            <h2 className="text-lg font-bold text-slate-900 mb-4">About the role</h2>
            <div className="prose prose-slate max-w-none text-slate-600 whitespace-pre-wrap leading-relaxed">
              {formatDescription(job.description).map((para, index) => (
                <p key={index} className="mb-4 last:mb-0">{para}</p>
              ))}
            </div>
          </div>
        </div>
        {isCandidate && <JobMatchPanel match={match} loading={matchLoading} error={matchError} onRetry={() => setMatchRetry((value) => value + 1)} />}
      </main>
    </div>
  );
}
