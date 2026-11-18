package com.securechat.client;

import com.securechat.protocol.ChatMessage;

import java.io.IOException;

/** Indicates that the server rejected a client's LOGIN request. */
public final class LoginRejectedException extends IOException {
    private final ChatMessage response;

    public LoginRejectedException(ChatMessage response) {
        super(response.payload() == null ? "Login rejected" : response.payload());
        this.response = response;
    }

    public ChatMessage response() {
        return response;
    }
}
