const AGGREGATORS = Object.freeze({
  adzuna: { name: "Adzuna", url: "https://www.adzuna.in" },
  jooble: { name: "Jooble", url: "https://in.jooble.org" },
});

/**
 * Listings from job-search aggregators credit the provider with a link wherever they are shown. Adzuna's API
 * terms require the word "Adzuna", at least 116 x 23 px, hyperlinked to its local domain on every displayed advert.
 */
export default function ProviderAttribution({ source, className = "" }) {
  const provider = AGGREGATORS[source?.toLowerCase?.()];
  if (!provider) return null;
  return (
    <a href={provider.url} target="_blank" rel="noopener noreferrer"
      className={`inline-flex min-h-[23px] min-w-[116px] items-center gap-1 text-xs text-slate-500 underline ${className}`}
      data-testid="provider-attribution">
      Jobs by{" "}<span className="font-semibold">{provider.name}</span>
    </a>
  );
}
