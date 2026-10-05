package com.toadzip.backend.ingest.collection.lh.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.ingest.collection.lh.detail.domain.LhAnnouncementDetailSourceSnapshot;
import com.toadzip.backend.ingest.collection.lh.detail.repository.LhDetailResponseParser;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySourceSnapshot;
import com.toadzip.backend.ingest.collection.lh.supply.repository.LhSupplyResponseParser;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class LhResponseParserTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private final LhDetailResponseParser detail = new LhDetailResponseParser();

    @Test
    void preservesPublicRentalSupplyFieldNamesAndRawValues() {
        var root = mapper.readTree("""
                [{"dsList01":[],"dsList02":[{"BZDT_NM":" 단지 ","HTY_NM":" 59형 ",
                  "RSDN_DDO_AR":"59.000000000","SPL_AR":"070","TOT_HSH_CNT":"0100",
                  "SIL_HSH_CNT":"020","LS_GMY":" 12,345 ","MM_RFE":" 00300 "}]}]
                """);

        assertThat(new LhSupplyResponseParser().parse("060", root))
                .containsExactly(new LhAnnouncementSupplySourceSnapshot(
                        " 단지 ", " 59형 ", "59.000000000", "070", "0100", "020", " 12,345 ", " 00300 "));
    }

    @Test
    void preservesSixDetailDatasetsAndAllScheduleFields() {
        var root = mapper.readTree("""
                [{"dsEtcInfo":[{"CRC_RSN":" 정정 ","ETC_FCTS":" 기타 ","PAN_DTL_CTS":" 내용 "}],
                  "dsSbd":[{"BZDT_NM":" 단지 ","LCT_ARA_ADR":" 주소 ","LCT_ARA_DTL_ADR":" 상세 ",
                    "SUM_TOT_HSH_CNT":"0100","HTN_FMLA_DS_CD_NM":" 난방 ","MIN_MAX_RSDN_DDO_AR":" 59.000 ",
                    "MVIN_XPC_YM":"202701","SPL_INF_GUD_FCTS":" 안내 "}],
                  "dsSplScdl":[{"SBD_LGO_NM":" 단지 ","ACP_DTTM":" 기간 ","SBSC_ACP_ST_DT":"20261001",
                    "SBSC_ACP_CLSG_DT":"20261002","PZWR_ANC_DT":"20261003","PPR_SBM_OPE_ANC_DT":"20261004",
                    "PZWR_PPR_SBM_ST_DT":"20261005","PZWR_PPR_SBM_ED_DT":"20261006",
                    "CTRT_ST_DT":"20261007","CTRT_ED_DT":"20261008"}],
                  "dsCtrtPlc":[{"CTRT_PLC_ADR":" 접수 주소 ","CTRT_PLC_DTL_ADR":" 상세 주소 ",
                    "TSK_ST_DTTM":" 시작 ","TSK_ED_DTTM":" 종료 ","SIL_OFC_TLNO":" 전화 ","SIL_OFC_GUD_FCTS":" 안내 "}],
                  "dsAhflInfo":[{"SL_PAN_AHFL_DS_CD_NM":" 종류 ","CMN_AHFL_NM":" 파일 ","AHFL_URL":" 파일 URL "}],
                  "dsSbdAhfl":[{"LS_SPL_INF_UPL_FL_DS_CD_NM":" 사진 ","CMN_AHFL_NM":" 이름 ",
                    "AHFL_URL":" 사진 URL ","BZDT_NM":" 단지 "}]}]
                """);

        List<LhAnnouncementDetailSourceSnapshot> rows = detail.parse(root);

        assertThat(rows).extracting(LhAnnouncementDetailSourceSnapshot::datasetType)
                .containsExactly("ETC_INFO", "COMPLEX", "SCHEDULE", "RECEPTION", "ANNOUNCEMENT_FILE", "COMPLEX_IMAGE");
        assertThat(rows.get(0).correctionReason()).isEqualTo(" 정정 ");
        assertThat(rows.get(0).etcContents()).isEqualTo(" 기타 \n 내용 ");
        assertThat(rows.get(1)).satisfies(row -> {
            assertThat(row.complexName()).isEqualTo(" 단지 ");
            assertThat(row.address()).isEqualTo(" 주소 ");
            assertThat(row.detailAddress()).isEqualTo(" 상세 ");
            assertThat(row.totalUnitCount()).isEqualTo("0100");
            assertThat(row.heatingDescription()).isEqualTo(" 난방 ");
            assertThat(row.exclusiveAreaRange()).isEqualTo(" 59.000 ");
            assertThat(row.expectedMoveInYearMonth()).isEqualTo("202701");
            assertThat(row.guidanceText()).isEqualTo(" 안내 ");
        });
        assertThat(rows.get(2)).satisfies(row -> {
            assertThat(row.applicationPeriod()).isEqualTo(" 기간 ");
            assertThat(row.applicationBeginDate()).isEqualTo("20261001");
            assertThat(row.applicationEndDate()).isEqualTo("20261002");
            assertThat(row.winnerAnnouncementDate()).isEqualTo("20261003");
            assertThat(row.documentTargetAnnouncementDate()).isEqualTo("20261004");
            assertThat(row.documentSubmissionBeginDate()).isEqualTo("20261005");
            assertThat(row.documentSubmissionEndDate()).isEqualTo("20261006");
            assertThat(row.contractBeginDate()).isEqualTo("20261007");
            assertThat(row.contractEndDate()).isEqualTo("20261008");
        });
        assertThat(rows.get(3)).satisfies(row -> {
            assertThat(row.receptionAddress()).isEqualTo(" 접수 주소 ");
            assertThat(row.receptionDetailAddress()).isEqualTo(" 상세 주소 ");
            assertThat(row.operationBegin()).isEqualTo(" 시작 ");
            assertThat(row.operationEnd()).isEqualTo(" 종료 ");
            assertThat(row.phone()).isEqualTo(" 전화 ");
            assertThat(row.receptionGuidance()).isEqualTo(" 안내 ");
        });
        assertThat(rows.get(4)).satisfies(row -> {
            assertThat(row.kind()).isEqualTo(" 종류 ");
            assertThat(row.name()).isEqualTo(" 파일 ");
            assertThat(row.url()).isEqualTo(" 파일 URL ");
        });
        assertThat(rows.get(5)).satisfies(row -> {
            assertThat(row.kind()).isEqualTo(" 사진 ");
            assertThat(row.name()).isEqualTo(" 이름 ");
            assertThat(row.url()).isEqualTo(" 사진 URL ");
            assertThat(row.attachmentComplexName()).isEqualTo(" 단지 ");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[{\"dsSbd\":[{\"LCC_NT_NM\":\"단지\",\"LGDN_ADR\":{}}]}]",
            "[{\"dsSplScdl\":[{\"ACP_DTTM\":[]}]}]",
            "[{\"dsAhflInfo\":[{\"CMN_AHFL_NM\":\"파일\",\"AHFL_URL\":{}}]}]",
            "[{\"dsSbd\":[{\"LCC_NT_NM\":\"단지\"}]},{\"dsSbd\":[]}]",
            "[{\"dsSbd\":{}}]"
    })
    void malformedFieldsAndDuplicateDatasetsCannotBecomeDetailSources(String payload) {
        assertThatThrownBy(() -> detail.parse(mapper.readTree(payload)))
                .isInstanceOf(ExternalDataRequestException.class);
    }
}
