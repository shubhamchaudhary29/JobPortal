import { useEffect, useState, useCallback } from "react";
import { useNavigate, Link } from "react-router-dom";
import Header from "../components/Header";
import Footer from "../components/Footer";
import Stepper from "../components/Stepper";
import { getMyProfile, updateMyProfile } from "../services/user-service";
import { getMyApplications } from "../services/application-service";

// ─── Status badge (compact, for the recent-applications table) ───────────────
const StatusBadge = ({ status }) => {
  const map = {
    APPLIED:      { cls: "bg-blue-50 text-blue-700 border-blue-100",     label: "Applied" },
    UNDER_REVIEW: { cls: "bg-amber-50 text-amber-700 border-amber-100",  label: "Under Review" },
    SHORTLISTED:  { cls: "bg-purple-50 text-purple-700 border-purple-100",label: "Shortlisted" },
    ACCEPTED:     { cls: "bg-green-50 text-green-700 border-green-100",   label: "Accepted" },
    REJECTED:     { cls: "bg-red-50 text-red-700 border-red-100",         label: "Rejected" },
  };
  const { cls, label } = map[status] || map.APPLIED;
  return (
    <span className={`inline-flex items-center text-xs font-bold border px-2.5 py-1 rounded-full uppercase tracking-wider ${cls}`}>
      {label}
    </span>
  );
};

// ─── Skeleton loader ──────────────────────────────────────────────────────────
const Skeleton = ({ className }) => (
  <div className={`animate-pulse bg-slate-200 rounded-xl ${className}`} />
);

// ─── Main Page ────────────────────────────────────────────────────────────────
export default function MyProfile() {
  const navigate = useNavigate();

  // Data states
  const [profile, setProfile]           = useState(null);
  const [applications, setApplications] = useState([]);
  const [loading, setLoading]           = useState(true);
  const [error, setError]               = useState(null);

  // Edit-name states
  const [editMode, setEditMode]         = useState(false);
  const [editName, setEditName]         = useState("");
  const [editError, setEditError]       = useState("");
  const [saving, setSaving]             = useState(false);

  // Toast state
  const [toast, setToast]               = useState(null); // { message, type }

  // ── Fetch both profile + applications in parallel ──────────────────────────
  const loadData = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [profileData, appsData] = await Promise.all([
        getMyProfile(),
        getMyApplications(),
      ]);
      setProfile(profileData);
      setApplications(appsData);
    } catch (err) {
      console.error("Failed to load profile", err);
      setError("Failed to load your profile. Please try again.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { loadData(); }, [loadData]);

  // ── Keyboard: Escape cancels edit mode ────────────────────────────────────
  useEffect(() => {
    const handleKey = (e) => {
      if (e.key === "Escape" && editMode) cancelEdit();
    };
    window.addEventListener("keydown", handleKey);
    return () => window.removeEventListener("keydown", handleKey);
  }, [editMode]);

  // ── Toast helper ──────────────────────────────────────────────────────────
  const showToast = (message, type = "success") => {
    setToast({ message, type });
    setTimeout(() => setToast(null), 3000);
  };

  // ── Edit handlers ─────────────────────────────────────────────────────────
  const startEdit = () => {
    setEditName(profile.fullName);
    setEditError("");
    setEditMode(true);
  };

  const cancelEdit = () => {
    setEditMode(false);
    setEditError("");
  };

  const saveEdit = async () => {
    const trimmed = editName.trim();
    if (!trimmed || trimmed.length < 2) {
      setEditError("Name must be at least 2 characters.");
      return;
    }
    if (trimmed.length > 100) {
      setEditError("Name must be at most 100 characters.");
      return;
    }
    setSaving(true);
    setEditError("");
    try {
      const updated = await updateMyProfile({ fullName: trimmed });
      setProfile(updated);
      setEditMode(false);
      showToast("Name updated successfully! ✓");
    } catch (err) {
      const msg = err?.response?.data?.message || "Failed to update name. Please try again.";
      setEditError(msg);
    } finally {
      setSaving(false);
    }
  };

  // ── Date formatter ────────────────────────────────────────────────────────
  const formatDate = (dateStr) => {
    if (!dateStr) return "";
    return new Date(dateStr).toLocaleDateString("en-US", {
      year: "numeric", month: "short", day: "numeric",
    });
  };

  // ── Stats config ──────────────────────────────────────────────────────────
  const stats = profile ? [
    {
      label: "Total Applications",
      value: profile.totalApplications,
      icon: "💼",
      accent: "border-indigo-400",
      bg: "bg-indigo-50",
      text: "text-indigo-700",
    },
    {
      label: "Accepted",
      value: profile.acceptedApplications,
      icon: "✅",
      accent: "border-green-400",
      bg: "bg-green-50",
      text: "text-green-700",
    },
    {
      label: "Rejected",
      value: profile.rejectedApplications,
      icon: "❌",
      accent: "border-red-400",
      bg: "bg-red-50",
      text: "text-red-700",
    },
    {
      label: "In Progress",
      value: profile.pendingApplications,
      icon: "⏳",
      accent: "border-amber-400",
      bg: "bg-amber-50",
      text: "text-amber-700",
    },
  ] : [];

  // ── Render ────────────────────────────────────────────────────────────────
  return (
    <div className="min-h-screen bg-slate-50 flex flex-col">
      <Header />

      {/* Toast notification */}
      {toast && (
        <div
          className={`fixed top-6 right-6 z-50 px-5 py-3.5 rounded-2xl shadow-xl text-sm font-bold transition-all ${
            toast.type === "success"
              ? "bg-green-500 text-white"
              : "bg-red-500 text-white"
          }`}
        >
          {toast.message}
        </div>
      )}

      <main className="flex-1 max-w-5xl w-full mx-auto px-4 py-10">

        {/* ── Error State ── */}
        {error && !loading && (
          <div className="bg-white border border-red-100 rounded-3xl p-10 text-center shadow-sm">
            <div className="text-4xl mb-3">⚠️</div>
            <h2 className="text-xl font-bold text-slate-800 mb-2">Something went wrong</h2>
            <p className="text-slate-500 mb-6">{error}</p>
            <button
              onClick={loadData}
              className="bg-indigo-600 text-white px-6 py-3 rounded-xl font-bold hover:bg-indigo-700 transition-all shadow-md"
            >
              Retry
            </button>
          </div>
        )}

        {/* ── Loading Skeletons ── */}
        {loading && (
          <div className="space-y-6">
            {/* Profile card skeleton */}
            <div className="bg-white rounded-3xl border border-slate-200 shadow-sm p-8 flex items-center gap-6">
              <Skeleton className="w-24 h-24 rounded-full flex-shrink-0" />
              <div className="flex-1 space-y-3">
                <Skeleton className="h-7 w-48" />
                <Skeleton className="h-4 w-64" />
                <Skeleton className="h-5 w-24 rounded-full" />
              </div>
            </div>
            {/* Stats skeleton */}
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
              {[1, 2, 3, 4].map((n) => <Skeleton key={n} className="h-28" />)}
            </div>
            {/* Applications skeleton */}
            <Skeleton className="h-64" />
          </div>
        )}

        {/* ── Main Content ── */}
        {!loading && !error && profile && (
          <div className="space-y-6">

            {/* ── Section 1: Profile Header Card ── */}
            <div className="bg-white rounded-3xl border border-slate-200 shadow-sm overflow-hidden">
              {/* Decorative gradient banner */}
              <div className="h-28 bg-gradient-to-r from-indigo-600 via-purple-600 to-pink-500" />

              <div className="px-8 pb-8">
                {/* Avatar — overlaps the banner */}
                <div className="-mt-12 mb-4">
                  <div className="w-24 h-24 rounded-full bg-gradient-to-tr from-indigo-500 to-purple-500 flex items-center justify-center shadow-xl border-4 border-white">
                    <span className="text-4xl font-extrabold text-white">
                      {profile.fullName.charAt(0).toUpperCase()}
                    </span>
                  </div>
                </div>

                <div className="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-4">
                  <div>
                    {/* Editable full name */}
                    {editMode ? (
                      <div>
                        <div className="flex items-center gap-3 flex-wrap">
                          <input
                            autoFocus
                            value={editName}
                            onChange={(e) => { setEditName(e.target.value); setEditError(""); }}
                            className="text-2xl font-extrabold text-slate-900 border-b-2 border-indigo-500 bg-transparent outline-none w-72 max-w-full py-1"
                            placeholder="Your full name"
                            maxLength={100}
                          />
                          <button
                            onClick={saveEdit}
                            disabled={saving}
                            className="bg-indigo-600 text-white text-sm font-bold px-4 py-1.5 rounded-lg hover:bg-indigo-700 transition disabled:opacity-60"
                          >
                            {saving ? "Saving…" : "Save"}
                          </button>
                          <button
                            onClick={cancelEdit}
                            className="text-sm font-bold text-slate-500 hover:text-slate-700 transition"
                          >
                            Cancel
                          </button>
                        </div>
                        {editError && (
                          <p className="mt-1.5 text-sm text-red-600 font-medium">{editError}</p>
                        )}
                      </div>
                    ) : (
                      <div className="flex items-center gap-2 group">
                        <h1 className="text-2xl font-extrabold text-slate-900">
                          {profile.fullName}
                        </h1>
                        <button
                          onClick={startEdit}
                          title="Edit name"
                          className="opacity-0 group-hover:opacity-100 transition-opacity text-slate-400 hover:text-indigo-600 text-base"
                        >
                          ✏️
                        </button>
                      </div>
                    )}

                    <p className="text-slate-500 font-medium mt-1">{profile.email}</p>

                    <span className="mt-3 inline-flex items-center gap-1.5 bg-indigo-50 text-indigo-700 border border-indigo-100 text-xs font-bold px-3 py-1 rounded-full uppercase tracking-wider">
                      👤 Candidate
                    </span>
                  </div>

                  <button
                    onClick={() => navigate("/jobs")}
                    className="self-start sm:self-center bg-indigo-600 text-white px-5 py-2.5 rounded-xl text-sm font-bold hover:bg-indigo-700 shadow-md transition-all"
                  >
                    Browse Jobs →
                  </button>
                </div>
              </div>
            </div>

            {/* ── Section 2: Stats Cards ── */}
            <div>
              <h2 className="text-lg font-extrabold text-slate-800 mb-4">Application Overview</h2>
              <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
                {stats.map((stat) => (
                  <div
                    key={stat.label}
                    className={`bg-white rounded-2xl border border-slate-200 shadow-sm p-5 border-l-4 ${stat.accent} hover:shadow-md transition-shadow`}
                  >
                    <div className="text-2xl mb-1">{stat.icon}</div>
                    <div className={`text-4xl font-extrabold ${stat.text}`}>{stat.value}</div>
                    <div className="text-xs font-semibold text-slate-500 mt-1 leading-tight">{stat.label}</div>
                  </div>
                ))}
              </div>
            </div>

            {/* ── Section 3: Recent Applications ── */}
            <div>
              <div className="flex items-center justify-between mb-4">
                <h2 className="text-lg font-extrabold text-slate-800">Recent Applications</h2>
                {applications.length > 0 && (
                  <Link
                    to="/my-applications"
                    className="text-sm font-bold text-indigo-600 hover:text-indigo-700 hover:underline"
                  >
                    View All →
                  </Link>
                )}
              </div>

              {applications.length === 0 ? (
                <div className="bg-white rounded-3xl border border-dashed border-slate-300 p-12 text-center">
                  <div className="text-5xl mb-4">💼</div>
                  <h3 className="text-lg font-bold text-slate-700 mb-2">No applications yet</h3>
                  <p className="text-slate-500 text-sm mb-6">
                    You haven't applied to any jobs. Start exploring opportunities!
                  </p>
                  <button
                    onClick={() => navigate("/jobs")}
                    className="bg-indigo-600 text-white px-6 py-2.5 rounded-xl font-bold hover:bg-indigo-700 transition shadow-md text-sm"
                  >
                    Browse Jobs
                  </button>
                </div>
              ) : (
                <div className="bg-white rounded-3xl border border-slate-200 shadow-sm overflow-hidden">
                  {/* Show the 5 most recent */}
                  {[...applications]
                    .sort((a, b) => new Date(b.appliedAt) - new Date(a.appliedAt))
                    .slice(0, 5)
                    .map((app, idx, arr) => (
                      <div
                        key={app.applicationId}
                        className={`p-5 sm:p-6 ${idx < arr.length - 1 ? "border-b border-slate-100" : ""} hover:bg-slate-50 transition-colors`}
                      >
                        {/* Row: job info + badge + date */}
                        <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
                          <div className="flex-1 min-w-0">
                            <button
                              onClick={() => navigate(`/jobs/${app.jobId}`)}
                              className="font-bold text-slate-900 hover:text-indigo-600 transition-colors text-left truncate block"
                            >
                              {app.jobTitle}
                            </button>
                            <p className="text-sm text-slate-500 font-medium mt-0.5">
                              {app.jobCompany}
                              {app.jobLocation && <span> · 📍 {app.jobLocation}</span>}
                            </p>
                          </div>
                          <div className="flex items-center gap-3 flex-shrink-0">
                            <StatusBadge status={app.status} />
                            <span className="text-xs text-slate-400 font-semibold whitespace-nowrap">
                              {formatDate(app.appliedAt)}
                            </span>
                          </div>
                        </div>

                        {/* Mini stepper for this application */}
                        <div className="mt-3">
                          <Stepper status={app.status} />
                        </div>
                      </div>
                    ))}

                  {/* Footer: View All button */}
                  {applications.length > 5 && (
                    <div className="px-6 py-4 bg-slate-50 border-t border-slate-100 text-center">
                      <Link
                        to="/my-applications"
                        className="text-sm font-bold text-indigo-600 hover:text-indigo-700 hover:underline"
                      >
                        View all {applications.length} applications →
                      </Link>
                    </div>
                  )}
                </div>
              )}
            </div>

          </div>
        )}
      </main>

      <Footer />
    </div>
  );
}
