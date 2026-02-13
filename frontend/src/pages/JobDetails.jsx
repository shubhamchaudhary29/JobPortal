import { useEffect, useState } from "react";
import { useParams, useNavigate } from "react-router-dom";
import Header from "../components/Header";
import ApplyJobModal from "../components/ApplyJobModal";
import { getJobById } from "../services/job-service";
import { hasUserApplied } from "../services/application-service";

export default function JobDetails() {
  const { jobId } = useParams();
  const navigate = useNavigate();

  const [job, setJob] = useState(null);
  const [loading, setLoading] = useState(true);
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [hasApplied, setHasApplied] = useState(false);

  const isLoggedIn = Boolean(localStorage.getItem("token"));

  useEffect(() => {
    if (jobId) {
      getJobById(jobId)
        .then((data) => {
          setJob(data);
          setLoading(false);
        })
        .catch((err) => console.error(err));

      if (isLoggedIn) {
        hasUserApplied(jobId)
          .then((status) => setHasApplied(status))
          .catch((err) => console.error(err));
      }
    }
  }, [jobId, isLoggedIn]);

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
              {job.description}
            </div>
          </div>
        </div>
      </main>
    </div>
  );
}