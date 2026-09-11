package com.plink.ticket;

import com.plink.ticket.repository.EventSessionRepository;
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
@SpringBootTest(properties = { "plink.ticket.mobile-only=ENFORCE" })
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

    private String path() { return "/api/t/" + sessionId + "/" + token; }

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
        mvc.perform(get("/api/t/" + sessionId + "/" + "z".repeat(22)).header("User-Agent", PHONE))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/t/" + (sessionId + 9999) + "/" + token).header("User-Agent", PHONE))
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
