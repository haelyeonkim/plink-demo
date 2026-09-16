package com.plink;

import com.plink.account.AdminAccountRepository;
import com.plink.account.AdminAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.plink.ticket.RecordingEmail;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * A link is the document and each recipient is an address issued under it, the way a
 * ticket session holds its tickets.
 */
// Isolated from ./.env: the suite must not depend on a developer's local configuration.
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:linkrecipienttest;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.config.import=", "plink.admin.email=", "plink.admin.password=",
    "plink.auth.base-url=http://localhost:3000"})
@AutoConfigureMockMvc
@Import(RecordingEmail.class)
class LinkRecipientTest {

    @Autowired MockMvc mvc;
    @Autowired AdminAccountService accounts;
    @Autowired AdminAccountRepository repository;
    @Autowired RecordingEmail.Mailbox mailbox;

    private static final String EMAIL = "links@example.com";
    private static final String PASSWORD = "a-long-enough-password";
    private final ObjectMapper mapper = new ObjectMapper();
    private MockHttpSession session;

    @BeforeEach void signIn() throws Exception {
        if (repository.findByEmail(EMAIL).isEmpty()) accounts.create(EMAIL, PASSWORD, null);
        repository.findByEmail(EMAIL).ifPresent(account -> repository.recordSuccess(account.id));
        session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).with(csrf())
                .contentType("application/json")
                .content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk());
    }

    private JsonNode json(String body) { return mapper.readTree(body); }

    private long createLink() throws Exception {
        String body = mvc.perform(post("/api/links").session(session).with(csrf())
                .contentType("application/json")
                .content("{\"originalUrl\":\"https://example.com/doc\",\"title\":\"제안서\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json(body).get("id").asLong();
    }

    private JsonNode issue(long linkId, String label) throws Exception {
        return issue(linkId, label.hashCode() + "@example.com", label);
    }

    private JsonNode issue(long linkId, String email, String label) throws Exception {
        String body = mvc.perform(post("/api/links/" + linkId + "/recipients").session(session).with(csrf())
                .contentType("application/json")
                .content("{\"email\":\"" + email + "\",\"label\":\"" + label + "\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json(body);
    }

    @Test void aLinkCarriesManyRecipientsEachWithTheirOwnAddress() throws Exception {
        long linkId = createLink();
        JsonNode first = issue(linkId, "김지수");
        JsonNode second = issue(linkId, "이현우");
        assertNotEquals(first.get("shortCode").asText(), second.get("shortCode").asText(),
            "수신자마다 다른 주소여야 합니다");

        mvc.perform(get("/api/links/" + linkId).session(session))
            .andExpect(jsonPath("$.recipients.length()").value(2));
        mvc.perform(get("/api/links").session(session))
            .andExpect(jsonPath("$[?(@.id == " + linkId + ")].recipientCount").value(2));

        // Both addresses open the same document for their own holder.
        for (JsonNode recipient : new JsonNode[] { first, second }) {
            mvc.perform(get("/api/links/s/" + recipient.get("shortCode").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("제안서"))
                .andExpect(jsonPath("$.expired").value(false));
        }
    }

    @Test void revokingOneAddressLeavesTheOthersOpen() throws Exception {
        long linkId = createLink();
        JsonNode revoked = issue(linkId, "그만 둔 담당자");
        JsonNode kept = issue(linkId, "새 담당자");

        mvc.perform(post("/api/links/" + linkId + "/recipients/" + revoked.get("id").asLong() + "/status")
                .session(session).with(csrf()).contentType("application/json").content("{\"revoked\":true}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.revoked").value(true));

        mvc.perform(get("/api/links/s/" + revoked.get("shortCode").asText()))
            .andExpect(jsonPath("$.expired").value(true));
        mvc.perform(get("/api/links/s/" + kept.get("shortCode").asText()))
            .andExpect(jsonPath("$.expired").value(false));

        // And it can be switched back on.
        mvc.perform(post("/api/links/" + linkId + "/recipients/" + revoked.get("id").asLong() + "/status")
                .session(session).with(csrf()).contentType("application/json").content("{\"revoked\":false}"))
            .andExpect(jsonPath("$.revoked").value(false));
        mvc.perform(get("/api/links/s/" + revoked.get("shortCode").asText()))
            .andExpect(jsonPath("$.expired").value(false));
    }

    @Test void anAddressIsIssuedToSomebody() throws Exception {
        long linkId = createLink();
        mvc.perform(post("/api/links/" + linkId + "/recipients").session(session).with(csrf())
                .contentType("application/json").content("{\"label\":\"이메일 없음\"}"))
            .andExpect(status().isBadRequest());
        // The address is the identity the passkey will bind to, so the page shows only a
        // masked form of it to whoever opens the link.
        JsonNode recipient = issue(linkId, "kim@example.com", "김지수");
        mvc.perform(get("/api/links/s/" + recipient.get("shortCode").asText()))
            .andExpect(jsonPath("$.issuedTo").value("k***@example.com"));
    }

    @Test void addressesAreNamespacedByTheAccountThatIssuedThem() throws Exception {
        long linkId = createLink();
        JsonNode recipient = issue(linkId, "kim@example.com", "김지수");
        String code = recipient.get("shortCode").asText();
        String slug = repository.findByEmail(EMAIL).orElseThrow().slug;
        assertTrue(recipient.get("url").asText().endsWith("/s/" + slug + "/" + code),
            "발급 주소에 발급처가 들어가야 합니다: " + recipient.get("url").asText());

        mvc.perform(get("/api/links/s/" + slug + "/" + code)).andExpect(status().isOk());
        // The bare code keeps working and names where it now lives.
        mvc.perform(get("/api/links/s/" + code))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.issuer").value(slug))
            .andExpect(jsonPath("$.path").value("/s/" + slug + "/" + code));
        // Somebody else's segment is not this link's address.
        mvc.perform(get("/api/links/s/somebody-else/" + code)).andExpect(status().isNotFound());
    }

    @Test void renamingTheSegmentKeepsEveryAddressWorking() throws Exception {
        long linkId = createLink();
        String code = issue(linkId, "kim@example.com", "김지수").get("shortCode").asText();
        long accountId = repository.findByEmail(EMAIL).orElseThrow().id;
        accounts.updateSlug(accountId, "renamed-desk");

        mvc.perform(get("/api/links/s/renamed-desk/" + code)).andExpect(status().isOk());
        mvc.perform(get("/api/links/s/" + code))
            .andExpect(jsonPath("$.issuer").value("renamed-desk"));
        mvc.perform(get("/api/links/" + linkId).session(session))
            .andExpect(jsonPath("$.recipients[0].url").value(org.hamcrest.Matchers.endsWith(
                "/s/renamed-desk/" + code)));
    }

    @Test void issuingAnAddressMailsItToThePersonItWasIssuedTo() throws Exception {
        long linkId = createLink();
        mailbox.clear();
        JsonNode recipient = issue(linkId, "kim@example.com", "김지수");
        assertEquals("EMAIL", recipient.get("deliveredVia").asText());
        assertEquals(1, mailbox.sent.size(), "수신자에게 한 통이 나가야 합니다");
        assertEquals("kim@example.com", mailbox.sent.get(0)[0]);
        assertTrue(mailbox.lastBody().contains(recipient.get("url").asText()),
            "메일에 발급된 주소가 들어 있어야 합니다: " + mailbox.lastBody());
    }

    @Test void anAddressCanBeIssuedWithoutMailingIt() throws Exception {
        long linkId = createLink();
        mailbox.clear();
        String body = mvc.perform(post("/api/links/" + linkId + "/recipients").session(session).with(csrf())
                .contentType("application/json")
                .content("{\"email\":\"quiet@example.com\",\"notify\":false}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertEquals("LINK", json(body).get("deliveredVia").asText());
        assertTrue(mailbox.sent.isEmpty(), "보내지 않기로 했으면 한 통도 나가면 안 됩니다");
    }

    @Test void aDeletedAddressStopsResolving() throws Exception {
        long linkId = createLink();
        JsonNode recipient = issue(linkId, "오발송");
        mvc.perform(delete("/api/links/" + linkId + "/recipients/" + recipient.get("id").asLong())
                .session(session).with(csrf()))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/links/s/" + recipient.get("shortCode").asText()))
            .andExpect(status().isNotFound());
    }

    @Test void recipientsBelongToTheirOwnLink() throws Exception {
        long mine = createLink();
        long other = createLink();
        JsonNode recipient = issue(mine, "김지수");
        // Addressing it through the wrong link is a miss, not a cross-link edit.
        mvc.perform(post("/api/links/" + other + "/recipients/" + recipient.get("id").asLong() + "/status")
                .session(session).with(csrf()).contentType("application/json").content("{\"revoked\":true}"))
            .andExpect(status().isNotFound());
    }

    @Test void anotherAccountCannotIssueUnderSomebodyElsesLink() throws Exception {
        long linkId = createLink();
        if (repository.findByEmail("stranger@example.com").isEmpty()) {
            accounts.create("stranger@example.com", PASSWORD, null);
        }
        repository.findByEmail("stranger@example.com")
            .ifPresent(account -> repository.recordSuccess(account.id));
        MockHttpSession theirs = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(theirs).with(csrf()).contentType("application/json")
                .content("{\"email\":\"stranger@example.com\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk());

        mvc.perform(post("/api/links/" + linkId + "/recipients").session(theirs).with(csrf())
                .contentType("application/json")
                .content("{\"email\":\"stranger@example.com\",\"label\":\"끼어들기\"}"))
            .andExpect(status().isNotFound());
    }
}
