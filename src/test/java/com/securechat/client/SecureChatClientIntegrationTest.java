package com.securechat.client;

import com.securechat.nio.NioChatServer;
import com.securechat.protocol.ChatMessage;
import com.securechat.protocol.MessageType;
import org.junit.jupiter.api.Test;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecureChatClientIntegrationTest {
    @Test
    void twoPersistentGuiClientsExchangePublicAndPrivateMessages()
            throws Exception {
        ExecutorService serverThread = Executors.newSingleThreadExecutor();

        try (NioChatServer server = new NioChatServer(0, 4)) {
            Future<?> serverTask = serverThread.submit(() -> {
                try {
                    server.serve();
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });

            RecordingListener aliceEvents = new RecordingListener();
            RecordingListener bobEvents = new RecordingListener();
            try (SecureChatClient alice = new SecureChatClient(aliceEvents);
                 SecureChatClient bob = new SecureChatClient(bobEvents)) {
                assertEquals(
                        MessageType.LOGIN_SUCCESS,
                        alice.connect("localhost", server.port(), "Alice").type()
                );
                assertEquals(
                        MessageType.LOGIN_SUCCESS,
                        bob.connect("localhost", server.port(), "Bob").type()
                );
                assertTrue(alice.tlsProtocol().startsWith("TLS"));
                assertTrue(bob.tlsProtocol().startsWith("TLS"));
                aliceEvents.await(MessageType.USER_JOINED);

                bob.requestUserList();
                ChatMessage userList = bobEvents.await(
                        MessageType.USER_LIST_RESPONSE
                );
                assertEquals("Alice,Bob", userList.payload());

                alice.sendPublicMessage("Hello from Alice");
                ChatMessage publicForAlice = aliceEvents.await(
                        MessageType.PUBLIC_MESSAGE
                );
                ChatMessage publicForBob = bobEvents.await(
                        MessageType.PUBLIC_MESSAGE
                );
                assertEquals(publicForAlice, publicForBob);
                assertEquals("Alice", publicForBob.sender());

                bob.sendPrivateMessage("alice", "Private hello");
                ChatMessage privateForAlice = aliceEvents.await(
                        MessageType.PRIVATE_MESSAGE
                );
                ChatMessage privateForBob = bobEvents.await(
                        MessageType.PRIVATE_MESSAGE
                );
                assertEquals(privateForAlice, privateForBob);
                assertEquals("Bob", privateForAlice.sender());
                assertEquals("Alice", privateForAlice.target());
                assertTrue(alice.isConnected());
                assertTrue(bob.isConnected());
            }

            server.close();
            serverTask.get(5, TimeUnit.SECONDS);
        } finally {
            serverThread.shutdownNow();
            serverThread.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static final class RecordingListener implements ChatClientListener {
        private final BlockingQueue<ChatMessage> messages =
                new LinkedBlockingQueue<>();

        @Override
        public void onMessage(ChatMessage message) {
            messages.add(message);
        }

        private ChatMessage await(MessageType type) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                ChatMessage message = messages.poll(200, TimeUnit.MILLISECONDS);
                if (message != null && message.type() == type) {
                    return message;
                }
            }
            throw new AssertionError("Timed out waiting for " + type);
        }
    }
}
