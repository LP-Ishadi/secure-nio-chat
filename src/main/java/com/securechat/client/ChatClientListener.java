package com.securechat.client;

import com.securechat.protocol.ChatMessage;

/** Receives asynchronous events from a persistent chat connection. */
public interface ChatClientListener {
    default void onMessage(ChatMessage message) {
    }

    default void onConnectionError(String message, Throwable cause) {
    }

    default void onDisconnected() {
    }
}
