package com.toadzip.backend.ingest.collection.repository.external;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.ingest.collection.dto.ExternalDataPage;
import com.toadzip.backend.ingest.collection.dto.ExternalDataResponse;
import com.toadzip.backend.ingest.collection.dto.LhCatalogSourceItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class LhLeaseCatalogResponseParserTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private final LhLeaseCatalogResponseParser parser = new LhLeaseCatalogResponseParser();

    @Test
    @DisplayName("LH 임대 카탈로그 응답을 파싱하고 마지막 페이지를 판단한다")
    void parsesCatalogAndCompletesByPageSize() {
        ExternalDataPage<LhCatalogSourceItem> page = parser.parse(response("""
                [{"resHeader":[{"SS_CODE":"Y"}]},{"dsList":[{"ARA_NM":"서울","SBD_LGO_NM":"가 단지"}]}]
                """));

        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.areaName()).isEqualTo("서울");
            assertThat(item.complexLabel()).isEqualTo("가 단지");
        });
        assertThat(page.completesCollection(1, 2)).isTrue();
    }

    private ExternalDataResponse response(String payload) {
        return new ExternalDataResponse(payload, objectMapper.readTree(payload));
    }
}
