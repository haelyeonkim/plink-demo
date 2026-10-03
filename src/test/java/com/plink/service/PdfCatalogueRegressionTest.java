package com.plink.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

/** Real uploaded catalogues, with image resources removed to keep fixtures small. */
class PdfCatalogueRegressionTest {
    @Test
    void extractsPageAndLineNumbersBeforeInterpretingCaptions() throws Exception {
        try (var stream = getClass().getResourceAsStream("/catalogues/test_file_1.pdf");
                var pdf = org.apache.pdfbox.Loader.loadPDF(stream.readAllBytes())) {
            var document = PdfTextDocument.extract(pdf);
            assertThat(document.pages()).hasSize(78);
            assertThat(document.pages().get(4).text()).isBlank();
            var page = document.pages().get(64);
            assertThat(page.number()).isEqualTo(65);
            assertThat(page.lines().stream().map(line -> line.text().strip()).filter(s -> !s.isEmpty()))
                .containsExactly("Nothing was entirely separated", "2026", "Oil on linen", "150 x 130 cm", "On Hold");
            for (int i = 0; i < page.lines().size(); i++) {
                assertThat(page.lines().get(i).number()).isEqualTo(i + 1);
            }
        }
    }

    @Test
    void importsAllThisWeekendRoomWorksWithTheirSectionArtist() throws Exception {
        var works = importFixture("test_file_1.pdf");
        assertThat(works).hasSize(34);
        assertThat(works.stream().map(w -> w.get("title"))).containsExactly(
            "Scalar and Vector No.21", "Scalar and Vector No.20", "Scalar and Vector No.19",
            "Scalar and Vector No.18", "Scalar and Vector No.13(small ver.)",
            "Scalar and Vector No.14(small ver.)", "Scalar and Vector No.15(small ver.)",
            "Cloudy as Ever", "…till Evening", "Cloudy Morning Today", "We’re on the Same Page…?",
            "Me, Bacchus", "…rather than Ladder", "My Tribulation", "What‘s on My Shelf", "Eye",
            "Faces of Today - Two of Us", "When I See You", "Behind My Back", "Holding a Cigarette",
            "Artificial Body", "In the Kitchen", "The Good Things", "The Keeper’s Hall", "On the Set",
            "South Metope VII", "난초 사냥꾼 Orchid Hunter",
            "검은 화병 III - 난초 The Black Glazed Vase III - Orchids", "Nothing was entirely separated",
            "What we thought we knew", "The order of invisible things", "Several ways of being",
            "You do something to me", "A temporary constellation");
        assertArtists(works, 0, 7, "김서울");
        assertArtists(works, 7, 22, "김진희");
        assertArtists(works, 22, 26, "박지나");
        assertArtists(works, 26, 28, "최지원");
        assertArtists(works, 28, 34, "벤조");
        assertThat(works.get(0)).containsEntry("year", "2026").containsEntry("height", "172")
            .containsEntry("width", "172").containsEntry("price", "34,000,000 KRW");
        assertThat(works.get(4)).containsEntry("height", "43.3").containsEntry("width", "34")
            .containsEntry("medium", "Hard maple wood, mineral watercolor, cold wax, watercolor ground, oil on universal primed linen canvas(fine texture)");
        assertThat(works.get(28)).containsEntry("artist", "벤조").containsEntry("year", "2026")
            .containsEntry("medium", "Oil on linen").containsEntry("height", "150")
            .containsEntry("width", "130").containsEntry("price", "").containsEntry("description", "On Hold");
        assertThat(works.get(29)).containsEntry("price", "21,600,000 KRW");
        assertThat(works.get(32)).containsEntry("year", "2023");
        assertThat(works).allSatisfy(work -> assertThat(work.get("image")).isEmpty());
    }

    @Test
    void importsArarioWithoutCoverBiographiesOrDuplicateTitleLoss() throws Exception {
        var works = importFixture("test_file_2.pdf");
        assertThat(works).hasSize(34);
        assertThat(works.stream().map(w -> w.get("artist")).distinct()).hasSize(11);
        assertThat(works.stream().map(w -> w.get("title"))).containsExactly(
            "산딸기 - 풍경 74", "여주 - 풍경 62", "들꽃 - 풍경 58", "느티나무 - 풍경 71", "들꽃 - 풍경 69", "들꽃 - 풍경 70",
            "오르는", "콤플렉스", "홀리", "홀리", "더 그레이트 챕북 3", "세이렌", "메두사", "이브", "상서", "상서",
            "아나토미 1", "눈깔사탕", "하비의 혈액순환설 8", "하비의 혈액순환설 3", "미르구름", "그림의 문",
            "이방의 달", "끝없는 시험대", "각자의 관심 사이", "기다리는 가지", "자라는 돌", "봉인된 정원",
            "안뜰에 머문 빛", "사라지는 사물", "화석", "뷰", "사라지는 장소", "변방의 생명");
        assertThat(works.get(0)).containsEntry("artist", "임노식").containsEntry("height", "100")
            .containsEntry("width", "70").containsEntry("price", "KRW 5,000,000");
        assertThat(works.get(18)).containsEntry("description", "가변크기").containsEntry("width", "");
        assertThat(works.get(25)).containsEntry("height", "100").containsEntry("width", "78")
            .containsEntry("description", "(프레임: 105 x 83 cm)\nEd. of 7 plus 1 A.P.");
        assertThat(works.get(33)).containsEntry("artist", "임수범").containsEntry("description", "(each)")
            .containsEntry("price", "KRW 750,000 (each)");
        assertThat(works).allSatisfy(work -> {
            assertThat(work.get("artist")).hasSize(3);
            assertThat(work.get("price")).startsWith("KRW ");
        });
    }

    private void assertArtists(List<Map<String, String>> works, int start, int end, String artist) {
        assertThat(works.subList(start, end)).allSatisfy(work -> assertThat(work).containsEntry("artist", artist));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> importFixture(String name) throws Exception {
        try (var stream = getClass().getResourceAsStream("/catalogues/" + name)) {
            assertThat(stream).as("PDF fixture %s", name).isNotNull();
            var file = new MockMultipartFile("file", name, "application/pdf", stream.readAllBytes());
            var result = new ContentImportService().fromPdf(file);
            assertThat(result).containsEntry("sourceType", "PDF");
            return (List<Map<String, String>>) ((Map<?, ?>) result.get("body")).get("artworks");
        }
    }
}
