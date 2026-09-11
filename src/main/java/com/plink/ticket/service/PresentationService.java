package com.plink.ticket.service;

import com.plink.ticket.config.TicketProperties;
import com.plink.ticket.model.Grant;
import com.plink.ticket.model.Ticket;
import com.plink.ticket.repository.NonceRepository;
import com.plink.ticket.repository.PresentationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A presentation grant is what one passkey ceremony buys: a short window during which
 * the holder's phone can mint rotating single-use codes. The phone signs each code
 * with the grant secret, so the QR itself carries no entitlement.
 *
 * <p>Wire format, kept inside the QR alphanumeric character set so the code stays at a
 * low QR version and decodes quickly on a tablet camera:
 * <pre>PLK2S.&lt;grant&gt;.&lt;counter&gt;.&lt;base36 seconds&gt;.&lt;nonce&gt;.&lt;mac&gt;</pre>
 */
@Service
public class PresentationService {
    public static final String PREFIX = "PLK2S";

    private final PresentationRepository grants;
    private final NonceRepository nonces;
    private final TicketProperties properties;

    public PresentationService(PresentationRepository grants, NonceRepository nonces, TicketProperties properties) {
        this.grants = grants;
        this.nonces = nonces;
        this.properties = properties;
    }

    public static String direction(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (!"IN".equals(value) && !"OUT".equals(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "입장 또는 퇴장을 선택해 주세요.");
        }
        return value;
    }

    /** Issued only after a successful user-verifying assertion on the bound passkey. */
    public Map<String, Object> issue(Ticket ticket, String direction, boolean uv) {
        Instant now = Instant.now();
        if (grants.countSince(ticket.id, Timestamp.from(now.minus(1, ChronoUnit.HOURS)))
                >= properties.getPresentationsPerHour()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "잠시 후에 다시 시도해 주세요. 반복 요청이 감지되었어요.");
        }
        // Only one live grant per ticket: opening a new one kills the previous QR.
        grants.revokeOpenGrants(ticket.id);

        Grant grant = new Grant();
        grant.id = Secrets.randomAlnum(12);
        grant.ticketId = ticket.id;
        grant.direction = direction;
        grant.secret = Secrets.randomToken(32);
        grant.uv = uv;
        grant.issuedAt = Timestamp.from(now);
        grant.expiresAt = Timestamp.from(now.plusSeconds(properties.getPresentationTtlSeconds()));
        grants.insert(grant);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("grantId", grant.id);
        result.put("secret", grant.secret);
        result.put("direction", direction);
        result.put("prefix", PREFIX);
        result.put("periodSeconds", properties.getCodePeriodSeconds());
        result.put("expiresAt", grant.expiresAt.toInstant().toString());
        result.put("serverTime", now.toString());
        return result;
    }

    public static class Verified {
        public final Grant grant;
        public final long counter;
        public Verified(Grant grant, long counter) { this.grant = grant; this.counter = counter; }
    }

    /**
     * Validates a scanned code: grant is live, MAC matches, timestamp is fresh, the
     * counter moved forward and the nonce has never been seen. Any failure is a
     * refusal, never a partial acceptance.
     */
    public Verified verify(String code) {
        String[] parts = code == null ? new String[0] : code.trim().split("\\.");
        if (parts.length != 6 || !PREFIX.equals(parts[0])) {
            throw deny("입장권 코드가 아니에요.");
        }
        String grantId = parts[1], counterText = parts[2], timeText = parts[3], nonce = parts[4], mac = parts[5];
        if (grantId.length() > 32 || nonce.length() > 32 || mac.length() != 32) {
            throw deny("입장권 코드가 아니에요.");
        }
        Grant grant = grants.lockById(grantId).orElseThrow(() -> deny("만료된 코드예요. 다시 인증해 주세요."));
        if (grant.consumed()) throw deny("이미 사용한 코드예요.");
        if (grant.expired()) throw deny("코드 유효 시간이 지났어요. 다시 인증해 주세요.");

        long counter, seconds;
        try {
            counter = Long.parseLong(counterText);
            seconds = Long.parseLong(timeText, 36);
        } catch (NumberFormatException malformed) {
            throw deny("입장권 코드가 아니에요.");
        }
        if (!Secrets.constantEquals(mac, Secrets.macShort(grant.secret,
                grantId + "|" + counterText + "|" + timeText + "|" + nonce))) {
            throw deny("코드 서명이 올바르지 않아요.");
        }
        long skew = Math.abs(Instant.now().getEpochSecond() - seconds);
        if (skew > properties.getClockSkewSeconds() + properties.getCodePeriodSeconds()) {
            throw deny("코드가 만료되었어요. 화면을 새로 고쳐 주세요.");
        }
        if (counter <= grant.lastCounter) throw deny("이미 지난 코드예요.");
        if (!nonces.tryUse(nonce)) throw deny("이미 사용한 코드예요.");
        return new Verified(grant, counter);
    }

    public void consume(Grant grant, long counter) {
        grants.updateCounter(grant.id, counter);
        grants.consume(grant.id);
    }

    public void revokeFor(long ticketId) {
        grants.revokeOpenGrants(ticketId);
    }

    private ResponseStatusException deny(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }
}
