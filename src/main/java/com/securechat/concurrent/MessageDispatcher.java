package com.securechat.concurrent;

import com.securechat.nio.ClientConnection;
import com.securechat.protocol.ChatMessage;
import com.securechat.protocol.MessageType;
import com.securechat.security.HeartbeatCredentials;
import com.securechat.security.SessionTokenService;
import com.securechat.udp.HeartbeatCodec;

import java.util.Objects;

/** Routes authenticated messages to application-level operations. */
public final class MessageDispatcher {
    /** Application limit measured in Unicode code points, not UTF-16 units. */
    public static final int MAX_MESSAGE_CHARACTERS = 2_000;

    /** Boundary between application decisions and selector-owned networking. */
    public interface Delivery {
        void send(ClientConnection recipient, ChatMessage message);

        void broadcastExcept(ChatMessage message, ClientConnection excluded);

        void disconnect(ClientConnection connection);

        void sessionEstablished(
                ClientConnection connection,
                HeartbeatCredentials credentials
        );
    }

    private final ClientRegistry clientRegistry;
    private final Delivery delivery;
    private final SessionTokenService tokenService;
    private final HeartbeatCodec heartbeatCodec = new HeartbeatCodec();
    private final int heartbeatPort;
    private final long heartbeatIntervalMillis;

    public MessageDispatcher(
            ClientRegistry clientRegistry,
            Delivery delivery,
            SessionTokenService tokenService,
            int heartbeatPort,
            long heartbeatIntervalMillis
    ) {
        this.clientRegistry = Objects.requireNonNull(
                clientRegistry,
                "clientRegistry must not be null"
        );
        this.delivery = Objects.requireNonNull(delivery, "delivery must not be null");
        this.tokenService = Objects.requireNonNull(tokenService);
        this.heartbeatPort = heartbeatPort;
        this.heartbeatIntervalMillis = heartbeatIntervalMillis;
    }

    /** Processes one decoded message without performing socket I/O directly. */
    public void dispatch(ClientConnection connection, ChatMessage message) {
        Objects.requireNonNull(connection, "connection must not be null");
        Objects.requireNonNull(message, "message must not be null");

        if (!connection.isRegistered()) {
            if (message.type() == MessageType.LOGIN) {
                handleLogin(connection, message);
            } else {
                delivery.send(connection, serverResponse(
                        MessageType.ERROR,
                        null,
                        "Login is required before other operations",
                        message.requestId()
                ));
            }
            return;
        }

        switch (message.type()) {
            case LOGIN -> delivery.send(connection, serverResponse(
                    MessageType.LOGIN_FAILURE,
                    connection.username(),
                    "This connection is already logged in",
                    message.requestId()
            ));
            case USER_LIST_REQUEST -> delivery.send(connection, serverResponse(
                    MessageType.USER_LIST_RESPONSE,
                    connection.username(),
                    String.join(",", clientRegistry.usernamesSnapshot()),
                    message.requestId()
            ));
            case PUBLIC_MESSAGE -> handlePublicMessage(connection, message);
            case PRIVATE_MESSAGE -> handlePrivateMessage(connection, message);
            case DISCONNECT -> delivery.disconnect(connection);
            default -> delivery.send(connection, serverResponse(
                    MessageType.ERROR,
                    connection.username(),
                    "Message type is not implemented in the concurrency phase",
                    message.requestId()
            ));
        }
    }

    private void handlePublicMessage(
            ClientConnection sender,
            ChatMessage request
    ) {
        String validationError = validatePayload(request.payload());
        if (validationError != null) {
            sendError(sender, validationError, request.requestId());
            return;
        }

        ChatMessage routed = routedMessage(
                MessageType.PUBLIC_MESSAGE,
                sender.username(),
                null,
                request.payload(),
                request.requestId()
        );
        delivery.broadcastExcept(routed, null);
    }

    private void handlePrivateMessage(
            ClientConnection sender,
            ChatMessage request
    ) {
        String validationError = validatePayload(request.payload());
        if (validationError != null) {
            sendError(sender, validationError, request.requestId());
            return;
        }
        if (request.target() == null || request.target().isBlank()) {
            sendError(
                    sender,
                    "Private message target must not be blank",
                    request.requestId()
            );
            return;
        }

        ClientConnection recipient = clientRegistry.find(request.target());
        if (recipient == null || !recipient.channel().isOpen()) {
            sendError(
                    sender,
                    "User is not online: " + request.target(),
                    request.requestId()
            );
            return;
        }

        ChatMessage routed = routedMessage(
                MessageType.PRIVATE_MESSAGE,
                sender.username(),
                recipient.username(),
                request.payload(),
                request.requestId()
        );
        delivery.send(recipient, routed);

        // Echo the authoritative form to the sender for UI confirmation.
        if (recipient != sender) {
            delivery.send(sender, routed);
        }
    }

    private void sendError(
            ClientConnection recipient,
            String reason,
            String requestId
    ) {
        delivery.send(recipient, serverResponse(
                MessageType.ERROR,
                recipient.username(),
                reason,
                requestId
        ));
    }

    private static String validatePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return "Message payload must not be blank";
        }

        int characterCount = payload.codePointCount(0, payload.length());
        if (characterCount > MAX_MESSAGE_CHARACTERS) {
            return "Message payload exceeds " + MAX_MESSAGE_CHARACTERS
                    + " characters";
        }

        return null;
    }

    private static ChatMessage routedMessage(
            MessageType type,
            String sender,
            String target,
            String payload,
            String requestId
    ) {
        return new ChatMessage(
                type,
                sender,
                target,
                payload,
                System.currentTimeMillis(),
                requestId
        );
    }

    public void announceUserLeft(String username) {
        if (username != null) {
            delivery.broadcastExcept(
                    serverEvent(MessageType.USER_LEFT, username),
                    null
            );
        }
    }

    private void handleLogin(ClientConnection connection, ChatMessage request) {
        String requestedUsername = request.sender();
        ClientRegistry.RegistrationResult result = clientRegistry.register(
                requestedUsername,
                connection
        );

        switch (result) {
            case SUCCESS -> {
                HeartbeatCredentials credentials = tokenService.issue(
                        connection.username(),
                        heartbeatPort,
                        heartbeatIntervalMillis
                );
                connection.registerHeartbeatSession(credentials.sessionId());
                delivery.send(connection, serverResponse(
                        MessageType.LOGIN_SUCCESS,
                        connection.username(),
                        heartbeatCodec.encodeCredentials(credentials),
                        request.requestId()
                ));
                delivery.sessionEstablished(connection, credentials);
                delivery.broadcastExcept(
                        serverEvent(MessageType.USER_JOINED, connection.username()),
                        connection
                );
            }
            case INVALID_USERNAME -> delivery.send(connection, serverResponse(
                    MessageType.LOGIN_FAILURE,
                    requestedUsername,
                    "Username must contain 3-20 letters, numbers, '_' or '-'",
                    request.requestId()
            ));
            case DUPLICATE_USERNAME -> delivery.send(connection, serverResponse(
                    MessageType.LOGIN_FAILURE,
                    requestedUsername,
                    "Username is already in use",
                    request.requestId()
            ));
            case ALREADY_REGISTERED -> delivery.send(connection, serverResponse(
                    MessageType.LOGIN_FAILURE,
                    connection.username(),
                    "This connection is already logged in",
                    request.requestId()
            ));
        }
    }

    private static ChatMessage serverResponse(
            MessageType type,
            String target,
            String payload,
            String requestId
    ) {
        return new ChatMessage(
                type,
                "SERVER",
                target,
                payload,
                System.currentTimeMillis(),
                requestId
        );
    }

    private static ChatMessage serverEvent(MessageType type, String username) {
        return ChatMessage.create(type, "SERVER", null, username);
    }
}
