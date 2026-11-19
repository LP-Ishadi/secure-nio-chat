package com.securechat.model;

import java.util.List;

/**
 * Payload carried by GROUP_CREATE requests.
 *
 * @param name group name
 * @param description group description
 * @param initialMembers optional list of existing registered usernames to add immediately
 */
public record CreateGroupRequest(
        String name,
        String description,
        List<String> initialMembers
) {
    public CreateGroupRequest {
        if (description == null) {
            description = "";
        }
        if (initialMembers == null) {
            initialMembers = List.of();
        }
    }
}
