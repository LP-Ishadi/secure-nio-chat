package com.securechat.protocol;

/** Indicates that an application payload cannot be placed into a TCP frame. */
public final class FrameEncodingException extends Exception {
    public FrameEncodingException(String message) {
        super(message);
    }
}
