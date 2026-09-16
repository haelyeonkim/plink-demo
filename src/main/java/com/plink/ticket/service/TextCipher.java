package com.plink.ticket.service;

import com.plink.ticket.config.TicketProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Reversible storage for the few secrets an operator has to read back, such as a gate
 * terminal token. Everything that only ever needs verifying stays hashed.
 */
@Component
public class TextCipher {
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public TextCipher(TicketProperties properties) {
        this.key = new SecretKeySpec(sha256("gate-token-key|" + properties.getTokenSecret()), "AES");
    }

    public String seal(String plain) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] joined = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, joined, 0, iv.length);
            System.arraycopy(sealed, 0, joined, iv.length, sealed.length);
            return Base64.getEncoder().encodeToString(joined);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot seal value", e);
        }
    }

    /** @return null when the stored value predates encryption or the key has changed. */
    public String open(String stored) {
        if (stored == null || stored.isBlank()) return null;
        try {
            byte[] joined = Base64.getDecoder().decode(stored);
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(joined, 0, iv, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(joined, IV_BYTES, joined.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (Exception unreadable) {
            return null;
        }
    }

    private static byte[] sha256(String input) {
        try { return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
}
