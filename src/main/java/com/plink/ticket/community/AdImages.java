package com.plink.ticket.community;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Duration;

/** How a board photo goes out: kept by the browser that was allowed it, and by nothing in between. */
final class AdImages {
    private AdImages() {}

    static ResponseEntity<byte[]> response(AdImageService.Download image) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.type()))
            .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
            .header("X-Content-Type-Options", "nosniff")
            .header("Content-Disposition", "inline").body(image.bytes());
    }
}
