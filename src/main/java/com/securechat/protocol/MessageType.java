package com.securechat.protocol;

/**
 * Identifies the application-level operation carried by a TCP chat message.
 *
 * <p>UDP heartbeat packets are deliberately not represented here. They use a
 * separate protocol and transport in the {@code udp} package.</p>
 */
public enum MessageType {
    /** Client asks to register a username after connecting. */
    LOGIN,

    /** Server confirms that username registration succeeded. */
    LOGIN_SUCCESS,

    /** Server rejects a username registration attempt. */
    LOGIN_FAILURE,

    /** Chat message intended for every connected user. */
    PUBLIC_MESSAGE,

    /** Chat message intended for one named user. */
    PRIVATE_MESSAGE,

    /** Client asks for a snapshot of currently connected users. */
    USER_LIST_REQUEST,

    /** Server returns a snapshot of currently connected users. */
    USER_LIST_RESPONSE,

    /** Server announces that a user has joined. */
    USER_JOINED,

    /** Server announces that a user has left. */
    USER_LEFT,

    /** Server announces a user's presence-state change. */
    PRESENCE_UPDATE,

    /** Server reports an invalid request or processing problem. */
    ERROR,

    /** Client requests a graceful end to its chat session. */
    DISCONNECT
}
