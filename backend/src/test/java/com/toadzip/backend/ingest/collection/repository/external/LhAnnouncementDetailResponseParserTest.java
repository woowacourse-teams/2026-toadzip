package com.toadzip.backend.ingest.collection.repository.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class LhAnnouncementDetailResponseParserTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private final LhAnnouncementDetailResponseParser parser = new LhAnnouncementDetailResponseParser();

    @Test
    void 공공임대_실응답의_단지명_주소_세대수를_보존한다() throws Exception {
        try (var input = getClass().getResourceAsStream("/ingest/lh/detail-060-excerpt.json")) {
            var sources = parser.parse("0000061177", objectMapper.readTree(input));

            assertThat(sources).filteredOn(source -> "COMPLEX".equals(source.getDatasetType()))
                    .singleElement().satisfies(source -> {
                        assertThat(source.getComplexName()).isEqualTo("화성동탄2A-40(공임리츠) A-40");
                        assertThat(source.getAddress()).isEqualTo("경기도 화성시 동탄대로12길 71");
                        assertThat(source.getTotalUnitCount()).isEqualTo("652");
                    });
        }
    }

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

    @ParameterizedTest
    @ValueSource(strings = {
            "[{\"dsEtcInfo\":[{}]}]",
            "[{\"dsSbd\":[{}]}]",
            "[{\"dsSplScdl\":[{}]}]",
            "[{\"dsCtrtPlc\":[{}]}]",
            "[{\"dsAhflInfo\":[{}]}]",
            "[{\"dsSbdAhfl\":[{}]}]",
            "[{\"dsSbd\":[{\"RENAMED_COMPLEX_NAME\":\"가 단지\"}]}]"
    })
    void 내용이_없는_상세_행은_거절한다(String response) {
        assertThatThrownBy(() -> parser.parse("PAN-1", objectMapper.readTree(response)))
                .isInstanceOf(ExternalDataRequestException.class);
    }

    @Test
    void 정상_상세_행의_선택_필드_누락과_빈_dataset은_허용한다() {
        var root = objectMapper.readTree("""
                [{"dsSbd":[{"LCC_NT_NM":"가 단지"}],"dsSplScdl":[]}]
                """);

        assertThat(parser.parse("PAN-1", root)).singleElement()
                .extracting(source -> source.getComplexName()).isEqualTo("가 단지");
    }
}
