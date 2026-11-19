package com.securechat.model;

/**
 * Distinguishes administrative members from standard group members.
 */
public enum GroupMemberRole {
    ADMIN,
    MEMBER;

    public static GroupMemberRole fromStringOrDefault(String value, GroupMemberRole defaultRole) {
        if (value == null || value.isBlank()) {
            return defaultRole;
        }
        try {
            return GroupMemberRole.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return defaultRole;
        }
    }
}
