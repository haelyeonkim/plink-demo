package com.plink.ticket;

import com.plink.ticket.controller.TicketAdminController;
import com.plink.ticket.model.EventSession;
import com.plink.ticket.model.Ticket;
import com.plink.ticket.repository.EventSessionRepository;
import com.plink.ticket.repository.TicketRepository;
import com.plink.ticket.schedule.ScheduleService;
import com.plink.ticket.service.Secrets;
import com.plink.ticket.service.TicketService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** An event over several days: which days a ticket admits, and when the event is over. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:scheduletest;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.config.import=", "plink.admin.email=", "plink.admin.password="})
@Import(RecordingEmail.class)
class ScheduleTest {
    @Autowired ScheduleService schedule;
    @Autowired TicketService tickets;
    @Autowired TicketRepository ticketRows;
    @Autowired EventSessionRepository sessions;
    @Autowired TicketAdminController admin;

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /** A moment on the venue's clock. */
    private static Instant at(String local) {
        return LocalDateTime.parse(local).atZone(SEOUL).toInstant();
    }

    /** Three days, the day turning at 4 a.m.: Saturday 10-21, a closed Sunday, Monday 10-18. */
    private long threeDays() {
        long id = sessions.insert("사흘 이벤트 " + Secrets.randomAlnum(6), null,
            Timestamp.from(at("2030-05-11T10:00")), null);
        sessions.updateDayClose(id, false, 4);
        schedule.saveDays(id, List.of(
            Map.of("date", "2030-05-11", "label", "DAY 1", "opensAt", "10:00", "closesAt", "21:00"),
            Map.of("date", "2030-05-12", "label", "DAY 2", "closed", true),
            Map.of("date", "2030-05-13", "label", "DAY 3", "opensAt", "10:00", "closesAt", "18:00")));
        return id;
    }

    private EventSession event(long id) { return sessions.findById(id).orElseThrow(); }

    private Ticket ticket(long sessionId, Long passId) {
        long id = ((Number) tickets.issue(sessionId, "d" + Secrets.randomAlnum(6) + "@example.com",
            null, null, null, false, Map.of(), passId).get("ticketId")).longValue();
        return ticketRows.findById(id).orElseThrow();
    }

    private long pass(long sessionId, String name, List<String> days) {
        java.util.HashMap<String, Object> body = new java.util.HashMap<>();
        body.put("name", name);
        body.put("days", days);
        schedule.createPass(sessionId, body);
        return schedule.resolvePass(sessionId, name);
    }

    private static HttpStatus status(Runnable call) {
        try { call.run(); return HttpStatus.OK; }
        catch (ResponseStatusException e) { return HttpStatus.valueOf(e.getStatusCode().value()); }
    }

    @Test void anEventWithoutDaysWorksAsBefore() {
        long id = sessions.insert("하루 이벤트", null, Timestamp.from(at("2030-05-11T10:00")), null);
        Ticket any = ticket(id, null);
        assertEquals(Optional.empty(), schedule.refusal(event(id), any, at("2031-01-01T12:00")));
        assertNull(schedule.holderView(event(id), any, at("2031-01-01T12:00")));
        assertFalse(schedule.ended(event(id), at("2031-01-01T12:00")));
    }

    @Test void theDaysSayWhenTheEventStarts() {
        long id = sessions.insert("시작 이벤트 " + Secrets.randomAlnum(6), null,
            Timestamp.from(at("2030-05-01T19:00")), Timestamp.from(at("2030-05-01T18:00")));
        sessions.updateDayClose(id, false, 4);
        schedule.saveDays(id, List.of(
            Map.of("date", "2030-05-10", "label", "DAY 0", "closed", true),
            Map.of("date", "2030-05-11", "label", "DAY 1", "opensAt", "10:00", "closesAt", "21:00"),
            Map.of("date", "2030-05-12", "label", "DAY 2")));
        // The first open day at its opening; each day's hours replace the single door time.
        assertEquals(at("2030-05-11T10:00"), event(id).startsAt.toInstant());
        assertNull(event(id).gateOpensAt);

        // Saving the details cannot pull the start away from the days.
        admin.updateSession(id, Map.of("name", "이름만 바꿈", "startsAt", "2030-06-01T00:00:00Z",
            "gateOpensAt", "2030-05-31T23:00:00Z"));
        assertEquals("이름만 바꿈", event(id).name);
        assertEquals(at("2030-05-11T10:00"), event(id).startsAt.toInstant());
        assertNull(event(id).gateOpensAt);

        // A first day open all day starts when the venue's day turns.
        schedule.saveDays(id, List.of(Map.of("date", "2030-05-12", "label", "DAY 2")));
        assertEquals(at("2030-05-12T04:00"), event(id).startsAt.toInstant());

        // Back to one day: the start stays, and the details set it again.
        schedule.saveDays(id, List.of());
        assertEquals(at("2030-05-12T04:00"), event(id).startsAt.toInstant());
        admin.updateSession(id, Map.of("name", "하루", "startsAt", "2030-06-01T10:00:00Z"));
        assertEquals(Instant.parse("2030-06-01T10:00:00Z"), event(id).startsAt.toInstant());
    }

    @Test void aPassAdmitsOnlyOnItsDays() {
        long id = threeDays();
        Ticket everyDay = ticket(id, null);
        Ticket monday = ticket(id, pass(id, "월요일권", List.of("2030-05-13")));
        Instant saturdayNoon = at("2030-05-11T12:00");

        assertEquals(Optional.empty(), schedule.refusal(event(id), everyDay, saturdayNoon));
        String refused = schedule.refusal(event(id), monday, saturdayNoon).orElseThrow();
        assertTrue(refused.contains("월요일권") && refused.contains("5.13 (월)"), refused);
        assertEquals(Optional.empty(), schedule.refusal(event(id), monday, at("2030-05-13T12:00")));
        assertEquals("CLOSED", schedule.holderView(event(id), monday, saturdayNoon).get("state"));
    }

    @Test void aClosedDayAndTheHoursAreKept() {
        long id = threeDays();
        Ticket everyDay = ticket(id, null);
        // Sunday is a rest day, even for an every-day ticket.
        assertTrue(schedule.refusal(event(id), everyDay, at("2030-05-12T12:00")).orElseThrow().contains("5.13 (월)"));
        assertTrue(schedule.refusal(event(id), everyDay, at("2030-05-11T09:30")).orElseThrow().contains("10:00부터"));
        assertEquals("오늘 입장이 마감됐어요.", schedule.refusal(event(id), everyDay, at("2030-05-11T21:00")).orElseThrow());
    }

    /** With the day turning at 4 a.m., 1 a.m. is still the night before. */
    @Test void theNightBelongsToTheDayBefore() {
        long id = sessions.insert("밤 이벤트 " + Secrets.randomAlnum(6), null, Timestamp.from(at("2030-05-11T18:00")), null);
        sessions.updateDayClose(id, false, 4);
        schedule.saveDays(id, List.of(
            Map.of("date", "2030-05-11", "opensAt", "18:00", "closesAt", "02:00"),
            Map.of("date", "2030-05-12", "opensAt", "18:00", "closesAt", "02:00")));
        Ticket saturday = ticket(id, pass(id, "토요일권", List.of("2030-05-11")));
        assertEquals(Optional.empty(), schedule.refusal(event(id), saturday, at("2030-05-12T01:00")));
        assertEquals("오늘 입장이 마감됐어요.", schedule.refusal(event(id), saturday, at("2030-05-12T02:30")).orElseThrow());
        // A close before its opening on the clock is fine when it falls after the turn of the day.
        assertEquals(HttpStatus.BAD_REQUEST, status(() -> schedule.saveDays(id,
            List.of(Map.of("date", "2030-05-11", "opensAt", "18:00", "closesAt", "17:00")))));
    }

    @Test void theEventEndsWhenItsLastOpenDayCloses() {
        long id = threeDays();
        Ticket everyDay = ticket(id, null);
        assertFalse(schedule.ended(event(id), at("2030-05-13T17:59")));
        assertTrue(schedule.ended(event(id), at("2030-05-13T18:00")));
        assertEquals("종료된 이벤트예요.", schedule.refusal(event(id), everyDay, at("2030-05-14T12:00")).orElseThrow());
        assertEquals("ENDED", schedule.holderView(event(id), everyDay, at("2030-05-14T12:00")).get("state"));
    }

    @Test void passesStayWithinTheSchedule() {
        long id = threeDays();
        java.util.HashMap<String, Object> outside = new java.util.HashMap<>();
        outside.put("name", "엉뚱한 날");
        outside.put("days", List.of("2030-06-01"));
        assertEquals(HttpStatus.BAD_REQUEST, status(() -> schedule.createPass(id, outside)));

        long weekend = pass(id, "주말권", List.of("2030-05-11", "2030-05-12"));
        // A day taken out of the schedule is taken out of the passes too.
        schedule.saveDays(id, List.of(Map.of("date", "2030-05-11"), Map.of("date", "2030-05-13")));
        @SuppressWarnings("unchecked")
        Map<String, Object> row = ((List<Map<String, Object>>) schedule.console(id).get("passes")).stream()
            .filter(p -> ((Number) p.get("id")).longValue() == weekend).findFirst().orElseThrow();
        assertEquals(List.of("2030-05-11"), row.get("days"));

        // One with tickets on it stays.
        ticket(id, weekend);
        assertEquals(HttpStatus.CONFLICT, status(() -> schedule.deletePass(id, weekend)));
        assertEquals(HttpStatus.BAD_REQUEST, status(() -> schedule.resolvePass(id, "없는 종류")));
    }

    @Test void theConsoleListsWhoMayComeOnADay() {
        long id = threeDays();
        long monday = pass(id, "월요일권", List.of("2030-05-13"));
        ticket(id, null);
        ticket(id, monday);
        ticket(id, pass(id, "토요일권", List.of("2030-05-11")));
        assertEquals(3L, ((Number) admin.listTickets(id, 0, 50, null, "ALL", null).get("total")).longValue());
        // Saturday: the every-day ticket and the Saturday one.
        assertEquals(2L, ((Number) admin.listTickets(id, 0, 50, null, "ALL", "2030-05-11").get("total")).longValue());
        // The issue form names the pass by id, a spreadsheet by name.
        Map<String, Object> issued = admin.issueTicket(id, Map.of("email", "sheet@example.com", "입장권 종류", "월요일권", "notify", "false"));
        assertEquals(monday, ticketRows.findById(((Number) issued.get("ticketId")).longValue()).orElseThrow().passId);
    }

    /** Someone invited comes on the days the person who invited them comes. */
    @Test void anInviteeTakesTheInvitersDays() {
        long id = threeDays();
        Ticket inviter = ticket(id, pass(id, "월요일권", List.of("2030-05-13")));
        long invitee = ((Number) tickets.issueInvite(id, "friend" + Secrets.randomAlnum(4) + "@example.com",
            new TicketService.Invitation(inviter.id, "k***@example.com", null, () -> { })).get("ticketId")).longValue();
        assertEquals(inviter.passId, ticketRows.findById(invitee).orElseThrow().passId);
    }

    @Autowired com.plink.ticket.service.AdmissionService admissions;
    @Autowired com.plink.ticket.repository.GateRepository gates;
    @Autowired com.plink.ticket.service.GateAuthService gateAuth;
    @Autowired com.plink.ticket.repository.HolderRepository holders;

    /** At the door: not one of the ticket's days is refused coming in, with the reason. */
    @Test void theGateRefusesADayTheTicketDoesNotAdmit() {
        java.time.LocalDate today = java.time.LocalDate.now(SEOUL);
        long id = sessions.insert("내일 이벤트 " + Secrets.randomAlnum(6), null, Timestamp.from(Instant.now()), null);
        schedule.saveDays(id, List.of(Map.of("date", today.plusDays(1).toString()), Map.of("date", today.plusDays(2).toString())));
        Ticket ticket = ticket(id, null);
        String email = ticket.issuedToEmail;
        ticketRows.bind(ticket.id, holders.create(email, tickets.userHandleFor(email)), email);
        String gateId = "g" + Secrets.randomAlnum(10);
        gates.insert(gateId, id, "정문", "A", "IN", gateAuth.hash("t"), null, null);

        ResponseStatusException refused = assertThrows(ResponseStatusException.class, () -> admissions.move(
            gates.findById(gateId).orElseThrow(), ticketRows.findById(ticket.id).orElseThrow(), null, "QR", null, () -> { }));
        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode());
        assertTrue(refused.getReason().contains("다음 입장일"), refused.getReason());
    }

    /** A showcase left open for a year and more is one schedule, and ends when its last day does. */
    @Test void aLongScheduleIsOneSchedule() {
        long id = sessions.insert("긴 이벤트 " + Secrets.randomAlnum(6), null, Timestamp.from(at("2030-01-01T10:00")), null);
        java.util.List<Map<String, Object>> days = new java.util.ArrayList<>();
        for (java.time.LocalDate d = java.time.LocalDate.parse("2030-01-01"); !d.isAfter(java.time.LocalDate.parse("2031-02-24")); d = d.plusDays(1)) {
            days.add(Map.of("date", d.toString()));
        }
        assertEquals(420, days.size());
        schedule.saveDays(id, days);
        assertEquals(at("2031-02-25T00:00"), schedule.endsAt(event(id)).orElseThrow());
        assertEquals(Optional.empty(), schedule.refusal(event(id), ticket(id, null), at("2030-08-15T12:00")));

        java.util.List<Map<String, Object>> tooMany = new java.util.ArrayList<>();
        for (int i = 0; i < 801; i++) tooMany.add(Map.of("date", java.time.LocalDate.parse("2030-01-01").plusDays(i).toString()));
        assertEquals(HttpStatus.BAD_REQUEST, status(() -> schedule.saveDays(id, tooMany)));
    }
}
