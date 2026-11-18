package com.securechat.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reassembles length-prefixed application messages from a TCP byte stream.
 *
 * <p>Each frame has this network representation:</p>
 *
 * <pre>
 * +----------------------+---------------------------+
 * | 4-byte payload size  | payload bytes             |
 * +----------------------+---------------------------+
 * </pre>
 *
 * <p>The four-byte size is a signed, big-endian integer. A decoder retains
 * incomplete state between calls, so each client connection must own its own
 * decoder instance. This class is not thread-safe.</p>
 */
public final class FrameDecoder {
    /** Default upper bound for one JSON message payload: 64 KiB. */
    public static final int DEFAULT_MAX_FRAME_SIZE = 64 * 1024;

    private final int maxFrameSize;
    private final ByteBuffer lengthBuffer = ByteBuffer
            .allocate(Integer.BYTES)
            .order(ByteOrder.BIG_ENDIAN);

    private ByteBuffer payloadBuffer;

    /** Creates a decoder with a 64 KiB maximum payload size. */
    public FrameDecoder() {
        this(DEFAULT_MAX_FRAME_SIZE);
    }

    /**
     * Creates a decoder with a caller-supplied payload limit.
     *
     * @param maxFrameSize largest accepted payload size in bytes
     */
    public FrameDecoder(int maxFrameSize) {
        if (maxFrameSize <= 0) {
            throw new IllegalArgumentException("maxFrameSize must be positive");
        }

        this.maxFrameSize = maxFrameSize;
    }

    /**
     * Consumes currently available TCP bytes and returns all completed payloads.
     *
     * <p>The source buffer's position advances by the number of consumed bytes.
     * The returned payloads do not contain their four-byte length headers.</p>
     *
     * @param source buffer in read mode containing newly received bytes
     * @return zero or more complete payloads, in arrival order
     * @throws FrameDecodingException if a declared payload size is zero,
     *                                negative, or above the configured limit
     */
    public List<byte[]> decode(ByteBuffer source) throws FrameDecodingException {
        Objects.requireNonNull(source, "source must not be null");

        List<byte[]> completedFrames = new ArrayList<>();

        while (source.hasRemaining()) {
            if (payloadBuffer == null) {
                transferAvailable(source, lengthBuffer);

                if (lengthBuffer.hasRemaining()) {
                    break;
                }

                lengthBuffer.flip();
                int payloadLength = lengthBuffer.getInt();
                lengthBuffer.clear();

                validatePayloadLength(payloadLength);
                payloadBuffer = ByteBuffer.allocate(payloadLength);
            }

            transferAvailable(source, payloadBuffer);

            if (!payloadBuffer.hasRemaining()) {
                completedFrames.add(payloadBuffer.array());
                payloadBuffer = null;
            }
        }

        return List.copyOf(completedFrames);
    }

    /** Returns whether an incomplete header or payload is currently retained. */
    public boolean hasPartialFrame() {
        return lengthBuffer.position() > 0 || payloadBuffer != null;
    }

    /** Discards any incomplete frame, normally when its connection closes. */
    public void reset() {
        lengthBuffer.clear();
        payloadBuffer = null;
    }

    /** Returns the configured maximum payload size in bytes. */
    public int maxFrameSize() {
        return maxFrameSize;
    }

    private void validatePayloadLength(int payloadLength)
            throws FrameDecodingException {
        if (payloadLength <= 0) {
            reset();
            throw new FrameDecodingException(
                    "Frame payload length must be positive: " + payloadLength
            );
        }

        if (payloadLength > maxFrameSize) {
            reset();
            throw new FrameDecodingException(
                    "Frame payload length " + payloadLength
                            + " exceeds maximum " + maxFrameSize
            );
        }
    }

    private static void transferAvailable(ByteBuffer source, ByteBuffer target) {
        int byteCount = Math.min(source.remaining(), target.remaining());
        ByteBuffer sourceSlice = source.slice();
        sourceSlice.limit(byteCount);
        target.put(sourceSlice);
        source.position(source.position() + byteCount);
    }
}
