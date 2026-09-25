package com.toadzip.backend.ingest.collection.repository.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class LhAnnouncementCatalogResponseParserTest {

    private final LhAnnouncementCatalogResponseParser parser = new LhAnnouncementCatalogResponseParser();

    @Test
    void 실응답의_조회_조건과_목록_내용을_읽는다() throws IOException {
        var page = parser.parse(response(), 1, 500);

        assertThat(page.totalCount()).isEqualTo(2);
        assertThat(page.entries()).hasSize(2);
        assertThat(page.entries().getFirst().snapshot().panId()).isNotBlank();
        assertThat(page.entries().getFirst().snapshot().supplyInfoTypeCode()).isNotBlank();
        assertThat(page.startDate()).isEqualTo("20260725");
    }

    @Test
    void 공고_식별자가_없으면_부분_목록으로_성공하지_않는다() throws IOException {
        JsonNode root = response();
        ((ObjectNode) root.get(1).path("dsList").get(0)).remove("PAN_ID");

        assertThatThrownBy(() -> parser.parse(root, 1, 500))
                .isInstanceOf(ExternalDataRequestException.class).hasMessageContaining("PAN_ID");
    }

    @Test
    void 요청과_다른_페이지를_거절한다() throws IOException {
        assertThatThrownBy(() -> parser.parse(response(), 2, 500))
                .isInstanceOf(ExternalDataRequestException.class).hasMessageContaining("다른 페이지");
    }

    @Test
    void 행마다_다른_전체건수를_거절한다() throws IOException {
        JsonNode root = response();
        ((ObjectNode) root.get(1).path("dsList").get(1)).put("ALL_CNT", "3");

        assertThatThrownBy(() -> parser.parse(root, 1, 500))
                .isInstanceOf(ExternalDataRequestException.class).hasMessageContaining("건수");
    }

    @Test
    void 목록_누락을_정상_빈_결과로_취급하지_않는다() throws IOException {
        JsonNode root = response();
        ((ObjectNode) root.get(1)).remove("dsList");

        assertThatThrownBy(() -> parser.parse(root, 1, 500)).isInstanceOf(ExternalDataRequestException.class);
    }

    @Test
    void 첫_페이지의_명시적인_빈_목록만_허용한다() throws IOException {
        JsonNode root = response();
        ((ObjectNode) root.get(1)).putArray("dsList");

        assertThat(parser.parse(root, 1, 500).totalCount()).isZero();
    }

    private JsonNode response() throws IOException {
        try (var input = getClass().getResourceAsStream("/ingest/lh-announcement-catalog.json")) {
            return JsonMapper.builder().build().readTree(input);
        }
    }
}
