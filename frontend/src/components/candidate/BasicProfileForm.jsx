const inputClass = "mt-1 w-full rounded-xl border border-slate-300 px-3 py-2 font-normal";

export default function BasicProfileForm({ draft, setDraft }) {
  const field = (key, value) => setDraft((current) => ({ ...current, [key]: value }));
  const link = (key, value) => setDraft((current) => ({ ...current, links: { ...current.links, [key]: value } }));
  const preference = (key, value) => setDraft((current) => ({ ...current, preferences: { ...current.preferences, [key]: value } }));
  return (
    <>
      <section className="bg-white rounded-3xl border border-slate-200 shadow-sm p-6" aria-labelledby="profile-heading">
        <h2 id="profile-heading" className="text-xl font-extrabold text-slate-900">Basic information</h2>
        <div className="grid sm:grid-cols-2 gap-4 mt-5">
          <label className="text-sm font-semibold text-slate-700">Full name<input required minLength={2} maxLength={100} value={draft.fullName || ""} onChange={(event) => field("fullName", event.target.value)} className={inputClass} /></label>
          <label className="text-sm font-semibold text-slate-700">Account email<input readOnly value={draft.email || ""} className={`${inputClass} bg-slate-100 text-slate-500`} /></label>
          <label className="text-sm font-semibold text-slate-700">Phone<input maxLength={30} value={draft.phone || ""} onChange={(event) => field("phone", event.target.value)} className={inputClass} /></label>
          <label className="text-sm font-semibold text-slate-700">Location<input maxLength={160} value={draft.location || ""} onChange={(event) => field("location", event.target.value)} className={inputClass} /></label>
          <label className="sm:col-span-2 text-sm font-semibold text-slate-700">Professional summary<textarea maxLength={3000} rows={4} value={draft.professionalSummary || ""} onChange={(event) => field("professionalSummary", event.target.value)} className={inputClass} /></label>
        </div>
      </section>

      <section className="bg-white rounded-3xl border border-slate-200 shadow-sm p-6" aria-labelledby="links-heading">
        <h2 id="links-heading" className="text-xl font-extrabold text-slate-900">Professional links</h2>
        <div className="grid sm:grid-cols-2 gap-4 mt-5">
          {[['linkedIn', 'LinkedIn'], ['github', 'GitHub'], ['portfolio', 'Portfolio'], ['website', 'Personal website']].map(([key, label]) => (
            <label key={key} className="text-sm font-semibold text-slate-700">{label}<input type="url" maxLength={2048} value={draft.links?.[key] || ""} onChange={(event) => link(key, event.target.value)} className={inputClass} placeholder="https://" /></label>
          ))}
        </div>
      </section>

      <section className="bg-white rounded-3xl border border-slate-200 shadow-sm p-6" aria-labelledby="preferences-heading">
        <h2 id="preferences-heading" className="text-xl font-extrabold text-slate-900">Job preferences</h2>
        <p className="text-sm text-slate-500 mt-1">A foundation for future matching; no match percentage is calculated in this phase.</p>
        <div className="grid sm:grid-cols-2 gap-4 mt-5">
          <label className="text-sm font-semibold text-slate-700">Preferred job titles<input value={(draft.preferences?.preferredJobTitles || []).join(", ")} onChange={(event) => preference("preferredJobTitles", list(event.target.value))} className={inputClass} placeholder="Backend Engineer, Java Developer" /></label>
          <label className="text-sm font-semibold text-slate-700">Preferred locations<input value={(draft.preferences?.preferredLocations || []).join(", ")} onChange={(event) => preference("preferredLocations", list(event.target.value))} className={inputClass} placeholder="Pune, Bengaluru" /></label>
          <label className="text-sm font-semibold text-slate-700">Remote preference<select value={draft.preferences?.remotePreference || ""} onChange={(event) => preference("remotePreference", event.target.value)} className={inputClass}>
            <option value="">No preference</option><option value="REMOTE">Remote</option><option value="HYBRID">Hybrid</option><option value="ONSITE">On-site</option><option value="FLEXIBLE">Flexible</option>
          </select></label>
          <label className="text-sm font-semibold text-slate-700">Employment types<input value={(draft.preferences?.employmentTypes || []).join(", ")} onChange={(event) => preference("employmentTypes", list(event.target.value))} className={inputClass} placeholder="FULL_TIME, CONTRACT" /></label>
          <label className="text-sm font-semibold text-slate-700">Minimum annual salary<input type="number" min="0" value={draft.preferences?.minimumSalary ?? ""} onChange={(event) => preference("minimumSalary", event.target.value === "" ? null : Number(event.target.value))} className={inputClass} /></label>
        </div>
      </section>
    </>
  );
}

const list = (value) => value.split(",").map((item) => item.trim()).filter(Boolean);
