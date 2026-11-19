package com.securechat.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DomainModelTest {

    @Test
    void userRoleResolutionAndDefaults() {
        assertEquals(UserRole.RESIDENT, UserRole.fromStringOrDefault("RESIDENT", UserRole.RESIDENT));
        assertEquals(UserRole.VOLUNTEER, UserRole.fromStringOrDefault("volunteer", UserRole.RESIDENT));
        assertEquals(UserRole.COORDINATOR, UserRole.fromStringOrDefault("Coordinator", UserRole.RESIDENT));
        assertEquals(UserRole.ADMIN, UserRole.fromStringOrDefault("admin", UserRole.RESIDENT));
        assertEquals(UserRole.RESIDENT, UserRole.fromStringOrDefault("unknown_role", UserRole.RESIDENT));
        assertEquals(UserRole.RESIDENT, UserRole.fromStringOrDefault(null, UserRole.RESIDENT));
    }

    @Test
    void floodReportCreationAndValidation() {
        FloodReport report = new FloodReport(
                1L,
                "Alice",
                "Flash flood on Main St",
                "Main Street Bridge",
                6.9271,
                79.8612,
                75,
                "CRITICAL",
                "Water entering ground floors",
                "REPORTED",
                1_700_000_000_000L
        );

        assertEquals(1L, report.id());
        assertEquals("Alice", report.reporterUsername());
        assertEquals("Flash flood on Main St", report.title());
        assertEquals(75, report.waterDepthCm());
        assertEquals("CRITICAL", report.severity());

        // Null checks
        assertThrows(NullPointerException.class, () -> new FloodReport(
                1L, null, "Title", "Location", null, null, 10, "MODERATE", "", "REPORTED", 1000L
        ));
        assertThrows(IllegalArgumentException.class, () -> new FloodReport(
                1L, "Alice", "Title", "Location", null, null, -5, "MODERATE", "", "REPORTED", 1000L
        ));
    }

    @Test
    void rescueRequestCreationAndValidation() {
        RescueRequest request = new RescueRequest(
                10L,
                "Bob",
                "12 River View Lane",
                6.9200,
                79.8500,
                4,
                2,
                "LIFE_THREATENING",
                "Elderly couple trapped on rooftop",
                "PENDING",
                1_700_000_000_000L
        );

        assertEquals(10L, request.id());
        assertEquals("Bob", request.residentUsername());
        assertEquals(4, request.peopleCount());
        assertEquals(2, request.vulnerableCount());
        assertEquals("LIFE_THREATENING", request.urgency());

        // Default corrections
        RescueRequest defaults = new RescueRequest(
                11L, "Charlie", "Shelter", null, null, 0, -1, null, null, null, 1000L
        );
        assertEquals(1, defaults.peopleCount());
        assertEquals(0, defaults.vulnerableCount());
        assertEquals("ROUTINE", defaults.urgency());
        assertEquals("PENDING", defaults.status());
    }

    @Test
    void rescueAssignmentCreationAndValidation() {
        RescueAssignment assignment = new RescueAssignment(
                100L,
                10L,
                "VolunteerTeamAlpha",
                "CoordJohn",
                "DISPATCHED",
                "Proceed via boat route B",
                1_700_000_000_100L,
                null
        );

        assertEquals(100L, assignment.id());
        assertEquals(10L, assignment.requestId());
        assertEquals("VolunteerTeamAlpha", assignment.volunteerUsername());
        assertEquals("CoordJohn", assignment.coordinatorUsername());
        assertEquals("DISPATCHED", assignment.status());

        assertThrows(NullPointerException.class, () -> new RescueAssignment(
                101L, 10L, null, "CoordJohn", "DISPATCHED", "", 1000L, null
        ));
    }

    @Test
    void announcementCreationAndValidation() {
        Announcement announcement = new Announcement(
                200L,
                "AdminChief",
                "Mandatory Evacuation Order",
                "All residents in Sector 4 must evacuate immediately to Community Hall.",
                "EVACUATION",
                "Sector 4",
                1_700_000_000_200L
        );

        assertEquals(200L, announcement.id());
        assertEquals("AdminChief", announcement.authorUsername());
        assertEquals("Mandatory Evacuation Order", announcement.title());
        assertEquals("EVACUATION", announcement.severity());
        assertEquals("Sector 4", announcement.targetArea());

        assertThrows(NullPointerException.class, () -> new Announcement(
                201L, null, "Title", "Content", "INFO", "ALL", 1000L
        ));
    }

    @Test
    void chatGroupCreationAndValidation() {
        ChatGroup group = new ChatGroup(
                "grp-101",
                "Rescue Operations",
                "Coordination for water rescues",
                "AdminAlice",
                1_700_000_000_000L
        );

        assertEquals("grp-101", group.groupId());
        assertEquals("Rescue Operations", group.name());
        assertEquals("Coordination for water rescues", group.description());
        assertEquals("AdminAlice", group.creatorUsername());
        assertEquals(1_700_000_000_000L, group.createdAt());

        // Default empty description if null passed
        ChatGroup noDesc = new ChatGroup("grp-102", "Team B", null, "Bob", 1000L);
        assertEquals("", noDesc.description());

        // Validation errors
        assertThrows(NullPointerException.class, () -> new ChatGroup(null, "Name", "", "Bob", 1000L));
        assertThrows(NullPointerException.class, () -> new ChatGroup("id", null, "", "Bob", 1000L));
        assertThrows(NullPointerException.class, () -> new ChatGroup("id", "Name", "", null, 1000L));
        assertThrows(IllegalArgumentException.class, () -> new ChatGroup("", "Name", "", "Bob", 1000L));
        assertThrows(IllegalArgumentException.class, () -> new ChatGroup("id", "", "", "Bob", 1000L));
        assertThrows(IllegalArgumentException.class, () -> new ChatGroup("id", "Name", "", "", 1000L));
        assertThrows(IllegalArgumentException.class, () -> new ChatGroup("id", "Name", "", "Bob", -1L));
    }

    @Test
    void groupMemberRoleAndMemberModel() {
        assertEquals(GroupMemberRole.ADMIN, GroupMemberRole.fromStringOrDefault("admin", GroupMemberRole.MEMBER));
        assertEquals(GroupMemberRole.ADMIN, GroupMemberRole.fromStringOrDefault("ADMIN", GroupMemberRole.MEMBER));
        assertEquals(GroupMemberRole.MEMBER, GroupMemberRole.fromStringOrDefault("member", GroupMemberRole.ADMIN));
        assertEquals(GroupMemberRole.MEMBER, GroupMemberRole.fromStringOrDefault("unknown", GroupMemberRole.MEMBER));
        assertEquals(GroupMemberRole.MEMBER, GroupMemberRole.fromStringOrDefault(null, GroupMemberRole.MEMBER));

        GroupMember member = new GroupMember(
                "grp-101",
                "VolunteerBob",
                GroupMemberRole.ADMIN,
                1_700_000_000_100L
        );

        assertEquals("grp-101", member.groupId());
        assertEquals("VolunteerBob", member.username());
        assertEquals(GroupMemberRole.ADMIN, member.role());
        assertEquals(1_700_000_000_100L, member.joinedAt());

        // Defaults to MEMBER if null role
        GroupMember defaultRole = new GroupMember("grp-101", "Charlie", null, 1000L);
        assertEquals(GroupMemberRole.MEMBER, defaultRole.role());

        assertThrows(NullPointerException.class, () -> new GroupMember(null, "Charlie", GroupMemberRole.MEMBER, 1000L));
        assertThrows(NullPointerException.class, () -> new GroupMember("grp-101", null, GroupMemberRole.MEMBER, 1000L));
        assertThrows(IllegalArgumentException.class, () -> new GroupMember("", "Charlie", GroupMemberRole.MEMBER, 1000L));
        assertThrows(IllegalArgumentException.class, () -> new GroupMember("grp-101", "", GroupMemberRole.MEMBER, 1000L));
        assertThrows(IllegalArgumentException.class, () -> new GroupMember("grp-101", "Charlie", GroupMemberRole.MEMBER, -1L));
    }
}
