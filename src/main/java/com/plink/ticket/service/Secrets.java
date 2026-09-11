package com.plink.ticket.service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** Token generation and keyed hashing. Nothing here ever logs or returns a raw secret. */
public final class Secrets {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL64 = Base64.getUrlEncoder().withoutPadding();

    private Secrets() {}

    /** URL-safe random string; 16 bytes gives 128 bits of entropy. */
    public static String randomToken(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return URL64.encodeToString(buffer);
    }

    public static String hmacHex(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    /**
     * Truncated keyed MAC for the rotating QR code. Uppercase hex stays inside QR
     * alphanumeric mode, which packs denser than base64 despite being longer.
     */
    public static String macShort(String key, String data) {
        return hmacHex(key, data).substring(0, 32).toUpperCase(java.util.Locale.ROOT);
    }

    private static final char[] ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    /** Uppercase base32-style identifier, safe for QR alphanumeric mode. */
    public static String randomAlnum(int length) {
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) out.append(ALNUM[RANDOM.nextInt(ALNUM.length)]);
        return out.toString();
    }

    public static String sha256Hex(String data) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static boolean constantEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return out.toString();
    }
}
