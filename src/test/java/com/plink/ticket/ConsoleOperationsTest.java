package com.plink.ticket;

import com.plink.ticket.model.Gate;
import com.plink.ticket.model.Ticket;
import com.plink.ticket.repository.EventSessionRepository;
import com.plink.ticket.repository.GateRepository;
import com.plink.ticket.repository.TicketRepository;
import com.plink.ticket.service.GateAuthService;
import com.plink.ticket.service.TextCipher;
import com.plink.ticket.service.Secrets;
import com.plink.ticket.service.TicketService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Import;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Console operations: handing over the link, re-issuing it, and deleting a session. */
// Isolated from ./.env: the suite must not depend on whichever origin, secret or
// face service a developer happens to have configured locally.
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:consoleoperationstest;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.config.import=", "plink.admin.email=", "plink.admin.password=", "plink.auth.base-url=http://localhost:3000"})
@Import(RecordingEmail.class)
class ConsoleOperationsTest {

    @Autowired TicketService tickets;
    @Autowired TicketRepository ticketRepository;
    @Autowired EventSessionRepository sessions;
    @Autowired RecordingEmail.Mailbox mailbox;
    @Autowired GateRepository gates;
    @Autowired com.plink.ticket.repository.HolderRepository holders;
    @Autowired GateAuthService gateAuth;
    @Autowired com.plink.ticket.controller.TicketAdminController adminController;
    @Autowired TextCipher cipher;
    @Autowired com.plink.ticket.service.TransferService transfers;

    private long newSession() {
        return sessions.insert("콘솔 테스트 " + Secrets.randomAlnum(6), null,
            Timestamp.from(Instant.now().plus(5, ChronoUnit.HOURS)), null);
    }

    private long holderFor(String email) {
        return holders.findByEmail(email).map(h -> h.id)
            .orElseGet(() -> holders.create(email, tickets.userHandleFor(email)));
    }

    private String tokenOf(String url) {
        return url.substring(url.lastIndexOf('/') + 1);
    }

    @Test void issuingHandsTheLinkBackOnce() {
        long sessionId = newSession();
        Map<String, Object> issued = tickets.issue(sessionId, "holder@example.com", "A-1", null, null);
        String url = String.valueOf(issued.get("url"));
        assertTrue(url.contains("/tickets/" + sessionId + "/"), url);
        // The link works, which is the point of returning it.
        assertEquals(issued.get("ticketId"), tickets.resolve(sessionId, tokenOf(url)).ticket.id);
    }

    @Test void reIssuingRotatesSoTheOldLinkStopsWorking() {
        long sessionId = newSession();
        Map<String, Object> issued = tickets.issue(sessionId, "holder@example.com", "A-1", null, null);
        long ticketId = ((Number) issued.get("ticketId")).longValue();
        String first = tokenOf(String.valueOf(issued.get("url")));

        Map<String, Object> again = tickets.reissueForConsole(ticketId, false);
        String second = tokenOf(String.valueOf(again.get("url")));

        assertNotEquals(first, second);
        assertEquals(ticketId, tickets.resolve(sessionId, second).ticket.id);
        assertThrows(ResponseStatusException.class, () -> tickets.resolve(sessionId, first),
            "이전 링크는 즉시 무효가 되어야 합니다");
    }

    @Test void reIssuingQuietlySendsNothing() {
        long sessionId = newSession();
        Map<String, Object> issued = tickets.issue(sessionId, "holder@example.com", null, null, null);
        mailbox.clear();
        tickets.reissueForConsole(((Number) issued.get("ticketId")).longValue(), false);
        assertTrue(mailbox.sent.isEmpty(), "notify=false면 아무것도 보내지 않습니다");
    }

    @Test void reIssuingWithNotifyMailsTheNewLink() {
        long sessionId = newSession();
        Map<String, Object> issued = tickets.issue(sessionId, "holder@example.com", null, null, null);
        mailbox.clear();
        Map<String, Object> again = tickets.reissueForConsole(((Number) issued.get("ticketId")).longValue(), true);
        assertEquals(String.valueOf(again.get("url")), mailbox.ticketUrlFor("holder@example.com").orElseThrow());
    }

    @Test void aPhoneNumberIsStoredWithoutItsFormatting() {
        long sessionId = newSession();
        Map<String, Object> issued = tickets.issue(sessionId, "holder@example.com", null, null, "010-1234-5678");
        Ticket ticket = ticketRepository.findById(((Number) issued.get("ticketId")).longValue()).orElseThrow();
        assertEquals("01012345678", ticket.phone);
        // No SMS provider is configured in tests, so delivery stays email-only.
        assertEquals("EMAIL", issued.get("deliveredVia"));
    }

    @Test void anImplausiblePhoneNumberIsRefused() {
        long sessionId = newSession();
        assertTrue(assertThrows(ResponseStatusException.class,
            () -> tickets.issue(sessionId, "holder@example.com", null, null, "123"))
            .getReason().contains("휴대폰 번호"));
    }

    @Test void aGateTokenStaysReadableToTheConsoleButNeverInTheClear() {
        long sessionId = newSession();
        String gateId = "g" + Secrets.randomAlnum(8);
        String token = Secrets.randomToken(24);
        gates.insert(gateId, sessionId, "정문", "A", "IN", gateAuth.hash(token), cipher.seal(token));

        Gate stored = gates.findById(gateId).orElseThrow();
        assertNotEquals(token, stored.tokenCipher, "평문으로 보관하지 않습니다");
        assertEquals(token, cipher.open(stored.tokenCipher), "콘솔에서는 다시 읽을 수 있어야 합니다");
    }

    @Test void oneGateBelongsToOneTerminal() {
        long sessionId = newSession();
        String gateId = "g" + Secrets.randomAlnum(8);
        String token = Secrets.randomToken(24);
        gates.insert(gateId, sessionId, null, null, "IN", gateAuth.hash(token), cipher.seal(token));

        assertEquals(gateId, gateAuth.authenticate(gateId, token, "tablet-1").id);
        // The same tablet keeps working; a second one is turned away.
        assertEquals(gateId, gateAuth.authenticate(gateId, token, "tablet-1").id);
        ResponseStatusException conflict = assertThrows(ResponseStatusException.class,
            () -> gateAuth.authenticate(gateId, token, "tablet-2"));
        assertTrue(conflict.getReason().contains("다른 단말에서 사용 중"), conflict.getReason());

        // Releasing it from the console lets the replacement in.
        gates.releaseDevice(gateId);
        assertEquals(gateId, gateAuth.authenticate(gateId, token, "tablet-2").id);
    }

    @Test void rotatingAGateTokenAlsoFreesTheTerminal() {
        long sessionId = newSession();
        String gateId = "g" + Secrets.randomAlnum(8);
        String first = Secrets.randomToken(24);
        gates.insert(gateId, sessionId, null, null, "IN", gateAuth.hash(first), cipher.seal(first));
        gateAuth.authenticate(gateId, first, "tablet-1");

        String second = Secrets.randomToken(24);
        gates.rotateToken(gateId, gateAuth.hash(second), cipher.seal(second));

        assertThrows(ResponseStatusException.class, () -> gateAuth.authenticate(gateId, first, "tablet-1"),
            "이전 토큰은 더 이상 통하지 않습니다");
        assertEquals(gateId, gateAuth.authenticate(gateId, second, "tablet-2").id);
    }

    @Test void aSeatOutsideTheCatalogueIsRefused() {
        long sessionId = newSession();
        sessions.updateCatalog(sessionId, "A-1\nA-2", "VIP\nR석");

        assertTrue(assertThrows(ResponseStatusException.class,
            () -> tickets.issue(sessionId, "holder@example.com", "Z-9", null, null))
            .getReason().contains("없는 좌석"));
        assertTrue(assertThrows(ResponseStatusException.class,
            () -> tickets.issue(sessionId, "holder@example.com", "A-1", "없는등급", null))
            .getReason().contains("없는 등급"));
        assertNotNull(tickets.issue(sessionId, "holder@example.com", "A-1", "VIP", null).get("ticketId"));
    }

    @Test void oneSeatCannotBeIssuedTwice() {
        long sessionId = newSession();
        sessions.updateCatalog(sessionId, "A-1", null);
        tickets.issue(sessionId, "first@example.com", "A-1", null, null);

        ResponseStatusException taken = assertThrows(ResponseStatusException.class,
            () -> tickets.issue(sessionId, "second@example.com", "A-1", null, null));
        assertTrue(taken.getReason().contains("이미 발급되었어요"), taken.getReason());
    }

    @Test void aSessionWithoutACatalogueTakesAnySeat() {
        long sessionId = newSession();
        assertNotNull(tickets.issue(sessionId, "holder@example.com", "무대 옆 보조석", "초대", null)
            .get("ticketId"));
    }

    @Test void aRevokedTicketStopsOpeningAndStopsAdmitting() {
        long sessionId = newSession();
        Map<String, Object> issued = tickets.issue(sessionId, "holder@example.com", "A-1", null, null);
        long ticketId = ((Number) issued.get("ticketId")).longValue();
        String token = tokenOf(String.valueOf(issued.get("url")));
        assertEquals(ticketId, tickets.resolve(sessionId, token).ticket.id);

        assertEquals("REVOKED", tickets.setRevoked(ticketId, true).get("status"));
        assertEquals(HttpStatus.GONE, assertThrows(ResponseStatusException.class,
            () -> tickets.resolve(sessionId, token)).getStatusCode(),
            "비활성화된 입장권의 링크는 열리지 않아야 합니다");

        // Turning it back on restores the state it had, and the same link works again.
        assertEquals("ISSUED", tickets.setRevoked(ticketId, false).get("status"));
        assertEquals(ticketId, tickets.resolve(sessionId, token).ticket.id);
    }

    @Test void reEnablingRestoresABoundTicketToBound() {
        long sessionId = newSession();
        Map<String, Object> issued = tickets.issue(sessionId, "holder@example.com", "B-1", null, null);
        long ticketId = ((Number) issued.get("ticketId")).longValue();
        ticketRepository.bind(ticketId, holderFor("holder@example.com"), "holder@example.com");

        tickets.setRevoked(ticketId, true);
        assertEquals("BOUND", tickets.setRevoked(ticketId, false).get("status"));
    }

    @Test void onlyARevokedTicketCanBeReEnabled() {
        long sessionId = newSession();
        Map<String, Object> issued = tickets.issue(sessionId, "holder@example.com", "C-1", null, null);
        long ticketId = ((Number) issued.get("ticketId")).longValue();
        assertTrue(assertThrows(ResponseStatusException.class,
            () -> tickets.setRevoked(ticketId, false)).getReason().contains("비활성화된 입장권이 아니"));
    }

    @Test void reEnablingKeepsATicketLockedWhileItsTransferIsStillOpen() {
        long sessionId = newSession();
        Map<String, Object> issued = tickets.issue(sessionId, "sender@example.com", "D-1", null, null);
        long ticketId = ((Number) issued.get("ticketId")).longValue();
        ticketRepository.bind(ticketId, holderFor("sender@example.com"), "sender@example.com");
        transfers.initiate(ticketRepository.findById(ticketId).orElseThrow(),
            "recipient@example.com", "127.0.0.1", "test");

        tickets.setRevoked(ticketId, true);
        // Restoring must not hand the ticket back to the sender: the recipient's claim
        // link is still live, and a BOUND ticket would let both of them in.
        assertEquals("TRANSFER_PENDING", tickets.setRevoked(ticketId, false).get("status"));
    }

    @Test void aBulkIssueReportsEachRowOnItsOwn() {
        long sessionId = newSession();
        sessions.updateCatalog(sessionId, "A-1,A-2", null);
        Map<String, Object> result = adminController.issueTickets(sessionId, Map.of(
            "notify", false,
            "rows", java.util.List.of(
                Map.of("email", "first@example.com", "seat", "A-1"),
                Map.of("email", "second@example.com", "seat", "A-1"),   // seat already gone
                Map.of("email", "not-an-address", "seat", "A-2"),
                Map.of("email", "third@example.com", "seat", "A-2"))));

        assertEquals(2, result.get("issued"));
        assertEquals(2, result.get("failed"));
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> rows =
            (java.util.List<Map<String, Object>>) result.get("results");
        assertEquals(Boolean.TRUE, rows.get(0).get("ok"));
        assertTrue(String.valueOf(rows.get(1).get("error")).contains("A-1"), "좌석 중복 사유가 보여야 합니다");
        assertEquals(Boolean.FALSE, rows.get(2).get("ok"));
        assertEquals(Boolean.TRUE, rows.get(3).get("ok"), "앞 행이 실패해도 뒷 행은 발급됩니다");
    }

    @Test void aQuietBulkIssueSendsNothing() {
        long sessionId = newSession();
        mailbox.clear();
        adminController.issueTickets(sessionId, Map.of("notify", false,
            "rows", java.util.List.of(Map.of("email", "quiet@example.com"))));
        assertTrue(mailbox.sent.isEmpty(), "보내지 않기로 했으면 메일이 나가면 안 됩니다");

        adminController.issueTickets(sessionId, Map.of("notify", true,
            "rows", java.util.List.of(Map.of("email", "loud@example.com"))));
        assertEquals(1, mailbox.sent.size());
    }

    @Test void deletingASessionTakesItsTicketsWithIt() {
        long sessionId = newSession();
        Map<String, Object> issued = tickets.issue(sessionId, "holder@example.com", "A-1", null, null);
        long ticketId = ((Number) issued.get("ticketId")).longValue();

        assertEquals(1, sessions.delete(sessionId));
        assertTrue(ticketRepository.findById(ticketId).isEmpty(), "입장권도 함께 지워집니다");
        assertTrue(sessions.findById(sessionId).isEmpty());
    }
}
