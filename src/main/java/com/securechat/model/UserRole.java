package com.securechat.model;

import java.util.Locale;

/**
 * Roles for users participating in the FloodConnect emergency coordination system.
 */
public enum UserRole {
    RESIDENT,
    VOLUNTEER,
    COORDINATOR,
    ADMIN;

    public static UserRole fromStringOrDefault(String roleStr, UserRole defaultRole) {
        if (roleStr == null || roleStr.isBlank()) {
            return defaultRole;
        }
        try {
            return UserRole.valueOf(roleStr.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return defaultRole;
        }
    }
}
