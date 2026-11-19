package com.securechat.concurrent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securechat.database.DatabaseManager;
import com.securechat.database.GroupRepository;
import com.securechat.database.MessageRepository;
import com.securechat.database.StoredGroupMessage;
import com.securechat.database.UserRepository;
import com.securechat.model.ChatGroup;
import com.securechat.model.GroupMember;
import com.securechat.model.GroupMemberRole;
import com.securechat.nio.ClientConnection;
import com.securechat.protocol.ChatMessage;
import com.securechat.protocol.MessageType;
import com.securechat.security.HeartbeatCredentials;
import com.securechat.security.SessionTokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageDispatcherGroupTest {

    @TempDir
    Path tempDir;

    private ClientRegistry clientRegistry;
    private TestDelivery delivery;
    private UserRepository userRepository;
    private MessageRepository messageRepository;
    private GroupRepository groupRepository;
    private MessageDispatcher dispatcher;
    private ObjectMapper objectMapper;

    private SocketChannel aliceChannel;
    private SocketChannel bobChannel;
    private SocketChannel charlieChannel;
    private ClientConnection aliceConn;
    private ClientConnection bobConn;
    private ClientConnection charlieConn;

    private static class TestDelivery implements MessageDispatcher.Delivery {
        final Map<String, List<ChatMessage>> sentToUser = new ConcurrentHashMap<>();
        final List<ChatMessage> broadcasts = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void send(ClientConnection recipient, ChatMessage message) {
            if (recipient != null && recipient.username() != null) {
                sentToUser.computeIfAbsent(recipient.username().toLowerCase(), k -> new ArrayList<>())
                        .add(message);
            }
        }

        @Override
        public void broadcastExcept(ChatMessage message, ClientConnection excluded) {
            broadcasts.add(message);
        }

        @Override
        public void disconnect(ClientConnection connection) {
        }

        @Override
        public void sessionEstablished(ClientConnection connection, HeartbeatCredentials credentials) {
        }

        List<ChatMessage> getMessages(String username) {
            return sentToUser.getOrDefault(username.toLowerCase(), Collections.emptyList());
        }

        ChatMessage getLastMessage(String username) {
            List<ChatMessage> list = getMessages(username);
            return list.isEmpty() ? null : list.get(list.size() - 1);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        Path testDb = tempDir.resolve("dispatcher-test.db");
        DatabaseManager.setDatabaseUrl("jdbc:sqlite:" + testDb.toAbsolutePath().toString().replace('\\', '/'));
        DatabaseManager.initializeDatabase();

        userRepository = new UserRepository();
        messageRepository = new MessageRepository();
        groupRepository = new GroupRepository(userRepository);
        clientRegistry = new ClientRegistry();
        delivery = new TestDelivery();
        objectMapper = new ObjectMapper();

        dispatcher = new MessageDispatcher(
                clientRegistry,
                delivery,
                new SessionTokenService(),
                5001,
                5000,
                userRepository,
                messageRepository,
                groupRepository,
                null
        );

        // Register users in DB
        char[] pass = "Password123".toCharArray();
        userRepository.registerUser("Alice", pass);
        userRepository.registerUser("Bob", pass);
        userRepository.registerUser("Charlie", pass);
        userRepository.registerUser("David", pass);

        // Open non-blocking socket channels for connection representation
        aliceChannel = SocketChannel.open();
        bobChannel = SocketChannel.open();
        charlieChannel = SocketChannel.open();

        aliceConn = new ClientConnection(aliceChannel);
        bobConn = new ClientConnection(bobChannel);
        charlieConn = new ClientConnection(charlieChannel);

        clientRegistry.register("Alice", aliceConn);
        clientRegistry.register("Bob", bobConn);
        clientRegistry.register("Charlie", charlieConn);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (aliceChannel != null && aliceChannel.isOpen()) aliceChannel.close();
        if (bobChannel != null && bobChannel.isOpen()) bobChannel.close();
        if (charlieChannel != null && charlieChannel.isOpen()) charlieChannel.close();
        DatabaseManager.resetDatabaseUrl();
    }

    @Test
    void groupCreationAndListing() throws Exception {
        String createJson = """
                {
                    "name": "First Responders",
                    "description": "Emergency coordination",
                    "initialMembers": ["Bob"]
                }
                """;
        ChatMessage createMsg = ChatMessage.create(
                MessageType.GROUP_CREATE,
                "Alice",
                null,
                createJson
        );

        dispatcher.dispatch(aliceConn, createMsg);

        ChatMessage response = delivery.getLastMessage("Alice");
        assertNotNull(response);
        assertEquals(MessageType.GROUP_CREATE_SUCCESS, response.type());

        ChatGroup created = objectMapper.readValue(response.payload(), ChatGroup.class);
        assertEquals("First Responders", created.name());
        assertEquals("Alice", created.creatorUsername());

        // Alice requests group list
        ChatMessage listMsg = ChatMessage.create(
                MessageType.GROUP_LIST_REQUEST,
                "Alice",
                null,
                null
        );
        dispatcher.dispatch(aliceConn, listMsg);

        ChatMessage listResponse = delivery.getLastMessage("Alice");
        assertEquals(MessageType.GROUP_LIST_RESPONSE, listResponse.type());
        List<ChatGroup> aliceGroups = objectMapper.readValue(
                listResponse.payload(),
                new TypeReference<List<ChatGroup>>() {}
        );
        assertEquals(1, aliceGroups.size());
        assertEquals(created.groupId(), aliceGroups.getFirst().groupId());

        // Bob requests group list (he was added initially)
        dispatcher.dispatch(bobConn, ChatMessage.create(MessageType.GROUP_LIST_REQUEST, "Bob", null, null));
        ChatMessage bobListResponse = delivery.getLastMessage("Bob");
        List<ChatGroup> bobGroups = objectMapper.readValue(
                bobListResponse.payload(),
                new TypeReference<List<ChatGroup>>() {}
        );
        assertEquals(1, bobGroups.size());
    }

    @Test
    void groupCreationRejectsBlankName() {
        ChatMessage createMsg = ChatMessage.create(
                MessageType.GROUP_CREATE,
                "Alice",
                null,
                "   "
        );
        dispatcher.dispatch(aliceConn, createMsg);

        ChatMessage response = delivery.getLastMessage("Alice");
        assertNotNull(response);
        assertEquals(MessageType.GROUP_CREATE_FAILURE, response.type());
    }

    @Test
    void preventNonMembersFromAccessingGroupConversations() throws Exception {
        ChatGroup group = groupRepository.createGroup("Secret Group", "", "Alice", List.of("Bob"));

        // Charlie is NOT a member and tries to read history
        ChatMessage historyReq = ChatMessage.create(
                MessageType.GROUP_HISTORY_REQUEST,
                "Charlie",
                group.groupId(),
                "50,0"
        );
        dispatcher.dispatch(charlieConn, historyReq);

        ChatMessage charlieResp = delivery.getLastMessage("Charlie");
        assertNotNull(charlieResp);
        assertEquals(MessageType.ERROR, charlieResp.type());
        assertTrue(charlieResp.payload().contains("Access denied"));

        // Charlie tries to send a message
        ChatMessage sendReq = ChatMessage.create(
                MessageType.GROUP_MESSAGE,
                "Charlie",
                group.groupId(),
                "Intruder message"
        );
        dispatcher.dispatch(charlieConn, sendReq);

        charlieResp = delivery.getLastMessage("Charlie");
        assertEquals(MessageType.ERROR, charlieResp.type());
        assertTrue(charlieResp.payload().contains("Access denied"));

        // Charlie tries to get members
        ChatMessage membersReq = ChatMessage.create(
                MessageType.GROUP_MEMBERS_REQUEST,
                "Charlie",
                group.groupId(),
                null
        );
        dispatcher.dispatch(charlieConn, membersReq);

        charlieResp = delivery.getLastMessage("Charlie");
        assertEquals(MessageType.ERROR, charlieResp.type());
        assertTrue(charlieResp.payload().contains("Access denied"));
    }

    @Test
    void onlyAuthorizedAdminsCanPerformAdminOperations() throws Exception {
        ChatGroup group = groupRepository.createGroup("Field Team", "", "Alice", List.of("Bob"));

        // Bob is a regular member and tries to add David
        ChatMessage bobAddDavid = ChatMessage.create(
                MessageType.GROUP_ADD_MEMBER,
                "Bob",
                group.groupId(),
                "David"
        );
        dispatcher.dispatch(bobConn, bobAddDavid);

        ChatMessage bobResp = delivery.getLastMessage("Bob");
        assertNotNull(bobResp);
        assertEquals(MessageType.ERROR, bobResp.type());
        assertTrue(bobResp.payload().contains("Only group administrators can add members"));

        // Alice (admin) adds David
        ChatMessage aliceAddDavid = ChatMessage.create(
                MessageType.GROUP_ADD_MEMBER,
                "Alice",
                group.groupId(),
                "David"
        );
        dispatcher.dispatch(aliceConn, aliceAddDavid);

        ChatMessage aliceResp = delivery.getLastMessage("Alice");
        assertEquals(MessageType.GROUP_OPERATION_SUCCESS, aliceResp.type());
        assertTrue(groupRepository.isMember(group.groupId(), "David"));

        // Bob (non-admin) tries to remove David
        ChatMessage bobRemoveDavid = ChatMessage.create(
                MessageType.GROUP_REMOVE_MEMBER,
                "Bob",
                group.groupId(),
                "David"
        );
        dispatcher.dispatch(bobConn, bobRemoveDavid);

        bobResp = delivery.getLastMessage("Bob");
        assertEquals(MessageType.ERROR, bobResp.type());
        assertTrue(bobResp.payload().contains("Only group administrators can remove members"));

        // Alice removes David
        ChatMessage aliceRemoveDavid = ChatMessage.create(
                MessageType.GROUP_REMOVE_MEMBER,
                "Alice",
                group.groupId(),
                "David"
        );
        dispatcher.dispatch(aliceConn, aliceRemoveDavid);

        aliceResp = delivery.getLastMessage("Alice");
        assertEquals(MessageType.GROUP_OPERATION_SUCCESS, aliceResp.type());
        assertFalse(groupRepository.isMember(group.groupId(), "David"));
    }

    @Test
    void groupMessagingDeliversToAllAuthorizedMembersOnline() throws Exception {
        ChatGroup group = groupRepository.createGroup("Comms", "", "Alice", List.of("Bob"));

        ChatMessage groupMsg = ChatMessage.create(
                MessageType.GROUP_MESSAGE,
                "Alice",
                group.groupId(),
                "Flood advisory updated for sector 2"
        );
        dispatcher.dispatch(aliceConn, groupMsg);

        // Bob should receive it
        ChatMessage bobReceived = delivery.getLastMessage("Bob");
        assertNotNull(bobReceived);
        assertEquals(MessageType.GROUP_MESSAGE, bobReceived.type());
        assertEquals("Alice", bobReceived.sender());
        assertEquals(group.groupId(), bobReceived.target());
        assertEquals("Flood advisory updated for sector 2", bobReceived.payload());

        // Alice (sender) should also receive it
        ChatMessage aliceReceived = delivery.getLastMessage("Alice");
        assertNotNull(aliceReceived);
        assertEquals(MessageType.GROUP_MESSAGE, aliceReceived.type());

        // Charlie (non-member) must NOT receive it
        List<ChatMessage> charlieMsgs = delivery.getMessages("Charlie");
        assertTrue(charlieMsgs.isEmpty());

        // Verify message was persistently stored in SQLite
        List<StoredGroupMessage> stored = groupRepository.getGroupMessages(group.groupId(), 10, 0);
        assertEquals(1, stored.size());
        assertEquals("Flood advisory updated for sector 2", stored.getFirst().content());
    }

    @Test
    void memberCanLeaveGroup() throws Exception {
        ChatGroup group = groupRepository.createGroup("Volunteers", "", "Alice", List.of("Bob"));

        assertTrue(groupRepository.isMember(group.groupId(), "Bob"));

        ChatMessage leaveMsg = ChatMessage.create(
                MessageType.GROUP_LEAVE,
                "Bob",
                group.groupId(),
                null
        );
        dispatcher.dispatch(bobConn, leaveMsg);

        ChatMessage bobResp = delivery.getLastMessage("Bob");
        assertNotNull(bobResp);
        assertEquals(MessageType.GROUP_OPERATION_SUCCESS, bobResp.type());

        assertFalse(groupRepository.isMember(group.groupId(), "Bob"));
    }

    @Test
    void markConversationReadDispatchesReadStatusUpdate() throws Exception {
        ChatMessage markReadMsg = ChatMessage.create(
                MessageType.MARK_READ,
                "Bob",
                "Alice",
                "0"
        );
        dispatcher.dispatch(bobConn, markReadMsg);

        ChatMessage bobResp = delivery.getLastMessage("Bob");
        assertNotNull(bobResp);
        assertEquals(MessageType.READ_STATUS_UPDATE, bobResp.type());
        assertEquals("Alice", bobResp.target());
    }

    @Test
    void conversationSummariesRequestReturnsSummaries() throws Exception {
        ChatMessage req = ChatMessage.create(
                MessageType.CONVERSATION_SUMMARIES_REQUEST,
                "Bob",
                null,
                null
        );
        dispatcher.dispatch(bobConn, req);

        ChatMessage bobResp = delivery.getLastMessage("Bob");
        assertNotNull(bobResp);
        assertEquals(MessageType.CONVERSATION_SUMMARIES_RESPONSE, bobResp.type());
        assertNotNull(bobResp.payload());
        assertTrue(bobResp.payload().startsWith("["));
    }
}
