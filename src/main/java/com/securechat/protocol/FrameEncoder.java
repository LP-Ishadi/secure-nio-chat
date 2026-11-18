package com.securechat.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Adds a four-byte length header to one application payload.
 *
 * <p>The returned buffer is in read mode and can be passed directly to a
 * socket channel's {@code write} method. A new buffer is returned for every
 * frame, so this encoder does not retain state and can be shared safely.</p>
 */
public final class FrameEncoder {
    private final int maxFrameSize;

    /** Creates an encoder with the same 64 KiB limit as the default decoder. */
    public FrameEncoder() {
        this(FrameDecoder.DEFAULT_MAX_FRAME_SIZE);
    }

    /**
     * Creates an encoder with a caller-supplied payload limit.
     *
     * @param maxFrameSize largest permitted payload size in bytes
     */
    public FrameEncoder(int maxFrameSize) {
        if (maxFrameSize <= 0) {
            throw new IllegalArgumentException("maxFrameSize must be positive");
        }

        this.maxFrameSize = maxFrameSize;
    }

    /**
     * Creates {@code [4-byte length][payload]} in network byte order.
     *
     * @param payload complete application payload, normally JSON from
     *                {@link MessageCodec#encode(ChatMessage)}
     * @return a new buffer positioned at the start of the frame
     * @throws FrameEncodingException if the payload is empty or too large
     */
    public ByteBuffer encode(byte[] payload) throws FrameEncodingException {
        Objects.requireNonNull(payload, "payload must not be null");
        validatePayloadLength(payload.length);

        ByteBuffer frame = ByteBuffer
                .allocate(Integer.BYTES + payload.length)
                .order(ByteOrder.BIG_ENDIAN);
        frame.putInt(payload.length);
        frame.put(payload);
        frame.flip();
        return frame;
    }

    /** Returns the configured maximum payload size in bytes. */
    public int maxFrameSize() {
        return maxFrameSize;
    }

    private void validatePayloadLength(int payloadLength)
            throws FrameEncodingException {
        if (payloadLength == 0) {
            throw new FrameEncodingException("Frame payload must not be empty");
        }

        if (payloadLength > maxFrameSize) {
            throw new FrameEncodingException(
                    "Frame payload length " + payloadLength
                            + " exceeds maximum " + maxFrameSize
            );
        }
    }
}
