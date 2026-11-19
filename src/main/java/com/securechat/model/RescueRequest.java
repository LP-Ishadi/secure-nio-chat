package com.securechat.model;

import java.util.Objects;

/**
 * Represents an emergency rescue (SOS) request in the FloodConnect system.
 *
 * @param id database primary key or generated id
 * @param residentUsername username of the resident requesting rescue
 * @param location location/address where rescue is needed
 * @param latitude optional GPS latitude coordinate
 * @param longitude optional GPS longitude coordinate
 * @param peopleCount number of people trapped or needing evacuation
 * @param vulnerableCount number of vulnerable individuals (elderly, infants, patients)
 * @param urgency urgency level (e.g., ROUTINE, URGENT, LIFE_THREATENING)
 * @param description medical or situational details
 * @param status request status (e.g., PENDING, ASSIGNED, IN_PROGRESS, RESCUED, CANCELLED)
 * @param createdAt epoch milliseconds when the request was initiated
 */
public record RescueRequest(
        long id,
        String residentUsername,
        String location,
        Double latitude,
        Double longitude,
        int peopleCount,
        int vulnerableCount,
        String urgency,
        String description,
        String status,
        long createdAt
) {
    public RescueRequest {
        Objects.requireNonNull(residentUsername, "residentUsername must not be null");
        Objects.requireNonNull(location, "location must not be null");
        if (peopleCount < 1) {
            peopleCount = 1;
        }
        if (vulnerableCount < 0) {
            vulnerableCount = 0;
        }
        if (urgency == null || urgency.isBlank()) {
            urgency = "ROUTINE";
        }
        if (status == null || status.isBlank()) {
            status = "PENDING";
        }
        if (description == null) {
            description = "";
        }
        if (createdAt < 0) {
            throw new IllegalArgumentException("createdAt must not be negative");
        }
    }
}
