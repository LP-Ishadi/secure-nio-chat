package com.securechat.model;

import java.util.Objects;

/**
 * Summary of a conversation including latest message snippet, timestamp,
 * and number of unread messages for a specific user.
 *
 * @param conversationId contact username or unique groupId
 * @param isGroup whether this conversation is a group chat
 * @param lastMessage preview of the latest message
 * @param lastSender sender of the latest message
 * @param lastTimestamp timestamp of the latest message in epoch milliseconds
 * @param unreadCount number of unread messages in this conversation
 */
public record ConversationSummary(
        String conversationId,
        boolean isGroup,
        String lastMessage,
        String lastSender,
        long lastTimestamp,
        int unreadCount
) {
    public ConversationSummary {
        Objects.requireNonNull(conversationId, "conversationId must not be null");
        if (lastMessage == null) {
            lastMessage = "";
        }
        if (lastSender == null) {
            lastSender = "";
        }
        if (unreadCount < 0) {
            unreadCount = 0;
        }
    }
}
