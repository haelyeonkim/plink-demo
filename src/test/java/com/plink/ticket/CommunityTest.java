package com.plink.ticket;

import com.plink.ticket.community.CommunityRepository;
import com.plink.ticket.community.CommunityService;
import com.plink.ticket.repository.AdmissionRepository;
import com.plink.ticket.repository.BoothRepository;
import com.plink.ticket.repository.EventSessionRepository;
import com.plink.ticket.repository.GateRepository;
import com.plink.ticket.repository.HolderRepository;
import com.plink.ticket.repository.TicketRepository;
import com.plink.ticket.service.CouponService;
import com.plink.ticket.service.GateAuthService;
import com.plink.ticket.service.Secrets;
import com.plink.ticket.service.TicketPasskeyService;
import com.plink.ticket.service.TicketService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The room's chat and board: who may read, who may write, and what the rules do. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:communitytest;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.config.import=", "plink.admin.email=", "plink.admin.password=",
    "plink.images.backend=local", "plink.images.directory=target/test-ad-images"})
@Import(RecordingEmail.class)
class CommunityTest {
    @Autowired CommunityService community;
    @Autowired CommunityRepository repo;
    @Autowired TicketService tickets;
    @Autowired TicketRepository ticketRows;
    @Autowired TicketPasskeyService passkeys;
    @Autowired HolderRepository holders;
    @Autowired EventSessionRepository sessions;
    @Autowired AdmissionRepository admissions;
    @Autowired BoothRepository booths;
    @Autowired GateRepository gates;
    @Autowired GateAuthService gateAuth;
    @Autowired JdbcTemplate jdbc;

    private long newSession() {
        return sessions.insert("소통 이벤트 " + Secrets.randomAlnum(6), "메인홀",
            Timestamp.from(Instant.now().plus(1, ChronoUnit.HOURS)), null);
    }

    /** A holder with a registered ticket, proven on this browser. */
    private record Holder(TicketService.Resolved resolved, MockHttpSession session, long ticketId) {}

    private Holder holder(long sessionId, String email) {
        Map<String, Object> issued = tickets.issue(sessionId, email, null, null, null);
        String url = issued.get("url").toString();
        String token = url.substring(url.lastIndexOf('/') + 1);
        long ticketId = ((Number) issued.get("ticketId")).longValue();
        long holderId = holders.findByEmail(email).map(h -> h.id)
            .orElseGet(() -> holders.create(email, tickets.userHandleFor(email)));
        ticketRows.bind(ticketId, holderId, email);
        MockHttpSession session = new MockHttpSession();
        passkeys.markViewer(session, ticketRows.findById(ticketId).orElseThrow());
        return new Holder(tickets.resolve(sessionId, token), session, ticketId);
    }

    private Holder inside(long sessionId, String email) {
        Map<String, Object> issued = tickets.issue(sessionId, email, null, null, null);
        String url = issued.get("url").toString();
        String token = url.substring(url.lastIndexOf('/') + 1);
        long ticketId = ((Number) issued.get("ticketId")).longValue();
        long holderId = holders.findByEmail(email).map(h -> h.id)
            .orElseGet(() -> holders.create(email, tickets.userHandleFor(email)));
        ticketRows.bind(ticketId, holderId, email);
        jdbc.update("UPDATE ticket_presence SET state = 'INSIDE', entry_count = 1 WHERE ticket_id = ?", ticketId);
        MockHttpSession session = new MockHttpSession();
        passkeys.markViewer(session, ticketRows.findById(ticketId).orElseThrow());
        return new Holder(tickets.resolve(sessionId, token), session, ticketId);
    }

    private static HttpStatus status(Runnable call) {
        try { call.run(); return HttpStatus.OK; }
        catch (ResponseStatusException e) { return HttpStatus.valueOf(e.getStatusCode().value()); }
    }

    @Test void onlyAHolderWhoHasComeInCanReadTheRoom() {
        long sessionId = newSession();
        Holder outside = holder(sessionId, "outside@example.com");
        assertEquals(HttpStatus.FORBIDDEN, status(() -> community.chat(outside.resolved(), outside.session(), 0)));
        assertEquals(HttpStatus.FORBIDDEN, status(() -> community.ads(outside.resolved(), outside.session())));
        assertEquals(false, community.summary(outside.resolved(), outside.session()).get("entered"));

        Holder in = inside(sessionId, "in@example.com");
        // The link alone, on a browser that has not answered the passkey, opens nothing.
        assertEquals(HttpStatus.FORBIDDEN, status(() -> community.chat(in.resolved(), new MockHttpSession(), 0)));
        assertEquals(true, community.summary(in.resolved(), in.session()).get("entered"));
        assertNotNull(community.chat(in.resolved(), in.session(), 0).get("messages"));
    }

    @Test void oneMessageAMinuteAndOnlyFromInside() {
        long sessionId = newSession();
        Holder in = inside(sessionId, "talk@example.com");
        Map<String, Object> sent = community.post(in.resolved(), in.session(), "안녕하세요", null);
        assertEquals(CommunityService.nickname(sessionId, in.ticketId()), sent.get("author"));
        assertEquals(true, sent.get("mine"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, status(() -> community.post(in.resolved(), in.session(), "또", null)));

        // Out of the room: the chat closes, reading included, while the board stays open.
        jdbc.update("UPDATE ticket_presence SET state = 'OUTSIDE' WHERE ticket_id = ?", in.ticketId());
        jdbc.update("UPDATE chat_message SET created_at = ? WHERE ticket_id = ?",
            Timestamp.from(Instant.now().minus(2, ChronoUnit.MINUTES)), in.ticketId());
        assertEquals(HttpStatus.FORBIDDEN, status(() -> community.chat(in.resolved(), in.session(), 0)));
        assertEquals(HttpStatus.FORBIDDEN, status(() -> community.post(in.resolved(), in.session(), "밖에서", null)));
        assertNotNull(community.ads(in.resolved(), in.session()));
        Map<String, Object> summary = community.summary(in.resolved(), in.session());
        assertEquals(true, summary.get("entered"));
        assertEquals(false, summary.get("chatOpen"));
        assertNull(summary.get("lastMessage"), "nothing of the talk reaches someone outside");

        // Back in, it opens again.
        jdbc.update("UPDATE ticket_presence SET state = 'INSIDE' WHERE ticket_id = ?", in.ticketId());
        Map<String, Object> me = (Map<String, Object>) community.chat(in.resolved(), in.session(), 0).get("me");
        assertEquals(true, me.get("canWrite"));
    }

    @Test void repliesQuoteAndLikesPinAPopularLineOnce() {
        long sessionId = newSession();
        repo.saveSettings(sessionId, new CommunityRepository.Settings(true, 0, 200, 2, 10, 3));
        Holder a = inside(sessionId, "a@example.com");
        Holder b = inside(sessionId, "b@example.com");
        long first = ((Number) community.post(a.resolved(), a.session(), "노을 예뻐요", null).get("id")).longValue();
        Map<String, Object> reply = community.post(b.resolved(), b.session(), "어디요?", first);
        assertEquals("노을 예뻐요", ((Map<?, ?>) reply.get("reply")).get("body"));

        assertEquals(HttpStatus.BAD_REQUEST, status(() -> community.like(a.resolved(), a.session(), first)),
            "nobody likes their own line");
        assertEquals(HttpStatus.BAD_REQUEST, status(() -> community.dislike(a.resolved(), a.session(), first)));
        assertEquals(true, community.like(b.resolved(), b.session(), first).get("liked"));
        assertEquals(false, community.like(b.resolved(), b.session(), first).get("liked"), "a second tap takes it back");

        // A dislike takes a like back, and the other way round.
        Holder c = inside(sessionId, "c@example.com");
        community.like(c.resolved(), c.session(), first);
        Map<String, Object> switched = community.dislike(c.resolved(), c.session(), first);
        assertEquals(0, switched.get("likes"));
        assertEquals(1, switched.get("dislikes"));
        Map<?, ?> seen = (Map<?, ?>) ((List<?>) community.chat(c.resolved(), c.session(), 0).get("messages")).get(0);
        assertEquals(true, seen.get("disliked"));
        assertEquals(false, seen.get("liked"));
        community.like(c.resolved(), c.session(), first);
        community.like(b.resolved(), b.session(), first);
        Map<String, Object> hot = (Map<String, Object>) community.chat(a.resolved(), a.session(), 0).get("hot");
        assertNotNull(hot, "two likes reach this event's bar");
        assertEquals(first, ((Number) hot.get("id")).longValue());
    }

    @Test void reportsHideALineAndMutingStopsWriting() {
        long sessionId = newSession();
        repo.saveSettings(sessionId, new CommunityRepository.Settings(true, 0, 200, 20, 10, 1));
        Holder a = inside(sessionId, "rude@example.com");
        Holder b = inside(sessionId, "reader@example.com");
        long id = ((Number) community.post(a.resolved(), a.session(), "별로인 말", null).get("id")).longValue();
        assertEquals(HttpStatus.BAD_REQUEST, status(() -> community.report(a.resolved(), a.session(), id)),
            "nobody reports their own line");
        community.report(b.resolved(), b.session(), id);
        List<?> visible = (List<?>) community.chat(b.resolved(), b.session(), 0).get("messages");
        assertTrue(visible.stream().noneMatch(m -> ((Map<?, ?>) m).get("id").equals(id)), "one report hides it here");

        community.setMuted(sessionId, a.ticketId(), true);
        assertEquals(HttpStatus.FORBIDDEN, status(() -> community.post(a.resolved(), a.session(), "다시", null)));
        community.setMuted(sessionId, a.ticketId(), false);
        assertEquals(HttpStatus.OK, status(() -> community.post(a.resolved(), a.session(), "다시", null)));
    }

    @Test void aMutedTicketOrAClosedChatNeitherReactsNorReports() {
        long sessionId = newSession();
        Holder a = inside(sessionId, "said@example.com");
        Holder b = inside(sessionId, "muted@example.com");
        long id = ((Number) community.post(a.resolved(), a.session(), "아무 말", null).get("id")).longValue();
        community.setMuted(sessionId, b.ticketId(), true);
        assertEquals(HttpStatus.FORBIDDEN, status(() -> community.like(b.resolved(), b.session(), id)));
        assertEquals(HttpStatus.FORBIDDEN, status(() -> community.dislike(b.resolved(), b.session(), id)));
        assertEquals(HttpStatus.FORBIDDEN, status(() -> community.report(b.resolved(), b.session(), id)));
        community.setMuted(sessionId, b.ticketId(), false);
        community.saveSettings(sessionId, Map.of("enabled", false));
        assertEquals(HttpStatus.FORBIDDEN, status(() -> community.like(b.resolved(), b.session(), id)));
        community.saveSettings(sessionId, Map.of("enabled", true));
        assertEquals(HttpStatus.OK, status(() -> community.like(b.resolved(), b.session(), id)));
        CommunityRepository.Message m = repo.findMessage(id).orElseThrow();
        assertEquals(1, m.likes());
        assertEquals(0, m.reports());
    }

    @Test void anUnhiddenLineNeedsFreshReportsToHideAgain() {
        long sessionId = newSession();
        repo.saveSettings(sessionId, new CommunityRepository.Settings(true, 0, 200, 20, 10, 2));
        Holder a = inside(sessionId, "reported@example.com");
        Holder b = inside(sessionId, "r1@example.com");
        Holder c = inside(sessionId, "r2@example.com");
        Holder d = inside(sessionId, "r3@example.com");
        long id = ((Number) community.post(a.resolved(), a.session(), "애매한 말", null).get("id")).longValue();
        community.report(b.resolved(), b.session(), id);
        community.report(b.resolved(), b.session(), id);
        assertFalse(repo.findMessage(id).orElseThrow().hidden(), "one ticket reporting twice is one report");
        community.report(c.resolved(), c.session(), id);
        assertTrue(repo.findMessage(id).orElseThrow().hidden());

        community.setHidden(sessionId, id, false);
        assertEquals(0, repo.findMessage(id).orElseThrow().reports());
        community.report(d.resolved(), d.session(), id);
        assertFalse(repo.findMessage(id).orElseThrow().hidden(), "one new report does not hide it straight back");
        // Those who reported before may report again; it is a new look at the line.
        community.report(b.resolved(), b.session(), id);
        assertTrue(repo.findMessage(id).orElseThrow().hidden());
    }

    @Test void likesAndDislikesFromOneTicketNeverPileUpOrCoexist() throws Exception {
        long sessionId = newSession();
        Holder a = inside(sessionId, "line@example.com");
        Holder b = inside(sessionId, "tapper@example.com");
        long id = ((Number) community.post(a.resolved(), a.session(), "눌러 보세요", null).get("id")).longValue();
        int rounds = 15;
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(3);
        List<java.util.concurrent.Future<?>> taps = new java.util.ArrayList<>();
        for (int t = 0; t < 3; t++) {
            int which = t;
            taps.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < rounds; i++) {
                    if (which == 2) community.dislike(b.resolved(), b.session(), id);
                    else community.like(b.resolved(), b.session(), id);
                }
                return null;
            }));
        }
        start.countDown();
        for (var tap : taps) tap.get(60, java.util.concurrent.TimeUnit.SECONDS);
        pool.shutdown();

        int likeRows = jdbc.queryForObject("SELECT COUNT(*) FROM chat_like WHERE message_id = ?", Integer.class, id);
        int dislikeRows = jdbc.queryForObject("SELECT COUNT(*) FROM chat_dislike WHERE message_id = ?", Integer.class, id);
        CommunityRepository.Message m = repo.findMessage(id).orElseThrow();
        assertTrue(likeRows + dislikeRows <= 1, "a ticket likes or dislikes a line, never both");
        assertEquals(likeRows, m.likes(), "the counter is the rows");
        assertEquals(dislikeRows, m.dislikes());
    }

    @Test void savingTheChatRulesKeepsOneRow() {
        long sessionId = newSession();
        community.saveSettings(sessionId, Map.of("cooldownSeconds", 5));
        community.saveSettings(sessionId, Map.of("maxLength", 50));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM chat_setting WHERE session_id = ?", Integer.class, sessionId));
        CommunityRepository.Settings saved = repo.settings(sessionId);
        assertEquals(5, saved.cooldownSeconds());
        assertEquals(50, saved.maxLength());
    }

    @Test void theConsoleFilesANoticeOnlyUnderItsOwnEventsBooth() {
        long sessionId = newSession();
        long elsewhere = newSession();
        long foreign = booths.insert(elsewhere, "남의 부스", null);
        assertEquals(HttpStatus.BAD_REQUEST, status(() -> community.createAd(sessionId, Map.of("category", "SALE",
            "title", "글", "body", "x", "boothId", foreign))));
        assertEquals(HttpStatus.NOT_FOUND, status(() -> community.createAd(sessionId, Map.of("category", "SALE",
            "title", "글", "body", "x", "boothId", 999_999_999L))));
    }

    @Test void aBoothAttachesOnlyItsOwnLoosePhotos() throws Exception {
        long sessionId = newSession();
        CouponService.Counter mine = boothGate(sessionId, booths.insert(sessionId, "우리 부스", null));
        CouponService.Counter theirs = boothGate(sessionId, booths.insert(sessionId, "옆 부스", null));
        String theirPhoto = community.boothUpload(theirs, png(10, 10)).get("imageId").toString();
        community.boothCreate(theirs, Map.of("category", "SALE", "title", "옆 글", "body", "x", "imageId", theirPhoto));
        assertEquals(HttpStatus.BAD_REQUEST, status(() -> community.boothCreate(mine, Map.of("category", "SALE",
            "title", "가져온 사진", "body", "x", "imageId", theirPhoto))));

        String myPhoto = community.boothUpload(mine, png(10, 10)).get("imageId").toString();
        long first = ((Number) community.boothCreate(mine, Map.of("category", "SALE", "title", "첫 글", "body", "x",
            "imageId", myPhoto)).get("id")).longValue();
        assertEquals(HttpStatus.BAD_REQUEST, status(() -> community.boothCreate(mine, Map.of("category", "SALE",
            "title", "둘째 글", "body", "x", "imageId", myPhoto))), "a photo already on a notice is that notice's");
        assertEquals(HttpStatus.OK, status(() -> community.boothUpdate(mine, first, Map.of("category", "SALE",
            "title", "첫 글 고침", "body", "x", "imageId", myPhoto))), "a notice keeps its own photo");
    }

    @Autowired com.plink.ticket.controller.TicketAdminController admin;

    @Test void deletingAnEventTakesItsPhotoFilesWithIt() throws Exception {
        long sessionId = newSession();
        String imageId = community.consoleUpload(sessionId, png(10, 10)).get("imageId").toString();
        community.createAd(sessionId, Map.of("category", "NOTICE", "title", "사진", "body", "x", "imageId", imageId));
        java.nio.file.Path file = java.nio.file.Path.of("target/test-ad-images", repo.findImage(imageId).orElseThrow().key());
        assertTrue(java.nio.file.Files.exists(file));
        admin.deleteSession(sessionId, true);
        assertTrue(repo.findImage(imageId).isEmpty());
        assertFalse(java.nio.file.Files.exists(file));
    }

    @Test void anOrganiserNoticeStaysOnTop() {
        long sessionId = newSession();
        Holder in = inside(sessionId, "notice@example.com");
        community.notice(sessionId, "오후 2시 라이브 드로잉", true, true, false);
        Map<String, Object> chat = community.chat(in.resolved(), in.session(), 0);
        Map<?, ?> notice = (Map<?, ?>) chat.get("notice");
        assertEquals("운영팀", notice.get("author"));
        assertEquals("오후 2시 라이브 드로잉", notice.get("body"));
        // Also on the board, pinned.
        Map<?, ?> top = community.ads(in.resolved(), in.session()).get(0);
        assertEquals("NOTICE", top.get("category"));
        assertEquals(true, top.get("pinned"));
    }

    @Test void aBoothPostsOnlyItsOwnAndOnlyWhatABoothMay() {
        long sessionId = newSession();
        long booth = booths.insert(sessionId, "굿즈 부스", "로비 왼쪽");
        long other = booths.insert(sessionId, "다른 부스", null);
        CouponService.Counter terminal = boothGate(sessionId, booth);
        Holder in = inside(sessionId, "ads@example.com");

        Map<String, Object> ad = community.boothCreate(terminal, Map.of("category", "SALE",
            "title", "엽서 2+1", "body", "오후 3시까지", "place", "로비 왼쪽"));
        assertEquals("LIVE", ad.get("state"));
        approve(sessionId, ad);
        assertEquals(HttpStatus.BAD_REQUEST, status(() -> community.boothCreate(terminal,
            Map.of("category", "NOTICE", "title", "공지", "body", "x"))), "the organiser's category is not a booth's");
        // Scheduled for later: not on the holder's board yet.
        approve(sessionId, community.boothCreate(terminal, Map.of("category", "PROGRAM", "title", "나중", "body", "x",
            "startsAt", Instant.now().plus(1, ChronoUnit.HOURS).toString())));
        List<Map<String, Object>> board = community.ads(in.resolved(), in.session());
        assertEquals(1, board.size());
        assertEquals("굿즈 부스", board.get(0).get("booth"));

        long otherAd = ((Number) community.createAd(sessionId, Map.of("category", "SALE", "title", "남의 글",
            "body", "x", "boothId", other)).get("id")).longValue();
        assertEquals(HttpStatus.NOT_FOUND, status(() -> community.boothEnd(terminal, otherAd)));
        community.boothEnd(terminal, ((Number) ad.get("id")).longValue());
        assertEquals(1, community.ads(in.resolved(), in.session()).size(), "only the other booth's is left");
    }

    @Test void anAlertReachesEveryHolderUntilTakenDown() {
        long sessionId = newSession();
        Holder outside = holder(sessionId, "alert-out@example.com");
        community.notice(sessionId, "채팅에만 있는 공지", true, false, false);
        assertNull(community.summary(outside.resolved(), outside.session()).get("alert"), "a plain notice stays in the chat");

        long id = ((Number) community.notice(sessionId, "입장이 10분 늦어져요", false, false, true).get("messageId")).longValue();
        Map<?, ?> alert = (Map<?, ?>) community.summary(outside.resolved(), outside.session()).get("alert");
        assertNotNull(alert, "an alert reaches a holder who has not come in yet");
        assertEquals("입장이 10분 늦어져요", alert.get("body"));
        assertEquals(false, community.summary(outside.resolved(), outside.session()).get("entered"));
        assertNull(community.summary(outside.resolved(), new MockHttpSession()).get("alert"),
            "the link alone, without the passkey, hears nothing");

        community.setAlert(sessionId, id, false);
        assertNull(community.summary(outside.resolved(), outside.session()).get("alert"));
        community.setAlert(sessionId, id, true);
        community.setHidden(sessionId, id, true);
        assertNull(community.summary(outside.resolved(), outside.session()).get("alert"), "a hidden notice is not raised");
        Holder in = inside(sessionId, "alert-in@example.com");
        long line = ((Number) community.post(in.resolved(), in.session(), "x", null).get("id")).longValue();
        assertEquals(HttpStatus.BAD_REQUEST, status(() -> community.setAlert(sessionId, line, true)),
            "only the organiser's notices go on tickets");
    }

    @Test void aPhotoIsRedrawnAndShownOnlyToTheRoom() throws Exception {
        long sessionId = newSession();
        long booth = booths.insert(sessionId, "사진 부스", null);
        CouponService.Counter terminal = boothGate(sessionId, booth);
        Holder in = inside(sessionId, "photo-in@example.com");
        Holder outside = holder(sessionId, "photo-out@example.com");

        String imageId = community.boothUpload(terminal, png(2400, 1200)).get("imageId").toString();
        long adId = approve(sessionId, community.boothCreate(terminal, Map.of("category", "SALE", "title", "사진 글",
            "body", "x", "imageId", imageId)));
        assertEquals(imageId, community.ads(in.resolved(), in.session()).get(0).get("imageId"));

        var photo = community.holderImage(in.resolved(), in.session(), adId);
        assertEquals("image/jpeg", photo.type());
        BufferedImage drawn = ImageIO.read(new ByteArrayInputStream(photo.bytes()));
        assertEquals(1600, drawn.getWidth(), "drawn again no wider than a phone needs");
        assertEquals(800, drawn.getHeight());
        assertEquals(HttpStatus.FORBIDDEN, status(() -> community.holderImage(outside.resolved(), outside.session(), adId)));

        // A photo from another event cannot be attached here, and a file that is no picture is refused.
        long elsewhere = newSession();
        String foreign = community.consoleUpload(elsewhere, png(10, 10)).get("imageId").toString();
        assertEquals(HttpStatus.BAD_REQUEST, status(() -> community.boothCreate(terminal, Map.of("category", "SALE",
            "title", "남의 사진", "body", "x", "imageId", foreign))));
        assertEquals(HttpStatus.BAD_REQUEST, status(() -> community.boothUpload(terminal,
            new MockMultipartFile("file", "x.jpg", "image/jpeg", "<svg/>".getBytes()))));

        // Taking the photo off the notice removes it.
        community.boothUpdate(terminal, adId, Map.of("category", "SALE", "title", "사진 글", "body", "x"));
        assertTrue(repo.findImage(imageId).isEmpty());
    }

    /** The organiser lets a booth's notice onto the board. */
    private long approve(long sessionId, Map<String, Object> ad) {
        long id = ((Number) ad.get("id")).longValue();
        community.review(sessionId, id, "APPROVE", null);
        return id;
    }

    private static MockMultipartFile png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return new MockMultipartFile("file", "photo.png", "image/png", out.toByteArray());
    }

    private CouponService.Counter boothGate(long sessionId, long boothId) {
        String id = "b" + Secrets.randomAlnum(10);
        gates.insert(id, sessionId, "부스 단말", "메인홀", "BIDIRECTIONAL",
            gateAuth.hash("t-" + id), null, null, "BOOTH", boothId);
        return CouponService.Counter.at(gates.findById(id).orElseThrow());
    }
}
