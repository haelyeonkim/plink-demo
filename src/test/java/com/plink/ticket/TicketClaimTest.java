package com.plink.ticket;

import com.plink.ticket.repository.EventSessionRepository;
import com.plink.ticket.repository.HolderRepository;
import com.yubico.webauthn.data.ByteArray;
import com.plink.ticket.service.TicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The claim path: a personal URL plus control of the mailbox it was sent to. The
 * mobile-only policy is set to ENFORCE here so both sides of it are exercised.
 */
// Isolated from ./.env: the suite must not depend on whichever origin, secret or
// face service a developer happens to have configured locally.
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:ticketclaimtest;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.config.import=", "plink.admin.email=", "plink.admin.password=", "plink.ticket.mobile-only=ENFORCE" })
@AutoConfigureMockMvc
@Import(RecordingEmail.class)
class TicketClaimTest {
    private static final String PHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) Safari/605.1";
    private static final String DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/140.0 Safari/537.36";
    private static final String KAKAO = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) KAKAOTALK 10.5.0";

    @Autowired MockMvc mvc;
    @Autowired TicketService tickets;
    @Autowired EventSessionRepository sessions;
    @Autowired RecordingEmail.Mailbox mailbox;
    @Autowired HolderRepository holders;

    private long sessionId;
    private String token;

    @BeforeEach void issueTicket() {
        mailbox.clear();
        sessionId = sessions.insert("클레임 테스트 회차", "테스트홀",
            Timestamp.from(Instant.now().plus(3, ChronoUnit.HOURS)),
            Timestamp.from(Instant.now().minus(10, ChronoUnit.MINUTES)));
        tickets.issue(sessionId, "holder@example.com", "D-4", "VIP");
        String url = mailbox.lastTicketUrl().orElseThrow();
        token = url.substring(url.lastIndexOf('/') + 1);
    }

    private String path() { return "/api/tickets/" + sessionId + "/" + token; }

    @Test void aDesktopIsTurnedAwayWhenMobileOnlyIsEnforced() throws Exception {
        mvc.perform(get(path()).header("User-Agent", DESKTOP))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("휴대폰")));
    }

    @Test void clientHintsOutweighTheUserAgentString() throws Exception {
        // Sec-CH-UA-Mobile is what a current Chromium actually sends.
        mvc.perform(get(path()).header("User-Agent", DESKTOP).header("Sec-CH-UA-Mobile", "?1"))
            .andExpect(status().isOk());
        mvc.perform(get(path()).header("User-Agent", PHONE).header("Sec-CH-UA-Mobile", "?0"))
            .andExpect(status().isForbidden());
    }

    @Test void thePhonePageShowsTheEventButNoSecrets() throws Exception {
        mvc.perform(get(path()).header("User-Agent", PHONE))
            .andExpect(status().isOk())
            .andExpect(header().string("Accept-CH", org.hamcrest.Matchers.containsString("Sec-CH-UA-Mobile")))
            .andExpect(jsonPath("$.claimed").value(false))
            .andExpect(jsonPath("$.seat").value("D-4"))
            .andExpect(jsonPath("$.event.name").value("클레임 테스트 회차"))
            .andExpect(jsonPath("$.holderEmailMasked").value("h***@example.com"))
            .andExpect(jsonPath("$.presence.inside").value(false))
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("holder@example.com"))));
    }

    /**
     * A registered ticket's link opened on another phone shows the event and asks for the
     * passkey; the seat, the address, presence and the face status wait for it.
     */
    @Test void aRegisteredTicketShowsNothingUntilItsPasskeyAnswers(
            @Autowired com.plink.ticket.repository.TicketRepository ticketRows,
            @Autowired com.plink.ticket.service.TicketPasskeyService passkeys) throws Exception {
        long ticketId = tickets.resolve(sessionId, token).ticket.id;
        long holderId = holders.findByEmail("holder@example.com").map(h -> h.id)
            .orElseGet(() -> holders.create("holder@example.com", tickets.userHandleFor("holder@example.com")));
        ticketRows.bind(ticketId, holderId, "holder@example.com");

        mvc.perform(get(path()).header("User-Agent", PHONE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.locked").value(true))
            .andExpect(jsonPath("$.event.name").value("클레임 테스트 회차"))
            .andExpect(jsonPath("$.seat").doesNotExist())
            .andExpect(jsonPath("$.holderEmailMasked").doesNotExist())
            .andExpect(jsonPath("$.presence").doesNotExist())
            .andExpect(jsonPath("$.coupons").doesNotExist());
        mvc.perform(get(path() + "/face").header("User-Agent", PHONE))
            .andExpect(status().isForbidden());
        // Nor can the link alone put a face on the ticket, or take one off it: this event
        // claims without a code, so the mailbox check would have let it through.
        for (String face : new String[] {"/face/consent", "/face/withdraw"}) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path() + face)
                    .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                    .header("User-Agent", PHONE).contentType("application/json").content("{\"agreed\":true}"))
                .andExpect(status().isForbidden());
        }

        // The browser that answered the passkey sees the ticket.
        org.springframework.mock.web.MockHttpSession proven = new org.springframework.mock.web.MockHttpSession();
        passkeys.markViewer(proven, ticketRows.findById(ticketId).orElseThrow());
        mvc.perform(get(path()).header("User-Agent", PHONE).session(proven))
            .andExpect(jsonPath("$.locked").value(false))
            .andExpect(jsonPath("$.seat").value("D-4"));
        mvc.perform(get(path() + "/face").header("User-Agent", PHONE).session(proven))
            .andExpect(status().isOk());

        // And only for a while.
        org.springframework.mock.web.MockHttpSession stale = new org.springframework.mock.web.MockHttpSession();
        stale.setAttribute(com.plink.ticket.service.TicketPasskeyService.VIEW_PREFIX + ticketId,
            (System.currentTimeMillis() - 1) + "|" + holderId + "@"
                + ticketRows.findById(ticketId).orElseThrow().boundAt.getTime());
        mvc.perform(get(path()).header("User-Agent", PHONE).session(stale))
            .andExpect(jsonPath("$.locked").value(true));

        // A new phone asks for a fresh link with the email the ticket went to; a wrong
        // one gets nothing, and the right one only sends the link to that address.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path() + "/recover")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .header("User-Agent", PHONE).contentType("application/json").content(contact("guess@example.com")))
            .andExpect(status().isForbidden());
        mailbox.clear();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path() + "/recover")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .header("User-Agent", PHONE).contentType("application/json").content(contact("holder@example.com")))
            .andExpect(status().isOk());
        assertTrue(mailbox.lastTicketUrl().isPresent(), "새 링크는 입장권의 주소로만 가요");
    }

    /**
     * The passkey that opened the ticket also opens its codes: on that browser an entry
     * code needs no second prompt, and without it the link alone opens nothing.
     */
    @Test void theProvenBrowserOpensCodesWithoutAnotherPasskey(
            @Autowired com.plink.ticket.repository.TicketRepository ticketRows,
            @Autowired com.plink.ticket.service.TicketPasskeyService passkeys) throws Exception {
        long ticketId = tickets.resolve(sessionId, token).ticket.id;
        var present = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path() + "/present")
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
            .header("User-Agent", PHONE).contentType("application/json").content("{\"direction\":\"IN\"}");
        // Not registered yet: there is nobody to prove anything for.
        mvc.perform(present).andExpect(status().isConflict());

        long holderId = holders.findByEmail("holder@example.com").map(h -> h.id)
            .orElseGet(() -> holders.create("holder@example.com", tickets.userHandleFor("holder@example.com")));
        ticketRows.bind(ticketId, holderId, "holder@example.com");
        mvc.perform(present).andExpect(status().isForbidden());

        org.springframework.mock.web.MockHttpSession proven = new org.springframework.mock.web.MockHttpSession();
        passkeys.markViewer(proven, ticketRows.findById(ticketId).orElseThrow());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path() + "/present")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .header("User-Agent", PHONE).session(proven).contentType("application/json")
                .content("{\"direction\":\"IN\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.grantId").exists())
            .andExpect(jsonPath("$.direction").value("IN"));
    }

    /** Recovering onto a new phone ends what the old phone could see, at once. */
    @Test void aRebindEndsTheOldPhonesView(
            @Autowired com.plink.ticket.repository.TicketRepository ticketRows,
            @Autowired com.plink.ticket.service.TicketPasskeyService passkeys) {
        long ticketId = tickets.resolve(sessionId, token).ticket.id;
        long holderId = holders.findByEmail("holder@example.com").map(h -> h.id)
            .orElseGet(() -> holders.create("holder@example.com", tickets.userHandleFor("holder@example.com")));
        ticketRows.bind(ticketId, holderId, "holder@example.com");
        org.springframework.mock.web.MockHttpSession oldPhone = new org.springframework.mock.web.MockHttpSession();
        passkeys.markViewer(oldPhone, ticketRows.findById(ticketId).orElseThrow());
        assertTrue(passkeys.canView(oldPhone, ticketRows.findById(ticketId).orElseThrow()));

        tickets.reissueForConsole(ticketId, false);
        // bound_at is to the millisecond; a rebind in the same instant is not this test.
        try { Thread.sleep(5); } catch (InterruptedException ignored) { }
        ticketRows.bind(ticketId, holderId, "holder@example.com");
        assertFalse(passkeys.canView(oldPhone, ticketRows.findById(ticketId).orElseThrow()),
            "새 휴대폰에 다시 등록하면 옛 기기의 확인은 끝납니다");
    }

    @Test void anInAppBrowserIsFlaggedSoThePageCanRedirect() throws Exception {
        mvc.perform(get(path()).header("User-Agent", KAKAO))
            .andExpect(status().isOk())
            .andExpect(header().string("X-InApp-Browser", "kakaotalk"));
    }

    @Test void anUnknownTokenIsIndistinguishableFromAWrongSession() throws Exception {
        mvc.perform(get("/api/tickets/" + sessionId + "/" + "z".repeat(22)).header("User-Agent", PHONE))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/tickets/" + (sessionId + 9999) + "/" + token).header("User-Agent", PHONE))
            .andExpect(status().isNotFound());
    }

    /** No code is mailed any more: the endpoint that sent one is gone. */
    @Test void registeringSendsNoCode() throws Exception {
        mvc.perform(post(path() + "/otp").with(csrf()).header("User-Agent", PHONE)
                .contentType("application/json").content(contact("holder@example.com")))
            .andExpect(result -> assertTrue(result.getResponse().getStatus() >= 400));
        assertTrue(mailbox.lastCode().isEmpty());
    }

    @Test void writesStillRequireTheCsrfToken() throws Exception {
        mvc.perform(post(path() + "/passkey/options").header("User-Agent", PHONE)
                .contentType("application/json").content(contact("holder@example.com")))
            .andExpect(status().isForbidden());
    }

    /** By default the email or phone is checked; the page says so, and which of them it can take. */
    @Test void thePageReportsWhatRegistrationAsksFor() throws Exception {
        mvc.perform(get(path()).header("User-Agent", PHONE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.event.claimSkipCheck").value(false))
            .andExpect(jsonPath("$.event.claimByPhone").value(false));
    }

    @Test void thePhoneIsAskedWhereItIsOnlyWhenTheEventChecks() throws Exception {
        mvc.perform(get(path()).header("User-Agent", PHONE))
            .andExpect(jsonPath("$.event.geoCheck").value(false));
        // A mode with no venue to measure from checks nothing either.
        sessions.updateGeoPolicy(sessionId, null, null, 300, "ENFORCE");
        mvc.perform(get(path()).header("User-Agent", PHONE))
            .andExpect(jsonPath("$.event.geoCheck").value(false));
        sessions.updateGeoPolicy(sessionId, 37.5, 127.0, 300, "ADVISE");
        mvc.perform(get(path()).header("User-Agent", PHONE))
            .andExpect(jsonPath("$.event.geoCheck").value(true));
        sessions.updateGeoPolicy(sessionId, null, null, 300, "OFF");
    }

    /** An organiser handing links over in person can let the link stand alone. */
    @Test void turningTheCheckOffLetsTheLinkStandAlone() throws Exception {
        sessions.updateClaimPolicy(sessionId, true);
        mvc.perform(post(path() + "/passkey/options").session(new MockHttpSession()).with(csrf())
                .header("User-Agent", PHONE).contentType("application/json").content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.options").exists());

        sessions.updateClaimPolicy(sessionId, false);
        mvc.perform(post(path() + "/passkey/options").session(new MockHttpSession()).with(csrf())
                .header("User-Agent", PHONE).contentType("application/json").content("{}"))
            .andExpect(status().isBadRequest());
    }

    /**
     * The typed email or phone is all that stands between a forwarded link and a
     * stranger's phone, so it is checked rather than recorded.
     */
    @Test void claimingNeedsTheAddressTheTicketWentTo() throws Exception {
        mvc.perform(post(path() + "/passkey/options").session(new MockHttpSession()).with(csrf())
                .header("User-Agent", PHONE).contentType("application/json").content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("이메일이나 휴대폰 번호를 입력")));
        mvc.perform(post(path() + "/passkey/options").session(new MockHttpSession()).with(csrf())
                .header("User-Agent", PHONE).contentType("application/json").content(contact("someone.else@example.com")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("일치하지 않아요")));
        // Case and surrounding spaces are how people actually type an address.
        mvc.perform(post(path() + "/passkey/options").session(new MockHttpSession()).with(csrf())
                .header("User-Agent", PHONE).contentType("application/json").content(contact("  Holder@Example.com ")))
            .andExpect(status().isOk());
        // This ticket went out without a phone, so a number cannot claim it.
        mvc.perform(post(path() + "/passkey/options").session(new MockHttpSession()).with(csrf())
                .header("User-Agent", PHONE).contentType("application/json").content(contact("01012345678")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("휴대폰 번호가 없어요")));
    }

    /** A ticket that went to a phone as well can be claimed with either, however the number is written. */
    @Test void thePhoneTheTicketWentToClaimsItToo() throws Exception {
        long phoneEvent = sessions.insert("휴대폰 회차", null, Timestamp.from(Instant.now().plus(3, ChronoUnit.HOURS)), null);
        tickets.issue(phoneEvent, "phone.holder@example.com", null, null, "010-1234-5678");
        String url = mailbox.lastTicketUrl().orElseThrow();
        String base = "/api/tickets/" + phoneEvent + "/" + url.substring(url.lastIndexOf('/') + 1);
        mvc.perform(get(base).header("User-Agent", PHONE)).andExpect(jsonPath("$.event.claimByPhone").value(true));
        for (String number : new String[] { "01012345678", "010-1234-5678", "+82 10 1234 5678" }) {
            mvc.perform(post(base + "/passkey/options").session(new MockHttpSession()).with(csrf())
                    .header("User-Agent", PHONE).contentType("application/json").content(contact(number)))
                .andExpect(status().isOk());
        }
        mvc.perform(post(base + "/passkey/options").session(new MockHttpSession()).with(csrf())
                .header("User-Agent", PHONE).contentType("application/json").content(contact("010-9999-0000")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("휴대폰 번호와 일치하지")));
    }

    /**
     * The reason this identity exists: a person accumulates tickets, not passkeys. Their
     * next ticket - a different event, since one event gives an address one ticket -
     * asks them to prove the passkey they already have instead of creating another
     * entry on their device.
     */
    @Test void aSecondTicketForTheSamePersonAuthenticatesInsteadOfRegistering() throws Exception {
        MockHttpSession first = verifiedSession("holder@example.com");
        mvc.perform(post(path() + "/passkey/options").session(first).with(csrf())
                .header("User-Agent", PHONE).contentType("application/json").content(contact("holder@example.com")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mode").value("register"));

        // Stand in for a completed registration on this person's device.
        long holderId = holders.findByEmail("holder@example.com").orElseThrow().id;
        holders.insert(holderId, new ByteArray(new byte[] { 1, 2, 3, 4 }),
            new ByteArray(new byte[] { 5, 6, 7, 8 }), 0, "test");

        long nextEvent = sessions.insert("다음 회차", null,
            Timestamp.from(Instant.now().plus(3, ChronoUnit.HOURS)), null);
        tickets.issue(nextEvent, "holder@example.com", "D-9", null);
        String secondUrl = mailbox.lastTicketUrl().orElseThrow();
        String secondToken = secondUrl.substring(secondUrl.lastIndexOf('/') + 1);
        MockHttpSession second = verifiedSession("holder@example.com",
            "/api/tickets/" + nextEvent + "/" + secondToken);

        mvc.perform(post("/api/tickets/" + nextEvent + "/" + secondToken + "/passkey/options")
                .session(second).with(csrf()).header("User-Agent", PHONE)
                .contentType("application/json").content(contact("holder@example.com")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mode").value("authenticate"));
    }

    @Test void adifferentPersonStillRegistersTheirOwnPasskey() throws Exception {
        long otherSession = sessions.insert("다른 회차", null,
            Timestamp.from(Instant.now().plus(2, ChronoUnit.HOURS)), null);
        tickets.issue(otherSession, "someone.else@example.com", "E-1", null);
        String url = mailbox.lastTicketUrl().orElseThrow();
        String token = url.substring(url.lastIndexOf('/') + 1);
        MockHttpSession session = verifiedSession("someone.else@example.com",
            "/api/tickets/" + otherSession + "/" + token);

        mvc.perform(post("/api/tickets/" + otherSession + "/" + token + "/passkey/options")
                .session(session).with(csrf()).header("User-Agent", PHONE)
                .contentType("application/json").content(contact("someone.else@example.com")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mode").value("register"));
    }

    private MockHttpSession verifiedSession(String email) throws Exception {
        return verifiedSession(email, path());
    }

    private MockHttpSession verifiedSession(String email, String base) {
        return new MockHttpSession();
    }

    private static String contact(String value) {
        return "{\"contact\":\"" + value + "\"}";
    }

    @Test void maskingNeverLeaksTheLocalPart() {
        assertEquals("h***@example.com", TicketService.mask("holder@example.com"));
        assertEquals("a***@b.co", TicketService.mask("ab@b.co"));
        assertNull(TicketService.mask(null));
    }

    @Test void issuedTicketsAreListedForTheOrganiserWithoutTokens() throws Exception {
        Map<String, Object> issued = tickets.issue(sessionId, "second@example.com", "D-5", null);
        assertFalse(issued.toString().contains("token_hmac"));
        assertTrue(issued.containsKey("url"), "서비스 계층은 링크를 돌려주지만 콘솔 API는 제거합니다");
    }
}
