package com.securechat.database;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserRepositoryTest {

    @TempDir
    Path tempDir;

    private UserRepository userRepository;

    @BeforeEach
    void setUp() throws IOException, SQLException {
        Path testDb = tempDir.resolve("test-users.db");
        DatabaseManager.setDatabaseUrl("jdbc:sqlite:" + testDb.toAbsolutePath().toString().replace('\\', '/'));
        DatabaseManager.initializeDatabase();
        userRepository = new UserRepository();
    }

    @AfterEach
    void tearDown() {
        DatabaseManager.resetDatabaseUrl();
    }

    @Test
    void registersAndAuthenticatesUserSuccessfully() throws Exception {
        char[] password = "SecretPassword123".toCharArray();
        assertTrue(userRepository.registerUser("Alice", password));
        assertTrue(userRepository.userExists("Alice"));
        assertTrue(userRepository.userExists("alice"));

        assertTrue(userRepository.authenticateUser("Alice", password));
        assertTrue(userRepository.authenticateUser("alice", password));
        assertFalse(userRepository.authenticateUser("Alice", "WrongPassword123".toCharArray()));
        assertFalse(userRepository.authenticateUser("Bob", password));
    }

    @Test
    void rejectsDuplicateRegistrationCaseInsensitively() throws Exception {
        char[] password = "SecretPassword123".toCharArray();
        assertTrue(userRepository.registerUser("Charlie", password));
        assertFalse(userRepository.registerUser("charlie", password));
        assertFalse(userRepository.registerUser("CHARLIE", password));
    }

    @Test
    void rejectsInvalidUsernamesAndPasswordLengths() {
        assertThrows(IllegalArgumentException.class, () ->
                userRepository.registerUser("ab", "ValidPass123".toCharArray())
        );
        assertThrows(IllegalArgumentException.class, () ->
                userRepository.registerUser("invalid username!", "ValidPass123".toCharArray())
        );
        assertThrows(IllegalArgumentException.class, () ->
                userRepository.registerUser("ValidUser", "short".toCharArray())
        );
        assertThrows(IllegalArgumentException.class, () ->
                userRepository.registerUser("ValidUser", null)
        );
    }

    @Test
    void retrievesAllRegisteredUsernamesWithoutSensitiveData() throws Exception {
        char[] password = "Password123".toCharArray();
        userRepository.registerUser("Charlie", password);
        userRepository.registerUser("alice", password);
        userRepository.registerUser("Bob", password);

        List<String> allUsers = userRepository.getAllRegisteredUsernames();
        assertEquals(List.of("alice", "Bob", "Charlie"), allUsers);
    }
}
