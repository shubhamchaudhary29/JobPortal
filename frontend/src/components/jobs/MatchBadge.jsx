const labels = {
  EXCELLENT: "Excellent",
  STRONG: "Strong",
  MODERATE: "Moderate",
  WEAK: "Weak",
  LOW: "Low",
};

const colors = {
  EXCELLENT: "bg-emerald-50 text-emerald-700 border-emerald-200",
  STRONG: "bg-green-50 text-green-700 border-green-200",
  MODERATE: "bg-amber-50 text-amber-700 border-amber-200",
  WEAK: "bg-orange-50 text-orange-700 border-orange-200",
  LOW: "bg-slate-100 text-slate-600 border-slate-200",
};

export default function MatchBadge({ match }) {
  if (!match) return null;
  if (match.matchLevel === "LOW_DATA" || match.confidence === "LOW") {
    return (
      <span className="inline-flex rounded-full border border-slate-200 bg-slate-50 px-3 py-1.5 text-xs font-bold text-slate-600">
        Limited profile data
      </span>
    );
  }
  const level = match.matchLevel || "LOW";
  return (
    <span className={`inline-flex rounded-full border px-3 py-1.5 text-xs font-bold ${colors[level] || colors.LOW}`}
      aria-label={`${Math.round(match.overallScore)} percent profile job compatibility, ${labels[level] || "Low"}`}>
      {Math.round(match.overallScore)}% Match · {labels[level] || "Low"}
    </span>
  );
}
