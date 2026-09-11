package com.plink.ticket.service;

import com.plink.ticket.config.TicketProperties;
import com.plink.ticket.model.EventSession;
import com.plink.ticket.model.Presence;
import com.plink.ticket.model.Ticket;
import com.plink.ticket.model.Transfer;
import com.plink.ticket.repository.AdmissionRepository;
import com.plink.ticket.repository.TicketPasskeyRepository;
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
 */
@Service
public class TransferService {
    static final int CLAIM_TTL_HOURS = 24;

    private final TicketRepository tickets;
    private final TransferRepository transfers;
    private final TicketPasskeyRepository passkeys;
    private final AdmissionRepository admissions;
    private final PresentationService presentations;
    private final TicketService ticketService;
    private final EmailSender mail;
    private final TicketProperties properties;

    public TransferService(TicketRepository tickets, TransferRepository transfers,
            TicketPasskeyRepository passkeys, AdmissionRepository admissions,
            PresentationService presentations, TicketService ticketService, EmailSender mail,
            TicketProperties properties) {
        this.tickets = tickets;
        this.transfers = transfers;
        this.passkeys = passkeys;
        this.admissions = admissions;
        this.presentations = presentations;
        this.ticketService = ticketService;
        this.mail = mail;
        this.properties = properties;
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

        // The right is locked here: any live QR dies immediately, and the status keeps
        // the gate from admitting on a grant issued a moment earlier.
        presentations.revokeFor(locked.id);
        tickets.updateStatus(locked.id, "TRANSFER_PENDING");

        String claimToken = Secrets.randomToken(16);
        Timestamp expiresAt = Timestamp.from(Instant.now().plus(CLAIM_TTL_HOURS, ChronoUnit.HOURS));
        transfers.insert(locked.id, locked.holderEmail, recipient, ticketService.tokenHmac(claimToken),
            snapshot(session), ip, userAgent, expiresAt);

        String url = ticketService.urlFor(locked.sessionId, claimToken);
        mail.send(recipient, "[" + session.name + "] 입장권을 받았어요",
            "아래 링크를 휴대폰에서 열고 본인 확인을 마치면 입장권이 회원님 앞으로 넘어옵니다.\n\n" + url
            + "\n\n" + CLAIM_TTL_HOURS + "시간 안에 등록하지 않으면 양도가 취소되고 보낸 사람에게 돌아갑니다.");
        mail.send(locked.holderEmail, "[" + session.name + "] 입장권 양도를 요청했어요",
            TicketService.mask(recipient) + " 님에게 양도 링크를 보냈습니다.\n"
            + "상대가 등록을 마치기 전까지는 입장권 화면에서 취소할 수 있어요.\n"
            + "본인이 요청하지 않았다면 즉시 취소하고 안내 데스크에 알려 주세요.");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "PENDING");
        result.put("toEmail", TicketService.mask(recipient));
        result.put("expiresAt", expiresAt.toInstant().toString());
        return result;
    }

    @Transactional
    public Map<String, Object> cancel(Ticket ticket) {
        Transfer transfer = transfers.findPendingByTicket(ticket.id)
            .orElseThrow(() -> refuse("진행 중인 양도가 없어요."));
        transfers.close(transfer.id, "CANCELLED");
        tickets.updateStatus(ticket.id, "BOUND");
        mail.send(transfer.toEmail, "입장권 양도가 취소되었어요",
            "보낸 사람이 양도를 취소했습니다. 받은 링크는 더 이상 사용할 수 없어요.");
        return Map.of("status", "CANCELLED");
    }

    /**
     * Completes a transfer once the recipient has registered their own passkey. The
     * sender's token is replaced by the claim token here, so their link stops resolving.
     */
    @Transactional
    public void accept(Transfer transfer, Ticket ticket) {
        transfers.accept(transfer.id);
        tickets.completeTransfer(ticket.id, transfer.toTokenHmac, transfer.toEmail);
        // A new holder starts with a clean movement history for this session.
        admissions.resetPresence(ticket.id);
        admissions.append(ticket.id, ticket.sessionId, null, null, "STAFF", "TRANSFERRED",
            TicketService.mask(transfer.fromEmail) + " → " + TicketService.mask(transfer.toEmail), null);
        mail.send(transfer.fromEmail, "입장권 양도가 완료되었어요",
            TicketService.mask(transfer.toEmail) + " 님이 등록을 마쳤습니다. 기존 링크와 기기의 패스키는 더 이상 사용할 수 없어요.");
    }

    /** Pending transfers that ran out of time return the ticket to the sender. */
    @Transactional
    public int expireStale() {
        int closed = 0;
        for (Transfer transfer : transfers.findExpired()) {
            transfers.close(transfer.id, "EXPIRED");
            tickets.findById(transfer.ticketId)
                .filter(ticket -> "TRANSFER_PENDING".equals(ticket.status))
                .ifPresent(ticket -> tickets.updateStatus(ticket.id, "BOUND"));
            mail.send(transfer.fromEmail, "입장권 양도가 만료되었어요",
                "상대가 기한 안에 등록하지 않아 입장권이 회원님에게 돌아왔습니다.");
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
