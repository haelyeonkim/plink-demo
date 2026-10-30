package com.plink.ticket;

import com.jayway.jsonpath.JsonPath;
import com.plink.ticket.model.Coupon;
import com.plink.ticket.model.Ticket;
import com.plink.ticket.repository.BoothRepository;
import com.plink.ticket.repository.CouponRepository;
import com.plink.ticket.repository.EventSessionRepository;
import com.plink.ticket.repository.GateRepository;
import com.plink.ticket.repository.HolderRepository;
import com.plink.ticket.repository.TicketRepository;
import com.plink.ticket.service.CouponService;
import com.plink.ticket.service.GateAuthService;
import com.plink.ticket.service.Secrets;
import com.plink.ticket.service.TicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The booth console run from a booth terminal: the manager's routes under the terminal's
 * own authentication, held to the terminal's booth and refused to every other terminal.
 */
// Isolated from ./.env: the suite must not depend on whichever origin, secret or
// face service a developer happens to have configured locally.
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:boothterminaltest;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.config.import=", "plink.admin.email=", "plink.admin.password=",
    "plink.auth.base-url=http://localhost:3000", "plink.ticket.token-secret=booth-terminal-test-secret"})
@AutoConfigureMockMvc
class BoothTerminalConsoleTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired EventSessionRepository sessions;
    @Autowired BoothRepository booths;
    @Autowired GateRepository gates;
    @Autowired GateAuthService gateAuth;
    @Autowired TicketService tickets;
    @Autowired TicketRepository ticketRows;
    @Autowired HolderRepository holders;
    @Autowired CouponService coupons;
    @Autowired CouponRepository couponRows;

    private long sessionId;
    private long boothId;
    private long otherBoothId;

    @BeforeEach void event() {
        sessionId = sessions.insert("부스 단말 이벤트 " + Secrets.randomAlnum(6), "메인홀",
            Timestamp.from(Instant.now().plus(2, ChronoUnit.HOURS)), null);
        boothId = booths.insert(sessionId, "커피 스탠드", "입구 옆");
        otherBoothId = booths.insert(sessionId, "굿즈 부스", null);
    }

    /** A terminal as the console registers one; its token is {@code t-{id}}. */
    private record Terminal(String id, String device) {
        String token() { return "t-" + id; }
    }

    private Terminal terminal(String role, Long booth) {
        String id = "g" + Secrets.randomAlnum(10);
        gates.insert(id, sessionId, "단말", "메인홀", "BIDIRECTIONAL", gateAuth.hash("t-" + id), null, null,
            role, booth);
        return new Terminal(id, "dev-" + Secrets.randomAlnum(8));
    }

    private String base(Terminal t) { return "/api/gates/" + t.id() + "/booth"; }

    /** As the terminal sends it: its token, and the device it is bound to. */
    private MockHttpServletRequestBuilder as(Terminal t, MockHttpServletRequestBuilder request) {
        return request.header("X-Gate-Token", t.token()).header("X-Gate-Device", t.device());
    }

    private ResultActions json(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private Ticket boundTicket() {
        String email = "t-" + Secrets.randomAlnum(8).toLowerCase() + "@example.com";
        long id = ((Number) tickets.issue(sessionId, email, null, null).get("ticketId")).longValue();
        long holderId = holders.findByEmail(email).map(h -> h.id)
            .orElseGet(() -> holders.create(email, tickets.userHandleFor(email)));
        ticketRows.bind(id, holderId, email);
        return ticketRows.findById(id).orElseThrow();
    }

    private long createdOffer(Terminal t, String title) throws Exception {
        String body = json(as(t, post(base(t) + "/offers")), "{\"title\":\"" + title + "\"}")
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.offerId")).longValue();
    }

    @Test void aBoothTerminalRunsTheWholeConsole() throws Exception {
        Terminal stand = terminal("BOOTH", boothId);
        Ticket a = boundTicket();
        Ticket b = boundTicket();
        jdbc.update("UPDATE ticket_presence SET state = 'INSIDE', inside_since = CURRENT_TIMESTAMP WHERE ticket_id = ?", b.id);

        mvc.perform(as(stand, get(base(stand) + "/dashboard")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.booth.boothId").value(boothId))
            .andExpect(jsonPath("$.booth.name").value("커피 스탠드"))
            .andExpect(jsonPath("$.event.venue").value("메인홀"))
            .andExpect(jsonPath("$.liveTickets").value(2))
            .andExpect(jsonPath("$.inside").value(1))
            .andExpect(jsonPath("$.offers").isEmpty())
            .andExpect(jsonPath("$.recent").isEmpty());

        String created = json(as(stand, post(base(stand) + "/offers")), "{\"title\":\"아메리카노\",\"detail\":\"1잔\"}")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.title").value("아메리카노"))
            .andExpect(jsonPath("$.detail").value("1잔"))
            .andExpect(jsonPath("$.active").value(true))
            .andExpect(jsonPath("$.holders").value(0))
            .andReturn().getResponse().getContentAsString();
        long coffee = ((Number) JsonPath.read(created, "$.offerId")).longValue();

        json(as(stand, put(base(stand) + "/offers/" + coffee)), "{\"title\":\"라테\",\"detail\":\"따뜻하게\"}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("라테"));
        json(as(stand, post(base(stand) + "/offers/" + coffee + "/active")), "{\"active\":false}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        json(as(stand, post(base(stand) + "/issue")), "{\"offerId\":" + coffee + ",\"target\":\"ALL\"}")
            .andExpect(status().isConflict());
        json(as(stand, post(base(stand) + "/offers/" + coffee + "/active")), "{\"active\":true}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));

        json(as(stand, post(base(stand) + "/issue")), "{\"offerId\":" + coffee + ",\"target\":\"INSIDE\"}")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.boothId").value(boothId))
            .andExpect(jsonPath("$.given").value(1)).andExpect(jsonPath("$.tickets").value(1));
        json(as(stand, post(base(stand) + "/issue")),
                "{\"offerId\":" + coffee + ",\"target\":\"PICKED\",\"ticketIds\":[" + a.id + "],\"quantity\":2}")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.given").value(2)).andExpect(jsonPath("$.quantity").value(2));
        json(as(stand, post(base(stand) + "/issue")), "{\"target\":\"ALL\"}")
            .andExpect(status().isBadRequest());

        mvc.perform(as(stand, get(base(stand) + "/tickets")).param("offerId", String.valueOf(coffee)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(2))
            .andExpect(jsonPath("$.pageSize").value(50))
            .andExpect(jsonPath("$.items[?(@.ticketId == " + a.id + ")].held").value(2))
            .andExpect(jsonPath("$.items[0].emailMasked").value(containsString("***@example.com")));
        mvc.perform(as(stand, get(base(stand) + "/tickets")).param("inside", "true"))
            .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].ticketId").value(b.id));

        String listed = mvc.perform(as(stand, get(base(stand) + "/coupons")).param("status", "ISSUED"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(3))
            .andExpect(jsonPath("$.counts.all").value(3))
            .andExpect(jsonPath("$.counts.issued").value(3))
            .andReturn().getResponse().getContentAsString();
        long first = ((Number) JsonPath.read(listed, "$.items[0].couponId")).longValue();
        json(as(stand, post(base(stand) + "/coupons/status")), "{\"ids\":[" + first + "],\"status\":\"VOID\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.changed").value(1)).andExpect(jsonPath("$.status").value("VOID"));
        assertEquals("VOID", couponRows.findById(first).orElseThrow().status);
        // Marking one used by hand stays with the organiser, from a terminal as from a phone.
        json(as(stand, post(base(stand) + "/coupons/status")), "{\"ids\":[" + first + "],\"status\":\"REDEEMED\"}")
            .andExpect(status().isBadRequest());

        mvc.perform(as(stand, get(base(stand) + "/dashboard")))
            .andExpect(jsonPath("$.offers[0].holders").value(2))
            .andExpect(jsonPath("$.recent.length()").value(3));

        long spare = createdOffer(stand, "머핀");
        mvc.perform(as(stand, delete(base(stand) + "/offers/" + spare)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.deleted").value(true)).andExpect(jsonPath("$.offerId").value(spare));
    }

    @Test void anIssueFromATerminalTopsUpLikeTheManagersAndTheOrganisers() throws Exception {
        Terminal stand = terminal("BOOTH", boothId);
        Ticket a = boundTicket();
        Ticket b = boundTicket();
        long coffee = createdOffer(stand, "아메리카노");
        // Somebody already given one by the organiser is topped up, not given another.
        coupons.issue(sessionId, coffee, List.of(a.id));

        json(as(stand, post(base(stand) + "/issue")), "{\"offerId\":" + coffee + ",\"target\":\"ALL\"}")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.title").value("아메리카노"))
            .andExpect(jsonPath("$.given").value(1))
            .andExpect(jsonPath("$.tickets").value(1))
            .andExpect(jsonPath("$.already").value(1));
        json(as(stand, post(base(stand) + "/issue")), "{\"offerId\":" + coffee + ",\"target\":\"ALL\"}")
            .andExpect(jsonPath("$.given").value(0)).andExpect(jsonPath("$.already").value(2));
        for (Ticket ticket : List.of(a, b)) {
            List<Coupon> held = couponRows.findByTicket(ticket.id);
            assertEquals(1, held.size());
            assertEquals(boothId, held.get(0).boothId);
        }
        json(as(stand, post(base(stand) + "/issue")), "{\"offerId\":" + coffee + ",\"target\":\"PICKED\"}")
            .andExpect(status().isBadRequest());
        json(as(stand, post(base(stand) + "/issue")), "{\"offerId\":" + coffee + ",\"target\":\"ELSEWHERE\"}")
            .andExpect(status().isBadRequest());
    }

    @Test void aTerminalCannotTouchAnotherBoothsOffersOrCoupons() throws Exception {
        Terminal stand = terminal("BOOTH", boothId);
        long theirs = coupons.defineOffer(sessionId, otherBoothId, "에코백", null).id;
        Ticket holder = boundTicket();
        coupons.issue(sessionId, theirs, List.of(holder.id));
        Coupon theirCoupon = couponRows.findByTicket(holder.id).get(0);

        json(as(stand, put(base(stand) + "/offers/" + theirs)), "{\"title\":\"바뀐 이름\"}")
            .andExpect(status().isNotFound());
        json(as(stand, post(base(stand) + "/offers/" + theirs + "/active")), "{\"active\":false}")
            .andExpect(status().isNotFound());
        mvc.perform(as(stand, delete(base(stand) + "/offers/" + theirs))).andExpect(status().isNotFound());
        json(as(stand, post(base(stand) + "/issue")), "{\"offerId\":" + theirs + ",\"target\":\"ALL\"}")
            .andExpect(status().isNotFound());
        mvc.perform(as(stand, get(base(stand) + "/tickets")).param("offerId", String.valueOf(theirs)))
            .andExpect(status().isNotFound());
        json(as(stand, post(base(stand) + "/coupons/status")), "{\"ids\":[" + theirCoupon.id + "],\"status\":\"VOID\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.changed").value(0)).andExpect(jsonPath("$.skipped").value(1));
        assertEquals("ISSUED", couponRows.findById(theirCoupon.id).orElseThrow().status);
        mvc.perform(as(stand, get(base(stand) + "/coupons")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(as(stand, get(base(stand) + "/dashboard")))
            .andExpect(jsonPath("$.offers").isEmpty());
        assertEquals("에코백", jdbc.queryForObject("SELECT title FROM coupon_offer WHERE id = ?", String.class, theirs));
    }

    @Test void onlyABoothTerminalAtABoothThatStillExistsGetsTheConsole() throws Exception {
        Terminal door = terminal("ADMISSION", null);
        mvc.perform(as(door, get(base(door) + "/dashboard")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("부스 단말에서만 쓸 수 있어요."));
        json(as(door, post(base(door) + "/offers")), "{\"title\":\"몰래 쿠폰\"}")
            .andExpect(status().isForbidden());

        long doomed = booths.insert(sessionId, "철수한 부스", null);
        Terminal orphan = terminal("BOOTH", doomed);
        mvc.perform(as(orphan, get(base(orphan) + "/dashboard"))).andExpect(status().isOk());
        booths.delete(doomed);
        mvc.perform(as(orphan, get(base(orphan) + "/dashboard")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("이 단말에 연결된 부스가 없어요."));
        json(as(orphan, post(base(orphan) + "/offers")), "{\"title\":\"몰래 쿠폰\"}")
            .andExpect(status().isForbidden());

        // Without a token, or with a wrong one, nothing at all.
        mvc.perform(get("/api/gates/" + door.id() + "/booth/dashboard")).andExpect(status().isUnauthorized());
        Terminal stand = terminal("BOOTH", boothId);
        mvc.perform(get(base(stand) + "/dashboard").header("X-Gate-Token", "t-wrong"))
            .andExpect(status().isUnauthorized());
    }

    @Test void aBoundTerminalsConsoleNeedsItsOwnDevice() throws Exception {
        Terminal stand = terminal("BOOTH", boothId);
        // The first request binds the gate to this device.
        mvc.perform(as(stand, get(base(stand) + "/dashboard"))).andExpect(status().isOk());

        // A copy of the token without the device header is refused...
        mvc.perform(get(base(stand) + "/dashboard").header("X-Gate-Token", stand.token()))
            .andExpect(status().isUnauthorized());
        json(post(base(stand) + "/offers").header("X-Gate-Token", stand.token()), "{\"title\":\"몰래 쿠폰\"}")
            .andExpect(status().isUnauthorized());
        // ...and so is another device while this one is still at work.
        Terminal copy = new Terminal(stand.id(), "dev-somebody-else");
        mvc.perform(as(copy, get(base(copy) + "/dashboard"))).andExpect(status().isConflict());
        json(as(copy, post(base(copy) + "/offers")), "{\"title\":\"몰래 쿠폰\"}")
            .andExpect(status().isConflict());
        assertTrue(jdbc.queryForList("SELECT title FROM coupon_offer WHERE booth_id = ?", String.class, boothId)
            .isEmpty());
    }

    /** The organiser, as the console signs in: an account row the session check can find. */
    private org.springframework.test.web.servlet.request.RequestPostProcessor organiser() {
        jdbc.update("MERGE INTO admin_account (id, email, password_hash, slug, can_links, can_tickets, is_owner) "
            + "KEY (id) VALUES (995, 'admin995@example.com', 'unused', 'admin-995', TRUE, TRUE, FALSE)");
        com.plink.account.AdminAccount account = new com.plink.account.AdminAccount();
        account.id = 995;
        account.email = "admin995@example.com";
        account.passwordHash = "unused";
        account.canLinks = true;
        account.canTickets = true;
        com.plink.account.AdminPrincipal principal = new com.plink.account.AdminPrincipal(account);
        return authentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal, null,
            principal.authorities().stream().map(org.springframework.security.core.authority.SimpleGrantedAuthority::new).toList()));
    }

    @Test void aDeletedTerminalIsGoneAndItsTabletIsRefusedButItsHistoryStays() throws Exception {
        Terminal t = terminal("BOOTH", boothId);
        long offer = createdOffer(t, "스티커");
        jdbc.update("INSERT INTO gate_offer (gate_id, offer_id, auto_on_entry) VALUES (?, ?, FALSE)", t.id(), offer);
        Ticket ticket = boundTicket();
        coupons.issue(sessionId, offer, java.util.List.of(ticket.id));
        jdbc.update("UPDATE coupon SET status = 'REDEEMED', redeemed_at = CURRENT_TIMESTAMP, redeemed_gate = ? WHERE ticket_id = ?",
            t.id(), ticket.id);

        mvc.perform(delete("/api/admin/gates/" + t.id()).with(organiser()).with(csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.deleted").value(true));

        assertTrue(gates.findById(t.id()).isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM gate_offer WHERE gate_id = ?", Integer.class, t.id()));
        // What it handed over is still on record.
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM coupon WHERE redeemed_gate = ?", Integer.class, t.id()));
        mvc.perform(as(t, get(base(t) + "/dashboard"))).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/admin/gates/" + t.id()).with(organiser()).with(csrf())).andExpect(status().isNotFound());
    }

    @Test void onlyAnOrganiserDeletesATerminal() throws Exception {
        Terminal t = terminal("BOOTH", boothId);
        mvc.perform(delete("/api/admin/gates/" + t.id()).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(as(t, delete("/api/admin/gates/" + t.id())).with(csrf())).andExpect(status().isUnauthorized());
        assertTrue(gates.findById(t.id()).isPresent());
    }

    @Test void anOrganiserRenamesATerminalAndItsPlaceAndDirectionStay() throws Exception {
        Terminal t = terminal("BOOTH", boothId);
        json(put("/api/admin/gates/" + t.id()).with(organiser()).with(csrf()), "{\"label\":\"계산대 2\"}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.label").value("계산대 2"));
        com.plink.ticket.model.Gate renamed = gates.findById(t.id()).orElseThrow();
        assertEquals("계산대 2", renamed.label);
        assertEquals("메인홀", renamed.zone);
        assertEquals("BIDIRECTIONAL", renamed.direction);
        json(put("/api/admin/gates/" + t.id()).with(organiser()).with(csrf()), "{\"label\":\"  \"}")
            .andExpect(status().isBadRequest());
        json(put("/api/admin/gates/" + t.id()).with(csrf()), "{\"label\":\"x\"}").andExpect(status().isUnauthorized());
    }
}
