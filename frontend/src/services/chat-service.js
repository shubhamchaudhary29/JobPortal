import apiClient from "./helper";

/**
 * GET /chat/rooms
 * Returns all chat rooms for the currently logged-in user, newest activity first.
 */
export const getMyChatRooms = async () => {
  const response = await apiClient.get("/chat/rooms");
  return response.data;
};

/**
 * GET /chat/rooms/:roomId
 * Returns a single chat room's metadata.
 */
export const getChatRoom = async (roomId) => {
  const response = await apiClient.get(`/chat/rooms/${roomId}`);
  return response.data;
};

/**
 * GET /chat/rooms/:roomId/messages
 * Returns all messages ordered by sentAt ASC.
 * Also marks the other party's unread messages as read on the server.
 */
export const getChatMessages = async (roomId) => {
  const response = await apiClient.get(`/chat/rooms/${roomId}/messages`);
  return response.data;
};

/**
 * GET /chat/unread
 * Returns the total unread message count across all rooms.
 */
export const getUnreadCount = async () => {
  const response = await apiClient.get("/chat/unread");
  return response.data; // number
};
