package com.securechat.protocol;

/** Indicates that a chat message could not be encoded or decoded. */
public final class MessageCodecException extends Exception {
    public MessageCodecException(String message) {
        super(message);
    }

    public MessageCodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
