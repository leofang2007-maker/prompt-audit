package com.gigrt.promptaudit.tenant;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Salted PBKDF2-HMAC-SHA256 password hashing — no external dependency (Java 8 stdlib).
 * Format: {@code pbkdf2$<iterations>$<saltB64>$<hashB64>}.
 */
public final class PasswordHash {

    private static final int ITERATIONS = 120_000;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RNG = new SecureRandom();

    private PasswordHash() {}

    public static String hash(String password) {
        byte[] salt = new byte[16];
        RNG.nextBytes(salt);
        byte[] dk = pbkdf2(password, salt, ITERATIONS);
        Base64.Encoder b64 = Base64.getEncoder();
        return "pbkdf2$" + ITERATIONS + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(dk);
    }

    /** Constant-time verify. */
    public static boolean verify(String password, String stored) {
        try {
            String[] p = stored.split("\\$");
            if (p.length != 4 || !"pbkdf2".equals(p[0])) return false;
            int iters = Integer.parseInt(p[1]);
            byte[] salt = Base64.getDecoder().decode(p[2]);
            byte[] expected = Base64.getDecoder().decode(p[3]);
            byte[] actual = pbkdf2(password, salt, iters);
            if (actual.length != expected.length) return false;
            int r = 0;
            for (int i = 0; i < actual.length; i++) r |= actual[i] ^ expected[i];
            return r == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new RuntimeException("pbkdf2 failed", e);
        }
    }
}
