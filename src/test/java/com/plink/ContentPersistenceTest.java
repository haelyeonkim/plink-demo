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
    @Autowired com.plink.repository.ArtworkRepository artworks;
    @MockitoBean ContentImportService importer;

    @ParameterizedTest
    @org.junit.jupiter.params.provider.NullAndEmptySource
    @ValueSource(strings = {"hold", "sold"})
    void saleStatusPersistsAndCanBeCleared(String saleStatus) throws Exception {
        var owner = oidcLogin().idToken(token -> token.subject("sale-status-owner"));
        var mapper = new ObjectMapper();
        Map<String, Object> work = new java.util.LinkedHashMap<>();
        work.put("title", "Sale status work");
        work.put("saleStatus", saleStatus);
        Map<String, Object> request = Map.of("title", "Sale status", "body", Map.of("artworks", List.of(work)));
        String response = mvc.perform(post("/api/contents").with(owner).with(csrf())
                .contentType("application/json").content(mapper.writeValueAsString(request)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = mapper.readTree(response).get("id").asLong();
        String expected = saleStatus == null || saleStatus.isEmpty() ? null : saleStatus;
        var row = artworks.findByOwner("sale-status-owner").stream()
            .filter(artwork -> artwork.sourceContentId() == id).findFirst().orElseThrow();
        org.assertj.core.api.Assertions.assertThat(row.saleStatus()).isEqualTo(expected);
        org.assertj.core.api.Assertions.assertThat(row.body().get("saleStatus")).isEqualTo(expected);
        String library = mvc.perform(get("/api/contents/artworks").with(owner))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for (var entry : mapper.readTree(library)) {
            if (entry.get("sourceContentId").asLong() != id) continue;
            org.assertj.core.api.Assertions.assertThat(entry.has("saleStatus")).isTrue();
            org.assertj.core.api.Assertions.assertThat(entry.get("saleStatus").isNull() ? null
                : entry.get("saleStatus").asText()).isEqualTo(expected);
        }

        work.put("saleStatus", "invalid");
        mvc.perform(put("/api/contents/" + id).with(owner).with(csrf())
                .contentType("application/json").content(mapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest());
        org.assertj.core.api.Assertions.assertThat(artworks.findByOwner("sale-status-owner").stream()
            .filter(artwork -> artwork.sourceContentId() == id).findFirst().orElseThrow().saleStatus())
            .isEqualTo(expected);

        work.put("saleStatus", null);
        mvc.perform(put("/api/contents/" + id).with(owner).with(csrf())
                .contentType("application/json").content(mapper.writeValueAsString(request)))
            .andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(artworks.findByOwner("sale-status-owner").stream()
            .filter(artwork -> artwork.sourceContentId() == id).findFirst().orElseThrow().saleStatus()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"URL", "PDF"})
    void importedContentSurvivesNewRequestsAndIsPrivateToItsOwner(String type) throws Exception {
        Map<String, String> work = Map.ofEntries(
            Map.entry("title", "Blue Field"), Map.entry("artist", "Jane Artist"),
            Map.entry("year", "2026"), Map.entry("medium", "Oil on canvas"),
            Map.entry("width", "70"), Map.entry("height", "100"), Map.entry("depth", "3"),
            Map.entry("unit", "cm"), Map.entry("description", "Edition of 7"),
            Map.entry("price", "KRW 5,000,000"), Map.entry("image", ""));
        Map<String, Object> draft = Map.of(
            "title", "Imported " + type,
            "body", Map.of("artworks", List.of(work)),
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
        org.assertj.core.api.Assertions.assertThat(artworks.findByOwner("content-owner")
            .stream().filter(row -> row.sourceContentId() == id)).singleElement()
            .satisfies(row -> org.assertj.core.api.Assertions.assertThat(row.body()).isEqualTo(work));

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
    @org.junit.jupiter.api.Test
    void realMultiPagePdfIsParsedStoredAndReadBackWithoutMergingSameTitleWorks() throws Exception {
        byte[] bytes;
        try (var pdf = new org.apache.pdfbox.pdmodel.PDDocument();
                var output = new java.io.ByteArrayOutputStream()) {
            for (int pageNumber = 0; pageNumber < 2; pageNumber++) {
                var page = new org.apache.pdfbox.pdmodel.PDPage();
                pdf.addPage(page);
                try (var stream = new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf, page)) {
                    stream.beginText();
                    stream.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(50, 750);
                    stream.setLeading(18);
                    for (String line : List.of("Artist: Jane Artist", "Title: Untitled",
                            "Year: 2026", "Medium: Bronze", "Dimensions: 100 x 70 x 30 mm",
                            "Price: USD 1,200", "Edition: " + (pageNumber + 1) + "/7")) {
                        stream.showText(line); stream.newLine();
                    }
                    stream.endText();
                }
            }
            pdf.save(output);
            bytes = output.toByteArray();
        }
        // Keep the existing test context, but delegate this upload to the actual parser.
        when(importer.fromPdf(any())).thenAnswer(call ->
            new ContentImportService().fromPdf(call.getArgument(0)));
        var owner = oidcLogin().idToken(token -> token.subject("real-pdf-owner"));
        var file = new MockMultipartFile("file", "two-pages.pdf", "application/pdf", bytes);
        String response = mvc.perform(multipart("/api/contents/import/pdf").file(file)
                .with(owner).with(csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.artworkCount").value(2))
            .andReturn().getResponse().getContentAsString();
        long id = new ObjectMapper().readTree(response).get("id").asLong();
        var rows = artworks.findByOwner("real-pdf-owner");
        org.assertj.core.api.Assertions.assertThat(rows).hasSize(2).allSatisfy(row -> {
            org.assertj.core.api.Assertions.assertThat(row.sourceContentId()).isEqualTo(id);
            org.assertj.core.api.Assertions.assertThat(row.body()).containsEntry("artist", "Jane Artist")
                .containsEntry("title", "Untitled").containsEntry("year", "2026")
                .containsEntry("medium", "Bronze").containsEntry("height", "10")
                .containsEntry("width", "7").containsEntry("depth", "3")
                .containsEntry("unit", "cm").containsEntry("price", "USD 1,200");
        });
        org.assertj.core.api.Assertions.assertThat(rows).extracting(row -> row.description())
            .containsExactly("1/7", "2/7");
        mvc.perform(get("/api/contents/" + id).with(owner)).andExpect(status().isOk())
            .andExpect(jsonPath("$.body.artworks.length()").value(2))
            .andExpect(jsonPath("$.body.artworks[1].description").value("2/7"));
    }

}
