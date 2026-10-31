package com.plink.ticket;

import com.jayway.jsonpath.JsonPath;
import com.plink.account.AdminAccount;
import com.plink.account.AdminPrincipal;
import com.plink.ticket.model.Coupon;
import com.plink.ticket.model.Ticket;
import com.plink.ticket.repository.BoothRepository;
import com.plink.ticket.repository.CouponRepository;
import com.plink.ticket.repository.EventSessionRepository;
import com.plink.ticket.repository.HolderRepository;
import com.plink.ticket.repository.TicketRepository;
import com.plink.ticket.service.CouponService;
import com.plink.ticket.service.PresentationService;
import com.plink.ticket.service.Secrets;
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

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Booth managers: invited by email, signed in with a real passkey ceremony against a
 * software authenticator, and held to their own booth whatever they ask for.
 */
// Isolated from ./.env: the suite must not depend on whichever origin, secret or
// face service a developer happens to have configured locally.
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:boothmanagertest;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.config.import=", "plink.admin.email=", "plink.admin.password=",
    "plink.auth.base-url=http://localhost:3000", "plink.ticket.token-secret=booth-manager-test-secret"})
@AutoConfigureMockMvc
@Import(RecordingEmail.class)
class BoothManagerTest {
    private static final String ORIGIN = "http://localhost:3000";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired RecordingEmail.Mailbox mailbox;
    @Autowired EventSessionRepository sessions;
    @Autowired BoothRepository booths;
    @Autowired TicketService tickets;
    @Autowired TicketRepository ticketRows;
    @Autowired HolderRepository holders;
    @Autowired CouponService coupons;
    @Autowired CouponRepository couponRows;
    @Autowired PresentationService presentations;

    private long sessionId;
    private long boothId;
    private long otherBoothId;

    @BeforeEach void event() {
        for (long id : new long[] { 996, 997 }) {
            jdbc.update("MERGE INTO admin_account (id, email, password_hash, slug, can_links, can_tickets, is_owner) "
                + "KEY (id) VALUES (?, ?, 'unused', ?, TRUE, ?, FALSE)",
                id, "admin" + id + "@example.com", "admin-" + id, id == 996);
        }
        mailbox.clear();
        sessionId = sessions.insert("부스 관리자 이벤트 " + Secrets.randomAlnum(6), "메인홀",
            Timestamp.from(Instant.now().plus(2, ChronoUnit.HOURS)), null);
        boothId = booths.insert(sessionId, "커피 스탠드", "입구 옆");
        otherBoothId = booths.insert(sessionId, "굿즈 부스", null);
    }

    /** An organiser with the ticket console, or (996 aside) one without it. */
    private static RequestPostProcessor admin(boolean tickets) {
        AdminAccount account = new AdminAccount();
        account.id = tickets ? 996 : 997;
        account.email = "admin" + account.id + "@example.com";
        account.passwordHash = "unused";
        account.canLinks = true;
        account.canTickets = tickets;
        AdminPrincipal principal = new AdminPrincipal(account);
        return authentication(new UsernamePasswordAuthenticationToken(principal, null,
            principal.authorities().stream().map(SimpleGrantedAuthority::new).toList()));
    }

    private static String email() {
        return "mgr-" + Secrets.randomAlnum(8).toLowerCase() + "@example.com";
    }

    private ResultActions json(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(request.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private record Invite(long managerId, String token) {}

    private Invite invite(long booth, String email) throws Exception {
        String body = json(post("/api/admin/booths/" + booth + "/managers").with(admin(true)),
                "{\"email\":\"" + email + "\",\"notify\":true}")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("INVITED"))
            .andReturn().getResponse().getContentAsString();
        String link = JsonPath.read(body, "$.link");
        assertTrue(link.startsWith(ORIGIN + "/tickets/booth/"));
        return new Invite(((Number) JsonPath.read(body, "$.managerId")).longValue(),
            link.substring(link.lastIndexOf('/') + 1));
    }

    private String base(String token) { return "/api/booth/" + token; }

    /** The mailbox proof: a code to the invited address, typed back. */
    private void proveMailbox(String token, String email, MockHttpSession browser) throws Exception {
        json(post(base(token) + "/otp").session(browser), "{\"email\":\"" + email + "\"}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.sent").value(true));
        String code = mailbox.lastCode().orElseThrow();
        json(post(base(token) + "/otp/verify").session(browser),
                "{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.verified").value(true));
    }

    /** Asks for options and returns the challenge, checking which ceremony it is. */
    private String challenge(String token, MockHttpSession browser, String mode) throws Exception {
        String body = json(post(base(token) + "/passkey/options").session(browser), "{}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mode").value(mode))
            .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.options.publicKey.challenge");
    }

    private ResultActions finish(String token, MockHttpSession browser, String credential) throws Exception {
        return json(post(base(token) + "/passkey/finish").session(browser), credential);
    }

    /** Invite, prove the mailbox, register a passkey: a signed-in manager's browser. */
    private MockHttpSession signedIn(String token, String email, FakePasskey key) throws Exception {
        MockHttpSession browser = new MockHttpSession();
        proveMailbox(token, email, browser);
        finish(token, browser, key.register(challenge(token, browser, "register")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.signedIn").value(true));
        return browser;
    }

    private Ticket boundTicket(String email) {
        long id = ((Number) tickets.issue(sessionId, email, null, null).get("ticketId")).longValue();
        long holderId = holders.findByEmail(email).map(h -> h.id)
            .orElseGet(() -> holders.create(email, tickets.userHandleFor(email)));
        ticketRows.bind(id, holderId, email);
        return ticketRows.findById(id).orElseThrow();
    }

    private long offer(long booth, String title) {
        return coupons.defineOffer(sessionId, booth, title, null).id;
    }

    private String ticketCode(Ticket ticket) {
        return TicketCodes.code(presentations.issue(ticket, "IN", true), 1);
    }

    private String couponCode(Ticket ticket, long couponId) {
        return TicketCodes.code(coupons.openCode(ticket, couponId), 1);
    }

    // ---- Claiming --------------------------------------------------------------------------------

    @Test void anInvitationNeedsTheMailboxThenAPasskeyAndThenOpensTheBooth() throws Exception {
        String email = email();
        Invite invite = invite(boothId, email);
        assertTrue(mailbox.lastBody().contains("/tickets/booth/" + invite.token()), "the link was mailed");

        MockHttpSession browser = new MockHttpSession();
        mvc.perform(get(base(invite.token())).session(browser))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.boothName").value("커피 스탠드"))
            .andExpect(jsonPath("$.status").value("INVITED"))
            .andExpect(jsonPath("$.signedIn").value(false))
            .andExpect(jsonPath("$.emailMasked").value(containsString("***@example.com")));
        mvc.perform(get(base(invite.token()) + "/dashboard").session(browser))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("패스키로 다시 로그인해 주세요."));

        // No code, no ceremony; and a code goes only to the invited address.
        json(post(base(invite.token()) + "/passkey/options").session(browser), "{}")
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("이메일 인증을 먼저 완료해 주세요."));
        json(post(base(invite.token()) + "/otp").session(browser), "{\"email\":\"someone@example.com\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("초대받은 이메일이 아니에요."));
        // CSRF holds here as everywhere a cookie carries the sign-in.
        mvc.perform(post(base(invite.token()) + "/otp").session(browser)
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\"}"))
            .andExpect(status().isForbidden());

        proveMailbox(invite.token(), email, browser);
        FakePasskey phone = new FakePasskey(ORIGIN);
        finish(invite.token(), browser, phone.register(challenge(invite.token(), browser, "register")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.signedIn").value(true));

        mvc.perform(get(base(invite.token())).session(browser))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.signedIn").value(true));
        mvc.perform(get(base(invite.token()) + "/dashboard").session(browser))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.booth.name").value("커피 스탠드"))
            .andExpect(jsonPath("$.offers").isArray());

        // Another browser signs in with the passkey alone; a different passkey does not.
        MockHttpSession later = new MockHttpSession();
        finish(invite.token(), later, phone.authenticate(challenge(invite.token(), later, "authenticate")))
            .andExpect(status().isOk());
        mvc.perform(get(base(invite.token()) + "/dashboard").session(later)).andExpect(status().isOk());

        MockHttpSession stranger = new MockHttpSession();
        String asked = challenge(invite.token(), stranger, "authenticate");
        finish(invite.token(), stranger, new FakePasskey(ORIGIN).authenticate(asked))
            .andExpect(status().isForbidden());
        mvc.perform(get(base(invite.token()) + "/dashboard").session(stranger)).andExpect(status().isUnauthorized());

        // Once taken up, the invitation sends no more codes.
        json(post(base(invite.token()) + "/otp").session(stranger), "{\"email\":\"" + email + "\"}")
            .andExpect(status().isConflict());
    }

    @Test void aSecondDeviceCannotClaimAnInvitationAlreadyTakenUp() throws Exception {
        String email = email();
        Invite invite = invite(boothId, email);
        // Both phones prove the mailbox and get registration options before either finishes.
        MockHttpSession first = new MockHttpSession();
        MockHttpSession second = new MockHttpSession();
        proveMailbox(invite.token(), email, first);
        jdbc.update("DELETE FROM email_otp WHERE email = ?", email); // past the resend interval
        proveMailbox(invite.token(), email, second);
        FakePasskey mine = new FakePasskey(ORIGIN);
        FakePasskey theirs = new FakePasskey(ORIGIN);
        String firstChallenge = challenge(invite.token(), first, "register");
        String secondChallenge = challenge(invite.token(), second, "register");

        finish(invite.token(), first, mine.register(firstChallenge)).andExpect(status().isOk());
        finish(invite.token(), second, theirs.register(secondChallenge))
            .andExpect(status().isConflict());
        mvc.perform(get(base(invite.token()) + "/dashboard").session(second)).andExpect(status().isUnauthorized());
        // And from now on that link asks for the first phone's passkey, not for a new one.
        challenge(invite.token(), new MockHttpSession(), "authenticate");
    }

    @Test void aRevokedManagerIsRefusedAtOnceEvenWhileSignedIn() throws Exception {
        String email = email();
        Invite invite = invite(boothId, email);
        MockHttpSession browser = signedIn(invite.token(), email, new FakePasskey(ORIGIN));
        mvc.perform(get(base(invite.token()) + "/dashboard").session(browser)).andExpect(status().isOk());

        mvc.perform(delete("/api/admin/booth-managers/" + invite.managerId()).with(admin(true)).with(csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REVOKED"));

        mvc.perform(get(base(invite.token()) + "/dashboard").session(browser)).andExpect(status().isGone());
        mvc.perform(get(base(invite.token())).session(browser)).andExpect(status().isGone());
        json(post(base(invite.token()) + "/offers").session(browser), "{\"title\":\"몰래 쿠폰\"}")
            .andExpect(status().isGone());
        json(post(base(invite.token()) + "/passkey/options").session(browser), "{}").andExpect(status().isGone());
        assertTrue(couponOffers(boothId).isEmpty());
    }

    private List<String> couponOffers(long booth) {
        return jdbc.queryForList("SELECT title FROM coupon_offer WHERE booth_id = ?", String.class, booth);
    }

    // ---- Held to the booth ------------------------------------------------------------------------

    @Test void aManagerCannotTouchAnotherBoothsOffersOrCoupons() throws Exception {
        String email = email();
        Invite invite = invite(boothId, email);
        MockHttpSession browser = signedIn(invite.token(), email, new FakePasskey(ORIGIN));
        String token = invite.token();

        long theirs = offer(otherBoothId, "에코백");
        Ticket holder = boundTicket(email());
        coupons.issue(sessionId, theirs, List.of(holder.id));
        Coupon theirCoupon = couponRows.findByTicket(holder.id).get(0);

        json(put(base(token) + "/offers/" + theirs).session(browser), "{\"title\":\"바뀐 이름\"}")
            .andExpect(status().isNotFound());
        json(post(base(token) + "/offers/" + theirs + "/active").session(browser), "{\"active\":false}")
            .andExpect(status().isNotFound());
        mvc.perform(delete(base(token) + "/offers/" + theirs).session(browser).with(csrf()))
            .andExpect(status().isNotFound());
        json(post(base(token) + "/issue").session(browser), "{\"offerId\":" + theirs + ",\"target\":\"ALL\"}")
            .andExpect(status().isNotFound());
        mvc.perform(get(base(token) + "/tickets").param("offerId", String.valueOf(theirs)).session(browser))
            .andExpect(status().isNotFound());
        json(post(base(token) + "/scan").session(browser),
                "{\"code\":\"" + ticketCode(holder) + "\",\"action\":\"GIVE\",\"offerId\":" + theirs + "}")
            .andExpect(status().isNotFound());

        json(post(base(token) + "/coupons/status").session(browser),
                "{\"ids\":[" + theirCoupon.id + "],\"status\":\"VOID\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.changed").value(0)).andExpect(jsonPath("$.skipped").value(1));
        assertEquals("ISSUED", couponRows.findById(theirCoupon.id).orElseThrow().status);

        // Their coupon's own code is refused at this booth, and its list does not show it.
        json(post(base(token) + "/scan").session(browser),
                "{\"code\":\"" + couponCode(holder, theirCoupon.id) + "\",\"action\":\"REDEEM\"}")
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value(containsString("굿즈 부스")));
        assertEquals("ISSUED", couponRows.findById(theirCoupon.id).orElseThrow().status);
        mvc.perform(get(base(token) + "/coupons").session(browser))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        assertEquals(List.of("에코백"), couponOffers(otherBoothId));
    }

    @Test void issuingReachesEveryoneTheInsideOrThePicked() throws Exception {
        String email = email();
        Invite invite = invite(boothId, email);
        MockHttpSession browser = signedIn(invite.token(), email, new FakePasskey(ORIGIN));
        String token = invite.token();

        Ticket a = boundTicket(email());
        Ticket b = boundTicket(email());
        Ticket c = boundTicket(email());
        Ticket off = boundTicket(email());
        tickets.setRevoked(off.id, true);
        jdbc.update("UPDATE ticket_presence SET state = 'INSIDE', inside_since = CURRENT_TIMESTAMP WHERE ticket_id = ?", b.id);

        String created = json(post(base(token) + "/offers").session(browser), "{\"title\":\"아메리카노\",\"detail\":\"1잔\"}")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.title").value("아메리카노"))
            .andExpect(jsonPath("$.active").value(true))
            .andReturn().getResponse().getContentAsString();
        long coffee = ((Number) JsonPath.read(created, "$.offerId")).longValue();
        long latte = ((Number) JsonPath.read(json(post(base(token) + "/offers").session(browser),
            "{\"title\":\"라테\"}").andReturn().getResponse().getContentAsString(), "$.offerId")).longValue();
        long muffin = ((Number) JsonPath.read(json(post(base(token) + "/offers").session(browser),
            "{\"title\":\"머핀\"}").andReturn().getResponse().getContentAsString(), "$.offerId")).longValue();

        mvc.perform(get(base(token) + "/dashboard").session(browser))
            .andExpect(jsonPath("$.liveTickets").value(3))
            .andExpect(jsonPath("$.inside").value(1));
        mvc.perform(get(base(token) + "/tickets").param("inside", "true").session(browser))
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].ticketId").value(b.id))
            .andExpect(jsonPath("$.items[0].inside").value(true))
            .andExpect(jsonPath("$.items[0].emailMasked").value(containsString("***@example.com")));

        json(post(base(token) + "/issue").session(browser), "{\"offerId\":" + coffee + ",\"target\":\"ALL\"}")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.given").value(3)).andExpect(jsonPath("$.tickets").value(3))
            .andExpect(jsonPath("$.already").value(0)).andExpect(jsonPath("$.title").value("아메리카노"));
        assertTrue(couponRows.findByTicket(off.id).isEmpty(), "a revoked ticket gets nothing");

        json(post(base(token) + "/issue").session(browser), "{\"offerId\":" + latte + ",\"target\":\"INSIDE\"}")
            .andExpect(jsonPath("$.given").value(1)).andExpect(jsonPath("$.tickets").value(1));
        assertEquals(1, couponRows.findByTicket(b.id).stream().filter(x -> x.title.equals("라테")).count());
        assertEquals(0, couponRows.findByTicket(a.id).stream().filter(x -> x.title.equals("라테")).count());

        json(post(base(token) + "/issue").session(browser),
                "{\"offerId\":" + muffin + ",\"target\":\"PICKED\",\"ticketIds\":[" + a.id + "," + c.id + "],\"quantity\":2}")
            .andExpect(jsonPath("$.given").value(4)).andExpect(jsonPath("$.tickets").value(2))
            .andExpect(jsonPath("$.quantity").value(2));
        mvc.perform(get(base(token) + "/tickets").param("offerId", String.valueOf(muffin)).session(browser))
            .andExpect(jsonPath("$.items[?(@.ticketId == " + a.id + ")].held").value(2));
        // Picked tickets must be usable tickets of this event.
        json(post(base(token) + "/issue").session(browser),
                "{\"offerId\":" + muffin + ",\"target\":\"PICKED\",\"ticketIds\":[" + off.id + "]}")
            .andExpect(status().isBadRequest());
        long elsewhere = sessions.insert("다른 이벤트", "별관", Timestamp.from(Instant.now().plus(1, ChronoUnit.DAYS)), null);
        long stranger = ((Number) tickets.issue(elsewhere, email(), null, null).get("ticketId")).longValue();
        json(post(base(token) + "/issue").session(browser),
                "{\"offerId\":" + muffin + ",\"target\":\"PICKED\",\"ticketIds\":[" + stranger + "]}")
            .andExpect(status().isBadRequest());
        assertTrue(couponRows.findByTicket(stranger).isEmpty());

        mvc.perform(get(base(token) + "/dashboard").session(browser))
            .andExpect(jsonPath("$.offers[?(@.offerId == " + muffin + ")].holders").value(2))
            .andExpect(jsonPath("$.offers[?(@.offerId == " + muffin + ")].issued").value(4));
    }

    @Test void scanningGivesAndRedeemsThroughTheBoothRules() throws Exception {
        String email = email();
        Invite invite = invite(boothId, email);
        MockHttpSession browser = signedIn(invite.token(), email, new FakePasskey(ORIGIN));
        String token = invite.token();
        long coffee = offer(boothId, "아메리카노");
        long cookie = offer(boothId, "쿠키");
        Ticket holder = boundTicket(email());

        String code = ticketCode(holder);
        json(post(base(token) + "/scan").session(browser),
                "{\"code\":\"" + code + "\",\"action\":\"GIVE\",\"offerId\":" + coffee + "}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value("GRANTED"))
            .andExpect(jsonPath("$.count").value(1))
            .andExpect(jsonPath("$.ticketRef").value(holder.ticketRef))
            .andExpect(jsonPath("$.message").value("아메리카노 지급했어요."));
        // The code was spent at the hand-over, as at a terminal.
        json(post(base(token) + "/scan").session(browser),
                "{\"code\":\"" + code + "\",\"action\":\"GIVE\",\"offerId\":" + coffee + "}")
            .andExpect(status().isForbidden());

        // Without an offer chosen, the read asks, and the answer gives.
        String asked = json(post(base(token) + "/scan").session(browser),
                "{\"code\":\"" + ticketCode(holder) + "\",\"action\":\"GIVE\"}")
            .andExpect(jsonPath("$.outcome").value("CHOOSE"))
            .andExpect(jsonPath("$.choices.length()").value(2))
            .andReturn().getResponse().getContentAsString();
        json(post(base(token) + "/scan/confirm").session(browser),
                "{\"pick\":\"" + JsonPath.read(asked, "$.pick") + "\",\"items\":[{\"offerId\":" + cookie + ",\"count\":2}]}")
            .andExpect(jsonPath("$.outcome").value("GRANTED")).andExpect(jsonPath("$.count").value(2));

        // A stopped offer is not given.
        coupons.setOfferActive(cookie, false);
        json(post(base(token) + "/scan").session(browser),
                "{\"code\":\"" + ticketCode(holder) + "\",\"action\":\"GIVE\",\"offerId\":" + cookie + "}")
            .andExpect(status().isConflict());

        Coupon given = couponRows.findByTicket(holder.id).stream()
            .filter(c -> c.title.equals("아메리카노")).findFirst().orElseThrow();
        json(post(base(token) + "/scan").session(browser),
                "{\"code\":\"" + couponCode(holder, given.id) + "\",\"action\":\"REDEEM\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value("REDEEMED"))
            .andExpect(jsonPath("$.booth").value("커피 스탠드"))
            .andExpect(jsonPath("$.message").value("아메리카노 사용 처리했어요."));
        Coupon spent = couponRows.findById(given.id).orElseThrow();
        assertEquals("REDEEMED", spent.status);
        assertEquals("mgr:" + invite.managerId(), spent.redeemedGate);
        // The ticket's entry code is not a coupon.
        json(post(base(token) + "/scan").session(browser),
                "{\"code\":\"" + ticketCode(holder) + "\",\"action\":\"REDEEM\"}")
            .andExpect(status().isForbidden());

        mvc.perform(get(base(token) + "/coupons").param("status", "REDEEMED").session(browser))
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].redeemedBy").value("부스 관리자"))
            .andExpect(jsonPath("$.counts.all").value(3))
            .andExpect(jsonPath("$.counts.issued").value(2))
            .andExpect(jsonPath("$.counts.redeemed").value(1));
        mvc.perform(get("/api/admin/sessions/" + sessionId + "/coupons").param("status", "REDEEMED").with(admin(true)))
            .andExpect(jsonPath("$.items[0].redeemedAtGate").value("부스 관리자"));

        // Void and restore, on the booth's own coupons.
        json(post(base(token) + "/coupons/status").session(browser), "{\"ids\":[" + given.id + "],\"status\":\"VOID\"}")
            .andExpect(jsonPath("$.changed").value(1));
        json(post(base(token) + "/coupons/status").session(browser), "{\"ids\":[" + given.id + "],\"status\":\"ISSUED\"}")
            .andExpect(jsonPath("$.changed").value(1));
        json(post(base(token) + "/coupons/status").session(browser), "{\"ids\":[" + given.id + "],\"status\":\"REDEEMED\"}")
            .andExpect(status().isBadRequest());
    }

    // ---- The organiser's side ---------------------------------------------------------------------

    @Test void theOrganiserInvitesReissuesAndRevokes() throws Exception {
        String email = email();
        Invite invite = invite(boothId, email);
        json(post("/api/admin/booths/" + boothId + "/managers").with(admin(true)), "{\"email\":\"" + email.toUpperCase() + "\"}")
            .andExpect(status().isConflict());
        mvc.perform(get("/api/admin/sessions/" + sessionId + "/booths").with(admin(true)))
            .andExpect(jsonPath("$[?(@.boothId == " + boothId + ")].managers").value(1))
            .andExpect(jsonPath("$[?(@.boothId == " + otherBoothId + ")].managers").value(0));

        FakePasskey phone = new FakePasskey(ORIGIN);
        MockHttpSession browser = signedIn(invite.token(), email, phone);
        mvc.perform(get("/api/admin/booths/" + boothId + "/managers").with(admin(true)))
            .andExpect(jsonPath("$[0].email").value(email))
            .andExpect(jsonPath("$[0].status").value("ACTIVE"))
            .andExpect(jsonPath("$[0].claimedAt").isNotEmpty());

        // A reissue kills the old link and the sign-in that rode on it.
        mailbox.clear();
        String reissued = json(post("/api/admin/booth-managers/" + invite.managerId() + "/reissue").with(admin(true)),
                "{\"notify\":true}")
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String link = JsonPath.read(reissued, "$.link");
        String fresh = link.substring(link.lastIndexOf('/') + 1);
        assertNotEquals(invite.token(), fresh);
        assertTrue(mailbox.lastBody().contains(link));
        mvc.perform(get(base(invite.token())).session(browser)).andExpect(status().isNotFound());
        mvc.perform(get(base(fresh) + "/dashboard").session(browser)).andExpect(status().isUnauthorized());
        mvc.perform(get(base(fresh))).andExpect(jsonPath("$.status").value("INVITED"));
        // Taken up again: the mailbox, then the passkey this person already has.
        MockHttpSession again = new MockHttpSession();
        jdbc.update("DELETE FROM email_otp WHERE email = ?", email); // past the resend interval
        proveMailbox(fresh, email, again);
        finish(fresh, again, phone.authenticate(challenge(fresh, again, "authenticate"))).andExpect(status().isOk());
        mvc.perform(get(base(fresh) + "/dashboard").session(again)).andExpect(status().isOk());

        mvc.perform(delete("/api/admin/booth-managers/" + invite.managerId()).with(admin(true)).with(csrf()))
            .andExpect(status().isOk());
        mvc.perform(get("/api/admin/booths/" + boothId + "/managers").with(admin(true)))
            .andExpect(jsonPath("$[0].status").value("REVOKED"));
        mvc.perform(get("/api/admin/sessions/" + sessionId + "/booths").with(admin(true)))
            .andExpect(jsonPath("$[?(@.boothId == " + boothId + ")].managers").value(0));
        json(post("/api/admin/booth-managers/" + invite.managerId() + "/reissue").with(admin(true)), "{}")
            .andExpect(status().isConflict());
        // Invited again, the same row opens afresh.
        Invite back = invite(boothId, email);
        assertEquals(invite.managerId(), back.managerId());
        mvc.perform(get(base(back.token()))).andExpect(jsonPath("$.status").value("INVITED"));
        mvc.perform(get(base(fresh))).andExpect(status().isNotFound());
    }

    // ---- The terminal a manager answers for -----------------------------------------------------

    /** A booth terminal made from the console, as 단말 추가 does. */
    private String terminal(long booth, String label) throws Exception {
        String body = json(post("/api/admin/sessions/" + sessionId + "/gates").with(admin(true)),
                "{\"label\":\"" + label + "\",\"zone\":\"부스\",\"role\":\"BOOTH\",\"boothId\":\"" + booth + "\"}")
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.gateId");
    }

    private Invite inviteAt(long booth, String email, String terminal) throws Exception {
        String body = json(post("/api/admin/booths/" + booth + "/managers").with(admin(true)),
                "{\"email\":\"" + email + "\",\"gateId\":\"" + terminal + "\"}")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.gateId").value(terminal))
            .andReturn().getResponse().getContentAsString();
        String link = JsonPath.read(body, "$.link");
        return new Invite(((Number) JsonPath.read(body, "$.managerId")).longValue(),
            link.substring(link.lastIndexOf('/') + 1));
    }

    @Test void anInvitationNamesOneOfTheBoothsTerminalsOrMakesOne() throws Exception {
        String counter = terminal(boothId, "커피 카운터");
        String theirs = terminal(otherBoothId, "굿즈 카운터");
        String door = JsonPath.read(json(post("/api/admin/sessions/" + sessionId + "/gates").with(admin(true)),
                "{\"label\":\"정문\",\"zone\":\"로비\",\"direction\":\"IN\"}")
            .andReturn().getResponse().getContentAsString(), "$.gateId");

        String first = email();
        Invite a = inviteAt(boothId, first, counter);

        String second = email();
        String made = json(post("/api/admin/booths/" + boothId + "/managers").with(admin(true)),
                "{\"email\":\"" + second + "\",\"newTerminal\":{\"label\":\"커피 스탠드 2\"}}")
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String fresh = JsonPath.read(made, "$.gateId");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM gate WHERE id = ? AND role = 'BOOTH' "
            + "AND booth_id = ? AND label = '커피 스탠드 2' AND zone = '입구 옆'", Integer.class, fresh, boothId));

        // Another booth's terminal, a door, or no terminal at all is refused, and nobody is invited.
        String third = email();
        for (String wrong : new String[] { theirs, door, "g-missing" }) {
            json(post("/api/admin/booths/" + boothId + "/managers").with(admin(true)),
                    "{\"email\":\"" + third + "\",\"gateId\":\"" + wrong + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("이 부스의 단말을 골라 주세요."));
        }
        json(post("/api/admin/booths/" + boothId + "/managers").with(admin(true)),
                "{\"email\":\"" + third + "\",\"newTerminal\":{\"label\":\"  \"}}")
            .andExpect(status().isBadRequest());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM booth_manager WHERE email = ?", Integer.class, third));
        // A duplicate address leaves no new terminal behind.
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM gate WHERE session_id = ?", Integer.class, sessionId);
        json(post("/api/admin/booths/" + boothId + "/managers").with(admin(true)),
                "{\"email\":\"" + first + "\",\"newTerminal\":{\"label\":\"남는 단말\"}}")
            .andExpect(status().isConflict());
        assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM gate WHERE session_id = ?", Integer.class, sessionId));

        mvc.perform(get("/api/admin/booths/" + boothId + "/managers").with(admin(true)))
            .andExpect(jsonPath("$[0].gateId").value(counter))
            .andExpect(jsonPath("$[0].gateLabel").value("커피 카운터"))
            .andExpect(jsonPath("$[1].gateId").value(fresh))
            .andExpect(jsonPath("$[1].gateLabel").value("커피 스탠드 2"));

        // Moved to the new terminal, then refused another booth's, then left with none.
        json(put("/api/admin/booth-managers/" + a.managerId()).with(admin(true)), "{\"gateId\":\"" + fresh + "\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.gateId").value(fresh))
            .andExpect(jsonPath("$.gateLabel").value("커피 스탠드 2"));
        json(put("/api/admin/booth-managers/" + a.managerId()).with(admin(true)), "{\"gateId\":\"" + theirs + "\"}")
            .andExpect(status().isBadRequest());

        // The terminal cards say who answers for them; a door has no such list.
        String listPath = "/api/admin/sessions/" + sessionId + "/gates";
        mvc.perform(get(listPath).with(admin(true)))
            .andExpect(jsonPath("$[?(@.gateId == '" + fresh + "')].managers.length()").value(2))
            .andExpect(jsonPath("$[?(@.gateId == '" + counter + "')].managers.length()").value(0))
            .andExpect(jsonPath("$[?(@.gateId == '" + fresh + "')].managers[0].email").value(first))
            .andExpect(jsonPath("$[?(@.gateId == '" + fresh + "')].managers[0].status").value("INVITED"))
            .andExpect(jsonPath("$[?(@.gateId == '" + door + "')].managers").isEmpty());

        json(put("/api/admin/booth-managers/" + a.managerId()).with(admin(true)), "{\"gateId\":null}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.gateId").isEmpty());
        // A revoked manager drops off the card.
        long secondId = ((Number) JsonPath.read(made, "$.managerId")).longValue();
        mvc.perform(delete("/api/admin/booth-managers/" + secondId).with(admin(true)).with(csrf()))
            .andExpect(status().isOk());
        mvc.perform(get(listPath).with(admin(true)))
            .andExpect(jsonPath("$[?(@.gateId == '" + fresh + "')].managers.length()").value(0));
        json(put("/api/admin/booth-managers/" + secondId).with(admin(true)), "{\"gateId\":\"" + counter + "\"}")
            .andExpect(status().isConflict());
        json(put("/api/admin/booth-managers/" + a.managerId()).with(admin(false)), "{\"gateId\":null}")
            .andExpect(status().isForbidden());
    }

    @Test void aManagerWithATerminalRecordsAsThatTerminal() throws Exception {
        String counter = terminal(boothId, "커피 카운터");
        String email = email();
        Invite invite = inviteAt(boothId, email, counter);
        MockHttpSession browser = signedIn(invite.token(), email, new FakePasskey(ORIGIN));
        String token = invite.token();
        long coffee = offer(boothId, "아메리카노");
        Ticket holder = boundTicket(email());

        // The phone still gives what the booth has switched on, not the terminal's own list
        // (which is empty here), and the read and its answer meet at the same counter.
        String asked = json(post(base(token) + "/scan").session(browser),
                "{\"code\":\"" + ticketCode(holder) + "\",\"action\":\"GIVE\"}")
            .andExpect(jsonPath("$.outcome").value("CHOOSE"))
            .andExpect(jsonPath("$.choices.length()").value(1))
            .andReturn().getResponse().getContentAsString();
        json(post(base(token) + "/scan/confirm").session(browser),
                "{\"pick\":\"" + JsonPath.read(asked, "$.pick") + "\",\"items\":[{\"offerId\":" + coffee + ",\"count\":2}]}")
            .andExpect(jsonPath("$.outcome").value("GRANTED"));

        List<Coupon> held = couponRows.findByTicket(holder.id);
        json(post(base(token) + "/scan").session(browser),
                "{\"code\":\"" + couponCode(holder, held.get(0).id) + "\",\"action\":\"REDEEM\"}")
            .andExpect(jsonPath("$.outcome").value("REDEEMED"));
        assertEquals(counter, couponRows.findById(held.get(0).id).orElseThrow().redeemedGate);
        mvc.perform(get("/api/admin/sessions/" + sessionId + "/coupons").param("status", "REDEEMED").with(admin(true)))
            .andExpect(jsonPath("$.items[0].redeemedAtGate").value("커피 카운터"));
        mvc.perform(get(base(token) + "/coupons").param("status", "REDEEMED").session(browser))
            .andExpect(jsonPath("$.items[0].redeemedBy").value("커피 카운터"));
        mvc.perform(get(base(token) + "/dashboard").session(browser))
            .andExpect(jsonPath("$.terminal.gateId").value(counter))
            .andExpect(jsonPath("$.terminal.label").value("커피 카운터"))
            .andExpect(jsonPath("$.terminal.connected").value(false));

        // Taken off the terminal, the manager records as themselves again.
        json(put("/api/admin/booth-managers/" + invite.managerId()).with(admin(true)), "{\"gateId\":null}")
            .andExpect(status().isOk());
        json(post(base(token) + "/scan").session(browser),
                "{\"code\":\"" + couponCode(holder, held.get(1).id) + "\",\"action\":\"REDEEM\"}")
            .andExpect(jsonPath("$.outcome").value("REDEEMED"));
        assertEquals("mgr:" + invite.managerId(), couponRows.findById(held.get(1).id).orElseThrow().redeemedGate);
        mvc.perform(get(base(token) + "/dashboard").session(browser))
            .andExpect(jsonPath("$.terminal").doesNotExist());
    }

    @Test void aSignedInManagerMakesTheirDeviceTheirTerminal() throws Exception {
        String counter = terminal(boothId, "커피 카운터");
        String email = email();
        Invite invite = inviteAt(boothId, email, counter);
        String path = base(invite.token()) + "/terminal";

        // Not signed in: the link alone opens nothing.
        json(post(path).session(new MockHttpSession()), "{\"deviceId\":\"phone-1\"}")
            .andExpect(status().isUnauthorized());

        MockHttpSession browser = signedIn(invite.token(), email, new FakePasskey(ORIGIN));
        json(post(path).session(browser), "{\"deviceId\":\" \"}").andExpect(status().isBadRequest());
        String body = json(post(path).session(browser), "{\"deviceId\":\"phone-1\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.gateId").value(counter))
            .andExpect(jsonPath("$.label").value("커피 카운터"))
            .andReturn().getResponse().getContentAsString();
        String gateToken = JsonPath.read(body, "$.gateToken");
        assertEquals("phone-1", jdbc.queryForObject("SELECT bound_device FROM gate WHERE id = ?", String.class, counter));

        // The token is the terminal's own: it opens the terminal's routes from that device.
        mvc.perform(get("/api/gates/" + counter + "/booth/dashboard")
                .header("X-Gate-Token", gateToken).header("X-Gate-Device", "phone-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.booth.name").value("커피 스탠드"));
        // Asking again from the same device is fine; another device is refused.
        json(post(path).session(browser), "{\"deviceId\":\"phone-1\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.gateToken").value(gateToken));
        json(post(path).session(browser), "{\"deviceId\":\"tablet-2\"}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("이 단말은 다른 기기에서 사용 중이에요. 주최 측에 연결 해제를 요청해 주세요."));
        assertEquals("phone-1", jdbc.queryForObject("SELECT bound_device FROM gate WHERE id = ?", String.class, counter));

        // Released from the console, the other device may have it.
        mvc.perform(post("/api/admin/gates/" + counter + "/release").with(admin(true)).with(csrf()))
            .andExpect(status().isOk());
        json(post(path).session(browser), "{\"deviceId\":\"tablet-2\"}").andExpect(status().isOk());

        // Unassigned: nothing to connect.
        json(put("/api/admin/booth-managers/" + invite.managerId()).with(admin(true)), "{\"gateId\":null}")
            .andExpect(status().isOk());
        json(post(path).session(browser), "{\"deviceId\":\"tablet-2\"}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("담당 단말이 정해지지 않았어요. 주최 측에 문의해 주세요."));

        // Revoked: gone, whatever the browser remembers.
        json(put("/api/admin/booth-managers/" + invite.managerId()).with(admin(true)), "{\"gateId\":\"" + counter + "\"}")
            .andExpect(status().isOk());
        mvc.perform(delete("/api/admin/booth-managers/" + invite.managerId()).with(admin(true)).with(csrf()))
            .andExpect(status().isOk());
        json(post(path).session(browser), "{\"deviceId\":\"tablet-2\"}").andExpect(status().isGone());
    }

    @Test void onlyTheTicketConsoleCanManageManagers() throws Exception {
        String invitePath = "/api/admin/booths/" + boothId + "/managers";
        mvc.perform(get(invitePath)).andExpect(status().isUnauthorized());
        json(post(invitePath), "{\"email\":\"" + email() + "\"}").andExpect(status().isUnauthorized());
        mvc.perform(get(invitePath).with(admin(false))).andExpect(status().isForbidden());
        json(post(invitePath).with(admin(false)), "{\"email\":\"" + email() + "\"}").andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/booth-managers/1").with(admin(false)).with(csrf()))
            .andExpect(status().isForbidden());
        json(post("/api/admin/booth-managers/1/reissue").with(admin(false)), "{}").andExpect(status().isForbidden());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM booth_manager WHERE booth_id = ?", Integer.class, boothId));
    }
}
