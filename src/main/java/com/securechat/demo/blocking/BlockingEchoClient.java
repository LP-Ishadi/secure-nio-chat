package com.securechat.demo.blocking;

import com.securechat.protocol.ChatMessage;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Objects;

/** Connects to the blocking demo server and exchanges one message. */
public final class BlockingEchoClient {
    private static final int SOCKET_TIMEOUT_MILLIS = 10_000;

    private final String host;
    private final int port;

    public BlockingEchoClient(String host, int port) {
        this.host = requireHost(host);
        validatePort(port);
        this.port = port;
    }

    /** Sends one message and blocks until its echoed copy is received. */
    public ChatMessage exchange(ChatMessage message) throws IOException {
        Objects.requireNonNull(message, "message must not be null");

        try (Socket socket = new Socket()) {
            socket.connect(
                    new InetSocketAddress(host, port),
                    SOCKET_TIMEOUT_MILLIS
            );
            socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            socket.setTcpNoDelay(true);

            try (BlockingMessageConnection connection =
                         new BlockingMessageConnection(socket)) {
                connection.send(message);
                return connection.receive();
            }
        }
    }

    private static String requireHost(String host) {
        Objects.requireNonNull(host, "host must not be null");
        if (host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        return host;
    }

    private static void validatePort(int port) {
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
    }
}
