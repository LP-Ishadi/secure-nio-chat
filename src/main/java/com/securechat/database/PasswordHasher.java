
package com.securechat.database;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public final class PasswordHasher {

    private static final int ITERATIONS = 600_000;
    private static final int KEY_LENGTH = 256;
    private static final int SALT_LENGTH = 16;

    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {
    }

    public static String hashPassword(char[] password) {

        byte[] salt = new byte[SALT_LENGTH];
        RANDOM.nextBytes(salt);

        byte[] hash = generateHash(password, salt);

        return "pbkdf2$" + ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(hash);
    }

    public static boolean verifyPassword(
            char[] password,
            String storedHash
    ) {

        try {
            String[] parts = storedHash.split("\\$");

            if (parts.length != 4 || !parts[0].equals("pbkdf2")) {
                return false;
            }

            int iterations = Integer.parseInt(parts[1]);

            if (iterations < 100_000 || iterations > 2_000_000) {
                return false;
            }

            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expectedHash = Base64.getDecoder().decode(parts[3]);

            if (salt.length != SALT_LENGTH || expectedHash.length != 32) {
                return false;
            }

            byte[] actualHash = generateHash(
                    password, salt, iterations
            );

            return MessageDigest.isEqual(expectedHash, actualHash);

        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] generateHash(
            char[] password,
            byte[] salt
    ) {
        return generateHash(password, salt, ITERATIONS);
    }

    private static byte[] generateHash(
            char[] password,
            byte[] salt,
            int iterations
    ) {

        PBEKeySpec spec = new PBEKeySpec(
                password, salt, iterations, KEY_LENGTH
        );

        try {
            SecretKeyFactory factory =
                    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");

            return factory.generateSecret(spec).getEncoded();

        } catch (Exception e) {
            throw new IllegalStateException("Password hashing failed.", e);

        } finally {
            spec.clearPassword();
        }
    }
}
