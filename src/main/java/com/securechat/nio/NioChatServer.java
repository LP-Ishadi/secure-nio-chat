package com.securechat.nio;

import com.securechat.concurrent.ChatWorkerPool;
import com.securechat.concurrent.ClientRegistry;
import com.securechat.concurrent.MessageDispatcher;
import com.securechat.database.DatabaseManager;
import com.securechat.protocol.ChatMessage;
import com.securechat.protocol.FrameDecodingException;
import com.securechat.protocol.FrameEncoder;
import com.securechat.protocol.FrameEncodingException;
import com.securechat.protocol.MessageCodec;
import com.securechat.protocol.MessageCodecException;
import com.securechat.protocol.MessageType;
import com.securechat.security.HeartbeatCredentials;
import com.securechat.security.SessionTokenService;
import com.securechat.security.TlsContextFactory;
import com.securechat.udp.HeartbeatServer;
import com.securechat.udp.PresenceChange;
import com.securechat.udp.PresenceTracker;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

/** Non-blocking chat server with one selector thread and a worker pool. */
public final class NioChatServer implements AutoCloseable {
    public static final long HEARTBEAT_INTERVAL_MILLIS = 5_000;

    private final Selector selector;
    private final ServerSocketChannel serverChannel;
    private final MessageCodec messageCodec = new MessageCodec();
    private final FrameEncoder frameEncoder = new FrameEncoder();
    private final ClientRegistry clientRegistry = new ClientRegistry();
    private final ChatWorkerPool workerPool;
    private final MessageDispatcher messageDispatcher;
    private final SessionTokenService tokenService = new SessionTokenService();
    private final PresenceTracker presenceTracker = new PresenceTracker();
    private final HeartbeatServer heartbeatServer;
    private final SSLContext sslContext;
    private final ConcurrentLinkedQueue<Runnable> selectorTasks =
            new ConcurrentLinkedQueue<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger connectedClients = new AtomicInteger();

    public NioChatServer(int port) throws IOException {
        this(
                "0.0.0.0",
                port,
                0,
                defaultWorkerCount(),
                TlsContextFactory.createDefaultServerContext()
        );
    }

    public NioChatServer(int port, int workerThreadCount) throws IOException {
        this(
                "0.0.0.0",
                port,
                0,
                workerThreadCount,
                TlsContextFactory.createDefaultServerContext()
        );
    }

    /** Binds immediately and creates the requested number of worker threads. */
    public NioChatServer(
            String bindHost,
            int port,
            int workerThreadCount
    ) throws IOException {
        this(
                bindHost,
                port,
                0,
                workerThreadCount,
                TlsContextFactory.createDefaultServerContext()
        );
    }

    /** Binds the TCP chat socket and UDP heartbeat socket immediately. */
    public NioChatServer(
            String bindHost,
            int port,
            int heartbeatPort,
            int workerThreadCount
    ) throws IOException {
        this(
                bindHost,
                port,
                heartbeatPort,
                workerThreadCount,
                TlsContextFactory.createDefaultServerContext()
        );
    }

    public NioChatServer(
            String bindHost,
            int port,
            int heartbeatPort,
            int workerThreadCount,
            SSLContext sslContext
    ) throws IOException {
        if (bindHost == null || bindHost.isBlank()) {
            throw new IllegalArgumentException("bindHost must not be blank");
        }
        validatePort(port);
        validatePort(heartbeatPort);
        this.sslContext = Objects.requireNonNull(
                sslContext,
                "sslContext must not be null"
        );
        try {
            DatabaseManager.initializeDatabase();
        } catch (Exception exception) {
            System.err.println("Database initialization warning: " + exception.getMessage());
        }
        workerPool = new ChatWorkerPool(workerThreadCount);

        Selector createdSelector = Selector.open();
        ServerSocketChannel createdServer = null;
        HeartbeatServer createdHeartbeatServer = null;
        try {
            createdServer = ServerSocketChannel.open();
            createdServer.configureBlocking(false);
            createdServer.bind(new InetSocketAddress(
                    InetAddress.getByName(bindHost),
                    port
            ));
            createdServer.register(createdSelector, SelectionKey.OP_ACCEPT);
            createdHeartbeatServer = new HeartbeatServer(
                    bindHost,
                    heartbeatPort,
                    tokenService,
                    presenceTracker,
                    this::publishPresenceChange
            );
        } catch (IOException | RuntimeException exception) {
            if (createdHeartbeatServer != null) {
                createdHeartbeatServer.close();
            }
            if (createdServer != null) {
                createdServer.close();
            }
            createdSelector.close();
            workerPool.close();
            throw exception;
        }

        selector = createdSelector;
        serverChannel = Objects.requireNonNull(createdServer);
        heartbeatServer = Objects.requireNonNull(createdHeartbeatServer);
        messageDispatcher = new MessageDispatcher(
                clientRegistry,
                new MessageDispatcher.Delivery() {
                    @Override
                    public void send(
                            ClientConnection recipient,
                            ChatMessage message
                    ) {
                        deliver(recipient, message);
                    }

                    @Override
                    public void broadcastExcept(
                            ChatMessage message,
                            ClientConnection excluded
                    ) {
                        broadcast(message, excluded);
                    }

                    @Override
                    public void disconnect(ClientConnection connection) {
                        requestClose(connection);
                    }

                    @Override
                    public void sessionEstablished(
                            ClientConnection connection,
                            HeartbeatCredentials credentials
                    ) {
                        PresenceChange change = presenceTracker.register(
                                credentials.sessionId(),
                                connection.username(),
                                System.currentTimeMillis()
                        );
                        publishPresenceChange(change);
                    }
                },
                tokenService,
                heartbeatServer.port(),
                HEARTBEAT_INTERVAL_MILLIS,
                new com.securechat.database.UserRepository(),
                new com.securechat.database.MessageRepository(),
                presenceTracker
        );
    }

    /** Runs the selector event loop on the calling thread until closed. */
    public void serve() throws IOException {
        if (closed.get()) {
            throw new IllegalStateException("server is already closed");
        }
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("server is already running");
        }

        try {
            heartbeatServer.start();
            while (running.get() && selector.isOpen()) {
                selector.select();
                runSelectorTasks();
                processSelectedKeys();
                runSelectorTasks();
            }
        } catch (ClosedSelectorException exception) {
            if (!closed.get()) {
                throw exception;
            }
        } finally {
            running.set(false);
            closeResources();
        }
    }

    public int port() {
        return serverChannel.socket().getLocalPort();
    }

    public int heartbeatPort() {
        return heartbeatServer.port();
    }

    public SocketAddress localAddress() throws IOException {
        return serverChannel.getLocalAddress();
    }

    public int connectedClientCount() {
        return connectedClients.get();
    }

    public int registeredClientCount() {
        return clientRegistry.size();
    }

    public boolean isRunning() {
        return running.get();
    }

    private void processSelectedKeys() {
        Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();

        while (iterator.hasNext()) {
            SelectionKey key = iterator.next();
            iterator.remove();

            if (!key.isValid()) {
                continue;
            }

            try {
                if (key.isAcceptable()) {
                    acceptAvailableClients();
                }
                if (key.isValid() && key.isReadable()) {
                    readFromClient(key);
                }
                if (key.isValid() && key.isWritable()) {
                    writeToClient(key);
                }
            } catch (IOException
                     | FrameDecodingException
                     | MessageCodecException
                     | RejectedExecutionException exception) {
                closeClient(key);
            }
        }
    }

    private void acceptAvailableClients() throws IOException {
        SocketChannel clientChannel;

        while ((clientChannel = serverChannel.accept()) != null) {
            try {
                clientChannel.configureBlocking(false);
                clientChannel.socket().setTcpNoDelay(true);
                SelectionKey clientKey = clientChannel.register(
                        selector,
                        SelectionKey.OP_READ
                );
                SSLEngine sslEngine = sslContext.createSSLEngine();
                sslEngine.setUseClientMode(false);
                sslEngine.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
                ClientConnection connection = new ClientConnection(
                        clientChannel,
                        workerPool,
                        sslEngine
                );
                clientKey.attach(connection);
                updateWriteInterest(clientKey, connection);
                connectedClients.incrementAndGet();
            } catch (IOException | RuntimeException exception) {
                clientChannel.close();
                throw exception;
            }
        }
    }

    private void readFromClient(SelectionKey key)
            throws IOException, FrameDecodingException, MessageCodecException {
        ClientConnection connection = connectionFrom(key);
        ByteBuffer readBuffer = connection.readBuffer();
        int byteCount = connection.tlsChannel().read(
                connection.channel(),
                readBuffer
        );

        if (byteCount == -1) {
            closeClient(key);
            return;
        }
        if (byteCount == 0) {
            updateWriteInterest(key, connection);
            return;
        }

        readBuffer.flip();
        List<byte[]> payloads;
        try {
            payloads = connection.frameDecoder().decode(readBuffer);
        } finally {
            readBuffer.compact();
        }

        for (byte[] payload : payloads) {
            ChatMessage message = messageCodec.decode(payload);
            connection.executeSerial(
                    () -> messageDispatcher.dispatch(connection, message)
            );
        }
        updateWriteInterest(key, connection);
    }

    private void deliver(ClientConnection recipient, ChatMessage message) {
        if (closed.get()) {
            return;
        }

        try {
            ByteBuffer frame = frameEncoder.encode(messageCodec.encode(message));
            recipient.outboundMessages().enqueue(frame);
            requestWrite(recipient);
        } catch (MessageCodecException | FrameEncodingException exception) {
            requestClose(recipient);
        }
    }

    private void broadcast(ChatMessage message, ClientConnection excluded) {
        for (ClientConnection recipient : clientRegistry.connectionsSnapshot()) {
            if (recipient != excluded) {
                deliver(recipient, message);
            }
        }
    }

    private void publishPresenceChange(PresenceChange change) {
        ChatMessage message = ChatMessage.create(
                MessageType.PRESENCE_UPDATE,
                "SERVER",
                change.username(),
                change.status().name()
        );
        broadcast(message, null);
    }

    private void requestWrite(ClientConnection connection) {
        submitSelectorTask(() -> {
            SelectionKey key = connection.channel().keyFor(selector);
            if (key != null && key.isValid() && key.attachment() != null) {
                key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
            }
        });
    }

    private void requestClose(ClientConnection connection) {
        submitSelectorTask(() -> {
            SelectionKey key = connection.channel().keyFor(selector);
            if (key != null && key.attachment() != null) {
                closeClient(key);
            }
        });
    }

    private void submitSelectorTask(Runnable task) {
        if (closed.get()) {
            return;
        }
        selectorTasks.add(task);
        selector.wakeup();
    }

    private void runSelectorTasks() {
        Runnable task;
        while ((task = selectorTasks.poll()) != null) {
            task.run();
        }
    }

    private void writeToClient(SelectionKey key)
            throws IOException, FrameDecodingException, MessageCodecException {
        ClientConnection connection = connectionFrom(key);
        connection.tlsChannel().write(
                connection.channel(),
                connection.outboundMessages()
        );
        updateWriteInterest(key, connection);
        if (key.isValid() && connection.tlsChannel().hasBufferedInput()) {
            readFromClient(key);
        }
    }

    private static void updateWriteInterest(
            SelectionKey key,
            ClientConnection connection
    ) {
        if (key == null || !key.isValid()) {
            return;
        }
        if (connection.tlsChannel().needsWrite(
                connection.outboundMessages()
        )) {
            key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
        } else {
            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
        }
    }

    private static ClientConnection connectionFrom(SelectionKey key) {
        return (ClientConnection) key.attachment();
    }

    private void closeClient(SelectionKey key) {
        Object attachment = key.attachment();
        key.attach(null);
        key.cancel();

        try {
            key.channel().close();
        } catch (IOException ignored) {
            // The connection is already being discarded.
        }

        if (attachment instanceof ClientConnection connection) {
            String heartbeatSessionId = connection.heartbeatSessionId();
            String username = clientRegistry.remove(connection);
            connection.clear();
            tokenService.revoke(heartbeatSessionId);
            presenceTracker.markOffline(heartbeatSessionId)
                    .ifPresent(this::publishPresenceChange);
            connectedClients.updateAndGet(count -> Math.max(0, count - 1));

            if (username != null && running.get()) {
                try {
                    workerPool.execute(
                            () -> messageDispatcher.announceUserLeft(username)
                    );
                } catch (RejectedExecutionException ignored) {
                    // Server shutdown already owns cleanup.
                }
            }
        }
    }

    @Override
    public void close() throws IOException {
        running.set(false);
        selector.wakeup();
        closeResources();
    }

    private synchronized void closeResources() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        heartbeatServer.close();
        workerPool.close();
        selectorTasks.clear();

        IOException failure = null;
        try {
            if (selector.isOpen()) {
                Set<SelectionKey> keys = Set.copyOf(selector.keys());
                for (SelectionKey key : keys) {
                    try {
                        key.channel().close();
                    } catch (IOException exception) {
                        if (failure == null) {
                            failure = exception;
                        } else {
                            failure.addSuppressed(exception);
                        }
                    }
                }
            }
        } finally {
            connectedClients.set(0);
            clientRegistry.clear();
            try {
                selector.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }

        if (failure != null) {
            throw failure;
        }
    }

    private static int defaultWorkerCount() {
        return Math.max(2, Math.min(
                8,
                Runtime.getRuntime().availableProcessors()
        ));
    }

    private static void validatePort(int port) {
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
    }
}
