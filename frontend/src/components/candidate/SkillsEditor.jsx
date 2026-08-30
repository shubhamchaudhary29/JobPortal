import { useState } from "react";

const key = (value) => value.trim().toLowerCase().replace(/[\s_-]+/g, "");

export default function SkillsEditor({ skills = [], onChange }) {
  const [value, setValue] = useState("");
  const [message, setMessage] = useState("");
  const add = () => {
    const clean = value.trim();
    if (!clean) return;
    if (skills.some((skill) => key(skill.name) === key(clean))) {
      setMessage("That skill is already listed."); return;
    }
    onChange([...skills, { name: clean, originalName: null, category: null, confidence: null, source: "MANUAL" }]);
    setValue(""); setMessage("");
  };
  return (
    <section className="bg-white rounded-3xl border border-slate-200 shadow-sm p-6" aria-labelledby="skills-heading">
      <h2 id="skills-heading" className="text-xl font-extrabold text-slate-900">Skills</h2>
      <p className="text-sm text-slate-500 mt-1">Aliases and case variants are normalized when you save.</p>
      <div className="flex gap-2 mt-4">
        <input aria-label="New skill" value={value} maxLength={80} onChange={(event) => { setValue(event.target.value); setMessage(""); }}
          onKeyDown={(event) => { if (event.key === "Enter") { event.preventDefault(); add(); } }}
          className="flex-1 rounded-xl border border-slate-300 px-3 py-2" placeholder="e.g. Spring Boot" />
        <button type="button" onClick={add} className="px-4 py-2 rounded-xl bg-slate-900 text-white font-bold">Add skill</button>
      </div>
      {message && <p role="alert" className="text-sm text-amber-700 mt-2">{message}</p>}
      <div className="flex flex-wrap gap-2 mt-4">
        {skills.length === 0 && <p className="text-sm text-slate-500">No skills added yet.</p>}
        {skills.map((skill, index) => (
          <span key={`${skill.name}-${index}`} className="inline-flex items-center gap-2 bg-indigo-50 text-indigo-800 border border-indigo-100 px-3 py-1.5 rounded-full text-sm font-semibold">
            {skill.name}
            <button type="button" aria-label={`Remove ${skill.name}`} onClick={() => onChange(skills.filter((_, item) => item !== index))} className="text-indigo-500 hover:text-red-600">×</button>
          </span>
        ))}
      </div>
    </section>
  );
}
