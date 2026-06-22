import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import Header from "../components/Header";
import { getMyJobs } from "../services/job-service";
import { getApplicationsForJob } from "../services/application-service";

export default function Profile() {
  const navigate = useNavigate();
  const [myJobs, setMyJobs] = useState([]);
  const [loading, setLoading] = useState(true);
  const [expandedJobId, setExpandedJobId] = useState(null);
  const [applicants, setApplicants] = useState({});

  useEffect(() => {
    getMyJobs()
      .then((data) => { setMyJobs(data); setLoading(false); })
      .catch((err) => { console.error(err); setLoading(false); });
  }, []);

  const toggleJob = async (jobId) => {
    if (expandedJobId === jobId) { setExpandedJobId(null); return; }
    setExpandedJobId(jobId);
    if (!applicants[jobId]) {
      try {
        const data = await getApplicationsForJob(jobId);
        setApplicants((prev) => ({ ...prev, [jobId]: data }));
      } catch (err) { console.error(err); }
    }
  };

  return (
    <div className="min-h-screen bg-slate-50">
      <Header />
      <main className="max-w-5xl mx-auto px-4 py-12">
        <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between mb-10 gap-4">
          <div>
            <h1 className="text-3xl font-extrabold text-slate-900">Recruiter Dashboard</h1>
            <p className="text-slate-500 mt-1 font-medium">Manage your active job postings and applicants.</p>
          </div>
          <button onClick={() => navigate("/post-job")} className="bg-indigo-600 text-white px-6 py-3 rounded-xl text-sm font-bold hover:bg-indigo-700 shadow-md transition-all">
            + Post New Job
          </button>
        </div>

        {loading ? (
          <p className="text-slate-500 font-medium">Loading your dashboard...</p>
        ) : myJobs.length === 0 ? (
          <div className="bg-white p-12 rounded-3xl shadow-sm text-center border border-slate-200">
            <div className="text-5xl mb-4">📭</div>
            <h3 className="text-xl font-bold text-slate-800 mb-2">No active jobs</h3>
            <p className="text-slate-500 mb-6">You haven't posted any opportunities yet.</p>
            <button onClick={() => navigate("/post-job")} className="text-indigo-600 font-bold hover:underline">Create your first job post →</button>
          </div>
        ) : (
          <div className="space-y-4">
            {myJobs.map((job) => (
              <div key={job._id || job.id} className="bg-white rounded-2xl border border-slate-200 shadow-sm overflow-hidden transition-all">
                <div onClick={() => toggleJob(job._id || job.id)} className="p-6 flex items-center justify-between cursor-pointer hover:bg-slate-50 transition">
                  <div className="flex items-center gap-5">
                    <div className={`w-12 h-12 rounded-xl flex items-center justify-center font-bold text-lg text-white transition-colors ${expandedJobId === (job._id || job.id) ? 'bg-indigo-600' : 'bg-slate-300'}`}>
                      {job.title.charAt(0)}
                    </div>
                    <div>
                      <h3 className="font-bold text-lg text-slate-900">{job.title}</h3>
                      <p className="text-sm font-medium text-slate-500">📍 {job.location} &nbsp;•&nbsp; 💰 ₹{(job.salary/100000).toFixed(1)} LPA</p>
                    </div>
                  </div>
                  <div className="flex items-center gap-4">
                    <span className="bg-slate-100 text-slate-600 px-3 py-1 rounded-full text-xs font-bold uppercase tracking-wider">
                        {applicants[job._id || job.id] ? applicants[job._id || job.id].length : "View"} Applicants
                    </span>
                  </div>
                </div>

                {expandedJobId === (job._id || job.id) && (
                  <div className="border-t border-slate-100 bg-slate-50/50 p-6">
                    {!applicants[job._id || job.id] ? (
                      <p className="text-center text-sm font-medium text-slate-500 py-4">Fetching candidates...</p>
                    ) : applicants[job._id || job.id].length === 0 ? (
                      <p className="text-center text-sm font-medium text-slate-500 py-4">No candidates have applied yet.</p>
                    ) : (
                      <div className="bg-white rounded-xl border border-slate-200 overflow-hidden">
                        <table className="w-full text-sm text-left">
                          <thead className="bg-slate-50 border-b border-slate-200 text-slate-600 font-semibold">
                            <tr>
                              <th className="px-6 py-4">Candidate ID</th>
                              <th className="px-6 py-4">Date Applied</th>
                              <th className="px-6 py-4 text-right">Resume</th>
                            </tr>
                          </thead>
                          <tbody className="divide-y divide-slate-100">
                            {applicants[job._id || job.id].map((app) => (
                              <tr key={app.id} className="hover:bg-slate-50 transition-colors">
                                <td className="px-6 py-4 font-medium text-slate-800">{app.userId}</td>
                                <td className="px-6 py-4 text-slate-500 font-medium">{new Date(app.appliedAt).toLocaleDateString()}</td>
                                <td className="px-6 py-4 text-right">
                                  <a href={`http://localhost:8080/applications/download/${app.id}`} target="_blank" rel="noreferrer"
                                    className="inline-flex items-center gap-2 text-indigo-600 bg-indigo-50 px-3 py-1.5 rounded-lg hover:bg-indigo-100 font-bold transition-colors">
                                    Download PDF
                                  </a>
                                </td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    )}
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
      </main>
    </div>
  );
}