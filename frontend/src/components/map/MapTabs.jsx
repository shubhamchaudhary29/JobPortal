import { useRef } from "react";
import { formatCount } from "../../map/format";
import { MAP_TABS } from "../../map/panelData";

/** WAI-ARIA tabs: arrow keys, Home and End move between tabs; only the active tab is in the tab order. */
export default function MapTabs({ active, counts, onChange, panelId }) {
  const buttons = useRef([]);

  const onKeyDown = (event) => {
    const index = MAP_TABS.findIndex((tab) => tab.id === active);
    const last = MAP_TABS.length - 1;
    const next = { ArrowRight: index === last ? 0 : index + 1, ArrowLeft: index === 0 ? last : index - 1, Home: 0, End: last }[event.key];
    if (next === undefined) return;
    event.preventDefault();
    onChange(MAP_TABS[next].id);
    buttons.current[next]?.focus();
  };

  return (
    <div role="tablist" aria-label="Where the jobs are" onKeyDown={onKeyDown}
      className="flex gap-1 rounded-2xl bg-slate-100 p-1">
      {MAP_TABS.map((tab, index) => {
        const selected = tab.id === active;
        return (
          <button key={tab.id} ref={(node) => { buttons.current[index] = node; }} type="button" role="tab"
            id={`map-tab-${tab.id}`} aria-selected={selected} aria-controls={panelId} tabIndex={selected ? 0 : -1}
            onClick={() => onChange(tab.id)}
            className={`flex-1 rounded-xl px-2 py-2 text-xs font-semibold transition-colors sm:text-sm ${selected
              ? "bg-white text-indigo-600 shadow-sm" : "text-slate-600 hover:text-indigo-600"}`}>
            {tab.label}
            {counts[tab.id] !== undefined && <>{" "}<span className="font-medium text-slate-400">{formatCount(counts[tab.id])}</span></>}
          </button>
        );
      })}
    </div>
  );
}
