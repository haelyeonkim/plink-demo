package com.plink.ticket.face;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Development stand-in. It derives a stable vector from the capture bytes so the whole
 * pipeline - consent, encrypted storage, 1:N search, gate decision, retention - can run
 * and be tested end to end.
 *
 * <p><strong>This is not face recognition.</strong> It recognises identical bytes, not
 * people. Production must point {@code plink.ticket.face.service-url} at the face
 * service so {@link HttpFaceEmbedder} takes over; until then the log says so on every
 * extraction.
 */
@Component
@ConditionalOnMissingBean(HttpFaceEmbedder.class)
public class LocalFaceEmbedder implements FaceEmbedder {
    private static final Logger log = LoggerFactory.getLogger(LocalFaceEmbedder.class);
    private static final int DIMENSIONS = 128;

    @Override
    public FaceVector embed(List<byte[]> frames) {
        if (frames == null || frames.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "얼굴 사진을 찾을 수 없어요.");
        }
        log.warn("Face extraction is running on the development stand-in; set "
            + "plink.ticket.face.service-url before using this for real people.");
        byte[] best = frames.stream().max(java.util.Comparator.comparingInt(frame -> frame.length))
            .orElseThrow();
        if (best.length < 32) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "사진 품질이 낮아요. 정면을 보고 밝은 곳에서 다시 찍어 주세요.");
        }
        return new FaceVector(spread(best), algoVersion(), 0.9);
    }

    @Override
    public double liveness(List<byte[]> frames, String challenge) {
        // Nothing here can tell a live face from a photograph; only the real model can.
        return frames == null || frames.isEmpty() ? 0.0 : 0.95;
    }

    @Override
    public String algoVersion() { return "dev-stub-1"; }

    private float[] spread(byte[] source) {
        float[] values = new float[DIMENSIONS];
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] seed = digest.digest(source);
            for (int i = 0; i < DIMENSIONS; i++) {
                if (i % seed.length == 0 && i > 0) {
                    seed = digest.digest(seed);
                }
                values[i] = ((seed[i % seed.length] & 0xFF) - 127.5f) / 127.5f;
            }
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        return values;
    }

    static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
