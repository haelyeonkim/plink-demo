package com.plink.ticket.service;

import com.plink.activity.Activity;
import com.plink.activity.ActivityContext;
import com.plink.activity.ActivityLog;
import com.plink.ticket.config.TicketProperties;
import com.plink.ticket.model.EventSession;
import com.plink.ticket.model.Presence;
import com.plink.ticket.model.Ticket;
import com.plink.ticket.model.TicketField;
import com.plink.ticket.model.Transfer;
import com.plink.ticket.repository.AdmissionRepository;
import com.plink.ticket.repository.EventSessionRepository;
import com.plink.ticket.repository.HolderRepository;
import com.plink.ticket.repository.TicketFieldRepository;
import com.plink.ticket.repository.TicketRepository;
import com.plink.ticket.repository.TransferRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

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
    private final TicketFieldRepository fields;
    private final EventSessionRepository sessions;
    private final HolderRepository holders;
    private final AdmissionRepository admissions;
    private final TransferRepository transfers;
    private final PresentationService presentations;
    private final EmailSender mail;
    private final MessageSender sms;
    private final TicketProperties properties;
    private final TextCipher cipher;
    private final CouponService coupons;
    private final com.plink.ticket.live.LiveEvents live;
    /**
     * Issuing and console re-issue commit the ticket before any message goes out, then
     * report what was actually delivered. A link mailed from inside the transaction
     * could outlive a rollback, and a slow relay would hold a pooled connection with it.
     */
    private final TransactionTemplate transactions;
    private final String baseUrl;

    private final com.plink.ticket.schedule.ScheduleService schedule;

    public TicketService(TicketRepository tickets, TicketFieldRepository fields,
            EventSessionRepository sessions,
            HolderRepository holders, AdmissionRepository admissions,
            TransferRepository transfers, PresentationService presentations, EmailSender mail,
            MessageSender sms, TicketProperties properties, TextCipher cipher, CouponService coupons,
            com.plink.ticket.live.LiveEvents live,
            org.springframework.transaction.PlatformTransactionManager transactionManager,
            com.plink.ticket.schedule.ScheduleService schedule,
            @Value("${plink.auth.base-url}") String baseUrl) {
        this.schedule = schedule;
        this.live = live;
        this.transactions = new TransactionTemplate(transactionManager);
        this.coupons = coupons;
        this.tickets = tickets;
        this.fields = fields;
        this.sessions = sessions;
        this.holders = holders;
        this.admissions = admissions;
        this.transfers = transfers;
        this.presentations = presentations;
        this.mail = mail;
        this.sms = sms;
        this.properties = properties;
        this.cipher = cipher;
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

    public Map<String, Object> issue(long sessionId, String email, String seat, String tier) {
        return issue(sessionId, email, seat, tier, null);
    }

    public Map<String, Object> issue(long sessionId, String email, String seat, String tier, String rawPhone) {
        return issue(sessionId, email, seat, tier, rawPhone, true);
    }

    public Map<String, Object> issue(long sessionId, String email, String seat, String tier,
            String rawPhone, boolean notify) {
        return issue(sessionId, email, seat, tier, rawPhone, notify, java.util.Map.of());
    }

    /**
     * @param notify false when the operator is uploading a list and will hand the links
     *     over themselves, so hundreds of messages do not go out by accident.
     */
    /**
     * @param values what the organiser's own fields say for this ticket, keyed by the
     *     field's label. Seat and tier are passed separately because they have columns.
     */
    public Map<String, Object> issue(long sessionId, String email, String seat, String tier,
            String rawPhone, boolean notify, Map<String, String> values) {
        return issue(sessionId, email, seat, tier, rawPhone, notify, values, null, null);
    }

    /** @param passId the days the ticket admits, by pass; null admits every day */
    public Map<String, Object> issue(long sessionId, String email, String seat, String tier,
            String rawPhone, boolean notify, Map<String, String> values, Long passId) {
        return issue(sessionId, email, seat, tier, rawPhone, notify, values, null, passId);
    }

    /**
     * A ticket a holder asks for on somebody else's behalf: issued and mailed like the
     * console's, under their name.
     *
     * @param admit run inside the issuing transaction before the ticket is created: where
     *     the caller locks the inviter and checks they still have an invite to give, so the
     *     check and the ticket it allows commit together or not at all
     */
    public record Invitation(long inviterId, String inviterMasked, String name, Runnable admit) {}

    public Map<String, Object> issueInvite(long sessionId, String email, Invitation invitation) {
        return issue(sessionId, email, null, null, null, true, Map.of(), invitation, null);
    }

    private Map<String, Object> issue(long sessionId, String email, String seat, String tier,
            String rawPhone, boolean notify, Map<String, String> values, Invitation invitation, Long passId) {
        Issued issued = transactions.execute(status -> {
            if (invitation != null) invitation.admit().run();
            Issued created = create(sessionId, email, seat, tier, rawPhone, values);
            if (invitation != null) tickets.markInvited(created.id, invitation.inviterId(), invitation.name());
            // An invitee comes on the days the person who asked them comes.
            Long pass = invitation != null
                ? tickets.findById(invitation.inviterId()).map(t -> t.passId).orElse(null) : passId;
            if (pass != null) tickets.setPass(created.id, pass);
            return created;
        });
        // Committed: the link exists whatever happens to the messages below.
        String claimNote = claimNote();
        String delivered = !notify ? "LINK" : invitation == null
            ? deliver(issued.session, issued.recipient, issued.phone, issued.url,
                "[" + issued.session.name + "] 입장권이 발급되었어요",
                "아래 링크를 휴대폰에서 열고 본인 확인을 마치면 입장권이 활성화됩니다.\n\n" + issued.url + claimNote)
            : deliver(issued.session, issued.recipient, issued.phone, issued.url,
                "[" + issued.session.name + "] 초대 입장권이 도착했어요",
                invitation.inviterMasked() + "님이 초대했어요.\n\n"
                + "아래 링크를 휴대폰에서 열고 본인 확인을 마치면 입장권이 활성화됩니다.\n\n" + issued.url + claimNote);
        tickets.recordDelivery(issued.id, delivered);
        ActivityLog.emit(Activity.of(invitation == null ? Activity.TICKET : Activity.INVITE,
                invitation == null ? "ticket.issued" : "invite.issued")
            .session(sessionId).ticket(issued.id).result(delivered).summary(issued.recipient)
            .put("ticketRef", issued.ref).put("email", issued.recipient).put("phone", issued.phone)
            .put("seat", issued.seat).put("tier", tier).put("values", values.isEmpty() ? null : values)
            .put("notify", notify).put("deliveredVia", delivered)
            .put("inviter", invitation == null ? null : invitation.inviterId())
            .put("inviteName", invitation == null ? null : invitation.name())
            .put("claimExpiresAt", issued.claimExpiresAt));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ticketId", issued.id);
        result.put("ticketRef", issued.ref);
        result.put("seat", issued.seat);
        result.put("issuedToEmail", issued.recipient);
        result.put("phone", issued.phone);
        result.put("deliveredVia", delivered);
        // Returned once, here, because the console has just created it. The token itself
        // is never stored, so this is the only moment it can be shown without rotating.
        result.put("url", issued.url);
        result.put("claimExpiresAt", issued.claimExpiresAt.toInstant().toString());
        return result;
    }

    private String claimNote() {
        return "\n\n이 링크는 " + properties.getClaimTtlHours() + "시간 안에 등록해야 하며, 먼저 등록한 기기에만 묶입니다."
            + "\n링크를 다른 사람에게 전달하지 마세요.";
    }

    /**
     * Mails an invite nobody has registered yet its own link again, unchanged, as the
     * console's resend does: the link already in the invitee's mailbox keeps working. The
     * window to register starts over, since a reminder for a link that can no longer be
     * registered would be no use. The caller has checked who may ask and how often.
     *
     * @return whether the mail went
     */
    public boolean resendInvite(Ticket invited, String inviterMasked) {
        String token = cipher.open(invited.tokenCipher);
        if (token == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이 초대는 메일을 다시 보낼 수 없어요.");
        }
        EventSession session = requireSession(invited.sessionId);
        String url = urlFor(invited.sessionId, token);
        tickets.extendClaim(invited.id, Timestamp.from(
            Instant.now().plus(properties.getClaimTtlHours(), ChronoUnit.HOURS)));
        boolean mailed = mail.send(invited.issuedToEmail, "[" + session.name + "] 초대 입장권을 다시 보내 드려요",
            inviterMasked + "님이 초대했어요.\n\n"
            + "아래 링크를 휴대폰에서 열고 본인 확인을 마치면 입장권이 활성화됩니다. 처음 받은 링크와 같은 링크예요.\n\n"
            + url + claimNote());
        if (mailed) tickets.recordDelivery(invited.id, "EMAIL");
        ActivityLog.emit(Activity.of(Activity.INVITE, "invite.resent").ok(mailed).session(invited.sessionId)
            .ticket(invited.id).summary(invited.issuedToEmail).put("email", invited.issuedToEmail));
        return mailed;
    }

    /** What the issuing transaction leaves for the messages that follow it. */
    private record Issued(long id, String ref, String seat, String recipient, String phone, String url,
            Timestamp claimExpiresAt, EventSession session) {}

    /**
     * Mails and texts a link, and says how it went: "LINK" means nobody was notified and
     * the operator has to hand the link over.
     */
    private String deliver(EventSession session, String recipient, String phone, String url,
            String subject, String body) {
        boolean mailed = mail.send(recipient, subject, body);
        String delivered = mailed ? "EMAIL" : "LINK";
        if (phone != null && sms.send(phone, smsText(session, url))) {
            delivered = mailed ? "EMAIL+SMS" : "SMS";
        }
        return delivered;
    }

    private Issued create(long sessionId, String email, String seat, String tier,
            String rawPhone, Map<String, String> values) {
        String recipient = EmailOtpService.normalize(email);
        String phone = normalizePhone(rawPhone);
        // Held until the ticket is in: two issues to one address, or an issue racing a
        // transfer to it, would otherwise both pass the check below.
        EventSession session = sessions.lockById(sessionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "이벤트를 찾을 수 없어요."));
        // One ticket per address per event. Two links to the same inbox are two links
        // that cannot both be used - the second binds a device, the first is left dead
        // in a mailbox - and on the door it reads as one person holding two entries.
        if (!tickets.findLiveBySessionAndEmail(sessionId, recipient).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                recipient + " 주소로 발급된 입장권이 이미 있어요. 기존 입장권을 삭제하거나 비활성화한 뒤 다시 발급해 주세요.");
        }

        // The organiser's fields decide what a ticket may say. Seat and tier are read
        // from their own field when one is configured, so a catalogue is enforced the
        // same way whatever the field is called.
        Map<String, String> extra = new LinkedHashMap<>();
        for (TicketField field : fields.findBySession(sessionId)) {
            String raw = field.seat() ? firstOf(seat, values.get(field.label))
                : field.tier() ? firstOf(tier, values.get(field.label))
                : values.get(field.label);
            String value = fromCatalogue(field.values(), raw, field.label);
            if (value == null) continue;
            if (field.seat()) seat = value;
            else if (field.tier()) tier = value;
            else extra.put(field.label, value);
        }
        String attributes = extra.isEmpty() ? null : new ObjectMapper().writeValueAsString(extra);
        String token = Secrets.randomToken(16);
        String ref = Secrets.randomAlnum(12);
        Timestamp claimExpiresAt = Timestamp.from(
            Instant.now().plus(properties.getClaimTtlHours(), ChronoUnit.HOURS));
        long id;
        try {
            id = tickets.insert(sessionId, ref, tokenHmac(token), cipher.seal(token), seat, tier,
                attributes, recipient, phone, claimExpiresAt);
        } catch (org.springframework.dao.DataIntegrityViolationException taken) {
            // (session_id, seat) is unique, so this is the seat already being spoken for.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                seat == null ? "입장권을 발급하지 못했어요." : seat + " 좌석은 이미 발급되었어요.");
        }
        admissions.create(id, sessionId);
        return new Issued(id, ref, seat, recipient, phone, urlFor(sessionId, token), claimExpiresAt, session);
    }

    /**
     * Issues a fresh link for a ticket and returns it once.
     *
     * <p>Only a keyed hash of the token is stored, so an existing link cannot be looked
     * up again - showing one always means minting a new one, and the previous link stops
     * resolving immediately. That is the cost of not keeping live links in the database.
     */
    public Map<String, Object> reissueForConsole(long ticketId, boolean notify) {
        Reissued reissued = transactions.execute(status -> {
            Ticket ticket = tickets.lockById(ticketId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "입장권을 찾을 수 없어요."));
            EventSession session = requireSession(ticket.sessionId);
            String recipient = ticket.holderEmail != null ? ticket.holderEmail : ticket.issuedToEmail;
            String token = rotate(ticket, recipient);
            Timestamp claimExpiresAt = tickets.findById(ticket.id).orElseThrow().claimExpiresAt;
            return new Reissued(ticket, session, recipient, urlFor(ticket.sessionId, token), claimExpiresAt);
        });
        Ticket ticket = reissued.ticket;
        String delivered = notify ? deliver(reissued.session, reissued.recipient, ticket.phone, reissued.url,
            "[" + reissued.session.name + "] 입장권 링크",
            "아래 링크를 휴대폰에서 열고 등록해 주세요. 이전 링크는 더 이상 사용할 수 없습니다.\n\n" + reissued.url)
            : "LINK";
        tickets.recordDelivery(ticket.id, delivered);
        ActivityLog.emit(Activity.of(Activity.TICKET, "ticket.reissued_by_console").session(ticket.sessionId)
            .ticket(ticket.id).result(delivered).summary(reissued.recipient)
            .put("ticketRef", ticket.ticketRef).put("email", reissued.recipient).put("notify", notify));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ticketId", ticket.id);
        result.put("ticketRef", ticket.ticketRef);
        result.put("url", reissued.url);
        result.put("deliveredVia", delivered);
        result.put("notified", notify);
        result.put("claimExpiresAt", reissued.claimExpiresAt.toInstant().toString());
        return result;
    }

    private record Reissued(Ticket ticket, EventSession session, String recipient, String url,
            Timestamp claimExpiresAt) {}

    /**
     * Drops the binding and mints a new token for a ticket the caller has locked, and
     * returns the token. Shared by the holder's recovery and the console's re-issue.
     *
     * <p>A transfer still open on the ticket is cancelled in the same transaction. The
     * rotation puts the ticket back to ISSUED, which is no longer "transfer pending":
     * left open, the recipient could still accept it and both the recovered link and the
     * claim link would admit somebody.
     */
    private String rotate(Ticket locked, String recipient) {
        for (Transfer open : transfers.lockPendingByTicket(locked.id)) {
            if (!transfers.close(open.id, "CANCELLED")) continue;
            AfterCommit.run(() -> mail.send(open.toEmail, "입장권 양도가 취소되었어요",
                "입장권이 재발급되어 양도가 취소되었습니다. 받은 링크는 더 이상 사용할 수 없어요."));
        }
        tickets.unbind(locked.id);
        presentations.revokeFor(locked.id);
        String token = Secrets.randomToken(16);
        Timestamp claimExpiresAt = Timestamp.from(
            Instant.now().plus(properties.getClaimTtlHours(), ChronoUnit.HOURS));
        tickets.rotateToken(locked.id, tokenHmac(token), cipher.seal(token), recipient,
            claimExpiresAt, true);
        // The phone that held it stops hearing about it now, not when it next reconnects.
        live.dropTicket(locked.id);
        return token;
    }

    /**
     * Sends tickets their own links again, unchanged: nothing is rotated, a registered
     * holder keeps their device, and the mail is the reminder the operator meant. A ticket
     * that is off, or was issued before links were kept, is skipped and said so.
     */
    public Map<String, Object> resendLinks(long sessionId, java.util.List<Long> ticketIds) {
        return resendLinks(sessionId, ticketIds, false);
    }

    /** @param first the tickets were issued without mail and this is their first: worded as the ticket arriving. */
    public Map<String, Object> resendLinks(long sessionId, java.util.List<Long> ticketIds, boolean first) {
        EventSession session = requireSession(sessionId);
        int sent = 0;
        java.util.List<Map<String, Object>> skipped = new java.util.ArrayList<>();
        for (Long ticketId : ticketIds) {
            Ticket ticket = tickets.findById(ticketId).filter(t -> t.sessionId == sessionId).orElse(null);
            String reason = null;
            String token = null;
            if (ticket == null) reason = "이 이벤트의 입장권이 아니에요.";
            else if (ticket.revoked()) reason = "비활성화된 입장권이에요.";
            else if ((token = cipher.open(ticket.tokenCipher)) == null) {
                reason = "링크를 보관하기 전에 발급되어 다시 보낼 수 없어요. 새 링크로 재발급해 주세요.";
            }
            if (reason == null) {
                String url = urlFor(sessionId, token);
                String recipient = ticket.holderEmail != null ? ticket.holderEmail : ticket.issuedToEmail;
                boolean mailed = first
                    ? mail.send(recipient, "[" + session.name + "] 입장권이 발급되었어요",
                        "아래 링크를 휴대폰에서 열고 본인 확인을 마치면 입장권이 활성화됩니다.\n\n" + url
                        + "\n\n링크를 다른 사람에게 전달하지 마세요.")
                    : mail.send(recipient, "[" + session.name + "] 입장권 링크를 다시 보내 드려요",
                        "아래 링크를 휴대폰에서 열어 주세요. 처음 받은 링크와 같은 링크입니다.\n\n" + url
                        + "\n\n링크를 다른 사람에게 전달하지 마세요.");
                boolean texted = ticket.phone != null && sms.send(ticket.phone, smsText(session, url));
                if (mailed || texted) {
                    tickets.recordDelivery(ticket.id, mailed && texted ? "EMAIL+SMS" : mailed ? "EMAIL" : "SMS");
                    sent++;
                    ActivityLog.emit(Activity.of(Activity.TICKET, first ? "ticket.mailed_first" : "ticket.resent")
                        .session(sessionId).ticket(ticket.id).summary(recipient)
                        .put("ticketRef", ticket.ticketRef).put("email", recipient).put("mailed", mailed)
                        .put("texted", texted));
                    continue;
                }
                reason = "메일·문자를 보내지 못했어요.";
            }
            ActivityLog.emit(Activity.of(Activity.TICKET, first ? "ticket.mailed_first" : "ticket.resent")
                .failed("skipped").session(sessionId).ticket(ticketId).summary(reason).put("reason", reason));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ticketId", ticketId);
            row.put("ticketRef", ticket == null ? null : ticket.ticketRef);
            row.put("reason", reason);
            skipped.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sent", sent);
        result.put("skipped", skipped);
        return result;
    }

    String smsText(EventSession session, String url) {
        return "[" + session.name + "] 입장권이 발급되었어요. 휴대폰에서 열고 등록해 주세요.\n" + url;
    }

    /** The value the operator sent, whichever of the two ways they sent it. */
    private static String firstOf(String direct, String byLabel) {
        return direct != null && !direct.isBlank() ? direct : byLabel;
    }

    /**
     * Keeps an issued value inside the field's list when one is defined. A field with no
     * values takes free text, which is what an organiser who has not listed every table
     * number needs.
     */
    private static String fromCatalogue(java.util.List<String> catalogue, String value, String label) {
        String trimmed = value == null ? null : value.trim();
        if (trimmed == null || trimmed.isEmpty()) return null;
        if (!catalogue.isEmpty() && !catalogue.contains(trimmed)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "이 이벤트의 " + label + " 목록에 없는 값이에요: " + trimmed);
        }
        return trimmed;
    }

    /** Digits only, so a number typed with dashes or spaces still matches. */
    /**
     * Whether what a holder typed is the email or the phone number this ticket went to.
     *
     * @return null when it is, else what to tell them
     */
    public static String contactMismatch(String typed, String email, String phone) {
        String value = typed == null ? "" : typed.strip();
        if (value.isEmpty()) return "입장권을 받은 이메일이나 휴대폰 번호를 입력해 주세요.";
        if (value.contains("@")) {
            return email != null && EmailOtpService.normalize(value).equalsIgnoreCase(email) ? null
                : "입장권을 받은 이메일과 일치하지 않아요. 받으신 주소를 다시 확인해 주세요.";
        }
        String digits = phoneDigits(value);
        if (digits.length() < 9) return "이메일이나 휴대폰 번호를 다시 확인해 주세요.";
        if (phone == null) return "이 입장권에는 휴대폰 번호가 없어요. 입장권을 받은 이메일을 입력해 주세요.";
        return digits.equals(phoneDigits(phone)) ? null
            : "입장권을 받은 휴대폰 번호와 일치하지 않아요. 번호를 다시 확인해 주세요.";
    }

    /** A Korean number as it is dialled at home: +82 10 ... and 010 ... are one number. */
    private static String phoneDigits(String raw) {
        String digits = raw.replaceAll("\\D", "");
        return digits.startsWith("82") && digits.length() >= 11 ? "0" + digits.substring(2) : digits;
    }

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
    public Map<String, Object> reissue(Ticket resolved) {
        // Read again under the lock: the ticket may have been transferred, revoked or
        // recovered since the page resolved it, and the checks below are about now.
        Ticket ticket = tickets.lockById(resolved.id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "입장권을 찾을 수 없어요."));
        if (ticket.revoked()) {
            throw new ResponseStatusException(HttpStatus.GONE, "사용할 수 없는 입장권이에요.");
        }
        // The new link goes only where the ticket already went: whoever asked for it, it
        // lands with the holder.
        String recipient = ticket.holderEmail != null ? ticket.holderEmail : ticket.issuedToEmail;
        if (ticket.reissueCount >= properties.getRebindMaxPerTicket()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "재발급 가능 횟수를 모두 사용했어요. 안내 데스크에서 도움을 받아 주세요.");
        }
        EventSession session = requireSession(ticket.sessionId);
        String url = urlFor(ticket.sessionId, rotate(ticket, recipient));
        String phone = ticket.phone;
        AfterCommit.run(() -> {
            mail.send(recipient, "[" + session.name + "] 입장권 재발급 링크",
                "새 기기에서 아래 링크를 열고 다시 등록해 주세요. 이전 링크와 이전 기기의 패스키는 지금부터 사용할 수 없습니다.\n\n"
                + url + "\n\n요청하지 않았다면 즉시 안내 데스크에 알려 주세요.");
            if (phone != null) sms.send(phone, smsText(session, url));
        });

        ActivityLog.emit(Activity.of(Activity.TICKET, "ticket.reissued_by_holder").session(ticket.sessionId)
            .ticket(ticket.id).summary(recipient).put("email", recipient)
            .put("reissueCount", ticket.reissueCount + 1));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reissued", true);
        result.put("sentTo", mask(recipient));
        result.put("remaining", Math.max(0, properties.getRebindMaxPerTicket() - ticket.reissueCount - 1));
        return result;
    }

    /**
     * Removes a ticket outright.
     *
     * <p>Revoking is the everyday answer: it stops the ticket working and keeps the row
     * that says it existed. Deleting is for a ticket issued by mistake - the wrong
     * address, a duplicate - and it takes the ledger with it, so a ticket that has
     * already walked through a gate is refused unless the caller insists.
     */
    @Transactional
    public Map<String, Object> delete(long ticketId, boolean force) {
        Ticket ticket = tickets.lockById(ticketId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "입장권을 찾을 수 없어요."));
        int movements = admissions.countMovements(ticket.id);
        if (movements > 0 && !force) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "이 입장권은 게이트를 " + movements + "번 통과한 기록이 있어요. "
                + "기록까지 함께 지우려면 다시 한 번 확인해 주세요.");
        }
        presentations.revokeFor(ticket.id);
        tickets.delete(ticket.id);
        ActivityLog.emit(Activity.of(Activity.TICKET, "ticket.deleted").session(ticket.sessionId).ticket(ticket.id)
            .summary(ticket.issuedToEmail).put("ticketRef", ticket.ticketRef).put("email", ticket.issuedToEmail)
            .put("holder", ticket.holderEmail).put("status", ticket.status).put("seat", ticket.seat)
            .put("tier", ticket.tier).put("movements", movements).put("force", force));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", true);
        result.put("ticketId", ticket.id);
        result.put("ticketRef", ticket.ticketRef);
        result.put("movements", movements);
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
        ActivityLog.emit(Activity.of(Activity.TICKET, revoked ? "ticket.revoked" : "ticket.restored")
            .session(updated.sessionId).ticket(updated.id).result(updated.status)
            .put("ticketRef", updated.ticketRef).put("before", ticket.status));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ticketId", updated.id);
        result.put("ticketRef", updated.ticketRef);
        result.put("status", updated.status);
        return result;
    }

    public EventSession requireSession(long id) {
        return sessions.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "이벤트를 찾을 수 없어요."));
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
        ActivityContext.holder(ticket.id, ticket.sessionId,
            claim != null ? claim.toEmail : ticket.holderEmail != null ? ticket.holderEmail : ticket.issuedToEmail);
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
        return view(resolved, true);
    }

    /**
     * What the ticket page shows. A registered ticket opened by a browser that has not
     * answered its passkey gets only the event it is for, and a request to prove it.
     */
    public Map<String, Object> view(Resolved resolved, boolean viewer) {
        Ticket ticket = resolved.ticket;
        EventSession session = requireSession(ticket.sessionId);
        // A recipient opening a transfer link has not registered anything yet, whatever
        // binding the sender may still hold.
        boolean claimed = !resolved.viaTransfer() && ticket.claimed();
        if (claimed && !viewer) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("name", session.name);
            event.put("startsAt", session.startsAt.toInstant().toString());
            event.put("venue", session.venue);
            Map<String, Object> locked = new LinkedHashMap<>();
            locked.put("claimed", true);
            locked.put("locked", true);
            locked.put("role", "HOLDER");
            locked.put("event", event);
            return locked;
        }
        Presence presence = admissions.find(ticket.id).orElse(null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ticketRef", ticket.ticketRef);
        result.put("claimed", claimed);
        result.put("locked", false);
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
        result.put("invitation", resolved.viaTransfer() ? null : invitation(ticket));

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("name", session.name);
        event.put("venue", session.venue);
        event.put("startsAt", session.startsAt.toInstant().toString());
        event.put("gateOpensAt", session.gateOpensAt == null ? null : session.gateOpensAt.toInstant().toString());
        event.put("exitScanRequired", session.exitScanRequired);
        event.put("reentryMode", session.reentryMode);
        // Whether registering asks for the email or phone the ticket went to.
        event.put("claimSkipCheck", session.claimSkipCheck);
        // Which of the two the holder can type: a ticket issued without a phone takes email only.
        event.put("claimByPhone", !resolved.viaTransfer() && ticket.phone != null);
        // An event that does not admit by face says nothing about faces on its tickets.
        event.put("faceEnabled", session.faceEnabled);
        // The phone asks for its position only when the event will look at it.
        event.put("geoCheck", GeoCheck.active(session));
        // The days this ticket admits and where it stands today; null without a schedule.
        event.put("schedule", schedule.holderView(session, ticket, Instant.now()));
        result.put("event", event);

        Map<String, Object> state = new LinkedHashMap<>();
        if (resolved.viaTransfer()) {
            // The recipient has proved nothing yet, and what the sender has done with the
            // ticket - where they are, what they collected - is the sender's business.
            state.put("inside", false);
            state.put("entryCount", 0);
            state.put("reentryRemaining", null);
            state.put("reentryUntil", null);
            result.put("presence", state);
            result.put("coupons", java.util.List.of());
            return result;
        }
        state.put("inside", presence != null && presence.inside());
        state.put("entryCount", presence == null ? 0 : presence.entryCount);
        state.put("reentryRemaining", presence == null ? null : reentryRemaining(session, presence));
        state.put("reentryUntil", presence == null || presence.lastExitAt == null || !session.reentryAllowed()
            ? null
            : presence.lastExitAt.toInstant().plus(session.reentryGraceMinutes, ChronoUnit.MINUTES).toString());
        result.put("presence", state);
        // What this ticket can collect while it is inside. Empty for most events, so it
        // costs nothing to carry, and the screen shows nothing when there is nothing.
        result.put("coupons", coupons.forTicket(session.id, ticket.id));
        return result;
    }

    /**
     * Who invited this ticket, masked, and how many are in that group now: what the
     * invitee's page says even before they register. Nothing else about the group - not
     * who the others are - is said to a link that has not proved its holder.
     */
    private Map<String, Object> invitation(Ticket ticket) {
        if (!ticket.invitee || ticket.invitedBy == null) return null;
        Ticket inviter = tickets.findById(ticket.invitedBy).orElse(null);
        if (inviter == null) return null;
        long size = (inviter.revoked() ? 0 : 1)
            + tickets.findInvitedBy(inviter.id).stream().filter(member -> !member.revoked()).count();
        Map<String, Object> invitation = new LinkedHashMap<>();
        invitation.put("by", mask(inviter.holderEmail != null ? inviter.holderEmail : inviter.issuedToEmail));
        invitation.put("groupSize", size);
        return invitation;
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
