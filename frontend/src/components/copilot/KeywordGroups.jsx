import { Link } from "react-router-dom";

const groups = [
  ["strong", "Strong evidence", "border-emerald-200 bg-emerald-50"],
  ["supported", "Supported", "border-blue-200 bg-blue-50"],
  ["underrepresented", "Underrepresented", "border-amber-200 bg-amber-50"],
  ["missing", "Missing from your profile", "border-rose-200 bg-rose-50"],
];

export default function KeywordGroups({ analysis }) {
  return <div className="grid gap-4 md:grid-cols-2">
    {groups.map(([key, title, color]) => <section key={key} className={`rounded-2xl border p-5 ${color}`}>
      <h3 className="font-extrabold text-slate-900">{title}</h3>
      {(analysis?.[key] || []).length === 0 ? <p className="text-sm text-slate-500 mt-2">None identified.</p> : <ul className="mt-3 space-y-2">
        {analysis[key].map((item) => <li key={`${key}-${item.keyword}`} className="text-sm">
          <span className="font-bold">{item.keyword}</span> <span className="text-xs text-slate-500">{item.importance}</span>
          {item.evidence?.[0]?.sourceField && <p className="text-xs text-slate-600 mt-0.5">Evidence: {item.evidence[0].sourceField}</p>}
        </li>)}
      </ul>}
      {key === "missing" && (analysis?.missing || []).length > 0 && <p className="text-xs text-rose-800 mt-3">These are not inserted into resumes or cover letters. <Link className="font-bold underline" to="/my-profile">Update your profile only if you have real evidence.</Link></p>}
    </section>)}
  </div>;
}
