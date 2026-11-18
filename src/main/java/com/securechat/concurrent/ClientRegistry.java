package com.securechat.concurrent;

import com.securechat.nio.ClientConnection;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Maintains the set of currently registered clients using unique usernames. */
public final class ClientRegistry {
    private static final Pattern VALID_USERNAME =
            Pattern.compile("[A-Za-z0-9_-]{3,20}");

    private final ConcurrentHashMap<String, ClientConnection> clientsByUsername =
            new ConcurrentHashMap<>();

    /** Possible outcomes of one atomic username registration attempt. */
    public enum RegistrationResult {
        SUCCESS,
        INVALID_USERNAME,
        DUPLICATE_USERNAME,
        ALREADY_REGISTERED
    }

    /**
     * Atomically registers the connection under a case-insensitive username.
     */
    public RegistrationResult register(
            String username,
            ClientConnection connection
    ) {
        Objects.requireNonNull(connection, "connection must not be null");

        if (!isValidUsername(username)) {
            return RegistrationResult.INVALID_USERNAME;
        }
        if (connection.isRegistered()) {
            return RegistrationResult.ALREADY_REGISTERED;
        }

        String key = normalize(username);
        ClientConnection existing = clientsByUsername.putIfAbsent(key, connection);
        if (existing != null) {
            return RegistrationResult.DUPLICATE_USERNAME;
        }

        if (!connection.registerUsername(username)) {
            clientsByUsername.remove(key, connection);
            return RegistrationResult.ALREADY_REGISTERED;
        }

        return RegistrationResult.SUCCESS;
    }

    /** Returns the registered connection, or {@code null} when it is offline. */
    public ClientConnection find(String username) {
        if (username == null) {
            return null;
        }
        return clientsByUsername.get(normalize(username));
    }

    /**
     * Atomically removes this exact connection and returns its former username.
     */
    public String remove(ClientConnection connection) {
        Objects.requireNonNull(connection, "connection must not be null");
        String username = connection.username();

        if (username == null) {
            return null;
        }

        return clientsByUsername.remove(normalize(username), connection)
                ? username
                : null;
    }

    /** Returns an alphabetical snapshot that cannot modify the registry. */
    public List<String> usernamesSnapshot() {
        return clientsByUsername.values().stream()
                .map(ClientConnection::username)
                .filter(Objects::nonNull)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    /** Returns a stable snapshot for broadcasting without exposing the map. */
    public List<ClientConnection> connectionsSnapshot() {
        return clientsByUsername.values().stream()
                .distinct()
                .toList();
    }

    public int size() {
        return clientsByUsername.size();
    }

    /** Clears server-owned registration state during shutdown. */
    public void clear() {
        clientsByUsername.clear();
    }

    public static boolean isValidUsername(String username) {
        return username != null && VALID_USERNAME.matcher(username).matches();
    }

    private static String normalize(String username) {
        return username.toLowerCase(Locale.ROOT);
    }
}
