package com.toadzip.backend.ingest.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LhNoticeRequestTest {

    @Test
    @DisplayName("마이홈 공고 URL에서 LH 조회 조건을 추출한다")
    void extractsLhRequestParameters() {
        var request = LhNoticeRequest.from(
                URI.create("https://apply.lh.or.kr/panDetail?panId=100&ccrCnntSysDsCd=03"
                        + "&uppAisTpCd=06&aisTpCd=06"),
                "063"
        ).orElseThrow();

        assertThat(request.panId()).isEqualTo("100");
        assertThat(request.toParams()).containsEntry("PAN_ID", java.util.List.of("100"));
        assertThat(request.requestDescription()).contains("PAN_ID=100");
    }

    @Test
    @DisplayName("필수 조회 조건이 없으면 LH 조회 조건을 만들지 않는다")
    void rejectsIncompleteLhRequestParameters() {
        assertThat(LhNoticeRequest.from(URI.create("https://example.com/panDetail?panId=100"), "063"))
                .isEmpty();
    }
}
