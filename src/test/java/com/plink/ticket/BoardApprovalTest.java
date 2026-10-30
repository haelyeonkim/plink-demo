package com.plink.ticket;

import com.jayway.jsonpath.JsonPath;
import com.plink.account.AdminAccount;
import com.plink.account.AdminPrincipal;
import com.plink.ticket.community.CommunityService;
import com.plink.ticket.repository.BoothRepository;
import com.plink.ticket.repository.EventSessionRepository;
import com.plink.ticket.repository.GateRepository;
import com.plink.ticket.repository.HolderRepository;
import com.plink.ticket.repository.TicketRepository;
import com.plink.ticket.service.GateAuthService;
import com.plink.ticket.service.Secrets;
import com.plink.ticket.service.TicketPasskeyService;
import com.plink.ticket.service.TicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * A booth's notices wait for the organiser: written from the booth's terminal or by a
 * booth manager, they reach holders only once approved, and go back to waiting when the
 * booth changes them. Each booth sees and changes its own notices alone.
 */
// Isolated from ./.env: the suite must not depend on whichever origin, secret or
// face service a developer happens to have configured locally.
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:boardapprovaltest;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.config.import=", "plink.admin.email=", "plink.admin.password=",
    "plink.auth.base-url=http://localhost:3000", "plink.ticket.token-secret=board-approval-test-secret",
    "plink.images.backend=local", "plink.images.directory=target/test-ad-images"})
@AutoConfigureMockMvc
@Import(RecordingEmail.class)
class BoardApprovalTest {
    private static final String ORIGIN = "http://localhost:3000";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired RecordingEmail.Mailbox mailbox;
    @Autowired EventSessionRepository sessions;
    @Autowired BoothRepository booths;
    @Autowired GateRepository gates;
    @Autowired GateAuthService gateAuth;
    @Autowired TicketService tickets;
    @Autowired TicketRepository ticketRows;
    @Autowired HolderRepository holders;
    @Autowired TicketPasskeyService passkeys;
    @Autowired CommunityService community;

    private long sessionId;
    private long boothId;
    private long otherBoothId;

    @BeforeEach void event() {
        for (long id : new long[] { 986, 987 }) {
            jdbc.update("MERGE INTO admin_account (id, email, password_hash, slug, can_links, can_tickets, is_owner) "
                + "KEY (id) VALUES (?, ?, 'unused', ?, TRUE, ?, FALSE)",
                id, "admin" + id + "@example.com", "admin-" + id, id == 986);
        }
        mailbox.clear();
        sessionId = sessions.insert("알림판 승인 이벤트 " + Secrets.randomAlnum(6), "메인홀",
            Timestamp.from(Instant.now().plus(2, ChronoUnit.HOURS)), null);
        boothId = booths.insert(sessionId, "커피 스탠드", "입구 옆");
        otherBoothId = booths.insert(sessionId, "굿즈 부스", null);
    }

    // ---- Who is asking ----

    /** An organiser with the ticket console, or one without it. */
    private static RequestPostProcessor admin(boolean tickets) {
        AdminAccount account = new AdminAccount();
        account.id = tickets ? 986 : 987;
        account.email = "admin" + account.id + "@example.com";
        account.passwordHash = "unused";
        account.canLinks = true;
        account.canTickets = tickets;
        AdminPrincipal principal = new AdminPrincipal(account);
        return authentication(new UsernamePasswordAuthenticationToken(principal, null,
            principal.authorities().stream().map(SimpleGrantedAuthority::new).toList()));
    }

    private ResultActions json(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(request.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private record Terminal(String id, String device) {
        String token() { return "t-" + id; }
        String base() { return "/api/gates/" + id + "/booth"; }
        MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request) {
            return request.header("X-Gate-Token", token()).header("X-Gate-Device", device);
        }
    }

    private Terminal terminal(long booth) {
        String id = "g" + Secrets.randomAlnum(10);
        gates.insert(id, sessionId, "단말", "메인홀", "BIDIRECTIONAL", gateAuth.hash("t-" + id), null, null,
            "BOOTH", booth);
        return new Terminal(id, "dev-" + Secrets.randomAlnum(8));
    }

    /** A booth manager signed in on a browser: invited, mailbox proven, passkey bound. */
    private record Manager(String token, MockHttpSession browser) {
        String base() { return "/api/booth/" + token; }
    }

    private Manager manager(long booth) throws Exception {
        String email = "mgr-" + Secrets.randomAlnum(8).toLowerCase() + "@example.com";
        String invited = json(post("/api/admin/booths/" + booth + "/managers").with(admin(true)),
                "{\"email\":\"" + email + "\",\"notify\":true}")
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String link = JsonPath.read(invited, "$.link");
        String token = link.substring(link.lastIndexOf('/') + 1);
        String base = "/api/booth/" + token;
        MockHttpSession browser = new MockHttpSession();
        json(post(base + "/otp").session(browser), "{\"email\":\"" + email + "\"}").andExpect(status().isOk());
        json(post(base + "/otp/verify").session(browser),
                "{\"email\":\"" + email + "\",\"code\":\"" + mailbox.lastCode().orElseThrow() + "\"}")
            .andExpect(status().isOk());
        String options = json(post(base + "/passkey/options").session(browser), "{}")
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String challenge = JsonPath.read(options, "$.options.publicKey.challenge");
        json(post(base + "/passkey/finish").session(browser), new FakePasskey(ORIGIN).register(challenge))
            .andExpect(status().isOk()).andExpect(jsonPath("$.signedIn").value(true));
        return new Manager(token, browser);
    }

    /** A holder who has come in, proven on their browser: what their board shows. */
    private record Holder(TicketService.Resolved resolved, MockHttpSession session) {}

    private Holder holderInside() {
        String email = "h-" + Secrets.randomAlnum(8).toLowerCase() + "@example.com";
        Map<String, Object> issued = tickets.issue(sessionId, email, null, null, null);
        String url = issued.get("url").toString();
        long ticketId = ((Number) issued.get("ticketId")).longValue();
        long holderId = holders.findByEmail(email).map(h -> h.id)
            .orElseGet(() -> holders.create(email, tickets.userHandleFor(email)));
        ticketRows.bind(ticketId, holderId, email);
        jdbc.update("UPDATE ticket_presence SET state = 'INSIDE', entry_count = 1 WHERE ticket_id = ?", ticketId);
        MockHttpSession session = new MockHttpSession();
        passkeys.markViewer(session, ticketRows.findById(ticketId).orElseThrow());
        return new Holder(tickets.resolve(sessionId, url.substring(url.lastIndexOf('/') + 1)), session);
    }

    private List<Object> board(Holder holder) {
        return community.ads(holder.resolved(), holder.session()).stream().map(row -> row.get("title")).toList();
    }

    private static String ad(String title) {
        return "{\"category\":\"SALE\",\"title\":\"" + title + "\",\"body\":\"오늘만\",\"place\":\"입구 옆\"}";
    }

    private static long id(ResultActions result) throws Exception {
        return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    private ResultActions review(long adId, String body) throws Exception {
        return json(post("/api/admin/sessions/" + sessionId + "/ads/" + adId + "/review").with(admin(true)), body);
    }

    // ---- The rules ----

    @Test void aBoothsNoticeWaitsUntilApproved() throws Exception {
        Terminal stand = terminal(boothId);
        Holder in = holderInside();

        long adId = id(json(stand.as(post(stand.base() + "/ads")), ad("라테 1+1"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.review").value("PENDING")));
        assertEquals(List.of(), board(in), "a waiting notice is not on holders' boards");
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
            () -> community.ad(in.resolved(), in.session(), adId), "nor opened by its id");

        mvc.perform(get("/api/admin/sessions/" + sessionId + "/ads/summary").with(admin(true)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.pending").value(1));
        mvc.perform(get("/api/admin/sessions/" + sessionId + "/ads?status=PENDING").with(admin(true)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].title").value("라테 1+1"))
            .andExpect(jsonPath("$[0].booth").value("커피 스탠드"))
            .andExpect(jsonPath("$[0].requestedBy").value(stand.id()));
        mvc.perform(get("/api/admin/sessions/" + sessionId + "/ads?status=PUBLISHED").with(admin(true)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        review(adId, "{\"decision\":\"APPROVE\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.review").value("PUBLISHED"))
            .andExpect(jsonPath("$.reviewedAt").isNotEmpty());
        assertEquals(List.of("라테 1+1"), board(in));
        mvc.perform(get("/api/admin/sessions/" + sessionId + "/ads/summary").with(admin(true)))
            .andExpect(jsonPath("$.pending").value(0)).andExpect(jsonPath("$.published").value(1));
    }

    @Test void aRejectedNoticeTellsTheBoothWhy() throws Exception {
        Terminal stand = terminal(boothId);
        Holder in = holderInside();
        long adId = id(json(stand.as(post(stand.base() + "/ads")), ad("전부 공짜")).andExpect(status().isCreated()));

        review(adId, "{\"decision\":\"REJECT\",\"reason\":\"가격을 적어 주세요.\"}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.review").value("REJECTED"));
        assertEquals(List.of(), board(in));
        mvc.perform(stand.as(get(stand.base() + "/ads")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].review").value("REJECTED"))
            .andExpect(jsonPath("$[0].rejectReason").value("가격을 적어 주세요."));

        // Fixed and sent again: waiting once more, the old reason gone.
        json(stand.as(put(stand.base() + "/ads/" + adId)), ad("라테 3,000원"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.review").value("PENDING"))
            .andExpect(jsonPath("$.rejectReason").isEmpty());
        review(adId, "{\"decision\":\"MAYBE\"}").andExpect(status().isBadRequest());
    }

    @Test void aBoothsEditTakesAnApprovedNoticeBackForReview() throws Exception {
        Terminal stand = terminal(boothId);
        Holder in = holderInside();
        long adId = id(json(stand.as(post(stand.base() + "/ads")), ad("라테 1+1")).andExpect(status().isCreated()));
        review(adId, "{\"decision\":\"APPROVE\"}").andExpect(status().isOk());
        assertEquals(List.of("라테 1+1"), board(in));

        json(stand.as(put(stand.base() + "/ads/" + adId)), ad("라테 무료"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.review").value("PENDING"));
        assertEquals(List.of(), board(in), "the changed text is not what was approved");
        review(adId, "{\"decision\":\"APPROVE\"}").andExpect(status().isOk());
        assertEquals(List.of("라테 무료"), board(in));
    }

    @Test void theOrganisersOwnNoticesGoOutAtOnce() throws Exception {
        Holder in = holderInside();
        json(post("/api/admin/sessions/" + sessionId + "/ads").with(admin(true)),
                "{\"category\":\"NOTICE\",\"title\":\"물품 보관소 안내\",\"body\":\"2층\"}")
            .andExpect(status().isCreated()).andExpect(jsonPath("$.review").value("PUBLISHED"));
        assertEquals(List.of("물품 보관소 안내"), board(in));

        // Correcting a booth's waiting notice is the organiser's own word: it goes out as corrected.
        Terminal stand = terminal(boothId);
        long adId = id(json(stand.as(post(stand.base() + "/ads")), ad("라테 1+1")).andExpect(status().isCreated()));
        json(put("/api/admin/sessions/" + sessionId + "/ads/" + adId).with(admin(true)), ad("라테 2+1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.review").value("PUBLISHED"));
        assertTrue(board(in).contains("라테 2+1"));
    }

    @Test void aBoothSeesAndChangesOnlyItsOwnNotices() throws Exception {
        Terminal mine = terminal(boothId);
        Terminal theirs = terminal(otherBoothId);
        long theirAd = id(json(theirs.as(post(theirs.base() + "/ads")), ad("엽서 세트")).andExpect(status().isCreated()));
        json(mine.as(post(mine.base() + "/ads")), ad("라테 1+1")).andExpect(status().isCreated());

        mvc.perform(mine.as(get(mine.base() + "/ads")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].title").value("라테 1+1"));
        json(mine.as(put(mine.base() + "/ads/" + theirAd)), ad("가로채기")).andExpect(status().isNotFound());
        mvc.perform(mine.as(delete(mine.base() + "/ads/" + theirAd))).andExpect(status().isNotFound());
        mvc.perform(mine.as(post(mine.base() + "/ads/" + theirAd + "/end"))).andExpect(status().isNotFound());

        Manager stranger = manager(boothId);
        json(put(stranger.base() + "/ads/" + theirAd).session(stranger.browser()), ad("가로채기"))
            .andExpect(status().isNotFound());
        mvc.perform(get(stranger.base() + "/ads").session(stranger.browser()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(theirs.as(get(theirs.base() + "/ads")))
            .andExpect(jsonPath("$[0].title").value("엽서 세트"));

        // An admission door has no booth to post for.
        String door = "g" + Secrets.randomAlnum(10);
        gates.insert(door, sessionId, "입구", "메인홀", "BIDIRECTIONAL", gateAuth.hash("t-" + door), null, null,
            "ADMISSION", null);
        Terminal entrance = new Terminal(door, "dev-" + Secrets.randomAlnum(8));
        json(entrance.as(post(entrance.base() + "/ads")), ad("입구 글")).andExpect(status().isForbidden());
    }

    @Test void aManagerRunsTheBoardLikeTheTerminal() throws Exception {
        Manager manager = manager(boothId);
        Holder in = holderInside();
        long adId = id(json(post(manager.base() + "/ads").session(manager.browser()), ad("쿠키 증정"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.review").value("PENDING"))
            .andExpect(jsonPath("$.booth").value("커피 스탠드")));
        mvc.perform(get("/api/admin/sessions/" + sessionId + "/ads?status=PENDING").with(admin(true)))
            .andExpect(jsonPath("$[0].requestedBy").value(org.hamcrest.Matchers.startsWith("mgr:")));
        review(adId, "{\"decision\":\"APPROVE\"}").andExpect(status().isOk());
        assertEquals(List.of("쿠키 증정"), board(in));

        // The terminal at the same booth sees the manager's notice, and the other way round.
        Terminal stand = terminal(boothId);
        mvc.perform(stand.as(get(stand.base() + "/ads"))).andExpect(jsonPath("$[0].id").value(adId));

        json(put(manager.base() + "/ads/" + adId).session(manager.browser()), ad("쿠키 2개"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.review").value("PENDING"));
        assertEquals(List.of(), board(in));
        mvc.perform(delete(manager.base() + "/ads/" + adId).session(manager.browser()).with(csrf()))
            .andExpect(status().isOk());
        mvc.perform(get(manager.base() + "/ads").session(manager.browser()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        // Without the signed-in browser the link alone opens nothing.
        mvc.perform(get(manager.base() + "/ads")).andExpect(status().is4xxClientError());
    }

    @Test void onlyTheTicketConsoleReviews() throws Exception {
        Terminal stand = terminal(boothId);
        long adId = id(json(stand.as(post(stand.base() + "/ads")), ad("라테 1+1")).andExpect(status().isCreated()));
        json(post("/api/admin/sessions/" + sessionId + "/ads/" + adId + "/review").with(admin(false)),
            "{\"decision\":\"APPROVE\"}").andExpect(status().isForbidden());
        json(post("/api/admin/sessions/" + sessionId + "/ads/" + adId + "/review"), "{\"decision\":\"APPROVE\"}")
            .andExpect(status().is4xxClientError());
        mvc.perform(get("/api/admin/sessions/" + sessionId + "/ads?status=PENDING").with(admin(false)))
            .andExpect(status().isForbidden());
        mvc.perform(stand.as(get(stand.base() + "/ads"))).andExpect(jsonPath("$[0].review").value("PENDING"));
    }
}
