package com.securechat.concurrent;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maintains in-memory active chat-room and group memberships for fast delivery.
 */
public final class ChatRoomRegistry {

    private final ConcurrentHashMap<String, Set<String>> groupMembers = new ConcurrentHashMap<>();

    public void registerMember(String groupId, String username) {
        if (groupId != null && username != null) {
            groupMembers.computeIfAbsent(groupId, k -> ConcurrentHashMap.newKeySet()).add(username);
        }
    }

    public void unregisterMember(String groupId, String username) {
        if (groupId != null && username != null) {
            Set<String> members = groupMembers.get(groupId);
            if (members != null) {
                members.remove(username);
            }
        }
    }

    public Set<String> getMembers(String groupId) {
        if (groupId == null) {
            return Collections.emptySet();
        }
        Set<String> members = groupMembers.get(groupId);
        return members == null ? Collections.emptySet() : Collections.unmodifiableSet(members);
    }

    public void removeGroup(String groupId) {
        if (groupId != null) {
            groupMembers.remove(groupId);
        }
    }
}
