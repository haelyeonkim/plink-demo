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

    @Test
    void parsesArtlogicCatalogueWhenRowsAreAtRoot() {
        String html = """
            <html><head><title>KIAF 2026</title></head><body><script>
              window.pv_data = {"private_view_data":{"contentAbove":"Fair preview"},"rows":[{
                "artist":"Etsu Egami","title":"The little Mermaid","year":"2024",
                "width":"62.5","height":"79.5","img_url_medium":"https://cdn.example/mermaid.jpg",
                "display_price":"USD 15,515","_details_multiline":"<div class='medium'>Oil on canvas</div>"
              }]};
            </script></body></html>
            """;

        Map<String, Object> result = importer.fromHtml(html, "https://privateviews.artlogic.net/example");
        List<Map<String, String>> works = castWorks(castMap(result.get("body")).get("artworks"));

        assertThat(works).singleElement().satisfies(work -> {
            assertThat(work.get("artist")).isEqualTo("Etsu Egami");
            assertThat(work.get("title")).isEqualTo("The little Mermaid");
            assertThat(work.get("year")).isEqualTo("2024");
            assertThat(work.get("medium")).isEqualTo("Oil on canvas");
        });
    }

    @Test
    void extractsRealPdfBytesIntoSeparateFieldsAndWarnsAboutDimensionOrder() throws Exception {
        var result = importer.fromPdf(pdfWithLines("Jane Artist", "Landscape", "2026",
            "Oil on canvas", "100 x 70 cm", "USD 500"));
        var works = castWorks(castMap(result.get("body")).get("artworks"));
        assertThat(works).singleElement().satisfies(work -> assertThat(work)
            .containsEntry("artist", "Jane Artist").containsEntry("title", "Landscape")
            .containsEntry("medium", "Oil on canvas").containsEntry("year", "2026")
            .containsEntry("width", "70").containsEntry("height", "100")
            .containsEntry("price", "USD 500"));
        assertThat(result.get("warnings").toString()).contains("세로 × 가로");
    }

    @Test
    void rejectsPdfWithoutTextInsteadOfInventingArtwork() throws Exception {
        var file = pdfWithLines();
        assertThatThrownBy(() -> importer.fromPdf(file))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void rejectsEmptyWrongExtensionAndCorruptUploads() {
        assertThatThrownBy(() -> importer.fromPdf(null))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        for (String name : List.of("wrong.txt", "broken.pdf")) {
            var file = new org.springframework.mock.web.MockMultipartFile("file", name,
                "application/pdf", new byte[]{1, 2, 3});
            assertThatThrownBy(() -> importer.fromPdf(file))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                    e -> assertThat(e.getStatusCode()).isEqualTo(name.endsWith(".pdf")
                        ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.BAD_REQUEST));
        }
    }

    @Test
    void acceptsPdfAtHundredMegabyteLimit() throws Exception {
        var original = pdfWithLines("Artist: Test Artist", "Title: Test Work", "Year: 2026", "Medium: Oil on canvas", "Size: 30 x 40 cm");
        var file = new org.springframework.mock.web.MockMultipartFile("file", "large.pdf",
                "application/pdf", original.getBytes()) {
            @Override public long getSize() { return 100L * 1024 * 1024; }
        };
        assertThat(importer.fromPdf(file)).containsKey("body");
    }

    @Test
    void refusesOversizedPdfBeforeReadingBytes() {
        var file = new org.springframework.mock.web.MockMultipartFile("file", "large.pdf",
                "application/pdf", new byte[]{1}) {
            @Override public long getSize() { return 100L * 1024 * 1024 + 1; }
        };
        assertThatThrownBy(() -> importer.fromPdf(file))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
    }

    @Test
    void rejectsTextPdfWithoutRecognizableCaptions() throws Exception {
        var file = pdfWithLines("Exhibition 2026", "A catalogue introduction without artworks.");
        assertThatThrownBy(() -> importer.fromPdf(file))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    private org.springframework.mock.web.MockMultipartFile pdfWithLines(String... lines) throws Exception {
        try (var pdf = new org.apache.pdfbox.pdmodel.PDDocument();
                var bytes = new java.io.ByteArrayOutputStream()) {
            var page = new org.apache.pdfbox.pdmodel.PDPage();
            pdf.addPage(page);
            try (var content = new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf, page)) {
                content.beginText();
                content.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                    org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 750);
                content.setLeading(18);
                for (String line : lines) { content.showText(line); content.newLine(); }
                content.endText();
            }
            pdf.save(bytes);
            return new org.springframework.mock.web.MockMultipartFile("file", "catalogue.pdf",
                "application/pdf", bytes.toByteArray());
        }
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
