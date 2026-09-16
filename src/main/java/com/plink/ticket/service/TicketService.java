package com.plink.ticket.service;

import com.plink.ticket.config.TicketProperties;
import com.plink.ticket.model.EventSession;
import com.plink.ticket.model.Presence;
import com.plink.ticket.model.Ticket;
import com.plink.ticket.model.Transfer;
import com.plink.ticket.repository.AdmissionRepository;
import com.plink.ticket.repository.EventSessionRepository;
import com.plink.ticket.repository.HolderRepository;
import com.plink.ticket.repository.TicketRepository;
import com.plink.ticket.repository.TransferRepository;
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
    private final HolderRepository holders;
    private final AdmissionRepository admissions;
    private final TransferRepository transfers;
    private final PresentationService presentations;
    private final EmailSender mail;
    private final MessageSender sms;
    private final TicketProperties properties;
    private final String baseUrl;

    public TicketService(TicketRepository tickets, EventSessionRepository sessions,
            HolderRepository holders, AdmissionRepository admissions,
            TransferRepository transfers, PresentationService presentations, EmailSender mail,
            MessageSender sms, TicketProperties properties,
            @Value("${plink.auth.base-url}") String baseUrl) {
        this.tickets = tickets;
        this.sessions = sessions;
        this.holders = holders;
        this.admissions = admissions;
        this.transfers = transfers;
        this.presentations = presentations;
        this.mail = mail;
        this.sms = sms;
        this.properties = properties;
        this.baseUrl = baseUrl;
    }

    /**
     * A person's WebAuthn user handle, derived from their verified email so it is the
     * same on every device and across every ticket they hold.
     */
    public String userHandleFor(String email) {
        String hex = Secrets.hmacHex(properties.getTokenSecret(), "holder-handle|" + email);
        byte[] bytes = new byte[32];
        for (int i = 0; i < 32; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String tokenHmac(String token) {
        return Secrets.hmacHex(properties.getTokenSecret(), "ticket-token|" + token);
    }

    public String urlFor(long sessionId, String token) {
        return baseUrl + "/tickets/" + sessionId + "/" + token;
    }

    @Transactional
    public Map<String, Object> issue(long sessionId, String email, String seat, String tier) {
        return issue(sessionId, email, seat, tier, null);
    }

    public Map<String, Object> issue(long sessionId, String email, String seat, String tier, String rawPhone) {
        return issue(sessionId, email, seat, tier, rawPhone, true);
    }

    /**
     * @param notify false when the operator is uploading a list and will hand the links
     *     over themselves, so hundreds of messages do not go out by accident.
     */
    @Transactional
    public Map<String, Object> issue(long sessionId, String email, String seat, String tier,
            String rawPhone, boolean notify) {
        EventSession session = requireSession(sessionId);
        String recipient = EmailOtpService.normalize(email);
        String phone = normalizePhone(rawPhone);
        seat = fromCatalogue(session.seatList(), seat, "좌석");
        tier = fromCatalogue(session.tierList(), tier, "등급");
        String token = Secrets.randomToken(16);
        String ref = Secrets.randomAlnum(12);
        Timestamp claimExpiresAt = Timestamp.from(
            Instant.now().plus(properties.getClaimTtlHours(), ChronoUnit.HOURS));
        long id;
        try {
            id = tickets.insert(sessionId, ref, tokenHmac(token), seat, tier, recipient, phone, claimExpiresAt);
        } catch (org.springframework.dao.DataIntegrityViolationException taken) {
            // (session_id, seat) is unique, so this is the seat already being spoken for.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                seat == null ? "입장권을 발급하지 못했어요." : seat + " 좌석은 이미 발급되었어요.");
        }
        admissions.create(id, sessionId);

        String url = urlFor(sessionId, token);
        boolean mailed = notify && mail.send(recipient, "[" + session.name + "] 입장권이 발급되었어요",
            "아래 링크를 휴대폰에서 열고 본인 확인을 마치면 입장권이 활성화됩니다.\n\n" + url
            + "\n\n이 링크는 " + properties.getClaimTtlHours() + "시간 안에 등록해야 하며, 먼저 등록한 기기에만 묶입니다."
            + "\n링크를 다른 사람에게 전달하지 마세요.");

        // "LINK" means nobody was notified and the operator has to hand the link over.
        String delivered = mailed ? "EMAIL" : "LINK";
        if (notify && phone != null && sms.send(phone, smsText(session, url))) {
            delivered = mailed ? "EMAIL+SMS" : "SMS";
        }
        tickets.recordDelivery(id, delivered);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ticketId", id);
        result.put("ticketRef", ref);
        result.put("seat", seat);
        result.put("issuedToEmail", recipient);
        result.put("phone", phone);
        result.put("deliveredVia", delivered);
        // Returned once, here, because the console has just created it. The token itself
        // is never stored, so this is the only moment it can be shown without rotating.
        result.put("url", url);
        result.put("claimExpiresAt", claimExpiresAt.toInstant().toString());
        return result;
    }

    /**
     * Issues a fresh link for a ticket and returns it once.
     *
     * <p>Only a keyed hash of the token is stored, so an existing link cannot be looked
     * up again - showing one always means minting a new one, and the previous link stops
     * resolving immediately. That is the cost of not keeping live links in the database.
     */
    @Transactional
    public Map<String, Object> reissueForConsole(long ticketId, boolean notify) {
        Ticket ticket = tickets.lockById(ticketId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "입장권을 찾을 수 없어요."));
        EventSession session = requireSession(ticket.sessionId);
        String recipient = ticket.holderEmail != null ? ticket.holderEmail : ticket.issuedToEmail;

        tickets.unbind(ticket.id);
        presentations.revokeFor(ticket.id);
        String token = Secrets.randomToken(16);
        Timestamp claimExpiresAt = Timestamp.from(
            Instant.now().plus(properties.getClaimTtlHours(), ChronoUnit.HOURS));
        tickets.rotateToken(ticket.id, tokenHmac(token), recipient, claimExpiresAt, true);

        String url = urlFor(ticket.sessionId, token);
        String delivered = "LINK";
        if (notify) {
            boolean mailed = mail.send(recipient, "[" + session.name + "] 입장권 링크",
                "아래 링크를 휴대폰에서 열고 등록해 주세요. 이전 링크는 더 이상 사용할 수 없습니다.\n\n" + url);
            delivered = mailed ? "EMAIL" : "LINK";
            if (ticket.phone != null && sms.send(ticket.phone, smsText(session, url))) {
                delivered = mailed ? "EMAIL+SMS" : "SMS";
            }
        }
        tickets.recordDelivery(ticket.id, delivered);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ticketId", ticket.id);
        result.put("ticketRef", ticket.ticketRef);
        result.put("url", url);
        result.put("deliveredVia", delivered);
        result.put("notified", notify);
        result.put("claimExpiresAt", claimExpiresAt.toInstant().toString());
        return result;
    }

    String smsText(EventSession session, String url) {
        return "[" + session.name + "] 입장권이 발급되었어요. 휴대폰에서 열고 등록해 주세요.\n" + url;
    }

    /**
     * Keeps an issued value inside the session's catalogue when one is defined. Without a
     * catalogue anything goes, so sessions created before seats were configurable keep
     * working.
     */
    private static String fromCatalogue(java.util.List<String> catalogue, String value, String label) {
        String trimmed = value == null ? null : value.trim();
        if (trimmed == null || trimmed.isEmpty()) return null;
        if (!catalogue.isEmpty() && !catalogue.contains(trimmed)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "이 행사에 없는 " + label + "이에요: " + trimmed);
        }
        return trimmed;
    }

    /** Digits only, so a number typed with dashes or spaces still matches. */
    public static String normalizePhone(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String digits = raw.replaceAll("[^0-9+]", "");
        if (digits.length() < 9 || digits.length() > 20) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "휴대폰 번호를 확인해 주세요.");
        }
        return digits;
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
        tickets.unbind(ticket.id);
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

    /**
     * Switches a ticket off, or back on.
     *
     * <p>Revoking stops the link opening and the gate admitting, and kills any live QR.
     * It does not eject someone already inside - the ledger records that they entered,
     * and rewriting that would be a lie. Re-enabling restores the ticket to whatever it
     * was: bound if someone had claimed it, issued if nobody had.
     */
    @Transactional
    public Map<String, Object> setRevoked(long ticketId, boolean revoked) {
        Ticket ticket = tickets.lockById(ticketId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "입장권을 찾을 수 없어요."));
        if (revoked) {
            presentations.revokeFor(ticket.id);
            tickets.updateStatus(ticket.id, "REVOKED");
        } else {
            if (!ticket.revoked()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "비활성화된 입장권이 아니에요.");
            }
            // A transfer that was still open when the ticket went off must stay locked:
            // restoring it to BOUND would let the sender enter while the recipient's
            // claim link is live, and both would get in off one ticket.
            boolean transferring = transfers.findPendingByTicket(ticket.id).filter(Transfer::open).isPresent();
            tickets.updateStatus(ticket.id,
                transferring ? "TRANSFER_PENDING" : ticket.claimed() ? "BOUND" : "ISSUED");
        }
        Ticket updated = tickets.findById(ticketId).orElseThrow();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ticketId", updated.id);
        result.put("ticketRef", updated.ticketRef);
        result.put("status", updated.status);
        return result;
    }

    public EventSession requireSession(long id) {
        return sessions.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "행사를 찾을 수 없어요."));
    }

    /**
     * A resolved ticket, and which side of a transfer the visitor is on. A link is either
     * the ticket's own token or a pending transfer's claim token; both open the same
     * ticket but mean opposite things.
     */
    public static class Resolved {
        public final Ticket ticket;
        public final Transfer claim;
        Resolved(Ticket ticket, Transfer claim) { this.ticket = ticket; this.claim = claim; }
        public boolean viaTransfer() { return claim != null; }
    }

    /** Resolves a personal token. A wrong token and a wrong session look identical. */
    public Resolved resolve(long sessionId, String token) {
        if (token == null || token.length() < 16 || token.length() > 64) {
            throw notFound();
        }
        String hash = tokenHmac(token);
        Ticket ticket = tickets.findByTokenHmac(hash).orElse(null);
        Transfer claim = null;
        if (ticket == null) {
            claim = transfers.findByClaimToken(hash).filter(Transfer::open).orElseThrow(this::notFound);
            ticket = tickets.findById(claim.ticketId).orElseThrow(this::notFound);
        }
        if (ticket.sessionId != sessionId) throw notFound();
        if (ticket.revoked()) {
            throw new ResponseStatusException(HttpStatus.GONE, "사용할 수 없는 입장권이에요.");
        }
        return new Resolved(ticket, claim);
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "입장권을 찾을 수 없어요.");
    }

    /** What the ticket page renders. Never includes the token or any credential. */
    public Map<String, Object> view(Resolved resolved) {
        Ticket ticket = resolved.ticket;
        EventSession session = requireSession(ticket.sessionId);
        Presence presence = admissions.find(ticket.id).orElse(null);
        // A recipient opening a transfer link has not registered anything yet, whatever
        // binding the sender may still hold.
        boolean claimed = !resolved.viaTransfer() && ticket.claimed();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ticketRef", ticket.ticketRef);
        result.put("claimed", claimed);
        result.put("role", resolved.viaTransfer() ? "RECIPIENT" : "HOLDER");
        Transfer pending = transfers.findPendingByTicket(ticket.id).filter(Transfer::open).orElse(null);
        result.put("transfer", pending == null ? null : Map.of(
            "status", pending.status,
            "toEmail", mask(pending.toEmail),
            "expiresAt", pending.expiresAt.toInstant().toString()));
        result.put("seat", ticket.seat);
        result.put("tier", ticket.tier);
        result.put("holderEmailMasked", mask(resolved.viaTransfer() ? resolved.claim.toEmail
            : ticket.holderEmail != null ? ticket.holderEmail : ticket.issuedToEmail));
        result.put("claimExpired", !claimed && ticket.claimExpiresAt != null
            && ticket.claimExpiresAt.toInstant().isBefore(Instant.now()));

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("name", session.name);
        event.put("venue", session.venue);
        event.put("startsAt", session.startsAt.toInstant().toString());
        event.put("gateOpensAt", session.gateOpensAt == null ? null : session.gateOpensAt.toInstant().toString());
        event.put("exitScanRequired", session.exitScanRequired);
        event.put("reentryMode", session.reentryMode);
        event.put("claimRequiresOtp", session.claimRequiresOtp);
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
