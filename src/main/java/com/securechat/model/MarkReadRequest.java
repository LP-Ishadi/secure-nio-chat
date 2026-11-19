package com.securechat.model;

import java.util.Objects;

/**
 * Payload carried by MARK_READ requests to update a user's read watermark
 * for a personal or group conversation.
 *
 * @param conversationId contact username or unique groupId
 * @param isGroup whether this is a group chat
 * @param lastReadMessageId the maximum message ID read by the user
 */
public record MarkReadRequest(
        String conversationId,
        boolean isGroup,
        long lastReadMessageId
) {
    public MarkReadRequest {
        Objects.requireNonNull(conversationId, "conversationId must not be null");
        if (lastReadMessageId < 0) {
            lastReadMessageId = 0;
        }
    }
}
