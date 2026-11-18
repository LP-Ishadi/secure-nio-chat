package com.securechat.udp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresenceTrackerTest {
    @Test
    void transitionsThroughAwayUnreachableOnlineAndOffline() {
        PresenceTracker tracker = new PresenceTracker(1_000, 2_000);
        assertEquals(
                PresenceStatus.ONLINE,
                tracker.register("session", "Alice", 10_000).status()
        );

        assertEquals(
                List.of(new PresenceChange("Alice", PresenceStatus.AWAY)),
                tracker.expire(11_000)
        );
        assertEquals(
                List.of(new PresenceChange("Alice", PresenceStatus.UNREACHABLE)),
                tracker.expire(12_000)
        );
        assertEquals(
                new PresenceChange("Alice", PresenceStatus.ONLINE),
                tracker.recordHeartbeat("session", "Alice", 1, 12_100)
                        .orElseThrow()
        );
        assertTrue(tracker.recordHeartbeat(
                "session",
                "Alice",
                1,
                12_200
        ).isEmpty());
        assertEquals(
                PresenceStatus.OFFLINE,
                tracker.markOffline("session").orElseThrow().status()
        );
        assertEquals(0, tracker.size());
    }
}
