package com.securechat.demo.blocking;

import com.securechat.protocol.ChatMessage;

/** Command-line entry point for the Phase 3 blocking server demonstration. */
public final class BlockingEchoServerMain {
    private static final int DEFAULT_PORT = 5000;

    private BlockingEchoServerMain() {
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;

        try (BlockingEchoServer server = new BlockingEchoServer(port)) {
            System.out.println("Blocking echo server listening on "
                    + server.localAddress());
            System.out.println("Waiting for one client and one message...");

            ChatMessage received = server.serveOnce();
            System.out.println("Received and echoed: " + received);
        }
    }
}
