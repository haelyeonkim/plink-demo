package com.plink.ticket;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * A software authenticator: one ES256 key, answering registration and assertion
 * challenges the way a phone's platform authenticator would, with "none" attestation
 * and user verification set. Lets a test walk the real WebAuthn checks end to end
 * instead of planting a signed-in mark in the session.
 */
public final class FakePasskey {
    private static final Base64.Encoder URL64 = Base64.getUrlEncoder().withoutPadding();

    private final KeyPair keys;
    private final byte[] credentialId = new byte[16];
    private final String origin;
    private final String rpId;
    private int counter = 0;

    public FakePasskey(String origin) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            this.keys = generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        new SecureRandom().nextBytes(credentialId);
        this.origin = origin;
        this.rpId = URI.create(origin).getHost();
    }

    /** The JSON a browser posts after navigator.credentials.create() with this challenge. */
    public String register(String challenge) {
        byte[] clientData = clientData("webauthn.create", challenge);
        ByteArrayOutputStream auth = new ByteArrayOutputStream();
        auth.writeBytes(sha256(rpId.getBytes(StandardCharsets.UTF_8)));
        auth.write(0x45); // user present, user verified, attested credential data
        auth.writeBytes(ByteBuffer.allocate(4).putInt(counter).array());
        auth.writeBytes(new byte[16]); // AAGUID
        auth.writeBytes(ByteBuffer.allocate(2).putShort((short) credentialId.length).array());
        auth.writeBytes(credentialId);
        auth.writeBytes(coseKey());
        byte[] authData = auth.toByteArray();

        ByteArrayOutputStream attestation = new ByteArrayOutputStream();
        attestation.write(0xA3);
        text(attestation, "fmt");
        text(attestation, "none");
        text(attestation, "attStmt");
        attestation.write(0xA0);
        text(attestation, "authData");
        bytes(attestation, authData);

        return "{\"type\":\"public-key\",\"id\":\"" + b64(credentialId) + "\",\"rawId\":\"" + b64(credentialId)
            + "\",\"response\":{\"attestationObject\":\"" + b64(attestation.toByteArray())
            + "\",\"clientDataJSON\":\"" + b64(clientData) + "\",\"transports\":[\"internal\"]},"
            + "\"clientExtensionResults\":{}}";
    }

    /** The JSON a browser posts after navigator.credentials.get() with this challenge. */
    public String authenticate(String challenge) {
        byte[] clientData = clientData("webauthn.get", challenge);
        counter++;
        ByteArrayOutputStream auth = new ByteArrayOutputStream();
        auth.writeBytes(sha256(rpId.getBytes(StandardCharsets.UTF_8)));
        auth.write(0x05); // user present, user verified
        auth.writeBytes(ByteBuffer.allocate(4).putInt(counter).array());
        byte[] authData = auth.toByteArray();
        byte[] signature;
        try {
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(keys.getPrivate());
            signer.update(authData);
            signer.update(sha256(clientData));
            signature = signer.sign();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return "{\"type\":\"public-key\",\"id\":\"" + b64(credentialId) + "\",\"rawId\":\"" + b64(credentialId)
            + "\",\"response\":{\"authenticatorData\":\"" + b64(authData) + "\",\"clientDataJSON\":\""
            + b64(clientData) + "\",\"signature\":\"" + b64(signature) + "\"},\"clientExtensionResults\":{}}";
    }

    private byte[] clientData(String type, String challenge) {
        return ("{\"type\":\"" + type + "\",\"challenge\":\"" + challenge + "\",\"origin\":\"" + origin
            + "\",\"crossOrigin\":false}").getBytes(StandardCharsets.UTF_8);
    }

    /** The public key as COSE: EC2, ES256, P-256, x, y. */
    private byte[] coseKey() {
        ECPublicKey key = (ECPublicKey) keys.getPublic();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xA5);
        out.write(0x01); out.write(0x02);       // kty: EC2
        out.write(0x03); out.write(0x26);       // alg: -7 (ES256)
        out.write(0x20); out.write(0x01);       // crv: P-256
        out.write(0x21); bytes(out, coordinate(key.getW().getAffineX()));
        out.write(0x22); bytes(out, coordinate(key.getW().getAffineY()));
        return out.toByteArray();
    }

    private static byte[] coordinate(BigInteger value) {
        byte[] raw = value.toByteArray();
        if (raw.length == 32) return raw;
        if (raw.length > 32) return Arrays.copyOfRange(raw, raw.length - 32, raw.length);
        byte[] padded = new byte[32];
        System.arraycopy(raw, 0, padded, 32 - raw.length, raw.length);
        return padded;
    }

    private static void text(ByteArrayOutputStream out, String value) {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        header(out, 0x60, utf8.length);
        out.writeBytes(utf8);
    }

    private static void bytes(ByteArrayOutputStream out, byte[] value) {
        header(out, 0x40, value.length);
        out.writeBytes(value);
    }

    private static void header(ByteArrayOutputStream out, int major, int length) {
        if (length < 24) {
            out.write(major | length);
        } else if (length < 256) {
            out.write(major | 24);
            out.write(length);
        } else {
            out.write(major | 25);
            out.write(length >> 8);
            out.write(length & 0xFF);
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(byte[] data) {
        return URL64.encodeToString(data);
    }
}
