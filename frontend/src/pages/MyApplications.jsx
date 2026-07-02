import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import Header from "../components/Header";
import Footer from "../components/Footer";
import Stepper from "../components/Stepper";
import { getMyApplications } from "../services/application-service";



export default function MyApplications() {
  const [applications, setApplications] = useState([]);
  const [loading, setLoading] = useState(true);
  const navigate = useNavigate();

  useEffect(() => {
    getMyApplications()
      .then((data) => {
        setApplications(data);
        setLoading(false);
      })
      .catch((err) => {
        console.error("Failed to load applications", err);
        setLoading(false);
      });
  }, []);

  const formatDate = (dateStr) => {
    if (!dateStr) return "";
    return new Date(dateStr).toLocaleDateString("en-US", {
      year: "numeric",
      month: "long",
      day: "numeric",
    });
  };

  return (
    <div className="min-h-screen bg-slate-50 flex flex-col">
      <Header />
      
      <main className="flex-1 max-w-4xl w-full mx-auto px-4 py-12">
        <div className="mb-10">
          <h1 className="text-3xl font-extrabold text-slate-900">My Applications</h1>
          <p className="text-slate-500 mt-2 font-medium">
            Track the progress of jobs you have applied to.
          </p>
        </div>

        {loading ? (
          <div className="space-y-6">
            {[1, 2, 3].map((n) => (
              <div key={n} className="bg-white rounded-3xl p-8 border border-slate-200 shadow-sm animate-pulse h-64"></div>
            ))}
          </div>
        ) : applications.length === 0 ? (
          <div className="text-center py-24 bg-white rounded-3xl border border-dashed border-slate-300">
            <div className="text-5xl mb-4">💼</div>
            <h3 className="text-xl font-bold text-slate-700 mb-2">No applications yet</h3>
            <p className="text-slate-500 max-w-sm mx-auto mb-6">
              You haven't applied to any job postings. Start exploring opportunities!
            </p>
            <button 
              onClick={() => navigate("/jobs")} 
              className="bg-indigo-600 hover:bg-indigo-700 text-white font-bold px-6 py-3 rounded-xl shadow-md transition-all"
            >
              Browse Jobs
            </button>
          </div>
        ) : (
          <div className="space-y-6">
            {applications.map((app) => (
              <div 
                key={app.applicationId}
                className="bg-white rounded-3xl border border-slate-200 shadow-sm overflow-hidden transition-all duration-300 hover:shadow-md"
              >
                {/* Job Info Header */}
                <div className="p-6 sm:p-8 border-b border-slate-100 flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4 bg-slate-50/30">
                  <div>
                    <div className="flex items-center gap-2 flex-wrap">
                      <h3 className="text-xl font-bold text-slate-900 hover:text-indigo-600 transition-colors cursor-pointer" onClick={() => navigate(`/jobs/${app.jobId}`)}>
                        {app.jobTitle}
                      </h3>
                      {app.sourceUrl && (
                        <span className="inline-flex items-center text-[10px] font-bold text-indigo-700 bg-indigo-50 border border-indigo-100 px-2 py-0.5 rounded-full uppercase tracking-wider">
                          External Job
                        </span>
                      )}
                    </div>
                    <p className="text-slate-500 font-medium mt-1">{app.jobCompany}</p>
                    <div className="flex gap-4 mt-3 text-xs text-slate-400 font-semibold">
                      <span>📍 {app.jobLocation}</span>
                      <span>💰 ₹{app.jobSalary ? (app.jobSalary / 100000).toFixed(1) + ' LPA' : 'Not Disclosed'}</span>
                      <span>📅 Applied {formatDate(app.appliedAt)}</span>
                    </div>
                  </div>
                </div>

                {/* Pipeline Stepper */}
                <div className="px-6 py-8 sm:px-8">
                  <Stepper status={app.status} />
                </div>

                {/* Congratulations or rejection banner */}
                {app.status === "ACCEPTED" && (
                  <div className="bg-green-50 border-t border-green-100 px-6 py-4 sm:px-8 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
                    <span className="text-green-700 font-semibold text-sm">
                      🎉 Congratulations! You have been accepted for this role.
                    </span>
                    <button
                      onClick={() => navigate("/chat")}
                      className="self-start sm:self-center bg-green-600 hover:bg-green-700 text-white font-bold text-xs px-4.5 py-2 rounded-xl transition-all shadow-sm flex items-center gap-1.5"
                    >
                      💬 Open Chat
                    </button>
                  </div>
                )}
                {app.status === "REJECTED" && (
                  <div className="bg-slate-50 border-t border-slate-100 px-6 py-4 sm:px-8 text-slate-500 font-medium text-sm flex items-center gap-2">
                    Thank you for your interest and the time you spent applying. Unfortunately, the company decided not to move forward.
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
      </main>

      <Footer />
    </div>
  );
}
