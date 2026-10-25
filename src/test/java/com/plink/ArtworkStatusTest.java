package com.plink;

import com.plink.repository.*;
import com.plink.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:artworkstatustest;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.config.import=",
    "plink.admin.email=", "plink.admin.password=", "plink.auth.base-url=http://localhost:3000"})
@AutoConfigureMockMvc
class ArtworkStatusTest {
    @Autowired MockMvc mvc;
    @Autowired ArtworkRepository artworks;
    @Autowired ContentRepository contents;
    @Autowired ArtworkDeliveryService deliveries;
    @Autowired LinkService links;
    @Autowired ArtworkStatusService statuses;
    @Autowired ArtworkStatusEvents events;
    @Autowired ContentImageService images;
    @Autowired JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    private long source(String owner) throws Exception {
        String json = mvc.perform(post("/api/contents").with(oidcLogin().idToken(t -> t.subject(owner))).with(csrf())
            .contentType("application/json").content("""
                {"title":"Catalog","body":{"artworks":[{"title":"Same title","price":"100"},
                {"title":"Same title","price":"200"}]}}
                """)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).path("id").asLong();
    }

    @Test void statusReachesTwoOpenLinksAfterCommitAndExpirationClosesStream() throws Exception {
        String owner = UUID.randomUUID().toString();
        source(owner);
        long id = artworks.findByOwner(owner).get(0).id();
        var first = deliveries.create(owner, List.of(id), "one@example.com", "", "One", null, null, 0, false);
        var second = deliveries.create(owner, List.of(id), "two@example.com", "", "Two", null, null, 0, false);
        var results = new ArrayList<org.springframework.test.web.servlet.MvcResult>();
        for (var delivery : List.of(first, second)) {
            String url = "/api/links/s/" + delivery.recipient().shortCode + "/artwork-status/events";
            mvc.perform(get(url)).andExpect(status().isForbidden());
            var session = new MockHttpSession();
            images.grant(session, delivery.link(), delivery.recipient());
            results.add(mvc.perform(get(url).session(session)).andExpect(status().isOk())
                .andExpect(request().asyncStarted()).andReturn());
        }
        for (String state : List.of("hold", "sold")) {
            mvc.perform(patch("/api/admin/artworks/" + id + "/status").with(AdminArtworkTest.admin(true)).with(csrf())
                .header("Origin", "http://localhost:3000")
                .contentType("application/json").content("{\"saleStatus\":\"" + state + "\"}"))
                .andExpect(status().isOk());
            for (var result : results) assertThat(result.getResponse().getContentAsString())
                .contains("event:statuses", "\"saleStatus\":\"" + state + "\"");
            assertThat(statuses.statuses(first.link().getContentId()).get(0).saleStatus()).isEqualTo(state);
            assertThat(statuses.statuses(second.link().getContentId()).get(0).saleStatus()).isEqualTo(state);
        }
        statuses.change(id, null);
        assertThat(statuses.statuses(first.link().getContentId()).get(0).saleStatus()).isNull();
        jdbc.update("UPDATE protected_link SET expires_at = ? WHERE id IN (?, ?)",
            new java.sql.Timestamp(System.currentTimeMillis() - 1000), first.link().getId(), second.link().getId());
        events.refreshAll();
        for (var result : results) assertThat(result.getResponse().getContentAsString()).contains("event:unavailable");
    }

    @Test void databaseDetailsReachSelectionAndSourceLinksAndOpenStreams() throws Exception {
        String owner = UUID.randomUUID().toString();
        long source = source(owner);
        long id = artworks.findByOwner(owner).get(0).id();
        var delivery = deliveries.create(owner, List.of(id), "reader@example.com", "", "Copy", null, null, 0, false);
        var direct = links.createLink(null, source, "Source", null, null, null, 0, owner);
        var recipient = links.issue(direct.getId(), "direct@example.com", "");
        String stored = contents.findById(delivery.link().getContentId()).orElseThrow().body;
        var results = new ArrayList<org.springframework.test.web.servlet.MvcResult>();
        for (var pair : List.of(Map.entry(delivery.link(), delivery.recipient()), Map.entry(direct, recipient))) {
            var session = new MockHttpSession();
            images.grant(session, pair.getKey(), pair.getValue());
            results.add(mvc.perform(get("/api/links/s/" + pair.getValue().shortCode + "/artwork-status/events")
                .session(session)).andExpect(status().isOk()).andExpect(request().asyncStarted()).andReturn());
        }
        // Direct database writes have no application event; the periodic refresh must detect them.
        jdbc.update("UPDATE artwork SET title='Updated work', price_mode='amount', price_amount=1234.50, "
            + "price_currency='EUR', price_krw_rate=1500, price_rate_date=DATE '2026-10-25' WHERE id=?", id);
        events.refreshAll();
        for (long contentId : List.of(source, delivery.link().getContentId())) {
            var rows = statuses.body(contents.findById(contentId).orElseThrow()).path("artworks");
            var row = java.util.stream.StreamSupport.stream(rows.spliterator(), false)
                .filter(r -> r.path("artworkId").asLong() == id).findFirst().orElseThrow();
            assertThat(row.path("title").asText()).isEqualTo("Updated work");
            assertThat(row.path("priceAmount").asText()).isEqualTo("1234.5");
            assertThat(row.path("priceCurrency").asText()).isEqualTo("EUR");
            assertThat(row.path("priceKrwRate").asText()).isEqualTo("1500");
        }
        for (var result : results) assertThat(result.getResponse().getContentAsString())
            .contains("Updated work", "\"priceAmount\":\"1234.5\"", "\"priceCurrency\":\"EUR\"");
        mvc.perform(patch("/api/admin/artworks/" + id).with(AdminArtworkTest.admin(true)).with(csrf())
            .contentType("application/json").content("{\"priceMode\":\"hidden\"}"))
            .andExpect(status().isOk());
        events.refreshAll();
        for (var result : results) assertThat(result.getResponse().getContentAsString())
            .contains("\"priceMode\":\"hidden\"", "\"priceAmount\":\"\"", "\"priceKrwRate\":\"\"");
        assertThat(contents.findById(delivery.link().getContentId()).orElseThrow().body).isEqualTo(stored);
    }

    @Test void browserCanPreflightStatusChangesOnlyFromConfiguredOrigin() throws Exception {
        mvc.perform(options("/api/admin/artworks/1/status")
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "PATCH")
                .header("Access-Control-Request-Headers", "content-type,x-csrf-token"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
            .andExpect(header().string("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("PATCH")));
        mvc.perform(options("/api/admin/artworks/1/status")
                .header("Origin", "https://untrusted.example")
                .header("Access-Control-Request-Method", "PATCH"))
            .andExpect(status().isForbidden());
    }

    @Test void revokedRecipientsAndInvalidatedSessionsLoseLiveStatusAccess() throws Exception {
        String owner = UUID.randomUUID().toString();
        source(owner);
        long id = artworks.findByOwner(owner).get(0).id();
        for (boolean revokeRecipient : List.of(true, false)) {
            var delivery = deliveries.create(owner, List.of(id), "reader@example.com", "",
                "Revocation", null, null, 0, false);
            String url = "/api/links/s/" + delivery.recipient().shortCode + "/artwork-status/events";
            var session = new MockHttpSession();
            images.grant(session, delivery.link(), delivery.recipient());
            var result = mvc.perform(get(url).session(session)).andExpect(status().isOk())
                .andExpect(request().asyncStarted()).andReturn();
            if (revokeRecipient) {
                jdbc.update("UPDATE link_recipient SET status = 'REVOKED' WHERE id = ?", delivery.recipient().id);
            } else {
                session.invalidate();
            }
            events.refreshAll();
            assertThat(result.getResponse().getContentAsString()).contains("event:unavailable");
            String closed = result.getResponse().getContentAsString();
            statuses.change(id, "sold");
            assertThat(result.getResponse().getContentAsString()).isEqualTo(closed);
            mvc.perform(get(url).session(revokeRecipient ? session : new MockHttpSession()))
                .andExpect(status().isForbidden());
        }
    }

    @Test void identitySurvivesReorderingRemovalAndStaleEditsWithCurrentDetails() throws Exception {
        String owner = UUID.randomUUID().toString();
        long source = source(owner);
        var rows = artworks.findByOwner(owner);
        long first = rows.get(0).id(), second = rows.get(1).id();
        var delivery = deliveries.create(owner, List.of(first), "reader@example.com", "", "Copy", null, null, 0, false);
        statuses.change(first, "sold");
        var edited = List.of(Map.of("artworkId", Long.toString(second), "title", "Second"),
            Map.of("artworkId", Long.toString(first), "title", "Changed", "price", "999", "saleStatus", ""));
        mvc.perform(put("/api/contents/" + source).with(oidcLogin().idToken(t -> t.subject(owner))).with(csrf())
            .contentType("application/json").content(mapper.writeValueAsString(Map.of("title", "Edited", "body", Map.of("artworks", edited)))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.body.artworks[1].artworkId").value(Long.toString(first)))
            .andExpect(jsonPath("$.body.artworks[1].saleStatus").value("sold"));
        var snapshot = statuses.body(contents.findById(delivery.link().getContentId()).orElseThrow()).path("artworks").get(0);
        assertThat(snapshot.path("title").asText()).isEqualTo("Changed");
        assertThat(snapshot.path("price").asText()).isEqualTo("USD 999");
        artworks.replace(source, owner, List.of(edited.get(0)));
        assertThat(statuses.statuses(delivery.link().getContentId()).get(0).artworkId()).isEqualTo(first);
        statuses.change(first, "hold");
        assertThat(statuses.statuses(delivery.link().getContentId()).get(0).saleStatus()).isEqualTo("hold");
        mvc.perform(delete("/api/contents/" + source).with(oidcLogin().idToken(t -> t.subject(owner))).with(csrf()))
            .andExpect(status().isConflict());
    }

    @Test void statusChangesRequireAdminAndValidStatus() throws Exception {
        String owner = UUID.randomUUID().toString();
        source(owner);
        long id = artworks.findByOwner(owner).get(0).id();
        for (boolean admin : List.of(false, true)) {
            mvc.perform(patch("/api/admin/artworks/" + id + "/status").with(AdminArtworkTest.admin(admin)).with(csrf())
                .contentType("application/json").content("{\"saleStatus\":\"invalid\"}"))
                .andExpect(admin ? status().isBadRequest() : status().isForbidden());
        }
    }
}
