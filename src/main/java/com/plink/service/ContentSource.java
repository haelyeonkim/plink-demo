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
            if (isWebAddress(ref)) return ref;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "원본 웹페이지 주소를 확인해 주세요.");
    }

    /**
     * An http or https address with a host and nothing smuggled in front of it. A
     * javascript: or data: address would run in whichever page offers it as a link, and
     * user@host is a classic way of making one host read like another.
     */
    public static boolean isWebAddress(String value) {
        if (value == null) return false;
        try {
            URI uri = URI.create(value.trim());
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null && uri.getRawUserInfo() == null;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
