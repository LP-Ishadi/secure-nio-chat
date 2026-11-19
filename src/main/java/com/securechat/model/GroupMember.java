package com.securechat.model;

import java.util.Objects;

/**
 * Represents a member of a chat group and their role within it.
 *
 * @param groupId group identifier
 * @param username username of the member
 * @param role administrative or standard member role
 * @param joinedAt epoch milliseconds when the user joined the group
 */
public record GroupMember(
        String groupId,
        String username,
        GroupMemberRole role,
        long joinedAt
) {
    public GroupMember {
        Objects.requireNonNull(groupId, "groupId must not be null");
        Objects.requireNonNull(username, "username must not be null");
        if (groupId.isBlank()) {
            throw new IllegalArgumentException("groupId must not be blank");
        }
        if (username.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        if (role == null) {
            role = GroupMemberRole.MEMBER;
        }
        if (joinedAt < 0) {
            throw new IllegalArgumentException("joinedAt must not be negative");
        }
    }
}
