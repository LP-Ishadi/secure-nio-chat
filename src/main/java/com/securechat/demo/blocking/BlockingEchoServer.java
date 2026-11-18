package com.securechat.demo.blocking;

import com.securechat.protocol.ChatMessage;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;

/** Accepts one blocking TCP client, then receives and echoes one message. */
public final class BlockingEchoServer implements AutoCloseable {
    private static final int ACCEPT_TIMEOUT_MILLIS = 60_000;
    private static final int CLIENT_READ_TIMEOUT_MILLIS = 10_000;

    private final ServerSocket serverSocket;

    /**
     * Binds the server immediately. Port {@code 0} asks the OS for a free port.
     */
    public BlockingEchoServer(int port) throws IOException {
        validatePort(port, true);

        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(port));
        serverSocket.setSoTimeout(ACCEPT_TIMEOUT_MILLIS);
    }

    /** Blocks while serving exactly one message from exactly one client. */
    public ChatMessage serveOnce() throws IOException {
        try (Socket clientSocket = serverSocket.accept()) {
            clientSocket.setSoTimeout(CLIENT_READ_TIMEOUT_MILLIS);
            clientSocket.setTcpNoDelay(true);

            try (BlockingMessageConnection connection =
                         new BlockingMessageConnection(clientSocket)) {
                ChatMessage received = connection.receive();
                connection.send(received);
                return received;
            }
        }
    }

    /** Returns the actual bound port, including an OS-selected port. */
    public int port() {
        return serverSocket.getLocalPort();
    }

    public SocketAddress localAddress() {
        return serverSocket.getLocalSocketAddress();
    }

    @Override
    public void close() throws IOException {
        serverSocket.close();
    }

    private static void validatePort(int port, boolean allowAutomaticPort) {
        int minimum = allowAutomaticPort ? 0 : 1;
        if (port < minimum || port > 65_535) {
            throw new IllegalArgumentException(
                    "port must be between " + minimum + " and 65535"
            );
        }
    }
}
