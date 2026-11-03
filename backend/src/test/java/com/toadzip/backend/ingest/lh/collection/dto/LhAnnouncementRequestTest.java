package com.toadzip.backend.ingest.lh.collection.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.ingest.lh.collection.dto.LhAnnouncementRequest;
import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LhAnnouncementRequestTest {

    @Test
    @DisplayName("마이홈 공고 URL에서 LH 조회 조건을 추출한다")
    void extractsLhRequestParameters() {
        var request = LhAnnouncementRequest.from(
                URI.create("https://apply.lh.or.kr/panDetail?panId=100&ccrCnntSysDsCd=03"
                        + "&uppAisTpCd=06&aisTpCd=06"),
                "063"
        ).orElseThrow();

        assertThat(request.panId()).isEqualTo("100");
        assertThat(request.query().parameters()).containsEntry("PAN_ID", "100").containsEntry("AIS_TP_CD", "06")
                .doesNotContainKeys("PG_SZ", "PAGE");
        assertThat(request.requestDescription()).isEqualTo(
                "PAN_ID=100&CCR_CNNT_SYS_DS_CD=03&UPP_AIS_TP_CD=06"
                        + "&SPL_INF_TP_CD=063&AIS_TP_CD=06&COLLECTION_VERSION=6"
        );
    }

    @Test
    @DisplayName("필수 조회 조건이 없으면 LH 조회 조건을 만들지 않는다")
    void rejectsIncompleteLhRequestParameters() {
        assertThat(LhAnnouncementRequest.from(URI.create("https://example.com/panDetail?panId=100"), "063"))
                .isEmpty();
    }
}
