
package com.securechat.database;

import com.securechat.protocol.ChatMessage;
import com.securechat.protocol.MessageType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class MessageRepository {

    public void saveMessage(ChatMessage message) throws SQLException {

        if (message.type() != MessageType.PUBLIC_MESSAGE
                && message.type() != MessageType.PRIVATE_MESSAGE) {
            throw new IllegalArgumentException(
                    "Only chat messages can be stored."
            );
        }

        String sql = """
                INSERT INTO messages (
                    message_type,
                    sender,
                    recipient,
                    content,
                    sent_at,
                    request_id
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """;

        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setString(1, message.type().name());
            statement.setString(2, message.sender());
            statement.setString(3, message.target());
            statement.setString(4, message.payload());
            statement.setLong(5, message.timestamp());
            statement.setString(6, message.requestId());

            statement.executeUpdate();
        }
    }

    public List<StoredMessage> getConversation(
            String username,
            String contact,
            int limit
    ) throws SQLException {

        if (username == null || contact == null
                || username.isBlank() || contact.isBlank()) {
            throw new IllegalArgumentException(
                    "Usernames must not be blank."
            );
        }

        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException(
                    "Limit must be between 1 and 500."
            );
        }

        String sql = """
                SELECT id, message_type, sender, recipient,
                       content, sent_at, request_id
                FROM messages
                WHERE message_type = 'PRIVATE_MESSAGE'
                  AND (
                    (sender = ? AND recipient = ?)
                    OR
                    (sender = ? AND recipient = ?)
                  )
                ORDER BY sent_at DESC, id DESC
                LIMIT ?
                """;

        List<StoredMessage> messages = new ArrayList<>();

        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setString(1, username);
            statement.setString(2, contact);
            statement.setString(3, contact);
            statement.setString(4, username);
            statement.setInt(5, limit);

            try (ResultSet result = statement.executeQuery()) {

                while (result.next()) {
                    messages.add(new StoredMessage(
                            result.getLong("id"),
                            result.getString("message_type"),
                            result.getString("sender"),
                            result.getString("recipient"),
                            result.getString("content"),
                            result.getLong("sent_at"),
                            result.getString("request_id")
                    ));
                }
            }
        }

        java.util.Collections.reverse(messages);

        return messages;
    }

    public List<StoredMessage> getPublicMessages(
            int limit
    ) throws SQLException {

        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException(
                    "Limit must be between 1 and 500."
            );
        }

        String sql = """
                SELECT id, message_type, sender, recipient,
                       content, sent_at, request_id
                FROM messages
                WHERE message_type = 'PUBLIC_MESSAGE'
                ORDER BY sent_at DESC, id DESC
                LIMIT ?
                """;

        List<StoredMessage> messages = new ArrayList<>();

        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setInt(1, limit);

            try (ResultSet result = statement.executeQuery()) {

                while (result.next()) {
                    messages.add(new StoredMessage(
                            result.getLong("id"),
                            result.getString("message_type"),
                            result.getString("sender"),
                            result.getString("recipient"),
                            result.getString("content"),
                            result.getLong("sent_at"),
                            result.getString("request_id")
                    ));
                }
            }
        }

        java.util.Collections.reverse(messages);

        return messages;
    }
}
