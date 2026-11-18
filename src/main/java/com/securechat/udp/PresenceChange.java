package com.securechat.udp;

/** One user transition that should be published to connected clients. */
public record PresenceChange(String username, PresenceStatus status) {
}
