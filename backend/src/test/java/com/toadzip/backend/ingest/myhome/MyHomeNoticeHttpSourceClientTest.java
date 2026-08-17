package com.toadzip.backend.ingest.myhome;

import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.toadzip.backend.ingest.openapi.DataGoKrOpenApiClient;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MyHomeNoticeHttpSourceClientTest {

	private MockRestServiceServer server;

	private MyHomeNoticeSourceClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		DataGoKrOpenApiClient openApiClient = new DataGoKrOpenApiClient(builder.build(), JsonMapper.builder().build(),
				"https://example.com", "service-key", "마이홈 공고");
		client = new MyHomeNoticeHttpSourceClient(openApiClient);
	}

	@Test
	@DisplayName("공급유형 코드와 페이지 조건으로 공고 원천 행을 조회한다")
	void fetchesNoticeSourcePage() {
		server
			.expect(requestTo("https://example.com/rsdtRcritNtcList?serviceKey=service-key&suplyTy=10"
					+ "&pageNo=2&numOfRows=50"))
			.andRespond(withSuccess(successResponse(), MediaType.APPLICATION_JSON));

		var rows = client.fetch(new MyHomeNoticePageRequest(MyHomeNoticeSupplyType.HAPPY_HOUSE, 2, 50));

		assertThat(rows).singleElement().satisfies(row -> {
			assertThat(row.pblancId()).isEqualTo("20989");
			assertThat(row.houseSn()).isEqualTo(1);
			assertThat(row.pblancNm()).isEqualTo("행복주택 모집공고");
			assertThat(row.totHshldCo()).isEqualTo("100");
			assertThat(row.rentGtn()).isEqualTo(10_000_000L);
		});
		server.verify();
	}

	@Test
	@DisplayName("공고 조회 공급유형은 허용된 일곱 코드만 제공한다")
	void providesOnlyApprovedSupplyTypeCodes() {
		assertThat(Arrays.stream(MyHomeNoticeSupplyType.values()).map(MyHomeNoticeSupplyType::requestCode))
			.containsExactly("01", "02", "03", "05", "06", "10", "12");
	}

	@Test
	@DisplayName("공고 페이지 번호와 크기는 양수여야 한다")
	void rejectsInvalidPageConditions() {
		assertThatThrownBy(() -> new MyHomeNoticePageRequest(MyHomeNoticeSupplyType.NATIONAL_RENTAL, 0, 10))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new MyHomeNoticePageRequest(MyHomeNoticeSupplyType.NATIONAL_RENTAL, 1, 0))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private String successResponse() {
		return """
				{
				  "response": {
				    "header": {"resultCode": "00", "resultMsg": "NORMAL SERVICE"},
				    "body": {
				      "item": {
				        "pblancId": "20989",
				        "houseSn": 1,
				        "pblancNm": "행복주택 모집공고",
				        "totHshldCo": 100,
				        "rentGtn": 10000000
				      }
				    }
				  }
				}
				""";
	}

}
