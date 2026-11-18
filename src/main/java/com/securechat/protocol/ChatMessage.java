package com.securechat.protocol;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable representation of one application-level chat message.
 *
 * @param type operation that the receiver should process
 * @param sender claimed sender name; may be {@code null} before login or for
 *               some server-generated messages
 * @param target intended recipient; normally used only for private messages
 * @param payload message content or operation-specific data
 * @param timestamp creation time as milliseconds since the Unix epoch
 * @param requestId identifier used to trace a request and its response
 */
public record ChatMessage(
        MessageType type,
        String sender,
        String target,
        String payload,
        long timestamp,
        String requestId
) {
    /**
     * Validates the structural requirements shared by every message type.
     * Message-specific rules are applied later by the message dispatcher.
     */
    public ChatMessage {
        Objects.requireNonNull(type, "type must not be null");

        if (timestamp < 0) {
            throw new IllegalArgumentException("timestamp must not be negative");
        }

        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
    }

    /**
     * Creates a message with the current time and a new unique request ID.
     */
    public static ChatMessage create(
            MessageType type,
            String sender,
            String target,
            String payload
    ) {
        return new ChatMessage(
                type,
                sender,
                target,
                payload,
                Instant.now().toEpochMilli(),
                UUID.randomUUID().toString()
        );
    }
}
