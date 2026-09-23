package com.toadzip.backend.ingest.collection.repository.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class LhAnnouncementDetailResponseParserTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private final LhAnnouncementDetailResponseParser parser = new LhAnnouncementDetailResponseParser();

    @Test
    @DisplayName("LH 공고 상세 dataset을 정해진 순서의 원천 데이터로 파싱한다")
    void parsesDetailSourcesInDatasetOrder() {
        var root = objectMapper.readTree("""
                [{"dsSbd":[{"LCC_NT_NM":"가 단지"}]},{"dsEtcInfo":[{"ETC_CTS":"안내"}]}]
                """);

        var sources = parser.parse("PAN-1", root);

        assertThat(sources).hasSize(2);
        assertThat(sources.get(0)).satisfies(source -> {
            assertThat(source.getSourceOrder()).isZero();
            assertThat(source.getPanId()).isEqualTo("PAN-1");
            assertThat(source.getDatasetType()).isEqualTo("ETC_INFO");
            assertThat(source.getEtcContents()).isEqualTo("안내");
        });
        assertThat(sources.get(1)).satisfies(source -> {
            assertThat(source.getSourceOrder()).isOne();
            assertThat(source.getDatasetType()).isEqualTo("COMPLEX");
            assertThat(source.getComplexName()).isEqualTo("가 단지");
        });
    }

    @Test
    @DisplayName("LH 공고 상세 dataset이 하나도 없으면 실패한다")
    void rejectsMissingDetailDataset() {
        var root = objectMapper.readTree("[{\"resHeader\":[{\"SS_CODE\":\"Y\"}]}]");

        assertThatThrownBy(() -> parser.parse("PAN-1", root))
                .isInstanceOf(ExternalDataRequestException.class)
                .hasMessage("LH 공고 상세 응답에 예상 dataset이 없습니다.");
    }

    @Test
    @DisplayName("LH 공고 상세 dataset이 빈 배열이면 정상 빈 결과로 처리한다")
    void parsesEmptyDetailDataset() {
        var root = objectMapper.readTree("[{\"dsEtcInfo\":[]}]");

        assertThat(parser.parse("PAN-1", root)).isEmpty();
    }

    @Test
    @DisplayName("존재하는 LH 공고 상세 dataset 중 하나라도 타입이 잘못되면 실패한다")
    void rejectsInvalidDetailDatasetType() {
        var root = objectMapper.readTree("[{\"dsEtcInfo\":[]},{\"dsSbd\":\"invalid\"}]");

        assertThatThrownBy(() -> parser.parse("PAN-1", root))
                .isInstanceOf(ExternalDataRequestException.class);
    }
}
