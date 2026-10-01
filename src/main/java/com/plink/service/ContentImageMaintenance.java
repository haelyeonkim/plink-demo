package com.plink.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ContentImageMaintenance {
    private final ContentImageService images;
    public ContentImageMaintenance(ContentImageService images) { this.images = images; }
    @Scheduled(initialDelay = 3600000, fixedDelay = 3600000)
    public void cleanup() { images.cleanup(); }
}
