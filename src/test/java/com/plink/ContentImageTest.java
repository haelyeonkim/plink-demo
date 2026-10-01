package com.plink;

import com.plink.service.ContentImageService;
import com.plink.repository.LinkRepository;
import com.plink.repository.LinkRecipientRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:contentimagetest;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.config.import=", "plink.admin.email=", "plink.admin.password=",
    "plink.images.backend=local", "plink.auth.base-url=http://localhost:3000"})
@AutoConfigureMockMvc
class ContentImageTest {
    @TempDir static Path directory;
    @DynamicPropertySource static void storage(DynamicPropertyRegistry registry) {
        registry.add("plink.images.directory", () -> directory.toString());
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ContentImageService images;
    @Autowired LinkRepository links;
    @Autowired LinkRecipientRepository recipients;
    @Autowired com.plink.service.ArtworkDeliveryService deliveries;
    private final ObjectMapper mapper = new ObjectMapper();

    private String upload() throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(3, 3, BufferedImage.TYPE_INT_RGB), "png", bytes);
        String response = mvc.perform(multipart("/api/contents/images")
            .file(new MockMultipartFile("file", "test.png", "image/png", bytes.toByteArray()))
            .with(oidcLogin()).with(csrf())).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).get("url").asText();
    }
    private long save(String url) throws Exception {
        String response = mvc.perform(post("/api/contents").with(oidcLogin()).with(csrf())
            .contentType("application/json").content(document(url)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).get("id").asLong();
    }
    private String document(String url) {
        return mapper.writeValueAsString(Map.of("title", "Image test", "body",
            Map.of("artworks", List.of(Map.of("title", "Work", "image", url)))));
    }
    @Test void uploadIsPrivateAndPersistsWithContent() throws Exception {
        String url = upload();
        long content = save(url);
        mvc.perform(get(url).with(oidcLogin())).andExpect(status().isOk())
            .andExpect(content().contentType("image/png")).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get(url)).andExpect(status().isNotFound());
        mvc.perform(get(url).with(oidcLogin().idToken(t -> t.subject("other")))).andExpect(status().isNotFound());
        mvc.perform(get("/api/contents/" + content).with(oidcLogin()))
            .andExpect(jsonPath("$.body.artworks[0].image").value(url));
        mvc.perform(post("/api/contents").with(oidcLogin().idToken(t -> t.subject("other"))).with(csrf())
            .contentType("application/json").content(document(url))).andExpect(status().isNotFound());
    }
    @Test void rejectsAnonymousUploadsMissingCsrfAndDisguisedFiles() throws Exception {
        var file = new MockMultipartFile("file", "bad.png", "image/png", "<svg/>".getBytes());
        mvc.perform(multipart("/api/contents/images").file(file).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/contents/images").file(file).with(oidcLogin())).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/contents/images").file(file).with(oidcLogin()).with(csrf())).andExpect(status().isBadRequest());
    }
    @Test void cleanupKeepsReferencedImagesAndRemovesAbandonedUploads() throws Exception {
        String abandoned = upload(), kept = upload();
        long content = save(kept);
        jdbc.update("UPDATE content_image SET created_at = ?", new Timestamp(System.currentTimeMillis() - 172800000));
        images.cleanup();
        mvc.perform(get(abandoned).with(oidcLogin())).andExpect(status().isNotFound());
        mvc.perform(get(kept).with(oidcLogin())).andExpect(status().isOk());
        mvc.perform(delete("/api/contents/" + content).with(oidcLogin()).with(csrf())).andExpect(status().isOk());
        images.cleanup();
        mvc.perform(get(kept).with(oidcLogin())).andExpect(status().isNotFound());
    }
    @Test void recipientNeedsPasskeyGrantAndRevocationClosesImageAccess() throws Exception {
        String url = upload(), unrelated = upload();
        long content = save(url);
        String response = mvc.perform(post("/api/links").with(oidcLogin()).with(csrf())
            .contentType("application/json").content("{\"contentId\":" + content + ",\"title\":\"Artwork\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long linkId = mapper.readTree(response).get("id").asLong();
        response = mvc.perform(post("/api/links/" + linkId + "/recipients").with(oidcLogin()).with(csrf())
            .contentType("application/json").content("{\"email\":\"viewer@example.com\",\"notify\":false}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long recipientId = mapper.readTree(response).get("id").asLong();
        var session = new MockHttpSession();
        mvc.perform(get(url).session(session)).andExpect(status().isNotFound());
        images.grant(session, links.findById(linkId).orElseThrow(), recipients.findById(recipientId).orElseThrow());
        mvc.perform(get(url).session(session)).andExpect(status().isOk());
        mvc.perform(get(unrelated).session(session)).andExpect(status().isNotFound());
        mvc.perform(post("/api/links/" + linkId + "/recipients/" + recipientId + "/status")
            .with(oidcLogin()).with(csrf()).contentType("application/json").content("{\"revoked\":true}"))
            .andExpect(status().isOk());
        mvc.perform(get(url).session(session)).andExpect(status().isNotFound());
    }

    @Test void recipientSnapshotRetainsImageAfterSourceDeletion() throws Exception {
        String url = upload();
        long source = save(url);
        long artwork = jdbc.queryForObject("SELECT id FROM artwork WHERE source_content_id = ?", Long.class, source);
        String owner = jdbc.queryForObject("SELECT owner_sub FROM link_content WHERE id = ?", String.class, source);
        var delivery = deliveries.create(owner, List.of(artwork), "snapshot@example.com", "",
            "Snapshot", null, null, 0, false);
        mvc.perform(delete("/api/contents/" + source).with(oidcLogin()).with(csrf())).andExpect(status().isOk());
        jdbc.update("UPDATE content_image SET created_at = ?", new Timestamp(System.currentTimeMillis() - 172800000));
        images.cleanup();
        var session = new MockHttpSession();
        images.grant(session, delivery.link(), delivery.recipient());
        mvc.perform(get(url).session(session)).andExpect(status().isOk());
        jdbc.update("UPDATE protected_link SET expires_at = ? WHERE id = ?",
            new Timestamp(System.currentTimeMillis() - 1000), delivery.link().getId());
        mvc.perform(get(url).session(session)).andExpect(status().isNotFound());
    }
}
