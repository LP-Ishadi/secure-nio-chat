package com.securechat.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Shared parser for the external client and server property files. */
final class PropertyFiles {
    private PropertyFiles() {
    }

    static Properties load(Path path) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        }
        return properties;
    }

    static String require(Properties properties, String key)
            throws ConfigurationException {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new ConfigurationException("Missing required property: " + key);
        }
        return value.trim();
    }

    static int requirePort(Properties properties, String key)
            throws ConfigurationException {
        String value = require(properties, key);
        try {
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65_535) {
                throw new ConfigurationException(
                        key + " must be between 1 and 65535"
                );
            }
            return port;
        } catch (NumberFormatException exception) {
            throw new ConfigurationException(key + " must be a number", exception);
        }
    }
}
