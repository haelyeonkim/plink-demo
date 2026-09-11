package com.plink.ticket.face;

import com.plink.ticket.config.TicketProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts feature vectors at rest. A face template cannot be reissued like a password,
 * so the vector never sits in the database in the clear and no raw frame is stored at all.
 */
@Component
public class TemplateCipher {
    private static final Logger log = LoggerFactory.getLogger(TemplateCipher.class);
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public TemplateCipher(TicketProperties properties) {
        String configured = properties.getFace().getTemplateKey();
        byte[] material;
        if (configured != null && !configured.isBlank()) {
            material = Base64.getDecoder().decode(configured);
            if (material.length != 32) {
                throw new IllegalStateException("plink.ticket.face.template-key must be 32 bytes, base64 encoded");
            }
        } else {
            // Derivable keys are for development only: the point of a separate key is that
            // losing the token secret does not also lose the biometric store.
            log.warn("plink.ticket.face.template-key is unset; deriving one from the token secret. "
                + "Set a separate key before enrolling real faces.");
            material = sha256(("face-template-key|" + properties.getTokenSecret()).getBytes(StandardCharsets.UTF_8));
        }
        this.key = new SecretKeySpec(material, "AES");
    }

    public String seal(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES);
        for (float value : vector) buffer.putFloat(value);
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(buffer.array());
            byte[] joined = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, joined, 0, iv.length);
            System.arraycopy(sealed, 0, joined, iv.length, sealed.length);
            return Base64.getEncoder().encodeToString(joined);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot seal face template", e);
        }
    }

    public float[] open(String stored) {
        byte[] joined = Base64.getDecoder().decode(stored);
        byte[] iv = new byte[IV_BYTES];
        System.arraycopy(joined, 0, iv, 0, IV_BYTES);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(joined, IV_BYTES, joined.length - IV_BYTES);
            ByteBuffer buffer = ByteBuffer.wrap(plain);
            float[] vector = new float[plain.length / Float.BYTES];
            for (int i = 0; i < vector.length; i++) vector[i] = buffer.getFloat();
            return vector;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot open face template", e);
        }
    }

    private static byte[] sha256(byte[] input) {
        try { return MessageDigest.getInstance("SHA-256").digest(input); }
        catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
}
