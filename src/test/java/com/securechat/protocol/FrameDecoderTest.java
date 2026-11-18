package com.securechat.protocol;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameDecoderTest {
    @Test
    void decodesOneCompleteFrame() throws FrameDecodingException {
        FrameDecoder decoder = new FrameDecoder();
        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
        ByteBuffer source = frame(payload);

        List<byte[]> decoded = decoder.decode(source);

        assertEquals(1, decoded.size());
        assertArrayEquals(payload, decoded.getFirst());
        assertFalse(source.hasRemaining());
        assertFalse(decoder.hasPartialFrame());
    }

    @Test
    void retainsHeaderSplitAcrossReads() throws FrameDecodingException {
        FrameDecoder decoder = new FrameDecoder();
        byte[] completeFrame = bytes(frame("hello".getBytes(StandardCharsets.UTF_8)));

        List<byte[]> firstResult = decoder.decode(
                ByteBuffer.wrap(completeFrame, 0, 2)
        );

        assertTrue(firstResult.isEmpty());
        assertTrue(decoder.hasPartialFrame());

        List<byte[]> secondResult = decoder.decode(
                ByteBuffer.wrap(completeFrame, 2, completeFrame.length - 2)
        );

        assertEquals(1, secondResult.size());
        assertArrayEquals(
                "hello".getBytes(StandardCharsets.UTF_8),
                secondResult.getFirst()
        );
        assertFalse(decoder.hasPartialFrame());
    }

    @Test
    void retainsPayloadSplitAcrossReads() throws FrameDecodingException {
        FrameDecoder decoder = new FrameDecoder();
        byte[] completeFrame = bytes(frame("fragmented".getBytes(StandardCharsets.UTF_8)));

        List<byte[]> firstResult = decoder.decode(
                ByteBuffer.wrap(completeFrame, 0, 7)
        );

        assertTrue(firstResult.isEmpty());
        assertTrue(decoder.hasPartialFrame());

        List<byte[]> secondResult = decoder.decode(
                ByteBuffer.wrap(completeFrame, 7, completeFrame.length - 7)
        );

        assertEquals(1, secondResult.size());
        assertArrayEquals(
                "fragmented".getBytes(StandardCharsets.UTF_8),
                secondResult.getFirst()
        );
        assertFalse(decoder.hasPartialFrame());
    }

    @Test
    void decodesSeveralFramesFromOneRead() throws FrameDecodingException {
        FrameDecoder decoder = new FrameDecoder();
        ByteBuffer source = join(
                frame("one".getBytes(StandardCharsets.UTF_8)),
                frame("two".getBytes(StandardCharsets.UTF_8)),
                frame("three".getBytes(StandardCharsets.UTF_8))
        );

        List<byte[]> decoded = decoder.decode(source);

        assertEquals(3, decoded.size());
        assertArrayEquals("one".getBytes(StandardCharsets.UTF_8), decoded.get(0));
        assertArrayEquals("two".getBytes(StandardCharsets.UTF_8), decoded.get(1));
        assertArrayEquals("three".getBytes(StandardCharsets.UTF_8), decoded.get(2));
    }

    @Test
    void decodesCompleteFrameAndRetainsFollowingPartialFrame()
            throws FrameDecodingException {
        FrameDecoder decoder = new FrameDecoder();
        byte[] first = bytes(frame("first".getBytes(StandardCharsets.UTF_8)));
        byte[] second = bytes(frame("second".getBytes(StandardCharsets.UTF_8)));
        ByteBuffer initialRead = ByteBuffer.allocate(first.length + 6);
        initialRead.put(first);
        initialRead.put(second, 0, 6);
        initialRead.flip();

        List<byte[]> initialResult = decoder.decode(initialRead);

        assertEquals(1, initialResult.size());
        assertArrayEquals("first".getBytes(StandardCharsets.UTF_8), initialResult.getFirst());
        assertTrue(decoder.hasPartialFrame());

        List<byte[]> finalResult = decoder.decode(
                ByteBuffer.wrap(second, 6, second.length - 6)
        );

        assertEquals(1, finalResult.size());
        assertArrayEquals("second".getBytes(StandardCharsets.UTF_8), finalResult.getFirst());
        assertFalse(decoder.hasPartialFrame());
    }

    @Test
    void integratesWithMessageCodec() throws Exception {
        MessageCodec codec = new MessageCodec();
        ChatMessage original = new ChatMessage(
                MessageType.PUBLIC_MESSAGE,
                "Alice",
                null,
                "Hello everyone 👋",
                1_700_000_000_000L,
                "request-789"
        );
        byte[] jsonPayload = codec.encode(original);
        FrameDecoder decoder = new FrameDecoder();

        byte[] decodedPayload = decoder
                .decode(frame(jsonPayload))
                .getFirst();
        ChatMessage decodedMessage = codec.decode(decodedPayload);

        assertEquals(original, decodedMessage);
    }

    @Test
    void acceptsPayloadAtConfiguredMaximum() throws FrameDecodingException {
        FrameDecoder decoder = new FrameDecoder(5);
        byte[] payload = new byte[5];

        List<byte[]> decoded = decoder.decode(frame(payload));

        assertEquals(1, decoded.size());
        assertArrayEquals(payload, decoded.getFirst());
    }

    @Test
    void rejectsZeroNegativeAndOversizedLengths() {
        FrameDecoder decoder = new FrameDecoder(10);

        assertThrows(FrameDecodingException.class, () -> decoder.decode(header(0)));
        assertThrows(FrameDecodingException.class, () -> decoder.decode(header(-1)));
        assertThrows(FrameDecodingException.class, () -> decoder.decode(header(11)));
    }

    @Test
    void resetDiscardsIncompleteFrame() throws FrameDecodingException {
        FrameDecoder decoder = new FrameDecoder();
        decoder.decode(ByteBuffer.wrap(new byte[]{0, 0}));

        assertTrue(decoder.hasPartialFrame());

        decoder.reset();

        assertFalse(decoder.hasPartialFrame());
        List<byte[]> result = decoder.decode(frame("new".getBytes(StandardCharsets.UTF_8)));
        assertArrayEquals("new".getBytes(StandardCharsets.UTF_8), result.getFirst());
    }

    @Test
    void rejectsInvalidConfigurationAndNullInput() {
        assertThrows(IllegalArgumentException.class, () -> new FrameDecoder(0));
        assertThrows(IllegalArgumentException.class, () -> new FrameDecoder(-1));
        assertThrows(
                NullPointerException.class,
                () -> new FrameDecoder().decode(null)
        );
    }

    private static ByteBuffer frame(byte[] payload) {
        ByteBuffer frame = ByteBuffer
                .allocate(Integer.BYTES + payload.length)
                .order(ByteOrder.BIG_ENDIAN);
        frame.putInt(payload.length);
        frame.put(payload);
        frame.flip();
        return frame;
    }

    private static ByteBuffer header(int payloadLength) {
        ByteBuffer header = ByteBuffer.allocate(Integer.BYTES);
        header.putInt(payloadLength);
        header.flip();
        return header;
    }

    private static ByteBuffer join(ByteBuffer... buffers) {
        int totalSize = 0;
        for (ByteBuffer buffer : buffers) {
            totalSize += buffer.remaining();
        }

        ByteBuffer joined = ByteBuffer.allocate(totalSize);
        for (ByteBuffer buffer : buffers) {
            joined.put(buffer);
        }
        joined.flip();
        return joined;
    }

    private static byte[] bytes(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }
}
