package com.securechat.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Properties;

/** Validated bind and service-port settings used by the server. */
public record ServerConfig(
        String bindHost,
        int serverPort,
        int heartbeatPort,
        Path keyStorePath,
        String keyStorePassword
) {
    public static final Path DEFAULT_PATH = Path.of("config", "server.properties");

    public ServerConfig {
        if (bindHost == null || bindHost.isBlank()) {
            throw new IllegalArgumentException("bindHost must not be blank");
        }
        requirePort(serverPort, "serverPort");
        requirePort(heartbeatPort, "heartbeatPort");
        requirePath(keyStorePath, "keyStorePath");
        requireText(keyStorePassword, "keyStorePassword");
    }

    public static ServerConfig load(Path path)
            throws IOException, ConfigurationException {
        Properties properties = PropertyFiles.load(path);
        try {
            return new ServerConfig(
                    PropertyFiles.require(properties, "server.host"),
                    PropertyFiles.requirePort(properties, "server.port"),
                    PropertyFiles.requirePort(properties, "heartbeat.port"),
                    Path.of(PropertyFiles.require(properties, "tls.keystore.path")),
                    PropertyFiles.require(properties, "tls.keystore.password")
            );
        } catch (IllegalArgumentException exception) {
            throw new ConfigurationException(
                    "Invalid server configuration: " + exception.getMessage(),
                    exception
            );
        }
    }

    public static ServerConfig defaults() {
        return new ServerConfig(
                "0.0.0.0",
                5000,
                5001,
                Path.of("config", "certs", "server-keystore.p12"),
                "changeit"
        );
    }

    public static ServerConfig loadDefaultOrFallback() {
        try {
            return load(DEFAULT_PATH);
        } catch (IOException | ConfigurationException exception) {
            return defaults();
        }
    }

    private static void requirePort(int port, String name) {
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException(name + " must be between 1 and 65535");
        }
    }

    private static void requirePath(Path path, String name) {
        if (path == null || path.toString().isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
