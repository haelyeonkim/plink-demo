package com.plink.ticket;

import com.jayway.jsonpath.JsonPath;
import com.plink.ticket.config.TicketProperties;
import com.plink.ticket.repository.EventSessionRepository;
import com.plink.ticket.repository.HolderRepository;
import com.plink.ticket.repository.TicketRepository;
import com.plink.ticket.service.TicketService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Claiming a ticket with a passkey the person already has, through real ceremonies against
 * a software authenticator: the request names nobody, so whoever claimed something sent to
 * the same address cannot stand in the way of the visitor's own passkey, and a passkey made
 * where a ticket would not have allowed it does not carry a ticket either.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:existingpasskeyclaim;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.config.import=", "plink.admin.email=", "plink.admin.password=",
    "plink.auth.base-url=http://localhost:3000", "plink.ticket.mobile-only=ENFORCE" })
@AutoConfigureMockMvc
@Import(RecordingEmail.class)
class ExistingPasskeyClaimTest {
    private static final String ORIGIN = "http://localhost:3000";
    private static final String PHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) Safari/605.1";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TicketService tickets;
    @Autowired TicketRepository ticketRows;
    @Autowired EventSessionRepository sessions;
    @Autowired HolderRepository holders;
    @Autowired RecordingEmail.Mailbox mailbox;
    @Autowired TicketProperties properties;

    @BeforeEach void clear() { mailbox.clear(); }

    @AfterEach void policy() { properties.setAllowedAaguids(List.of()); }

    /** Each ticket in an event of its own: one address holds one ticket per event. */
    private String issue(String email, String phone) {
        long event = sessions.insert("기존 패스키 회차", "테스트홀",
            Timestamp.from(Instant.now().plus(3, ChronoUnit.HOURS)),
            Timestamp.from(Instant.now().minus(10, ChronoUnit.MINUTES)));
        tickets.issue(event, email, null, null, phone);
        String url = mailbox.lastTicketUrl().orElseThrow();
        return "/api/tickets/" + event + "/" + url.substring(url.lastIndexOf('/') + 1);
    }

    private long ticketId(String base) {
        String[] parts = base.split("/");
        return tickets.resolve(Long.parseLong(parts[3]), parts[4]).ticket.id;
    }

    private ResultActions options(String base, MockHttpSession browser, String body) throws Exception {
        return mvc.perform(post(base + "/passkey/options").session(browser).with(csrf())
            .header("User-Agent", PHONE).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions finish(String base, MockHttpSession browser, String credential) throws Exception {
        return mvc.perform(post(base + "/passkey/finish").session(browser).with(csrf())
            .header("User-Agent", PHONE).contentType(MediaType.APPLICATION_JSON).content(credential));
    }

    /** A person new to Passlink claims a ticket; returns the user handle their passkey was made under. */
    private String claimNew(String base, FakePasskey key) throws Exception {
        MockHttpSession browser = new MockHttpSession();
        String body = options(base, browser, "{}").andExpect(status().isOk())
            .andExpect(jsonPath("$.mode").value("register")).andReturn().getResponse().getContentAsString();
        finish(base, browser, key.register(JsonPath.read(body, "$.options.publicKey.challenge")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.claimed").value(true));
        return JsonPath.read(body, "$.options.publicKey.user.id");
    }

    /** Answers a ticket's "existing" request the way a discoverable passkey on the phone does. */
    private ResultActions claimExisting(String base, FakePasskey key, String handle, String attachment) throws Exception {
        MockHttpSession browser = new MockHttpSession();
        String body = options(base, browser, "{\"choice\":\"existing\"}").andExpect(status().isOk())
            .andExpect(jsonPath("$.mode").value("authenticate"))
            .andExpect(jsonPath("$.options.publicKey.allowCredentials").doesNotExist())
            .andReturn().getResponse().getContentAsString();
        return finish(base, browser, key.authenticate(JsonPath.read(body, "$.options.publicKey.challenge"), handle, attachment));
    }

    private Long holderOf(String base) {
        return ticketRows.findById(ticketId(base)).orElseThrow().holderId;
    }

    /**
     * Bob's link was forwarded to Carol, who claimed it. Bob's next ticket to the same address
     * still lets Bob answer with his own passkey: the request does not name Carol.
     */
    @Test void aForwardedClaimDoesNotLockOutTheRealRecipient() throws Exception {
        FakePasskey carol = new FakePasskey(ORIGIN), bob = new FakePasskey(ORIGIN);
        claimNew(issue("bob@example.com", null), carol);
        String bobHandle = claimNew(issue("bob.other@example.com", null), bob);
        long bobHolder = holders.findByHandle(bobHandle).orElseThrow().id;

        String next = issue("bob@example.com", null);
        mvc.perform(get(next).header("User-Agent", PHONE)).andExpect(jsonPath("$.suggest").value("existing"));
        claimExisting(next, bob, bobHandle, "platform").andExpect(status().isOk());
        assertEquals(bobHolder, holderOf(next));
        assertEquals(1, holders.findByHolder(bobHolder).size(), "Bob still has one passkey");
    }

    /** A passkey from a security key - a link allows one - does not carry a ticket on a mobile-only event. */
    @Test void aSecurityKeyCannotClaimATicket() throws Exception {
        FakePasskey key = new FakePasskey(ORIGIN);
        String handle = claimNew(issue("key@example.com", null), key);
        String next = issue("key@example.com", null);
        claimExisting(next, key, handle, "cross-platform").andExpect(status().isForbidden());
        assertNull(holderOf(next));
        claimExisting(next, key, handle, "platform").andExpect(status().isOk());
        assertNotNull(holderOf(next));
    }

    /** With an allow-list of authenticator models, an existing passkey of another model is refused. */
    @Test void anExistingPasskeyMustBeAnAllowedModel() throws Exception {
        FakePasskey key = new FakePasskey(ORIGIN);
        String handle = claimNew(issue("model@example.com", null), key);
        properties.setAllowedAaguids(List.of("fbfc3007-154e-4ecc-8c0b-6e020557d7bd"));
        String next = issue("model@example.com", null);
        claimExisting(next, key, handle, "platform").andExpect(status().isForbidden());
        assertNull(holderOf(next));
    }

    /** A registration given up halfway left a holder with an address and no passkey: nobody to answer. */
    @Test void aHolderWithNoPasskeyIsNotSomebodyToAnswer() throws Exception {
        holders.create("ghost@example.com", "ghost-handle");
        String base = issue("ghost@example.com", null);
        mvc.perform(get(base).header("User-Agent", PHONE)).andExpect(jsonPath("$.suggest").value("new"));
        options(base, new MockHttpSession(), "{}")
            .andExpect(jsonPath("$.options.publicKey.excludeCredentials").isEmpty());
    }
}
