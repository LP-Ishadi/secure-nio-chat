package com.securechat.database;

import com.securechat.model.ChatGroup;
import com.securechat.model.GroupMember;
import com.securechat.model.GroupMemberRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroupRepositoryTest {

    @TempDir
    Path tempDir;

    private UserRepository userRepository;
    private GroupRepository groupRepository;

    @BeforeEach
    void setUp() throws IOException, SQLException {
        Path testDb = tempDir.resolve("test-groups.db");
        DatabaseManager.setDatabaseUrl("jdbc:sqlite:" + testDb.toAbsolutePath().toString().replace('\\', '/'));
        DatabaseManager.initializeDatabase();

        userRepository = new UserRepository();
        groupRepository = new GroupRepository(userRepository);

        // Pre-register test users
        char[] pass = "Password123".toCharArray();
        userRepository.registerUser("Alice", pass);
        userRepository.registerUser("Bob", pass);
        userRepository.registerUser("Charlie", pass);
        userRepository.registerUser("David", pass);
    }

    @AfterEach
    void tearDown() {
        DatabaseManager.resetDatabaseUrl();
    }

    @Test
    void createGroupAssignsCreatorAsAdminAndSetsTimestamps() throws Exception {
        ChatGroup group = groupRepository.createGroup(
                "Rescue Unit",
                "Flood response operations",
                "Alice",
                null
        );

        assertNotNull(group.groupId());
        assertTrue(group.groupId().startsWith("grp-"));
        assertEquals("Rescue Unit", group.name());
        assertEquals("Flood response operations", group.description());
        assertEquals("Alice", group.creatorUsername());
        assertTrue(group.createdAt() > 0);

        // Verify in DB
        Optional<ChatGroup> fetched = groupRepository.getGroup(group.groupId());
        assertTrue(fetched.isPresent());
        assertEquals(group.groupId(), fetched.get().groupId());

        // Verify creator is an ADMIN
        assertTrue(groupRepository.isMember(group.groupId(), "Alice"));
        assertTrue(groupRepository.isAdmin(group.groupId(), "Alice"));

        List<GroupMember> members = groupRepository.getGroupMembers(group.groupId());
        assertEquals(1, members.size());
        assertEquals("Alice", members.getFirst().username());
        assertEquals(GroupMemberRole.ADMIN, members.getFirst().role());
    }

    @Test
    void createGroupWithInitialMembersAddsExistingRegisteredUsers() throws Exception {
        ChatGroup group = groupRepository.createGroup(
                "Evacuation Team",
                "Evacuating flooded zones",
                "Alice",
                List.of("Bob", "Charlie", "NonExistentUser")
        );

        List<GroupMember> members = groupRepository.getGroupMembers(group.groupId());
        // Alice (admin), Bob (member), Charlie (member); NonExistentUser skipped
        assertEquals(3, members.size());

        assertTrue(groupRepository.isMember(group.groupId(), "Bob"));
        assertFalse(groupRepository.isAdmin(group.groupId(), "Bob"));

        assertTrue(groupRepository.isMember(group.groupId(), "Charlie"));
        assertFalse(groupRepository.isAdmin(group.groupId(), "Charlie"));

        assertFalse(groupRepository.isMember(group.groupId(), "NonExistentUser"));
    }

    @Test
    void getGroupsForUserReturnsAllGroupsJoined() throws Exception {
        ChatGroup g1 = groupRepository.createGroup("Group Alpha", "Desc A", "Alice", List.of("Bob"));
        ChatGroup g2 = groupRepository.createGroup("Group Beta", "Desc B", "Bob", List.of("Alice"));
        ChatGroup g3 = groupRepository.createGroup("Group Gamma", "Desc C", "Charlie", null);

        List<ChatGroup> aliceGroups = groupRepository.getGroupsForUser("Alice");
        assertEquals(2, aliceGroups.size());
        assertTrue(aliceGroups.stream().anyMatch(g -> g.groupId().equals(g1.groupId())));
        assertTrue(aliceGroups.stream().anyMatch(g -> g.groupId().equals(g2.groupId())));

        List<ChatGroup> bobGroups = groupRepository.getGroupsForUser("Bob");
        assertEquals(2, bobGroups.size());

        List<ChatGroup> charlieGroups = groupRepository.getGroupsForUser("Charlie");
        assertEquals(1, charlieGroups.size());
        assertEquals(g3.groupId(), charlieGroups.getFirst().groupId());
    }

    @Test
    void addMemberAndPreventDuplicateMemberships() throws Exception {
        ChatGroup group = groupRepository.createGroup("Volunteers", "General", "Alice", null);

        // Add registered user
        assertTrue(groupRepository.addMember(group.groupId(), "Bob", GroupMemberRole.MEMBER));
        assertTrue(groupRepository.isMember(group.groupId(), "Bob"));

        // Duplicate membership prevented
        assertThrows(IllegalArgumentException.class, () ->
                groupRepository.addMember(group.groupId(), "Bob", GroupMemberRole.MEMBER)
        );

        // Non-existent user addition rejected
        assertThrows(IllegalArgumentException.class, () ->
                groupRepository.addMember(group.groupId(), "UnknownGhost", GroupMemberRole.MEMBER)
        );
    }

    @Test
    void removeMemberAndLeaveGroupOperations() throws Exception {
        ChatGroup group = groupRepository.createGroup("Dispatch", "", "Alice", List.of("Bob", "Charlie"));

        // Admin removes Charlie
        assertTrue(groupRepository.removeMember(group.groupId(), "Charlie"));
        assertFalse(groupRepository.isMember(group.groupId(), "Charlie"));

        // Bob voluntarily leaves group
        assertTrue(groupRepository.leaveGroup(group.groupId(), "Bob"));
        assertFalse(groupRepository.isMember(group.groupId(), "Bob"));

        // Alice remains
        assertEquals(1, groupRepository.getGroupMembers(group.groupId()).size());
    }

    @Test
    void groupMessagingPersistentStorageAndRetrieval() throws Exception {
        ChatGroup group = groupRepository.createGroup("Alerts", "Emergency notifications", "Alice", List.of("Bob"));

        long t1 = 1_700_000_000_000L;
        long t2 = 1_700_000_001_000L;

        StoredGroupMessage msg1 = groupRepository.saveGroupMessage(
                group.groupId(), "Alice", "Water level rising at Bridge", t1, "req-1"
        );
        StoredGroupMessage msg2 = groupRepository.saveGroupMessage(
                group.groupId(), "Bob", "Boat team dispatched", t2, "req-2"
        );

        assertNotNull(msg1);
        assertEquals(group.groupId(), msg1.groupId());
        assertEquals("Alice", msg1.sender());
        assertEquals("Water level rising at Bridge", msg1.content());
        assertEquals("TEXT", msg1.messageType());

        List<StoredGroupMessage> history = groupRepository.getGroupMessages(group.groupId(), 50, 0);
        assertEquals(2, history.size());
        // Ordered chronologically
        assertEquals("Alice", history.get(0).sender());
        assertEquals("Water level rising at Bridge", history.get(0).content());
        assertEquals("Bob", history.get(1).sender());
        assertEquals("Boat team dispatched", history.get(1).content());
    }

    @Test
    void paginationForMessageHistory() throws Exception {
        ChatGroup group = groupRepository.createGroup("HighVolume", "", "Alice", null);

        for (int i = 1; i <= 25; i++) {
            groupRepository.saveGroupMessage(
                    group.groupId(), "Alice", "Message " + i, 1000L * i, "req-" + i
            );
        }

        // Page 1: limit 10, offset 0 (most recent 10 messages: 16 to 25)
        List<StoredGroupMessage> page1 = groupRepository.getGroupMessages(group.groupId(), 10, 0);
        assertEquals(10, page1.size());
        assertEquals("Message 16", page1.get(0).content());
        assertEquals("Message 25", page1.get(9).content());

        // Page 2: limit 10, offset 10 (earlier 10 messages: 6 to 15)
        List<StoredGroupMessage> page2 = groupRepository.getGroupMessages(group.groupId(), 10, 10);
        assertEquals(10, page2.size());
        assertEquals("Message 6", page2.get(0).content());
        assertEquals("Message 15", page2.get(9).content());

        // Page 3: limit 10, offset 20 (oldest 5 messages: 1 to 5)
        List<StoredGroupMessage> page3 = groupRepository.getGroupMessages(group.groupId(), 10, 20);
        assertEquals(5, page3.size());
        assertEquals("Message 1", page3.get(0).content());
        assertEquals("Message 5", page3.get(4).content());

        // Cursor-based pagination: messages before timestamp of Message 6
        List<StoredGroupMessage> beforeCursor = groupRepository.getGroupMessagesBefore(
                group.groupId(), 10, 1000L * 6
        );
        assertEquals(5, beforeCursor.size());
        assertEquals("Message 1", beforeCursor.get(0).content());
        assertEquals("Message 5", beforeCursor.get(4).content());
    }

    @Test
    void supportsVoiceMessageTypeForFutureAudioIntegration() throws Exception {
        ChatGroup group = groupRepository.createGroup("VoiceEnabled", "", "Alice", null);

        StoredGroupMessage voiceMsg = groupRepository.saveGroupMessage(
                group.groupId(),
                "Alice",
                "Voice transmission",
                "VOICE",
                "/audio/recordings/audio-101.ogg",
                1_700_000_000_500L,
                "voice-req-1"
        );

        assertEquals("VOICE", voiceMsg.messageType());
        assertEquals("/audio/recordings/audio-101.ogg", voiceMsg.mediaUrl());

        List<StoredGroupMessage> messages = groupRepository.getGroupMessages(group.groupId(), 10, 0);
        assertEquals(1, messages.size());
        assertEquals("VOICE", messages.getFirst().messageType());
        assertEquals("/audio/recordings/audio-101.ogg", messages.getFirst().mediaUrl());
    }
}
