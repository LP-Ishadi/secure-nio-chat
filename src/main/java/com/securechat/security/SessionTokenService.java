package com.securechat.security;

import com.securechat.udp.HeartbeatPacket;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Creates and validates unpredictable client heartbeat sessions. */
public final class SessionTokenService {
    private static final int SECRET_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentHashMap<String, SessionRecord> sessions =
            new ConcurrentHashMap<>();

    public HeartbeatCredentials issue(
            String username,
            int heartbeatPort,
            long intervalMillis
    ) {
        byte[] secretBytes = new byte[SECRET_BYTES];
        secureRandom.nextBytes(secretBytes);
        String sessionId = UUID.randomUUID().toString();
        String secret = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(secretBytes);
        sessions.put(sessionId, new SessionRecord(username, secret));
        return new HeartbeatCredentials(
                sessionId,
                secret,
                heartbeatPort,
                intervalMillis
        );
    }

    /** Returns the authenticated username when the packet signature is valid. */
    public Optional<String> validate(HeartbeatPacket packet) {
        SessionRecord record = sessions.get(packet.sessionId());
        if (record == null) {
            return Optional.empty();
        }

        boolean valid = HeartbeatAuthenticator.verify(
                record.secret(),
                packet.sessionId(),
                packet.sequence(),
                packet.timestamp(),
                packet.signature()
        );
        return valid ? Optional.of(record.username()) : Optional.empty();
    }

    public void revoke(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    public int size() {
        return sessions.size();
    }

    private record SessionRecord(String username, String secret) {
    }
}
