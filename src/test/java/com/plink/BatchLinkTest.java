package com.plink;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:batchlinks;DB_CLOSE_DELAY=-1",
    "plink.auth.google-client-id=test-client", "plink.auth.google-client-secret=test-secret"})
@AutoConfigureMockMvc
class BatchLinkTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.plink.service.LinkService linkService;

    @Test void recipientIsCheckedOnlyBeforePasskeyRegistration() throws Exception {
        for (String contact : new String[]{"first-last@example.com", "01012345678"}) {
            com.plink.model.ProtectedLink link = linkService.createLink("https://example.com", null, null, null, contact, 0, "owner");
            String base = "/api/links/s/" + link.getShortCode();
            mvc.perform(get(base)).andExpect(jsonPath("$.recipientType").value(contact.contains("@") ? "email" : "phone"))
                .andExpect(jsonPath("$.recipientNames").doesNotExist());
            for (String body : new String[]{"{}", "{\"recipientContact\":\"wrong@example.com\"}"}) {
                mvc.perform(post(base + "/passkey/options").with(csrf()).contentType("application/json").content(body))
                    .andExpect(status().isForbidden());
            }
            String supplied = contact.contains("@") ? "FIRST-LAST@example.com" : "010-1234-5678";
            mvc.perform(post(base + "/passkey/options").with(csrf()).contentType("application/json")
                .content("{\"recipientContact\":\"" + supplied + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("register"));
            // Existing binding: only the registered passkey is requested, no contact is needed.
            String identifier = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(link.getShortCode().getBytes());
            jdbc.update("INSERT INTO link_passkey (link_id,credential_id,user_handle,public_key,receiver_name) VALUES (?,?,?,?,?)",
                link.getId(), identifier, identifier, "AQID", "receiver");
            mvc.perform(get(base)).andExpect(jsonPath("$.recipientType").isEmpty());
            mvc.perform(post(base + "/passkey/options").with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("authenticate"));
        }
    }

    @Test void createsDistinctLinksMappedToEachRecipient() throws Exception {
        String body = "{\"originalUrl\":\"https://example.com/batch\",\"maxViews\":3,\"recipients\":[\"one@example.com\",\"010-1234-5678\"]}";
        String response = mvc.perform(post("/api/links/batch").with(oidcLogin()).with(csrf())
            .contentType("application/json").content(body))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].recipientNames").value("one@example.com"))
            .andExpect(jsonPath("$[1].recipientNames").value("01012345678"))
            .andExpect(jsonPath("$[1].maxViews").value(3)).andReturn().getResponse().getContentAsString();
        JsonNode links = mapper.readTree(response);
        assertNotEquals(links.get(0).get("shortCode"), links.get(1).get("shortCode"));
        for (JsonNode link : links) {
            mvc.perform(get("/api/links/" + link.get("id").asLong()).with(oidcLogin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.originalUrl").value("https://example.com/batch"));
            mvc.perform(get("/api/links/s/" + link.get("shortCode").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.recipientNames").doesNotExist());
        }
    }

    @Test void rejectsInvalidAndDuplicateRecipientsWithoutSaving() throws Exception {
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM protected_link", Integer.class);
        for (String recipients : new String[]{"[]", "[\"ok@example.com\",\"invalid\"]", "[\"010-1234-5678\",\"01012345678\"]",
                "[\"010 1234 5678\"]", "[\"0101234567\"]", "[\"+821012345678\"]", "[\"010-123-45678\"]"}) {
            mvc.perform(post("/api/links/batch").with(oidcLogin()).with(csrf()).contentType("application/json")
                .content("{\"originalUrl\":\"https://example.com\",\"recipients\":" + recipients + "}"))
                .andExpect(status().isBadRequest());
        }
        assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM protected_link", Integer.class));
    }

    @Test void requiresAuthenticationAndCsrf() throws Exception {
        mvc.perform(post("/api/links/batch").with(csrf()).contentType("application/json").content("{}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/links/batch").with(oidcLogin()).contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
    }
}
