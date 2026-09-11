package com.plink.ticket;

import com.plink.ticket.face.FaceService;
import com.plink.ticket.face.TemplateCipher;
import com.plink.ticket.model.EventSession;
import com.plink.ticket.model.Gate;
import com.plink.ticket.model.Ticket;
import com.plink.ticket.repository.EventSessionRepository;
import com.plink.ticket.repository.FaceRepository;
import com.plink.ticket.repository.GateRepository;
import com.plink.ticket.repository.TicketRepository;
import com.plink.ticket.service.AdmissionService;
import com.plink.ticket.service.GateAuthService;
import com.plink.ticket.service.Secrets;
import com.plink.ticket.service.TicketService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The face track, exercised through the development embedder. It recognises identical
 * bytes rather than people, which is enough to pin the parts that are ours: consent,
 * encrypted storage, de-duplication, the 1:N margin rule, retention and withdrawal.
 */
@SpringBootTest
@Import(RecordingEmail.class)
class FaceTrackTest {

    @Autowired FaceService faces;
    @Autowired FaceRepository faceRepository;
    @Autowired TemplateCipher cipher;
    @Autowired TicketService tickets;
    @Autowired TicketRepository ticketRepository;
    @Autowired EventSessionRepository sessions;
    @Autowired GateRepository gates;
    @Autowired GateAuthService gateAuth;
    @Autowired AdmissionService admissions;

    private long newSession() {
        return sessions.insert("얼굴 테스트 " + Secrets.randomAlnum(6), "테스트홀",
            Timestamp.from(Instant.now().plus(4, ChronoUnit.HOURS)),
            Timestamp.from(Instant.now().minus(30, ChronoUnit.MINUTES)));
    }

    private Ticket boundTicket(long sessionId, String email) {
        Map<String, Object> issued = tickets.issue(sessionId, email, "A-1", null);
        long id = ((Number) issued.get("ticketId")).longValue();
        ticketRepository.bind(id, email);
        return ticketRepository.findById(id).orElseThrow();
    }

    private List<byte[]> capture(String face) {
        // Distinct bytes stand in for distinct faces.
        return List.of((face + "-frame-with-enough-bytes-to-pass-the-size-check").getBytes(StandardCharsets.UTF_8));
    }

    private Gate gate(long sessionId, String direction) {
        String id = "g" + Secrets.randomAlnum(10);
        gates.insert(id, sessionId, direction, "A", direction, gateAuth.hash("t"));
        return gates.findById(id).orElseThrow();
    }

    @Test void enrolmentRequiresConsentFirst() {
        Ticket ticket = boundTicket(newSession(), "holder@example.com");
        ResponseStatusException refused = assertThrows(ResponseStatusException.class,
            () -> faces.enrol(ticket, capture("a")));
        assertTrue(refused.getReason().contains("동의"), refused.getReason());
    }

    @Test void refusingConsentIsAnAnswerNotAnError() {
        Ticket ticket = boundTicket(newSession(), "holder@example.com");
        ResponseStatusException refused = assertThrows(ResponseStatusException.class,
            () -> faces.consent(ticket, "holder@example.com", false));
        assertTrue(refused.getReason().contains("패스키만으로도"), "대체 수단을 안내해야 합니다");
        assertEquals(Boolean.FALSE, faces.status(ticket).get("consented"));
    }

    @Test void consentRecordsPurposeAndRetention() {
        long sessionId = newSession();
        Ticket ticket = boundTicket(sessionId, "holder@example.com");
        Map<String, Object> consent = faces.consent(ticket, "holder@example.com", true);
        assertEquals(FaceService.PURPOSES, consent.get("purposes"));
        assertNotNull(consent.get("retainUntil"));
        assertNotNull(consent.get("version"));
    }

    @Test void theStoredTemplateIsEncryptedAndHoldsNoImage() {
        long sessionId = newSession();
        Ticket ticket = boundTicket(sessionId, "holder@example.com");
        faces.consent(ticket, "holder@example.com", true);
        faces.enrol(ticket, capture("alpha"));

        FaceRepository.Template stored = faceRepository.findByTicket(ticket.id).orElseThrow();
        assertFalse(stored.vector.contains("alpha"), "원문이 그대로 저장되면 안 됩니다");
        float[] opened = cipher.open(stored.vector);
        assertEquals(stored.dimensions, opened.length);
        assertTrue(stored.purgeAfter.toInstant().isAfter(Instant.now()));
    }

    @Test void theSameFaceCannotBeEnrolledTwiceInOneSession() {
        long sessionId = newSession();
        Ticket first = boundTicket(sessionId, "first@example.com");
        Ticket second = boundTicket(sessionId, "second@example.com");
        faces.consent(first, "first@example.com", true);
        faces.consent(second, "second@example.com", true);
        faces.enrol(first, capture("same-person"));

        ResponseStatusException refused = assertThrows(ResponseStatusException.class,
            () -> faces.enrol(second, capture("same-person")));
        assertTrue(refused.getReason().contains("이미 이 회차의 다른 입장권"), refused.getReason());
    }

    @Test void aFaceAtTheGateAdmitsThroughTheSameLedger() {
        long sessionId = newSession();
        Ticket ticket = boundTicket(sessionId, "holder@example.com");
        faces.consent(ticket, "holder@example.com", true);
        faces.enrol(ticket, capture("beta"));
        EventSession session = tickets.requireSession(sessionId);

        FaceService.Match match = faces.identify(session, capture("beta"), null, "g1");
        assertEquals(ticket.id, match.ticketId);

        Gate entry = gate(sessionId, "IN");
        Map<String, Object> result = admissions.move(entry, ticketRepository.findById(ticket.id).orElseThrow(),
            null, "FACE", null, () -> { });
        assertEquals("ADMITTED", result.get("outcome"));
        assertEquals(Boolean.TRUE, result.get("inside"));
    }

    @Test void anUnknownFaceIsRefused() {
        long sessionId = newSession();
        Ticket ticket = boundTicket(sessionId, "holder@example.com");
        faces.consent(ticket, "holder@example.com", true);
        faces.enrol(ticket, capture("gamma"));
        EventSession session = tickets.requireSession(sessionId);

        ResponseStatusException refused = assertThrows(ResponseStatusException.class,
            () -> faces.identify(session, capture("stranger"), null, "g1"));
        assertTrue(refused.getReason().contains("등록된 얼굴을 찾지 못했어요"), refused.getReason());
    }

    @Test void reentryCanBeRestrictedToFace() {
        long sessionId = newSession();
        sessions.updateFacePolicy(sessionId, false, true, "PASSIVE", "LOW_SCORE_ONLY", 7);
        sessions.updatePolicy(sessionId, "UNLIMITED", 0, 45, 0, true, "LENIENT");
        Ticket ticket = boundTicket(sessionId, "holder@example.com");
        Gate entry = gate(sessionId, "IN");
        Gate exit = gate(sessionId, "OUT");

        admissions.move(entry, ticketRepository.findById(ticket.id).orElseThrow(), null, "QR", null, () -> { });
        admissions.move(exit, ticketRepository.findById(ticket.id).orElseThrow(), null, "QR", null, () -> { });

        ResponseStatusException refused = assertThrows(ResponseStatusException.class,
            () -> admissions.move(entry, ticketRepository.findById(ticket.id).orElseThrow(),
                null, "QR", null, () -> { }));
        assertTrue(refused.getReason().contains("재입장은 얼굴 인식으로만"), refused.getReason());
    }

    @Test void aFaceOnlySessionRefusesTheQrTrack() {
        long sessionId = newSession();
        sessions.updateFacePolicy(sessionId, true, false, "PASSIVE", "ALWAYS", 7);
        Ticket ticket = boundTicket(sessionId, "holder@example.com");
        Gate entry = gate(sessionId, "IN");
        ResponseStatusException refused = assertThrows(ResponseStatusException.class,
            () -> admissions.move(entry, ticket, null, "QR", null, () -> { }));
        assertTrue(refused.getReason().contains("얼굴 인식으로만 입장"), refused.getReason());
    }

    @Test void withdrawingConsentDestroysTheTemplateImmediately() {
        long sessionId = newSession();
        Ticket ticket = boundTicket(sessionId, "holder@example.com");
        faces.consent(ticket, "holder@example.com", true);
        faces.enrol(ticket, capture("delta"));
        assertTrue(faceRepository.findByTicket(ticket.id).isPresent());

        Map<String, Object> result = faces.withdraw(ticket);
        assertEquals(1, result.get("templatesDeleted"));
        assertTrue(faceRepository.findByTicket(ticket.id).isEmpty(), "철회는 즉시 파기입니다");
        assertEquals(Boolean.FALSE, faces.status(ticket).get("consented"));

        ResponseStatusException refused = assertThrows(ResponseStatusException.class,
            () -> faces.enrol(ticket, capture("delta")));
        assertTrue(refused.getReason().contains("동의"));
    }

    @Test void templatesArePurgedWhenTheRetentionWindowEnds() {
        long sessionId = sessions.insert("지난 회차", null,
            Timestamp.from(Instant.now().minus(40, ChronoUnit.DAYS)),
            Timestamp.from(Instant.now().minus(40, ChronoUnit.DAYS)));
        sessions.updateFacePolicy(sessionId, false, false, "PASSIVE", "LOW_SCORE_ONLY", 7);
        Ticket ticket = boundTicket(sessionId, "holder@example.com");
        faces.consent(ticket, "holder@example.com", true);
        faces.enrol(ticket, capture("epsilon"));

        assertTrue(faces.purgeExpired() >= 1);
        assertTrue(faceRepository.findByTicket(ticket.id).isEmpty());
    }

    @Test void gateAttemptsAreRecordedWithoutFrames() {
        long sessionId = newSession();
        Ticket ticket = boundTicket(sessionId, "holder@example.com");
        faces.consent(ticket, "holder@example.com", true);
        faces.enrol(ticket, capture("zeta"));
        EventSession session = tickets.requireSession(sessionId);
        faces.identify(session, capture("zeta"), null, "g1");

        List<Map<String, Object>> attempts = faceRepository.recentAttempts(sessionId, 10);
        assertTrue(attempts.stream().anyMatch(row -> "MATCHED".equals(row.get("result"))));
        assertTrue(attempts.stream().allMatch(row -> row.size() == 5), "점수와 결과만 남습니다");
    }

    @Test void anUnboundTicketCannotEnrolAFace() {
        long sessionId = newSession();
        Map<String, Object> issued = tickets.issue(sessionId, "nobody@example.com", "B-1", null);
        Ticket unbound = ticketRepository.findById(((Number) issued.get("ticketId")).longValue()).orElseThrow();
        faceRepository.recordConsent(unbound.id, "nobody@example.com", "v", "test",
            Timestamp.from(Instant.now().plus(1, ChronoUnit.DAYS)));
        assertTrue(assertThrows(ResponseStatusException.class,
            () -> faces.enrol(unbound, capture("eta")))
            .getReason().contains("패스키 등록을 먼저"));
    }
}
