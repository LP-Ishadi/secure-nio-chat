package com.securechat.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Objects;

/** Converts chat messages between Java objects and UTF-8 JSON bytes. */
public final class MessageCodec {
    private final ObjectMapper objectMapper;

    /** Creates a codec with the chat protocol's JSON rules. */
    public MessageCodec() {
        objectMapper = JsonMapper.builder()
                .defaultPropertyInclusion(JsonInclude.Value.construct(
                        JsonInclude.Include.NON_NULL,
                        JsonInclude.Include.ALWAYS
                ))
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
    }

    /**
     * Serializes one message as UTF-8 JSON bytes.
     *
     * @param message message to serialize
     * @return JSON encoded using UTF-8
     * @throws MessageCodecException if serialization fails
     */
    public byte[] encode(ChatMessage message) throws MessageCodecException {
        Objects.requireNonNull(message, "message must not be null");

        try {
            return objectMapper.writeValueAsBytes(message);
        } catch (IOException exception) {
            throw new MessageCodecException("Could not encode chat message", exception);
        }
    }

    /**
     * Deserializes one complete UTF-8 JSON document into a message.
     *
     * <p>TCP frame extraction is intentionally not performed here. The
     * {@link FrameDecoder} will provide exactly one JSON payload to this
     * method.</p>
     *
     * @param jsonBytes one complete JSON message encoded using UTF-8
     * @return decoded chat message
     * @throws MessageCodecException if the input is empty, malformed, or does
     *                               not match the message structure
     */
    public ChatMessage decode(byte[] jsonBytes) throws MessageCodecException {
        Objects.requireNonNull(jsonBytes, "jsonBytes must not be null");

        if (jsonBytes.length == 0) {
            throw new MessageCodecException("Cannot decode an empty message");
        }

        try {
            return objectMapper.readValue(jsonBytes, ChatMessage.class);
        } catch (IOException | RuntimeException exception) {
            throw new MessageCodecException("Could not decode chat message", exception);
        }
    }
}
