package com.securechat.nio;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securechat.client.ChatClientListener;
import com.securechat.client.LoginRejectedException;
import com.securechat.client.SecureChatClient;
import com.securechat.database.DatabaseManager;
import com.securechat.database.MessageRepository;
import com.securechat.database.StoredMessage;
import com.securechat.database.UserRepository;
import com.securechat.protocol.ChatMessage;
import com.securechat.protocol.MessageType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NioAuthAndPersistenceIntegrationTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        Path testDb = tempDir.resolve("test-chat-integration.db");
        DatabaseManager.setDatabaseUrl("jdbc:sqlite:" + testDb.toAbsolutePath().toString().replace('\\', '/'));
        DatabaseManager.initializeDatabase();
    }

    @AfterEach
    void tearDown() {
        DatabaseManager.resetDatabaseUrl();
    }

    @Test
    void userRegistrationAuthenticationAndHistoryOverTlsNio() throws Exception {
        ExecutorService serverThread = Executors.newSingleThreadExecutor();

        try (NioChatServer server = new NioChatServer(0, 4)) {
            Future<?> serverTask = serverThread.submit(() -> {
                try {
                    server.serve();
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });

            RecordingListener aliceListener = new RecordingListener();
            try (SecureChatClient registrationClient = new SecureChatClient(aliceListener)) {
                // 1. Register Alice
                ChatMessage regAlice = registrationClient.register(
                        "localhost", server.port(), "AliceAuth", "AlicePass123"
                );
                assertEquals(MessageType.REGISTER_SUCCESS, regAlice.type());

                // 2. Duplicate registration rejected
                ChatMessage regDup = registrationClient.register(
                        "localhost", server.port(), "aliceauth", "AlicePass123"
                );
                assertEquals(MessageType.REGISTER_FAILURE, regDup.type());

                // 3. Register Bob
                ChatMessage regBob = registrationClient.register(
                        "localhost", server.port(), "BobAuth", "BobPassword456"
                );
                assertEquals(MessageType.REGISTER_SUCCESS, regBob.type());
            }

            // 4. Test wrong password, missing password, and unregistered user rejected
            RecordingListener badListener = new RecordingListener();
            try (SecureChatClient badClient = new SecureChatClient(badListener)) {
                // Wrong password rejected
                assertThrows(LoginRejectedException.class, () ->
                        badClient.connect("localhost", server.port(), "AliceAuth", "WrongPassword")
                );
            }
            try (SecureChatClient badClient = new SecureChatClient(badListener)) {
                // Missing/null password rejected
                assertThrows(LoginRejectedException.class, () ->
                        badClient.connect("localhost", server.port(), "AliceAuth", null)
                );
            }
            try (SecureChatClient badClient = new SecureChatClient(badListener)) {
                // Unregistered user rejected
                assertThrows(LoginRejectedException.class, () ->
                        badClient.connect("localhost", server.port(), "UnknownUser", "Password123")
                );
            }

            // 5. Connect Alice and Bob with valid credentials
            RecordingListener aliceEvents = new RecordingListener();
            RecordingListener bobEvents = new RecordingListener();
            try (SecureChatClient alice = new SecureChatClient(aliceEvents);
                 SecureChatClient bob = new SecureChatClient(bobEvents)) {

                ChatMessage aliceLogin = alice.connect(
                        "localhost", server.port(), "AliceAuth", "AlicePass123"
                );
                assertEquals(MessageType.LOGIN_SUCCESS, aliceLogin.type());

                ChatMessage bobLogin = bob.connect(
                        "localhost", server.port(), "BobAuth", "BobPassword456"
                );
                assertEquals(MessageType.LOGIN_SUCCESS, bobLogin.type());

                aliceEvents.await(MessageType.USER_JOINED);

                // 6. Public message exchange
                alice.sendPublicMessage("Persistent public greeting");
                ChatMessage pubForAlice = aliceEvents.await(MessageType.PUBLIC_MESSAGE);
                ChatMessage pubForBob = bobEvents.await(MessageType.PUBLIC_MESSAGE);
                assertEquals("Persistent public greeting", pubForAlice.payload());
                assertEquals(pubForAlice.payload(), pubForBob.payload());

                // 7. Private message exchange
                bob.sendPrivateMessage("AliceAuth", "Secret private note");
                ChatMessage privForAlice = aliceEvents.await(MessageType.PRIVATE_MESSAGE);
                ChatMessage privForBob = bobEvents.await(MessageType.PRIVATE_MESSAGE);
                assertEquals("Secret private note", privForAlice.payload());
                assertEquals("BobAuth", privForAlice.sender());
                assertEquals("AliceAuth", privForAlice.target());
                assertEquals(privForAlice.payload(), privForBob.payload());

                // 8. History retrieval for public chat
                alice.requestHistory(null, 10);
                ChatMessage publicHistoryResponse = aliceEvents.await(MessageType.HISTORY_RESPONSE);
                List<StoredMessage> publicHistory = objectMapper.readValue(
                        publicHistoryResponse.payload(),
                        new TypeReference<List<StoredMessage>>() {}
                );
                assertFalse(publicHistory.isEmpty());
                assertEquals("Persistent public greeting", publicHistory.getLast().content());

                // 9. History retrieval for private chat between Alice and Bob
                bob.requestHistory("AliceAuth", 10);
                ChatMessage privateHistoryResponse = bobEvents.await(MessageType.HISTORY_RESPONSE);
                List<StoredMessage> privateHistory = objectMapper.readValue(
                        privateHistoryResponse.payload(),
                        new TypeReference<List<StoredMessage>>() {}
                );
                assertFalse(privateHistory.isEmpty());
                assertEquals("Secret private note", privateHistory.getLast().content());
            }

            server.close();
            serverTask.get(5, TimeUnit.SECONDS);
        } finally {
            serverThread.shutdownNow();
            serverThread.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void registeredUsersOnlineOfflineStatusAndHistoryWithOfflineContact() throws Exception {
        // Pre-register UserOne and UserTwo in SQLite
        UserRepository userRepo = new UserRepository();
        userRepo.registerUser("UserOne", "Password123".toCharArray());
        userRepo.registerUser("UserTwo", "Password456".toCharArray());

        // Pre-seed previous conversation between UserOne and UserTwo
        MessageRepository msgRepo = new MessageRepository();
        ChatMessage historic = new ChatMessage(
                MessageType.PRIVATE_MESSAGE,
                "UserTwo",
                "UserOne",
                "Hello from yesterday",
                System.currentTimeMillis() - 60_000,
                "historic-msg-1"
        );
        msgRepo.saveMessage(historic);

        ExecutorService serverThread = Executors.newSingleThreadExecutor();
        try (NioChatServer server = new NioChatServer(0, 4)) {
            Future<?> serverTask = serverThread.submit(() -> {
                try {
                    server.serve();
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });

            RecordingListener userOneEvents = new RecordingListener();
            try (SecureChatClient userOne = new SecureChatClient(userOneEvents)) {
                // Connect UserOne only (UserTwo remains offline)
                ChatMessage loginOne = userOne.connect(
                        "localhost", server.port(), "UserOne", "Password123"
                );
                assertEquals(MessageType.LOGIN_SUCCESS, loginOne.type());

                // Request user list: must contain both registered users
                userOne.requestUserList();
                ChatMessage userList = userOneEvents.await(MessageType.USER_LIST_RESPONSE);
                assertEquals("UserOne,UserTwo", userList.payload());

                // Request history with offline UserTwo
                userOne.requestHistory("UserTwo", 10);
                ChatMessage historyResp = userOneEvents.await(MessageType.HISTORY_RESPONSE);
                assertEquals("UserTwo", historyResp.target());
                List<StoredMessage> history = objectMapper.readValue(
                        historyResp.payload(),
                        new TypeReference<List<StoredMessage>>() {}
                );
                assertEquals(1, history.size());
                assertEquals("Hello from yesterday", history.getFirst().content());
                assertEquals("UserTwo", history.getFirst().sender());

                // Now connect UserTwo
                RecordingListener userTwoEvents = new RecordingListener();
                try (SecureChatClient userTwo = new SecureChatClient(userTwoEvents)) {
                    ChatMessage loginTwo = userTwo.connect(
                            "localhost", server.port(), "UserTwo", "Password456"
                    );
                    assertEquals(MessageType.LOGIN_SUCCESS, loginTwo.type());

                    // UserOne observes UserTwo joined and presence online
                    userOneEvents.await(MessageType.USER_JOINED);
                    ChatMessage presenceOnline = userOneEvents.awaitPresence("UserTwo", "ONLINE");
                    assertEquals("UserTwo", presenceOnline.target());
                    assertEquals("ONLINE", presenceOnline.payload());

                    // Send a new live private message
                    userOne.sendPrivateMessage("UserTwo", "Welcome online!");
                    ChatMessage privForTwo = userTwoEvents.await(MessageType.PRIVATE_MESSAGE);
                    assertEquals("Welcome online!", privForTwo.payload());
                }

                // UserTwo has disconnected: UserOne observes user left and presence offline
                userOneEvents.await(MessageType.USER_LEFT);
                ChatMessage presenceOffline = userOneEvents.awaitPresence("UserTwo", "OFFLINE");
                assertEquals("UserTwo", presenceOffline.target());
                assertEquals("OFFLINE", presenceOffline.payload());

                // UserOne can still query conversation history with offline UserTwo
                userOne.requestHistory("UserTwo", 10);
                ChatMessage updatedHistoryResp = userOneEvents.await(MessageType.HISTORY_RESPONSE);
                List<StoredMessage> updatedHistory = objectMapper.readValue(
                        updatedHistoryResp.payload(),
                        new TypeReference<List<StoredMessage>>() {}
                );
                assertEquals(2, updatedHistory.size());
                assertEquals("Hello from yesterday", updatedHistory.get(0).content());
                assertEquals("Welcome online!", updatedHistory.get(1).content());
            }

            server.close();
            serverTask.get(5, TimeUnit.SECONDS);
        } finally {
            serverThread.shutdownNow();
            serverThread.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static final class RecordingListener implements ChatClientListener {
        private final List<ChatMessage> messages = new CopyOnWriteArrayList<>();
        private final Set<ChatMessage> consumed = Collections.newSetFromMap(new ConcurrentHashMap<>());

        @Override
        public void onMessage(ChatMessage message) {
            messages.add(message);
        }

        private ChatMessage await(MessageType type) throws Exception {
            return awaitMatching(msg -> msg.type() == type, "message type: " + type);
        }

        private ChatMessage awaitPresence(String targetUser, String expectedStatus) throws Exception {
            return awaitMatching(
                    msg -> msg.type() == MessageType.PRESENCE_UPDATE
                            && targetUser.equalsIgnoreCase(msg.target())
                            && expectedStatus.equalsIgnoreCase(msg.payload()),
                    "presence " + targetUser + "=" + expectedStatus
            );
        }

        private ChatMessage awaitMatching(Predicate<ChatMessage> predicate, String description) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                for (ChatMessage message : messages) {
                    if (!consumed.contains(message) && predicate.test(message)) {
                        consumed.add(message);
                        return message;
                    }
                }
                Thread.sleep(50);
            }
            throw new AssertionError("Timed out waiting for " + description);
        }
    }
}
