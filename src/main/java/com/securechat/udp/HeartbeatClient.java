package com.securechat.udp;

import com.securechat.security.HeartbeatAuthenticator;
import com.securechat.security.HeartbeatCredentials;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Periodically sends signed client presence heartbeats over UDP. */
public final class HeartbeatClient implements AutoCloseable {
    private final InetAddress serverAddress;
    private final HeartbeatCredentials credentials;
    private final HeartbeatCodec codec = new HeartbeatCodec();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicBoolean running = new AtomicBoolean();

    private volatile DatagramSocket socket;
    private volatile ScheduledExecutorService scheduler;
    private volatile IOException lastFailure;

    public HeartbeatClient(String serverHost, HeartbeatCredentials credentials)
            throws IOException {
        this.serverAddress = InetAddress.getByName(serverHost);
        this.credentials = Objects.requireNonNull(credentials);
    }

    public void start() throws SocketException {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("heartbeat client is already running");
        }

        try {
            socket = new DatagramSocket();
        } catch (SocketException exception) {
            running.set(false);
            throw exception;
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "udp-heartbeat-client");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleAtFixedRate(
                this::sendSafely,
                0,
                credentials.intervalMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    public boolean isRunning() {
        return running.get();
    }

    public IOException lastFailure() {
        return lastFailure;
    }

    private void sendSafely() {
        if (!running.get()) {
            return;
        }

        long currentSequence = sequence.getAndIncrement();
        long timestamp = System.currentTimeMillis();
        String signature = HeartbeatAuthenticator.sign(
                credentials.secret(),
                credentials.sessionId(),
                currentSequence,
                timestamp
        );
        HeartbeatPacket packet = new HeartbeatPacket(
                credentials.sessionId(),
                currentSequence,
                timestamp,
                signature
        );
        byte[] bytes = codec.encodePacket(packet);
        DatagramPacket datagram = new DatagramPacket(
                bytes,
                bytes.length,
                serverAddress,
                credentials.heartbeatPort()
        );
        try {
            socket.send(datagram);
        } catch (IOException exception) {
            lastFailure = exception;
        }
    }

    @Override
    public void close() {
        running.set(false);
        ScheduledExecutorService activeScheduler = scheduler;
        if (activeScheduler != null) {
            activeScheduler.shutdownNow();
        }
        DatagramSocket activeSocket = socket;
        if (activeSocket != null) {
            activeSocket.close();
        }
    }
}
