package com.plink.ticket;

import com.plink.ticket.model.Ticket;
import com.plink.ticket.model.Transfer;
import com.plink.ticket.repository.AdmissionRepository;
import com.plink.ticket.repository.EventSessionRepository;
import com.plink.ticket.repository.TicketRepository;
import com.plink.ticket.repository.TransferRepository;
import com.plink.ticket.service.TicketService;
import com.plink.ticket.service.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Transfer moves the right and nothing else: the recipient registers their own passkey
 * against their own mailbox, and the sender's link dies the moment that completes.
 */
// Isolated from ./.env: the suite must not depend on whichever origin, secret or
// face service a developer happens to have configured locally.
@SpringBootTest(properties = "spring.config.import=")
@Import(RecordingEmail.class)
class TransferFlowTest {

    @Autowired TicketService tickets;
    @Autowired TransferService transfers;
    @Autowired TicketRepository ticketRepository;
    @Autowired TransferRepository transferRepository;
    @Autowired EventSessionRepository sessions;
    @Autowired AdmissionRepository admissions;
    @Autowired RecordingEmail.Mailbox mailbox;

    private long newSession() {
        return sessions.insert("양도 테스트 " + Instant.now().toEpochMilli(), "테스트홀",
            Timestamp.from(Instant.now().plus(6, ChronoUnit.HOURS)),
            Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)));
    }

    private Ticket boundTicket(long sessionId, String email) {
        mailbox.clear();
        Map<String, Object> issued = tickets.issue(sessionId, email, "A-1", "STANDARD");
        long id = ((Number) issued.get("ticketId")).longValue();
        ticketRepository.bind(id, email);
        return ticketRepository.findById(id).orElseThrow();
    }

    private String tokenFromLastMail() {
        String url = mailbox.lastTicketUrl().orElseThrow();
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private String tokenFor(String recipient) {
        String url = mailbox.ticketUrlFor(recipient).orElseThrow();
        return url.substring(url.lastIndexOf('/') + 1);
    }

    @Test void aTransferLocksTheRightAndMailsTheRecipient() {
        long sessionId = newSession();
        Ticket ticket = boundTicket(sessionId, "sender@example.com");
        mailbox.clear();

        Map<String, Object> result = transfers.initiate(ticket, "Recipient@Example.com", "10.0.0.1", "test");
        assertEquals("PENDING", result.get("status"));
        assertEquals("r***@example.com", result.get("toEmail"));

        Ticket locked = ticketRepository.findById(ticket.id).orElseThrow();
        assertEquals("TRANSFER_PENDING", locked.status, "양도 중에는 입장할 수 없어야 합니다");
        assertTrue(mailbox.sent.stream().anyMatch(m -> m[0].equals("recipient@example.com")));
        assertTrue(mailbox.sent.stream().anyMatch(m -> m[0].equals("sender@example.com")),
            "보낸 사람에게도 알림이 가야 합니다");
    }

    @Test void theRecipientLinkResolvesToTheSameTicketAsAClaim() {
        long sessionId = newSession();
        Ticket ticket = boundTicket(sessionId, "sender@example.com");
        mailbox.clear();
        transfers.initiate(ticket, "recipient@example.com", null, null);
        String claimToken = tokenFor("recipient@example.com");

        TicketService.Resolved resolved = tickets.resolve(sessionId, claimToken);
        assertTrue(resolved.viaTransfer());
        assertEquals(ticket.id, resolved.ticket.id);
        Map<String, Object> view = tickets.view(resolved);
        assertEquals("RECIPIENT", view.get("role"));
        assertEquals(Boolean.FALSE, view.get("claimed"), "받는 사람은 아직 등록 전입니다");
        assertEquals("r***@example.com", view.get("holderEmailMasked"));
    }

    @Test void acceptingRotatesTheTokenSoTheSenderLinkDies() {
        long sessionId = newSession();
        Ticket ticket = boundTicket(sessionId, "sender@example.com");
        String senderToken = tokenFromLastMail();
        mailbox.clear();
        transfers.initiate(ticket, "recipient@example.com", null, null);
        String claimToken = tokenFor("recipient@example.com");

        Transfer transfer = transferRepository.findPendingByTicket(ticket.id).orElseThrow();
        transfers.accept(transfer, ticket);

        Ticket moved = ticketRepository.findById(ticket.id).orElseThrow();
        assertEquals("BOUND", moved.status);
        assertEquals("recipient@example.com", moved.holderEmail);
        assertEquals(1, moved.transferCount);

        assertThrows(ResponseStatusException.class, () -> tickets.resolve(sessionId, senderToken),
            "보낸 사람의 링크는 더 이상 열리지 않아야 합니다");
        TicketService.Resolved now = tickets.resolve(sessionId, claimToken);
        assertFalse(now.viaTransfer(), "수락 후에는 받은 사람의 링크가 곧 입장권입니다");
    }

    @Test void theSenderCanCancelUntilTheRecipientRegisters() {
        long sessionId = newSession();
        Ticket ticket = boundTicket(sessionId, "sender@example.com");
        String senderToken = tokenFromLastMail();
        transfers.initiate(ticket, "recipient@example.com", null, null);

        transfers.cancel(ticket);
        assertEquals("BOUND", ticketRepository.findById(ticket.id).orElseThrow().status);
        assertFalse(tickets.resolve(sessionId, senderToken).viaTransfer(),
            "취소 후 보낸 사람의 링크는 그대로 살아 있습니다");
        assertTrue(transferRepository.findPendingByTicket(ticket.id).isEmpty());
    }

    @Test void anUnclaimedTransferExpiresBackToTheSender() {
        long sessionId = newSession();
        Ticket ticket = boundTicket(sessionId, "sender@example.com");
        transfers.initiate(ticket, "recipient@example.com", null, null);
        Transfer transfer = transferRepository.findPendingByTicket(ticket.id).orElseThrow();

        // Pull the window into the past rather than waiting a day for it.
        transferRepository.close(transfer.id, "PENDING");
        ticketRepository.findById(ticket.id).orElseThrow();
        expire(transfer.id);

        assertEquals(1, transfers.expireStale());
        assertEquals("BOUND", ticketRepository.findById(ticket.id).orElseThrow().status);
        assertEquals("EXPIRED", transferRepository.findByClaimToken(transfer.toTokenHmac).orElseThrow().status);
    }

    @Test void policyRefusalsAreExplicit() {
        long sessionId = newSession();
        Ticket ticket = boundTicket(sessionId, "sender@example.com");

        assertTrue(assertThrows(ResponseStatusException.class,
            () -> transfers.initiate(ticket, "sender@example.com", null, null))
            .getReason().contains("같은 이메일"));

        transfers.initiate(ticket, "first@example.com", null, null);
        assertTrue(assertThrows(ResponseStatusException.class,
            () -> transfers.initiate(ticket, "second@example.com", null, null))
            .getReason().contains("진행 중인 양도"));
    }

    @Test void transferCountIsCapped() {
        long sessionId = newSession();
        sessions.updateTransferPolicy(sessionId, 0, 120, false);
        Ticket ticket = boundTicket(sessionId, "sender@example.com");
        assertTrue(assertThrows(ResponseStatusException.class,
            () -> transfers.initiate(ticket, "someone@example.com", null, null))
            .getReason().contains("양도 가능 횟수"));
    }

    @Test void theWindowClosesBeforeDoors() {
        long sessionId = sessions.insert("곧 시작하는 회차", null,
            Timestamp.from(Instant.now().plus(30, ChronoUnit.MINUTES)), null);
        Ticket ticket = boundTicket(sessionId, "sender@example.com");
        assertTrue(assertThrows(ResponseStatusException.class,
            () -> transfers.initiate(ticket, "someone@example.com", null, null))
            .getReason().contains("양도 마감"));
    }

    @Test void aTicketInsideTheVenueCannotBeHandedOver() {
        long sessionId = newSession();
        Ticket ticket = boundTicket(sessionId, "sender@example.com");
        admissions.markInside(ticket.id, 1, 0, "gate-1");
        assertTrue(assertThrows(ResponseStatusException.class,
            () -> transfers.initiate(ticket, "someone@example.com", null, null))
            .getReason().contains("장내에 있는 동안"));
    }

    @Test void anAlreadyUsedTicketIsNotTransferableByDefault() {
        long sessionId = newSession();
        Ticket ticket = boundTicket(sessionId, "sender@example.com");
        admissions.markInside(ticket.id, 1, 0, "gate-1");
        admissions.markOutside(ticket.id, "gate-2");
        assertTrue(assertThrows(ResponseStatusException.class,
            () -> transfers.initiate(ticket, "someone@example.com", null, null))
            .getReason().contains("이미 입장한"));

        sessions.updateTransferPolicy(sessionId, 2, 120, true);
        assertEquals("PENDING",
            transfers.initiate(ticket, "someone@example.com", null, null).get("status"));
    }

    @Test void anUnboundTicketCannotBeTransferred() {
        long sessionId = newSession();
        Map<String, Object> issued = tickets.issue(sessionId, "nobody@example.com", "B-1", null);
        Ticket unbound = ticketRepository.findById(((Number) issued.get("ticketId")).longValue()).orElseThrow();
        assertTrue(assertThrows(ResponseStatusException.class,
            () -> transfers.initiate(unbound, "someone@example.com", null, null))
            .getReason().contains("등록이 완료된"));
    }

    @Test void theNewHolderStartsWithACleanMovementHistory() {
        long sessionId = newSession();
        sessions.updateTransferPolicy(sessionId, 2, 120, true);
        Ticket ticket = boundTicket(sessionId, "sender@example.com");
        admissions.markInside(ticket.id, 2, 1, "gate-1");
        admissions.markOutside(ticket.id, "gate-1");

        transfers.initiate(ticket, "recipient@example.com", null, null);
        Transfer transfer = transferRepository.findPendingByTicket(ticket.id).orElseThrow();
        transfers.accept(transfer, ticket);

        var presence = admissions.find(ticket.id).orElseThrow();
        assertEquals(0, presence.entryCount);
        assertEquals(0, presence.reentryCount);
        assertFalse(presence.inside());
    }

    /** Drags a pending transfer's deadline into the past. */
    private void expire(long transferId) {
        org.springframework.jdbc.core.JdbcTemplate jdbc =
            new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.update("UPDATE ticket_transfer SET expires_at = ?, status = 'PENDING' WHERE id = ?",
            Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)), transferId);
    }

    @Autowired javax.sql.DataSource dataSource;
}
