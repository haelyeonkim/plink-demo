package com.plink.activity;

import com.plink.account.AdminAccount;
import com.plink.account.AdminPrincipal;
import com.plink.ticket.RecordingEmail;
import com.plink.ticket.repository.EventSessionRepository;
import com.plink.ticket.service.Secrets;
import com.plink.ticket.service.TicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** The service's own record, the console that reads it, and the links that hand it out. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:activitydatatest;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.config.import=",
    "plink.admin.email=", "plink.admin.password=", "plink.auth.base-url=http://localhost:3000"})
@AutoConfigureMockMvc
@Import(RecordingEmail.class)
class ActivityDataTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ActivityLog activity;
    @Autowired TicketService tickets;
    @Autowired EventSessionRepository sessions;
    @Autowired RecordingEmail.Mailbox mailbox;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach void accounts() {
        for (long id : new long[] { 998, 999 }) {
            jdbc.update("MERGE INTO admin_account (id, email, password_hash, slug, can_links, can_tickets, is_owner) "
                + "KEY (id) VALUES (?, ?, 'unused', ?, TRUE, TRUE, ?)",
                id, "admin" + id + "@example.com", "admin-" + id, id == 999);
        }
    }

    static RequestPostProcessor admin(boolean owner) {
        var account = new AdminAccount();
        account.id = owner ? 999 : 998; account.email = "admin" + account.id + "@example.com";
        account.passwordHash = "unused";
        account.owner = owner; account.canLinks = true; account.canTickets = true;
        var principal = new AdminPrincipal(account);
        return authentication(new UsernamePasswordAuthenticationToken(principal, null,
            principal.authorities().stream().map(SimpleGrantedAuthority::new).toList()));
    }

    private long newSession() {
        return sessions.insert("데이터 테스트 " + Secrets.randomAlnum(6), null,
            Timestamp.from(Instant.now().plus(5, ChronoUnit.HOURS)), null);
    }

    private List<Map<String, Object>> rows(String where, Object... args) {
        activity.flush();
        return jdbc.queryForList("SELECT * FROM activity_log WHERE " + where + " ORDER BY id", args);
    }

    @Test void everyRequestIsRecordedWithoutItsSecrets() throws Exception {
        long sessionId = newSession();
        Map<String, Object> issued = tickets.issue(sessionId, "guest@example.com", null, null, null, false);
        String url = String.valueOf(issued.get("url"));
        String token = url.substring(url.lastIndexOf('/') + 1);

        mvc.perform(get("/api/tickets/" + sessionId + "/" + token).header("User-Agent", "Mozilla/5.0 (iPhone)"))
            .andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json")
            .content("{\"email\":\"nobody@example.com\",\"password\":\"hunter2-secret\"}"));

        List<Map<String, Object>> requests = rows("category = 'REQUEST'");
        String everything = requests.toString();
        assertThat(everything).doesNotContain(token).doesNotContain("hunter2-secret");
        Map<String, Object> page = requests.stream()
            .filter(r -> String.valueOf(r.get("PATH")).startsWith("/api/tickets/" + sessionId + "/")).findFirst().orElseThrow();
        assertThat(page.get("ACTOR_TYPE")).isEqualTo("HOLDER");
        assertThat(((Number) page.get("TICKET_ID")).longValue()).isEqualTo(((Number) issued.get("ticketId")).longValue());
        assertThat(((Number) page.get("SESSION_ID")).longValue()).isEqualTo(sessionId);
        assertThat(page.get("STATUS")).isEqualTo(200);

        // The meaning, besides the request: the issue, the page view, the refused sign-in.
        assertThat(rows("action = 'ticket.issued' AND ticket_id = ?", issued.get("ticketId"))).hasSize(1);
        assertThat(rows("action = 'ticket.page' AND ticket_id = ?", issued.get("ticketId"))).hasSize(1);
        Map<String, Object> login = rows("action = 'admin.login' AND actor_email = 'nobody@example.com'").get(0);
        assertThat(login.get("OK")).isEqualTo(false);
        assertThat(login.get("RESULT")).isEqualTo("unknown_account");
    }

    @Test void theBrowserBeaconNeedsNoTokenButIsRateLimitedAndShort() throws Exception {
        mvc.perform(post("/api/log/client").contentType("application/json")
            .content("{\"type\":\"error\",\"app\":\"ticket\",\"path\":\"/tickets/3/abcdefghijklmnopqrstuvwxyz\","
                + "\"message\":\"TypeError: x is undefined\",\"stack\":\"at f\"}"))
            .andExpect(status().isNoContent());
        Map<String, Object> error = rows("action = 'client.error'").get(0);
        assertThat(error.get("CATEGORY")).isEqualTo("ERROR");
        assertThat(String.valueOf(error.get("PATH"))).isEqualTo("/tickets/3/abcd…");
    }

    @Test void onlyOwnersReadTheData() throws Exception {
        for (String path : new String[] { "/api/admin/data/overview", "/api/admin/data/logs", "/api/admin/data/exports" }) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).with(admin(false))).andExpect(status().isForbidden());
            mvc.perform(get(path).with(oidcLogin())).andExpect(status().isForbidden());
            mvc.perform(get(path).with(admin(true))).andExpect(status().isOk());
        }
    }

    @Test void theOverviewAndTheLogReadOneEvent() throws Exception {
        long sessionId = newSession();
        tickets.issue(sessionId, "a@example.com", null, null, null, false);
        tickets.issue(sessionId, "b@example.com", null, null, null, false);
        activity.flush();
        mvc.perform(get("/api/admin/data/overview").param("sessionId", String.valueOf(sessionId)).with(admin(true)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.kpis.issued").value(2))
            .andExpect(jsonPath("$.kpis.admittedPeople").value(0));
        mvc.perform(get("/api/admin/data/logs").param("sessionId", String.valueOf(sessionId))
                .param("categories", "TICKET").param("q", "b@example").with(admin(true)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.rows[0].actionLabel").value("발급"))
            .andExpect(jsonPath("$.rows[0].detail.email").value("b@example.com"));
        mvc.perform(get("/api/admin/data/categories").param("sessionId", String.valueOf(sessionId)).with(admin(true)))
            .andExpect(jsonPath("$[?(@.key == 'TICKET')].count").value(2));
    }

    @Test void savedViewsComeBack() throws Exception {
        String created = mvc.perform(post("/api/admin/data/views").with(admin(true)).with(csrf())
                .contentType("application/json").content("{\"name\":\"거절만\",\"filters\":{\"result\":\"FAIL\"}}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long id = json.readTree(created).get("id").asLong();
        mvc.perform(get("/api/admin/data/views").with(admin(true)))
            .andExpect(jsonPath("$[?(@.id == " + id + ")].filters.result").value("FAIL"));
        mvc.perform(delete("/api/admin/data/views/" + id).with(admin(true)).with(csrf())).andExpect(status().isNoContent());
    }

    @Test void anExportOpensWithItsCodeCountsDownloadsAndCloses() throws Exception {
        long sessionId = newSession();
        tickets.issue(sessionId, "minji@example.com", null, null, "010-1234-5678", false);

        // The raw addresses need a reason on record.
        mvc.perform(post("/api/admin/data/exports").with(admin(true)).with(csrf()).contentType("application/json")
                .content("{\"sessionId\":" + sessionId + ",\"pii\":\"RAW\",\"recipientName\":\"운영사무국\","
                    + "\"recipientEmail\":\"ops@example.com\",\"datasets\":[{\"key\":\"TICKETS\"}]}"))
            .andExpect(status().isBadRequest());
        // Numbers only cannot carry a list of people.
        mvc.perform(post("/api/admin/data/exports").with(admin(true)).with(csrf()).contentType("application/json")
                .content("{\"sessionId\":" + sessionId + ",\"pii\":\"AGGREGATE\",\"recipientName\":\"운영사무국\","
                    + "\"recipientEmail\":\"ops@example.com\",\"datasets\":[{\"key\":\"TICKETS\"}]}"))
            .andExpect(status().isBadRequest());

        String body = mvc.perform(post("/api/admin/data/exports").with(admin(true)).with(csrf())
                .contentType("application/json")
                .content("{\"sessionId\":" + sessionId + ",\"pii\":\"MASKED\",\"recipientName\":\"운영사무국\","
                    + "\"recipientEmail\":\"ops@example.com\",\"days\":7,\"maxDownloads\":2,\"requireCode\":true,"
                    + "\"datasets\":[{\"key\":\"SUMMARY\"},{\"key\":\"TICKETS\",\"columns\":[\"ticketRef\",\"email\",\"phone\"]}]}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode created = json.readTree(body);
        long id = created.get("id").asLong();
        String token = created.get("url").asString().substring(created.get("url").asString().lastIndexOf('/') + 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM data_export WHERE token_hmac = ?", Integer.class, token)).isZero();

        MockHttpSession browser = new MockHttpSession();
        String x = "/api/x/" + token;
        mvc.perform(get(x).session(browser)).andExpect(status().isOk())
            .andExpect(jsonPath("$.unlocked").value(false)).andExpect(jsonPath("$.files.length()").value(0));
        mvc.perform(get(x + "/zip").session(browser)).andExpect(status().isForbidden());
        mvc.perform(post(x + "/code").session(browser).with(csrf())).andExpect(status().isOk());
        String code = mailbox.lastCode().orElseThrow();
        mvc.perform(post(x + "/verify").session(browser).with(csrf()).contentType("application/json")
                .content("{\"code\":\"" + code + "\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.unlocked").value(true))
            .andExpect(jsonPath("$.files.length()").value(5));

        String csv = mvc.perform(get(x + "/files/{name}", "발급_등록현황.csv")
                .session(browser))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).contains("입장권 번호,받은 이메일,휴대폰").contains("m***@example.com").doesNotContain("minji@example.com")
            .contains("010****78");
        mvc.perform(get(x + "/zip").session(browser)).andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/zip"));
        // Two downloads were allowed, and both are spent.
        mvc.perform(get(x).session(browser)).andExpect(status().isGone());
        String access = mvc.perform(get("/api/admin/data/exports/" + id + "/access").with(admin(true)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(access.split("\"DOWNLOAD\"", -1).length - 1).isEqualTo(2);

        mvc.perform(post("/api/admin/data/exports/" + id + "/close").with(admin(true)).with(csrf()))
            .andExpect(status().isNoContent());
        mvc.perform(get(x)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM data_export_file WHERE export_id = ? AND content IS NOT NULL",
            Integer.class, id)).isZero();
        mvc.perform(get("/api/admin/data/exports").with(admin(true)))
            .andExpect(jsonPath("$[?(@.id == " + id + ")].status").value("CLOSED"));
    }
}
