package com.plink.ticket;

import com.plink.account.AdminAccountRepository;
import com.plink.account.AdminAccountService;
import com.plink.ticket.community.CommunityRepository;
import com.plink.ticket.community.CommunityService;
import com.plink.ticket.repository.EventSessionRepository;
import com.plink.ticket.repository.HolderRepository;
import com.plink.ticket.repository.TicketRepository;
import com.plink.ticket.service.Secrets;
import com.plink.ticket.service.TicketPasskeyService;
import com.plink.ticket.service.TicketService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Putting a ticket out of the chat: what it can no longer do, and what the room no longer sees. */
// Isolated from ./.env: the suite must not depend on a developer's local configuration.
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:chatbantest;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.config.import=", "plink.admin.email=", "plink.admin.password=", "plink.auth.base-url=http://localhost:3000"})
@AutoConfigureMockMvc
@Import(RecordingEmail.class)
class ChatBanTest {
    @Autowired CommunityService community;
    @Autowired CommunityRepository repo;
    @Autowired TicketService tickets;
    @Autowired TicketRepository ticketRows;
    @Autowired TicketPasskeyService passkeys;
    @Autowired HolderRepository holders;
    @Autowired EventSessionRepository sessions;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired AdminAccountService accounts;
    @Autowired AdminAccountRepository accountRows;

    private static final String PASSWORD = "a-long-enough-password";

    private long newSession() {
        long sessionId = sessions.insert("차단 이벤트 " + Secrets.randomAlnum(6), "메인홀",
            Timestamp.from(Instant.now().plus(1, ChronoUnit.HOURS)), null);
        // No cooldown, so one holder can write several lines in a test.
        repo.saveSettings(sessionId, new CommunityRepository.Settings(true, 0, 200, 20, 10, 3));
        return sessionId;
    }

    private record Holder(TicketService.Resolved resolved, MockHttpSession session, long ticketId) {}

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

    private static HttpStatus outcome(Runnable call) {
        try { call.run(); return HttpStatus.OK; }
        catch (ResponseStatusException e) { return HttpStatus.valueOf(e.getStatusCode().value()); }
    }

    private long say(Holder h, String body, Long replyTo) {
        return ((Number) community.post(h.resolved(), h.session(), body, replyTo).get("id")).longValue();
    }

    private List<Map<?, ?>> feed(Holder h) {
        return ((List<?>) community.chat(h.resolved(), h.session(), 0).get("messages")).stream()
            .<Map<?, ?>>map(m -> (Map<?, ?>) m).toList();
    }

    private static boolean shows(List<Map<?, ?>> rows, long id) {
        return rows.stream().anyMatch(m -> ((Number) m.get("id")).longValue() == id);
    }

    @Test void aBannedTicketNeitherWritesNorPressesAndIsToldSo() {
        long sessionId = newSession();
        Holder a = inside(sessionId, "talker@example.com");
        Holder b = inside(sessionId, "banned@example.com");
        long id = say(a, "아무 말", null);
        community.setBanned(sessionId, b.ticketId(), true);

        assertEquals(HttpStatus.FORBIDDEN, outcome(() -> community.post(b.resolved(), b.session(), "다시", null)));
        assertEquals(HttpStatus.FORBIDDEN, outcome(() -> community.like(b.resolved(), b.session(), id)));
        assertEquals(HttpStatus.FORBIDDEN, outcome(() -> community.dislike(b.resolved(), b.session(), id)));
        assertEquals(HttpStatus.FORBIDDEN, outcome(() -> community.report(b.resolved(), b.session(), id)));
        Map<?, ?> me = (Map<?, ?>) community.chat(b.resolved(), b.session(), 0).get("me");
        assertEquals(false, me.get("canWrite"));
        assertEquals(true, me.get("banned"));
        assertEquals("운영자가 이 입장권의 채팅을 차단했어요.", me.get("blocked"));

        community.setBanned(sessionId, b.ticketId(), false);
        assertEquals(HttpStatus.OK, outcome(() -> community.like(b.resolved(), b.session(), id)));
        assertEquals(false, ((Map<?, ?>) community.chat(b.resolved(), b.session(), 0).get("me")).get("banned"));
    }

    @Test void aBanHidesEveryLineAndQuoteFromHoldersButNotTheConsoleAndUnbanBringsThemBack() {
        long sessionId = newSession();
        Holder rude = inside(sessionId, "rude@example.com");
        Holder reader = inside(sessionId, "reader@example.com");
        long first = say(rude, "첫 줄", null);
        long second = say(rude, "둘째 줄", null);
        long answer = say(reader, "그게 무슨 말이에요?", first);
        // One line already hidden on its own stays hidden after the ban is lifted.
        community.setHidden(sessionId, second, true);

        community.setBanned(sessionId, rude.ticketId(), true);
        List<Map<?, ?>> seen = feed(reader);
        assertFalse(shows(seen, first));
        assertTrue(shows(seen, answer), "the reply itself stays");
        Map<?, ?> reply = seen.stream().filter(m -> ((Number) m.get("id")).longValue() == answer).findFirst().orElseThrow();
        assertEquals("가려진 메시지예요.", ((Map<?, ?>) reply.get("reply")).get("body"));
        assertEquals(HttpStatus.NOT_FOUND, outcome(() -> community.like(reader.resolved(), reader.session(), first)));
        assertEquals(HttpStatus.BAD_REQUEST, outcome(() -> community.post(reader.resolved(), reader.session(), "답", first)));
        Map<String, Object> summary = community.summary(reader.resolved(), reader.session());
        assertEquals(answer, ((Number) summary.get("latestChatId")).longValue());

        // The console still sees every line, marked.
        List<?> console = (List<?>) community.consoleChat(sessionId, "ALL").get("messages");
        Map<?, ?> row = console.stream().map(m -> (Map<?, ?>) m)
            .filter(m -> ((Number) m.get("id")).longValue() == first).findFirst().orElseThrow();
        assertEquals(true, row.get("banned"));
        assertEquals(false, row.get("hidden"), "a ban flips no line's own flag");

        community.setBanned(sessionId, rude.ticketId(), false);
        seen = feed(reader);
        assertTrue(shows(seen, first));
        assertFalse(shows(seen, second), "what was hidden before stays hidden");
        reply = seen.stream().filter(m -> ((Number) m.get("id")).longValue() == answer).findFirst().orElseThrow();
        assertEquals("첫 줄", ((Map<?, ?>) reply.get("reply")).get("body"));
    }

    @Test void aBannedLineLeavesThePopularBar() {
        long sessionId = newSession();
        repo.saveSettings(sessionId, new CommunityRepository.Settings(true, 0, 200, 1, 10, 3));
        Holder a = inside(sessionId, "popular@example.com");
        Holder b = inside(sessionId, "fan@example.com");
        long id = say(a, "인기 있는 말", null);
        community.like(b.resolved(), b.session(), id);
        assertNotNull(community.chat(b.resolved(), b.session(), 0).get("hot"));
        community.setBanned(sessionId, a.ticketId(), true);
        assertNull(community.chat(b.resolved(), b.session(), 0).get("hot"));
    }

    @Test void theConsoleListsChatPeoplePagedAndSearchable() {
        long sessionId = newSession();
        Holder a = inside(sessionId, "first-person@example.com");
        Holder b = inside(sessionId, "second-person@example.com");
        Holder quiet = inside(sessionId, "quiet@example.com");
        inside(sessionId, "never@example.com");
        say(a, "하나", null);
        say(a, "둘", null);
        say(b, "셋", null);
        community.setMuted(sessionId, quiet.ticketId(), true);
        community.setBanned(sessionId, b.ticketId(), true);

        Map<String, Object> all = community.chatPeople(sessionId, "", 0, 20);
        assertEquals(3, all.get("total"), "writers and the muted, not everyone with a ticket");
        Map<String, Object> page = community.chatPeople(sessionId, "", 1, 2);
        assertEquals(1, ((List<?>) page.get("items")).size());
        assertEquals(1, page.get("page"));

        Map<?, ?> found = (Map<?, ?>) ((List<?>) community.chatPeople(sessionId, "FIRST-PERSON", 0, 20).get("items")).get(0);
        assertEquals(a.ticketId(), ((Number) found.get("ticketId")).longValue());
        assertEquals(2, found.get("messages"));
        assertEquals(CommunityService.nickname(sessionId, a.ticketId()), found.get("author"));
        assertNull(found.get("email"), "emails find someone, they are not sent back");

        String alias = CommunityService.nickname(sessionId, b.ticketId());
        List<?> byAlias = (List<?>) community.chatPeople(sessionId, alias, 0, 20).get("items");
        assertTrue(byAlias.stream().anyMatch(m -> ((Number) ((Map<?, ?>) m).get("ticketId")).longValue() == b.ticketId()
            && Boolean.TRUE.equals(((Map<?, ?>) m).get("banned"))));
        String ref = ticketRows.findById(quiet.ticketId()).orElseThrow().ticketRef;
        Map<?, ?> muted = (Map<?, ?>) ((List<?>) community.chatPeople(sessionId, ref, 0, 20).get("items")).get(0);
        assertEquals(true, muted.get("muted"));
        assertEquals(0, muted.get("messages"));
        assertNull(muted.get("lastAt"));
    }

    @Test void onlyAnOrganiserReachesThePeopleListAndTheBan() throws Exception {
        long sessionId = newSession();
        Holder a = inside(sessionId, "mvc@example.com");
        String ban = "{\"ticketId\":" + a.ticketId() + ",\"banned\":true}";

        mvc.perform(get("/api/admin/sessions/" + sessionId + "/chat/people"))
            .andExpect(result -> assertTrue(result.getResponse().getStatus() == 401 || result.getResponse().getStatus() == 403));
        MockHttpSession linksOnly = signIn("ban-links-only@example.com", false);
        mvc.perform(get("/api/admin/sessions/" + sessionId + "/chat/people").session(linksOnly))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/sessions/" + sessionId + "/chat/ban").session(linksOnly).with(csrf())
                .contentType("application/json").content(ban))
            .andExpect(status().isForbidden());
        assertFalse(repo.banned(sessionId, a.ticketId()));

        MockHttpSession organiser = signIn("ban-organiser@example.com", true);
        mvc.perform(post("/api/admin/sessions/" + sessionId + "/chat/ban").session(organiser).with(csrf())
                .contentType("application/json").content(ban))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ticketId").value(a.ticketId()))
            .andExpect(jsonPath("$.banned").value(true));
        assertTrue(repo.banned(sessionId, a.ticketId()));
        mvc.perform(get("/api/admin/sessions/" + sessionId + "/chat/people?q=mvc@&page=0&size=10").session(organiser))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].banned").value(true))
            .andExpect(jsonPath("$.items[0].muted").value(false));
    }

    private MockHttpSession signIn(String email, boolean tickets) throws Exception {
        accountRows.findByEmail(email).orElseGet(() -> {
            accounts.create(email, PASSWORD, null, true, tickets, false);
            return accountRows.findByEmail(email).orElseThrow();
        });
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).with(csrf())
                .contentType("application/json")
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk());
        return session;
    }
}
