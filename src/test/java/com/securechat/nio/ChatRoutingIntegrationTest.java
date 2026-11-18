package com.securechat.nio;

import com.securechat.demo.blocking.BlockingMessageConnection;
import com.securechat.protocol.ChatMessage;
import com.securechat.protocol.MessageType;
import com.securechat.testutil.TlsTestSockets;
import org.junit.jupiter.api.Test;

import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatRoutingIntegrationTest {
    @Test
    void routesPublicAndPrivateMessagesUsingAuthenticatedIdentity()
            throws Exception {
        ExecutorService serverThread = Executors.newSingleThreadExecutor();

        try (NioChatServer server = new NioChatServer(0, 4)) {
            Future<?> serverTask = startServer(serverThread, server);

            try (ClientSession unregistered = connect(server.port())) {
                unregistered.send(message(
                        MessageType.PUBLIC_MESSAGE,
                        "Mallory",
                        null,
                        "Not logged in",
                        "before-login"
                ));
                ChatMessage error = unregistered.receiveUntil(MessageType.ERROR);
                assertTrue(error.payload().contains("Login is required"));
            }

            try (ClientSession alice = connect(server.port());
                 ClientSession bob = connect(server.port())) {
                assertEquals(
                        MessageType.LOGIN_SUCCESS,
                        alice.login("Alice", "login-alice").type()
                );
                assertEquals(
                        MessageType.LOGIN_SUCCESS,
                        bob.login("Bob", "login-bob").type()
                );
                alice.receiveUntil(MessageType.USER_JOINED);

                alice.send(message(
                        MessageType.PUBLIC_MESSAGE,
                        "Mallory",
                        "Bob",
                        "Hello everyone 👋",
                        "public-1"
                ));

                ChatMessage publicForAlice = alice.receiveUntil(
                        MessageType.PUBLIC_MESSAGE
                );
                ChatMessage publicForBob = bob.receiveUntil(
                        MessageType.PUBLIC_MESSAGE
                );
                assertEquals(publicForAlice, publicForBob);
                assertEquals("Alice", publicForAlice.sender());
                assertNull(publicForAlice.target());
                assertEquals("Hello everyone 👋", publicForAlice.payload());

                bob.send(message(
                        MessageType.PRIVATE_MESSAGE,
                        "Mallory",
                        "aLiCe",
                        "Private hello",
                        "private-1"
                ));

                ChatMessage privateForAlice = alice.receiveUntil(
                        MessageType.PRIVATE_MESSAGE
                );
                ChatMessage privateForBob = bob.receiveUntil(
                        MessageType.PRIVATE_MESSAGE
                );
                assertEquals(privateForAlice, privateForBob);
                assertEquals("Bob", privateForAlice.sender());
                assertEquals("Alice", privateForAlice.target());
                assertEquals("Private hello", privateForAlice.payload());

                alice.send(message(
                        MessageType.PRIVATE_MESSAGE,
                        "Alice",
                        "Carol",
                        "Are you there?",
                        "unknown-user"
                ));
                ChatMessage unknownUser = alice.receiveUntil(MessageType.ERROR);
                assertEquals("unknown-user", unknownUser.requestId());
                assertTrue(unknownUser.payload().contains("Carol"));

                alice.send(message(
                        MessageType.PUBLIC_MESSAGE,
                        "Alice",
                        null,
                        "   ",
                        "blank-message"
                ));
                ChatMessage blankMessage = alice.receiveUntil(MessageType.ERROR);
                assertEquals("blank-message", blankMessage.requestId());
                assertTrue(blankMessage.payload().contains("must not be blank"));

                alice.send(message(
                        MessageType.PUBLIC_MESSAGE,
                        "Alice",
                        null,
                        "x".repeat(2_001),
                        "oversized-message"
                ));
                ChatMessage oversized = alice.receiveUntil(MessageType.ERROR);
                assertEquals("oversized-message", oversized.requestId());
                assertTrue(oversized.payload().contains("exceeds 2000"));
            }

            server.close();
            serverTask.get(5, TimeUnit.SECONDS);
        } finally {
            serverThread.shutdownNow();
            serverThread.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static Future<?> startServer(
            ExecutorService executor,
            NioChatServer server
    ) {
        return executor.submit(() -> {
            try {
                server.serve();
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
    }

    private static ClientSession connect(int port) throws Exception {
        return new ClientSession(TlsTestSockets.connect("127.0.0.1", port));
    }

    private static ChatMessage message(
            MessageType type,
            String sender,
            String target,
            String payload,
            String requestId
    ) {
        return new ChatMessage(
                type,
                sender,
                target,
                payload,
                1_700_000_000_000L,
                requestId
        );
    }

    private static final class ClientSession implements AutoCloseable {
        private final BlockingMessageConnection connection;

        private ClientSession(Socket socket) throws Exception {
            connection = new BlockingMessageConnection(socket);
        }

        private ChatMessage login(String username, String requestId)
                throws Exception {
            send(message(
                    MessageType.LOGIN,
                    username,
                    null,
                    null,
                    requestId
            ));
            return connection.receive();
        }

        private void send(ChatMessage outgoing) throws Exception {
            connection.send(outgoing);
        }

        private ChatMessage receiveUntil(MessageType expected) throws Exception {
            ChatMessage received;
            do {
                received = connection.receive();
            } while (received.type() != expected);
            return received;
        }

        @Override
        public void close() throws Exception {
            connection.close();
        }
    }
}
