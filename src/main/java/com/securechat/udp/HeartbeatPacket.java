package com.securechat.udp;

import java.util.Objects;

/** Signed application payload carried by one UDP datagram. */
public record HeartbeatPacket(
        String sessionId,
        long sequence,
        long timestamp,
        String signature
) {
    public HeartbeatPacket {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(signature, "signature must not be null");
        if (sessionId.isBlank() || signature.isBlank()) {
            throw new IllegalArgumentException("sessionId and signature must not be blank");
        }
        if (sequence < 0 || timestamp < 0) {
            throw new IllegalArgumentException("sequence and timestamp must not be negative");
        }
    }
}
