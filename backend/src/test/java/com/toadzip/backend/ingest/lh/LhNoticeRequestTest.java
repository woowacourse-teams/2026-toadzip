package com.toadzip.backend.ingest.lh;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LhNoticeRequestTest {

	@Test
	@DisplayName("LH 상세 URL에서 두 API 요청 파라미터를 만든다")
	void createsRequestFromDetailUrl() {
		LhNoticeRequest request = LhNoticeRequest.from(
				URI.create("https://apply.lh.or.kr/?panId=P1&ccrCnntSysDsCd=01&uppAisTpCd=06&aisTpCd=01"),
				"062").orElseThrow();

		assertThat(request.toParams()).containsEntry("PAN_ID", java.util.List.of("P1"))
				.containsEntry("SPL_INF_TP_CD", java.util.List.of("062"));
	}

	@Test
	@DisplayName("필수 식별자가 없는 URL은 요청을 만들지 않는다")
	void rejectsIncompleteDetailUrl() {
		assertThat(LhNoticeRequest.from(URI.create("https://example.com/?panId=P1"), "062")).isEmpty();
	}
}
