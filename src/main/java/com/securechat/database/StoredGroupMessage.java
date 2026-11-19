package com.securechat.database;

/**
 * Persisted record of a message sent within a chat group.
 *
 * @param id primary key
 * @param groupId group to which the message was sent
 * @param sender username of the sender
 * @param content message body (text or caption)
 * @param messageType message format type ("TEXT" or "VOICE")
 * @param mediaUrl optional URL or reference path for media/voice content
 * @param sentAt epoch milliseconds when the message was sent
 * @param requestId request tracking identifier
 */
public record StoredGroupMessage(
        long id,
        String groupId,
        String sender,
        String content,
        String messageType,
        String mediaUrl,
        long sentAt,
        String requestId
) {
    public StoredGroupMessage {
        if (messageType == null || messageType.isBlank()) {
            messageType = "TEXT";
        }
    }
}
