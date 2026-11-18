package com.securechat.config;

/** Indicates that a required application property is missing or invalid. */
public final class ConfigurationException extends Exception {
    public ConfigurationException(String message) {
        super(message);
    }

    public ConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
