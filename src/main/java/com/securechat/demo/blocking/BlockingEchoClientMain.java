package com.securechat.demo.blocking;

import com.securechat.protocol.ChatMessage;
import com.securechat.protocol.MessageType;

import java.util.Arrays;

/** Command-line entry point for the Phase 3 blocking client demonstration. */
public final class BlockingEchoClientMain {
    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 5000;
    private static final String DEFAULT_USERNAME = "Alice";
    private static final String DEFAULT_MESSAGE = "Hello from blocking TCP";

    private BlockingEchoClientMain() {
    }

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : DEFAULT_HOST;
        int port = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_PORT;
        String username = args.length > 2 ? args[2] : DEFAULT_USERNAME;
        String text = args.length > 3
                ? String.join(" ", Arrays.copyOfRange(args, 3, args.length))
                : DEFAULT_MESSAGE;

        ChatMessage outgoing = ChatMessage.create(
                MessageType.PUBLIC_MESSAGE,
                username,
                null,
                text
        );
        BlockingEchoClient client = new BlockingEchoClient(host, port);

        System.out.println("Connecting to " + host + ":" + port);
        ChatMessage echoed = client.exchange(outgoing);
        System.out.println("Server echoed: " + echoed);
    }
}
