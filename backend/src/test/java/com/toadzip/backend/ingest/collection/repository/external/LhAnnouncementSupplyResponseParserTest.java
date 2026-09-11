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

        var sources = parser.parse("PAN-1", root);

        assertThat(sources).singleElement().satisfies(source -> {
            assertThat(source.getSourceOrder()).isZero();
            assertThat(source.getPanId()).isEqualTo("PAN-1");
            assertThat(source.getComplexLabel()).isEqualTo("가 단지");
            assertThat(source.getTypeName()).isEqualTo("46형");
        });
    }

    @Test
    @DisplayName("LH 공고 공급 dataset이 없으면 실패한다")
    void rejectsMissingSupplyDataset() {
        var root = objectMapper.readTree("[{\"resHeader\":[{\"SS_CODE\":\"Y\"}]}]");

        assertThatThrownBy(() -> parser.parse("PAN-1", root))
                .isInstanceOf(ExternalDataRequestException.class)
                .hasMessage("LH 공고 공급 응답에 예상 dataset이 없습니다.");
    }

    @Test
    @DisplayName("LH 공고 공급 dataset이 빈 배열이면 정상 빈 결과로 처리한다")
    void parsesEmptySupplyDataset() {
        var root = objectMapper.readTree("[{\"dsList01\":[]}]");

        assertThat(parser.parse("PAN-1", root)).isEmpty();
    }

    @Test
    @DisplayName("LH 공고 공급 dataset이 null 또는 스칼라이면 실패한다")
    void rejectsInvalidSupplyDatasetType() {
        var nullDataset = objectMapper.readTree("[{\"dsList01\":null}]");
        var scalarDataset = objectMapper.readTree("[{\"dsList01\":1}]");

        assertThatThrownBy(() -> parser.parse("PAN-1", nullDataset))
                .isInstanceOf(ExternalDataRequestException.class);
        assertThatThrownBy(() -> parser.parse("PAN-1", scalarDataset))
                .isInstanceOf(ExternalDataRequestException.class);
    }
}
