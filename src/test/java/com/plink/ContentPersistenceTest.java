package com.plink;

import com.plink.service.ContentImportService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:contentpersistencetest;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.config.import=", "plink.admin.email=", "plink.admin.password=",
    "plink.auth.base-url=http://localhost:3000"})
@AutoConfigureMockMvc
class ContentPersistenceTest {
    @Autowired MockMvc mvc;
    @MockitoBean ContentImportService importer;

    @ParameterizedTest
    @ValueSource(strings = {"URL", "PDF"})
    void importedContentSurvivesNewRequestsAndIsPrivateToItsOwner(String type) throws Exception {
        Map<String, Object> draft = Map.of(
            "title", "Imported " + type,
            "body", Map.of("artworks", List.of(Map.of("title", "Blue Field"))),
            "sourceType", type, "sourceRef", type.equals("URL") ? "a".repeat(64) : "catalog.pdf",
            "artworkCount", 1, "warnings", List.of("Review imported information"));
        when(importer.fromUrl(any())).thenReturn(draft);
        when(importer.fromPdf(any())).thenReturn(draft);
        var owner = oidcLogin().idToken(token -> token.subject("content-owner"));
        var other = oidcLogin().idToken(token -> token.subject("other-owner"));
        var request = type.equals("URL")
            ? post("/api/contents/import/url").contentType("application/json")
                .content("{\"url\":\"https://example.com/catalog\"}")
            : multipart("/api/contents/import/pdf")
                .file(new MockMultipartFile("file", "catalog.pdf", "application/pdf", new byte[]{1}));
        String response = mvc.perform(request.with(owner).with(csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").isNumber())
            .andExpect(jsonPath("$.warnings[0]").value("Review imported information"))
            .andReturn().getResponse().getContentAsString();
        long id = new ObjectMapper().readTree(response).get("id").asLong();

        mvc.perform(get("/api/contents").with(owner))
            .andExpect(status().isOk()).andExpect(jsonPath("$[*].title", hasItem("Imported " + type)));
        mvc.perform(get("/api/contents/" + id).with(owner))
            .andExpect(status().isOk()).andExpect(jsonPath("$.sourceType").value(type))
            .andExpect(jsonPath("$.body.artworks[0].title").value("Blue Field"));
        mvc.perform(get("/api/contents").with(other))
            .andExpect(status().isOk()).andExpect(jsonPath("$[*].title", not(hasItem("Imported " + type))));
        mvc.perform(get("/api/contents/" + id).with(other)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/contents/" + id).with(other).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get("/api/contents/artworks").with(other))
            .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
    }
}
