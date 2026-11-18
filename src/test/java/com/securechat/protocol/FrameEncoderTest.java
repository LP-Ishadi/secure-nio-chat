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

class FrameEncoderTest {
    @Test
    void writesBigEndianLengthAndPayload() throws FrameEncodingException {
        FrameEncoder encoder = new FrameEncoder();
        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);

        ByteBuffer frame = encoder.encode(payload);

        assertEquals(ByteOrder.BIG_ENDIAN, frame.order());
        assertEquals(Integer.BYTES + payload.length, frame.remaining());
        assertEquals(payload.length, frame.getInt());

        byte[] encodedPayload = new byte[frame.remaining()];
        frame.get(encodedPayload);
        assertArrayEquals(payload, encodedPayload);
        assertFalse(frame.hasRemaining());
    }

    @Test
    void returnsBufferReadyForSocketWriting() throws FrameEncodingException {
        ByteBuffer frame = new FrameEncoder().encode(
                "ready".getBytes(StandardCharsets.UTF_8)
        );

        assertEquals(0, frame.position());
        assertEquals(frame.capacity(), frame.limit());
    }

    @Test
    void copiesPayloadIntoIndependentFrame() throws FrameEncodingException {
        byte[] payload = "safe".getBytes(StandardCharsets.UTF_8);
        ByteBuffer frame = new FrameEncoder().encode(payload);

        payload[0] = 'X';
        frame.getInt();
        byte[] encodedPayload = new byte[frame.remaining()];
        frame.get(encodedPayload);

        assertArrayEquals("safe".getBytes(StandardCharsets.UTF_8), encodedPayload);
    }

    @Test
    void acceptsPayloadAtConfiguredMaximum() throws FrameEncodingException {
        FrameEncoder encoder = new FrameEncoder(5);

        ByteBuffer frame = encoder.encode(new byte[5]);

        assertEquals(5, frame.getInt());
        assertEquals(5, frame.remaining());
        assertEquals(5, encoder.maxFrameSize());
    }

    @Test
    void rejectsEmptyAndOversizedPayloads() {
        FrameEncoder encoder = new FrameEncoder(5);

        assertThrows(
                FrameEncodingException.class,
                () -> encoder.encode(new byte[0])
        );
        assertThrows(
                FrameEncodingException.class,
                () -> encoder.encode(new byte[6])
        );
    }

    @Test
    void rejectsInvalidConfigurationAndNullInput() {
        assertThrows(IllegalArgumentException.class, () -> new FrameEncoder(0));
        assertThrows(IllegalArgumentException.class, () -> new FrameEncoder(-1));
        assertThrows(
                NullPointerException.class,
                () -> new FrameEncoder().encode(null)
        );
    }

    @Test
    void roundTripsSeveralFramesThroughDecoder() throws Exception {
        FrameEncoder encoder = new FrameEncoder();
        FrameDecoder decoder = new FrameDecoder();
        byte[] first = "first".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second".getBytes(StandardCharsets.UTF_8);
        ByteBuffer firstFrame = encoder.encode(first);
        ByteBuffer secondFrame = encoder.encode(second);
        ByteBuffer networkRead = ByteBuffer.allocate(
                firstFrame.remaining() + secondFrame.remaining()
        );
        networkRead.put(firstFrame);
        networkRead.put(secondFrame);
        networkRead.flip();

        List<byte[]> decoded = decoder.decode(networkRead);

        assertEquals(2, decoded.size());
        assertArrayEquals(first, decoded.get(0));
        assertArrayEquals(second, decoded.get(1));
    }

    @Test
    void integratesWithCodecAndDecoder() throws Exception {
        MessageCodec codec = new MessageCodec();
        FrameEncoder encoder = new FrameEncoder();
        FrameDecoder decoder = new FrameDecoder();
        ChatMessage original = new ChatMessage(
                MessageType.PUBLIC_MESSAGE,
                "Alice",
                null,
                "Hello from the complete protocol",
                1_700_000_000_000L,
                "request-complete"
        );

        ByteBuffer frame = encoder.encode(codec.encode(original));
        byte[] decodedPayload = decoder.decode(frame).getFirst();
        ChatMessage decoded = codec.decode(decodedPayload);

        assertEquals(original, decoded);
    }
}
