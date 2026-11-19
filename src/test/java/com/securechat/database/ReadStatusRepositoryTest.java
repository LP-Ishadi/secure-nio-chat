package com.securechat.database;

import com.securechat.model.ChatGroup;
import com.securechat.model.ConversationSummary;
import com.securechat.model.GroupMemberRole;
import com.securechat.protocol.ChatMessage;
import com.securechat.protocol.MessageType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadStatusRepositoryTest {

    @TempDir
    Path tempDir;

    private ReadStatusRepository readStatusRepository;
    private UserRepository userRepository;
    private GroupRepository groupRepository;
    private MessageRepository messageRepository;

    @BeforeEach
    void setUp() throws Exception {
        Path testDb = tempDir.resolve("test-read-status.db");
        String dbUrl = "jdbc:sqlite:" + testDb.toAbsolutePath().toString().replace('\\', '/');
        DatabaseManager.setDatabaseUrl(dbUrl);
        DatabaseManager.initializeDatabase();

        userRepository = new UserRepository();
        groupRepository = new GroupRepository();
        readStatusRepository = new ReadStatusRepository(userRepository, groupRepository);
        messageRepository = new MessageRepository();

        // Register test users
        userRepository.registerUser("alice", "Password123!".toCharArray());
        userRepository.registerUser("bob", "Password123!".toCharArray());
        userRepository.registerUser("carol", "Password123!".toCharArray());
    }

    @AfterEach
    void tearDown() {
        DatabaseManager.resetDatabaseUrl();
    }

    @Test
    void testPersonalChatUnreadCountAndReset() throws SQLException {
        // Initially 0 unread messages
        assertEquals(0, readStatusRepository.getUnreadCount("bob", "alice", false));

        // Alice sends message 1 to Bob
        ChatMessage msg1 = new ChatMessage(
                MessageType.PRIVATE_MESSAGE,
                "alice",
                "bob",
                "Hello Bob 1",
                System.currentTimeMillis(),
                UUID.randomUUID().toString()
        );
        messageRepository.saveMessage(msg1);

        assertEquals(1, readStatusRepository.getUnreadCount("bob", "alice", false));
        // Alice should have 0 unread for messages she sent
        assertEquals(0, readStatusRepository.getUnreadCount("alice", "bob", false));

        // Alice sends message 2 to Bob
        ChatMessage msg2 = new ChatMessage(
                MessageType.PRIVATE_MESSAGE,
                "alice",
                "bob",
                "Hello Bob 2",
                System.currentTimeMillis(),
                UUID.randomUUID().toString()
        );
        messageRepository.saveMessage(msg2);

        assertEquals(2, readStatusRepository.getUnreadCount("bob", "alice", false));

        // Bob opens the conversation and marks as read (watermark = 0 means up to latest)
        readStatusRepository.markConversationRead("bob", "alice", false, 0);

        assertEquals(0, readStatusRepository.getUnreadCount("bob", "alice", false));

        // Alice sends message 3
        ChatMessage msg3 = new ChatMessage(
                MessageType.PRIVATE_MESSAGE,
                "alice",
                "bob",
                "Hello Bob 3",
                System.currentTimeMillis(),
                UUID.randomUUID().toString()
        );
        messageRepository.saveMessage(msg3);

        assertEquals(1, readStatusRepository.getUnreadCount("bob", "alice", false));
    }

    @Test
    void testGroupChatUnreadCountExcludesOwnMessages() throws SQLException {
        // Alice creates a group
        ChatGroup group = groupRepository.createGroup("Colombo Flood", "Updates", "alice", List.of("bob", "carol"));
        assertNotNull(group);
        String groupId = group.groupId();

        // Initially everyone has 0 unread
        assertEquals(0, readStatusRepository.getUnreadCount("alice", groupId, true));
        assertEquals(0, readStatusRepository.getUnreadCount("bob", groupId, true));
        assertEquals(0, readStatusRepository.getUnreadCount("carol", groupId, true));

        // Alice sends a group message
        groupRepository.saveGroupMessage(groupId, "alice", "Water level rising", System.currentTimeMillis(), UUID.randomUUID().toString());

        // Alice (sender) has 0 unread
        assertEquals(0, readStatusRepository.getUnreadCount("alice", groupId, true));
        // Bob and Carol have 1 unread
        assertEquals(1, readStatusRepository.getUnreadCount("bob", groupId, true));
        assertEquals(1, readStatusRepository.getUnreadCount("carol", groupId, true));

        // Bob sends a response
        groupRepository.saveGroupMessage(groupId, "bob", "Understood, sending boats", System.currentTimeMillis(), UUID.randomUUID().toString());

        // Alice now has 1 unread (Bob's message)
        assertEquals(1, readStatusRepository.getUnreadCount("alice", groupId, true));
        // Bob has 1 unread (Alice's message, Bob's own message not counted)
        assertEquals(1, readStatusRepository.getUnreadCount("bob", groupId, true));
        // Carol has 2 unread (Alice's + Bob's)
        assertEquals(2, readStatusRepository.getUnreadCount("carol", groupId, true));

        // Carol opens group and marks as read
        readStatusRepository.markConversationRead("carol", groupId, true, 0);
        assertEquals(0, readStatusRepository.getUnreadCount("carol", groupId, true));

        // Bob opens group and marks as read
        readStatusRepository.markConversationRead("bob", groupId, true, 0);
        assertEquals(0, readStatusRepository.getUnreadCount("bob", groupId, true));
    }

    @Test
    void testWatermarkOnlyAdvancesForward() throws SQLException {
        readStatusRepository.markConversationRead("alice", "bob", false, 10);
        assertEquals(10, readStatusRepository.getLastReadMessageId("alice", "bob", false));

        // Try to regress watermark to 5
        readStatusRepository.markConversationRead("alice", "bob", false, 5);
        // Watermark must remain 10
        assertEquals(10, readStatusRepository.getLastReadMessageId("alice", "bob", false));

        // Advance to 15
        readStatusRepository.markConversationRead("alice", "bob", false, 15);
        assertEquals(15, readStatusRepository.getLastReadMessageId("alice", "bob", false));
    }

    @Test
    void testConversationSummariesForUser() throws SQLException {
        // Alice sends private message to Bob
        long time1 = System.currentTimeMillis();
        messageRepository.saveMessage(new ChatMessage(
                MessageType.PRIVATE_MESSAGE,
                "alice",
                "bob",
                "Hey Bob!",
                time1,
                UUID.randomUUID().toString()
        ));

        // Bob belongs to a group
        ChatGroup group = groupRepository.createGroup("Volunteers", "Ops", "carol", List.of("bob"));
        String groupId = group.groupId();

        long time2 = System.currentTimeMillis();
        groupRepository.saveGroupMessage(groupId, "carol", "Meeting at 5pm", time2, UUID.randomUUID().toString());

        // Fetch summaries for Bob
        List<ConversationSummary> summaries = readStatusRepository.getConversationSummaries("bob");
        assertNotNull(summaries);
        assertFalse(summaries.isEmpty());

        // Find alice summary
        ConversationSummary aliceSummary = summaries.stream()
                .filter(s -> s.conversationId().equalsIgnoreCase("alice"))
                .findFirst()
                .orElse(null);
        assertNotNull(aliceSummary);
        assertFalse(aliceSummary.isGroup());
        assertEquals("Hey Bob!", aliceSummary.lastMessage());
        assertEquals("alice", aliceSummary.lastSender());
        assertEquals(1, aliceSummary.unreadCount());

        // Find group summary
        ConversationSummary groupSummary = summaries.stream()
                .filter(s -> s.conversationId().equalsIgnoreCase(groupId))
                .findFirst()
                .orElse(null);
        assertNotNull(groupSummary);
        assertTrue(groupSummary.isGroup());
        assertEquals("Meeting at 5pm", groupSummary.lastMessage());
        assertEquals("carol", groupSummary.lastSender());
        assertEquals(1, groupSummary.unreadCount());
    }
}
