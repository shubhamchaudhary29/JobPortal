export default function CollectionEditor({ title, description, items = [], fields, emptyItem, onChange }) {
  const setField = (index, field, raw) => {
    const value = field.type === "list" ? raw.split(",").map((item) => item.trim()).filter(Boolean)
      : field.type === "checkbox" ? raw : raw;
    onChange(items.map((item, itemIndex) => itemIndex === index ? { ...item, [field.key]: value } : item));
  };
  return (
    <section className="bg-white rounded-3xl border border-slate-200 shadow-sm p-6">
      <div className="flex items-start justify-between gap-4">
        <div><h2 className="text-xl font-extrabold text-slate-900">{title}</h2><p className="text-sm text-slate-500 mt-1">{description}</p></div>
        <button type="button" onClick={() => onChange([...items, { ...emptyItem }])} className="shrink-0 px-3 py-2 rounded-xl bg-indigo-50 text-indigo-700 text-sm font-bold">Add {title.toLowerCase().replace(/s$/, "")}</button>
      </div>
      {items.length === 0 && <p className="mt-5 rounded-xl bg-slate-50 p-4 text-sm text-slate-500">No {title.toLowerCase()} added yet.</p>}
      <div className="space-y-5 mt-5">
        {items.map((item, index) => (
          <fieldset key={index} className="border border-slate-200 rounded-2xl p-4 grid sm:grid-cols-2 gap-4">
            <legend className="px-2 text-sm font-bold text-slate-600">{title.replace(/s$/, "")} {index + 1}</legend>
            {fields.map((field) => field.type === "checkbox" ? (
              <label key={field.key} className="flex items-center gap-2 text-sm font-semibold text-slate-700 self-end pb-2">
                <input type="checkbox" checked={Boolean(item[field.key])} onChange={(event) => setField(index, field, event.target.checked)} /> {field.label}
              </label>
            ) : (
              <label key={field.key} className={`text-sm font-semibold text-slate-700 ${field.wide ? "sm:col-span-2" : ""}`}>
                {field.label}
                {field.type === "textarea" ? (
                  <textarea value={item[field.key] || ""} maxLength={field.maxLength || 6000} rows={3} onChange={(event) => setField(index, field, event.target.value)}
                    className="mt-1 w-full rounded-xl border border-slate-300 px-3 py-2 font-normal" />
                ) : (
                  <input type={field.type === "list" ? "text" : (field.type || "text")} value={field.type === "list" ? (item[field.key] || []).join(", ") : (item[field.key] || "")}
                    maxLength={field.maxLength || 200} placeholder={field.placeholder || ""} onChange={(event) => setField(index, field, event.target.value)}
                    className="mt-1 w-full rounded-xl border border-slate-300 px-3 py-2 font-normal" />
                )}
              </label>
            ))}
            <button type="button" onClick={() => onChange(items.filter((_, itemIndex) => itemIndex !== index))}
              className="sm:col-span-2 justify-self-start text-sm font-bold text-red-700">Remove {title.toLowerCase().replace(/s$/, "")} {index + 1}</button>
          </fieldset>
        ))}
      </div>
    </section>
  );
}
