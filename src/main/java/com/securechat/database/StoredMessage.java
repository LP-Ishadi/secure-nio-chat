
package com.securechat.database;

public record StoredMessage(
        long id,
        String messageType,
        String sender,
        String recipient,
        String content,
        long sentAt,
        String requestId
) {
}
