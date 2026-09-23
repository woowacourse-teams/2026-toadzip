package com.toadzip.backend.ingest.collection.repository.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class LhAnnouncementSupplyResponseParserTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private final LhAnnouncementSupplyResponseParser parser = new LhAnnouncementSupplyResponseParser();

    @Test
    @DisplayName("LH 공고 공급 응답을 원천 데이터로 파싱한다")
    void parsesSupplySources() {
        var root = objectMapper.readTree("""
                [{"dsList01":[{"SBD_LGO_NM":"가 단지","HTY_NNA":"46형"}]}]
                """);

        var sources = parser.parse("PAN-1", "062", root);

        assertThat(sources).singleElement().satisfies(source -> {
            assertThat(source.getSourceOrder()).isZero();
            assertThat(source.getPanId()).isEqualTo("PAN-1");
            assertThat(source.getComplexLabel()).isEqualTo("가 단지");
            assertThat(source.getTypeName()).isEqualTo("46형");
        });
    }

    @Test
    @DisplayName("060 공공임대의 dsList02 공급행과 유형별 필드를 읽는다")
    void parsesPublicRentalSupplySources() {
        var root = objectMapper.readTree("""
                [{"dsSch":[{"SPL_INF_TP_CD":"060"}]},
                 {"dsList01":[],"dsList02":[{"BZDT_NM":"가 단지","HTY_NM":"46형",
                 "RSDN_DDO_AR":"46.8","SPL_AR":"67.0","TOT_HSH_CNT":"100",
                 "SIL_HSH_CNT":"20","LS_GMY":"10000000","MM_RFE":"200000"}]}]
                """);

        var sources = parser.parse("PAN-1", "060", root);

        assertThat(sources).singleElement().satisfies(source -> {
            assertThat(source.getComplexLabel()).isEqualTo("가 단지");
            assertThat(source.getTypeName()).isEqualTo("46형");
            assertThat(source.getExclusiveArea()).isEqualTo("46.8");
            assertThat(source.getSupplyArea()).isEqualTo("67.0");
            assertThat(source.getTotalUnitCount()).isEqualTo("100");
            assertThat(source.getSuppliedUnitCount()).isEqualTo("20");
            assertThat(source.getDepositText()).isEqualTo("10000000");
            assertThat(source.getMonthlyRentText()).isEqualTo("200000");
        });
    }

    @Test
    @DisplayName("060 응답에 dsList02가 없고 dsList01만 있으면 수집에 실패한다")
    void rejectsWrongPublicRentalDataset() {
        var root = objectMapper.readTree("""
                [{"dsSch":[{"SPL_INF_TP_CD":"060"}]},{"dsList01":[{"SBD_LGO_NM":"가 단지","HTY_NNA":"46형"}]}]
                """);

        assertThatThrownBy(() -> parser.parse("PAN-1", "060", root))
                .isInstanceOf(ExternalDataRequestException.class);
    }

    @Test
    @DisplayName("060 공공임대 공급행의 단지명 또는 주택형이 없으면 수집에 실패한다")
    void rejectsUnidentifiablePublicRentalSupplyRow() {
        var root = objectMapper.readTree("""
                [{"dsList02":[{"SBD_LGO_NM":"잘못된 필드","HTY_NNA":"46형"}]}]
                """);

        assertThatThrownBy(() -> parser.parse("PAN-1", "060", root))
                .isInstanceOf(ExternalDataRequestException.class);
    }

    @Test
    @DisplayName("LH 공고 공급 dataset이 없으면 실패한다")
    void rejectsMissingSupplyDataset() {
        var root = objectMapper.readTree("[{\"resHeader\":[{\"SS_CODE\":\"Y\"}]}]");

        assertThatThrownBy(() -> parser.parse("PAN-1", "062", root))
                .isInstanceOf(ExternalDataRequestException.class)
                .hasMessage("LH 공고 공급 응답에 예상 dataset이 없습니다.");
    }

    @Test
    @DisplayName("LH 공고 공급 dataset이 빈 배열이면 정상 빈 결과로 처리한다")
    void parsesEmptySupplyDataset() {
        var root = objectMapper.readTree("[{\"dsList01\":[]}]");

        assertThat(parser.parse("PAN-1", "062", root)).isEmpty();
    }

    @Test
    @DisplayName("LH 공고 공급 dataset이 null 또는 스칼라이면 실패한다")
    void rejectsInvalidSupplyDatasetType() {
        var nullDataset = objectMapper.readTree("[{\"dsList01\":null}]");
        var scalarDataset = objectMapper.readTree("[{\"dsList01\":1}]");

        assertThatThrownBy(() -> parser.parse("PAN-1", "062", nullDataset))
                .isInstanceOf(ExternalDataRequestException.class);
        assertThatThrownBy(() -> parser.parse("PAN-1", "062", scalarDataset))
                .isInstanceOf(ExternalDataRequestException.class);
    }
}
