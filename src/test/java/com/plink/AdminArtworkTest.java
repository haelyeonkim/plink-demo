package com.plink;

import com.plink.account.AdminAccount;
import com.plink.account.AdminPrincipal;
import com.plink.repository.ContentRepository;
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

    static RequestPostProcessor admin(boolean owner) {
        var account = new AdminAccount();
        account.id = 999; account.email = "admin@example.com";
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

    @Test void rejectsInvalidPagingAndStatus() throws Exception {
        mvc.perform(get("/api/admin/artworks").with(admin(true)).param("page", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/artworks").with(admin(true)).param("pageSize", "101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/artworks").with(admin(true)).param("status", "invalid")).andExpect(status().isBadRequest());
    }
}
