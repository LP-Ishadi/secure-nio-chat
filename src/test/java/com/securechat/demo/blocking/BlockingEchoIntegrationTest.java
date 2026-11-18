package com.securechat.demo.blocking;

import com.securechat.protocol.ChatMessage;
import com.securechat.protocol.MessageType;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockingEchoIntegrationTest {
    @Test
    void exchangesFramedMessageOverRealTcpConnection() throws Exception {
        ExecutorService serverThread = Executors.newSingleThreadExecutor();

        try (BlockingEchoServer server = new BlockingEchoServer(0)) {
            Future<ChatMessage> serverResult = serverThread.submit(server::serveOnce);
            ChatMessage outgoing = new ChatMessage(
                    MessageType.PUBLIC_MESSAGE,
                    "Alice",
                    null,
                    "Hello over TCP 👋",
                    1_700_000_000_000L,
                    "tcp-demo-1"
            );
            BlockingEchoClient client = new BlockingEchoClient(
                    InetAddress.getLoopbackAddress().getHostAddress(),
                    server.port()
            );

            ChatMessage echoed = client.exchange(outgoing);
            ChatMessage receivedByServer = serverResult.get(5, TimeUnit.SECONDS);

            assertEquals(outgoing, receivedByServer);
            assertEquals(outgoing, echoed);
        } finally {
            serverThread.shutdownNow();
            serverThread.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
