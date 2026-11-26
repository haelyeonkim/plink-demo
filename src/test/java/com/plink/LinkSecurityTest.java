package com.plink;

import com.plink.account.AdminAccountRepository;
import com.plink.account.AdminAccountService;
import com.plink.ticket.RecordingEmail;
import com.plink.ticket.repository.HolderRepository;
import com.plink.ticket.service.TicketService;
import com.yubico.webauthn.data.ByteArray;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The protected link's own defences: how a password is kept, who may make the first
 * passkey for an address, how guessing is answered, and what the console accepts.
 */
// Isolated from ./.env: the suite must not depend on a developer's local configuration.
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:linksecuritytest;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.config.import=", "plink.admin.email=", "plink.admin.password=",
    "plink.auth.base-url=http://localhost:3000",
    "plink.ticket.venue-zone=Asia/Seoul",
    "plink.links.max-failures-per-code=3", "plink.links.max-failures-per-client=6"})
@AutoConfigureMockMvc
@Import(RecordingEmail.class)
class LinkSecurityTest {

    @Autowired MockMvc mvc;
    @Autowired AdminAccountService accounts;
    @Autowired AdminAccountRepository repository;
    @Autowired RecordingEmail.Mailbox mailbox;
    @Autowired JdbcTemplate jdbc;
    @Autowired HolderRepository holders;
    @Autowired com.plink.repository.LinkRecipientRepository recipients;
    @Autowired TicketService tickets;

    private static final String EMAIL = "linksec@example.com";
    private static final String PASSWORD = "a-long-enough-password";
    private static final AtomicInteger CLIENTS = new AtomicInteger(1);
    private final ObjectMapper mapper = new ObjectMapper();
    private MockHttpSession admin;
    /** Every test guesses from its own address, so the per-client cap stays its own. */
    private RequestPostProcessor client;

    @BeforeEach void signIn() throws Exception {
        if (repository.findByEmail(EMAIL).isEmpty()) accounts.create(EMAIL, PASSWORD, null);
        admin = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(admin).with(csrf()).contentType("application/json")
                .content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk());
        String address = "10.20.30." + CLIENTS.getAndIncrement();
        client = request -> { request.setRemoteAddr(address); return request; };
    }

    private JsonNode json(String body) { return mapper.readTree(body); }

    private JsonNode createLink(String body) throws Exception {
        return json(mvc.perform(post("/api/links").session(admin).with(csrf())
                .contentType("application/json").content(body))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private String issue(long linkId, String email) throws Exception {
        return json(mvc.perform(post("/api/links/" + linkId + "/recipients").session(admin).with(csrf())
                .contentType("application/json").content("{\"email\":\"" + email + "\",\"notify\":false}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
            .get("shortCode").asText();
    }

    private org.springframework.test.web.servlet.ResultActions options(String code, String password,
            MockHttpSession session) throws Exception {
        return options(code, password, null, session);
    }

    /** {@code choice} "existing" answers a passkey the visitor already has; otherwise a new one is made. */
    private org.springframework.test.web.servlet.ResultActions options(String code, String password,
            String choice, MockHttpSession session) throws Exception {
        return mvc.perform(post("/api/links/s/" + code + "/passkey/options").session(session).with(csrf())
            .with(client).contentType("application/json")
            .content("{\"password\":" + (password == null ? "null" : "\"" + password + "\"")
                + (choice == null ? "" : ",\"choice\":\"" + choice + "\"") + "}"));
    }

    @Test void aPasswordFromBeforeBcryptStillOpensAndIsUpgraded() throws Exception {
        long linkId = createLink("{\"originalUrl\":\"https://example.com/a\",\"password\":\"open-sesame\"}")
            .get("id").asLong();
        String stored = jdbc.queryForObject("SELECT password_hash FROM protected_link WHERE id = ?",
            String.class, linkId);
        assertTrue(stored.startsWith("$2"), "새 비밀번호는 BCrypt로 저장해야 합니다: " + stored);

        jdbc.update("UPDATE protected_link SET password_hash = ? WHERE id = ?",
            String.valueOf("open-sesame".hashCode()), linkId);
        String code = issue(linkId, "legacy@example.com");
        options(code, "wrong", new MockHttpSession()).andExpect(status().isForbidden());
        assertEquals(String.valueOf("open-sesame".hashCode()), jdbc.queryForObject(
            "SELECT password_hash FROM protected_link WHERE id = ?", String.class, linkId),
            "틀린 비밀번호로는 아무것도 바뀌지 않아야 합니다");

        options(code, "open-sesame", new MockHttpSession()).andExpect(status().isOk());
        String upgraded = jdbc.queryForObject("SELECT password_hash FROM protected_link WHERE id = ?",
            String.class, linkId);
        assertTrue(upgraded.startsWith("$2"), "확인된 옛 비밀번호는 BCrypt로 바뀌어야 합니다: " + upgraded);
        options(code, "open-sesame", new MockHttpSession()).andExpect(status().isOk());
    }

    /** The first passkey made on an address claims it: nothing is mailed, nothing typed. */
    @Test void theFirstPasskeyClaimsWithoutACode() throws Exception {
        long linkId = createLink("{\"originalUrl\":\"https://example.com/b\"}").get("id").asLong();
        String code = issue(linkId, "first@example.com");
        mailbox.clear();

        mvc.perform(get("/api/links/s/" + code).with(client))
            .andExpect(jsonPath("$.contactRequired").doesNotExist())
            .andExpect(jsonPath("$.suggest").value("new"));
        options(code, null, new MockHttpSession())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mode").value("register"))
            .andExpect(jsonPath("$.options.publicKey.user.name").value(org.hamcrest.Matchers.startsWith("패스링크 · ")))
            .andExpect(jsonPath("$.options.publicKey.authenticatorSelection.residentKey").value("required"));
        assertTrue(mailbox.sent.isEmpty(), "인증번호를 보내지 않아야 합니다");
        mvc.perform(post("/api/links/s/" + code + "/passkey/verify").session(new MockHttpSession()).with(csrf()).with(client)
                .contentType("application/json").content("{\"code\":\"123456\"}"))
            .andExpect(result -> assertTrue(result.getResponse().getStatus() >= 400, "인증번호 확인 경로는 없어야 합니다"));
    }

    /**
     * Somebody whose address has claimed before - here a holder from when an email named the
     * person - is offered that passkey first, asked only for it, and excluded from making another.
     */
    @Test void anAddressThatClaimedBeforeIsOfferedItsPasskey() throws Exception {
        String email = "known@example.com";
        long holderId = holders.create(email, tickets.userHandleFor(email));
        holders.insert(holderId, new ByteArray(new byte[] { 1, 2, 3, 4 }), new ByteArray(new byte[] { 5, 6 }),
            0, "00000000000000000000000000000000");
        String credential = new ByteArray(new byte[] { 1, 2, 3, 4 }).getBase64Url();
        long linkId = createLink("{\"originalUrl\":\"https://example.com/c\"}").get("id").asLong();
        String code = issue(linkId, email);

        mvc.perform(get("/api/links/s/" + code).with(client)).andExpect(jsonPath("$.suggest").value("existing"));
        options(code, null, "existing", new MockHttpSession())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mode").value("authenticate"))
            // Naming nobody: the passkey that answers decides whose claim it is.
            .andExpect(jsonPath("$.options.publicKey.allowCredentials").doesNotExist());
        options(code, null, new MockHttpSession())
            .andExpect(jsonPath("$.mode").value("register"))
            .andExpect(jsonPath("$.options.publicKey.excludeCredentials[0].id").value(credential));
    }

    /** A link claimed when an email named the person still asks that person's passkey, by its handle. */
    @Test void aLinkClaimedByAnEmailHolderStillOpensWithItsPasskey() throws Exception {
        String email = "before@example.com";
        long holderId = holders.create(email, tickets.userHandleFor(email));
        holders.insert(holderId, new ByteArray(new byte[] { 7, 7, 7, 7 }), new ByteArray(new byte[] { 5, 6 }),
            0, "00000000000000000000000000000000");
        long linkId = createLink("{\"originalUrl\":\"https://example.com/claimed\"}").get("id").asLong();
        String code = issue(linkId, email);
        long recipientId = jdbc.queryForObject("SELECT id FROM link_recipient WHERE short_code = ?", Long.class, code);
        assertEquals(1, recipients.bindIfUnclaimed(recipientId, holderId));

        options(code, null, new MockHttpSession())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mode").value("authenticate"))
            .andExpect(jsonPath("$.options.publicKey.allowCredentials[0].id")
                .value(new ByteArray(new byte[] { 7, 7, 7, 7 }).getBase64Url()));
    }

    @Test void aWrongPasswordIsRefused() throws Exception {
        long linkId = createLink("{\"originalUrl\":\"https://example.com/d\",\"password\":\"right-one\"}")
            .get("id").asLong();
        String code = issue(linkId, "same@example.com");
        options(code, "wrong-one", new MockHttpSession())
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("비밀번호를 확인해 주세요."));
        options(code, "right-one", new MockHttpSession()).andExpect(status().isOk());
    }

    @Test void guessingAtOneAddressIsCutOff() throws Exception {
        long linkId = createLink("{\"originalUrl\":\"https://example.com/e\",\"password\":\"right-one\"}")
            .get("id").asLong();
        String code = issue(linkId, "guess@example.com");
        for (int i = 0; i < 3; i++) {
            options(code, "guess-" + i, new MockHttpSession()).andExpect(status().isForbidden());
        }
        // Even the right answer waits once the address has been guessed at enough.
        options(code, "right-one", new MockHttpSession())
            .andExpect(status().isTooManyRequests());
    }

    @Test void oneClientGuessingAcrossManyAddressesIsCutOff() throws Exception {
        long linkId = createLink("{\"originalUrl\":\"https://example.com/f\",\"password\":\"right-one\"}")
            .get("id").asLong();
        for (int round = 0; round < 3; round++) {
            String code = issue(linkId, "sweep" + round + "@example.com");
            for (int i = 0; i < 2; i++) {
                options(code, "nope", new MockHttpSession())
                    .andExpect(status().isForbidden());
            }
        }
        String fresh = issue(linkId, "fresh@example.com");
        options(fresh, "right-one", new MockHttpSession())
            .andExpect(status().isTooManyRequests());
        // Somebody else, elsewhere, is not held up by it.
        mvc.perform(post("/api/links/s/" + fresh + "/passkey/options").session(new MockHttpSession())
                .with(csrf()).with(request -> { request.setRemoteAddr("10.99.99.99"); return request; })
                .contentType("application/json")
                .content("{\"password\":\"right-one\"}"))
            .andExpect(status().isOk());
    }

    @Test void aWallClockDeadlineIsReadInTheVenueZone() throws Exception {
        long bare = createLink("{\"originalUrl\":\"https://example.com/g\",\"expiresAt\":\"2026-05-01 18:00:00\"}")
            .get("id").asLong();
        long withOffset = createLink("{\"originalUrl\":\"https://example.com/g\","
            + "\"expiresAt\":\"2026-05-01T09:00:00.000Z\"}").get("id").asLong();
        Instant expected = Instant.parse("2026-05-01T09:00:00Z");
        for (long id : new long[] { bare, withOffset }) {
            assertEquals(expected, jdbc.queryForObject("SELECT expires_at FROM protected_link WHERE id = ?",
                Timestamp.class, id).toInstant(), "서울 18시는 UTC 9시입니다");
        }
        mvc.perform(post("/api/links").session(admin).with(csrf()).contentType("application/json")
                .content("{\"originalUrl\":\"https://example.com/g\",\"expiresAt\":\"next tuesday\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(put("/api/links/" + bare).session(admin).with(csrf()).contentType("application/json")
                .content("{\"expiresAt\":\"2026-13-45 99:00\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test void theConsoleRefusesWhatALinkCannotHold() throws Exception {
        for (String url : new String[] { "javascript:alert(1)", "data:text/html,hi", "https://user@example.com/",
                "ftp://example.com/file", "/relative/path" }) {
            mvc.perform(post("/api/links").session(admin).with(csrf()).contentType("application/json")
                    .content("{\"originalUrl\":\"" + url + "\"}"))
                .andExpect(status().isBadRequest());
        }
        long id = createLink("{\"originalUrl\":\"https://example.com/h\"}").get("id").asLong();
        mvc.perform(put("/api/links/" + id).session(admin).with(csrf()).contentType("application/json")
                .content("{\"originalUrl\":\"javascript:alert(1)\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/links").session(admin).with(csrf()).contentType("application/json")
                .content("{\"originalUrl\":\"https://example.com/h\",\"maxViews\":\"lots\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/links").session(admin).with(csrf()).contentType("application/json")
                .content("{\"originalUrl\":\"https://example.com/h\",\"title\":\"" + "가".repeat(51) + "\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/links").session(admin).with(csrf()).contentType("application/json")
                .content("{\"originalUrl\":\"https://example.com/h\",\"recipientNames\":\"" + "a".repeat(501) + "\"}"))
            .andExpect(status().isBadRequest());
        assertEquals(0, createLink("{\"originalUrl\":\"https://example.com/h\",\"maxViews\":-5}")
            .get("maxViews").asInt(), "음수는 제한 없음으로 읽습니다");
    }

    @Test void anEditChangesOnlyWhatItNames() throws Exception {
        long id = createLink("{\"originalUrl\":\"https://example.com/i\",\"title\":\"원래 제목\","
            + "\"expiresAt\":\"2030-01-01T00:00:00Z\",\"maxViews\":5}").get("id").asLong();
        mvc.perform(put("/api/links/" + id).session(admin).with(csrf()).contentType("application/json")
                .content("{\"title\":\"새 제목\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("새 제목"))
            .andExpect(jsonPath("$.maxViews").value(5))
            .andExpect(jsonPath("$.expiresAt").isNotEmpty());
        // Named and empty still clears.
        mvc.perform(put("/api/links/" + id).session(admin).with(csrf()).contentType("application/json")
                .content("{\"expiresAt\":null}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("새 제목"))
            .andExpect(jsonPath("$.expiresAt").isEmpty());
    }
}
