package com.securechat.udp;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** Tracks online, away, unreachable, and offline presence states. */
public final class PresenceTracker {
    public static final long DEFAULT_AWAY_TIMEOUT_MILLIS = 15_000;
    public static final long DEFAULT_UNREACHABLE_TIMEOUT_MILLIS = 30_000;

    private final long awayTimeoutMillis;
    private final long unreachableTimeoutMillis;
    private final ConcurrentHashMap<String, Entry> entries =
            new ConcurrentHashMap<>();

    public PresenceTracker() {
        this(DEFAULT_AWAY_TIMEOUT_MILLIS, DEFAULT_UNREACHABLE_TIMEOUT_MILLIS);
    }

    public PresenceTracker(long awayTimeoutMillis, long unreachableTimeoutMillis) {
        if (awayTimeoutMillis <= 0
                || unreachableTimeoutMillis <= awayTimeoutMillis) {
            throw new IllegalArgumentException(
                    "timeouts must satisfy 0 < away < unreachable"
            );
        }
        this.awayTimeoutMillis = awayTimeoutMillis;
        this.unreachableTimeoutMillis = unreachableTimeoutMillis;
    }

    public PresenceChange register(
            String sessionId,
            String username,
            long nowMillis
    ) {
        entries.put(sessionId, new Entry(
                username,
                nowMillis,
                -1,
                PresenceStatus.ONLINE
        ));
        return new PresenceChange(username, PresenceStatus.ONLINE);
    }

    /** Records only increasing sequence numbers to reject replayed datagrams. */
    public Optional<PresenceChange> recordHeartbeat(
            String sessionId,
            String username,
            long sequence,
            long nowMillis
    ) {
        AtomicReference<PresenceChange> change = new AtomicReference<>();
        entries.compute(sessionId, (key, current) -> {
            if (current != null && sequence <= current.lastSequence()) {
                return current;
            }

            if (current == null || current.status() != PresenceStatus.ONLINE) {
                change.set(new PresenceChange(username, PresenceStatus.ONLINE));
            }
            return new Entry(
                    username,
                    nowMillis,
                    sequence,
                    PresenceStatus.ONLINE
            );
        });
        return Optional.ofNullable(change.get());
    }

    /** Applies timeout transitions and returns only newly changed states. */
    public List<PresenceChange> expire(long nowMillis) {
        List<PresenceChange> changes = new ArrayList<>();
        entries.replaceAll((sessionId, current) -> {
            long elapsed = Math.max(0, nowMillis - current.lastHeartbeatMillis());
            PresenceStatus next = current.status();
            if (elapsed >= unreachableTimeoutMillis) {
                next = PresenceStatus.UNREACHABLE;
            } else if (elapsed >= awayTimeoutMillis) {
                next = PresenceStatus.AWAY;
            }

            if (next != current.status()) {
                changes.add(new PresenceChange(current.username(), next));
                return current.withStatus(next);
            }
            return current;
        });
        return List.copyOf(changes);
    }

    public Optional<PresenceChange> markOffline(String sessionId) {
        Entry removed = sessionId == null ? null : entries.remove(sessionId);
        return removed == null
                ? Optional.empty()
                : Optional.of(new PresenceChange(
                        removed.username(),
                        PresenceStatus.OFFLINE
                ));
    }

    public Optional<PresenceStatus> status(String sessionId) {
        Entry entry = entries.get(sessionId);
        return entry == null ? Optional.empty() : Optional.of(entry.status());
    }

    public int size() {
        return entries.size();
    }

    private record Entry(
            String username,
            long lastHeartbeatMillis,
            long lastSequence,
            PresenceStatus status
    ) {
        private Entry withStatus(PresenceStatus newStatus) {
            return new Entry(
                    username,
                    lastHeartbeatMillis,
                    lastSequence,
                    newStatus
            );
        }
    }
}
