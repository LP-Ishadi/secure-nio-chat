package com.securechat.udp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.securechat.security.HeartbeatCredentials;

import java.nio.charset.StandardCharsets;

/** UTF-8 JSON codec for login credentials and UDP heartbeat packets. */
public final class HeartbeatCodec {
    private final JsonMapper mapper = JsonMapper.builder()
            .defaultPropertyInclusion(JsonInclude.Value.construct(
                    JsonInclude.Include.NON_NULL,
                    JsonInclude.Include.ALWAYS
            ))
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public String encodeCredentials(HeartbeatCredentials credentials) {
        try {
            return mapper.writeValueAsString(credentials);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not encode heartbeat credentials", exception);
        }
    }

    public HeartbeatCredentials decodeCredentials(String json)
            throws HeartbeatProtocolException {
        try {
            return mapper.readValue(json, HeartbeatCredentials.class);
        } catch (Exception exception) {
            throw new HeartbeatProtocolException(
                    "Could not decode heartbeat credentials",
                    exception
            );
        }
    }

    public byte[] encodePacket(HeartbeatPacket packet) {
        try {
            return mapper.writeValueAsBytes(packet);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not encode heartbeat packet", exception);
        }
    }

    public HeartbeatPacket decodePacket(byte[] bytes, int length)
            throws HeartbeatProtocolException {
        try {
            String json = new String(bytes, 0, length, StandardCharsets.UTF_8);
            return mapper.readValue(json, HeartbeatPacket.class);
        } catch (Exception exception) {
            throw new HeartbeatProtocolException(
                    "Could not decode heartbeat packet",
                    exception
            );
        }
    }
}
