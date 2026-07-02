import { useEffect, useRef, useCallback } from "react";
import { Client } from "@stomp/stompjs";
import SockJS from "sockjs-client";

/**
 * Custom hook that manages a STOMP/SockJS WebSocket connection.
 *
 * @param {object} options
 * @param {Function} options.onMessage        - Called with a parsed ChatMessage when a room message arrives
 * @param {Function} options.onNotification   - Called with a parsed ChatMessage when a personal notification arrives
 * @param {string|null} options.chatRoomId    - The room ID to subscribe to (null = no room subscription)
 *
 * @returns {{ sendMessage: Function }}
 */
export function useWebSocket({ onMessage, onNotification, chatRoomId }) {
  const clientRef = useRef(null);
  const roomSubscriptionRef = useRef(null);
  const token = localStorage.getItem("token");

  // ── Send a message to the server ──────────────────────────────────────────
  const sendMessage = useCallback((roomId, content) => {
    if (clientRef.current && clientRef.current.connected) {
      clientRef.current.publish({
        destination: "/app/chat.send",
        body: JSON.stringify({ chatRoomId: roomId, content }),
      });
    }
  }, []);

  // ── Manage the per-room subscription when chatRoomId changes ─────────────
  useEffect(() => {
    if (!clientRef.current || !clientRef.current.connected || !chatRoomId) return;

    // Unsubscribe from previous room
    if (roomSubscriptionRef.current) {
      roomSubscriptionRef.current.unsubscribe();
      roomSubscriptionRef.current = null;
    }

    // Subscribe to new room
    roomSubscriptionRef.current = clientRef.current.subscribe(
      `/topic/chat/${chatRoomId}`,
      (frame) => {
        try {
          const message = JSON.parse(frame.body);
          if (onMessage) onMessage(message);
        } catch (e) {
          console.error("[WS] Failed to parse room message:", e);
        }
      }
    );

    return () => {
      if (roomSubscriptionRef.current) {
        roomSubscriptionRef.current.unsubscribe();
        roomSubscriptionRef.current = null;
      }
    };
  }, [chatRoomId, onMessage]);

  // ── Create and connect the STOMP client (runs once on mount) ─────────────
  useEffect(() => {
    if (!token) return;

    const client = new Client({
      // SockJS factory instead of a plain WebSocket URL
      webSocketFactory: () => new SockJS("/ws"),

      // Pass JWT in STOMP CONNECT headers (picked up by WebSocketAuthInterceptor)
      connectHeaders: {
        Authorization: `Bearer ${token}`,
      },

      // Reconnect automatically after 5 s if the connection drops
      reconnectDelay: 5000,

      onConnect: () => {
        // Subscribe to personal notification queue
        client.subscribe("/user/queue/notifications", (frame) => {
          try {
            const notification = JSON.parse(frame.body);
            if (onNotification) onNotification(notification);
          } catch (e) {
            console.error("[WS] Failed to parse notification:", e);
          }
        });

        // Subscribe to the current chat room (if any) after connection is ready
        if (chatRoomId) {
          roomSubscriptionRef.current = client.subscribe(
            `/topic/chat/${chatRoomId}`,
            (frame) => {
              try {
                const message = JSON.parse(frame.body);
                if (onMessage) onMessage(message);
              } catch (e) {
                console.error("[WS] Failed to parse room message:", e);
              }
            }
          );
        }
      },

      onStompError: (frame) => {
        console.error("[WS] STOMP error:", frame.headers["message"], frame.body);
      },

      onDisconnect: () => {
        console.log("[WS] Disconnected from WebSocket");
      },
    });

    clientRef.current = client;
    client.activate();

    // Cleanup on unmount — deactivate the STOMP client
    return () => {
      if (roomSubscriptionRef.current) {
        roomSubscriptionRef.current.unsubscribe();
        roomSubscriptionRef.current = null;
      }
      client.deactivate();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token]); // Re-create client only if token changes (effectively once)

  return { sendMessage };
}
