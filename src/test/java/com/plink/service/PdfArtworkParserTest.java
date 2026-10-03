package com.plink.service;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

class PdfArtworkParserTest {
    private final PdfArtworkParser parser = new PdfArtworkParser();

    @Test
    void doesNotTreatBlankLineBeforeTitleAsArtist() {
        assertThat(parser.parse("\n  \nNothing was entirely separated\n2026\nOil on linen\n150 x 130 cm\nOn Hold")).isEmpty();
    }

    @Test
    void sectionArtistChangesOnlyWithBiographyAndNeverCombinesPages() {
        var works = parser.parse("""
            김서울(b.1988)의 작품 소개
            \f
            Work No.1 (small ver.)
            2026
            Oil on
            canvas
            50 x 40 cm
            1,000,000 KRW
            \f
            김진희(b.1990)의 작품 소개
            \f
            Who is there…?
            2025
            Acrylic on canvas
            30 x 20 cm
            On hold
            \f
            Incomplete work
            2026
            Oil on canvas
            \f
            80 x 70 cm
            """);
        assertThat(works).hasSize(2);
        assertThat(works.get(0)).containsEntry("artist", "김서울")
            .containsEntry("medium", "Oil on canvas").containsEntry("price", "1,000,000 KRW");
        assertThat(works.get(1)).containsEntry("artist", "김진희")
            .containsEntry("title", "Who is there…?").containsEntry("description", "On hold")
            .containsEntry("price", "");
    }

    @Test
    void separatesReportedCatalogueWithoutAbsorbingBiographiesOrDroppingSameTitleWorks() throws Exception {
        String text;
        try (var stream = getClass().getResourceAsStream("/catalogues/arario-reported-text.txt")) {
            text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        var works = parser.parse(text);
        assertThat(works).hasSize(34);
        assertThat(works.stream().map(w -> w.get("artist")).distinct()).hasSize(11);
        assertThat(works).allSatisfy(w -> {
            assertThat(w.get("artist")).hasSize(3);
            assertThat(w.get("title").length()).isLessThan(30);
            assertThat(w.get("year")).matches("20\\d{2}");
            assertThat(w.get("medium")).isNotBlank();
            assertThat(w.get("price")).startsWith("KRW ");
            assertThat(w.get("description")).doesNotContain("태어났다", "개인전", "b.");
        });
        assertThat(works.get(0)).containsEntry("title", "산딸기 - 풍경 74")
            .containsEntry("height", "100").containsEntry("width", "70")
            .containsEntry("medium", "캔버스에 유채").containsEntry("price", "KRW 5,000,000");
        assertThat(works.stream().filter(w -> w.get("title").equals("홀리"))).hasSize(2);
        assertThat(works.stream().filter(w -> w.get("title").equals("상서"))).hasSize(2);
        assertThat(works.get(18)).containsEntry("description", "가변크기")
            .containsEntry("width", "").containsEntry("height", "");
        assertThat(works.get(25)).containsEntry("height", "100").containsEntry("width", "78")
            .containsEntry("description", "(프레임: 105 x 83 cm)\nEd. of 7 plus 1 A.P.");
        assertThat(works.get(33)).containsEntry("price", "KRW 750,000 (each)")
            .containsEntry("description", "(each)");
    }

    @Test
    void parsesLineCaptionsWithoutBlankLinesOrBiographyAndConvertsMillimetres() {
        var works = parser.parse("""
            Exhibition 2026
            Jane Artist
            Small Sculpture
            2024
            Bronze
            100 x 70 x 30 mm
            USD 1,200
            Jane Artist
            Another Work
            2025
            Oil on canvas
            20 × 15 inches
            """);
        assertThat(works).hasSize(2);
        assertThat(works.get(0)).containsEntry("artist", "Jane Artist")
            .containsEntry("title", "Small Sculpture").containsEntry("height", "10")
            .containsEntry("width", "7").containsEntry("depth", "3").containsEntry("unit", "cm")
            .containsEntry("price", "USD 1,200");
        assertThat(works.get(1)).containsEntry("unit", "inch").containsEntry("price", "");
    }

    @Test
    void acceptsLabeledFieldsWithoutYearOrPriceAndPreservesUncertainDimensions() {
        var works = parser.parse("""
            Artist: Jane Doe
            Title: Untitled
            Medium: Mixed media
            Dimensions: W 50 x H 80 cm
            Edition: 2/7
            
            작가: 홍길동
            작품명: 풍경
            제작연도: 2024–2026
            재료: 종이에 연필
            규격: 30 x 20 cm
            가격: 가격 문의
            """);
        assertThat(works).hasSize(2);
        assertThat(works.get(0)).containsEntry("artist", "Jane Doe")
            .containsEntry("year", "").containsEntry("price", "")
            .containsEntry("width", "").containsEntry("height", "")
            .containsEntry("description", "W 50 x H 80 cm\n2/7");
        assertThat(works.get(1)).containsEntry("year", "2024–2026")
            .containsEntry("price", "가격 문의").containsEntry("width", "20");
    }

    @Test
    void supportsInlineSeparatorAndDoesNotMergeLabeledFieldsAcrossPages() {
        assertThat(parser.parse("Jane Doe | Landscape 2026 Oil on canvas 80 x 50 cm USD 500"))
            .singleElement().satisfies(w -> assertThat(w).containsEntry("artist", "Jane Doe")
                .containsEntry("title", "Landscape").containsEntry("price", "USD 500"));
        assertThat(parser.parse("Artist: Jane Doe\n\f\nTitle: A different page")).isEmpty();
        assertThat(parser.parse("Jane Doe\nLandscape\n2026\nOil on canvas\n\f\n80 x 50 cm")).isEmpty();
        assertThat(parser.parse("Artist: Jane Doe\nTitle: Untitled\nYear:\nMedium: Oil"))
            .singleElement().satisfies(w -> assertThat(w).containsEntry("year", "")
                .containsEntry("medium", "Oil"));
    }

    @Test
    void ignoresIntroAndBiographyEvenWhenTheyContainYearsAndDimensions() {
        assertThat(parser.parse("""
            Fair 2026
            We present artists born in 1989.
            Their paintings measure 100 x 70 cm.

            임노식(b. 1989)은 2026년 개인전을 개최했다.
            Cover Image: 임노식, 풍경, 2026, 캔버스에 유채, 100 x 70 cm
            """)).isEmpty();
    }
}
