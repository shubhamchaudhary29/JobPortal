const AGGREGATORS = Object.freeze({
  jooble: { name: "Jooble", url: "https://in.jooble.org" },
  careerjet: { name: "Careerjet", url: "https://www.careerjet.co.in" },
});

/** Listings from job-search aggregators must credit the provider wherever they are shown. */
export default function ProviderAttribution({ source, linked = false, className = "" }) {
  const provider = AGGREGATORS[source?.toLowerCase?.()];
  if (!provider) return null;
  const label = <>Jobs by <span className="font-semibold">{provider.name}</span></>;
  return linked ? (
    <a href={provider.url} target="_blank" rel="noopener noreferrer" className={`text-xs text-slate-500 underline ${className}`} data-testid="provider-attribution">{label}</a>
  ) : (
    <span className={`text-xs text-slate-500 ${className}`} data-testid="provider-attribution">{label}</span>
  );
}
