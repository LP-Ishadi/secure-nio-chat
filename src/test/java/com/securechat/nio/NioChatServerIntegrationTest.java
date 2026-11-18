package com.securechat.nio;

import com.securechat.demo.blocking.BlockingMessageConnection;
import com.securechat.protocol.ChatMessage;
import com.securechat.protocol.MessageType;
import com.securechat.testutil.TlsTestSockets;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NioChatServerIntegrationTest {
    private static final int CLIENT_COUNT = 5;

    @Test
    void registersSeveralConcurrentClientsUsingOneSelector()
            throws Exception {
        ExecutorService serverThread = Executors.newSingleThreadExecutor();
        ExecutorService clientThreads = Executors.newFixedThreadPool(CLIENT_COUNT);

        try (NioChatServer server = new NioChatServer(0)) {
            Future<?> serverTask = serverThread.submit(() -> {
                try {
                    server.serve();
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });
            String host = InetAddress.getLoopbackAddress().getHostAddress();
            List<Future<ChatMessage>> clientResults = new ArrayList<>();

            for (int index = 0; index < CLIENT_COUNT; index++) {
                int clientNumber = index;
                clientResults.add(clientThreads.submit(() -> {
                    ChatMessage login = new ChatMessage(
                            MessageType.LOGIN,
                            "Client-" + clientNumber,
                            null,
                            null,
                            1_700_000_000_000L + clientNumber,
                            "nio-request-" + clientNumber
                    );
                    try (Socket socket = TlsTestSockets.connect(
                            host,
                            server.port()
                    )) {
                        try (BlockingMessageConnection connection =
                                     new BlockingMessageConnection(socket)) {
                            connection.send(login);
                            return connection.receive();
                        }
                    }
                }));
            }

            for (int index = 0; index < CLIENT_COUNT; index++) {
                ChatMessage response = clientResults
                        .get(index)
                        .get(5, TimeUnit.SECONDS);
                assertEquals(MessageType.LOGIN_SUCCESS, response.type());
                assertEquals("Client-" + index, response.target());
                assertEquals("nio-request-" + index, response.requestId());
            }

            server.close();
            serverTask.get(5, TimeUnit.SECONDS);
        } finally {
            clientThreads.shutdownNow();
            serverThread.shutdownNow();
            clientThreads.awaitTermination(5, TimeUnit.SECONDS);
            serverThread.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
