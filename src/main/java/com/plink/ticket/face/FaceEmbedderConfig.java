package com.plink.ticket.face;

import com.plink.ticket.config.TicketProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Picks the extractor from one place. A plain branch rather than conditional annotations:
 * a blank service URL is the same as an absent one, which annotations get wrong, and it
 * also lets a test pin the local backend without depending on the developer's own .env.
 */
@Configuration
public class FaceEmbedderConfig {
    private static final Logger log = LoggerFactory.getLogger(FaceEmbedderConfig.class);

    @Bean
    FaceEmbedder faceEmbedder(TicketProperties properties) {
        String url = properties.getFace().getServiceUrl();
        if (url == null || url.isBlank()) {
            return new LocalFaceEmbedder();
        }
        log.info("Face extraction delegated to {}", url);
        return new HttpFaceEmbedder(url, properties.getFace().getServiceToken());
    }
}
