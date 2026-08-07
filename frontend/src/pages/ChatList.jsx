import { useEffect, useState, useCallback } from "react";
import { useNavigate } from "react-router-dom";
import Header from "../components/Header";
import Footer from "../components/Footer";
import { getMyChatRooms, getUnreadCount } from "../services/chat-service";

// ── Relative time formatter ───────────────────────────────────────────────────
function relativeTime(dateStr) {
  if (!dateStr) return "";
  const date = new Date(dateStr);
  const now = new Date();
  const diffMs = now - date;
  const diffMin = Math.floor(diffMs / 60000);
  if (diffMin < 1) return "Just now";
  if (diffMin < 60) return `${diffMin}m ago`;
  const diffHrs = Math.floor(diffMin / 60);
  if (diffHrs < 24) return `${diffHrs}h ago`;
  const diffDays = Math.floor(diffHrs / 24);
  if (diffDays === 1) return "Yesterday";
  return date.toLocaleDateString("en-US", { month: "short", day: "numeric" });
}

// ── Skeleton for a single room card ──────────────────────────────────────────
function RoomSkeleton() {
  return (
    <div className="bg-white rounded-2xl border border-slate-200 p-5 animate-pulse flex items-center gap-4">
      <div className="w-12 h-12 rounded-full bg-slate-200 flex-shrink-0" />
      <div className="flex-1 space-y-2">
        <div className="h-4 bg-slate-200 rounded w-32" />
        <div className="h-3 bg-slate-200 rounded w-48" />
        <div className="h-3 bg-slate-200 rounded w-24" />
      </div>
    </div>
  );
}

// ── Main page ─────────────────────────────────────────────────────────────────
export default function ChatList() {
  const navigate = useNavigate();
  const role = localStorage.getItem("role");

  const [rooms, setRooms] = useState([]);
  const [unreadTotal, setUnreadTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const loadData = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [roomsData, unread] = await Promise.all([
        getMyChatRooms(),
        getUnreadCount(),
      ]);
      setRooms(roomsData);
      setUnreadTotal(typeof unread === "number" ? unread : 0);
    } catch (err) {
      console.error("Failed to load chat rooms", err);
      setError("Failed to load conversations. Please try again.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { loadData(); }, [loadData]);

  // Determine the "other party" name for each room based on current user role
  const getOtherParty = (room) => {
    if (role === "RECRUITER") {
      return { name: room.candidateName, email: room.candidateEmail, label: "Candidate" };
    }
    return { name: room.recruiterName, email: room.recruiterEmail, label: "Recruiter" };
  };

  return (
    <div className="min-h-screen bg-slate-50 flex flex-col">
      <Header />

      <main className="flex-1 max-w-3xl w-full mx-auto px-4 py-10">
        {/* Page header */}
        <div className="mb-8">
          <div className="flex items-center gap-3">
            <h1 className="text-3xl font-extrabold text-slate-900">Messages</h1>
            {unreadTotal > 0 && (
              <span className="inline-flex items-center justify-center min-w-[28px] h-7 px-2 bg-indigo-600 text-white text-sm font-extrabold rounded-full">
                {unreadTotal > 99 ? "99+" : unreadTotal}
              </span>
            )}
          </div>
          <p className="text-slate-500 mt-1 font-medium">
            Your post-acceptance conversations
          </p>
        </div>

        {/* Error state */}
        {error && !loading && (
          <div className="bg-white rounded-3xl border border-red-100 p-10 text-center shadow-sm">
            <div className="text-4xl mb-3">⚠️</div>
            <p className="text-slate-600 mb-4">{error}</p>
            <button
              onClick={loadData}
              className="bg-indigo-600 text-white px-6 py-2.5 rounded-xl font-bold hover:bg-indigo-700 transition shadow-md text-sm"
            >
              Retry
            </button>
          </div>
        )}

        {/* Loading skeletons */}
        {loading && (
          <div className="space-y-3">
            {[1, 2, 3].map((n) => <RoomSkeleton key={n} />)}
          </div>
        )}

        {/* Empty state */}
        {!loading && !error && rooms.length === 0 && (
          <div className="bg-white rounded-3xl border border-dashed border-slate-300 p-16 text-center">
            <div className="text-5xl mb-4">💬</div>
            <h3 className="text-lg font-bold text-slate-700 mb-2">
              No conversations yet
            </h3>
            <p className="text-slate-500 text-sm mb-6 max-w-sm mx-auto">
              {role === "RECRUITER"
                ? "Accept candidates to start a conversation with them."
                : "Apply to jobs and get accepted to start chatting with recruiters."}
            </p>
            <button
              onClick={() => navigate(role === "RECRUITER" ? "/profile" : "/jobs")}
              className="bg-indigo-600 text-white px-6 py-2.5 rounded-xl font-bold hover:bg-indigo-700 transition shadow-md text-sm"
            >
              {role === "RECRUITER" ? "Go to Dashboard" : "Browse Jobs"}
            </button>
          </div>
        )}

        {/* Room list */}
        {!loading && !error && rooms.length > 0 && (
          <div className="space-y-3">
            {rooms.map((room) => {
              const other = getOtherParty(room);
              const hasPreview = Boolean(room.lastMessagePreview);

              return (
                <button
                  key={room.id}
                  onClick={() => navigate(`/chat/${room.id}`)}
                  className="w-full bg-white rounded-2xl border border-slate-200 shadow-sm p-5 flex items-center gap-4 hover:shadow-md hover:border-indigo-200 transition-all text-left group"
                >
                  {/* Avatar */}
                  <div className="w-12 h-12 rounded-full bg-gradient-to-tr from-indigo-500 to-purple-500 flex-shrink-0 flex items-center justify-center shadow-sm group-hover:scale-105 transition-transform">
                    <span className="text-white font-extrabold text-lg">
                      {other.name?.charAt(0)?.toUpperCase() || "?"}
                    </span>
                  </div>

                  {/* Content */}
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center justify-between gap-2">
                      <div className="flex items-center gap-2 min-w-0">
                        <span className="font-bold text-slate-900 truncate">{other.name}</span>
                        <span className="flex-shrink-0 text-[10px] font-bold bg-slate-100 text-slate-500 px-2 py-0.5 rounded-full uppercase tracking-wider">
                          {other.label}
                        </span>
                      </div>
                      {room.lastMessageAt && (
                        <span className="flex-shrink-0 text-xs text-slate-400 font-medium">
                          {relativeTime(room.lastMessageAt)}
                        </span>
                      )}
                    </div>

                    <p className="text-sm font-semibold text-indigo-600 mt-0.5 truncate">
                      💼 {room.jobTitle}
                    </p>

                    <p className="text-sm text-slate-500 font-medium mt-0.5 truncate">
                      {hasPreview ? room.lastMessagePreview : "No messages yet — say hello!"}
                    </p>
                  </div>

                  {/* Chevron */}
                  <div className="flex-shrink-0 text-slate-300 group-hover:text-indigo-400 transition-colors text-lg">
                    ›
                  </div>
                </button>
              );
            })}
          </div>
        )}
      </main>

      <Footer />
    </div>
  );
}
