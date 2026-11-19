package com.securechat.model;

import java.util.Objects;

/**
 * Represents the assignment of a rescue request to a volunteer or rescue team.
 *
 * @param id database primary key or generated id
 * @param requestId identifier of the associated rescue request
 * @param volunteerUsername username of the volunteer/team assigned to the mission
 * @param coordinatorUsername username of the coordinator who dispatched the assignment
 * @param status assignment status (e.g., DISPATCHED, ON_SCENE, COMPLETED, ABORTED)
 * @param notes instructions or coordination notes
 * @param assignedAt epoch milliseconds when assigned
 * @param completedAt optional epoch milliseconds when the assignment completed or null
 */
public record RescueAssignment(
        long id,
        long requestId,
        String volunteerUsername,
        String coordinatorUsername,
        String status,
        String notes,
        long assignedAt,
        Long completedAt
) {
    public RescueAssignment {
        Objects.requireNonNull(volunteerUsername, "volunteerUsername must not be null");
        Objects.requireNonNull(coordinatorUsername, "coordinatorUsername must not be null");
        if (status == null || status.isBlank()) {
            status = "DISPATCHED";
        }
        if (notes == null) {
            notes = "";
        }
        if (assignedAt < 0) {
            throw new IllegalArgumentException("assignedAt must not be negative");
        }
    }
}
