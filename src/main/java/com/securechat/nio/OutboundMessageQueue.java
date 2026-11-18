package com.securechat.nio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/** Stores framed data waiting to be written to a non-blocking client channel. */
public final class OutboundMessageQueue {
    private final Deque<ByteBuffer> buffers = new ArrayDeque<>();

    /** Adds a buffer without changing the caller's position. */
    public synchronized void enqueue(ByteBuffer frame) {
        Objects.requireNonNull(frame, "frame must not be null");
        if (!frame.hasRemaining()) {
            throw new IllegalArgumentException("frame must contain bytes");
        }

        buffers.addLast(frame.asReadOnlyBuffer());
    }

    /**
     * Writes as much queued data as the channel currently accepts.
     *
     * @return {@code true} when every queued buffer was completely written
     */
    public synchronized boolean writeTo(SocketChannel channel) throws IOException {
        Objects.requireNonNull(channel, "channel must not be null");

        while (!buffers.isEmpty()) {
            ByteBuffer current = buffers.peekFirst();
            int byteCount = channel.write(current);

            if (current.hasRemaining()) {
                return false;
            }

            buffers.removeFirst();
            if (byteCount == 0) {
                return buffers.isEmpty();
            }
        }

        return true;
    }

    public synchronized boolean isEmpty() {
        return buffers.isEmpty();
    }

    /** Returns the selector-owned head buffer for TLS wrapping. */
    synchronized ByteBuffer peek() {
        return buffers.peekFirst();
    }

    synchronized void removeCompletedHead(ByteBuffer expected) {
        if (expected != null && !expected.hasRemaining()
                && buffers.peekFirst() == expected) {
            buffers.removeFirst();
        }
    }

    public synchronized int size() {
        return buffers.size();
    }

    public synchronized void clear() {
        buffers.clear();
    }
}
