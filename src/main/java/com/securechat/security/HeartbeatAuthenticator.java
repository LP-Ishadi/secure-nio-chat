package com.securechat.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

/** Creates and verifies HMAC-SHA256 signatures for heartbeat datagrams. */
public final class HeartbeatAuthenticator {
    private static final String ALGORITHM = "HmacSHA256";

    private HeartbeatAuthenticator() {
    }

    public static String sign(
            String encodedSecret,
            String sessionId,
            long sequence,
            long timestamp
    ) {
        try {
            byte[] secret = Base64.getUrlDecoder().decode(encodedSecret);
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            byte[] signature = mac.doFinal(canonicalBytes(
                    sessionId,
                    sequence,
                    timestamp
            ));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("Could not create heartbeat signature", exception);
        }
    }

    public static boolean verify(
            String encodedSecret,
            String sessionId,
            long sequence,
            long timestamp,
            String suppliedSignature
    ) {
        if (suppliedSignature == null) {
            return false;
        }

        try {
            byte[] expected = Base64.getUrlDecoder().decode(sign(
                    encodedSecret,
                    sessionId,
                    sequence,
                    timestamp
            ));
            byte[] supplied = Base64.getUrlDecoder().decode(suppliedSignature);
            return MessageDigest.isEqual(expected, supplied);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return false;
        }
    }

    private static byte[] canonicalBytes(
            String sessionId,
            long sequence,
            long timestamp
    ) {
        return (sessionId + "\n" + sequence + "\n" + timestamp)
                .getBytes(StandardCharsets.UTF_8);
    }
}
