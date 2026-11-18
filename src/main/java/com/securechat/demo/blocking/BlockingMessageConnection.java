package com.securechat.demo.blocking;

import com.securechat.protocol.ChatMessage;
import com.securechat.protocol.FrameDecoder;
import com.securechat.protocol.FrameDecodingException;
import com.securechat.protocol.FrameEncoder;
import com.securechat.protocol.FrameEncodingException;
import com.securechat.protocol.MessageCodec;
import com.securechat.protocol.MessageCodecException;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Sends and receives framed chat messages through one blocking TCP socket.
 *
 * <p>This class exists for the Phase 3 learning demo. The final server will
 * use non-blocking {@code SocketChannel} instances instead.</p>
 */
public final class BlockingMessageConnection implements AutoCloseable {
    private static final int READ_BUFFER_SIZE = 8 * 1024;

    private final Socket socket;
    private final InputStream input;
    private final OutputStream output;
    private final MessageCodec codec = new MessageCodec();
    private final FrameEncoder frameEncoder = new FrameEncoder();
    private final FrameDecoder frameDecoder = new FrameDecoder();
    private final Deque<byte[]> completedPayloads = new ArrayDeque<>();
    private final byte[] readBuffer = new byte[READ_BUFFER_SIZE];

    public BlockingMessageConnection(Socket socket) throws IOException {
        this.socket = Objects.requireNonNull(socket, "socket must not be null");

        if (!socket.isConnected() || socket.isClosed()) {
            throw new IllegalArgumentException("socket must be open and connected");
        }

        input = socket.getInputStream();
        output = socket.getOutputStream();
    }

    /** Encodes and writes one complete application message. */
    public void send(ChatMessage message) throws IOException {
        Objects.requireNonNull(message, "message must not be null");

        try {
            ByteBuffer frame = frameEncoder.encode(codec.encode(message));
            byte[] frameBytes = new byte[frame.remaining()];
            frame.get(frameBytes);
            output.write(frameBytes);
            output.flush();
        } catch (MessageCodecException | FrameEncodingException exception) {
            throw new IOException("Could not send chat message", exception);
        }
    }

    /**
     * Blocks until one complete application message is available.
     *
     * @throws EOFException if the peer closes before another complete message
     */
    public ChatMessage receive() throws IOException {
        while (completedPayloads.isEmpty()) {
            int byteCount = input.read(readBuffer);

            if (byteCount == -1) {
                String detail = frameDecoder.hasPartialFrame()
                        ? "Peer closed during an incomplete frame"
                        : "Peer closed before sending another message";
                throw new EOFException(detail);
            }

            try {
                List<byte[]> frames = frameDecoder.decode(
                        ByteBuffer.wrap(readBuffer, 0, byteCount)
                );
                completedPayloads.addAll(frames);
            } catch (FrameDecodingException exception) {
                throw new IOException("Peer sent an invalid frame", exception);
            }
        }

        byte[] payload = completedPayloads.removeFirst();
        try {
            return codec.decode(payload);
        } catch (MessageCodecException exception) {
            throw new IOException("Peer sent an invalid chat message", exception);
        }
    }

    public Socket socket() {
        return socket;
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
