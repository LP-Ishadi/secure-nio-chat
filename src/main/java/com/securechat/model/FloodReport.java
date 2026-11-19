package com.securechat.model;

import java.util.Objects;

/**
 * Represents a reported flood incident in the FloodConnect system.
 *
 * @param id database primary key or generated id
 * @param reporterUsername username of the resident or volunteer who reported it
 * @param title brief summary of the incident
 * @param location textual address or landmark
 * @param latitude optional GPS latitude coordinate
 * @param longitude optional GPS longitude coordinate
 * @param waterDepthCm estimated water depth in centimeters
 * @param severity severity level (e.g., LOW, MODERATE, CRITICAL, SEVERE)
 * @param description detailed description of the flood situation
 * @param status report status (e.g., REPORTED, VERIFIED, RESOLVED)
 * @param reportedAt epoch milliseconds when reported
 */
public record FloodReport(
        long id,
        String reporterUsername,
        String title,
        String location,
        Double latitude,
        Double longitude,
        int waterDepthCm,
        String severity,
        String description,
        String status,
        long reportedAt
) {
    public FloodReport {
        Objects.requireNonNull(reporterUsername, "reporterUsername must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(location, "location must not be null");
        if (reportedAt < 0) {
            throw new IllegalArgumentException("reportedAt must not be negative");
        }
        if (waterDepthCm < 0) {
            throw new IllegalArgumentException("waterDepthCm must not be negative");
        }
        if (severity == null || severity.isBlank()) {
            severity = "MODERATE";
        }
        if (status == null || status.isBlank()) {
            status = "REPORTED";
        }
        if (description == null) {
            description = "";
        }
    }
}
