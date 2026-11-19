package com.securechat.model;

import java.util.Objects;

/**
 * Represents an official emergency announcement broadcast in the FloodConnect system.
 *
 * @param id database primary key or generated id
 * @param authorUsername username of the coordinator or admin broadcasting the alert
 * @param title headline of the announcement
 * @param content detailed bulletin or advisory
 * @param severity alert severity (e.g., INFO, ADVISORY, WARNING, EVACUATION)
 * @param targetArea designated district or 'ALL'
 * @param broadcastAt epoch milliseconds when broadcast
 */
public record Announcement(
        long id,
        String authorUsername,
        String title,
        String content,
        String severity,
        String targetArea,
        long broadcastAt
) {
    public Announcement {
        Objects.requireNonNull(authorUsername, "authorUsername must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(content, "content must not be null");
        if (severity == null || severity.isBlank()) {
            severity = "INFO";
        }
        if (targetArea == null || targetArea.isBlank()) {
            targetArea = "ALL";
        }
        if (broadcastAt < 0) {
            throw new IllegalArgumentException("broadcastAt must not be negative");
        }
    }
}
