package com.securechat.nio;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

/** Adapts an {@link SSLEngine} to one selector-owned non-blocking channel. */
final class TlsChannel {
    private static final ByteBuffer EMPTY = ByteBuffer.allocate(0);

    private final SSLEngine engine;
    private ByteBuffer encryptedInput;
    private ByteBuffer encryptedOutput;
    private boolean handshakeComplete;

    TlsChannel(SSLEngine engine) throws SSLException {
        this.engine = engine;
        int packetSize = engine.getSession().getPacketBufferSize();
        encryptedInput = ByteBuffer.allocate(packetSize * 2);
        encryptedOutput = ByteBuffer.allocate(packetSize * 2);
        encryptedOutput.flip();
        engine.beginHandshake();
    }

    int applicationBufferSize() {
        return engine.getSession().getApplicationBufferSize() * 4;
    }

    /** Reads encrypted bytes and places decrypted bytes in {@code plaintext}. */
    int read(SocketChannel channel, ByteBuffer plaintext) throws IOException {
        int networkBytes = channel.read(encryptedInput);
        if (networkBytes == -1) {
            return -1;
        }

        int initialPosition = plaintext.position();
        encryptedInput.flip();
        try {
            boolean continueUnwrapping = true;
            while (continueUnwrapping) {
                runDelegatedTasks();
                SSLEngineResult.HandshakeStatus handshakeStatus =
                        engine.getHandshakeStatus();
                if (handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                    continue;
                }
                if (handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                    break;
                }
                if (!encryptedInput.hasRemaining()) {
                    break;
                }

                SSLEngineResult result = engine.unwrap(
                        encryptedInput,
                        plaintext
                );
                observeHandshake(result.getHandshakeStatus());
                switch (result.getStatus()) {
                    case OK -> continueUnwrapping = result.bytesConsumed() > 0
                            || result.bytesProduced() > 0;
                    case BUFFER_UNDERFLOW -> continueUnwrapping = false;
                    case BUFFER_OVERFLOW -> throw new SSLException(
                            "TLS plaintext buffer is too small"
                    );
                    case CLOSED -> {
                        return -1;
                    }
                }
            }
        } finally {
            encryptedInput.compact();
            if (!encryptedInput.hasRemaining()) {
                encryptedInput = grow(encryptedInput);
            }
        }
        return plaintext.position() - initialPosition;
    }

    /** Wraps queued plaintext frames and writes their encrypted TLS records. */
    boolean write(SocketChannel channel, OutboundMessageQueue outbound)
            throws IOException {
        if (!flushEncryptedOutput(channel)) {
            return false;
        }

        while (true) {
            runDelegatedTasks();
            SSLEngineResult.HandshakeStatus handshakeStatus =
                    engine.getHandshakeStatus();

            ByteBuffer source;
            boolean handshakeWrap;
            if (handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                source = EMPTY;
                handshakeWrap = true;
            } else if (handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_UNWRAP
                    || handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                return outbound.isEmpty();
            } else {
                handshakeComplete = true;
                source = outbound.peek();
                if (source == null) {
                    return true;
                }
                handshakeWrap = false;
            }

            encryptedOutput.clear();
            SSLEngineResult result = engine.wrap(source, encryptedOutput);
            observeHandshake(result.getHandshakeStatus());
            if (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                encryptedOutput = grow(encryptedOutput);
                continue;
            }
            if (result.getStatus() == SSLEngineResult.Status.CLOSED) {
                return true;
            }
            if (result.getStatus() != SSLEngineResult.Status.OK) {
                throw new SSLException("Unexpected TLS wrap status: "
                        + result.getStatus());
            }

            if (!handshakeWrap) {
                outbound.removeCompletedHead(source);
            }
            encryptedOutput.flip();
            if (!flushEncryptedOutput(channel)) {
                return false;
            }
            if (result.bytesConsumed() == 0 && result.bytesProduced() == 0) {
                return outbound.isEmpty();
            }
        }
    }

    boolean needsWrite(OutboundMessageQueue outbound) {
        return encryptedOutput.hasRemaining()
                || engine.getHandshakeStatus()
                == SSLEngineResult.HandshakeStatus.NEED_WRAP
                || (handshakeComplete && !outbound.isEmpty());
    }

    boolean hasBufferedInput() {
        return encryptedInput.position() > 0;
    }

    private boolean flushEncryptedOutput(SocketChannel channel)
            throws IOException {
        while (encryptedOutput.hasRemaining()) {
            if (channel.write(encryptedOutput) == 0) {
                return false;
            }
        }
        return true;
    }

    private void runDelegatedTasks() {
        if (engine.getHandshakeStatus()
                != SSLEngineResult.HandshakeStatus.NEED_TASK) {
            return;
        }
        Runnable task;
        while ((task = engine.getDelegatedTask()) != null) {
            task.run();
        }
    }

    private void observeHandshake(SSLEngineResult.HandshakeStatus status) {
        if (status == SSLEngineResult.HandshakeStatus.FINISHED
                || status == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
            handshakeComplete = true;
        }
    }

    private static ByteBuffer grow(ByteBuffer original) {
        ByteBuffer larger = ByteBuffer.allocate(original.capacity() * 2);
        original.flip();
        larger.put(original);
        return larger;
    }
}
