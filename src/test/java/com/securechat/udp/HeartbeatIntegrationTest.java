package com.securechat.udp;

import com.securechat.security.HeartbeatCredentials;
import com.securechat.security.SessionTokenService;
import org.junit.jupiter.api.Test;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class HeartbeatIntegrationTest {
    @Test
    void signedUdpHeartbeatReachesServerAndMarksUserOnline() throws Exception {
        SessionTokenService tokens = new SessionTokenService();
        PresenceTracker tracker = new PresenceTracker(1_000, 2_000);
        BlockingQueue<PresenceChange> changes = new LinkedBlockingQueue<>();

        try (HeartbeatServer server = new HeartbeatServer(
                "127.0.0.1",
                0,
                tokens,
                tracker,
                changes::add
        )) {
            HeartbeatCredentials credentials = tokens.issue(
                    "Alice",
                    server.port(),
                    100
            );
            server.start();

            try (HeartbeatClient client = new HeartbeatClient(
                    "127.0.0.1",
                    credentials
            )) {
                client.start();
                PresenceChange change = changes.poll(3, TimeUnit.SECONDS);

                assertNotNull(change);
                assertEquals(
                        new PresenceChange("Alice", PresenceStatus.ONLINE),
                        change
                );
                assertEquals(
                        PresenceStatus.ONLINE,
                        tracker.status(credentials.sessionId()).orElseThrow()
                );
            }
        }
    }
}
