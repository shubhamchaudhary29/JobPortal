const LOCALE = "en-IN";
const LAKH = 100000;
const integer = new Intl.NumberFormat(LOCALE, { maximumFractionDigits: 0 });
const oneDecimal = new Intl.NumberFormat(LOCALE, { maximumFractionDigits: 1 });
const rupees = new Intl.NumberFormat(LOCALE, { style: "currency", currency: "INR", maximumFractionDigits: 0 });

/** Compact job count: 999, 1.2k, 12k. */
export function formatCount(value) {
  const count = Number(value) || 0;
  if (count < 1000) return integer.format(count);
  if (count < 10000) return `${oneDecimal.format(count / 1000)}k`;
  return `${integer.format(Math.round(count / 1000))}k`;
}

const positive = (value) => (Number.isFinite(value) && value > 0 ? value : null);

/** Annual INR salary as lakhs per annum (₹8–12 LPA); amounts under a lakh are shown in rupees. */
export function formatSalary(min, max) {
  const low = positive(min);
  const high = positive(max);
  if (low === null && high === null) return "Not disclosed";
  const largest = Math.max(low ?? 0, high ?? 0);
  const amount = largest < LAKH ? (value) => rupees.format(value) : (value) => `₹${oneDecimal.format(value / LAKH)}`;
  const unit = largest < LAKH ? "" : " LPA";
  if (low !== null && high !== null) {
    const [from, to] = low <= high ? [low, high] : [high, low];
    if (amount(from) === amount(to)) return `${amount(from)}${unit}`;
    return `${amount(from)}–${amount(to).replace("₹", "")}${unit}`;
  }
  return low !== null ? `${amount(low)}+${unit}` : `Up to ${amount(high)}${unit}`;
}

/** Distance label: "< 1 km", "3.2 km", "42 km"; empty when unknown. */
export function formatDistance(km) {
  if (!Number.isFinite(km) || km < 0) return "";
  if (km < 1) return "< 1 km";
  if (km < 10) return `${oneDecimal.format(km)} km`;
  return `${integer.format(Math.round(km))} km`;
}
