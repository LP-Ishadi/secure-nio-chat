package com.securechat.nio;

import com.securechat.concurrent.SerialExecutor;
import com.securechat.protocol.FrameDecoder;

import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLContext;
import java.security.NoSuchAlgorithmException;

/** Stores the state associated with one non-blocking client connection. */
public final class ClientConnection {
    private final SocketChannel channel;
    private final ByteBuffer readBuffer;
    private final TlsChannel tlsChannel;
    private final FrameDecoder frameDecoder = new FrameDecoder();
    private final OutboundMessageQueue outboundMessages =
            new OutboundMessageQueue();
    private final AtomicReference<String> username = new AtomicReference<>();
    private final AtomicReference<String> heartbeatSessionId =
            new AtomicReference<>();
    private final SerialExecutor serialExecutor;

    public ClientConnection(SocketChannel channel) throws SSLException {
        this(channel, Runnable::run, defaultEngine());
    }

    public ClientConnection(
            SocketChannel channel,
            Executor workerExecutor,
            SSLEngine sslEngine
    ) throws SSLException {
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
        tlsChannel = new TlsChannel(
                Objects.requireNonNull(sslEngine, "sslEngine must not be null")
        );
        readBuffer = ByteBuffer.allocate(tlsChannel.applicationBufferSize());
        serialExecutor = new SerialExecutor(workerExecutor);
    }

    public SocketChannel channel() {
        return channel;
    }

    public ByteBuffer readBuffer() {
        return readBuffer;
    }

    public FrameDecoder frameDecoder() {
        return frameDecoder;
    }

    public OutboundMessageQueue outboundMessages() {
        return outboundMessages;
    }

    TlsChannel tlsChannel() {
        return tlsChannel;
    }

    private static SSLEngine defaultEngine() throws SSLException {
        try {
            SSLEngine engine = SSLContext.getDefault().createSSLEngine();
            engine.setUseClientMode(false);
            return engine;
        } catch (NoSuchAlgorithmException exception) {
            throw new SSLException("Default TLS context is unavailable", exception);
        }
    }

    /** Registers this connection's identity exactly once. */
    public boolean registerUsername(String requestedUsername) {
        Objects.requireNonNull(
                requestedUsername,
                "requestedUsername must not be null"
        );
        return username.compareAndSet(null, requestedUsername);
    }

    /** Returns the authenticated username, or {@code null} before login. */
    public String username() {
        return username.get();
    }

    public boolean isRegistered() {
        return username.get() != null;
    }

    public boolean registerHeartbeatSession(String sessionId) {
        return heartbeatSessionId.compareAndSet(
                null,
                Objects.requireNonNull(sessionId)
        );
    }

    public String heartbeatSessionId() {
        return heartbeatSessionId.get();
    }

    public void executeSerial(Runnable task) {
        serialExecutor.execute(task);
    }

    /** Discards state that belongs only to this connection. */
    public void clear() {
        frameDecoder.reset();
        outboundMessages.clear();
        readBuffer.clear();
        username.set(null);
        heartbeatSessionId.set(null);
        serialExecutor.close();
    }
}
