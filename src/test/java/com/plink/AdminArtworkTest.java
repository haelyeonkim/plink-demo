package com.plink;

import com.plink.account.AdminAccount;
import com.plink.account.AdminPrincipal;
import com.plink.repository.ContentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:adminartworktest;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.config.import=",
    "plink.admin.email=", "plink.admin.password=", "plink.auth.base-url=http://localhost:3000"})
@AutoConfigureMockMvc
@Transactional
class AdminArtworkTest {
    @Autowired MockMvc mvc;
    @Autowired ContentRepository contents;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void accounts() { ensureAdmins(jdbc); }

    /**
     * The session check holds a principal to its row, so the accounts {@link #admin}
     * signs in as have to exist. Idempotent, for classes that keep their rows.
     */
    static void ensureAdmins(JdbcTemplate jdbc) {
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

    @Test void onlyAccountOwnersCanReadAllArtworksAndTheirImages() throws Exception {
        for (String path : new String[]{"/api/admin/artworks", "/api/admin/artworks/images/missing"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).with(oidcLogin())).andExpect(status().isForbidden());
            mvc.perform(get(path).with(admin(false))).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/admin/artworks").with(admin(true))).andExpect(status().isOk());
    }

    @Test void includesEveryOwnerAndLegacyWorksWithoutDuplicatingDeliverySnapshots() throws Exception {
        jdbc.update("INSERT INTO admin_account (id, email, display_name, password_hash, slug) VALUES (42, ?, ?, ?, 'gallery')",
            "gallery@example.com", "Gallery", "unused");
        mvc.perform(post("/api/contents").with(oidcLogin().idToken(t -> t.subject("local:42"))).with(csrf())
            .contentType("application/json").content("""
                {"title":"Gallery catalog","body":{"artworks":[
                  {"title":"Blue Field","artist":"Jane","saleStatus":"hold"},
                  {"title":"Red Field","artist":"Jane","saleStatus":"sold"}]}}
                """)).andExpect(status().isCreated());
        contents.insert("legacy-owner", "Legacy catalog", "EXHIBITION",
            "{\"artworks\":[{\"title\":\"100% Original\",\"artist\":\"Lee\"}]}");
        contents.insert("legacy-owner", "Delivery", "SELECTION",
            "{\"artworks\":[{\"title\":\"Snapshot copy\"}]}");
        mvc.perform(get("/api/admin/artworks").with(admin(true)))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.total").value(3)).andExpect(jsonPath("$.items.length()").value(3));
        // Reloading does not index the same legacy work twice.
        mvc.perform(get("/api/admin/artworks").with(admin(true)).param("status", "unsold"))
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].title").value("100% Original"));
        mvc.perform(get("/api/admin/artworks").with(admin(true)).param("status", "hold").param("search", "GALLERY@"))
            .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].title").value("Blue Field"))
            .andExpect(jsonPath("$.items[0].ownerName").value("Gallery"));
        mvc.perform(get("/api/admin/artworks").with(admin(true)).param("status", "sold"))
            .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].saleStatus").value("sold"));
        mvc.perform(get("/api/admin/artworks").with(admin(true)).param("search", "%"))
            .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/admin/artworks").with(admin(true)).param("search", "missing"))
            .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/admin/artworks").with(admin(true)).param("pageSize", "2").param("page", "1"))
            .andExpect(jsonPath("$.total").value(3)).andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test void editsSourceAndLibraryWithoutChangingStatusImageOrSnapshots() throws Exception {
        long source = contents.insert("other-owner", "Original", "EXHIBITION",
            "{\"intro\":\"keep\",\"artworks\":[{\"title\":\"Before\",\"saleStatus\":\"sold\",\"image\":\"https://example.com/a.jpg\"},{\"title\":\"Sibling\"}]}");
        long snapshot = contents.insert("other-owner", "Delivery", "SELECTION",
            "{\"artworks\":[{\"title\":\"Before\"}]}");
        mvc.perform(get("/api/admin/artworks").with(admin(true))).andExpect(status().isOk());
        long id = jdbc.queryForObject("SELECT id FROM artwork WHERE source_content_id = ? AND position = 0", Long.class, source);
        String path = "/api/admin/artworks/" + id;
        for (boolean allowed : new boolean[]{false, true}) {
            mvc.perform(patch(path).with(admin(allowed)).with(csrf()).contentType("application/json")
                .content("{\"title\":\"After\",\"description\":\"New description\",\"price\":\"100\"}"))
                .andExpect(allowed ? status().isOk() : status().isForbidden());
        }
        org.junit.jupiter.api.Assertions.assertEquals("After", jdbc.queryForObject("SELECT title FROM artwork WHERE id = ?", String.class, id));
        org.junit.jupiter.api.Assertions.assertEquals("sold", jdbc.queryForObject("SELECT sale_status FROM artwork WHERE id = ?", String.class, id));
        org.junit.jupiter.api.Assertions.assertEquals("https://example.com/a.jpg", jdbc.queryForObject("SELECT image_url FROM artwork WHERE id = ?", String.class, id));
        var body = new tools.jackson.databind.ObjectMapper().readTree(contents.findById(source).orElseThrow().body);
        org.junit.jupiter.api.Assertions.assertEquals("After", body.path("artworks").get(0).path("title").asText());
        org.junit.jupiter.api.Assertions.assertEquals("Sibling", body.path("artworks").get(1).path("title").asText());
        org.junit.jupiter.api.Assertions.assertEquals("keep", body.path("intro").asText());
        org.junit.jupiter.api.Assertions.assertTrue(contents.findById(snapshot).orElseThrow().body.contains("Before"));
        for (String invalid : new String[]{"{\"title\":\" \"}", "{\"saleStatus\":\"hold\"}", "{\"artist\":123}"}) {
            mvc.perform(patch(path).with(admin(true)).with(csrf()).contentType("application/json").content(invalid))
                .andExpect(status().isBadRequest());
        }
        mvc.perform(patch("/api/admin/artworks/99999999").with(admin(true)).with(csrf())
            .contentType("application/json").content("{\"title\":\"Missing\"}")).andExpect(status().isNotFound());
    }

    @Test void exposesOriginalWebUrlAndRejectsUnsafeSourceLinks() throws Exception {
        mvc.perform(post("/api/contents").with(oidcLogin()).with(csrf()).contentType("application/json")
            .content("""
                {"title":"Web source","sourceType":"URL","sourceRef":"https://example.com/catalog?view=works",
                 "body":{"artworks":[{"title":"Web work"}]}}
                """)).andExpect(status().isCreated());
        mvc.perform(get("/api/admin/artworks").with(admin(true)).param("search", "Web work"))
            .andExpect(jsonPath("$.items[0].sourceRef").value("https://example.com/catalog?view=works"));
        long artworkId = jdbc.queryForObject("SELECT id FROM artwork WHERE title='Web work'", Long.class);
        mvc.perform(patch("/api/admin/artworks/" + artworkId).with(admin(true)).with(csrf())
            .contentType("application/json").content("{\"sourceRef\":\"https://example.com/restored\"}"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/admin/artworks").with(admin(true)).param("search", "Web work"))
            .andExpect(jsonPath("$.items[0].sourceRef").value("https://example.com/restored"));
        mvc.perform(patch("/api/admin/artworks/" + artworkId).with(admin(true)).with(csrf())
            .contentType("application/json").content("{\"sourceRef\":\"javascript:alert(1)\"}"))
            .andExpect(status().isBadRequest());
        for (String ref : new String[]{"javascript:alert(1)", "//example.com", "https://user:pass@example.com"}) {
            mvc.perform(post("/api/contents").with(oidcLogin()).with(csrf()).contentType("application/json")
                .content(new tools.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of(
                    "title", "Invalid", "sourceType", "URL", "sourceRef", ref, "body", java.util.Map.of()))))
                .andExpect(status().isBadRequest());
        }
    }

    @Test void rejectsInvalidPagingAndStatus() throws Exception {
        mvc.perform(get("/api/admin/artworks").with(admin(true)).param("page", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/artworks").with(admin(true)).param("pageSize", "101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/artworks").with(admin(true)).param("status", "invalid")).andExpect(status().isBadRequest());
    }
}
