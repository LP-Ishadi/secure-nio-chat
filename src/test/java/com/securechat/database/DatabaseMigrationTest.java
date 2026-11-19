package com.securechat.database;

import com.securechat.model.UserRole;
import com.securechat.protocol.ChatMessage;
import com.securechat.protocol.MessageType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseMigrationTest {

    @TempDir
    Path tempDir;

    private String dbUrl;

    @BeforeEach
    void setUp() {
        Path testDb = tempDir.resolve("test-migration.db");
        dbUrl = "jdbc:sqlite:" + testDb.toAbsolutePath().toString().replace('\\', '/');
        DatabaseManager.setDatabaseUrl(dbUrl);
    }

    @AfterEach
    void tearDown() {
        DatabaseManager.resetDatabaseUrl();
    }

    @Test
    void freshDatabaseInitializesAllTablesAndColumns() throws Exception {
        DatabaseManager.initializeDatabase();

        try (Connection connection = DatabaseManager.getConnection();
             Statement statement = connection.createStatement()) {

            // Check users columns
            Set<String> userCols = getTableColumns(statement, "users");
            assertTrue(userCols.contains("user_id"));
            assertTrue(userCols.contains("username"));
            assertTrue(userCols.contains("password_hash"));
            assertTrue(userCols.contains("role"));
            assertTrue(userCols.contains("phone"));
            assertTrue(userCols.contains("location"));
            assertTrue(userCols.contains("created_at"));

            // Check messages columns
            Set<String> msgCols = getTableColumns(statement, "messages");
            assertTrue(msgCols.contains("id"));
            assertTrue(msgCols.contains("message_type"));
            assertTrue(msgCols.contains("sender"));
            assertTrue(msgCols.contains("recipient"));
            assertTrue(msgCols.contains("content"));
            assertTrue(msgCols.contains("sent_at"));
            assertTrue(msgCols.contains("request_id"));

            // Check flood_reports columns
            Set<String> floodCols = getTableColumns(statement, "flood_reports");
            assertTrue(floodCols.contains("id"));
            assertTrue(floodCols.contains("reporter_username"));
            assertTrue(floodCols.contains("water_depth_cm"));
            assertTrue(floodCols.contains("severity"));

            // Check rescue_requests columns
            Set<String> rescueCols = getTableColumns(statement, "rescue_requests");
            assertTrue(rescueCols.contains("id"));
            assertTrue(rescueCols.contains("resident_username"));
            assertTrue(rescueCols.contains("people_count"));
            assertTrue(rescueCols.contains("urgency"));

            // Check rescue_assignments columns
            Set<String> assignCols = getTableColumns(statement, "rescue_assignments");
            assertTrue(assignCols.contains("id"));
            assertTrue(assignCols.contains("request_id"));
            assertTrue(assignCols.contains("volunteer_username"));

            // Check announcements columns
            Set<String> announceCols = getTableColumns(statement, "announcements");
            assertTrue(announceCols.contains("id"));
            assertTrue(announceCols.contains("author_username"));
            assertTrue(announceCols.contains("severity"));

            // Check chat_groups columns
            Set<String> chatGroupCols = getTableColumns(statement, "chat_groups");
            assertTrue(chatGroupCols.contains("group_id"));
            assertTrue(chatGroupCols.contains("name"));
            assertTrue(chatGroupCols.contains("description"));
            assertTrue(chatGroupCols.contains("creator_username"));
            assertTrue(chatGroupCols.contains("created_at"));

            // Check group_members columns
            Set<String> groupMemberCols = getTableColumns(statement, "group_members");
            assertTrue(groupMemberCols.contains("id"));
            assertTrue(groupMemberCols.contains("group_id"));
            assertTrue(groupMemberCols.contains("username"));
            assertTrue(groupMemberCols.contains("role"));
            assertTrue(groupMemberCols.contains("joined_at"));

            // Check group_messages columns
            Set<String> groupMsgCols = getTableColumns(statement, "group_messages");
            assertTrue(groupMsgCols.contains("id"));
            assertTrue(groupMsgCols.contains("group_id"));
            assertTrue(groupMsgCols.contains("sender"));
            assertTrue(groupMsgCols.contains("content"));
            assertTrue(groupMsgCols.contains("message_type"));
            assertTrue(groupMsgCols.contains("media_url"));
            assertTrue(groupMsgCols.contains("sent_at"));
            assertTrue(groupMsgCols.contains("request_id"));

            // Check conversation_read_state columns
            Set<String> readStateCols = getTableColumns(statement, "conversation_read_state");
            assertTrue(readStateCols.contains("id"));
            assertTrue(readStateCols.contains("username"));
            assertTrue(readStateCols.contains("conversation_id"));
            assertTrue(readStateCols.contains("is_group"));
            assertTrue(readStateCols.contains("last_read_message_id"));
            assertTrue(readStateCols.contains("last_read_at"));
        }
    }

    @Test
    void runningInitializationTwiceDoesNotCauseDuplicateColumnErrors() throws Exception {
        DatabaseManager.initializeDatabase();
        // Second call should run cleanly without throwing SQL errors
        DatabaseManager.initializeDatabase();

        UserRepository repo = new UserRepository();
        char[] pass = "ValidPassword123".toCharArray();
        assertTrue(repo.registerUser("AliceInit", pass));
        assertTrue(repo.authenticateUser("AliceInit", pass));
        assertEquals(UserRole.RESIDENT, repo.getUserRole("AliceInit"));
    }

    @Test
    void legacyDatabaseWithUsersAndMessagesMigratesSafely() throws Exception {
        // 1. Manually create an older schema with ONLY legacy columns
        try (Connection directConn = DriverManager.getConnection(dbUrl);
             Statement stmt = directConn.createStatement()) {

            stmt.execute("""
                    CREATE TABLE users (
                        user_id INTEGER PRIMARY KEY AUTOINCREMENT,
                        username TEXT NOT NULL UNIQUE COLLATE NOCASE,
                        password_hash TEXT NOT NULL,
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);

            stmt.execute("""
                    CREATE TABLE messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        message_type TEXT NOT NULL,
                        sender TEXT NOT NULL COLLATE NOCASE,
                        recipient TEXT COLLATE NOCASE,
                        content TEXT NOT NULL,
                        sent_at INTEGER NOT NULL,
                        request_id TEXT NOT NULL
                    )
                    """);

            // Insert a legacy user with PasswordHasher
            String hashed = PasswordHasher.hashPassword("LegacyPassword123".toCharArray());
            stmt.execute("INSERT INTO users (username, password_hash) VALUES ('LegacyAlice', '" + hashed + "')");

            // Insert a legacy message
            stmt.execute("INSERT INTO messages (message_type, sender, recipient, content, sent_at, request_id) " +
                    "VALUES ('PUBLIC_MESSAGE', 'LegacyAlice', NULL, 'Pre-migration message', 1000, 'req-0')");
        }

        // 2. Run DatabaseManager.initializeDatabase() to perform the migration
        DatabaseManager.initializeDatabase();

        // 3. Verify the legacy user is preserved, authenticated, and has default RESIDENT role
        UserRepository userRepo = new UserRepository();
        assertTrue(userRepo.userExists("LegacyAlice"));
        assertTrue(userRepo.authenticateUser("LegacyAlice", "LegacyPassword123".toCharArray()));
        assertEquals(UserRole.RESIDENT, userRepo.getUserRole("LegacyAlice"));

        // 4. Verify the legacy message is preserved
        MessageRepository msgRepo = new MessageRepository();
        List<StoredMessage> publicMsgs = msgRepo.getPublicMessages(10);
        assertEquals(1, publicMsgs.size());
        assertEquals("Pre-migration message", publicMsgs.getFirst().content());
        assertEquals("LegacyAlice", publicMsgs.getFirst().sender());
    }

    @Test
    void twoArgumentRegistrationAssignsResidentRoleByDefault() throws Exception {
        DatabaseManager.initializeDatabase();
        UserRepository repo = new UserRepository();

        assertTrue(repo.registerUser("ResidentBob", "Password123".toCharArray()));
        assertEquals(UserRole.RESIDENT, repo.getUserRole("ResidentBob"));
    }

    @Test
    void publicRegistrationRejectsPrivilegedRoles() throws Exception {
        DatabaseManager.initializeDatabase();
        UserRepository repo = new UserRepository();
        char[] pass = "Password123".toCharArray();

        // Admin and Coordinator are rejected on public registration
        assertThrows(IllegalArgumentException.class, () ->
                repo.registerUser("WannabeAdmin", pass, UserRole.ADMIN, "123", "City")
        );
        assertThrows(IllegalArgumentException.class, () ->
                repo.registerUser("WannabeCoord", pass, UserRole.COORDINATOR, "123", "City")
        );

        // Volunteer is allowed on public registration
        assertTrue(repo.registerUser("VolunteerDan", pass, UserRole.VOLUNTEER, "555-0199", "North District"));
        assertEquals(UserRole.VOLUNTEER, repo.getUserRole("VolunteerDan"));
    }

    @Test
    void updateUserRoleAllowsRoleChange() throws Exception {
        DatabaseManager.initializeDatabase();
        UserRepository repo = new UserRepository();

        assertTrue(repo.registerUser("PromotedUser", "Password123".toCharArray()));
        assertEquals(UserRole.RESIDENT, repo.getUserRole("PromotedUser"));

        // Promote to COORDINATOR
        assertTrue(repo.updateUserRole("PromotedUser", UserRole.COORDINATOR));
        assertEquals(UserRole.COORDINATOR, repo.getUserRole("PromotedUser"));

        // Promote to ADMIN
        assertTrue(repo.updateUserRole("PromotedUser", UserRole.ADMIN));
        assertEquals(UserRole.ADMIN, repo.getUserRole("PromotedUser"));
    }

    private static Set<String> getTableColumns(Statement stmt, String tableName) throws Exception {
        Set<String> columns = new HashSet<>();
        try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (rs.next()) {
                columns.add(rs.getString("name").toLowerCase(Locale.ROOT));
            }
        }
        return columns;
    }
}
