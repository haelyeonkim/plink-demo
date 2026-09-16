package com.plink.account;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Isolated from ./.env: the suite must not depend on whichever origin or secret a
// developer happens to have configured locally.
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:adminlogintest;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.config.import=", "plink.admin.email=", "plink.admin.password=", "plink.auth.base-url=http://localhost:3000"})
@AutoConfigureMockMvc
class AdminLoginTest {

    @Autowired MockMvc mvc;
    @Autowired AdminAccountService accounts;
    @Autowired AdminAccountRepository repository;

    private static final String EMAIL = "owner@example.com";
    private static final String PASSWORD = "a-long-enough-password";

    @BeforeEach void createAccount() {
        if (repository.findByEmail(EMAIL).isEmpty()) {
            accounts.create(EMAIL, PASSWORD, "운영자");
        }
        repository.findByEmail(EMAIL).ifPresent(account -> repository.recordSuccess(account.id));
    }

    private String body(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    @Test void aCorrectPasswordSignsIn() throws Exception {
        mvc.perform(post("/api/auth/login").session(new MockHttpSession()).with(csrf())
                .contentType("application/json").content(body(EMAIL, PASSWORD)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value(EMAIL));
    }

    @Test void theStoredPasswordIsAHashNotThePassword() {
        AdminAccount account = repository.findByEmail(EMAIL).orElseThrow();
        assertNotEquals(PASSWORD, account.passwordHash);
        assertTrue(account.passwordHash.startsWith("$2"), "BCrypt 해시여야 합니다");
    }

    @Test void anUnknownAddressAndAWrongPasswordAnswerTheSame() throws Exception {
        String wrongPassword = mvc.perform(post("/api/auth/login").with(csrf())
                .contentType("application/json").content(body(EMAIL, "not-the-password")))
            .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        String unknownEmail = mvc.perform(post("/api/auth/login").with(csrf())
                .contentType("application/json").content(body("nobody@example.com", PASSWORD)))
            .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        assertEquals(wrongPassword, unknownEmail, "어느 쪽이 틀렸는지 알려주면 계정 존재가 드러납니다");
    }

    @Test void repeatedFailuresLockTheAccount() throws Exception {
        String email = "lockme@example.com";
        accounts.create(email, PASSWORD, null);
        for (int i = 0; i < AdminAccountService.MAX_FAILURES; i++) {
            mvc.perform(post("/api/auth/login").with(csrf())
                    .contentType("application/json").content(body(email, "wrong")))
                .andExpect(status().isUnauthorized());
        }
        // Even the right password waits out the lockout.
        mvc.perform(post("/api/auth/login").with(csrf())
                .contentType("application/json").content(body(email, PASSWORD)))
            .andExpect(status().isTooManyRequests());
    }

    @Test void signingInGivesTheSessionAccessToTheConsole() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(get("/api/admin/sessions").session(session)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").session(session).with(csrf())
                .contentType("application/json").content(body(EMAIL, PASSWORD)))
            .andExpect(status().isOk());
        mvc.perform(get("/api/admin/sessions").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/links").session(session)).andExpect(status().isOk());
    }

    @Test void loginStillRequiresTheCsrfToken() throws Exception {
        mvc.perform(post("/api/auth/login").contentType("application/json").content(body(EMAIL, PASSWORD)))
            .andExpect(status().isForbidden());
    }

    @Test void shortPasswordsAreRefusedAtCreation() {
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
            () -> accounts.create("short@example.com", "short", null));
    }

    @Test void bootstrapCreatesThenResetsTheSameAccount() {
        accounts.bootstrap("boot@example.com", "first-password-long");
        AdminAccount created = repository.findByEmail("boot@example.com").orElseThrow();
        accounts.bootstrap("boot@example.com", "second-password-long");
        AdminAccount reset = repository.findByEmail("boot@example.com").orElseThrow();
        assertEquals(created.id, reset.id, "같은 계정을 재사용합니다");
        assertNotEquals(created.passwordHash, reset.passwordHash, "비밀번호는 갱신됩니다");
    }

    @Test void aLocalAdministratorOwnsTheLinksTheyCreate() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).with(csrf())
                .contentType("application/json").content(body(EMAIL, PASSWORD))).andExpect(status().isOk());
        mvc.perform(post("/api/links").session(session).with(csrf()).contentType("application/json")
                .content("{\"originalUrl\":\"https://example.com\",\"title\":\"내 링크\"}"))
            .andExpect(status().isCreated());
        mvc.perform(get("/api/links").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].title").value("내 링크"));
    }
}
