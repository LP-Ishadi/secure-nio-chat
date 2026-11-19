package com.securechat.database;

import com.securechat.protocol.ChatMessage;
import com.securechat.protocol.MessageType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageRepositoryTest {

    @TempDir
    Path tempDir;

    private MessageRepository messageRepository;

    @BeforeEach
    void setUp() throws IOException, SQLException {
        Path testDb = tempDir.resolve("test-messages.db");
        DatabaseManager.setDatabaseUrl("jdbc:sqlite:" + testDb.toAbsolutePath().toString().replace('\\', '/'));
        DatabaseManager.initializeDatabase();
        messageRepository = new MessageRepository();
    }

    @AfterEach
    void tearDown() {
        DatabaseManager.resetDatabaseUrl();
    }

    @Test
    void savesAndRetrievesPublicMessagesChronologically() throws Exception {
        ChatMessage msg1 = new ChatMessage(
                MessageType.PUBLIC_MESSAGE, "Alice", null, "First public", 1000L, "req-1"
        );
        ChatMessage msg2 = new ChatMessage(
                MessageType.PUBLIC_MESSAGE, "Bob", null, "Second public", 2000L, "req-2"
        );

        messageRepository.saveMessage(msg1);
        messageRepository.saveMessage(msg2);

        List<StoredMessage> retrieved = messageRepository.getPublicMessages(10);
        assertEquals(2, retrieved.size());
        assertEquals("First public", retrieved.get(0).content());
        assertEquals("Alice", retrieved.get(0).sender());
        assertNull(retrieved.get(0).recipient());
        assertEquals(1000L, retrieved.get(0).sentAt());

        assertEquals("Second public", retrieved.get(1).content());
        assertEquals("Bob", retrieved.get(1).sender());
        assertEquals(2000L, retrieved.get(1).sentAt());
    }

    @Test
    void savesAndRetrievesPrivateMessagesBetweenUsersChronologically() throws Exception {
        ChatMessage msg1 = new ChatMessage(
                MessageType.PRIVATE_MESSAGE, "Alice", "Bob", "Hi Bob", 1000L, "p-1"
        );
        ChatMessage msg2 = new ChatMessage(
                MessageType.PRIVATE_MESSAGE, "Bob", "Alice", "Hi Alice", 2000L, "p-2"
        );
        ChatMessage msg3 = new ChatMessage(
                MessageType.PRIVATE_MESSAGE, "Alice", "Charlie", "Hi Charlie", 3000L, "p-3"
        );

        messageRepository.saveMessage(msg1);
        messageRepository.saveMessage(msg2);
        messageRepository.saveMessage(msg3);

        List<StoredMessage> aliceBobChat = messageRepository.getConversation("Alice", "Bob", 10);
        assertEquals(2, aliceBobChat.size());
        assertEquals("Hi Bob", aliceBobChat.get(0).content());
        assertEquals("Hi Alice", aliceBobChat.get(1).content());

        // Case insensitivity
        List<StoredMessage> caseInsensitive = messageRepository.getConversation("bob", "alice", 10);
        assertEquals(2, caseInsensitive.size());

        List<StoredMessage> aliceCharlieChat = messageRepository.getConversation("Alice", "Charlie", 10);
        assertEquals(1, aliceCharlieChat.size());
        assertEquals("Hi Charlie", aliceCharlieChat.get(0).content());
    }

    @Test
    void rejectsInvalidMessageTypesForStorage() {
        ChatMessage login = ChatMessage.create(MessageType.LOGIN, "Alice", null, null);
        assertThrows(IllegalArgumentException.class, () -> messageRepository.saveMessage(login));
    }
}
