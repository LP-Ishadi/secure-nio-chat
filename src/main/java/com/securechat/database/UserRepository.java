
package com.securechat.database;

import com.securechat.concurrent.ClientRegistry;
import com.securechat.model.UserRole;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class UserRepository {

    public boolean registerUser(
            String username,
            char[] password
    ) throws SQLException {
        return registerUser(username, password, UserRole.RESIDENT, null, null);
    }

    public boolean registerUser(
            String username,
            char[] password,
            UserRole role,
            String phone,
            String location
    ) throws SQLException {

        if (!ClientRegistry.isValidUsername(username)) {
            throw new IllegalArgumentException(
                    "Username must contain 3-20 letters, numbers, underscores or hyphens."
            );
        }

        if (password == null || password.length < 8) {
            throw new IllegalArgumentException(
                    "Password must contain at least 8 characters."
            );
        }

        if (role == null) {
            role = UserRole.RESIDENT;
        }

        if (role == UserRole.COORDINATOR || role == UserRole.ADMIN) {
            throw new IllegalArgumentException(
                    "Public registration cannot assign privileged role: " + role
            );
        }

        String sql = """
                INSERT INTO users (username, password_hash, role, phone, location)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(username) DO NOTHING
                """;

        String passwordHash = PasswordHasher.hashPassword(password);

        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, username);
            statement.setString(2, passwordHash);
            statement.setString(3, role.name());
            statement.setString(4, phone);
            statement.setString(5, location);

            return statement.executeUpdate() == 1;
        }
    }

    public boolean authenticateUser(
            String username,
            char[] password
    ) throws SQLException {

        if (username == null || password == null) {
            return false;
        }

        String sql = """
                SELECT password_hash
                FROM users
                WHERE username = ?
                """;

        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, username);

            try (ResultSet result = statement.executeQuery()) {

                if (!result.next()) {
                    return false;
                }

                String storedHash = result.getString("password_hash");

                return PasswordHasher.verifyPassword(
                        password, storedHash
                );
            }
        }
    }

    public boolean userExists(String username) throws SQLException {
        if (username == null || username.isBlank()) {
            return false;
        }

        String sql = """
                SELECT 1
                FROM users
                WHERE username = ?
                """;

        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, username);

            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    public List<String> getAllRegisteredUsernames() throws SQLException {
        String sql = """
                SELECT username
                FROM users
                ORDER BY username COLLATE NOCASE ASC
                """;

        List<String> usernames = new ArrayList<>();
        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {

            while (result.next()) {
                usernames.add(result.getString("username"));
            }
        }
        return Collections.unmodifiableList(usernames);
    }

    public UserRole getUserRole(String username) throws SQLException {
        if (username == null || username.isBlank()) {
            return UserRole.RESIDENT;
        }

        String sql = """
                SELECT role
                FROM users
                WHERE username = ?
                """;

        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, username);

            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    String storedRole = result.getString("role");
                    return UserRole.fromStringOrDefault(storedRole, UserRole.RESIDENT);
                }
                return UserRole.RESIDENT;
            }
        }
    }

    public boolean updateUserRole(String username, UserRole role) throws SQLException {
        if (username == null || username.isBlank() || role == null) {
            return false;
        }

        String sql = """
                UPDATE users
                SET role = ?
                WHERE username = ?
                """;

        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, role.name());
            statement.setString(2, username);

            return statement.executeUpdate() == 1;
        }
    }
}
