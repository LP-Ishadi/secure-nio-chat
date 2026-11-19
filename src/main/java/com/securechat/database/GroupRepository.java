package com.securechat.database;

import com.securechat.model.ChatGroup;
import com.securechat.model.GroupMember;
import com.securechat.model.GroupMemberRole;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages persistent storage and retrieval for chat groups, memberships,
 * and group messages in SQLite.
 */
public class GroupRepository {

    private final UserRepository userRepository;

    public GroupRepository() {
        this(new UserRepository());
    }

    public GroupRepository(UserRepository userRepository) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository must not be null");
    }

    /**
     * Creates a new chat group, registers the creator as group administrator,
     * and optionally adds initial registered members.
     */
    public ChatGroup createGroup(
            String name,
            String description,
            String creatorUsername,
            List<String> initialMembers
    ) throws SQLException {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Group name must not be blank.");
        }
        if (creatorUsername == null || creatorUsername.isBlank()) {
            throw new IllegalArgumentException("Creator username must not be blank.");
        }
        if (!userRepository.userExists(creatorUsername)) {
            throw new IllegalArgumentException("Creator is not a registered user: " + creatorUsername);
        }

        String groupId = "grp-" + UUID.randomUUID().toString();
        long createdAt = System.currentTimeMillis();
        String desc = description == null ? "" : description.trim();

        String insertGroupSql = """
                INSERT INTO chat_groups (group_id, name, description, creator_username, created_at)
                VALUES (?, ?, ?, ?, ?)
                """;

        String insertMemberSql = """
                INSERT INTO group_members (group_id, username, role, joined_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(group_id, username) DO NOTHING
                """;

        try (Connection connection = DatabaseManager.getConnection()) {
            connection.setAutoCommit(false);
            try {
                // 1. Insert group record
                try (PreparedStatement groupStmt = connection.prepareStatement(insertGroupSql)) {
                    groupStmt.setString(1, groupId);
                    groupStmt.setString(2, name.trim());
                    groupStmt.setString(3, desc);
                    groupStmt.setString(4, creatorUsername.trim());
                    groupStmt.setLong(5, createdAt);
                    groupStmt.executeUpdate();
                }

                // 2. Creator automatically becomes group administrator
                try (PreparedStatement memberStmt = connection.prepareStatement(insertMemberSql)) {
                    memberStmt.setString(1, groupId);
                    memberStmt.setString(2, creatorUsername.trim());
                    memberStmt.setString(3, GroupMemberRole.ADMIN.name());
                    memberStmt.setLong(4, createdAt);
                    memberStmt.executeUpdate();
                }

                // 3. Add initial members if provided
                if (initialMembers != null && !initialMembers.isEmpty()) {
                    try (PreparedStatement memberStmt = connection.prepareStatement(insertMemberSql)) {
                        for (String member : initialMembers) {
                            if (member == null || member.isBlank()
                                    || member.equalsIgnoreCase(creatorUsername)) {
                                continue;
                            }
                            String trimmed = member.trim();
                            if (userRepository.userExists(trimmed)) {
                                memberStmt.setString(1, groupId);
                                memberStmt.setString(2, trimmed);
                                memberStmt.setString(3, GroupMemberRole.MEMBER.name());
                                memberStmt.setLong(4, createdAt);
                                memberStmt.executeUpdate();
                            }
                        }
                    }
                }

                connection.commit();
                return new ChatGroup(groupId, name.trim(), desc, creatorUsername.trim(), createdAt);
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    /**
     * Retrieves group information by unique group ID.
     */
    public Optional<ChatGroup> getGroup(String groupId) throws SQLException {
        if (groupId == null || groupId.isBlank()) {
            return Optional.empty();
        }

        String sql = """
                SELECT group_id, name, description, creator_username, created_at
                FROM chat_groups
                WHERE group_id = ?
                """;

        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, groupId);

            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new ChatGroup(
                            rs.getString("group_id"),
                            rs.getString("name"),
                            rs.getString("description"),
                            rs.getString("creator_username"),
                            rs.getLong("created_at")
                    ));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Displays all groups a member belongs to.
     */
    public List<ChatGroup> getGroupsForUser(String username) throws SQLException {
        if (username == null || username.isBlank()) {
            return Collections.emptyList();
        }

        String sql = """
                SELECT g.group_id, g.name, g.description, g.creator_username, g.created_at
                FROM chat_groups g
                JOIN group_members m ON g.group_id = m.group_id
                WHERE m.username = ? COLLATE NOCASE
                ORDER BY g.name COLLATE NOCASE ASC
                """;

        List<ChatGroup> groups = new ArrayList<>();
        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, username.trim());

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    groups.add(new ChatGroup(
                            rs.getString("group_id"),
                            rs.getString("name"),
                            rs.getString("description"),
                            rs.getString("creator_username"),
                            rs.getLong("created_at")
                    ));
                }
            }
        }
        return Collections.unmodifiableList(groups);
    }

    /**
     * Displays the member list for a group, sorted with admins first.
     */
    public List<GroupMember> getGroupMembers(String groupId) throws SQLException {
        if (groupId == null || groupId.isBlank()) {
            return Collections.emptyList();
        }

        String sql = """
                SELECT group_id, username, role, joined_at
                FROM group_members
                WHERE group_id = ?
                ORDER BY CASE WHEN role = 'ADMIN' THEN 0 ELSE 1 END,
                         username COLLATE NOCASE ASC
                """;

        List<GroupMember> members = new ArrayList<>();
        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, groupId.trim());

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    members.add(new GroupMember(
                            rs.getString("group_id"),
                            rs.getString("username"),
                            GroupMemberRole.fromStringOrDefault(rs.getString("role"), GroupMemberRole.MEMBER),
                            rs.getLong("joined_at")
                    ));
                }
            }
        }
        return Collections.unmodifiableList(members);
    }

    /**
     * Checks if a user is currently a member of a group.
     */
    public boolean isMember(String groupId, String username) throws SQLException {
        if (groupId == null || username == null || groupId.isBlank() || username.isBlank()) {
            return false;
        }

        String sql = """
                SELECT 1
                FROM group_members
                WHERE group_id = ? AND username = ? COLLATE NOCASE
                """;

        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, groupId.trim());
            statement.setString(2, username.trim());

            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Checks if a user is an administrator of the specified group.
     */
    public boolean isAdmin(String groupId, String username) throws SQLException {
        if (groupId == null || username == null || groupId.isBlank() || username.isBlank()) {
            return false;
        }

        String sql = """
                SELECT 1
                FROM group_members
                WHERE group_id = ? AND username = ? COLLATE NOCASE AND role = 'ADMIN'
                """;

        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, groupId.trim());
            statement.setString(2, username.trim());

            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Adds an existing registered user to a group.
     * Prevents duplicate memberships.
     */
    public boolean addMember(
            String groupId,
            String username,
            GroupMemberRole role
    ) throws SQLException {
        if (groupId == null || username == null || groupId.isBlank() || username.isBlank()) {
            throw new IllegalArgumentException("Group ID and username must not be blank.");
        }

        String trimmedUser = username.trim();
        if (!userRepository.userExists(trimmedUser)) {
            throw new IllegalArgumentException("User is not registered: " + trimmedUser);
        }

        if (isMember(groupId, trimmedUser)) {
            throw new IllegalArgumentException("User is already a member of this group: " + trimmedUser);
        }

        String sql = """
                INSERT INTO group_members (group_id, username, role, joined_at)
                VALUES (?, ?, ?, ?)
                """;

        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, groupId.trim());
            statement.setString(2, trimmedUser);
            statement.setString(3, (role == null ? GroupMemberRole.MEMBER : role).name());
            statement.setLong(4, System.currentTimeMillis());

            return statement.executeUpdate() == 1;
        }
    }

    /**
     * Removes a member from a group.
     */
    public boolean removeMember(String groupId, String username) throws SQLException {
        if (groupId == null || username == null || groupId.isBlank() || username.isBlank()) {
            return false;
        }

        String sql = """
                DELETE FROM group_members
                WHERE group_id = ? AND username = ? COLLATE NOCASE
                """;

        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, groupId.trim());
            statement.setString(2, username.trim());

            return statement.executeUpdate() > 0;
        }
    }

    /**
     * Allows a member to voluntarily leave a group.
     */
    public boolean leaveGroup(String groupId, String username) throws SQLException {
        return removeMember(groupId, username);
    }

    /**
     * Stores a group message persistently in SQLite.
     * Supports both text and future voice messages.
     */
    public StoredGroupMessage saveGroupMessage(
            String groupId,
            String sender,
            String content,
            String messageType,
            String mediaUrl,
            long sentAt,
            String requestId
    ) throws SQLException {
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("Group ID must not be blank.");
        }
        if (sender == null || sender.isBlank()) {
            throw new IllegalArgumentException("Sender must not be blank.");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Message content must not be blank.");
        }

        String type = (messageType == null || messageType.isBlank()) ? "TEXT" : messageType.trim();

        String sql = """
                INSERT INTO group_messages (group_id, sender, content, message_type, media_url, sent_at, request_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            statement.setString(1, groupId.trim());
            statement.setString(2, sender.trim());
            statement.setString(3, content);
            statement.setString(4, type);
            statement.setString(5, mediaUrl);
            statement.setLong(6, sentAt);
            statement.setString(7, requestId);

            statement.executeUpdate();

            long id = 0;
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    id = keys.getLong(1);
                }
            }

            return new StoredGroupMessage(id, groupId.trim(), sender.trim(), content, type, mediaUrl, sentAt, requestId);
        }
    }

    public StoredGroupMessage saveGroupMessage(
            String groupId,
            String sender,
            String content,
            long sentAt,
            String requestId
    ) throws SQLException {
        return saveGroupMessage(groupId, sender, content, "TEXT", null, sentAt, requestId);
    }

    /**
     * Retrieves paginated message history for a group, returned in chronological order.
     */
    public List<StoredGroupMessage> getGroupMessages(
            String groupId,
            int limit,
            int offset
    ) throws SQLException {
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("Group ID must not be blank.");
        }
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("Limit must be between 1 and 500.");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("Offset must not be negative.");
        }

        String sql = """
                SELECT id, group_id, sender, content, message_type, media_url, sent_at, request_id
                FROM group_messages
                WHERE group_id = ?
                ORDER BY sent_at DESC, id DESC
                LIMIT ? OFFSET ?
                """;

        List<StoredGroupMessage> messages = new ArrayList<>();
        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, groupId.trim());
            statement.setInt(2, limit);
            statement.setInt(3, offset);

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    messages.add(new StoredGroupMessage(
                            rs.getLong("id"),
                            rs.getString("group_id"),
                            rs.getString("sender"),
                            rs.getString("content"),
                            rs.getString("message_type"),
                            rs.getString("media_url"),
                            rs.getLong("sent_at"),
                            rs.getString("request_id")
                    ));
                }
            }
        }

        // Return oldest to newest for straightforward chat UI display
        Collections.reverse(messages);
        return messages;
    }

    /**
     * Cursor-based pagination: retrieves messages sent before a specified timestamp.
     */
    public List<StoredGroupMessage> getGroupMessagesBefore(
            String groupId,
            int limit,
            long beforeSentAt
    ) throws SQLException {
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("Group ID must not be blank.");
        }
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("Limit must be between 1 and 500.");
        }

        String sql = """
                SELECT id, group_id, sender, content, message_type, media_url, sent_at, request_id
                FROM group_messages
                WHERE group_id = ? AND sent_at < ?
                ORDER BY sent_at DESC, id DESC
                LIMIT ?
                """;

        List<StoredGroupMessage> messages = new ArrayList<>();
        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, groupId.trim());
            statement.setLong(2, beforeSentAt);
            statement.setInt(3, limit);

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    messages.add(new StoredGroupMessage(
                            rs.getLong("id"),
                            rs.getString("group_id"),
                            rs.getString("sender"),
                            rs.getString("content"),
                            rs.getString("message_type"),
                            rs.getString("media_url"),
                            rs.getLong("sent_at"),
                            rs.getString("request_id")
                    ));
                }
            }
        }

        Collections.reverse(messages);
        return messages;
    }
}
