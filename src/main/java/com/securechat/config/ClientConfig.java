package com.securechat.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Properties;

/** Validated settings used by a chat client. */
public record ClientConfig(
        String serverHost,
        int serverPort,
        int heartbeatPort,
        Path trustStorePath,
        String trustStorePassword
) {
    public static final Path DEFAULT_PATH = Path.of("config", "client.properties");

    public ClientConfig {
        requireHost(serverHost, "serverHost");
        requirePort(serverPort, "serverPort");
        requirePort(heartbeatPort, "heartbeatPort");
        requirePath(trustStorePath, "trustStorePath");
        requireText(trustStorePassword, "trustStorePassword");
    }

    public static ClientConfig load(Path path)
            throws IOException, ConfigurationException {
        Properties properties = PropertyFiles.load(path);
        try {
            return new ClientConfig(
                    PropertyFiles.require(properties, "server.host"),
                    PropertyFiles.requirePort(properties, "server.port"),
                    PropertyFiles.requirePort(properties, "heartbeat.port"),
                    Path.of(PropertyFiles.require(properties, "tls.truststore.path")),
                    PropertyFiles.require(properties, "tls.truststore.password")
            );
        } catch (IllegalArgumentException exception) {
            throw new ConfigurationException(
                    "Invalid client configuration: " + exception.getMessage(),
                    exception
            );
        }
    }

    public static ClientConfig defaults() {
        return new ClientConfig(
                "localhost",
                5000,
                5001,
                Path.of("config", "certs", "client-truststore.p12"),
                "changeit"
        );
    }

    public static ClientConfig loadDefaultOrFallback() {
        try {
            return load(DEFAULT_PATH);
        } catch (IOException | ConfigurationException exception) {
            return defaults();
        }
    }

    private static void requireHost(String host, String name) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
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
