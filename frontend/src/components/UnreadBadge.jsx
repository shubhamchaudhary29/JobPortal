import { useEffect, useState } from "react";
import { getUnreadCount } from "../services/chat-service";

/**
 * Polls the versioned conversation unread-count endpoint every 30 seconds.
 * Renders a small badge with the count if count > 0, nothing if 0.
 */
export default function UnreadBadge() {
  const [count, setCount] = useState(0);

  const fetchCount = async () => {
    try {
      const n = await getUnreadCount();
      setCount(typeof n === "number" ? n : 0);
    } catch {
      // Silently fail — badge is non-critical
    }
  };

  useEffect(() => {
    const initialFetch = setTimeout(fetchCount, 0);

    const interval = setInterval(fetchCount, 30_000); // Then every 30 s
    return () => {
      clearTimeout(initialFetch);
      clearInterval(interval);
    };
  }, []);

  if (count === 0) return null;

  return (
    <span className="ml-1.5 inline-flex items-center justify-center min-w-[18px] h-[18px] px-1 bg-red-500 text-white text-[10px] font-extrabold rounded-full leading-none">
      {count > 99 ? "99+" : count}
    </span>
  );
}
