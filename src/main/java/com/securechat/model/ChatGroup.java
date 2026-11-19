package com.securechat.model;

import java.util.Objects;

/**
 * Represents a chat group in the system.
 *
 * @param groupId unique identifier for the group
 * @param name display name of the group
 * @param description brief description of the group's purpose
 * @param creatorUsername username of the group creator (and initial administrator)
 * @param createdAt epoch milliseconds when the group was created
 */
public record ChatGroup(
        String groupId,
        String name,
        String description,
        String creatorUsername,
        long createdAt
) {
    public ChatGroup {
        Objects.requireNonNull(groupId, "groupId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(creatorUsername, "creatorUsername must not be null");

        if (groupId.isBlank()) {
            throw new IllegalArgumentException("groupId must not be blank");
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (creatorUsername.isBlank()) {
            throw new IllegalArgumentException("creatorUsername must not be blank");
        }
        if (description == null) {
            description = "";
        }
        if (createdAt < 0) {
            throw new IllegalArgumentException("createdAt must not be negative");
        }
    }
}
