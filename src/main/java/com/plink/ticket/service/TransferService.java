package com.plink.ticket.service;

import com.plink.ticket.config.TicketProperties;
import com.plink.ticket.model.EventSession;
import com.plink.ticket.model.Presence;
import com.plink.ticket.model.Ticket;
import com.plink.ticket.model.Transfer;
import com.plink.ticket.repository.AdmissionRepository;
import com.plink.ticket.repository.EventSessionRepository;
import com.plink.ticket.repository.FaceRepository;
import com.plink.ticket.repository.TicketRepository;
import com.plink.ticket.repository.TransferRepository;
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
 * Transfer moves the right, never the identity. The sender proves they hold the ticket
 * with a user-verifying assertion, the recipient proves their own mailbox and registers
 * their own passkey, and the sender's URL stops resolving the moment that completes.
 *
 * <p>The ticket keeps the sender's token while the transfer is pending, which is what
 * leaves the sender able to cancel; the recipient's claim link is a separate token that
 * only becomes the ticket's own once accepted.
 *
 * <p>Every path that ends a transfer - accepting, cancelling, expiring, and a recovery
 * or console re-issue in {@link TicketService} - locks the ticket row first and the
 * transfer row second, and closes the transfer only if it is still pending. Whichever
 * gets there first wins; the other finds nothing left to do.
 */
@Service
public class TransferService {
    static final int CLAIM_TTL_HOURS = 24;

    private final TicketRepository tickets;
    private final TransferRepository transfers;
    private final AdmissionRepository admissions;
    private final EventSessionRepository sessions;
    private final FaceRepository faces;
    private final PresentationService presentations;
    private final TicketService ticketService;
    private final EmailSender mail;
    private final TicketProperties properties;
    private final TextCipher cipher;

    private final com.plink.ticket.live.LiveEvents live;

    public TransferService(TicketRepository tickets, TransferRepository transfers,
            AdmissionRepository admissions, EventSessionRepository sessions, FaceRepository faces,
            PresentationService presentations, TicketService ticketService, EmailSender mail,
            TicketProperties properties, TextCipher cipher, com.plink.ticket.live.LiveEvents live) {
        this.live = live;
        this.tickets = tickets;
        this.transfers = transfers;
        this.admissions = admissions;
        this.sessions = sessions;
        this.faces = faces;
        this.presentations = presentations;
        this.ticketService = ticketService;
        this.mail = mail;
        this.properties = properties;
        this.cipher = cipher;
    }

    /** Called only after the holder's passkey assertion has been verified. */
    @Transactional
    public Map<String, Object> initiate(Ticket ticket, String rawRecipient, String ip, String userAgent) {
        String recipient = EmailOtpService.normalize(rawRecipient);
        Ticket locked = tickets.lockById(ticket.id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "입장권을 찾을 수 없어요."));
        EventSession session = ticketService.requireSession(locked.sessionId);

        // Checked before the status test: a ticket mid-transfer is not "unbound", and
        // saying so would send the sender looking for the wrong problem.
        if (transfers.findPendingByTicket(locked.id).filter(Transfer::open).isPresent()) {
            throw refuse("이미 진행 중인 양도가 있어요. 먼저 취소해 주세요.");
        }
        if (!locked.bound()) {
            throw refuse("등록이 완료된 입장권만 양도할 수 있어요.");
        }
        if (recipient.equalsIgnoreCase(locked.holderEmail)) {
            throw refuse("현재 보유자와 같은 이메일이에요.");
        }
        // One live ticket per address per event holds for transfers as much as for
        // issuing: the recipient would otherwise end up with two entries.
        if (!tickets.findLiveBySessionAndEmail(locked.sessionId, recipient).isEmpty()) {
            throw refuse("받는 분이 이미 이 이벤트의 입장권을 가지고 있어요.");
        }
        if (locked.transferCount >= session.transferMax) {
            throw refuse("양도 가능 횟수를 모두 사용했어요.");
        }
        Instant closesAt = session.startsAt.toInstant()
            .minus(session.transferClosesMinutesBefore, ChronoUnit.MINUTES);
        if (Instant.now().isAfter(closesAt)) {
            throw refuse("양도 마감 시간이 지났어요.");
        }
        Presence presence = admissions.find(locked.id).orElse(null);
        if (presence != null && presence.inside()) {
            throw refuse("장내에 있는 동안에는 양도할 수 없어요.");
        }
        if (presence != null && presence.entryCount > 0 && !session.transferAfterFirstEntry) {
            throw refuse("이미 입장한 입장권은 양도할 수 없어요.");
        }

        // A pending row whose window has already run out but that the sweep has not
        // reached yet is closed here, so the ticket never carries two pending transfers.
        for (Transfer stale : transfers.lockPendingByTicket(locked.id)) {
            transfers.close(stale.id, "EXPIRED");
        }

        // The right is locked here: any live QR dies immediately, and the status keeps
        // the gate from admitting on a grant issued a moment earlier.
        presentations.revokeFor(locked.id);
        tickets.updateStatus(locked.id, "TRANSFER_PENDING");

        String claimToken = Secrets.randomToken(16);
        Timestamp expiresAt = Timestamp.from(Instant.now().plus(CLAIM_TTL_HOURS, ChronoUnit.HOURS));
        transfers.insert(locked.id, locked.holderEmail, recipient, ticketService.tokenHmac(claimToken),
            cipher.seal(claimToken), snapshot(session), ip, userAgent, expiresAt);

        String url = ticketService.urlFor(locked.sessionId, claimToken);
        String sender = locked.holderEmail;
        AfterCommit.run(() -> {
            mail.send(recipient, "[" + session.name + "] 입장권을 받았어요",
                "아래 링크를 휴대폰에서 열고 본인 확인을 마치면 입장권이 회원님 앞으로 넘어옵니다.\n\n" + url
                + "\n\n" + CLAIM_TTL_HOURS + "시간 안에 등록하지 않으면 양도가 취소되고 보낸 사람에게 돌아갑니다.");
            mail.send(sender, "[" + session.name + "] 입장권 양도를 요청했어요",
                TicketService.mask(recipient) + " 님에게 양도 링크를 보냈습니다.\n"
                + "상대가 등록을 마치기 전까지는 입장권 화면에서 취소할 수 있어요.\n"
                + "본인이 요청하지 않았다면 즉시 취소하고 안내 데스크에 알려 주세요.");
        });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "PENDING");
        result.put("toEmail", TicketService.mask(recipient));
        result.put("expiresAt", expiresAt.toInstant().toString());
        return result;
    }

    @Transactional
    public Map<String, Object> cancel(Ticket ticket) {
        // The ticket first, as on every other path that ends a transfer: an acceptance
        // racing this cancel holds the same row, and whoever commits second finds the
        // transfer already closed.
        Ticket locked = tickets.lockById(ticket.id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "입장권을 찾을 수 없어요."));
        Transfer transfer = transfers.lockPendingByTicket(locked.id).stream().findFirst()
            .orElseThrow(() -> refuse("진행 중인 양도가 없어요."));
        if (!transfers.close(transfer.id, "CANCELLED")) {
            throw refuse("진행 중인 양도가 없어요.");
        }
        // A ticket switched off while the transfer was open stays off.
        if ("TRANSFER_PENDING".equals(locked.status)) {
            tickets.updateStatus(locked.id, "BOUND");
        }
        AfterCommit.run(() -> mail.send(transfer.toEmail, "입장권 양도가 취소되었어요",
            "보낸 사람이 양도를 취소했습니다. 받은 링크는 더 이상 사용할 수 없어요."));
        return Map.of("status", "CANCELLED");
    }

    /**
     * Completes a transfer once the recipient has registered their own passkey. The
     * sender's token is replaced by the claim token here, so their link stops resolving.
     *
     * <p>The transfer was read when the recipient's page opened, which may be long
     * before this commits. Everything that decides whether it may still complete is
     * therefore read again here, under the ticket's lock: a recovery or cancel that got
     * in first has rotated the token or closed the transfer, and completing anyway would
     * leave two people holding a working ticket.
     */
    @Transactional
    public void accept(Transfer claimed, Ticket ticket, long holderId) {
        Ticket locked = tickets.lockById(ticket.id)
            .orElseThrow(() -> refuse("입장권을 찾을 수 없어요."));
        Transfer transfer = transfers.lockById(claimed.id)
            .filter(row -> row.ticketId == locked.id)
            .orElseThrow(() -> refuse("양도 정보를 찾을 수 없어요."));
        if (!transfer.pending()) {
            throw refuse("취소되었거나 이미 끝난 양도예요.");
        }
        if (transfer.expired()) {
            throw refuse("양도 기한이 지났어요. 보낸 분께 다시 요청해 주세요.");
        }
        if (!"TRANSFER_PENDING".equals(locked.status)) {
            throw refuse("지금은 양도를 받을 수 없는 입장권이에요.");
        }
        // The address rule is checked again under the event's lock: a ticket issued to
        // the recipient since the transfer started would otherwise make it two.
        sessions.lockById(locked.sessionId);
        if (tickets.findLiveBySessionAndEmail(locked.sessionId, transfer.toEmail).stream()
                .anyMatch(other -> other.id != locked.id)) {
            throw refuse("이미 이 이벤트의 입장권을 가지고 있어요. 한 이벤트에는 한 장만 받을 수 있어요.");
        }
        if (!transfers.accept(transfer.id)) {
            throw refuse("취소되었거나 이미 끝난 양도예요.");
        }
        // The sender keeps their passkey - it carries their other tickets. Only this
        // ticket changes hands.
        tickets.completeTransfer(locked.id, transfer.toTokenHmac, transfer.toTokenCipher,
            holderId, transfer.toEmail);
        // The face enrolled on this ticket is the sender's. Left in place, the gate
        // would still recognise them and walk them in on a ticket they gave away.
        int removed = faces.forget(locked.id);
        if (removed > 0) {
            faces.recordAttempt(locked.sessionId, locked.id, null, "TRANSFER", "DELETED",
                null, null, null, "양도로 이전 보유자 얼굴 삭제");
        }
        // The sender's open page stops hearing about the ticket it no longer holds.
        live.dropTicket(locked.id);
        // A new holder starts with a clean movement history for this session.
        admissions.resetPresence(locked.id);
        admissions.append(locked.id, locked.sessionId, null, null, "STAFF", "TRANSFERRED",
            TicketService.mask(transfer.fromEmail) + " → " + TicketService.mask(transfer.toEmail), null);
        AfterCommit.run(() -> mail.send(transfer.fromEmail, "입장권 양도가 완료되었어요",
            TicketService.mask(transfer.toEmail) + " 님이 등록을 마쳤습니다. 기존 링크와 기기의 패스키는 더 이상 사용할 수 없어요."));
    }

    /** Pending transfers that ran out of time return the ticket to the sender. */
    @Transactional
    public int expireStale() {
        int closed = 0;
        for (Transfer found : transfers.findExpired()) {
            // Locked in the same order as accept and cancel; one that completed or was
            // cancelled since the sweep read it is left alone.
            Ticket ticket = tickets.lockById(found.ticketId).orElse(null);
            Transfer transfer = transfers.lockById(found.id).orElse(null);
            if (transfer == null || !transfer.pending() || !transfer.expired()) continue;
            if (!transfers.close(transfer.id, "EXPIRED")) continue;
            if (ticket != null && "TRANSFER_PENDING".equals(ticket.status)) {
                tickets.updateStatus(ticket.id, "BOUND");
            }
            AfterCommit.run(() -> mail.send(transfer.fromEmail, "입장권 양도가 만료되었어요",
                "상대가 기한 안에 등록하지 않아 입장권이 회원님에게 돌아왔습니다."));
            closed++;
        }
        return closed;
    }

    public Map<String, Object> describe(Transfer transfer) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("status", transfer.status);
        row.put("toEmail", TicketService.mask(transfer.toEmail));
        row.put("fromEmail", TicketService.mask(transfer.fromEmail));
        row.put("expiresAt", transfer.expiresAt.toInstant().toString());
        return row;
    }

    private String snapshot(EventSession session) {
        return "transferMax=" + session.transferMax
            + ";closesMinutesBefore=" + session.transferClosesMinutesBefore
            + ";afterFirstEntry=" + session.transferAfterFirstEntry
            + ";reentryMode=" + session.reentryMode;
    }

    private ResponseStatusException refuse(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
