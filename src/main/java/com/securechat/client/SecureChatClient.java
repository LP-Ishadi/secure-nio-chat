package com.securechat.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securechat.demo.blocking.BlockingMessageConnection;
import com.securechat.model.CreateGroupRequest;
import com.securechat.protocol.ChatMessage;
import com.securechat.protocol.MessageType;
import com.securechat.security.HeartbeatCredentials;
import com.securechat.security.TlsContextFactory;
import com.securechat.udp.HeartbeatClient;
import com.securechat.udp.HeartbeatCodec;
import com.securechat.udp.HeartbeatProtocolException;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

/** Persistent blocking-socket client used by the JavaFX interface. */
public final class SecureChatClient implements AutoCloseable {
    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;

    private final ChatClientListener listener;
    private final Object sendLock = new Object();
    private final AtomicBoolean connected = new AtomicBoolean();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final HeartbeatCodec heartbeatCodec = new HeartbeatCodec();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SSLContext configuredSslContext;

    private volatile Socket socket;
    private volatile BlockingMessageConnection connection;
    private volatile String username;
    private volatile Thread receiverThread;
    private volatile HeartbeatClient heartbeatClient;
    private volatile String tlsProtocol;

    public SecureChatClient(ChatClientListener listener) {
        this(listener, null);
    }

    public SecureChatClient(
            ChatClientListener listener,
            SSLContext sslContext
    ) {
        this.listener = Objects.requireNonNull(listener, "listener must not be null");
        configuredSslContext = sslContext;
    }

    /** Connects, performs LOGIN without a password, and starts the background receiver thread. */
    public ChatMessage connect(String host, int port, String requestedUsername)
            throws IOException {
        return connect(host, port, requestedUsername, null);
    }

    /** Connects, performs password-authenticated LOGIN, and starts the background receiver thread. */
    public ChatMessage connect(
            String host,
            int port,
            String requestedUsername,
            String password
    ) throws IOException {
        validateConnectionArguments(host, port, requestedUsername);
        if (socket != null || connected.get()) {
            throw new IllegalStateException("client has already been used");
        }

        SSLContext activeSslContext = configuredSslContext == null
                ? TlsContextFactory.createDefaultClientContext()
                : configuredSslContext;
        SSLSocket newSocket = (SSLSocket) activeSslContext
                .getSocketFactory()
                .createSocket();
        BlockingMessageConnection newConnection = null;
        HeartbeatClient newHeartbeatClient = null;
        try {
            newSocket.connect(
                    new InetSocketAddress(host, port),
                    CONNECT_TIMEOUT_MILLIS
            );
            newSocket.setSoTimeout(CONNECT_TIMEOUT_MILLIS);
            newSocket.setTcpNoDelay(true);
            newSocket.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
            SSLParameters parameters = newSocket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            newSocket.setSSLParameters(parameters);
            newSocket.startHandshake();
            tlsProtocol = newSocket.getSession().getProtocol();
            newConnection = new BlockingMessageConnection(newSocket);

            ChatMessage login = ChatMessage.create(
                    MessageType.LOGIN,
                    requestedUsername,
                    null,
                    password
            );
            newConnection.send(login);
            ChatMessage response = newConnection.receive();
            if (response.type() != MessageType.LOGIN_SUCCESS) {
                throw new LoginRejectedException(response);
            }

            HeartbeatCredentials credentials;
            try {
                credentials = heartbeatCodec.decodeCredentials(response.payload());
            } catch (HeartbeatProtocolException exception) {
                throw new IOException(
                        "Server returned invalid heartbeat credentials",
                        exception
                );
            }
            newHeartbeatClient = new HeartbeatClient(host, credentials);
            newHeartbeatClient.start();

            newSocket.setSoTimeout(0);
            socket = newSocket;
            connection = newConnection;
            username = requestedUsername;
            heartbeatClient = newHeartbeatClient;
            closing.set(false);
            connected.set(true);
            startReceiverThread();
            return response;
        } catch (IOException | RuntimeException exception) {
            if (newHeartbeatClient != null) {
                newHeartbeatClient.close();
            }
            if (newConnection != null) {
                try {
                    newConnection.close();
                } catch (IOException ignored) {
                    // Preserve the original connection/login failure.
                }
            } else {
                try {
                    newSocket.close();
                } catch (IOException ignored) {
                    // Preserve the original connection/login failure.
                }
            }
            throw exception;
        }
    }

    /** Connects temporarily to register a new user account, then closes the connection. */
    public ChatMessage register(
            String host,
            int port,
            String requestedUsername,
            String password
    ) throws IOException {
        validateConnectionArguments(host, port, requestedUsername);
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("Password must contain at least 8 characters");
        }

        SSLContext activeSslContext = configuredSslContext == null
                ? TlsContextFactory.createDefaultClientContext()
                : configuredSslContext;
        try (SSLSocket regSocket = (SSLSocket) activeSslContext
                .getSocketFactory()
                .createSocket()) {
            regSocket.connect(
                    new InetSocketAddress(host, port),
                    CONNECT_TIMEOUT_MILLIS
            );
            regSocket.setSoTimeout(CONNECT_TIMEOUT_MILLIS);
            regSocket.setTcpNoDelay(true);
            regSocket.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
            SSLParameters parameters = regSocket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            regSocket.setSSLParameters(parameters);
            regSocket.startHandshake();

            try (BlockingMessageConnection regConnection = new BlockingMessageConnection(regSocket)) {
                ChatMessage registerMessage = ChatMessage.create(
                        MessageType.REGISTER,
                        requestedUsername,
                        null,
                        password
                );
                regConnection.send(registerMessage);
                return regConnection.receive();
            }
        }
    }

    public void sendPublicMessage(String payload) throws IOException {
        send(ChatMessage.create(
                MessageType.PUBLIC_MESSAGE,
                username,
                null,
                payload
        ));
    }

    public void sendPrivateMessage(String target, String payload)
            throws IOException {
        send(ChatMessage.create(
                MessageType.PRIVATE_MESSAGE,
                username,
                target,
                payload
        ));
    }

    public void requestUserList() throws IOException {
        send(ChatMessage.create(
                MessageType.USER_LIST_REQUEST,
                username,
                null,
                null
        ));
    }

    public void requestHistory(String contact, int limit) throws IOException {
        send(ChatMessage.create(
                MessageType.HISTORY_REQUEST,
                username,
                contact,
                Integer.toString(limit)
        ));
    }

    public void requestHistory(String contact) throws IOException {
        requestHistory(contact, 50);
    }

    public void createGroup(String name, String description, List<String> initialMembers) throws IOException {
        CreateGroupRequest request = new CreateGroupRequest(name, description, initialMembers);
        String json = objectMapper.writeValueAsString(request);
        send(ChatMessage.create(
                MessageType.GROUP_CREATE,
                username,
                null,
                json
        ));
    }

    public void requestGroupList() throws IOException {
        send(ChatMessage.create(
                MessageType.GROUP_LIST_REQUEST,
                username,
                null,
                null
        ));
    }

    public void requestGroupMembers(String groupId) throws IOException {
        send(ChatMessage.create(
                MessageType.GROUP_MEMBERS_REQUEST,
                username,
                groupId,
                null
        ));
    }

    public void addGroupMember(String groupId, String targetUsername) throws IOException {
        send(ChatMessage.create(
                MessageType.GROUP_ADD_MEMBER,
                username,
                groupId,
                targetUsername
        ));
    }

    public void removeGroupMember(String groupId, String targetUsername) throws IOException {
        send(ChatMessage.create(
                MessageType.GROUP_REMOVE_MEMBER,
                username,
                groupId,
                targetUsername
        ));
    }

    public void leaveGroup(String groupId) throws IOException {
        send(ChatMessage.create(
                MessageType.GROUP_LEAVE,
                username,
                groupId,
                null
        ));
    }

    public void sendGroupMessage(String groupId, String payload) throws IOException {
        send(ChatMessage.create(
                MessageType.GROUP_MESSAGE,
                username,
                groupId,
                payload
        ));
    }

    public void requestGroupHistory(String groupId, int limit, int offset) throws IOException {
        send(ChatMessage.create(
                MessageType.GROUP_HISTORY_REQUEST,
                username,
                groupId,
                limit + "," + offset
        ));
    }

    public void requestGroupHistory(String groupId) throws IOException {
        requestGroupHistory(groupId, 50, 0);
    }

    public void markConversationRead(String conversationId, boolean isGroup, long lastReadMessageId) throws IOException {
        String payload = conversationId + "," + isGroup + "," + lastReadMessageId;
        send(ChatMessage.create(
                MessageType.MARK_READ,
                username,
                conversationId,
                payload
        ));
    }

    public void markConversationRead(String conversationId, boolean isGroup) throws IOException {
        markConversationRead(conversationId, isGroup, 0);
    }

    public void requestConversationSummaries() throws IOException {
        send(ChatMessage.create(
                MessageType.CONVERSATION_SUMMARIES_REQUEST,
                username,
                null,
                null
        ));
    }

    public boolean isConnected() {
        return connected.get();
    }

    public String username() {
        return username;
    }

    public String tlsProtocol() {
        return tlsProtocol;
    }

    private void send(ChatMessage message) throws IOException {
        if (!connected.get()) {
            throw new IOException("client is not connected");
        }

        synchronized (sendLock) {
            BlockingMessageConnection activeConnection = connection;
            if (activeConnection == null) {
                throw new IOException("client connection is unavailable");
            }
            activeConnection.send(message);
        }
    }

    private void startReceiverThread() {
        receiverThread = Thread.ofPlatform()
                .name("chat-client-receiver")
                .daemon(true)
                .start(this::receiveLoop);
    }

    private void receiveLoop() {
        try {
            while (connected.get()) {
                listener.onMessage(connection.receive());
            }
        } catch (EOFException | SocketException exception) {
            if (!closing.get()) {
                listener.onConnectionError("Connection to server was closed", exception);
            }
        } catch (IOException exception) {
            if (!closing.get()) {
                listener.onConnectionError("Could not receive server message", exception);
            }
        } finally {
            connected.set(false);
            closeSocketQuietly();
            listener.onDisconnected();
        }
    }

    /** Sends a graceful disconnect request and closes the socket. */
    public void disconnect() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }

        if (connected.get()) {
            try {
                send(ChatMessage.create(
                        MessageType.DISCONNECT,
                        username,
                        null,
                        null
                ));
            } catch (IOException ignored) {
                // Socket close below still completes local disconnection.
            }
        }

        connected.set(false);
        closeSocketQuietly();
    }

    private void closeSocketQuietly() {
        HeartbeatClient activeHeartbeatClient = heartbeatClient;
        heartbeatClient = null;
        if (activeHeartbeatClient != null) {
            activeHeartbeatClient.close();
        }

        BlockingMessageConnection activeConnection = connection;
        connection = null;
        socket = null;

        if (activeConnection != null) {
            try {
                activeConnection.close();
            } catch (IOException ignored) {
                // Connection is already closing.
            }
        }
    }

    @Override
    public void close() {
        disconnect();
    }

    private static void validateConnectionArguments(
            String host,
            int port,
            String username
    ) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }
    }
}
