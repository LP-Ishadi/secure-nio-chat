package com.securechat.security;

import java.util.Objects;

/** Secret material issued through TCP and used to authenticate UDP heartbeats. */
public record HeartbeatCredentials(
        String sessionId,
        String secret,
        int heartbeatPort,
        long intervalMillis
) {
    public HeartbeatCredentials {
        requireText(sessionId, "sessionId");
        requireText(secret, "secret");
        if (heartbeatPort < 1 || heartbeatPort > 65_535) {
            throw new IllegalArgumentException("heartbeatPort must be between 1 and 65535");
        }
        if (intervalMillis < 100) {
            throw new IllegalArgumentException("intervalMillis must be at least 100");
        }
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
