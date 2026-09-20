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

    @Test void theCodeGoesOnlyToTheAddressTheTicketWasIssuedTo() throws Exception {
        mvc.perform(post(path() + "/otp").with(csrf()).header("User-Agent", PHONE)
                .contentType("application/json").content("{\"email\":\"someone.else@example.com\"}"))
            .andExpect(status().isBadRequest());
        assertTrue(mailbox.lastCode().isEmpty(), "잘못된 주소로는 코드를 보내지 않습니다");
    }

    @Test void verifyingTheCodeUnlocksRegistration() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post(path() + "/otp").session(session).with(csrf()).header("User-Agent", PHONE)
                .contentType("application/json").content("{\"email\":\"holder@example.com\"}"))
            .andExpect(status().isOk());
        String code = mailbox.lastCode().orElseThrow();

        mvc.perform(post(path() + "/otp/verify").session(session).with(csrf()).header("User-Agent", PHONE)
                .contentType("application/json")
                .content("{\"email\":\"holder@example.com\",\"code\":\"000000\"}"))
            .andExpect(status().isBadRequest());

        mvc.perform(post(path() + "/otp/verify").session(session).with(csrf()).header("User-Agent", PHONE)
                .contentType("application/json")
                .content("{\"email\":\"holder@example.com\",\"code\":\"" + code + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.verified").value(true));

        // The same code cannot be spent twice.
        mvc.perform(post(path() + "/otp/verify").session(session).with(csrf()).header("User-Agent", PHONE)
                .contentType("application/json")
                .content("{\"email\":\"holder@example.com\",\"code\":\"" + code + "\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test void resendingTooSoonIsRateLimited() throws Exception {
        String body = "{\"email\":\"holder@example.com\"}";
        mvc.perform(post(path() + "/otp").with(csrf()).header("User-Agent", PHONE)
                .contentType("application/json").content(body)).andExpect(status().isOk());
        mvc.perform(post(path() + "/otp").with(csrf()).header("User-Agent", PHONE)
                .contentType("application/json").content(body)).andExpect(status().isTooManyRequests());
    }

    @Test void registrationRequiresAVerifiedMailbox() throws Exception {
        mvc.perform(post(path() + "/passkey/options").session(new MockHttpSession()).with(csrf())
                .header("User-Agent", PHONE).contentType("application/json").content("{}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("이메일 인증")));
    }

    @Test void writesStillRequireTheCsrfToken() throws Exception {
        mvc.perform(post(path() + "/otp").header("User-Agent", PHONE)
                .contentType("application/json").content("{\"email\":\"holder@example.com\"}"))
            .andExpect(status().isForbidden());
    }

    @Test void theAdminConsoleIsNotPublic() throws Exception {
        mvc.perform(get("/api/admin/sessions")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/sessions/" + sessionId + "/tickets").with(csrf())
                .contentType("application/json").content("{\"email\":\"x@example.com\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test void gateEndpointsRequireTheTerminalToken() throws Exception {
        mvc.perform(get("/api/gates/nope")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/gates/nope/scan").contentType("application/json").content("{\"code\":\"x\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test void thePageReportsWhetherClaimingNeedsTheMailbox() throws Exception {
        mvc.perform(get(path()).header("User-Agent", PHONE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.event.claimRequiresOtp").value(true));
    }

    @Test void turningTheClaimPolicyOffLetsTheLinkStandAlone() throws Exception {
        sessions.updateClaimPolicy(sessionId, false);
        // No code has been verified in this session, yet the ceremony is issued once the
        // address the ticket went to is typed back. Whether it registers or authenticates
        // depends on whether this person already has a passkey, which is not what this
        // test is about.
        mvc.perform(post(path() + "/passkey/options").session(new MockHttpSession()).with(csrf())
                .header("User-Agent", PHONE).contentType("application/json")
                .content("{\"email\":\"holder@example.com\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.options").exists());

        sessions.updateClaimPolicy(sessionId, true);
        mvc.perform(post(path() + "/passkey/options").session(new MockHttpSession()).with(csrf())
                .header("User-Agent", PHONE).contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
    }

    /**
     * Without a code the typed address is all that stands between a forwarded link and a
     * stranger's phone, so it is checked rather than recorded.
     */
    @Test void claimingWithoutACodeStillNeedsTheAddressTheTicketWentTo() throws Exception {
        sessions.updateClaimPolicy(sessionId, false);
        mvc.perform(post(path() + "/passkey/options").session(new MockHttpSession()).with(csrf())
                .header("User-Agent", PHONE).contentType("application/json").content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("이메일 주소를 입력")));

        mvc.perform(post(path() + "/passkey/options").session(new MockHttpSession()).with(csrf())
                .header("User-Agent", PHONE).contentType("application/json")
                .content("{\"email\":\"someone.else@example.com\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("일치하지 않아요")));

        // Case and surrounding spaces are how people actually type an address.
        mvc.perform(post(path() + "/passkey/options").session(new MockHttpSession()).with(csrf())
                .header("User-Agent", PHONE).contentType("application/json")
                .content("{\"email\":\"  Holder@Example.com \"}"))
            .andExpect(status().isOk());
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
                .header("User-Agent", PHONE).contentType("application/json").content("{}"))
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
                .contentType("application/json").content("{}"))
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
                .contentType("application/json").content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mode").value("register"));
    }

    private MockHttpSession verifiedSession(String email) throws Exception {
        return verifiedSession(email, path());
    }

    private MockHttpSession verifiedSession(String email, String base) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post(base + "/otp").session(session).with(csrf()).header("User-Agent", PHONE)
                .contentType("application/json").content("{\"email\":\"" + email + "\"}"))
            .andExpect(status().isOk());
        String code = mailbox.lastCode().orElseThrow();
        mvc.perform(post(base + "/otp/verify").session(session).with(csrf()).header("User-Agent", PHONE)
                .contentType("application/json")
                .content("{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}"))
            .andExpect(status().isOk());
        return session;
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
