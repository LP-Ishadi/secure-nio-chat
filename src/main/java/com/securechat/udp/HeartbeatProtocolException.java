package com.securechat.udp;

/** Indicates malformed heartbeat credentials or datagram JSON. */
public final class HeartbeatProtocolException extends Exception {
    public HeartbeatProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
