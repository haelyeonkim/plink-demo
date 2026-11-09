package com.plink.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ArtworkHoldExpiry {
    private final JdbcTemplate jdbc;
    private final ArtworkRequestService requests;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ArtworkHoldExpiry.class);
    public ArtworkHoldExpiry(JdbcTemplate jdbc, ArtworkRequestService requests) { this.jdbc = jdbc; this.requests = requests; }
    @Scheduled(fixedDelay = 30000)
    public void expire() {
        for (Long id : jdbc.queryForList("SELECT artwork_id FROM artwork_request WHERE hold_state='HELD' AND hold_until<=CURRENT_TIMESTAMP ORDER BY artwork_id LIMIT 200", Long.class)) {
            try { requests.expire(id); }
            catch (RuntimeException failure) { log.warn("Could not expire artwork hold {}", id, failure); }
        }
    }
}
