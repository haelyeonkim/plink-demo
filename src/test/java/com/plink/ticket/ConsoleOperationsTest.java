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
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired com.plink.ticket.repository.TicketFieldRepository fields;

    private long newSession() {
        return sessions.insert("콘솔 테스트 " + Secrets.randomAlnum(6), null,
            Timestamp.from(Instant.now().plus(5, ChronoUnit.HOURS)), null);
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<Map<String, Object>> rows(Map<String, Object> page) {
        return (java.util.List<Map<String, Object>>) page.get("items");
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

    /**
     * The console can be asked for the link again. Reading it must not be a disguised
     * re-issue: the same URL comes back and it still resolves.
     */
    @Test void theIssuedLinkCanBeReadBackWithoutRotating() {
        long sessionId = newSession();
        Map<String, Object> issued = tickets.issue(sessionId, "holder@example.com", "A-1", null, null);
        long ticketId = ((Number) issued.get("ticketId")).longValue();
        String url = String.valueOf(issued.get("url"));

        Map<String, Object> shown = adminController.ticketLink(ticketId);
        assertEquals(url, shown.get("url"), "보여 준 링크는 발급된 그 링크여야 합니다");
        assertEquals(url, String.valueOf(adminController.ticketLink(ticketId).get("url")),
            "두 번 봐도 같은 링크입니다");
        assertEquals(ticketId, tickets.resolve(sessionId, tokenOf(url)).ticket.id);
        assertEquals(0, ticketRepository.findById(ticketId).orElseThrow().reissueCount);

        // A re-issue moves it on, and the console then shows the new one.
        String next = String.valueOf(tickets.reissueForConsole(ticketId, false).get("url"));
        assertEquals(next, adminController.ticketLink(ticketId).get("url"));
        assertNotEquals(url, next);
    }

    /** Tickets issued before the link was kept have only the hash, and say so. */
    @Test void aTicketFromBeforeTheChangeSaysTheLinkIsGone() {
        long sessionId = newSession();
        long ticketId = ((Number) tickets.issue(sessionId, "holder@example.com", "B-2", null, null)
            .get("ticketId")).longValue();
        jdbc.update("UPDATE ticket SET token_cipher = NULL WHERE id = ?", ticketId);

        ResponseStatusException refused = assertThrows(ResponseStatusException.class,
            () -> adminController.ticketLink(ticketId));
        assertEquals(HttpStatus.NOT_FOUND, refused.getStatusCode());
        assertTrue(String.valueOf(refused.getReason()).contains("재발급"));
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
        gates.insert(gateId, sessionId, "정문", "A", "IN", gateAuth.hash(token), cipher.seal(token), gateAuth.tokenExpiry());

        Gate stored = gates.findById(gateId).orElseThrow();
        assertNotEquals(token, stored.tokenCipher, "평문으로 보관하지 않습니다");
        assertEquals(token, cipher.open(stored.tokenCipher), "콘솔에서는 다시 읽을 수 있어야 합니다");
    }

    @Test void oneGateBelongsToOneTerminal() {
        long sessionId = newSession();
        String gateId = "g" + Secrets.randomAlnum(8);
        String token = Secrets.randomToken(24);
        gates.insert(gateId, sessionId, null, null, "IN", gateAuth.hash(token), cipher.seal(token), gateAuth.tokenExpiry());

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
        gates.insert(gateId, sessionId, null, null, "IN", gateAuth.hash(first), cipher.seal(first), gateAuth.tokenExpiry());
        gateAuth.authenticate(gateId, first, "tablet-1");

        String second = Secrets.randomToken(24);
        gates.rotateToken(gateId, gateAuth.hash(second), cipher.seal(second), gateAuth.tokenExpiry());

        assertThrows(ResponseStatusException.class, () -> gateAuth.authenticate(gateId, first, "tablet-1"),
            "이전 토큰은 더 이상 통하지 않습니다");
        assertEquals(gateId, gateAuth.authenticate(gateId, second, "tablet-2").id);
    }

    /**
     * A terminal's token has an end date, and renewing it moves the date without
     * disturbing the tablet: the token, and the device holding the gate, both stand.
     */
    @Test void aGateTokenExpiresAndTheConsoleCanRenewIt() {
        long sessionId = newSession();
        String gateId = "g" + Secrets.randomAlnum(8);
        String token = Secrets.randomToken(24);
        gates.insert(gateId, sessionId, null, null, "IN", gateAuth.hash(token), cipher.seal(token),
            gateAuth.tokenExpiry());
        gateAuth.authenticate(gateId, token, "tablet-1");

        gates.renewToken(gateId, Timestamp.from(Instant.now().minus(1, ChronoUnit.MINUTES)));
        ResponseStatusException lapsed = assertThrows(ResponseStatusException.class,
            () -> gateAuth.authenticate(gateId, token, "tablet-1"));
        assertEquals(HttpStatus.UNAUTHORIZED, lapsed.getStatusCode());
        assertTrue(String.valueOf(lapsed.getReason()).contains("유효기간"), lapsed.getReason());

        adminController.renewGate(gateId, null);
        assertEquals(gateId, gateAuth.authenticate(gateId, token, "tablet-1").id,
            "갱신은 토큰도 단말 연결도 건드리지 않습니다");
        assertEquals("tablet-1", gates.findById(gateId).orElseThrow().boundDevice);

        // Zero days is a terminal that should not expire at all, and it is reversible.
        assertNull(adminController.renewGate(gateId, 0).get("tokenExpiresAt"));
        assertNull(gates.findById(gateId).orElseThrow().tokenExpiresAt);
        assertEquals(gateId, gateAuth.authenticate(gateId, token, "tablet-1").id);
        assertNotNull(adminController.renewGate(gateId, 7).get("tokenExpiresAt"));
    }

    /** Terminals registered before tokens had an end date keep working until renewed. */
    @Test void agateWithNoEndDateStillWorks() {
        long sessionId = newSession();
        String gateId = "g" + Secrets.randomAlnum(8);
        String token = Secrets.randomToken(24);
        gates.insert(gateId, sessionId, null, null, "IN", gateAuth.hash(token), cipher.seal(token), null);

        assertEquals(gateId, gateAuth.authenticate(gateId, token, "tablet-1").id);
        assertNotNull(adminController.renewGate(gateId, null).get("tokenExpiresAt"));
        assertNotNull(gates.findById(gateId).orElseThrow().tokenExpiresAt);
    }

    @Test void aSeatOutsideTheCatalogueIsRefused() {
        long sessionId = newSession();
        fields.insert(sessionId, "좌석", "SEAT", "A-1\nA-2");
        fields.insert(sessionId, "등급", "TIER", "VIP\nR석");

        assertTrue(assertThrows(ResponseStatusException.class,
            () -> tickets.issue(sessionId, "holder@example.com", "Z-9", null, null))
            .getReason().contains("좌석 목록에 없는 값"));
        assertTrue(assertThrows(ResponseStatusException.class,
            () -> tickets.issue(sessionId, "holder@example.com", "A-1", "없는등급", null))
            .getReason().contains("등급 목록에 없는 값"));
        assertNotNull(tickets.issue(sessionId, "holder@example.com", "A-1", "VIP", null).get("ticketId"));
    }

    /**
     * A field the organiser invented behaves like the two that were built in: its values
     * are offered, anything else is refused, and what was chosen rides on the ticket.
     */
    @Test void anOrganisersOwnFieldIsOfferedAndEnforced() {
        long sessionId = newSession();
        fields.insert(sessionId, "트랙", "CUSTOM", "디자인\n엔지니어링");
        fields.insert(sessionId, "식사", "CUSTOM", null);   // no list: free text

        assertTrue(assertThrows(ResponseStatusException.class,
            () -> adminController.issueTicket(sessionId, Map.of(
                "email", "holder@example.com", "values", Map.of("트랙", "마케팅"))))
            .getReason().contains("트랙 목록에 없는 값"));

        Map<String, Object> issued = adminController.issueTicket(sessionId, Map.of(
            "email", "holder@example.com",
            "values", Map.of("트랙", "디자인", "식사", "채식")));
        long ticketId = ((Number) issued.get("ticketId")).longValue();

        Map<String, Object> row = rows(adminController.listTickets(sessionId, 0, 50, null, "ALL")).stream()
            .filter(r -> ((Number) r.get("ticketId")).longValue() == ticketId).findFirst().orElseThrow();
        assertEquals(Map.of("트랙", "디자인", "식사", "채식"), row.get("attributes"));

        // Removing the field leaves the ticket that was issued with it untouched.
        long trackId = fields.findBySession(sessionId).stream()
            .filter(f -> "트랙".equals(f.label)).findFirst().orElseThrow().id;
        adminController.deleteField(trackId);
        assertEquals(Map.of("트랙", "디자인", "식사", "채식"),
            rows(adminController.listTickets(sessionId, 0, 50, null, "ALL")).stream()
                .filter(r -> ((Number) r.get("ticketId")).longValue() == ticketId)
                .findFirst().orElseThrow().get("attributes"));
    }

    /**
     * 발급 현황 comes a page at a time: searched, narrowed by the label on the rows, and
     * counted without reading the tickets themselves.
     */
    @Test void theTicketListComesAPageAtATime() {
        long sessionId = newSession();
        for (int i = 1; i <= 7; i++) tickets.issue(sessionId, "p" + i + "@example.com", "B-" + i, null, null);

        Map<String, Object> first = adminController.listTickets(sessionId, 0, 3, null, "ALL");
        assertEquals(7L, first.get("total"));
        assertEquals(3, rows(first).size());
        assertEquals(1, rows(adminController.listTickets(sessionId, 2, 3, null, "ALL")).size());

        assertEquals(1L, adminController.listTickets(sessionId, 0, 50, "b-5", "ALL").get("total"), "좌석");
        assertEquals(1L, adminController.listTickets(sessionId, 0, 50, "P6@Example", "ALL").get("total"),
            "이메일, 대소문자 무시");
        assertEquals(0L, adminController.listTickets(sessionId, 0, 50, "%", "ALL").get("total"),
            "입력한 %는 와일드카드가 아니라 글자예요");

        long revoked = ((Number) rows(first).get(0).get("ticketId")).longValue();
        ticketRepository.updateStatus(revoked, "REVOKED");
        assertEquals(1L, adminController.listTickets(sessionId, 0, 50, null, "REVOKED").get("total"));
        assertEquals(6L, adminController.listTickets(sessionId, 0, 50, null, "LIVE").get("total"));
        assertEquals(400, assertThrows(ResponseStatusException.class,
            () -> adminController.listTickets(sessionId, 0, 50, null, "nonsense")).getStatusCode().value());

        Map<String, Object> summary = adminController.ticketSummary(sessionId);
        @SuppressWarnings("unchecked")
        Map<String, Object> counts = (Map<String, Object>) summary.get("counts");
        assertEquals(7L, counts.get("all"));
        assertEquals(1L, counts.get("revoked"));
        assertEquals(6L, counts.get("unclaimed"));
        assertEquals(7, ((java.util.Collection<?>) summary.get("seats")).size(), "비활성 좌석도 점유 중");
    }

    @Test void oneSeatCannotBeIssuedTwice() {
        long sessionId = newSession();
        fields.insert(sessionId, "좌석", "SEAT", "A-1");
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
        fields.insert(sessionId, "좌석", "SEAT", "A-1\nA-2");
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

    /**
     * One address, one ticket. Two links to the same inbox cannot both be used - the
     * second device to register wins and the other link is dead mail - so the console
     * refuses instead of quietly creating the confusion.
     */
    @Test void asecondTicketForTheSameAddressIsRefused() {
        long sessionId = newSession();
        tickets.issue(sessionId, "twice@example.com", "A-1", null, null);

        ResponseStatusException refused = assertThrows(ResponseStatusException.class,
            () -> tickets.issue(sessionId, "TWICE@example.com", "A-2", null, null));
        assertEquals(HttpStatus.CONFLICT, refused.getStatusCode());
        assertEquals(1, ticketRepository.findBySession(sessionId).size());

        // Another event is another question entirely.
        assertNotNull(tickets.issue(newSession(), "twice@example.com", "A-1", null, null));
    }

    /** Revoking a ticket is how the address is freed for a replacement. */
    @Test void arevokedTicketNoLongerHoldsTheAddress() {
        long sessionId = newSession();
        long first = ((Number) tickets.issue(sessionId, "again@example.com", "A-1", null, null)
            .get("ticketId")).longValue();
        tickets.setRevoked(first, true);

        Map<String, Object> second = tickets.issue(sessionId, "again@example.com", "A-2", null, null);
        assertNotEquals(first, ((Number) second.get("ticketId")).longValue());
    }

    /**
     * Deleting is for a ticket issued by mistake. It frees the address, and because the
     * ledger hangs off the ticket, one that has already been through a gate is refused
     * until the operator says they mean that too.
     */
    @Test void aticketCanBeDeletedAndTheAddressReused() {
        long sessionId = newSession();
        long ticketId = ((Number) tickets.issue(sessionId, "oops@example.com", "A-1", null, null)
            .get("ticketId")).longValue();

        Map<String, Object> gone = tickets.delete(ticketId, false);
        assertEquals(true, gone.get("deleted"));
        assertTrue(ticketRepository.findById(ticketId).isEmpty());
        assertTrue(jdbc.queryForList("SELECT 1 FROM ticket_presence WHERE ticket_id = ?", ticketId).isEmpty(),
            "입장권에 매달린 기록도 함께 사라집니다");

        // The address is free again, which is the reason to delete rather than revoke.
        assertNotNull(tickets.issue(sessionId, "oops@example.com", "A-1", null, null));
    }

    @Test void deletingATicketThatHasEnteredNeedsSayingSo() {
        long sessionId = newSession();
        long ticketId = ((Number) tickets.issue(sessionId, "inside@example.com", "A-9", null, null)
            .get("ticketId")).longValue();
        jdbc.update("INSERT INTO admission_event (ticket_id, session_id, direction, method, result) "
            + "VALUES (?, ?, 'IN', 'QR', 'ADMITTED')", ticketId, sessionId);

        ResponseStatusException refused = assertThrows(ResponseStatusException.class,
            () -> tickets.delete(ticketId, false));
        assertEquals(HttpStatus.CONFLICT, refused.getStatusCode());
        assertTrue(ticketRepository.findById(ticketId).isPresent(), "거부됐으면 남아 있어야 합니다");

        assertEquals(1, ((Number) tickets.delete(ticketId, true).get("movements")).intValue());
        assertTrue(ticketRepository.findById(ticketId).isEmpty());
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
