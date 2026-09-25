package com.toadzip.backend.ingest.collection.repository.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.ingest.collection.domain.LhCatalogSourceSnapshot;
import com.toadzip.backend.ingest.collection.dto.ExternalDataPage;
import com.toadzip.backend.ingest.collection.dto.ExternalDataResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class LhLeaseCatalogResponseParserTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private final LhLeaseCatalogResponseParser parser = new LhLeaseCatalogResponseParser();

    @Test
    @DisplayName("LH 임대 카탈로그 응답을 파싱하고 마지막 페이지를 판단한다")
    void parsesCatalogAndCompletesByPageSize() {
        ExternalDataPage<LhCatalogSourceSnapshot> page = parser.parse(response("""
                [{"resHeader":[{"SS_CODE":"Y"}]},{"dsList":[{"ARA_NM":"서울",
                 "AIS_TP_CD_NM":"행복주택","SBD_LGO_NM":"가 단지"}]}]
                """));

        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.areaName()).isEqualTo("서울");
            assertThat(item.supplyTypeName()).isEqualTo("행복주택");
            assertThat(item.complexLabel()).isEqualTo("가 단지");
            assertThat(item.depositText()).isNull();
        });
        assertThat(page.completesCollection(1, 2)).isTrue();
    }

    @Test
    @DisplayName("LH 임대 카탈로그 dataset이 빈 배열이면 정상 빈 페이지로 처리한다")
    void parsesEmptyCatalogDataset() {
        ExternalDataPage<LhCatalogSourceSnapshot> page = parser.parse(response("""
                [{"resHeader":[{"SS_CODE":"Y"}]},{"dsList":[]}]
                """));

        assertThat(page.items()).isEmpty();
    }

    @Test
    @DisplayName("LH 임대 카탈로그 dataset이 없거나 타입이 잘못되면 실패한다")
    void rejectsMissingOrInvalidCatalogDataset() {
        ExternalDataResponse missing = response("[{\"resHeader\":[{\"SS_CODE\":\"Y\"}]}]");
        ExternalDataResponse scalar = response("[{\"dsList\":\"invalid\"}]");

        assertThatThrownBy(() -> parser.parse(missing))
                .isInstanceOf(ExternalDataRequestException.class)
                .hasMessage("LH 임대 카탈로그 응답에 예상 dataset이 없습니다.");
        assertThatThrownBy(() -> parser.parse(scalar))
                .isInstanceOf(ExternalDataRequestException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"ARA_NM\":\"서울\",\"AIS_TP_CD_NM\":\"행복주택\"}",
            "{\"ARA_NM\":\"서울\",\"SBD_LGO_NM\":\"가 단지\"}",
            "{\"AIS_TP_CD_NM\":\"행복주택\",\"SBD_LGO_NM\":\"가 단지\"}",
            "{\"AREA_RENAMED\":\"서울\",\"AIS_TP_CD_NM\":\"행복주택\",\"SBD_LGO_NM\":\"가 단지\"}"
    })
    void 식별_정보가_없는_카탈로그_행을_거절한다(String row) {
        assertThatThrownBy(() -> parser.parse(response("[{\"dsList\":[" + row + "]}]")))
                .isInstanceOf(ExternalDataRequestException.class);
    }

    private ExternalDataResponse response(String payload) {
        return new ExternalDataResponse(payload, objectMapper.readTree(payload));
    }
}
