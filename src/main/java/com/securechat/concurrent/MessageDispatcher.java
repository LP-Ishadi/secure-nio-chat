package com.securechat.concurrent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securechat.database.GroupRepository;
import com.securechat.database.MessageRepository;
import com.securechat.database.ReadStatusRepository;
import com.securechat.database.StoredGroupMessage;
import com.securechat.database.StoredMessage;
import com.securechat.database.UserRepository;
import com.securechat.model.ChatGroup;
import com.securechat.model.ConversationSummary;
import com.securechat.model.CreateGroupRequest;
import com.securechat.model.GroupMember;
import com.securechat.model.GroupMemberRole;
import com.securechat.model.MarkReadRequest;
import com.securechat.nio.ClientConnection;
import com.securechat.protocol.ChatMessage;
import com.securechat.protocol.MessageType;
import com.securechat.security.HeartbeatCredentials;
import com.securechat.security.SessionTokenService;
import com.securechat.udp.HeartbeatCodec;
import com.securechat.udp.PresenceChange;
import com.securechat.udp.PresenceTracker;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

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
    private final UserRepository userRepository;
    private final MessageRepository messageRepository;
    private final GroupRepository groupRepository;
    private final ReadStatusRepository readStatusRepository;
    private final PresenceTracker presenceTracker;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MessageDispatcher(
            ClientRegistry clientRegistry,
            Delivery delivery,
            SessionTokenService tokenService,
            int heartbeatPort,
            long heartbeatIntervalMillis
    ) {
        this(
                clientRegistry,
                delivery,
                tokenService,
                heartbeatPort,
                heartbeatIntervalMillis,
                new UserRepository(),
                new MessageRepository(),
                null
        );
    }

    public MessageDispatcher(
            ClientRegistry clientRegistry,
            Delivery delivery,
            SessionTokenService tokenService,
            int heartbeatPort,
            long heartbeatIntervalMillis,
            UserRepository userRepository,
            MessageRepository messageRepository
    ) {
        this(
                clientRegistry,
                delivery,
                tokenService,
                heartbeatPort,
                heartbeatIntervalMillis,
                userRepository,
                messageRepository,
                null
        );
    }

    public MessageDispatcher(
            ClientRegistry clientRegistry,
            Delivery delivery,
            SessionTokenService tokenService,
            int heartbeatPort,
            long heartbeatIntervalMillis,
            UserRepository userRepository,
            MessageRepository messageRepository,
            PresenceTracker presenceTracker
    ) {
        this(
                clientRegistry,
                delivery,
                tokenService,
                heartbeatPort,
                heartbeatIntervalMillis,
                userRepository,
                messageRepository,
                new GroupRepository(userRepository),
                presenceTracker
        );
    }

    public MessageDispatcher(
            ClientRegistry clientRegistry,
            Delivery delivery,
            SessionTokenService tokenService,
            int heartbeatPort,
            long heartbeatIntervalMillis,
            UserRepository userRepository,
            MessageRepository messageRepository,
            GroupRepository groupRepository,
            PresenceTracker presenceTracker
    ) {
        this(
                clientRegistry,
                delivery,
                tokenService,
                heartbeatPort,
                heartbeatIntervalMillis,
                userRepository,
                messageRepository,
                groupRepository,
                new ReadStatusRepository(userRepository, groupRepository),
                presenceTracker
        );
    }

    public MessageDispatcher(
            ClientRegistry clientRegistry,
            Delivery delivery,
            SessionTokenService tokenService,
            int heartbeatPort,
            long heartbeatIntervalMillis,
            UserRepository userRepository,
            MessageRepository messageRepository,
            GroupRepository groupRepository,
            ReadStatusRepository readStatusRepository,
            PresenceTracker presenceTracker
    ) {
        this.clientRegistry = Objects.requireNonNull(
                clientRegistry,
                "clientRegistry must not be null"
        );
        this.delivery = Objects.requireNonNull(delivery, "delivery must not be null");
        this.tokenService = Objects.requireNonNull(tokenService);
        this.heartbeatPort = heartbeatPort;
        this.heartbeatIntervalMillis = heartbeatIntervalMillis;
        this.userRepository = Objects.requireNonNull(
                userRepository,
                "userRepository must not be null"
        );
        this.messageRepository = Objects.requireNonNull(
                messageRepository,
                "messageRepository must not be null"
        );
        this.groupRepository = Objects.requireNonNull(
                groupRepository,
                "groupRepository must not be null"
        );
        this.readStatusRepository = Objects.requireNonNull(
                readStatusRepository,
                "readStatusRepository must not be null"
        );
        this.presenceTracker = presenceTracker;
    }

    /** Processes one decoded message without performing socket I/O directly. */
    public void dispatch(ClientConnection connection, ChatMessage message) {
        Objects.requireNonNull(connection, "connection must not be null");
        Objects.requireNonNull(message, "message must not be null");

        if (!connection.isRegistered()) {
            if (message.type() == MessageType.LOGIN) {
                handleLogin(connection, message);
            } else if (message.type() == MessageType.REGISTER) {
                handleRegister(connection, message);
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
            case REGISTER -> delivery.send(connection, serverResponse(
                    MessageType.REGISTER_FAILURE,
                    connection.username(),
                    "This connection is already logged in",
                    message.requestId()
            ));
            case USER_LIST_REQUEST -> handleUserListRequest(connection, message);
            case PUBLIC_MESSAGE -> handlePublicMessage(connection, message);
            case PRIVATE_MESSAGE -> handlePrivateMessage(connection, message);
            case HISTORY_REQUEST -> handleHistoryRequest(connection, message);
            case GROUP_CREATE -> handleGroupCreate(connection, message);
            case GROUP_LIST_REQUEST -> handleGroupListRequest(connection, message);
            case GROUP_MEMBERS_REQUEST -> handleGroupMembersRequest(connection, message);
            case GROUP_ADD_MEMBER -> handleGroupAddMember(connection, message);
            case GROUP_REMOVE_MEMBER -> handleGroupRemoveMember(connection, message);
            case GROUP_LEAVE -> handleGroupLeave(connection, message);
            case GROUP_MESSAGE -> handleGroupMessage(connection, message);
            case GROUP_HISTORY_REQUEST -> handleGroupHistoryRequest(connection, message);
            case MARK_READ -> handleMarkRead(connection, message);
            case CONVERSATION_SUMMARIES_REQUEST -> handleConversationSummariesRequest(connection, message);
            case DISCONNECT -> delivery.disconnect(connection);
            default -> delivery.send(connection, serverResponse(
                    MessageType.ERROR,
                    connection.username(),
                    "Message type is not implemented in the concurrency phase",
                    message.requestId()
            ));
        }
    }

    private void handleUserListRequest(
            ClientConnection connection,
            ChatMessage request
    ) {
        Set<String> allUsers = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        try {
            allUsers.addAll(userRepository.getAllRegisteredUsernames());
        } catch (SQLException exception) {
            System.err.println("Could not load registered users: " + exception.getMessage());
        }
        allUsers.addAll(clientRegistry.usernamesSnapshot());

        delivery.send(connection, serverResponse(
                MessageType.USER_LIST_RESPONSE,
                connection.username(),
                String.join(",", allUsers),
                request.requestId()
        ));

        if (presenceTracker != null) {
            for (PresenceChange change : presenceTracker.currentStatuses()) {
                delivery.send(connection, ChatMessage.create(
                        MessageType.PRESENCE_UPDATE,
                        "SERVER",
                        change.username(),
                        change.status().name()
                ));
            }
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
        try {
            messageRepository.saveMessage(routed);
        } catch (SQLException | RuntimeException exception) {
            System.err.println("Could not persist public message: " + exception.getMessage());
        }
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
        try {
            messageRepository.saveMessage(routed);
        } catch (SQLException | RuntimeException exception) {
            System.err.println("Could not persist private message: " + exception.getMessage());
        }
        delivery.send(recipient, routed);

        // Echo the authoritative form to the sender for UI confirmation.
        if (recipient != sender) {
            delivery.send(sender, routed);
        }
    }

    private void handleHistoryRequest(
            ClientConnection sender,
            ChatMessage request
    ) {
        int limit = 50;
        if (request.payload() != null && !request.payload().isBlank()) {
            try {
                limit = Math.max(1, Math.min(500, Integer.parseInt(request.payload().trim())));
            } catch (NumberFormatException ignored) {
            }
        }

        String target = request.target();
        boolean isPublic = (target == null || target.isBlank() || target.equalsIgnoreCase("Public Chat"));

        try {
            List<StoredMessage> messages;
            if (isPublic) {
                messages = messageRepository.getPublicMessages(limit);
            } else {
                messages = messageRepository.getConversation(sender.username(), target, limit);
            }

            String serialized = objectMapper.writeValueAsString(messages);
            delivery.send(sender, serverResponse(
                    MessageType.HISTORY_RESPONSE,
                    target,
                    serialized,
                    request.requestId()
            ));
        } catch (SQLException | IOException exception) {
            sendError(
                    sender,
                    "Could not retrieve message history: " + exception.getMessage(),
                    request.requestId()
            );
        }
    }

    private void handleGroupCreate(ClientConnection connection, ChatMessage request) {
        String name = null;
        String description = "";
        List<String> initialMembers = List.of();

        if (request.payload() != null && !request.payload().isBlank()) {
            String payload = request.payload().trim();
            if (payload.startsWith("{")) {
                try {
                    CreateGroupRequest createReq = objectMapper.readValue(payload, CreateGroupRequest.class);
                    name = createReq.name();
                    description = createReq.description();
                    initialMembers = createReq.initialMembers();
                } catch (Exception ignored) {
                }
            }
            if (name == null) {
                name = payload;
            }
        }

        if (name == null || name.isBlank()) {
            delivery.send(connection, serverResponse(
                    MessageType.GROUP_CREATE_FAILURE,
                    connection.username(),
                    "Group name must not be blank",
                    request.requestId()
            ));
            return;
        }

        try {
            ChatGroup group = groupRepository.createGroup(
                    name,
                    description,
                    connection.username(),
                    initialMembers
            );

            String serialized = objectMapper.writeValueAsString(group);
            delivery.send(connection, serverResponse(
                    MessageType.GROUP_CREATE_SUCCESS,
                    group.groupId(),
                    serialized,
                    request.requestId()
            ));
        } catch (SQLException | IllegalArgumentException exception) {
            delivery.send(connection, serverResponse(
                    MessageType.GROUP_CREATE_FAILURE,
                    connection.username(),
                    "Could not create group: " + exception.getMessage(),
                    request.requestId()
            ));
        } catch (Exception exception) {
            delivery.send(connection, serverResponse(
                    MessageType.GROUP_CREATE_FAILURE,
                    connection.username(),
                    "Error processing group creation: " + exception.getMessage(),
                    request.requestId()
            ));
        }
    }

    private void handleGroupListRequest(ClientConnection connection, ChatMessage request) {
        try {
            List<ChatGroup> groups = groupRepository.getGroupsForUser(connection.username());
            String serialized = objectMapper.writeValueAsString(groups);
            delivery.send(connection, serverResponse(
                    MessageType.GROUP_LIST_RESPONSE,
                    connection.username(),
                    serialized,
                    request.requestId()
            ));
        } catch (SQLException | IOException exception) {
            sendError(
                    connection,
                    "Could not retrieve group list: " + exception.getMessage(),
                    request.requestId()
            );
        }
    }

    private void handleGroupMembersRequest(ClientConnection connection, ChatMessage request) {
        String groupId = request.target();
        if (groupId == null || groupId.isBlank()) {
            sendError(connection, "Group ID must not be blank", request.requestId());
            return;
        }

        try {
            if (!groupRepository.isMember(groupId, connection.username())) {
                sendError(
                        connection,
                        "Access denied: you are not a member of this group",
                        request.requestId()
                );
                return;
            }

            List<GroupMember> members = groupRepository.getGroupMembers(groupId);
            String serialized = objectMapper.writeValueAsString(members);
            delivery.send(connection, serverResponse(
                    MessageType.GROUP_MEMBERS_RESPONSE,
                    groupId,
                    serialized,
                    request.requestId()
            ));
        } catch (SQLException | IOException exception) {
            sendError(
                    connection,
                    "Could not retrieve group members: " + exception.getMessage(),
                    request.requestId()
            );
        }
    }

    private void handleGroupAddMember(ClientConnection connection, ChatMessage request) {
        String groupId = request.target();
        if (groupId == null || groupId.isBlank()) {
            sendError(connection, "Group ID must not be blank", request.requestId());
            return;
        }

        String targetUsername = null;
        if (request.payload() != null && !request.payload().isBlank()) {
            String payload = request.payload().trim();
            if (payload.startsWith("{")) {
                try {
                    var node = objectMapper.readTree(payload);
                    if (node.has("username")) {
                        targetUsername = node.get("username").asText().trim();
                    }
                } catch (Exception ignored) {
                }
            }
            if (targetUsername == null) {
                targetUsername = payload;
            }
        }

        if (targetUsername == null || targetUsername.isBlank()) {
            sendError(connection, "Username must not be blank", request.requestId());
            return;
        }

        try {
            if (!groupRepository.isAdmin(groupId, connection.username())) {
                sendError(
                        connection,
                        "Only group administrators can add members",
                        request.requestId()
                );
                return;
            }

            if (!userRepository.userExists(targetUsername)) {
                sendError(
                        connection,
                        "User is not registered: " + targetUsername,
                        request.requestId()
                );
                return;
            }

            if (groupRepository.isMember(groupId, targetUsername)) {
                sendError(
                        connection,
                        "User is already a member of this group: " + targetUsername,
                        request.requestId()
                );
                return;
            }

            groupRepository.addMember(groupId, targetUsername, GroupMemberRole.MEMBER);

            delivery.send(connection, serverResponse(
                    MessageType.GROUP_OPERATION_SUCCESS,
                    groupId,
                    "Member added: " + targetUsername,
                    request.requestId()
            ));
        } catch (SQLException | IllegalArgumentException exception) {
            sendError(
                    connection,
                    "Could not add member: " + exception.getMessage(),
                    request.requestId()
            );
        }
    }

    private void handleGroupRemoveMember(ClientConnection connection, ChatMessage request) {
        String groupId = request.target();
        if (groupId == null || groupId.isBlank()) {
            sendError(connection, "Group ID must not be blank", request.requestId());
            return;
        }

        String targetUsername = request.payload() == null ? "" : request.payload().trim();
        if (targetUsername.isBlank()) {
            sendError(connection, "Target username must not be blank", request.requestId());
            return;
        }

        try {
            if (!groupRepository.isAdmin(groupId, connection.username())) {
                sendError(
                        connection,
                        "Only group administrators can remove members",
                        request.requestId()
                );
                return;
            }

            if (!groupRepository.isMember(groupId, targetUsername)) {
                sendError(
                        connection,
                        "User is not a member of this group: " + targetUsername,
                        request.requestId()
                );
                return;
            }

            groupRepository.removeMember(groupId, targetUsername);

            delivery.send(connection, serverResponse(
                    MessageType.GROUP_OPERATION_SUCCESS,
                    groupId,
                    "Member removed: " + targetUsername,
                    request.requestId()
            ));
        } catch (SQLException exception) {
            sendError(
                    connection,
                    "Could not remove member: " + exception.getMessage(),
                    request.requestId()
            );
        }
    }

    private void handleGroupLeave(ClientConnection connection, ChatMessage request) {
        String groupId = request.target();
        if (groupId == null || groupId.isBlank()) {
            sendError(connection, "Group ID must not be blank", request.requestId());
            return;
        }

        try {
            if (!groupRepository.isMember(groupId, connection.username())) {
                sendError(
                        connection,
                        "You are not a member of this group",
                        request.requestId()
                );
                return;
            }

            groupRepository.leaveGroup(groupId, connection.username());

            delivery.send(connection, serverResponse(
                    MessageType.GROUP_OPERATION_SUCCESS,
                    groupId,
                    "Left group successfully",
                    request.requestId()
            ));
        } catch (SQLException exception) {
            sendError(
                    connection,
                    "Could not leave group: " + exception.getMessage(),
                    request.requestId()
            );
        }
    }

    private void handleGroupMessage(ClientConnection sender, ChatMessage request) {
        String groupId = request.target();
        if (groupId == null || groupId.isBlank()) {
            sendError(sender, "Group ID must not be blank", request.requestId());
            return;
        }

        String validationError = validatePayload(request.payload());
        if (validationError != null) {
            sendError(sender, validationError, request.requestId());
            return;
        }

        try {
            if (!groupRepository.isMember(groupId, sender.username())) {
                sendError(
                        sender,
                        "Access denied: you are not a member of this group",
                        request.requestId()
                );
                return;
            }

            long sentAt = System.currentTimeMillis();
            groupRepository.saveGroupMessage(
                    groupId,
                    sender.username(),
                    request.payload(),
                    "TEXT",
                    null,
                    sentAt,
                    request.requestId()
            );

            ChatMessage routed = new ChatMessage(
                    MessageType.GROUP_MESSAGE,
                    sender.username(),
                    groupId,
                    request.payload(),
                    sentAt,
                    request.requestId()
            );

            List<GroupMember> members = groupRepository.getGroupMembers(groupId);
            for (GroupMember member : members) {
                ClientConnection recipient = clientRegistry.find(member.username());
                if (recipient != null && recipient.channel().isOpen()) {
                    delivery.send(recipient, routed);
                }
            }
        } catch (SQLException exception) {
            sendError(
                    sender,
                    "Could not send group message: " + exception.getMessage(),
                    request.requestId()
            );
        }
    }

    private void handleGroupHistoryRequest(ClientConnection sender, ChatMessage request) {
        String groupId = request.target();
        if (groupId == null || groupId.isBlank()) {
            sendError(sender, "Group ID must not be blank", request.requestId());
            return;
        }

        try {
            if (!groupRepository.isMember(groupId, sender.username())) {
                sendError(
                        sender,
                        "Access denied: you are not a member of this group",
                        request.requestId()
                );
                return;
            }

            int limit = 50;
            int offset = 0;
            String payload = request.payload();
            if (payload != null && !payload.isBlank()) {
                payload = payload.trim();
                if (payload.startsWith("{")) {
                    try {
                        var node = objectMapper.readTree(payload);
                        if (node.has("limit")) {
                            limit = node.get("limit").asInt();
                        }
                        if (node.has("offset")) {
                            offset = node.get("offset").asInt();
                        }
                    } catch (Exception ignored) {
                    }
                } else if (payload.contains(",")) {
                    String[] parts = payload.split(",", 2);
                    try {
                        limit = Integer.parseInt(parts[0].trim());
                    } catch (NumberFormatException ignored) {
                    }
                    try {
                        offset = Integer.parseInt(parts[1].trim());
                    } catch (NumberFormatException ignored) {
                    }
                } else {
                    try {
                        limit = Integer.parseInt(payload);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }

            limit = Math.max(1, Math.min(500, limit));
            offset = Math.max(0, offset);

            List<StoredGroupMessage> messages = groupRepository.getGroupMessages(groupId, limit, offset);
            String serialized = objectMapper.writeValueAsString(messages);
            delivery.send(sender, serverResponse(
                    MessageType.GROUP_HISTORY_RESPONSE,
                    groupId,
                    serialized,
                    request.requestId()
            ));
        } catch (SQLException | IOException exception) {
            sendError(
                    sender,
                    "Could not retrieve group message history: " + exception.getMessage(),
                    request.requestId()
            );
        }
    }

    private void handleMarkRead(ClientConnection connection, ChatMessage request) {
        String conversationId = request.target();
        boolean isGroup = false;
        long lastReadMessageId = 0;

        if (request.payload() != null && !request.payload().isBlank()) {
            String payload = request.payload().trim();
            if (payload.startsWith("{")) {
                try {
                    MarkReadRequest req = objectMapper.readValue(payload, MarkReadRequest.class);
                    if (req.conversationId() != null && !req.conversationId().isBlank()) {
                        conversationId = req.conversationId();
                    }
                    isGroup = req.isGroup();
                    lastReadMessageId = req.lastReadMessageId();
                } catch (Exception ignored) {
                }
            } else if (payload.contains(",")) {
                String[] parts = payload.split(",");
                if (parts.length >= 1 && (conversationId == null || conversationId.isBlank())) {
                    conversationId = parts[0].trim();
                }
                if (parts.length >= 2) {
                    isGroup = Boolean.parseBoolean(parts[1].trim());
                }
                if (parts.length >= 3) {
                    try {
                        lastReadMessageId = Long.parseLong(parts[2].trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }

        if (conversationId == null || conversationId.isBlank()) {
            sendError(connection, "Conversation ID must not be blank", request.requestId());
            return;
        }

        try {
            readStatusRepository.markConversationRead(
                    connection.username(),
                    conversationId,
                    isGroup,
                    lastReadMessageId
            );

            delivery.send(connection, serverResponse(
                    MessageType.READ_STATUS_UPDATE,
                    conversationId,
                    "Read status updated",
                    request.requestId()
            ));
        } catch (SQLException exception) {
            sendError(
                    connection,
                    "Could not update read status: " + exception.getMessage(),
                    request.requestId()
            );
        }
    }

    private void handleConversationSummariesRequest(ClientConnection connection, ChatMessage request) {
        try {
            List<ConversationSummary> summaries = readStatusRepository.getConversationSummaries(connection.username());
            String serialized = objectMapper.writeValueAsString(summaries);
            delivery.send(connection, serverResponse(
                    MessageType.CONVERSATION_SUMMARIES_RESPONSE,
                    connection.username(),
                    serialized,
                    request.requestId()
            ));
        } catch (SQLException | IOException exception) {
            sendError(
                    connection,
                    "Could not retrieve conversation summaries: " + exception.getMessage(),
                    request.requestId()
            );
        }
    }

    private void handleRegister(ClientConnection connection, ChatMessage request) {
        String requestedUsername = request.sender();
        String password = request.payload();

        if (requestedUsername == null || !ClientRegistry.isValidUsername(requestedUsername)) {
            delivery.send(connection, serverResponse(
                    MessageType.REGISTER_FAILURE,
                    requestedUsername,
                    "Username must contain 3-20 letters, numbers, '_' or '-'",
                    request.requestId()
            ));
            return;
        }

        if (password == null || password.length() < 8) {
            delivery.send(connection, serverResponse(
                    MessageType.REGISTER_FAILURE,
                    requestedUsername,
                    "Password must contain at least 8 characters",
                    request.requestId()
            ));
            return;
        }

        try {
            boolean registered = userRepository.registerUser(
                    requestedUsername,
                    password.toCharArray()
            );
            if (registered) {
                delivery.send(connection, serverResponse(
                        MessageType.REGISTER_SUCCESS,
                        requestedUsername,
                        "Registration successful",
                        request.requestId()
                ));
            } else {
                delivery.send(connection, serverResponse(
                        MessageType.REGISTER_FAILURE,
                        requestedUsername,
                        "Username is already registered",
                        request.requestId()
                ));
            }
        } catch (SQLException | IllegalArgumentException exception) {
            delivery.send(connection, serverResponse(
                    MessageType.REGISTER_FAILURE,
                    requestedUsername,
                    "Registration failed: " + exception.getMessage(),
                    request.requestId()
            ));
        }
    }

    private void handleLogin(ClientConnection connection, ChatMessage request) {
        String requestedUsername = request.sender();
        String password = request.payload();

        if (!ClientRegistry.isValidUsername(requestedUsername)) {
            delivery.send(connection, serverResponse(
                    MessageType.LOGIN_FAILURE,
                    requestedUsername,
                    "Username must contain 3-20 letters, numbers, '_' or '-'",
                    request.requestId()
            ));
            return;
        }

        if (password == null || password.isBlank()) {
            delivery.send(connection, serverResponse(
                    MessageType.LOGIN_FAILURE,
                    requestedUsername,
                    "Password is required",
                    request.requestId()
            ));
            return;
        }

        try {
            boolean authenticated = userRepository.authenticateUser(
                    requestedUsername,
                    password.toCharArray()
            );
            if (!authenticated) {
                delivery.send(connection, serverResponse(
                        MessageType.LOGIN_FAILURE,
                        requestedUsername,
                        "Invalid username or password",
                        request.requestId()
                ));
                return;
            }
        } catch (SQLException exception) {
            delivery.send(connection, serverResponse(
                    MessageType.LOGIN_FAILURE,
                    requestedUsername,
                    "Authentication error: " + exception.getMessage(),
                    request.requestId()
            ));
            return;
        }

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
                connection.confirmLogin();
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
