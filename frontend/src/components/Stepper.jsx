/**
 * Shared Stepper component for rendering the application pipeline progress.
 * Used by both MyApplications.jsx and MyProfile.jsx.
 *
 * Props:
 *   status {string} — one of: "APPLIED", "IN_REVIEW", "SHORTLISTED", "ACCEPTED", "REJECTED"
 */
export default function Stepper({ status }) {
  const currentStatus = status || "APPLIED";

  let displayStages = ["APPLIED", "IN_REVIEW", "SHORTLISTED", "ACCEPTED"];
  const isRejected = currentStatus === "REJECTED";
  const isAccepted = currentStatus === "ACCEPTED";

  if (isRejected) {
    displayStages = ["APPLIED", "IN_REVIEW", "SHORTLISTED", "REJECTED"];
  }

  const currentIndex = displayStages.indexOf(currentStatus);

  const formatStageLabel = (stage) => {
    switch (stage) {
      case "APPLIED":      return "Applied";
      case "IN_REVIEW": return "In Review";
      case "SHORTLISTED":  return "Shortlisted";
      case "ACCEPTED":     return "Accepted";
      case "REJECTED":     return "Rejected";
      default:             return stage;
    }
  };

  return (
    <div className="w-full py-4">
      <div className="flex items-center justify-between relative">
        {/* Background connecting line */}
        <div className="absolute left-0 right-0 top-1/2 -translate-y-1/2 h-[2px] bg-slate-200 z-0"></div>
        {/* Active progress line */}
        <div
          className={`absolute left-0 top-1/2 -translate-y-1/2 h-[2px] z-0 transition-all duration-500 ${
            isAccepted ? "bg-green-500" : isRejected ? "bg-red-500" : "bg-indigo-600"
          }`}
          style={{ width: `${(currentIndex / (displayStages.length - 1)) * 100}%` }}
        ></div>

        {displayStages.map((stage, idx) => {
          const isCompleted = idx <= currentIndex;
          const isCurrent   = idx === currentIndex;

          let circleColor = "bg-slate-200 border-slate-300 text-slate-500";
          let labelColor  = "text-slate-400 font-medium";

          if (isAccepted) {
            if (isCompleted) {
              circleColor = "bg-green-500 border-green-500 text-white shadow-lg shadow-green-100";
              labelColor  = "text-green-600 font-bold";
            }
          } else if (isRejected) {
            if (isCompleted) {
              if (stage === "REJECTED") {
                circleColor = "bg-red-500 border-red-500 text-white shadow-lg shadow-red-100";
                labelColor  = "text-red-600 font-bold";
              } else {
                circleColor = "bg-slate-400 border-slate-400 text-white";
                labelColor  = "text-slate-500 font-medium";
              }
            }
          } else {
            if (isCurrent) {
              circleColor = "bg-indigo-600 border-indigo-600 text-white shadow-lg shadow-indigo-100 animate-pulse";
              labelColor  = "text-indigo-600 font-bold";
            } else if (isCompleted) {
              circleColor = "bg-indigo-500 border-indigo-500 text-white";
              labelColor  = "text-indigo-500 font-semibold";
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

      {/* Mobile stage labels */}
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
}
