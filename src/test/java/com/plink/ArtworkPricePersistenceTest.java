package com.plink;

import com.plink.repository.ArtworkRepository;
import com.plink.repository.ContentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:artworkpricetest;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.config.import=",
    "plink.admin.email=", "plink.admin.password=", "plink.auth.base-url=http://localhost:3000"})
@AutoConfigureMockMvc
@Transactional
class ArtworkPricePersistenceTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void admins() { AdminArtworkTest.ensureAdmins(jdbc); }
    @Autowired ArtworkRepository artworks;
    @Autowired ContentRepository contents;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void savesStructuredPricesAcrossOwnerAndAdminEditorsAndSnapshots() throws Exception {
        var created = mvc.perform(post("/api/contents").with(oidcLogin()).with(csrf())
            .contentType("application/json").content("""
            {"title":"Prices","body":{"artworks":[{"title":"Work","priceMode":"amount",
             "priceAmount":"5000.25","priceCurrency":"USD","saleStatus":"hold",
             "priceKrwRate":"1350.5","priceRateDate":"2026-10-11"}]}}
            """)).andExpect(status().isCreated()).andExpect(jsonPath("$.body.artworks[0].priceAmount").value("5000.25"))
            .andReturn().getResponse().getContentAsString();
        long source = mapper.readTree(created).path("id").asLong();
        long id = jdbc.queryForObject("SELECT id FROM artwork WHERE source_content_id=?", Long.class, source);
        var record = artworks.findOwnedByIds("user", List.of(id)).get(0);
        long snapshot = contents.insert("user", "Delivery", "SELECTION",
            mapper.writeValueAsString(Map.of("artworks", List.of(record.body()))));
        artworks.linkSnapshot(snapshot, List.of(record));
        String originalSnapshot = contents.findById(snapshot).orElseThrow().body;
        String request = """
            {"priceMode":"amount","priceAmount":"3000.75","priceCurrency":"EUR",
             "priceKrwRate":"1500.123456","priceRateDate":"2026-10-10"}
            """;
        mvc.perform(patch("/api/admin/artworks/" + id).with(AdminArtworkTest.admin(false)).with(csrf())
            .contentType("application/json").content(request)).andExpect(status().isForbidden());
        mvc.perform(patch("/api/admin/artworks/" + id).with(AdminArtworkTest.admin(true)).with(csrf())
            .contentType("application/json").content(request)).andExpect(status().isOk());
        var updated = artworks.findOwnedByIds("user", List.of(id)).get(0);
        assertThat(updated.pricing().amount()).isEqualByComparingTo("3000.75");
        assertThat(updated.pricing().currency()).isEqualTo("EUR");
        assertThat(updated.pricing().krwRate()).isEqualByComparingTo("1500.123456");
        assertThat(updated.saleStatus()).isEqualTo("hold");
        assertThat(contents.findById(snapshot).orElseThrow().body).isEqualTo(originalSnapshot);
        var read = mvc.perform(get("/api/contents/" + source).with(oidcLogin()))
            .andExpect(jsonPath("$.body.artworks[0].priceCurrency").value("EUR"))
            .andReturn().getResponse().getContentAsString();
        mvc.perform(put("/api/contents/" + source).with(oidcLogin()).with(csrf()).contentType("application/json")
            .content(read)).andExpect(status().isOk());
        assertThat(artworks.findOwnedByIds("user", List.of(id)).get(0).pricing().amount()).isEqualByComparingTo("3000.75");
        mvc.perform(patch("/api/admin/artworks/" + id).with(AdminArtworkTest.admin(true)).with(csrf())
            .contentType("application/json").content("{\"priceMode\":\"inquiry\",\"priceNote\":\"가격 문의\"}"))
            .andExpect(status().isOk());
        updated = artworks.findOwnedByIds("user", List.of(id)).get(0);
        assertThat(updated.pricing().amount()).isNull();
        assertThat(updated.pricing().krwRate()).isNull();
        assertThat(updated.pricing().rateDate()).isNull();
        assertThat(updated.pricing().note()).isEqualTo("가격 문의");
    }
    @Test void badPriceRollsBackTheWholeContentSave() throws Exception {
        mvc.perform(post("/api/contents").with(oidcLogin()).with(csrf()).contentType("application/json")
            .content("""
            {"title":"Invalid","body":{"artworks":[{"title":"Work","priceMode":"amount","priceAmount":"-10","priceCurrency":"USD"}]}}
            """)).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM link_content WHERE title='Invalid'", Integer.class)).isZero();
    }
}
