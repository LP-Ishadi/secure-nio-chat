
package com.securechat.database;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class DatabaseManager {

    private static final String DEFAULT_DATABASE_DIRECTORY = "data";
    private static final String DEFAULT_DATABASE_FILE = "securechat.db";

    public static final String DEFAULT_DATABASE_URL =
            "jdbc:sqlite:" + DEFAULT_DATABASE_DIRECTORY + "/" + DEFAULT_DATABASE_FILE;

    private static volatile String activeDatabaseUrl = DEFAULT_DATABASE_URL;

    private DatabaseManager() {
    }

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(activeDatabaseUrl);
    }

    public static void setDatabaseUrl(String url) {
        activeDatabaseUrl = (url == null || url.isBlank()) ? DEFAULT_DATABASE_URL : url;
    }

    public static void resetDatabaseUrl() {
        activeDatabaseUrl = DEFAULT_DATABASE_URL;
    }

    public static String getDatabaseUrl() {
        return activeDatabaseUrl;
    }

    public static void initializeDatabase()
            throws IOException, SQLException {

        if (activeDatabaseUrl.startsWith("jdbc:sqlite:") && !activeDatabaseUrl.contains(":memory:")) {
            String rawPath = activeDatabaseUrl.substring("jdbc:sqlite:".length());
            Path dbPath = Path.of(rawPath);
            if (dbPath.getParent() != null) {
                Files.createDirectories(dbPath.getParent());
            }
        }

        String createUsersTable = """
                CREATE TABLE IF NOT EXISTS users (
                    user_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    username TEXT NOT NULL UNIQUE COLLATE NOCASE,
                    password_hash TEXT NOT NULL,
                    role TEXT NOT NULL DEFAULT 'RESIDENT',
                    phone TEXT,
                    location TEXT,
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """;

        String createMessagesTable = """
                CREATE TABLE IF NOT EXISTS messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    message_type TEXT NOT NULL,
                    sender TEXT NOT NULL COLLATE NOCASE,
                    recipient TEXT COLLATE NOCASE,
                    content TEXT NOT NULL,
                    sent_at INTEGER NOT NULL,
                    request_id TEXT NOT NULL
                )
                """;

        String createFloodReportsTable = """
                CREATE TABLE IF NOT EXISTS flood_reports (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    reporter_username TEXT NOT NULL COLLATE NOCASE,
                    title TEXT NOT NULL,
                    location TEXT NOT NULL,
                    latitude REAL,
                    longitude REAL,
                    water_depth_cm INTEGER DEFAULT 0,
                    severity TEXT NOT NULL DEFAULT 'MODERATE',
                    description TEXT NOT NULL DEFAULT '',
                    status TEXT NOT NULL DEFAULT 'REPORTED',
                    reported_at INTEGER NOT NULL
                )
                """;

        String createRescueRequestsTable = """
                CREATE TABLE IF NOT EXISTS rescue_requests (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    resident_username TEXT NOT NULL COLLATE NOCASE,
                    location TEXT NOT NULL,
                    latitude REAL,
                    longitude REAL,
                    people_count INTEGER NOT NULL DEFAULT 1,
                    vulnerable_count INTEGER NOT NULL DEFAULT 0,
                    urgency TEXT NOT NULL DEFAULT 'ROUTINE',
                    description TEXT NOT NULL DEFAULT '',
                    status TEXT NOT NULL DEFAULT 'PENDING',
                    created_at INTEGER NOT NULL
                )
                """;

        String createRescueAssignmentsTable = """
                CREATE TABLE IF NOT EXISTS rescue_assignments (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    request_id INTEGER NOT NULL,
                    volunteer_username TEXT NOT NULL COLLATE NOCASE,
                    coordinator_username TEXT NOT NULL COLLATE NOCASE,
                    status TEXT NOT NULL DEFAULT 'DISPATCHED',
                    notes TEXT NOT NULL DEFAULT '',
                    assigned_at INTEGER NOT NULL,
                    completed_at INTEGER
                )
                """;

        String createAnnouncementsTable = """
                CREATE TABLE IF NOT EXISTS announcements (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    author_username TEXT NOT NULL COLLATE NOCASE,
                    title TEXT NOT NULL,
                    content TEXT NOT NULL,
                    severity TEXT NOT NULL DEFAULT 'INFO',
                    target_area TEXT NOT NULL DEFAULT 'ALL',
                    broadcast_at INTEGER NOT NULL
                )
                """;

        String createChatGroupsTable = """
                CREATE TABLE IF NOT EXISTS chat_groups (
                    group_id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    description TEXT DEFAULT '',
                    creator_username TEXT NOT NULL COLLATE NOCASE,
                    created_at INTEGER NOT NULL,
                    FOREIGN KEY (creator_username) REFERENCES users(username) ON UPDATE CASCADE ON DELETE CASCADE
                )
                """;

        String createGroupMembersTable = """
                CREATE TABLE IF NOT EXISTS group_members (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    group_id TEXT NOT NULL,
                    username TEXT NOT NULL COLLATE NOCASE,
                    role TEXT NOT NULL DEFAULT 'MEMBER',
                    joined_at INTEGER NOT NULL,
                    FOREIGN KEY (group_id) REFERENCES chat_groups(group_id) ON UPDATE CASCADE ON DELETE CASCADE,
                    FOREIGN KEY (username) REFERENCES users(username) ON UPDATE CASCADE ON DELETE CASCADE,
                    UNIQUE (group_id, username)
                )
                """;

        String createGroupMessagesTable = """
                CREATE TABLE IF NOT EXISTS group_messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    group_id TEXT NOT NULL,
                    sender TEXT NOT NULL COLLATE NOCASE,
                    content TEXT NOT NULL,
                    message_type TEXT NOT NULL DEFAULT 'TEXT',
                    media_url TEXT,
                    sent_at INTEGER NOT NULL,
                    request_id TEXT NOT NULL,
                    FOREIGN KEY (group_id) REFERENCES chat_groups(group_id) ON UPDATE CASCADE ON DELETE CASCADE,
                    FOREIGN KEY (sender) REFERENCES users(username) ON UPDATE CASCADE ON DELETE CASCADE
                )
                """;

        String createConversationReadStateTable = """
                CREATE TABLE IF NOT EXISTS conversation_read_state (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    username TEXT NOT NULL COLLATE NOCASE,
                    conversation_id TEXT NOT NULL COLLATE NOCASE,
                    is_group INTEGER NOT NULL DEFAULT 0,
                    last_read_message_id INTEGER NOT NULL DEFAULT 0,
                    last_read_at INTEGER NOT NULL,
                    FOREIGN KEY (username) REFERENCES users(username) ON UPDATE CASCADE ON DELETE CASCADE,
                    UNIQUE (username, conversation_id, is_group)
                )
                """;

        try (Connection connection = getConnection();
             Statement statement = connection.createStatement()) {

            statement.execute(createUsersTable);
            statement.execute(createMessagesTable);
            statement.execute(createFloodReportsTable);
            statement.execute(createRescueRequestsTable);
            statement.execute(createRescueAssignmentsTable);
            statement.execute(createAnnouncementsTable);
            statement.execute(createChatGroupsTable);
            statement.execute(createGroupMembersTable);
            statement.execute(createGroupMessagesTable);
            statement.execute(createConversationReadStateTable);

            // Safely migrate existing users table if columns are missing
            Set<String> existingColumns = new HashSet<>();
            try (ResultSet rs = statement.executeQuery("PRAGMA table_info(users)")) {
                while (rs.next()) {
                    existingColumns.add(rs.getString("name").toLowerCase(Locale.ROOT));
                }
            }

            if (!existingColumns.contains("role")) {
                statement.execute("ALTER TABLE users ADD COLUMN role TEXT NOT NULL DEFAULT 'RESIDENT'");
            }
            if (!existingColumns.contains("phone")) {
                statement.execute("ALTER TABLE users ADD COLUMN phone TEXT");
            }
            if (!existingColumns.contains("location")) {
                statement.execute("ALTER TABLE users ADD COLUMN location TEXT");
            }

            // Create indexes
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_messages_conversation
                    ON messages(sender, recipient, sent_at)
                    """);

            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_flood_reports_status_time
                    ON flood_reports(status, reported_at DESC)
                    """);

            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_rescue_requests_status_urgency
                    ON rescue_requests(status, urgency)
                    """);

            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_rescue_assignments_lookup
                    ON rescue_assignments(request_id, volunteer_username)
                    """);

            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_announcements_time
                    ON announcements(broadcast_at DESC)
                    """);

            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_chat_groups_creator
                    ON chat_groups(creator_username)
                    """);

            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_group_members_user
                    ON group_members(username)
                    """);

            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_group_members_group
                    ON group_members(group_id)
                    """);

            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_group_messages_group_time
                    ON group_messages(group_id, sent_at DESC, id DESC)
                    """);

            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_group_messages_request_id
                    ON group_messages(request_id)
                    """);

            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_read_state_user
                    ON conversation_read_state(username, conversation_id, is_group)
                    """);
        }

        System.out.println("Database initialized successfully.");
    }
}
