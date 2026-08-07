import { useEffect, useState, useRef, useCallback } from "react";
import { useParams, useNavigate } from "react-router-dom";
import Header from "../components/Header";
import { getChatRoom, getChatMessages } from "../services/chat-service";
import { useWebSocket } from "../hooks/useWebSocket";
import { useAuth } from "../auth/auth-context";

// ── Date separator label (Today / Yesterday / date string) ───────────────────
function dateSeparatorLabel(dateStr) {
  const d = new Date(dateStr);
  const today = new Date();
  const yesterday = new Date();
  yesterday.setDate(today.getDate() - 1);

  if (d.toDateString() === today.toDateString()) return "Today";
  if (d.toDateString() === yesterday.toDateString()) return "Yesterday";
  return d.toLocaleDateString("en-US", { month: "long", day: "numeric", year: "numeric" });
}

// ── Time formatter (HH:MM) ────────────────────────────────────────────────────
function formatTime(dateStr) {
  return new Date(dateStr).toLocaleTimeString("en-US", {
    hour: "2-digit",
    minute: "2-digit",
    hour12: true,
  });
}

// ── Message bubble ────────────────────────────────────────────────────────────
function MessageBubble({ message, isOwn }) {
  return (
    <div className={`flex flex-col ${isOwn ? "items-end" : "items-start"} mb-1`}>
      {!isOwn && (
        <span className="text-xs text-slate-400 font-semibold mb-1 ml-1">
          {message.senderName}
        </span>
      )}
      <div
        className={`max-w-[75%] px-4 py-2.5 rounded-2xl text-sm leading-relaxed break-words ${
          isOwn
            ? "bg-indigo-600 text-white rounded-br-none"
            : "bg-white text-slate-800 border border-slate-200 rounded-bl-none shadow-sm"
        }`}
      >
        {message.content}
      </div>
      <span className={`text-[10px] text-slate-400 mt-1 ${isOwn ? "mr-1" : "ml-1"}`}>
        {message.sentAt ? formatTime(message.sentAt) : ""}
      </span>
    </div>
  );
}

// ── Skeleton for loading state ────────────────────────────────────────────────
function MessagesSkeleton() {
  return (
    <div className="flex-1 p-6 space-y-4 overflow-y-auto">
      {[1, 2, 3, 4, 5].map((n) => (
        <div key={n} className={`flex ${n % 2 === 0 ? "justify-end" : "justify-start"}`}>
          <div
            className={`h-10 rounded-2xl animate-pulse bg-slate-200 ${
              n % 2 === 0 ? "w-48" : "w-64"
            }`}
          />
        </div>
      ))}
    </div>
  );
}

// ── Main Page ─────────────────────────────────────────────────────────────────
export default function ChatRoom() {
  const { roomId } = useParams();
  const navigate = useNavigate();

  const { email: myEmail, role } = useAuth();

  const [room, setRoom] = useState(null);
  const [messages, setMessages] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const [inputValue, setInputValue] = useState("");
  const [charCount, setCharCount] = useState(0);

  const messagesEndRef = useRef(null);
  const inputRef = useRef(null);

  // ── Auto-scroll to bottom ─────────────────────────────────────────────────
  const scrollToBottom = useCallback(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, []);

  useEffect(() => {
    scrollToBottom();
  }, [messages, scrollToBottom]);

  // ── Load room + message history on mount ──────────────────────────────────
  useEffect(() => {
    const load = async () => {
      setLoading(true);
      setError(null);
      try {
        const [roomData, msgs] = await Promise.all([
          getChatRoom(roomId),
          getChatMessages(roomId),
        ]);
        setRoom(roomData);
        setMessages(msgs);
      } catch (err) {
        console.error("Failed to load chat room", err);
        setError("Could not load this conversation. You may not have access.");
      } finally {
        setLoading(false);
      }
    };
    load();
  }, [roomId]);

  // ── WebSocket: handle incoming messages ───────────────────────────────────
  const handleIncomingMessage = useCallback((msg) => {
    setMessages((prev) => {
      // Deduplicate: if the same message ID is already in the list, skip it
      if (prev.some((m) => m.id === msg.id)) return prev;
      return [...prev, msg];
    });
  }, []);

  const { sendMessage } = useWebSocket({
    chatRoomId: roomId,
    onMessage: handleIncomingMessage,
    onNotification: null, // Notifications handled by UnreadBadge
  });

  // ── Send message handler ──────────────────────────────────────────────────
  const handleSend = useCallback(() => {
    const text = inputValue.trim();
    if (!text || text.length === 0) return;
    if (text.length > 2000) return;

    sendMessage(roomId, text);
    setInputValue("");
    setCharCount(0);
  }, [inputValue, roomId, sendMessage]);

  const handleKeyDown = (e) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  const handleInputChange = (e) => {
    setInputValue(e.target.value);
    setCharCount(e.target.value.length);
  };

  // ── Determine other party info ────────────────────────────────────────────
  const otherName = room
    ? role === "RECRUITER" ? room.candidateName : room.recruiterName
    : "";
  const otherLabel = role === "RECRUITER" ? "Candidate" : "Recruiter";

  // ── Group messages by date for separators ─────────────────────────────────
  const messageGroups = [];
  let lastDate = null;
  messages.forEach((msg) => {
    const dateStr = msg.sentAt ? new Date(msg.sentAt).toDateString() : null;
    if (dateStr && dateStr !== lastDate) {
      messageGroups.push({ type: "separator", label: dateSeparatorLabel(msg.sentAt), key: dateStr });
      lastDate = dateStr;
    }
    messageGroups.push({ type: "message", msg });
  });

  return (
    <div className="h-screen flex flex-col bg-slate-50 overflow-hidden">
      <Header />

      {error ? (
        <div className="flex-1 flex flex-col items-center justify-center p-10 text-center">
          <div className="text-5xl mb-4">🔒</div>
          <h2 className="text-xl font-bold text-slate-800 mb-2">Access Denied</h2>
          <p className="text-slate-500 mb-6">{error}</p>
          <button
            onClick={() => navigate("/chat")}
            className="bg-indigo-600 text-white px-6 py-2.5 rounded-xl font-bold hover:bg-indigo-700 transition shadow-md text-sm"
          >
            ← Back to Messages
          </button>
        </div>
      ) : (
        <div className="flex-1 flex flex-col min-h-0">
          {/* ── Top bar ── */}
          <div className="bg-white border-b border-slate-200 px-4 py-3 flex items-center gap-3 shadow-sm z-10">
            <button
              onClick={() => navigate("/chat")}
              className="text-slate-400 hover:text-indigo-600 transition-colors font-bold text-xl px-1"
              title="Back to chats"
            >
              ←
            </button>

            {/* Avatar */}
            <div className="w-10 h-10 rounded-full bg-gradient-to-tr from-indigo-500 to-purple-500 flex items-center justify-center flex-shrink-0 shadow-sm">
              <span className="text-white font-extrabold">
                {otherName?.charAt(0)?.toUpperCase() || "?"}
              </span>
            </div>

            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-2">
                <span className="font-extrabold text-slate-900 truncate">{otherName}</span>
                <span className="text-[10px] font-bold bg-slate-100 text-slate-500 px-2 py-0.5 rounded-full uppercase tracking-wider flex-shrink-0">
                  {otherLabel}
                </span>
                {/* Static online dot */}
                <span className="w-2 h-2 rounded-full bg-green-400 flex-shrink-0" title="Online" />
              </div>
              <p className="text-xs text-indigo-600 font-semibold truncate">
                💼 {room?.jobTitle || "Loading…"}
              </p>
            </div>
          </div>

          {/* ── Messages area ── */}
          {loading ? (
            <MessagesSkeleton />
          ) : (
            <div className="flex-1 overflow-y-auto px-4 py-6 space-y-0.5">
              {messages.length === 0 && (
                <div className="flex flex-col items-center justify-center h-full text-center py-16">
                  <div className="text-5xl mb-4">👋</div>
                  <p className="text-slate-500 font-medium">
                    No messages yet. Say hello!
                  </p>
                </div>
              )}

              {messageGroups.map((item, idx) => {
                if (item.type === "separator") {
                  return (
                    <div key={item.key + idx} className="flex items-center gap-3 my-4">
                      <div className="flex-1 h-px bg-slate-200" />
                      <span className="text-xs text-slate-400 font-semibold whitespace-nowrap">
                        {item.label}
                      </span>
                      <div className="flex-1 h-px bg-slate-200" />
                    </div>
                  );
                }
                const { msg } = item;
                const isOwn = msg.senderEmail === myEmail;
                return (
                  <MessageBubble
                    key={msg.id || idx}
                    message={msg}
                    isOwn={isOwn}
                  />
                );
              })}

              {/* Sentinel div for auto-scroll */}
              <div ref={messagesEndRef} />
            </div>
          )}

          {/* ── Input area ── */}
          <div className="bg-white border-t border-slate-200 px-4 py-3 shadow-sm">
            {charCount > 1800 && (
              <div className="text-xs text-amber-600 font-semibold mb-1 text-right">
                {charCount}/2000 characters
              </div>
            )}
            <div className="flex items-end gap-3">
              <textarea
                ref={inputRef}
                value={inputValue}
                onChange={handleInputChange}
                onKeyDown={handleKeyDown}
                placeholder="Type a message… (Enter to send, Shift+Enter for new line)"
                maxLength={2000}
                rows={1}
                className="flex-1 resize-none bg-slate-50 border border-slate-200 rounded-2xl px-4 py-3 text-sm text-slate-800 placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:bg-white transition-all leading-relaxed max-h-40 overflow-y-auto"
                style={{ minHeight: "44px" }}
                onInput={(e) => {
                  // Auto-grow
                  e.target.style.height = "auto";
                  e.target.style.height = Math.min(e.target.scrollHeight, 160) + "px";
                }}
              />
              <button
                onClick={handleSend}
                disabled={!inputValue.trim()}
                className="flex-shrink-0 w-11 h-11 rounded-full bg-indigo-600 text-white flex items-center justify-center shadow-md hover:bg-indigo-700 transition-all disabled:opacity-40 disabled:cursor-not-allowed"
                title="Send message"
              >
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" className="w-5 h-5">
                  <path d="M3.478 2.405a.75.75 0 00-.926.94l2.432 7.905H13.5a.75.75 0 010 1.5H4.984l-2.432 7.905a.75.75 0 00.926.94 60.519 60.519 0 0018.445-8.986.75.75 0 000-1.218A60.517 60.517 0 003.478 2.405z" />
                </svg>
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
