package com.securechat.udp;

import com.securechat.security.HeartbeatCredentials;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HeartbeatCodecTest {
    private final HeartbeatCodec codec = new HeartbeatCodec();

    @Test
    void roundTripsCredentialsAndPacket() throws Exception {
        HeartbeatCredentials credentials = new HeartbeatCredentials(
                "session-1",
                "c2VjcmV0",
                5001,
                5_000
        );
        assertEquals(
                credentials,
                codec.decodeCredentials(codec.encodeCredentials(credentials))
        );

        HeartbeatPacket packet = new HeartbeatPacket(
                "session-1",
                3,
                99,
                "signature"
        );
        byte[] encoded = codec.encodePacket(packet);
        assertEquals(packet, codec.decodePacket(encoded, encoded.length));
    }

    @Test
    void rejectsMalformedAndUnknownFields() {
        byte[] malformed = "not-json".getBytes();
        byte[] unknownField = ("{\"sessionId\":\"s\",\"sequence\":1,"
                + "\"timestamp\":2,\"signature\":\"x\",\"extra\":true}")
                .getBytes();

        assertThrows(
                HeartbeatProtocolException.class,
                () -> codec.decodePacket(malformed, malformed.length)
        );
        assertThrows(
                HeartbeatProtocolException.class,
                () -> codec.decodePacket(unknownField, unknownField.length)
        );
    }
}
