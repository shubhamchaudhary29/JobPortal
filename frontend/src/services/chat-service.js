import apiClient from "./helper";
import { apiRoutes } from "./api-routes";

/**
 * GET /api/v1/conversations
 * Returns all chat rooms for the currently logged-in user, newest activity first.
 */
export const getMyChatRooms = async () => {
  const response = await apiClient.get(apiRoutes.conversations.collection);
  return response.data.content;
};

/**
 * GET /api/v1/conversations/:id
 * Returns a single chat room's metadata.
 */
export const getChatRoom = async (roomId) => {
  const response = await apiClient.get(apiRoutes.conversations.byId(roomId));
  return response.data;
};

/**
 * GET /api/v1/conversations/:id/messages
 * Returns all messages ordered by sentAt ASC.
 * Also marks the other party's unread messages as read on the server.
 */
export const getChatMessages = async (roomId) => {
  const response = await apiClient.get(apiRoutes.conversations.messages(roomId));
  return response.data.content;
};

/**
 * GET /api/v1/conversations/unread-count
 * Returns the total unread message count across all rooms.
 */
export const getUnreadCount = async () => {
  const response = await apiClient.get(apiRoutes.conversations.unreadCount);
  return response.data.count;
};
