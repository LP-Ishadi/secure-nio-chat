package com.securechat.database;

import com.securechat.model.ChatGroup;
import com.securechat.model.ConversationSummary;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Manages conversation read watermarks, unread message counts, and
 * conversation preview summaries persisted in SQLite.
 */
public class ReadStatusRepository {

    private final UserRepository userRepository;
    private final GroupRepository groupRepository;

    public ReadStatusRepository() {
        this(new UserRepository(), new GroupRepository());
    }

    public ReadStatusRepository(UserRepository userRepository, GroupRepository groupRepository) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository must not be null");
        this.groupRepository = Objects.requireNonNull(groupRepository, "groupRepository must not be null");
    }

    /**
     * Updates the last read message watermark for a user and conversation.
     * Uses atomic SQLite upsert and ensures watermark only advances forward.
     */
    public void markConversationRead(
            String username,
            String conversationId,
            boolean isGroup,
            long lastReadMessageId
    ) throws SQLException {
        if (username == null || username.isBlank()
                || conversationId == null || conversationId.isBlank()) {
            return;
        }

        String user = username.trim();
        String conv = conversationId.trim();

        // If no explicit message ID was provided, advance to the latest available message ID
        if (lastReadMessageId <= 0) {
            lastReadMessageId = getLatestMessageId(user, conv, isGroup);
        }

        long now = System.currentTimeMillis();

        String sql = """
                INSERT INTO conversation_read_state (username, conversation_id, is_group, last_read_message_id, last_read_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(username, conversation_id, is_group)
                DO UPDATE SET
                    last_read_message_id = MAX(conversation_read_state.last_read_message_id, excluded.last_read_message_id),
                    last_read_at = excluded.last_read_at
                """;

        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, user);
            statement.setString(2, conv);
            statement.setInt(3, isGroup ? 1 : 0);
            statement.setLong(4, lastReadMessageId);
            statement.setLong(5, now);

            statement.executeUpdate();
        }
    }

    /**
     * Gets the latest message ID in a conversation.
     */
    public long getLatestMessageId(String username, String conversationId, boolean isGroup)
            throws SQLException {
        if (isGroup) {
            String sql = "SELECT COALESCE(MAX(id), 0) FROM group_messages WHERE group_id = ?";
            try (Connection connection = DatabaseManager.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, conversationId);
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0;
                }
            }
        } else {
            String sql = """
                    SELECT COALESCE(MAX(id), 0)
                    FROM messages
                    WHERE message_type = 'PRIVATE_MESSAGE'
                      AND ((sender = ? AND recipient = ?) OR (sender = ? AND recipient = ?))
                    """;
            try (Connection connection = DatabaseManager.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, username);
                statement.setString(2, conversationId);
                statement.setString(3, conversationId);
                statement.setString(4, username);
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0;
                }
            }
        }
    }

    /**
     * Retrieves the persisted last-read message watermark.
     */
    public long getLastReadMessageId(String username, String conversationId, boolean isGroup)
            throws SQLException {
        if (username == null || conversationId == null) {
            return 0;
        }

        String sql = """
                SELECT last_read_message_id
                FROM conversation_read_state
                WHERE username = ? AND conversation_id = ? AND is_group = ?
                """;

        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, username.trim());
            statement.setString(2, conversationId.trim());
            statement.setInt(3, isGroup ? 1 : 0);

            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong("last_read_message_id") : 0;
            }
        }
    }

    /**
     * Calculates the unread message count for a personal or group conversation.
     */
    public int getUnreadCount(String username, String conversationId, boolean isGroup)
            throws SQLException {
        if (username == null || conversationId == null) {
            return 0;
        }

        if (isGroup) {
            String sql = """
                    SELECT COUNT(*)
                    FROM group_messages
                    WHERE group_id = ? AND sender != ? COLLATE NOCASE
                      AND id > (
                          SELECT COALESCE(MAX(last_read_message_id), 0)
                          FROM conversation_read_state
                          WHERE username = ? AND conversation_id = ? AND is_group = 1
                      )
                    """;
            try (Connection connection = DatabaseManager.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {

                statement.setString(1, conversationId.trim());
                statement.setString(2, username.trim());
                statement.setString(3, username.trim());
                statement.setString(4, conversationId.trim());

                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        } else {
            String sql = """
                    SELECT COUNT(*)
                    FROM messages
                    WHERE message_type = 'PRIVATE_MESSAGE'
                      AND sender = ? AND recipient = ?
                      AND id > (
                          SELECT COALESCE(MAX(last_read_message_id), 0)
                          FROM conversation_read_state
                          WHERE username = ? AND conversation_id = ? AND is_group = 0
                      )
                    """;
            try (Connection connection = DatabaseManager.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {

                statement.setString(1, conversationId.trim());
                statement.setString(2, username.trim());
                statement.setString(3, username.trim());
                statement.setString(4, conversationId.trim());

                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        }
    }

    /**
     * Generates conversation summaries for a user, including latest message preview,
     * timestamp, and unread counts for all personal chats and groups.
     */
    public List<ConversationSummary> getConversationSummaries(String username) throws SQLException {
        if (username == null || username.isBlank()) {
            return Collections.emptyList();
        }

        String user = username.trim();
        List<ConversationSummary> summaries = new ArrayList<>();

        // 1. Personal contacts
        List<String> registeredUsers = userRepository.getAllRegisteredUsernames();
        for (String contact : registeredUsers) {
            if (contact.equalsIgnoreCase(user)) {
                continue;
            }

            int unread = getUnreadCount(user, contact, false);

            // Fetch latest message between user and contact
            String latestSql = """
                    SELECT content, sender, sent_at
                    FROM messages
                    WHERE message_type = 'PRIVATE_MESSAGE'
                      AND ((sender = ? AND recipient = ?) OR (sender = ? AND recipient = ?))
                    ORDER BY sent_at DESC, id DESC
                    LIMIT 1
                    """;

            String lastMsg = "";
            String lastSender = "";
            long lastTime = 0;

            try (Connection connection = DatabaseManager.getConnection();
                 PreparedStatement statement = connection.prepareStatement(latestSql)) {

                statement.setString(1, user);
                statement.setString(2, contact);
                statement.setString(3, contact);
                statement.setString(4, user);

                try (ResultSet rs = statement.executeQuery()) {
                    if (rs.next()) {
                        lastMsg = rs.getString("content");
                        lastSender = rs.getString("sender");
                        lastTime = rs.getLong("sent_at");
                    }
                }
            }

            summaries.add(new ConversationSummary(contact, false, lastMsg, lastSender, lastTime, unread));
        }

        // 2. Group conversations user belongs to
        List<ChatGroup> userGroups = groupRepository.getGroupsForUser(user);
        for (ChatGroup group : userGroups) {
            int unread = getUnreadCount(user, group.groupId(), true);

            String latestGroupSql = """
                    SELECT content, sender, sent_at
                    FROM group_messages
                    WHERE group_id = ?
                    ORDER BY sent_at DESC, id DESC
                    LIMIT 1
                    """;

            String lastMsg = "";
            String lastSender = "";
            long lastTime = group.createdAt();

            try (Connection connection = DatabaseManager.getConnection();
                 PreparedStatement statement = connection.prepareStatement(latestGroupSql)) {

                statement.setString(1, group.groupId());

                try (ResultSet rs = statement.executeQuery()) {
                    if (rs.next()) {
                        lastMsg = rs.getString("content");
                        lastSender = rs.getString("sender");
                        lastTime = rs.getLong("sent_at");
                    }
                }
            }

            summaries.add(new ConversationSummary(group.groupId(), true, lastMsg, lastSender, lastTime, unread));
        }

        return summaries;
    }
}
