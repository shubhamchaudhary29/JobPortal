import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import Header from "../components/Header";
import Footer from "../components/Footer";
import { getMyApplications } from "../services/application-service";

const Stepper = ({ status }) => {
  const currentStatus = status || "APPLIED";
  
  // Stages depending on status
  let displayStages = ["APPLIED", "UNDER_REVIEW", "SHORTLISTED", "ACCEPTED"];
  const isRejected = currentStatus === "REJECTED";
  const isAccepted = currentStatus === "ACCEPTED";

  if (isRejected) {
    displayStages = ["APPLIED", "UNDER_REVIEW", "SHORTLISTED", "REJECTED"];
  }

  const currentIndex = displayStages.indexOf(currentStatus);

  const formatStageLabel = (stage) => {
    switch (stage) {
      case "APPLIED": return "Applied";
      case "UNDER_REVIEW": return "Under Review";
      case "SHORTLISTED": return "Shortlisted";
      case "ACCEPTED": return "Accepted";
      case "REJECTED": return "Rejected";
      default: return stage;
    }
  };

  return (
    <div className="w-full py-4">
      <div className="flex items-center justify-between relative">
        {/* Connecting Line */}
        <div className="absolute left-0 right-0 top-1/2 -translate-y-1/2 h-[2px] bg-slate-200 z-0"></div>
        {/* Active connection line */}
        <div 
          className={`absolute left-0 top-1/2 -translate-y-1/2 h-[2px] z-0 transition-all duration-500 ${
            isAccepted ? "bg-green-500" : isRejected ? "bg-red-500" : "bg-indigo-600"
          }`}
          style={{ width: `${(currentIndex / (displayStages.length - 1)) * 100}%` }}
        ></div>

        {displayStages.map((stage, idx) => {
          const isCompleted = idx <= currentIndex;
          const isCurrent = idx === currentIndex;
          
          let circleColor = "bg-slate-200 border-slate-300 text-slate-500";
          let labelColor = "text-slate-400 font-medium";

          if (isAccepted) {
            if (isCompleted) {
              circleColor = "bg-green-500 border-green-500 text-white shadow-lg shadow-green-100";
              labelColor = "text-green-600 font-bold";
            }
          } else if (isRejected) {
            if (isCompleted) {
              if (stage === "REJECTED") {
                circleColor = "bg-red-500 border-red-500 text-white shadow-lg shadow-red-100";
                labelColor = "text-red-600 font-bold";
              } else {
                circleColor = "bg-slate-400 border-slate-400 text-white";
                labelColor = "text-slate-500 font-medium";
              }
            }
          } else {
            if (isCurrent) {
              circleColor = "bg-indigo-600 border-indigo-600 text-white shadow-lg shadow-indigo-100 animate-pulse";
              labelColor = "text-indigo-600 font-bold";
            } else if (isCompleted) {
              circleColor = "bg-indigo-500 border-indigo-500 text-white";
              labelColor = "text-indigo-500 font-semibold";
            }
          }

          return (
            <div key={stage} className="flex flex-col items-center z-10 relative">
              <div className={`w-8 h-8 sm:w-10 sm:h-10 rounded-full border-2 flex items-center justify-center text-sm font-bold transition-all duration-300 ${circleColor}`}>
                {isCompleted ? "✓" : idx + 1}
              </div>
              <span className={`text-xs mt-2 text-center whitespace-nowrap hidden sm:inline ${labelColor}`}>
                {formatStageLabel(stage)}
              </span>
            </div>
          );
        })}
      </div>
      {/* Mobile labels */}
      <div className="flex justify-between mt-2 sm:hidden px-1">
        {displayStages.map((stage, idx) => {
          const isCurrent = idx === currentIndex;
          const labelColor = isCurrent
            ? isAccepted ? "text-green-600 font-bold" : isRejected ? "text-red-600 font-bold" : "text-indigo-600 font-bold"
            : "text-slate-400";
          return (
            <span key={stage} className={`text-[10px] text-center ${labelColor}`}>
              {formatStageLabel(stage)}
            </span>
          );
        })}
      </div>
    </div>
  );
};

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
                  <div className="bg-green-50 border-t border-green-100 px-6 py-4 sm:px-8 text-green-700 font-semibold text-sm flex items-center gap-2">
                    🎉 Congratulations! You have been accepted for this role. The recruiter will reach out soon.
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
