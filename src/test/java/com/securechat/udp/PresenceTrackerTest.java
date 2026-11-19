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

    @Test
    void queriesStatusAndEffectiveStatusesByUsername() {
        PresenceTracker tracker = new PresenceTracker(1_000, 2_000);
        assertEquals(PresenceStatus.OFFLINE, tracker.getStatus("UnknownUser"));
        assertEquals(PresenceStatus.OFFLINE, tracker.getStatus(null));

        tracker.register("session-1", "Alice", 10_000);
        tracker.register("session-2", "Bob", 10_000);

        assertEquals(PresenceStatus.ONLINE, tracker.getEffectiveStatus("Alice", 10_500));
        assertEquals(PresenceStatus.AWAY, tracker.getEffectiveStatus("Alice", 11_500));
        assertEquals(PresenceStatus.UNREACHABLE, tracker.getEffectiveStatus("Alice", 12_500));
        assertEquals(PresenceStatus.ONLINE, tracker.getEffectiveStatus("alice", 10_500)); // case-insensitive

        List<PresenceChange> active = tracker.currentStatuses(10_500);
        assertEquals(2, active.size());
        assertTrue(active.stream().anyMatch(c -> c.username().equals("Alice") && c.status() == PresenceStatus.ONLINE));
        assertTrue(active.stream().anyMatch(c -> c.username().equals("Bob") && c.status() == PresenceStatus.ONLINE));

        tracker.markOffline("session-1");
        assertEquals(PresenceStatus.OFFLINE, tracker.getStatus("Alice"));
        assertEquals(PresenceStatus.ONLINE, tracker.getEffectiveStatus("Bob", 10_500));
    }
}
