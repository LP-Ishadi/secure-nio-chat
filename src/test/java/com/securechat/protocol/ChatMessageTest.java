package com.securechat.protocol;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatMessageTest {
    @Test
    void storesAllProvidedMessageValues() {
        ChatMessage message = new ChatMessage(
                MessageType.PRIVATE_MESSAGE,
                "Alice",
                "Bob",
                "Hello Bob",
                1_700_000_000_000L,
                "request-123"
        );

        assertEquals(MessageType.PRIVATE_MESSAGE, message.type());
        assertEquals("Alice", message.sender());
        assertEquals("Bob", message.target());
        assertEquals("Hello Bob", message.payload());
        assertEquals(1_700_000_000_000L, message.timestamp());
        assertEquals("request-123", message.requestId());
    }

    @Test
    void createsTimestampAndUniqueRequestIdAutomatically() {
        long beforeCreation = System.currentTimeMillis();

        ChatMessage message = ChatMessage.create(
                MessageType.PUBLIC_MESSAGE,
                "Alice",
                null,
                "Hello everyone"
        );

        long afterCreation = System.currentTimeMillis();

        assertEquals(MessageType.PUBLIC_MESSAGE, message.type());
        assertEquals("Alice", message.sender());
        assertNull(message.target());
        assertEquals("Hello everyone", message.payload());
        assertTrue(message.timestamp() >= beforeCreation);
        assertTrue(message.timestamp() <= afterCreation);
        UUID.fromString(message.requestId());
    }

    @Test
    void permitsAbsentOptionalFields() {
        ChatMessage message = ChatMessage.create(
                MessageType.USER_LIST_REQUEST,
                "Alice",
                null,
                null
        );

        assertNull(message.target());
        assertNull(message.payload());
    }

    @Test
    void rejectsNullMessageType() {
        assertThrows(
                NullPointerException.class,
                () -> new ChatMessage(null, null, null, null, 0, "request-1")
        );
    }

    @Test
    void rejectsNegativeTimestamp() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ChatMessage(
                        MessageType.LOGIN,
                        "Alice",
                        null,
                        null,
                        -1,
                        "request-1"
                )
        );
    }

    @Test
    void rejectsBlankRequestId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ChatMessage(
                        MessageType.LOGIN,
                        "Alice",
                        null,
                        null,
                        0,
                        "  "
                )
        );
    }
}
