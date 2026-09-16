package com.plink.account;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** What each account may open, enforced on the server rather than in the menu. */
// Isolated from ./.env: the suite must not depend on a developer's local configuration.
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:accountpermissionstest;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.config.import=", "plink.admin.email=", "plink.admin.password=", "plink.auth.base-url=http://localhost:3000"})
@AutoConfigureMockMvc
class AccountPermissionsTest {

    @Autowired MockMvc mvc;
    @Autowired AdminAccountService accounts;
    @Autowired AdminAccountRepository repository;

    private static final String PASSWORD = "a-long-enough-password";

    private long ensure(String email, boolean links, boolean tickets, boolean owner) {
        return repository.findByEmail(email).map(account -> {
            repository.updatePermissions(account.id, links, tickets, owner);
            repository.recordSuccess(account.id);
            return account.id;
        }).orElseGet(() -> accounts.create(email, PASSWORD, null, links, tickets, owner));
    }

    private MockHttpSession signIn(String email) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).with(csrf())
                .contentType("application/json")
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk());
        return session;
    }

    @BeforeEach void accountsExist() {
        ensure("owner@example.com", true, true, true);
        ensure("links-only@example.com", true, false, false);
        ensure("tickets-only@example.com", false, true, false);
    }

    @Test void anAccountWithoutTheTicketScopeCannotReachTheTicketConsole() throws Exception {
        MockHttpSession session = signIn("links-only@example.com");
        mvc.perform(get("/api/links").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/sessions").session(session)).andExpect(status().isForbidden());
    }

    @Test void anAccountWithoutTheLinkScopeCannotReachTheLinkConsole() throws Exception {
        MockHttpSession session = signIn("tickets-only@example.com");
        mvc.perform(get("/api/admin/sessions").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/links").session(session)).andExpect(status().isForbidden());
    }

    @Test void onlyAnOwnerReachesAccountAdministration() throws Exception {
        mvc.perform(get("/api/accounts").session(signIn("links-only@example.com")))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/accounts").session(signIn("owner@example.com")))
            .andExpect(status().isOk());
    }

    @Test void theSessionTellsTheMenuWhatThisAccountMayOpen() throws Exception {
        mvc.perform(get("/api/auth/session").session(signIn("links-only@example.com")))
            .andExpect(jsonPath("$.user.canLinks").value(true))
            .andExpect(jsonPath("$.user.canTickets").value(false))
            .andExpect(jsonPath("$.user.canAccounts").value(false));
    }

    @Test void theLastOwnerCannotBeDemotedOrDeleted() {
        long owner = ensure("owner@example.com", true, true, true);
        long other = ensure("links-only@example.com", true, false, false);
        assertEquals(1, repository.countOwners());

        assertTrue(assertThrows(ResponseStatusException.class,
            () -> accounts.updatePermissions(other, owner, true, true, false))
            .getReason().contains("마지막 소유자"));
        assertTrue(assertThrows(ResponseStatusException.class,
            () -> accounts.delete(other, owner)).getReason().contains("마지막 소유자"));
        assertTrue(repository.findById(owner).orElseThrow().owner, "소유자 권한이 유지되어야 합니다");
    }

    @Test void nobodyEditsTheirOwnPermissions() {
        long owner = ensure("owner@example.com", true, true, true);
        assertTrue(assertThrows(ResponseStatusException.class,
            () -> accounts.updatePermissions(owner, owner, true, true, true))
            .getReason().contains("본인 계정"));
    }

    @Test void oneAccountNeverSeesAnotherAccountsLinks() throws Exception {
        MockHttpSession mine = signIn("links-only@example.com");
        String created = mvc.perform(post("/api/links").session(mine).with(csrf())
                .contentType("application/json")
                .content("{\"originalUrl\":\"https://example.com/mine\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(created.replaceAll(".*\"id\":(\\d+).*", "$1"));

        mvc.perform(get("/api/links").session(mine))
            .andExpect(jsonPath("$[?(@.id == " + id + ")]").exists());

        // A second account with the same scope holds no claim on the first one's links.
        ensure("other-links@example.com", true, false, false);
        MockHttpSession theirs = signIn("other-links@example.com");
        mvc.perform(get("/api/links").session(theirs))
            .andExpect(jsonPath("$[?(@.id == " + id + ")]").doesNotExist());
        mvc.perform(get("/api/links/" + id).session(theirs)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/links/" + id).session(theirs).with(csrf()))
            .andExpect(status().isNotFound());
    }

    @Test void aNewAccountGetsOnlyWhatItWasGiven() {
        long id = accounts.create("scoped@example.com", PASSWORD, null, false, true, false);
        AdminAccount created = repository.findById(id).orElseThrow();
        assertFalse(created.canLinks);
        assertTrue(created.canTickets);
        assertFalse(created.owner);
    }
}
