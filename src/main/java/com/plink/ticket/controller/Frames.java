package com.plink.ticket.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Decodes captured frames from a request. Frames are held in memory for the length of
 * the call and never written anywhere; the limits here keep that promise affordable.
 */
final class Frames {
    private static final int MAX_FRAMES = 5;
    private static final int MAX_BYTES = 400_000;

    private Frames() {}

    static List<byte[]> decode(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "얼굴 사진을 찾을 수 없어요.");
        }
        if (list.size() > MAX_FRAMES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "사진이 너무 많아요.");
        }
        List<byte[]> frames = new ArrayList<>(list.size());
        for (Object item : list) {
            byte[] decoded;
            try {
                String value = String.valueOf(item);
                int comma = value.indexOf(',');
                decoded = Base64.getDecoder().decode(value.startsWith("data:") ? value.substring(comma + 1) : value);
            } catch (IllegalArgumentException malformed) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "사진 형식을 확인할 수 없어요.");
            }
            if (decoded.length > MAX_BYTES) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "사진 용량이 너무 커요.");
            }
            frames.add(decoded);
        }
        return frames;
    }
}
