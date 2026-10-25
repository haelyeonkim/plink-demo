package com.plink.service;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class ContentSource {
    private ContentSource() {}
    public static String webReference(String value) {
        String ref = value == null ? "" : value.trim();
        if (!ref.isEmpty() && ref.length() <= 500) {
            if (ref.matches("[0-9a-f]{64}")) return ref; // Previously stored URL fingerprints.
            try {
                var uri = URI.create(ref);
                if (("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                        && uri.getHost() != null && uri.getUserInfo() == null) return ref;
            } catch (IllegalArgumentException invalid) { /* Report a field error below. */ }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "원본 웹페이지 주소를 확인해 주세요.");
    }
}
