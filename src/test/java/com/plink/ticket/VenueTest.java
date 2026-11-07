package com.plink.ticket;

import com.plink.ticket.repository.EventSessionRepository;
import com.plink.ticket.repository.HolderRepository;
import com.plink.ticket.repository.TicketRepository;
import com.plink.ticket.service.Secrets;
import com.plink.ticket.service.TicketPasskeyService;
import com.plink.ticket.service.TicketService;
import com.plink.ticket.venue.VenueRepository;
import com.plink.ticket.venue.VenueService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The venue tab: what the organiser may store, and what a holder is shown. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:venuetest;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.config.import=", "plink.admin.email=", "plink.admin.password=",
    "plink.images.backend=local", "plink.images.directory=target/test-venue-images"})
@Import(RecordingEmail.class)
class VenueTest {
    @Autowired VenueService venue;
    @Autowired VenueRepository repo;
    @Autowired TicketService tickets;
    @Autowired TicketRepository ticketRows;
    @Autowired TicketPasskeyService passkeys;
    @Autowired HolderRepository holders;
    @Autowired EventSessionRepository sessions;

    private long newSession() {
        return sessions.insert("행사장 이벤트 " + Secrets.randomAlnum(6), "메인홀",
            Timestamp.from(Instant.now().plus(1, ChronoUnit.DAYS)), null);
    }

    private record Holder(TicketService.Resolved resolved, MockHttpSession session) {}

    /** A ticket issued and, when {@code register}, registered and proven on this browser. */
    private Holder holder(long sessionId, String email, boolean register) {
        Map<String, Object> issued = tickets.issue(sessionId, email, null, null, null);
        String url = issued.get("url").toString();
        String token = url.substring(url.lastIndexOf('/') + 1);
        long ticketId = ((Number) issued.get("ticketId")).longValue();
        MockHttpSession session = new MockHttpSession();
        if (register) {
            long holderId = holders.findByEmail(email).map(h -> h.id)
                .orElseGet(() -> holders.create(email, tickets.userHandleFor(email)));
            ticketRows.bind(ticketId, holderId, email);
            passkeys.markViewer(session, ticketRows.findById(ticketId).orElseThrow());
        }
        return new Holder(tickets.resolve(sessionId, token), session);
    }

    private static HttpStatus status(Runnable call) {
        try { call.run(); return HttpStatus.OK; }
        catch (ResponseStatusException e) { return HttpStatus.valueOf(e.getStatusCode().value()); }
    }

    private static long id(Map<String, Object> section) {
        return ((Number) section.get("id")).longValue();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> sections(Map<String, Object> view) {
        return (List<Map<String, Object>>) view.get("sections");
    }

    private static MockMultipartFile png() throws Exception {
        BufferedImage image = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return new MockMultipartFile("file", "plan.png", "image/png", out.toByteArray());
    }

    @Test void aRegisteredTicketSeesOnlyWhatTheOrganiserShows() {
        long sessionId = newSession();
        long text = id(venue.create(sessionId, Map.of("kind", "TEXT", "title", "주차 안내")));
        venue.update(sessionId, text, Map.of("title", "주차 안내", "content", Map.of("body", "지하 2층")));
        Holder holder = holder(sessionId, "venue@example.com", true);

        // A new section starts hidden: half-written guidance never reaches a ticket.
        assertTrue(sections(venue.holder(holder.resolved(), holder.session())).isEmpty());
        venue.setVisible(sessionId, text, true);
        List<Map<String, Object>> shown = sections(venue.holder(holder.resolved(), holder.session()));
        assertEquals(1, shown.size());
        assertEquals("주차 안내", shown.get(0).get("title"));
        assertEquals("메인홀", venue.holder(holder.resolved(), holder.session()).get("venue"));
    }

    @Test void theTabOpensOnRegistrationNotOnEntry() {
        long sessionId = newSession();
        Holder unregistered = holder(sessionId, "later@example.com", false);
        assertEquals(HttpStatus.FORBIDDEN, status(() -> venue.holder(unregistered.resolved(), unregistered.session())));

        Holder registered = holder(sessionId, "early@example.com", true);
        // Registered but not yet through a gate: parking is needed before arriving.
        assertEquals(HttpStatus.OK, status(() -> venue.holder(registered.resolved(), registered.session())));
        // The same link on a browser that has not answered the passkey opens nothing.
        assertEquals(HttpStatus.FORBIDDEN, status(() -> venue.holder(registered.resolved(), new MockHttpSession())));
    }

    @Test void onlyAWebAddressIsALink() {
        long sessionId = newSession();
        long link = id(venue.create(sessionId, Map.of("kind", "LINK", "title", "예약")));
        assertEquals(HttpStatus.BAD_REQUEST, status(() -> venue.update(sessionId, link, Map.of("title", "예약",
            "content", Map.of("url", "javascript:alert(1)")))));
        assertEquals(HttpStatus.OK, status(() -> venue.update(sessionId, link, Map.of("title", "예약",
            "content", Map.of("url", "https://example.com/locker")))));
    }

    @Test void aTimetableKeepsItsTimesInOrderAndRefusesNonsense() {
        long sessionId = newSession();
        long table = id(venue.create(sessionId, Map.of("kind", "TIMETABLE", "title", "타임테이블")));
        Map<String, Object> day = Map.of("label", "DAY 1", "date", "2026-11-21");
        assertEquals(HttpStatus.BAD_REQUEST, status(() -> venue.update(sessionId, table, Map.of("title", "t",
            "content", Map.of("days", List.of(day), "items", List.of(Map.of("day", 0, "start", "25:00", "title", "x")))))));
        assertEquals(HttpStatus.BAD_REQUEST, status(() -> venue.update(sessionId, table, Map.of("title", "t",
            "content", Map.of("days", List.of(day),
                "items", List.of(Map.of("day", 0, "start", "14:00", "end", "13:00", "title", "x")))))));

        Map<String, Object> saved = venue.update(sessionId, table, Map.of("title", "t", "content", Map.of(
            "days", List.of(day), "stages", List.of("메인"),
            "items", List.of(
                Map.of("day", 0, "start", "15:00", "title", "둘째", "stage", 0),
                Map.of("day", 0, "start", "11:00", "title", "첫째")))));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) ((Map<String, Object>) saved.get("content")).get("items");
        assertEquals(List.of("첫째", "둘째"), items.stream().map(item -> item.get("title")).toList());
    }

    @Test void placesKeepOnlyTheirSectionsMarksAndLoseAHiddenMap() throws Exception {
        long sessionId = newSession();
        String plan = venue.upload(sessionId, png()).get("imageId").toString();
        long map = id(venue.create(sessionId, Map.of("kind", "MAP", "title", "지도")));
        venue.update(sessionId, map, Map.of("title", "지도",
            "content", Map.of("floors", List.of(Map.of("name", "1층", "imageId", plan)))));
        long toilets = id(venue.create(sessionId, Map.of("kind", "PLACES", "title", "화장실")));
        venue.update(sessionId, toilets, Map.of("title", "화장실", "content", Map.of(
            "mapId", map, "tags", List.of("장애인용"),
            "places", List.of(Map.of("name", "로비", "floor", 0, "x", 40.04, "y", 60,
                "tags", List.of("장애인용", "아무거나"))))));
        venue.setVisible(sessionId, toilets, true);
        Holder holder = holder(sessionId, "places@example.com", true);

        @SuppressWarnings("unchecked")
        Map<String, Object> content = (Map<String, Object>) sections(venue.holder(holder.resolved(), holder.session()))
            .get(0).get("content");
        // The map is still hidden, so the list is shown without it.
        assertNull(content.get("mapId"));
        @SuppressWarnings("unchecked")
        Map<String, Object> place = ((List<Map<String, Object>>) content.get("places")).get(0);
        assertEquals(List.of("장애인용"), place.get("tags"));
        assertEquals(40.0, ((Number) place.get("x")).doubleValue());
        // A hidden map's plan is not sent to a holder either.
        assertEquals(HttpStatus.NOT_FOUND, status(() -> venue.holderImage(holder.resolved(), holder.session(), plan)));

        venue.setVisible(sessionId, map, true);
        @SuppressWarnings("unchecked")
        Map<String, Object> linked = (Map<String, Object>) sections(venue.holder(holder.resolved(), holder.session()))
            .stream().filter(s -> "PLACES".equals(s.get("kind"))).findFirst().orElseThrow().get("content");
        assertEquals(map, ((Number) linked.get("mapId")).longValue());
        assertEquals(HttpStatus.OK, status(() -> venue.holderImage(holder.resolved(), holder.session(), plan)));
    }

    @Test void aPlanFromAnotherEventCannotBeBorrowed() throws Exception {
        long mine = newSession();
        long theirs = newSession();
        String theirPlan = venue.upload(theirs, png()).get("imageId").toString();
        long map = id(venue.create(mine, Map.of("kind", "MAP", "title", "지도")));
        assertEquals(HttpStatus.BAD_REQUEST, status(() -> venue.update(mine, map, Map.of("title", "지도",
            "content", Map.of("floors", List.of(Map.of("name", "1층", "imageId", theirPlan)))))));
        assertEquals(HttpStatus.NOT_FOUND, status(() -> venue.consoleImage(mine, theirPlan)));
    }

    @Test void aReplacedPlanIsRemoved() throws Exception {
        long sessionId = newSession();
        String first = venue.upload(sessionId, png()).get("imageId").toString();
        String second = venue.upload(sessionId, png()).get("imageId").toString();
        long map = id(venue.create(sessionId, Map.of("kind", "MAP", "title", "지도")));
        venue.update(sessionId, map, Map.of("title", "지도",
            "content", Map.of("floors", List.of(Map.of("name", "1층", "imageId", first)))));
        venue.update(sessionId, map, Map.of("title", "지도",
            "content", Map.of("floors", List.of(Map.of("name", "1층", "imageId", second)))));
        assertTrue(repo.image(first).isEmpty());
        assertTrue(repo.image(second).isPresent());
        venue.delete(sessionId, map);
        assertTrue(repo.image(second).isEmpty());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> shapesOf(Map<String, Object> section) {
        Map<String, Object> content = (Map<String, Object>) section.get("content");
        return (List<Map<String, Object>>) ((List<Map<String, Object>>) content.get("floors")).get(0).get("shapes");
    }

    @Test void aDrawnFloorKeepsOnlyWhatTheToolsMake() {
        long sessionId = newSession();
        long map = id(venue.create(sessionId, Map.of("kind", "MAP", "title", "지도")));
        Map<String, Object> area = Map.of("id", "a1", "type", "area", "name", "A홀", "color", "#315CF4",
            "desc", "부스", "pts", List.of(List.of(40, 40), List.of(520.04, 40), List.of(520, 380)), "onclick", "x");
        Map<String, Object> line = Map.of("id", "a1", "type", "line", "pts", List.of(List.of(500, 690), List.of(500, 600)));
        Map<String, Object> label = Map.of("type", "label", "x", 500, "y", 672, "name", "정문");
        Map<String, Object> saved = venue.update(sessionId, map, Map.of("title", "지도", "content", Map.of("floors",
            List.of(Map.of("name", "1층", "height", 700, "shapes", List.of(area, line, label))))));

        List<Map<String, Object>> shapes = shapesOf(saved);
        assertEquals(3, shapes.size());
        // Only the fields a kind allows survive, in the console's own colour spelling.
        assertFalse(shapes.get(0).containsKey("onclick"));
        assertEquals("#315cf4", shapes.get(0).get("color"));
        assertEquals(List.of(520.0, 40.0), ((List<?>) shapes.get(0).get("pts")).get(1));
        // A repeated id is replaced, so each shape can still be told apart.
        assertNotEquals(shapes.get(0).get("id"), shapes.get(1).get("id"));
        assertEquals("정문", shapes.get(2).get("name"));
    }

    @Test void aDrawingThatCannotBeShownIsRefused() {
        long sessionId = newSession();
        long map = id(venue.create(sessionId, Map.of("kind", "MAP", "title", "지도")));
        java.util.function.Function<Map<String, Object>, HttpStatus> save = shape -> status(() -> venue.update(sessionId, map,
            Map.of("title", "지도", "content", Map.of("floors", List.of(Map.of("name", "1층", "height", 700, "shapes", List.of(shape)))))));
        List<List<Integer>> triangle = List.of(List.of(0, 0), List.of(100, 0), List.of(0, 100));

        assertEquals(HttpStatus.BAD_REQUEST, save.apply(Map.of("type", "area", "color", "red", "pts", triangle)));
        assertEquals(HttpStatus.BAD_REQUEST, save.apply(Map.of("type", "area", "color", "#315cf4",
            "pts", List.of(List.of(0, 0), List.of(100, 0)))));
        // Below the floor's height is off the plan.
        assertEquals(HttpStatus.BAD_REQUEST, save.apply(Map.of("type", "area", "color", "#315cf4",
            "pts", List.of(List.of(0, 0), List.of(100, 0), List.of(0, 900)))));
        assertEquals(HttpStatus.BAD_REQUEST, save.apply(Map.of("type", "label", "x", 10, "y", 10)));
        assertEquals(HttpStatus.BAD_REQUEST, save.apply(Map.of("type", "circle", "pts", triangle)));
        assertEquals(HttpStatus.OK, save.apply(Map.of("type", "area", "color", "#315cf4", "pts", triangle)));
    }

    @Test void reorderingMustNameEverySectionOnce() {
        long sessionId = newSession();
        long a = id(venue.create(sessionId, Map.of("kind", "TEXT", "title", "가")));
        long b = id(venue.create(sessionId, Map.of("kind", "TEXT", "title", "나")));
        assertEquals(HttpStatus.CONFLICT, status(() -> venue.reorder(sessionId, List.of(a))));
        venue.reorder(sessionId, List.of(b, a));
        assertEquals(List.of("나", "가"),
            sections(venue.console(sessionId)).stream().map(s -> s.get("title")).toList());
    }

    @Test void anotherEventsSectionIsNotFound() {
        long mine = newSession();
        long theirs = newSession();
        long section = id(venue.create(theirs, Map.of("kind", "TEXT", "title", "남의 것")));
        assertEquals(HttpStatus.NOT_FOUND, status(() -> venue.setVisible(mine, section, true)));
        assertEquals(HttpStatus.NOT_FOUND, status(() -> venue.delete(mine, section)));
    }
}
