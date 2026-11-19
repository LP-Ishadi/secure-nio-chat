package com.securechat.nio;

import com.securechat.database.DatabaseManager;
import com.securechat.database.UserRepository;
import com.securechat.demo.blocking.BlockingMessageConnection;
import com.securechat.protocol.ChatMessage;
import com.securechat.protocol.MessageType;
import com.securechat.testutil.TlsTestSockets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.Socket;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NioRegistrationIntegrationTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        Path testDb = tempDir.resolve("test-reg.db");
        DatabaseManager.setDatabaseUrl("jdbc:sqlite:" + testDb.toAbsolutePath().toString().replace('\\', '/'));
        DatabaseManager.initializeDatabase();
    }

    @AfterEach
    void tearDown() {
        DatabaseManager.resetDatabaseUrl();
    }

    @Test
    void rejectsDuplicateAndPublishesUserLifecycleEvents() throws Exception {
        UserRepository userRepository = new UserRepository();
        userRepository.registerUser("Alice", "ValidPassword123".toCharArray());
        userRepository.registerUser("Bob", "ValidPassword123".toCharArray());

        ExecutorService serverThread = Executors.newSingleThreadExecutor();

        try (NioChatServer server = new NioChatServer(0)) {
            Future<?> serverTask = startServer(serverThread, server);

            try (ClientSession alice = connect(server.port());
                 ClientSession duplicateAlice = connect(server.port());
                 ClientSession bob = connect(server.port())) {
                ChatMessage aliceLogin = alice.login("Alice", "login-alice");
                assertEquals(MessageType.LOGIN_SUCCESS, aliceLogin.type());

                ChatMessage duplicateLogin = duplicateAlice.login(
                        "aLiCe",
                        "login-duplicate"
                );
                assertEquals(MessageType.LOGIN_FAILURE, duplicateLogin.type());
                assertTrue(duplicateLogin.payload().contains("already in use"));

                ChatMessage bobLogin = bob.login("Bob", "login-bob");
                assertEquals(MessageType.LOGIN_SUCCESS, bobLogin.type());

                ChatMessage joined = alice.receiveUntil(MessageType.USER_JOINED);
                assertEquals("Bob", joined.payload());

                alice.send(new ChatMessage(
                        MessageType.USER_LIST_REQUEST,
                        "SomeoneElse",
                        null,
                        null,
                        1_700_000_000_100L,
                        "users-1"
                ));
                ChatMessage users = alice.receiveUntil(
                        MessageType.USER_LIST_RESPONSE
                );
                assertEquals("Alice,Bob", users.payload());
                assertEquals("Alice", users.target());

                bob.close();
                ChatMessage left = alice.receiveUntil(MessageType.USER_LEFT);
                assertEquals("Bob", left.payload());
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

    private static final class ClientSession implements AutoCloseable {
        private final BlockingMessageConnection connection;

        private ClientSession(Socket socket) throws Exception {
            connection = new BlockingMessageConnection(socket);
        }

        private ChatMessage login(String username, String requestId)
                throws Exception {
            send(new ChatMessage(
                    MessageType.LOGIN,
                    username,
                    null,
                    "ValidPassword123",
                    1_700_000_000_000L,
                    requestId
            ));
            return connection.receive();
        }

        private void send(ChatMessage message) throws Exception {
            connection.send(message);
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
