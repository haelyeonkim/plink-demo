package com.plink.ticket;

import com.plink.ticket.model.Gate;
import com.plink.ticket.model.Ticket;
import com.plink.ticket.repository.AdmissionRepository;
import com.plink.ticket.repository.EventSessionRepository;
import com.plink.ticket.repository.GateRepository;
import com.plink.ticket.repository.TicketRepository;
import com.plink.ticket.service.AdmissionService;
import com.plink.ticket.service.GateAuthService;
import com.plink.ticket.service.PresentationService;
import com.plink.ticket.service.Secrets;
import com.plink.ticket.service.TicketService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The presence ledger is what replaces "one ticket, one admission" once re-entry is
 * allowed, so these tests pin the invariant that keeps a forwarded QR useless.
 */
@SpringBootTest
@Import(RecordingEmail.class)
class AdmissionLedgerTest {

    @Autowired TicketService tickets;
    @Autowired TicketRepository ticketRepository;
    @Autowired EventSessionRepository sessions;
    @Autowired GateRepository gates;
    @Autowired GateAuthService gateAuth;
    @Autowired PresentationService presentations;
    @Autowired AdmissionService admissions;
    @Autowired AdmissionRepository ledger;
    @Autowired RecordingEmail.Mailbox mailbox;

    /** A bound ticket plus the gates it will be scanned at. */
    class Fixture {
        final long sessionId;
        final Ticket ticket;
        final Gate entry, exit, both;

        Fixture(String reentryMode, int reentryMax, int graceMinutes, int cooldownSeconds, String unmatchedExit) {
            sessionId = sessions.insert("테스트 회차 " + Secrets.randomAlnum(6), "테스트홀",
                Timestamp.from(Instant.now().plus(2, ChronoUnit.HOURS)),
                Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)));
            sessions.updatePolicy(sessionId, reentryMode, reentryMax, graceMinutes, cooldownSeconds,
                true, unmatchedExit);
            Map<String, Object> issued = tickets.issue(sessionId, "holder@example.com", "A-1", "STANDARD");
            long ticketId = ((Number) issued.get("ticketId")).longValue();
            ticketRepository.bind(ticketId, "holder@example.com");
            ticket = ticketRepository.findById(ticketId).orElseThrow();
            entry = gate("IN");
            exit = gate("OUT");
            both = gate("BIDIRECTIONAL");
        }

        Gate gate(String direction) {
            String id = "g" + Secrets.randomAlnum(10);
            gates.insert(id, sessionId, direction + " 게이트", "A", direction, gateAuth.hash("t-" + id));
            return gates.findById(id).orElseThrow();
        }

        Map<String, Object> scan(Gate gate, String direction) {
            Map<String, Object> grant = presentations.issue(ticket, direction, true);
            return admissions.admit(gate, TicketCodes.code(grant, 1), "QR");
        }

        ResponseStatusException refuse(Gate gate, String direction) {
            return assertThrows(ResponseStatusException.class, () -> scan(gate, direction));
        }
    }

    @Test void entryExitAndReentryAreRecorded() {
        Fixture f = new Fixture("LIMITED", 3, 45, 0, "LENIENT");

        Map<String, Object> in = f.scan(f.entry, "IN");
        assertEquals("ADMITTED", in.get("outcome"));
        assertEquals(Boolean.TRUE, in.get("inside"));
        assertEquals(1, in.get("entryCount"));

        Map<String, Object> out = f.scan(f.exit, "OUT");
        assertEquals("EXITED", out.get("outcome"));
        assertEquals(Boolean.FALSE, out.get("inside"));

        Map<String, Object> back = f.scan(f.entry, "IN");
        assertEquals("ADMITTED", back.get("outcome"));
        assertEquals(2, back.get("entryCount"));
        assertEquals(2, back.get("reentryRemaining"), "3회 한도 중 1회 사용");
    }

    @Test void aTicketThatIsInsideCannotEnterAgain() {
        Fixture f = new Fixture("UNLIMITED", 0, 45, 0, "LENIENT");
        f.scan(f.entry, "IN");

        // A forwarded code presented at a different entry gate: the ticket is INSIDE,
        // so there is nothing left to admit. This is the sharing defence.
        Gate otherEntry = f.gate("IN");
        ResponseStatusException refused = f.refuse(otherEntry, "IN");
        assertTrue(refused.getReason().contains("이미 장내에 있는"), refused.getReason());
    }

    @Test void lenientPolicyDoesNotForgiveAnUnmatchedExitImmediately() {
        // auto_exit_after_minutes defaults to 240, so a fresh entry is never forgiven.
        Fixture f = new Fixture("UNLIMITED", 0, 45, 0, "LENIENT");
        f.scan(f.entry, "IN");
        assertTrue(f.refuse(f.gate("IN"), "IN").getReason().contains("이미 장내에 있는"));
    }

    @Test void exitRequiresAnInsideState() {
        Fixture f = new Fixture("LIMITED", 3, 45, 0, "LENIENT");
        assertTrue(f.refuse(f.exit, "OUT").getReason().contains("장내 입장 기록이 없어요"));
    }

    @Test void directionComesFromTheTerminal() {
        Fixture f = new Fixture("LIMITED", 3, 45, 0, "LENIENT");
        assertTrue(f.refuse(f.exit, "IN").getReason().contains("퇴장 게이트"));
        f.scan(f.entry, "IN");
        assertTrue(f.refuse(f.entry, "OUT").getReason().contains("입장 게이트"));
    }

    @Test void bidirectionalTerminalInfersDirectionFromPresence() {
        Fixture f = new Fixture("UNLIMITED", 0, 45, 0, "LENIENT");
        assertEquals("ADMITTED", f.scan(f.both, "IN").get("outcome"));
        assertTrue(f.refuse(f.both, "IN").getReason().contains("이미 장내에 있어요"));
        assertEquals("EXITED", f.scan(f.both, "OUT").get("outcome"));
        assertTrue(f.refuse(f.both, "OUT").getReason().contains("장내 기록이 없어요"));
    }

    @Test void rescanAtTheSameGateWithinCooldownDoesNotToggleState() {
        Fixture f = new Fixture("UNLIMITED", 0, 45, 60, "LENIENT");
        f.scan(f.both, "IN");
        // A second read at the same terminal seconds later is the operator scanning
        // twice, not a movement.
        Map<String, Object> again = f.scan(f.both, "OUT");
        assertEquals("DUPLICATE", again.get("outcome"));
        assertEquals(Boolean.TRUE, again.get("inside"), "상태는 그대로 장내");
    }

    @Test void reentryLimitIsEnforced() {
        Fixture f = new Fixture("LIMITED", 1, 45, 0, "LENIENT");
        f.scan(f.entry, "IN");
        f.scan(f.exit, "OUT");
        f.scan(f.entry, "IN");
        f.scan(f.exit, "OUT");
        assertTrue(f.refuse(f.entry, "IN").getReason().contains("재입장 횟수를 모두 사용했어요"));
    }

    @Test void reentryCanBeDisabledEntirely() {
        Fixture f = new Fixture("DISABLED", 0, 45, 0, "LENIENT");
        f.scan(f.entry, "IN");
        f.scan(f.exit, "OUT");
        assertTrue(f.refuse(f.entry, "IN").getReason().contains("재입장이 허용되지 않는"));
    }

    @Test void reentryGraceWindowExpires() throws Exception {
        Fixture f = new Fixture("UNLIMITED", 0, 0, 0, "LENIENT");
        f.scan(f.entry, "IN");
        f.scan(f.exit, "OUT");
        Thread.sleep(1100); // let the zero-minute grace window lapse
        assertTrue(f.refuse(f.entry, "IN").getReason().contains("재입장 유효 시간이 지났어요"));
    }

    @Test void aNonceIsAcceptedOnlyOnce() {
        Fixture f = new Fixture("UNLIMITED", 0, 45, 0, "LENIENT");
        Map<String, Object> grant = presentations.issue(f.ticket, "IN", true);
        String nonce = Secrets.randomAlnum(12);
        long now = System.currentTimeMillis() / 1000;
        admissions.admit(f.entry, TicketCodes.code(grant, 1, now, nonce), "QR");

        Map<String, Object> second = presentations.issue(f.ticket, "OUT", true);
        ResponseStatusException refused = assertThrows(ResponseStatusException.class,
            () -> admissions.admit(f.exit, TicketCodes.code(second, 1, now, nonce), "QR"));
        assertTrue(refused.getReason().contains("이미 사용한 코드"), refused.getReason());
    }

    @Test void aGrantIsSpentByOneSuccessfulScan() {
        Fixture f = new Fixture("UNLIMITED", 0, 45, 0, "LENIENT");
        Map<String, Object> grant = presentations.issue(f.ticket, "IN", true);
        admissions.admit(f.entry, TicketCodes.code(grant, 1), "QR");
        ResponseStatusException refused = assertThrows(ResponseStatusException.class,
            () -> admissions.admit(f.gate("IN"), TicketCodes.code(grant, 2), "QR"));
        assertTrue(refused.getReason().contains("이미 사용한 코드"), refused.getReason());
    }

    @Test void staleAndForgedCodesAreRefused() {
        Fixture f = new Fixture("UNLIMITED", 0, 45, 0, "LENIENT");
        Map<String, Object> grant = presentations.issue(f.ticket, "IN", true);

        long stale = System.currentTimeMillis() / 1000 - 600;
        assertTrue(assertThrows(ResponseStatusException.class,
            () -> admissions.admit(f.entry, TicketCodes.code(grant, 1, stale, Secrets.randomAlnum(12)), "QR"))
            .getReason().contains("만료"));

        String forged = TicketCodes.code(grant, 2).replaceAll("[A-F0-9]{32}$", "0".repeat(32));
        assertTrue(assertThrows(ResponseStatusException.class,
            () -> admissions.admit(f.entry, forged, "QR"))
            .getReason().contains("서명"));
    }

    @Test void onlyTheSessionsOwnGateAdmits() {
        Fixture f = new Fixture("UNLIMITED", 0, 45, 0, "LENIENT");
        Fixture other = new Fixture("UNLIMITED", 0, 45, 0, "LENIENT");
        assertTrue(f.refuse(other.entry, "IN").getReason().contains("다른 회차의 입장권"));
    }

    @Test void unboundTicketsAreRefused() {
        long sessionId = sessions.insert("미등록 회차", null,
            Timestamp.from(Instant.now().plus(1, ChronoUnit.HOURS)), null);
        Map<String, Object> issued = tickets.issue(sessionId, "nobody@example.com", "B-2", null);
        Ticket unbound = ticketRepository.findById(((Number) issued.get("ticketId")).longValue()).orElseThrow();
        String id = "g" + Secrets.randomAlnum(10);
        gates.insert(id, sessionId, "IN", "A", "IN", gateAuth.hash("t"));
        Gate gate = gates.findById(id).orElseThrow();

        Map<String, Object> grant = presentations.issue(unbound, "IN", true);
        assertTrue(assertThrows(ResponseStatusException.class,
            () -> admissions.admit(gate, TicketCodes.code(grant, 1), "QR"))
            .getReason().contains("등록이 완료되지 않은"));
    }

    @Test void refusalsAreRecordedInTheLedger() {
        Fixture f = new Fixture("UNLIMITED", 0, 45, 0, "LENIENT");
        Map<String, Object> grant = presentations.issue(f.ticket, "IN", true);
        String code = TicketCodes.code(grant, 1);
        try {
            admissions.admit(f.exit, code, "QR");
            fail("퇴장 게이트에 입장 코드를 제시하면 거부되어야 합니다");
        } catch (ResponseStatusException expected) {
            admissions.recordDenied(f.exit, code, "QR", expected.getReason());
        }
        List<Map<String, Object>> recent = ledger.recentEvents(f.sessionId, 10);
        assertTrue(recent.stream().anyMatch(row -> "DENIED".equals(row.get("result"))),
            "거부도 원장에 남아야 합니다");
    }

    @Test void occupancyCountsLiveAttendance() {
        Fixture f = new Fixture("UNLIMITED", 0, 45, 0, "LENIENT");
        assertEquals(0, ledger.occupancy(f.sessionId).get("inside"));
        f.scan(f.entry, "IN");
        assertEquals(1, ledger.occupancy(f.sessionId).get("inside"));
        assertEquals(1, ledger.stillInside(f.sessionId).size());
        f.scan(f.exit, "OUT");
        assertEquals(0, ledger.occupancy(f.sessionId).get("inside"));
    }

    @Test void issuingATicketMailsItsPersonalUrl() {
        mailbox.clear();
        long sessionId = sessions.insert("메일 회차", null,
            Timestamp.from(Instant.now().plus(1, ChronoUnit.HOURS)), null);
        tickets.issue(sessionId, "Holder@Example.com ", "C-3", null);
        assertTrue(mailbox.lastTicketUrl().isPresent(), "발급 메일에 개인 링크가 있어야 합니다");
        assertEquals("holder@example.com", mailbox.sent.get(mailbox.sent.size() - 1)[0],
            "이메일은 정규화되어 저장·발송됩니다");
    }
}
