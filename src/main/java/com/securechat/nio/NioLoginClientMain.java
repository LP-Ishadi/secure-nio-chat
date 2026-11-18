package com.securechat.nio;

import com.securechat.demo.blocking.BlockingMessageConnection;
import com.securechat.protocol.ChatMessage;
import com.securechat.protocol.MessageType;

import java.net.InetSocketAddress;
import java.net.Socket;

/** Small command-line client for demonstrating NIO username registration. */
public final class NioLoginClientMain {
    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 5000;
    private static final String DEFAULT_USERNAME = "Alice";

    private NioLoginClientMain() {
    }

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : DEFAULT_HOST;
        int port = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_PORT;
        String username = args.length > 2 ? args[2] : DEFAULT_USERNAME;

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 10_000);
            socket.setSoTimeout(10_000);

            try (BlockingMessageConnection connection =
                         new BlockingMessageConnection(socket)) {
                ChatMessage login = ChatMessage.create(
                        MessageType.LOGIN,
                        username,
                        null,
                        null
                );
                connection.send(login);
                ChatMessage response = connection.receive();
                System.out.println("Server response: " + response);

                if (response.type() == MessageType.LOGIN_SUCCESS) {
                    System.out.println(
                            "Logged in as " + username
                                    + ". Press Enter to disconnect."
                    );
                    System.in.read();
                    connection.send(ChatMessage.create(
                            MessageType.DISCONNECT,
                            username,
                            null,
                            null
                    ));
                }
            }
        }
    }
}
