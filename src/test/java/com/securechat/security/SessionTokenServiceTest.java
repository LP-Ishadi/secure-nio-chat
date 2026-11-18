package com.securechat.security;

import com.securechat.udp.HeartbeatPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionTokenServiceTest {
    @Test
    void validatesSignedPacketAndRejectsTamperingAndRevocation() {
        SessionTokenService service = new SessionTokenService();
        HeartbeatCredentials credentials = service.issue("Alice", 5001, 5_000);
        long timestamp = 123_456L;
        String signature = HeartbeatAuthenticator.sign(
                credentials.secret(),
                credentials.sessionId(),
                7,
                timestamp
        );
        HeartbeatPacket valid = new HeartbeatPacket(
                credentials.sessionId(),
                7,
                timestamp,
                signature
        );

        assertEquals("Alice", service.validate(valid).orElseThrow());
        assertTrue(service.validate(new HeartbeatPacket(
                credentials.sessionId(),
                8,
                timestamp,
                signature
        )).isEmpty());

        service.revoke(credentials.sessionId());
        assertTrue(service.validate(valid).isEmpty());
    }
}
