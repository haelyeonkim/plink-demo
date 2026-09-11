package com.plink.ticket.service;

import com.plink.ticket.config.TicketProperties;
import com.plink.ticket.model.EventSession;
import com.plink.ticket.model.Presence;
import com.plink.ticket.model.Ticket;
import com.plink.ticket.repository.AdmissionRepository;
import com.plink.ticket.repository.EventSessionRepository;
import com.plink.ticket.repository.TicketPasskeyRepository;
import com.plink.ticket.repository.TicketRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Issues ticket URLs and answers the ticket page. The personal token is only ever
 * held by the recipient: the database stores a keyed hash of it, so reading the
 * table does not hand out live claim links.
 */
@Service
public class TicketService {
    private final TicketRepository tickets;
    private final EventSessionRepository sessions;
    private final TicketPasskeyRepository passkeys;
    private final AdmissionRepository admissions;
    private final PresentationService presentations;
    private final EmailSender mail;
    private final TicketProperties properties;
    private final String baseUrl;

    public TicketService(TicketRepository tickets, EventSessionRepository sessions,
            TicketPasskeyRepository passkeys, AdmissionRepository admissions,
            PresentationService presentations, EmailSender mail,
            TicketProperties properties, @Value("${plink.auth.base-url}") String baseUrl) {
        this.tickets = tickets;
        this.sessions = sessions;
        this.passkeys = passkeys;
        this.admissions = admissions;
        this.presentations = presentations;
        this.mail = mail;
        this.properties = properties;
        this.baseUrl = baseUrl;
    }

    public String tokenHmac(String token) {
        return Secrets.hmacHex(properties.getTokenSecret(), "ticket-token|" + token);
    }

    public String urlFor(long sessionId, String token) {
        return baseUrl + "/t/" + sessionId + "/" + token;
    }

    @Transactional
    public Map<String, Object> issue(long sessionId, String email, String seat, String tier) {
        EventSession session = requireSession(sessionId);
        String recipient = EmailOtpService.normalize(email);
        String token = Secrets.randomToken(16);
        String ref = Secrets.randomAlnum(12);
        Timestamp claimExpiresAt = Timestamp.from(
            Instant.now().plus(properties.getClaimTtlHours(), ChronoUnit.HOURS));
        long id = tickets.insert(sessionId, ref, tokenHmac(token), seat, tier, recipient, claimExpiresAt);
        admissions.create(id, sessionId);

        String url = urlFor(sessionId, token);
        mail.send(recipient, "[" + session.name + "] 입장권이 발급되었어요",
            "아래 링크를 휴대폰에서 열고 본인 확인을 마치면 입장권이 활성화됩니다.\n\n" + url
            + "\n\n이 링크는 " + properties.getClaimTtlHours() + "시간 안에 등록해야 하며, 먼저 등록한 기기에만 묶입니다."
            + "\n링크를 다른 사람에게 전달하지 마세요.");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ticketId", id);
        result.put("ticketRef", ref);
        result.put("seat", seat);
        result.put("issuedToEmail", recipient);
        result.put("url", url);
        result.put("claimExpiresAt", claimExpiresAt.toInstant().toString());
        return result;
    }

    /**
     * Moves a ticket to a new device. Because one URL holds exactly one passkey, the
     * binding is dropped and the token rotated rather than a second credential added:
     * the old link stops resolving at the same moment.
     */
    @Transactional
    public Map<String, Object> reissue(Ticket ticket, String verifiedEmail) {
        String recipient = ticket.holderEmail != null ? ticket.holderEmail : ticket.issuedToEmail;
        if (!verifiedEmail.equalsIgnoreCase(recipient)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "입장권에 등록된 이메일로만 재발급할 수 있어요.");
        }
        if (ticket.reissueCount >= properties.getRebindMaxPerTicket()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "재발급 가능 횟수를 모두 사용했어요. 안내 데스크에서 도움을 받아 주세요.");
        }
        EventSession session = requireSession(ticket.sessionId);
        passkeys.deleteByTicketId(ticket.id);
        presentations.revokeFor(ticket.id);

        String token = Secrets.randomToken(16);
        Timestamp claimExpiresAt = Timestamp.from(
            Instant.now().plus(properties.getClaimTtlHours(), ChronoUnit.HOURS));
        tickets.rotateToken(ticket.id, tokenHmac(token), recipient, claimExpiresAt, true);

        String url = urlFor(ticket.sessionId, token);
        mail.send(recipient, "[" + session.name + "] 입장권 재발급 링크",
            "새 기기에서 아래 링크를 열고 다시 등록해 주세요. 이전 링크와 이전 기기의 패스키는 지금부터 사용할 수 없습니다.\n\n"
            + url + "\n\n요청하지 않았다면 즉시 안내 데스크에 알려 주세요.");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reissued", true);
        result.put("sentTo", mask(recipient));
        result.put("remaining", Math.max(0, properties.getRebindMaxPerTicket() - ticket.reissueCount - 1));
        return result;
    }

    public EventSession requireSession(long id) {
        return sessions.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "회차를 찾을 수 없어요."));
    }

    /** Resolves a personal token. A wrong token and a wrong session look identical. */
    public Ticket resolve(long sessionId, String token) {
        if (token == null || token.length() < 16 || token.length() > 64) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "입장권을 찾을 수 없어요.");
        }
        Ticket ticket = tickets.findByTokenHmac(tokenHmac(token))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "입장권을 찾을 수 없어요."));
        if (ticket.sessionId != sessionId) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "입장권을 찾을 수 없어요.");
        }
        if (ticket.revoked()) {
            throw new ResponseStatusException(HttpStatus.GONE, "사용할 수 없는 입장권이에요.");
        }
        return ticket;
    }

    /** What the ticket page renders. Never includes the token or any credential. */
    public Map<String, Object> view(Ticket ticket) {
        EventSession session = requireSession(ticket.sessionId);
        Presence presence = admissions.find(ticket.id).orElse(null);
        boolean claimed = passkeys.findByTicketId(ticket.id).isPresent();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ticketRef", ticket.ticketRef);
        result.put("claimed", claimed);
        result.put("seat", ticket.seat);
        result.put("tier", ticket.tier);
        result.put("holderEmailMasked", mask(ticket.holderEmail != null ? ticket.holderEmail : ticket.issuedToEmail));
        result.put("claimExpired", !claimed && ticket.claimExpiresAt != null
            && ticket.claimExpiresAt.toInstant().isBefore(Instant.now()));

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("name", session.name);
        event.put("venue", session.venue);
        event.put("startsAt", session.startsAt.toInstant().toString());
        event.put("gateOpensAt", session.gateOpensAt == null ? null : session.gateOpensAt.toInstant().toString());
        event.put("exitScanRequired", session.exitScanRequired);
        event.put("reentryMode", session.reentryMode);
        result.put("event", event);

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("inside", presence != null && presence.inside());
        state.put("entryCount", presence == null ? 0 : presence.entryCount);
        state.put("reentryRemaining", presence == null ? null : reentryRemaining(session, presence));
        state.put("reentryUntil", presence == null || presence.lastExitAt == null || !session.reentryAllowed()
            ? null
            : presence.lastExitAt.toInstant().plus(session.reentryGraceMinutes, ChronoUnit.MINUTES).toString());
        result.put("presence", state);
        return result;
    }

    public Integer reentryRemaining(EventSession session, Presence presence) {
        if (!session.reentryAllowed()) return 0;
        if (!session.reentryLimited()) return null;
        return Math.max(0, session.reentryMax - presence.reentryCount);
    }

    public static String mask(String email) {
        if (email == null) return null;
        int at = email.indexOf('@');
        if (at <= 1) return "***" + email.substring(Math.max(at, 0));
        return email.charAt(0) + "***" + email.substring(at);
    }
}
