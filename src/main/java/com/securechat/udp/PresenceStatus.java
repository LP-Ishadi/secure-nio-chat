package com.securechat.udp;

/** Presence state derived from TCP lifecycle and UDP heartbeat timing. */
public enum PresenceStatus {
    ONLINE,
    AWAY,
    UNREACHABLE,
    OFFLINE
}
