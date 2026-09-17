package com.plink.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContentImportServiceTest {
    private final ContentImportService importer = new ContentImportService();

    @Test
    void parsesArtworkFromJsonLdAsDraft() {
        String html = """
            <html><head>
              <meta property="og:title" content="Autumn Viewing Room">
              <meta property="og:description" content="Selected works">
              <script type="application/ld+json">
                {"@type":"VisualArtwork","name":"Blue Field","creator":{"name":"Jane Artist"},
                 "image":"https://example.com/blue.jpg","description":"Oil on canvas, 2026, 100 x 120 cm"}
              </script>
            </head></html>
            """;

        Map<String, Object> result = importer.fromHtml(html, "https://example.com/viewing-room");
        Map<String, Object> body = castMap(result.get("body"));
        List<Map<String, String>> works = castWorks(body.get("artworks"));

        assertThat(result.get("title")).isEqualTo("Autumn Viewing Room");
        assertThat(body.get("intro")).isEqualTo("Selected works");
        assertThat(works).singleElement().satisfies(work -> {
            assertThat(work.get("artist")).isEqualTo("Jane Artist");
            assertThat(work.get("title")).isEqualTo("Blue Field");
            assertThat(work.get("image")).isEqualTo("https://example.com/blue.jpg");
        });
    }

    @Test
    void blocksLoopbackUrlBeforeRequestingIt() {
        assertThatThrownBy(() -> importer.fromUrl("http://127.0.0.1/admin"))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void parsesArtlogicEmbeddedCatalogue() {
        String html = """
            <html><head><title>Private View</title></head><body><script>
              window.pv_data = {"private_view_data":{"rows":[{
                "artist":"Stefan Artist","title":"Works on paper","year":"2026",
                "width":"21.40","height":"27.90","img_url_medium":"https://cdn.example/work.jpg",
                "display_price":"USD 5,000","_details_multiline":"<div class='medium'>Oil on paper</div>"
              }]}};
            </script></body></html>
            """;

        Map<String, Object> result = importer.fromHtml(html, "https://privateviews.artlogic.net/example");
        List<Map<String, String>> works = castWorks(castMap(result.get("body")).get("artworks"));

        assertThat(works).singleElement().satisfies(work -> {
            assertThat(work.get("artist")).isEqualTo("Stefan Artist");
            assertThat(work.get("medium")).isEqualTo("Oil on paper");
            assertThat(work.get("price")).isEqualTo("USD 5,000");
            assertThat(work.get("width")).isEqualTo("21.40");
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> castWorks(Object value) {
        return (List<Map<String, String>>) value;
    }
}
