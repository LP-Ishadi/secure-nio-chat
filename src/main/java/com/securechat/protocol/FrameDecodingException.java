package com.securechat.protocol;

/** Indicates that a TCP frame header contains an invalid payload size. */
public final class FrameDecodingException extends Exception {
    public FrameDecodingException(String message) {
        super(message);
    }
}
