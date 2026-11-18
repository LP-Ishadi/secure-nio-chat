package com.securechat.protocol;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageCodecTest {
    private MessageCodec codec;

    @BeforeEach
    void createCodec() {
        codec = new MessageCodec();
    }

    @Test
    void roundTripsCompleteMessage() throws MessageCodecException {
        ChatMessage original = new ChatMessage(
                MessageType.PRIVATE_MESSAGE,
                "Alice",
                "Bob",
                "Hello | Bob\nUnicode: ආයුබෝවන් 👋 and a quote: \"",
                1_700_000_000_000L,
                "request-123"
        );

        byte[] encoded = codec.encode(original);
        ChatMessage decoded = codec.decode(encoded);

        assertEquals(original, decoded);
    }

    @Test
    void writesUtf8JsonAndOmitsNullOptionalFields() throws MessageCodecException {
        ChatMessage message = new ChatMessage(
                MessageType.USER_LIST_REQUEST,
                "ඇලිස්",
                null,
                null,
                1_700_000_000_000L,
                "request-456"
        );

        String json = new String(codec.encode(message), StandardCharsets.UTF_8);

        assertTrue(json.startsWith("{"));
        assertTrue(json.contains("\"type\":\"USER_LIST_REQUEST\""));
        assertTrue(json.contains("\"sender\":\"ඇලිස්\""));
        assertFalse(json.contains("\"target\""));
        assertFalse(json.contains("\"payload\""));
    }

    @Test
    void decodesValidJson() throws MessageCodecException {
        String json = """
                {
                  "type": "LOGIN",
                  "sender": "Alice",
                  "timestamp": 1700000000000,
                  "requestId": "login-1"
                }
                """;

        ChatMessage message = codec.decode(json.getBytes(StandardCharsets.UTF_8));

        assertEquals(MessageType.LOGIN, message.type());
        assertEquals("Alice", message.sender());
        assertEquals("login-1", message.requestId());
    }

    @Test
    void rejectsEmptyInput() {
        assertThrows(MessageCodecException.class, () -> codec.decode(new byte[0]));
    }

    @Test
    void rejectsMalformedJson() {
        byte[] malformed = "{not-json}".getBytes(StandardCharsets.UTF_8);

        assertThrows(MessageCodecException.class, () -> codec.decode(malformed));
    }

    @Test
    void rejectsUnknownMessageType() {
        String json = """
                {
                  "type": "NOT_A_REAL_TYPE",
                  "timestamp": 1700000000000,
                  "requestId": "request-1"
                }
                """;

        assertThrows(
                MessageCodecException.class,
                () -> codec.decode(json.getBytes(StandardCharsets.UTF_8))
        );
    }

    @Test
    void rejectsUnknownJsonField() {
        String json = """
                {
                  "type": "LOGIN",
                  "sender": "Alice",
                  "timestamp": 1700000000000,
                  "requestId": "request-1",
                  "unexpected": true
                }
                """;

        assertThrows(
                MessageCodecException.class,
                () -> codec.decode(json.getBytes(StandardCharsets.UTF_8))
        );
    }

    @Test
    void rejectsTrailingJsonDocument() {
        String json = """
                {"type":"LOGIN","timestamp":1,"requestId":"one"}
                {"type":"LOGIN","timestamp":2,"requestId":"two"}
                """;

        assertThrows(
                MessageCodecException.class,
                () -> codec.decode(json.getBytes(StandardCharsets.UTF_8))
        );
    }

    @Test
    void rejectsNullArguments() {
        assertThrows(NullPointerException.class, () -> codec.encode(null));
        assertThrows(NullPointerException.class, () -> codec.decode(null));
    }
}
