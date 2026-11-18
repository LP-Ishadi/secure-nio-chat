package com.securechat.udp;

import com.securechat.security.SessionTokenService;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** Receives, authenticates, and tracks client presence heartbeats over UDP. */
public final class HeartbeatServer implements AutoCloseable {
    private static final int MAX_DATAGRAM_BYTES = 2_048;
    private static final int RECEIVE_TIMEOUT_MILLIS = 1_000;
    private static final long MAX_CLOCK_DIFFERENCE_MILLIS = 120_000;

    @FunctionalInterface
    public interface PresenceListener {
        void onPresenceChanged(PresenceChange change);
    }

    private final DatagramSocket socket;
    private final SessionTokenService tokenService;
    private final PresenceTracker presenceTracker;
    private final PresenceListener listener;
    private final HeartbeatCodec codec = new HeartbeatCodec();
    private final AtomicBoolean running = new AtomicBoolean();

    private volatile Thread receiverThread;

    /** Binds immediately. Port {@code 0} asks the OS for a free UDP port. */
    public HeartbeatServer(
            String bindHost,
            int port,
            SessionTokenService tokenService,
            PresenceTracker presenceTracker,
            PresenceListener listener
    ) throws IOException {
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
        this.tokenService = Objects.requireNonNull(tokenService);
        this.presenceTracker = Objects.requireNonNull(presenceTracker);
        this.listener = Objects.requireNonNull(listener);
        socket = new DatagramSocket(null);
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(
                InetAddress.getByName(bindHost),
                port
        ));
        socket.setSoTimeout(RECEIVE_TIMEOUT_MILLIS);
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("heartbeat server is already running");
        }
        receiverThread = Thread.ofPlatform()
                .name("udp-heartbeat-server")
                .daemon(true)
                .start(this::receiveLoop);
    }

    public int port() {
        return socket.getLocalPort();
    }

    public boolean isRunning() {
        return running.get();
    }

    private void receiveLoop() {
        while (running.get()) {
            byte[] buffer = new byte[MAX_DATAGRAM_BYTES];
            DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(datagram);
                process(datagram);
            } catch (SocketTimeoutException ignored) {
                // Periodically wake up to apply timeout transitions.
            } catch (SocketException exception) {
                if (running.get()) {
                    // A transient socket failure should not publish false state.
                    continue;
                }
                break;
            } catch (IOException | HeartbeatProtocolException ignored) {
                // UDP is untrusted: malformed datagrams are dropped silently.
            }

            expirePresence();
        }
    }

    private void process(DatagramPacket datagram)
            throws HeartbeatProtocolException {
        HeartbeatPacket packet = codec.decodePacket(
                datagram.getData(),
                datagram.getLength()
        );
        long now = System.currentTimeMillis();
        if (Math.abs(now - packet.timestamp()) > MAX_CLOCK_DIFFERENCE_MILLIS) {
            return;
        }

        Optional<String> username = tokenService.validate(packet);
        if (username.isEmpty()) {
            return;
        }

        presenceTracker.recordHeartbeat(
                packet.sessionId(),
                username.get(),
                packet.sequence(),
                now
        ).ifPresent(this::notifyListener);
    }

    private void expirePresence() {
        for (PresenceChange change : presenceTracker.expire(
                System.currentTimeMillis()
        )) {
            notifyListener(change);
        }
    }

    private void notifyListener(PresenceChange change) {
        try {
            listener.onPresenceChanged(change);
        } catch (RuntimeException ignored) {
            // A listener failure must not terminate UDP monitoring.
        }
    }

    @Override
    public void close() {
        running.set(false);
        socket.close();
        Thread thread = receiverThread;
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(2_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
