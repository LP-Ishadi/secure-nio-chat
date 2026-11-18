package com.securechat.nio;

import com.securechat.config.ServerConfig;
import com.securechat.security.TlsContextFactory;

/** Command-line entry point for the plaintext NIO chat server. */
public final class NioRegistrationServerMain {
    private NioRegistrationServerMain() {
    }

    public static void main(String[] args) throws Exception {
        ServerConfig config = ServerConfig.loadDefaultOrFallback();
        int port = args.length > 0
                ? Integer.parseInt(args[0])
                : config.serverPort();
        String bindHost = args.length > 1 ? args[1] : config.bindHost();

        try (NioChatServer server = new NioChatServer(
                bindHost,
                port,
                config.heartbeatPort(),
                4,
                TlsContextFactory.createServerContext(
                        config.keyStorePath(),
                        config.keyStorePassword().toCharArray()
                )
        )) {
            System.out.println("TLS NIO chat server listening on "
                    + server.localAddress());
            System.out.println("Configured UDP heartbeat port: "
                    + server.heartbeatPort());
            System.out.println("Stop the server with Ctrl+C.");
            server.serve();
        }
    }
}
