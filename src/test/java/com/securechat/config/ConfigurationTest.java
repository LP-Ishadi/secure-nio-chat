package com.securechat.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigurationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsClientConfiguration() throws Exception {
        Path file = temporaryDirectory.resolve("client.properties");
        Files.writeString(file, """
                server.host=chat.example.test
                server.port=6000
                heartbeat.port=6001
                tls.truststore.path=config/certs/test-truststore.p12
                tls.truststore.password=client-secret
                """);

        ClientConfig config = ClientConfig.load(file);

        assertEquals("chat.example.test", config.serverHost());
        assertEquals(6000, config.serverPort());
        assertEquals(6001, config.heartbeatPort());
        assertEquals(
                Path.of("config/certs/test-truststore.p12"),
                config.trustStorePath()
        );
        assertEquals("client-secret", config.trustStorePassword());
    }

    @Test
    void loadsServerConfiguration() throws Exception {
        Path file = temporaryDirectory.resolve("server.properties");
        Files.writeString(file, """
                server.host=0.0.0.0
                server.port=7000
                heartbeat.port=7001
                tls.keystore.path=config/certs/test-keystore.p12
                tls.keystore.password=server-secret
                """);

        ServerConfig config = ServerConfig.load(file);

        assertEquals("0.0.0.0", config.bindHost());
        assertEquals(7000, config.serverPort());
        assertEquals(7001, config.heartbeatPort());
        assertEquals(
                Path.of("config/certs/test-keystore.p12"),
                config.keyStorePath()
        );
        assertEquals("server-secret", config.keyStorePassword());
    }

    @Test
    void rejectsMissingAndInvalidProperties() throws Exception {
        Path missingHost = temporaryDirectory.resolve("missing.properties");
        Files.writeString(missingHost, """
                server.port=5000
                heartbeat.port=5001
                tls.truststore.path=config/certs/client-truststore.p12
                tls.truststore.password=changeit
                """);
        Path invalidPort = temporaryDirectory.resolve("invalid.properties");
        Files.writeString(invalidPort, """
                server.host=localhost
                server.port=99999
                heartbeat.port=5001
                tls.truststore.path=config/certs/client-truststore.p12
                tls.truststore.password=changeit
                """);

        assertThrows(
                ConfigurationException.class,
                () -> ClientConfig.load(missingHost)
        );
        assertThrows(
                ConfigurationException.class,
                () -> ClientConfig.load(invalidPort)
        );
    }
}
