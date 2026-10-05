package com.toadzip.backend.ingest.collection.repository.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.ingest.collection.lh.detail.repository.LhDetailResponseParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class LhAnnouncementDetailResponseParserTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private final LhDetailResponseParser parser = new LhDetailResponseParser();

    @Test
    void 공공임대_실응답의_단지명_주소_세대수를_보존한다() throws Exception {
        try (var input = getClass().getResourceAsStream("/ingest/lh/detail-060-excerpt.json")) {
            var sources = parser.parse(objectMapper.readTree(input));

            assertThat(sources).filteredOn(source -> "COMPLEX".equals(source.datasetType()))
                    .singleElement().satisfies(source -> {
                        assertThat(source.complexName()).isEqualTo("화성동탄2A-40(공임리츠) A-40");
                        assertThat(source.address()).isEqualTo("경기도 화성시 동탄대로12길 71");
                        assertThat(source.totalUnitCount()).isEqualTo("652");
                    });
        }
    }

    @Test
    @DisplayName("LH 공고 상세 dataset을 정해진 순서의 원천 데이터로 파싱한다")
    void parsesDetailSourcesInDatasetOrder() {
        var root = objectMapper.readTree("""
                [{"dsSbd":[{"LCC_NT_NM":"가 단지"}]},{"dsEtcInfo":[{"ETC_CTS":"안내"}]}]
                """);

        var sources = parser.parse(root);

        assertThat(sources).hasSize(2);
        assertThat(sources.get(0)).satisfies(source -> {
            assertThat(source.datasetType()).isEqualTo("ETC_INFO");
            assertThat(source.etcContents()).isEqualTo("안내");
        });
        assertThat(sources.get(1)).satisfies(source -> {
            assertThat(source.datasetType()).isEqualTo("COMPLEX");
            assertThat(source.complexName()).isEqualTo("가 단지");
        });
    }

    @Test
    void 공공임대_실응답의_기타사항과_공고내용을_정정사유로_오인하지_않고_보존한다() {
        var root = objectMapper.readTree("""
                [{"dsEtcInfo":[{"ETC_FCTS":"기타 안내","PAN_DTL_CTS":"공고문 11쪽의 계약 포기 안내 변경"}]}]
                """);

        assertThat(parser.parse(root)).singleElement().satisfies(source -> {
            assertThat(source.datasetType()).isEqualTo("ETC_INFO");
            assertThat(source.etcContents()).isEqualTo("기타 안내\n공고문 11쪽의 계약 포기 안내 변경");
            assertThat(source.correctionReason()).isNull();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"ETC_CTS\":\"\",\"CRC_RSN\":\"\"}",
            "{\"ETC_FCTS\":\" \",\"PAN_DTL_CTS\":\"\"}"
    })
    void 확인된_빈_선택_안내_행은_생략하고_유효한_단지_원천을_보존한다(String emptyEtcInfo) {
        var root = objectMapper.readTree(
                "[{\"dsEtcInfo\":[" + emptyEtcInfo + "],\"dsSbd\":[{\"LCC_NT_NM\":\"가 단지\"}]}]"
        );

        assertThat(parser.parse(root)).singleElement().satisfies(source -> {
            assertThat(source.complexName()).isEqualTo("가 단지");
        });
    }

    @Test
    void 빈_선택_안내에_알_수_없는_필드가_추가되면_조용히_생략하지_않는다() {
        var root = objectMapper.readTree("""
                [{"dsEtcInfo":[{"ETC_CTS":"","CRC_RSN":"","RENAMED_CONTENT":"안내"}]}]
                """);

        assertThatThrownBy(() -> parser.parse(root))
                .isInstanceOf(ExternalDataRequestException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"CRC_RSN\":{},\"ETC_CTS\":[]}",
            "{\"ETC_FCTS\":[],\"PAN_DTL_CTS\":{}}"
    })
    void 선택_안내의_객체나_배열_값은_빈_문자열로_오인하지_않는다(String invalidEtcInfo) {
        var root = objectMapper.readTree("[{\"dsEtcInfo\":[" + invalidEtcInfo + "]}]");

        assertThatThrownBy(() -> parser.parse(root))
                .isInstanceOf(ExternalDataRequestException.class);
    }

    @Test
    void 행복주택_실응답의_빈_접수처_안내는_생략하고_일정과_첨부를_보존한다() {
        var root = objectMapper.readTree("""
                [{"dsCtrtPlc":[{"CTRT_PLC_DTL_ADR":"","SIL_OFC_GUD_FCTS":"","CTRT_PLC_ADR":"",
                  "TSK_ST_DTTM":"","TSK_ED_DTTM":"","SIL_OFC_TLNO":""}],
                  "dsSplScdl":[{"ACP_DTTM":"2026.09.30 ~ 2026.10.02"}],
                  "dsAhflInfo":[{"CMN_AHFL_NM":"공고문.pdf"}]}]
                """);

        var sources = parser.parse(root);

        assertThat(sources).extracting(source -> source.datasetType())
                .containsExactly("SCHEDULE", "ANNOUNCEMENT_FILE");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"CTRT_PLC_DTL_ADR\":\"\",\"SIL_OFC_GUD_FCTS\":\"\",\"CTRT_PLC_ADR\":\"\","
                    + "\"TSK_ST_DTTM\":\"\",\"TSK_ED_DTTM\":\"\",\"SIL_OFC_TLNO\":\"\",\"NEW_FIELD\":\"안내\"}",
            "{\"CTRT_PLC_DTL_ADR\":\"\",\"SIL_OFC_GUD_FCTS\":\"\",\"CTRT_PLC_ADR\":\"\","
                    + "\"TSK_ST_DTTM\":\"\",\"TSK_ED_DTTM\":\"\",\"SIL_OFC_TLNO\":[]}"
    })
    void 접수처의_알_수_없는_필드나_구조_오류는_빈_안내로_생략하지_않는다(String invalidReception) {
        var root = objectMapper.readTree("[{\"dsCtrtPlc\":[" + invalidReception + "]}]");

        assertThatThrownBy(() -> parser.parse(root))
                .isInstanceOf(ExternalDataRequestException.class);
    }

    @Test
    @DisplayName("LH 공고 상세 dataset이 하나도 없으면 실패한다")
    void rejectsMissingDetailDataset() {
        var root = objectMapper.readTree("[{\"resHeader\":[{\"SS_CODE\":\"Y\"}]}]");

        assertThatThrownBy(() -> parser.parse(root))
                .isInstanceOf(ExternalDataRequestException.class)
                .hasMessage("LH 공고 상세 응답에 예상 dataset이 없습니다.");
    }

    @Test
    @DisplayName("LH 공고 상세 dataset이 빈 배열이면 정상 빈 결과로 처리한다")
    void parsesEmptyDetailDataset() {
        var root = objectMapper.readTree("[{\"dsEtcInfo\":[]}]");

        assertThat(parser.parse(root)).isEmpty();
    }

    @Test
    @DisplayName("존재하는 LH 공고 상세 dataset 중 하나라도 타입이 잘못되면 실패한다")
    void rejectsInvalidDetailDatasetType() {
        var root = objectMapper.readTree("[{\"dsEtcInfo\":[]},{\"dsSbd\":\"invalid\"}]");

        assertThatThrownBy(() -> parser.parse(root))
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
        assertThatThrownBy(() -> parser.parse(objectMapper.readTree(response)))
                .isInstanceOf(ExternalDataRequestException.class);
    }

    @Test
    void 정상_상세_행의_선택_필드_누락과_빈_dataset은_허용한다() {
        var root = objectMapper.readTree("""
                [{"dsSbd":[{"LCC_NT_NM":"가 단지"}],"dsSplScdl":[]}]
                """);

        assertThat(parser.parse(root)).singleElement()
                .extracting(source -> source.complexName()).isEqualTo("가 단지");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"ETC_CTS\":\"안내\"}"})
    void 앞선_행의_내용과_관계없이_마지막_dataset의_구조_오류를_보고한다(String firstRow) {
        var root = objectMapper.readTree(
                "[{\"dsEtcInfo\":[" + firstRow + "]},{\"dsSbdAhfl\":[42]}]"
        );

        assertThatThrownBy(() -> parser.parse(root))
                .isInstanceOf(ExternalDataRequestException.class)
                .hasMessage("LH 응답의 dsSbdAhfl dataset 구조가 올바르지 않습니다.");
    }
}
