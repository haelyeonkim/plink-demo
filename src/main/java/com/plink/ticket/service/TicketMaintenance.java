package com.plink.ticket.service;

import com.plink.ticket.repository.AdmissionRepository;
import com.plink.ticket.repository.NonceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Spent nonces only need to outlive the freshness window they protect; keeping them
 * forever would grow without bound for no security gain.
 */
@Configuration
@EnableScheduling
public class TicketMaintenance {
    private static final Logger log = LoggerFactory.getLogger(TicketMaintenance.class);
    private final NonceRepository nonces;
    private final AdmissionRepository admissions;

    public TicketMaintenance(NonceRepository nonces, AdmissionRepository admissions) {
        this.nonces = nonces;
        this.admissions = admissions;
    }

    @Scheduled(initialDelay = 300_000, fixedDelay = 300_000)
    public void purgeSpentNonces() {
        int removed = nonces.purgeBefore(Timestamp.from(Instant.now().minus(10, ChronoUnit.MINUTES)));
        if (removed > 0) log.debug("Purged {} spent QR nonces", removed);
    }

    /**
     * Closes presence rows the AUTO_EXIT policy has given up on. The gate never forgives
     * an unmatched exit on the spot, so this is what keeps live occupancy from drifting
     * upward over a long event.
     */
    @Scheduled(initialDelay = 120_000, fixedDelay = 300_000)
    public void closeStalePresence() {
        Instant now = Instant.now();
        for (java.util.Map<String, Object> row : admissions.autoExitCandidates()) {
            Timestamp insideSince = (Timestamp) row.get("inside_since");
            int threshold = ((Number) row.get("auto_exit_after_minutes")).intValue();
            if (insideSince.toInstant().isAfter(now.minus(threshold, ChronoUnit.MINUTES))) continue;
            long ticketId = ((Number) row.get("ticket_id")).longValue();
            long sessionId = ((Number) row.get("session_id")).longValue();
            admissions.markOutside(ticketId, null);
            admissions.append(ticketId, sessionId, "OUT", null, "STAFF", "EXITED",
                "AUTO_EXIT 정책에 따른 자동 정리", null);
            log.info("Auto-exited ticket {} after {} minutes inside", ticketId, threshold);
        }
    }
}
