package com.plink.account;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A session is only as good as the account behind it is now, and sign-in failures are
 * held against whoever is failing rather than against the account.
 */
// Isolated from ./.env: the suite must not depend on a developer's local configuration.
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:adminsessiontest;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.config.import=", "plink.admin.email=", "plink.admin.password=",
    "plink.auth.base-url=http://localhost:3000"})
@AutoConfigureMockMvc
class AdminSessionTest {

    @Autowired MockMvc mvc;
    @Autowired AdminAccountService accounts;
    @Autowired AdminAccountRepository repository;

    private static final String PASSWORD = "a-long-enough-password";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private long owner() {
        return repository.findByEmail("session-owner@example.com").map(account -> account.id)
            .orElseGet(() -> accounts.create("session-owner@example.com", PASSWORD, null, true, true, true));
    }

    private String fresh(boolean links, boolean tickets) {
        String email = "staff" + SEQUENCE.incrementAndGet() + "@example.com";
        accounts.create(email, PASSWORD, null, links, tickets, false);
        return email;
    }

    private static RequestPostProcessor from(String address) {
        return request -> { request.setRemoteAddr(address); return request; };
    }

    private MockHttpSession signIn(String email) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).with(csrf()).contentType("application/json")
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk());
        return session;
    }

    @Test void takingAPermissionAwayReachesASessionAlreadyOpen() throws Exception {
        String email = fresh(true, true);
        MockHttpSession session = signIn(email);
        mvc.perform(get("/api/links").session(session)).andExpect(status().isOk());

        accounts.updatePermissions(owner(), repository.findByEmail(email).orElseThrow().id, false, true, false);
        mvc.perform(get("/api/links").session(session)).andExpect(status().isForbidden());
        // The rest of what the account may still do keeps working without signing in again.
        mvc.perform(get("/api/admin/sessions").session(session)).andExpect(status().isOk());
    }

    @Test void aDeletedAccountsSessionEnds() throws Exception {
        String email = fresh(true, true);
        MockHttpSession session = signIn(email);
        accounts.delete(owner(), repository.findByEmail(email).orElseThrow().id);
        mvc.perform(get("/api/links").session(session)).andExpect(status().isUnauthorized());
    }

    @Test void aPasswordResetEndsSessionsThatProvedTheOldOne() throws Exception {
        String email = fresh(true, true);
        MockHttpSession session = signIn(email);
        accounts.resetPassword(repository.findByEmail(email).orElseThrow().id, "a-brand-new-password");
        mvc.perform(get("/api/links").session(session)).andExpect(status().isUnauthorized());
    }

    @Test void changingYourOwnPasswordKeepsThisSession() throws Exception {
        String email = fresh(true, true);
        MockHttpSession session = signIn(email);
        MockHttpSession elsewhere = signIn(email);
        mvc.perform(post("/api/auth/password").session(session).with(csrf()).contentType("application/json")
                .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"another-long-password\"}"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/links").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/links").session(elsewhere)).andExpect(status().isUnauthorized());
    }

    @Test void failuresFromElsewhereDoNotLockTheOwnerOut() throws Exception {
        String email = fresh(true, true);
        for (int i = 0; i < AdminAccountService.MAX_FAILURES; i++) {
            mvc.perform(post("/api/auth/login").with(csrf()).with(from("203.0.113.7"))
                    .contentType("application/json").content("{\"email\":\"" + email + "\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/login").with(csrf()).with(from("203.0.113.7")).contentType("application/json")
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isTooManyRequests());
        mvc.perform(post("/api/auth/login").with(csrf()).with(from("198.51.100.20")).contentType("application/json")
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk());
    }

    @Test void signingInIssuesANewCsrfToken() throws Exception {
        String email = fresh(true, true);
        MockHttpSession session = new MockHttpSession();
        ObjectMapper mapper = new ObjectMapper();
        JsonNode before = mapper.readTree(mvc.perform(get("/api/auth/session").session(session))
            .andReturn().getResponse().getContentAsString());
        String header = before.get("csrfHeader").asText();
        String token = before.get("csrfToken").asText();
        mvc.perform(post("/api/auth/login").session(session).header(header, token)
                .contentType("application/json")
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk());
        // A token read before sign-in no longer works after it.
        mvc.perform(post("/api/links").session(session).header(header, token).contentType("application/json")
                .content("{\"originalUrl\":\"https://example.com\"}"))
            .andExpect(status().isForbidden());
        String after = mapper.readTree(mvc.perform(get("/api/auth/session").session(session))
            .andReturn().getResponse().getContentAsString()).get("csrfToken").asText();
        mvc.perform(post("/api/links").session(session).header(header, after).contentType("application/json")
                .content("{\"originalUrl\":\"https://example.com\"}"))
            .andExpect(status().isCreated());
    }
}
