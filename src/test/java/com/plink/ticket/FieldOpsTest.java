package com.plink.ticket;

import com.plink.ticket.model.EventSession;
import com.plink.ticket.model.Gate;
import com.plink.ticket.model.Ticket;
import com.plink.ticket.repository.AdmissionRepository;
import com.plink.ticket.repository.EventSessionRepository;
import com.plink.ticket.repository.GateRepository;
import com.plink.ticket.repository.PresentationRepository;
import com.plink.ticket.repository.TicketRepository;
import com.plink.ticket.service.AdmissionService;
import com.plink.ticket.service.GateAuthService;
import com.plink.ticket.service.GeoCheck;
import com.plink.ticket.service.OfflineSyncService;
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

/** Location binding, offline replay, anomaly signals and staff corrections. */
@SpringBootTest
@Import(RecordingEmail.class)
class FieldOpsTest {

    @Autowired TicketService tickets;
    @Autowired TicketRepository ticketRepository;
    @Autowired EventSessionRepository sessions;
    @Autowired GateRepository gates;
    @Autowired GateAuthService gateAuth;
    @Autowired PresentationService presentations;
    @Autowired PresentationRepository presentationRepository;
    @Autowired AdmissionService admissions;
    @Autowired AdmissionRepository ledger;
    @Autowired OfflineSyncService offline;

    private long newSession() {
        return sessions.insert("현장 테스트 " + Secrets.randomAlnum(6), "테스트홀",
            Timestamp.from(Instant.now().plus(3, ChronoUnit.HOURS)),
            Timestamp.from(Instant.now().minus(30, ChronoUnit.MINUTES)));
    }

    private Ticket boundTicket(long sessionId) {
        Map<String, Object> issued = tickets.issue(sessionId, "holder@example.com", "A-1", null);
        long id = ((Number) issued.get("ticketId")).longValue();
        ticketRepository.bind(id, "holder@example.com");
        return ticketRepository.findById(id).orElseThrow();
    }

    private Gate gate(long sessionId, String direction) {
        String id = "g" + Secrets.randomAlnum(10);
        gates.insert(id, sessionId, direction, "A", direction, gateAuth.hash("t"));
        return gates.findById(id).orElseThrow();
    }

    // --- location binding -------------------------------------------------

    @Test void distanceIsMeasuredOnTheGlobeNotTheMap() {
        // Seoul City Hall to Gwanghwamun, about 1.2 km.
        double metres = GeoCheck.haversineMeters(37.5663, 126.9779, 37.5759, 126.9769);
        assertTrue(metres > 900 && metres < 1400, "실측 거리와 크게 어긋나면 안 됩니다: " + metres);
    }

    @Test void aPoorFixCountsAsUnknownRatherThanAFailure() {
        long sessionId = newSession();
        sessions.updateGeoPolicy(sessionId, 37.5663, 126.9779, 300, "ENFORCE");
        EventSession session = tickets.requireSession(sessionId);

        GeoCheck.Result vague = GeoCheck.evaluate(session, 37.5663, 126.9779, 2000.0);
        assertNull(vague.ok, "정확도가 나쁘면 판정하지 않습니다");
        assertEquals("위치 정확도 부족", vague.note);

        GeoCheck.Result missing = GeoCheck.evaluate(session, null, null, null);
        assertNull(missing.ok);
    }

    @Test void locationIsJudgedAgainstTheVenueRadius() {
        long sessionId = newSession();
        sessions.updateGeoPolicy(sessionId, 37.5663, 126.9779, 300, "ADVISE");
        EventSession session = tickets.requireSession(sessionId);

        assertEquals(Boolean.TRUE, GeoCheck.evaluate(session, 37.5665, 126.9781, 20.0).ok);
        GeoCheck.Result far = GeoCheck.evaluate(session, 37.5759, 126.9769, 20.0);
        assertEquals(Boolean.FALSE, far.ok);
        assertEquals("공연장 반경 밖", far.note);
    }

    @Test void theVerdictIsRecordedOnTheGrant() {
        long sessionId = newSession();
        sessions.updateGeoPolicy(sessionId, 37.5663, 126.9779, 300, "ADVISE");
        EventSession session = tickets.requireSession(sessionId);
        Ticket ticket = boundTicket(sessionId);

        presentations.issue(ticket, "IN", true, GeoCheck.evaluate(session, 37.5759, 126.9769, 20.0));
        assertEquals(1, presentationRepository.countAwayFromVenue(ticket.id),
            "공연장 밖에서 연 기록이 남아야 이상 탐지가 가능합니다");
    }

    @Test void locationIsOffByDefault() {
        EventSession session = tickets.requireSession(newSession());
        assertNull(GeoCheck.evaluate(session, 37.0, 127.0, 10.0).ok);
    }

    // --- offline replay ---------------------------------------------------

    @Test void queuedMovementsAreReplayedInCaptureOrder() {
        long sessionId = newSession();
        sessions.updatePolicy(sessionId, "UNLIMITED", 0, 45, 0, true, "LENIENT");
        Ticket ticket = boundTicket(sessionId);
        Gate both = gate(sessionId, "BIDIRECTIONAL");

        Map<String, Object> entry = presentations.issue(ticket, "IN", true);
        Map<String, Object> exit = presentations.issue(ticket, "OUT", true);
        // Deliberately out of order: replay must sort by capture time, not arrival.
        Map<String, Object> result = offline.replay(both, List.of(
            Map.of("code", TicketCodes.code(exit, 1), "capturedAt", "2026-09-11T10:05:00Z"),
            Map.of("code", TicketCodes.code(entry, 1), "capturedAt", "2026-09-11T10:00:00Z")));

        assertEquals(2, result.get("received"));
        assertEquals(2, result.get("applied"));
        assertEquals(0, result.get("flagged"));
        assertFalse(ledger.find(ticket.id).orElseThrow().inside(), "입장 후 퇴장까지 반영됩니다");
    }

    @Test void anExpiredCodeStillCountsWhenItWasCapturedOffline() {
        long sessionId = newSession();
        Ticket ticket = boundTicket(sessionId);
        Gate entry = gate(sessionId, "IN");
        Map<String, Object> grant = presentations.issue(ticket, "IN", true);
        // Captured well outside the freshness window a live scan would demand.
        String code = TicketCodes.code(grant, 1, Instant.now().getEpochSecond() - 3600, Secrets.randomAlnum(12));

        assertThrows(ResponseStatusException.class, () -> admissions.admit(entry, code, "QR"),
            "온라인 경로는 여전히 신선도를 요구합니다");
        Map<String, Object> replayed = offline.replay(entry,
            List.of(Map.of("code", code, "capturedAt", "2026-09-11T10:00:00Z")));
        assertEquals(1, replayed.get("applied"));
    }

    @Test void contradictionsAreFlaggedNotForcedIntoTheState() {
        long sessionId = newSession();
        Ticket ticket = boundTicket(sessionId);
        Gate entry = gate(sessionId, "IN");
        Map<String, Object> grant = presentations.issue(ticket, "IN", true);
        String code = TicketCodes.code(grant, 1);

        admissions.admit(entry, code, "QR");
        // The terminal also queued the same read while it was offline.
        Map<String, Object> result = offline.replay(entry,
            List.of(Map.of("code", code, "capturedAt", "2026-09-11T10:00:00Z")));

        assertEquals(0, result.get("applied"));
        assertEquals(1, result.get("flagged"));
        assertFalse(ledger.flaggedEvents(sessionId).isEmpty(), "충돌은 조사 큐에 남습니다");
    }

    // --- anomaly signals --------------------------------------------------

    @Test void repeatedRefusalsSurfaceForInvestigation() {
        long sessionId = newSession();
        Ticket ticket = boundTicket(sessionId);
        for (int i = 0; i < 3; i++) {
            ledger.append(ticket.id, sessionId, "IN", "g1", "QR", "DENIED", "테스트 거부", null);
        }
        List<Map<String, Object>> refusals = ledger.repeatedRefusals(sessionId, 3);
        assertEquals(1, refusals.size());
        assertEquals(3L, ((Number) refusals.get(0).get("refusals")).longValue());
    }

    @Test void movementsTooFarApartTooFastAreSurfaced() {
        long sessionId = newSession();
        sessions.updatePolicy(sessionId, "UNLIMITED", 0, 45, 0, true, "LENIENT");
        Ticket ticket = boundTicket(sessionId);
        Gate first = gate(sessionId, "BIDIRECTIONAL");
        Gate second = gate(sessionId, "BIDIRECTIONAL");

        admissions.move(first, ticket, "IN", "QR", null, () -> { });
        admissions.move(second, ticketRepository.findById(ticket.id).orElseThrow(), "OUT", "QR", null, () -> { });

        assertFalse(ledger.impossibleMovements(sessionId, 60).isEmpty(),
            "서로 다른 게이트에서 순식간에 일어난 이동은 신호입니다");
    }

    // --- staff corrections ------------------------------------------------

    @Test void aStaffCorrectionIsANewLedgerEntry() {
        long sessionId = newSession();
        Ticket ticket = boundTicket(sessionId);
        ledger.markInside(ticket.id, 1, 0, "g1");
        ledger.appendDetailed(ticket.id, sessionId, "IN", null, "STAFF", "ADMITTED",
            "현장 확인 후 수동 입장", null, "staff@example.com", false, false);

        List<Map<String, Object>> recent = ledger.recentEvents(sessionId, 5);
        assertTrue(recent.stream().anyMatch(row -> "STAFF".equals(row.get("method"))));
        assertTrue(ledger.find(ticket.id).orElseThrow().inside());
    }
}
