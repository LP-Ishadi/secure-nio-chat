package com.securechat.concurrent;

import com.securechat.nio.ClientConnection;
import org.junit.jupiter.api.Test;

import java.nio.channels.SocketChannel;

import static com.securechat.concurrent.ClientRegistry.RegistrationResult.ALREADY_REGISTERED;
import static com.securechat.concurrent.ClientRegistry.RegistrationResult.DUPLICATE_USERNAME;
import static com.securechat.concurrent.ClientRegistry.RegistrationResult.INVALID_USERNAME;
import static com.securechat.concurrent.ClientRegistry.RegistrationResult.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ClientRegistryTest {
    @Test
    void registersFindsAndRemovesClient() throws Exception {
        ClientRegistry registry = new ClientRegistry();
        try (SocketChannel channel = SocketChannel.open()) {
            ClientConnection connection = new ClientConnection(channel);

            assertEquals(SUCCESS, registry.register("Alice", connection));
            assertSame(connection, registry.find("alice"));
            assertSame(connection, registry.find("ALICE"));
            assertEquals(1, registry.size());
            assertEquals("Alice", registry.remove(connection));
            assertNull(registry.find("Alice"));
            assertEquals(0, registry.size());
        }
    }

    @Test
    void rejectsCaseInsensitiveDuplicateUsername() throws Exception {
        ClientRegistry registry = new ClientRegistry();
        try (SocketChannel firstChannel = SocketChannel.open();
             SocketChannel secondChannel = SocketChannel.open()) {
            ClientConnection first = new ClientConnection(firstChannel);
            ClientConnection second = new ClientConnection(secondChannel);

            assertEquals(SUCCESS, registry.register("Alice", first));
            assertEquals(DUPLICATE_USERNAME, registry.register("aLiCe", second));
            assertEquals(1, registry.size());
            assertNull(second.username());
        }
    }

    @Test
    void rejectsInvalidAndRepeatedRegistration() throws Exception {
        ClientRegistry registry = new ClientRegistry();
        try (SocketChannel channel = SocketChannel.open()) {
            ClientConnection connection = new ClientConnection(channel);

            assertEquals(INVALID_USERNAME, registry.register("a", connection));
            assertEquals(INVALID_USERNAME, registry.register("Alice Smith", connection));
            assertEquals(SUCCESS, registry.register("Alice_1", connection));
            assertEquals(ALREADY_REGISTERED, registry.register("Bob", connection));
        }
    }

    @Test
    void returnsAlphabeticalUsernameSnapshot() throws Exception {
        ClientRegistry registry = new ClientRegistry();
        try (SocketChannel firstChannel = SocketChannel.open();
             SocketChannel secondChannel = SocketChannel.open()) {
            registry.register("zoe", new ClientConnection(firstChannel));
            registry.register("Alice", new ClientConnection(secondChannel));

            assertEquals(
                    java.util.List.of("Alice", "zoe"),
                    registry.usernamesSnapshot()
            );
        }
    }
}
