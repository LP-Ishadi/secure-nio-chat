package com.securechat.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MessageTypeTest {
    @Test
    void convertsProtocolNameToMessageType() {
        assertEquals(MessageType.LOGIN, MessageType.valueOf("LOGIN"));
        assertEquals(
                MessageType.PRIVATE_MESSAGE,
                MessageType.valueOf("PRIVATE_MESSAGE")
        );
    }
}
