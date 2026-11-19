
package com.securechat.protocol;

/**
 * Identifies the application-level operation carried by a TCP chat message.
 *
 * <p>UDP heartbeat packets use a separate protocol and transport.</p>
 */
public enum MessageType {

    /** Client requests authentication using an existing account. */
    LOGIN,

    /** Server confirms successful authentication. */
    LOGIN_SUCCESS,

    /** Server rejects an authentication attempt. */
    LOGIN_FAILURE,

    /** Client requests creation of a new account. */
    REGISTER,

    /** Server confirms successful account creation. */
    REGISTER_SUCCESS,

    /** Server rejects an account registration attempt. */
    REGISTER_FAILURE,

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

    /** Client requests stored conversation history. */
    HISTORY_REQUEST,

    /** Server returns stored conversation history. */
    HISTORY_RESPONSE,

    /** Client requests the creation of a new chat group. */
    GROUP_CREATE,

    /** Server confirms successful creation of a chat group. */
    GROUP_CREATE_SUCCESS,

    /** Server rejects group creation attempt. */
    GROUP_CREATE_FAILURE,

    /** Client requests the list of groups the user belongs to. */
    GROUP_LIST_REQUEST,

    /** Server returns the list of groups the user belongs to. */
    GROUP_LIST_RESPONSE,

    /** Client requests the list of members in a group. */
    GROUP_MEMBERS_REQUEST,

    /** Server returns the list of members in a group. */
    GROUP_MEMBERS_RESPONSE,

    /** Group administrator adds a user to the group. */
    GROUP_ADD_MEMBER,

    /** Group administrator removes a member from the group. */
    GROUP_REMOVE_MEMBER,

    /** Group member leaves the group. */
    GROUP_LEAVE,

    /** Message sent within a group. */
    GROUP_MESSAGE,

    /** Client requests paginated history for a group. */
    GROUP_HISTORY_REQUEST,

    /** Server returns paginated history for a group. */
    GROUP_HISTORY_RESPONSE,

    /** Server confirms a successful group administrative or membership operation. */
    GROUP_OPERATION_SUCCESS,

    /** Client informs server that a conversation has been read up to a message ID. */
    MARK_READ,

    /** Server acknowledges or synchronizes read status. */
    READ_STATUS_UPDATE,

    /** Client requests unread counts and latest message summaries for all conversations. */
    CONVERSATION_SUMMARIES_REQUEST,

    /** Server responds with unread counts and latest message previews for all conversations. */
    CONVERSATION_SUMMARIES_RESPONSE,

    /** Client requests a graceful end to its chat session. */
    DISCONNECT
}
